package com.admin.equipment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端授权边界测试（真实 HTTP 栈 + H2 + 种子数据）。
 * 覆盖：五角色登录、设备/工单/任务/统计数据范围一致、对象级越权 403（而非 404）、
 * 禁用账号与权限变更令旧令牌失效、跨班组/跨区域 ID 猜测被拒。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthorizationIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private final Map<String, String> tokens = new LinkedHashMap<>();
    private final Map<String, Long> userIds = new LinkedHashMap<>();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders auth(String who) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (who != null) h.setBearerAuth(tokens.get(who));
        return h;
    }

    private ResponseEntity<String> get(String who, String path) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(auth(who)), String.class);
    }

    private ResponseEntity<String> post(String who, String path, Object body) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, auth(who)), String.class);
    }

    private ResponseEntity<String> patch(String who, String path, Object body) {
        return rest.exchange(url(path), HttpMethod.PATCH, new HttpEntity<>(body, auth(who)), String.class);
    }

    private ResponseEntity<String> put(String who, String path, Object body) {
        return rest.exchange(url(path), HttpMethod.PUT, new HttpEntity<>(body, auth(who)), String.class);
    }

    private String login(String username, String password) {
        Map<String, String> body = Map.of("username", username, "password", password);
        ResponseEntity<String> resp = post(null, "/api/auth/login", body);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "登录应成功: " + username + " -> " + resp.getBody());
        try {
            return mapper.readTree(resp.getBody()).get("access_token").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void loginAll() {
        if (!tokens.isEmpty()) return;
        // 公开接口无需令牌
        ResponseEntity<String> health = get(null, "/api/health");
        assertEquals(HttpStatus.OK, health.getStatusCode());
        ResponseEntity<String> noToken = get(null, "/api/equipments");
        assertEquals(HttpStatus.UNAUTHORIZED, noToken.getStatusCode());

        tokens.put("admin", login("admin", "admin123"));
        tokens.put("plannerAll", login("zhaogcs", "123456"));   // 计划员，全厂区域
        tokens.put("plannerInj", login("sunjh", "123456"));   // 计划员，仅注塑 A/B
        tokens.put("wang", login("wangxj", "123456"));        // 巡检员 甲班 注塑A
        tokens.put("li", login("lixj", "123456"));            // 巡检员 乙班 动力站
        tokens.put("zhang", login("zhangwb", "123456"));      // 维修员 动力站,包装车间
        tokens.put("qian", login("qiansj", "123456"));        // 审计员 跨区域只读

        // 解析用户ID
        ResponseEntity<String> users = get("admin", "/api/admin/users");
        assertEquals(HttpStatus.OK, users.getStatusCode());
        try {
            for (JsonNode u : mapper.readTree(users.getBody())) {
                userIds.put(u.get("username").asText(), u.get("id").asLong());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // 便捷别名
        userIds.put("wang", userIds.get("wangxj"));
        userIds.put("li", userIds.get("lixj"));
        userIds.put("zhang", userIds.get("zhangwb"));
    }

    private long count(String jsonArrayBody) {
        try {
            return mapper.readTree(jsonArrayBody).size();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // 登录返回角色快照
    // ------------------------------------------------------------------
    @Test
    @Order(1)
    void loginReturnsRoleSnapshotAndMeMatches() {
        Map<String, String> body = Map.of("username", "wangxj", "password", "123456");
        ResponseEntity<String> resp = post(null, "/api/auth/login", body);
        try {
            JsonNode node = mapper.readTree(resp.getBody());
            assertEquals("INSPECTOR", node.get("role").asText());
            assertEquals("甲班巡检组", node.get("team_name").asText());
            assertTrue(node.get("managed_areas").asText().contains("注塑车间A区"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ResponseEntity<String> me = get("wang", "/api/auth/me");
        assertEquals(HttpStatus.OK, me.getStatusCode());
        assertTrue(me.getBody().contains("INSPECTOR"));
    }

    // ------------------------------------------------------------------
    // 设备数据范围
    // ------------------------------------------------------------------
    @Test
    @Order(2)
    void equipmentScopeConsistentAcrossRoles() {
        assertEquals(8, count(get("admin", "/api/equipments").getBody()));
        assertEquals(8, count(get("qian", "/api/equipments").getBody()));       // 审计跨区域
        assertEquals(2, count(get("plannerInj", "/api/equipments").getBody())); // 注塑A/B
        assertEquals(1, count(get("wang", "/api/equipments").getBody()));       // 仅注塑A

        // 王巡检可读本区域设备 EQ-1001(id=1)
        assertEquals(HttpStatus.OK, get("wang", "/api/equipments/1").getStatusCode());
        // 跨区域猜测动力站设备 EQ-1002(id=2)：存在但无权 -> 403，不是 404
        ResponseEntity<String> forbidden = get("wang", "/api/equipments/2");
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        // 不存在的 ID 仍是 404，二者不矛盾
        assertEquals(HttpStatus.NOT_FOUND, get("wang", "/api/equipments/99999").getStatusCode());

        // 接口级角色门槛：巡检员/审计员/维修员不能建设备
        Map<String, Object> newEq = Map.of("code", "EQ-X", "name", "x", "area", "电机房");
        assertEquals(HttpStatus.FORBIDDEN, post("wang", "/api/equipments", newEq).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, post("qian", "/api/equipments", newEq).getStatusCode());
    }

    // ------------------------------------------------------------------
    // 工单数据范围 + 维修员处理边界
    // ------------------------------------------------------------------
    @Test
    @Order(3)
    void workOrderScopeAndTransition() {
        // 种子工单：动力站2(含指派张)、包装车间1(指派张)、注塑A1
        assertEquals(4, count(get("admin", "/api/work-orders").getBody()));
        assertEquals(4, count(get("qian", "/api/work-orders").getBody()));
        assertEquals(3, count(get("zhang", "/api/work-orders").getBody())); // 动力站+包装+指派
        assertEquals(1, count(get("wang", "/api/work-orders").getBody()));  // 仅注塑A(o4,id=4)

        // 王巡检猜测动力站工单 id=1 -> 403
        assertEquals(HttpStatus.FORBIDDEN, get("wang", "/api/work-orders/1").getStatusCode());
        // 维修员可读本区域工单
        assertEquals(HttpStatus.OK, get("zhang", "/api/work-orders/1").getStatusCode());

        // 巡检员不能流转工单
        ResponseEntity<String> inspectTransition = patch("wang", "/api/work-orders/4/status",
                Map.of("status", "done"));
        assertEquals(HttpStatus.FORBIDDEN, inspectTransition.getStatusCode());
        // 维修员不能流转注塑A工单(跨区域)
        ResponseEntity<String> crossTransition = patch("zhang", "/api/work-orders/4/status",
                Map.of("status", "done"));
        assertEquals(HttpStatus.FORBIDDEN, crossTransition.getStatusCode());
        // 维修员可流转本区域工单
        ResponseEntity<String> okTransition = patch("zhang", "/api/work-orders/1/status",
                Map.of("status", "in_progress"));
        assertEquals(HttpStatus.OK, okTransition.getStatusCode());

        // 列表过滤与状态：工单1 已被改为 in_progress，维修员按 status 过滤仍受范围约束
        ResponseEntity<String> openList = get("zhang", "/api/work-orders?status=open");
        assertEquals(HttpStatus.OK, openList.getStatusCode());
        try {
            for (JsonNode o : mapper.readTree(openList.getBody())) {
                // 全部必须在张的可见范围内（不会借过滤参数绕出范围）
                assertNotEquals(4L, o.get("id").asLong());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // 巡检任务：分配/班组 隔离 + 跨班组 ID 猜测
    // ------------------------------------------------------------------
    @Test
    @Order(4)
    void taskIsolationByAssigneeAndTeam() {
        // 计划员赵(全厂)基于 plan2(动力站/乙班) 生成派给李巡检的任务
        Map<String, Object> genLi = Map.of(
                "planId", 2, "assigneeId", userIds.get("lixj"), "assigneeName", "李巡检",
                "useOptimizedRoute", true);
        ResponseEntity<String> tLi = post("plannerAll", "/api/inspection/tasks/generate", genLi);
        assertEquals(HttpStatus.CREATED, tLi.getStatusCode(), tLi.getBody());
        long liTaskId;
        try {
            liTaskId = mapper.readTree(tLi.getBody()).get("id").asLong();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 基于 plan1(注塑A/甲班) 生成派给王巡检的任务
        Map<String, Object> genWang = Map.of(
                "planId", 1, "assigneeId", userIds.get("wangxj"), "assigneeName", "王巡检");
        ResponseEntity<String> tWang = post("plannerAll", "/api/inspection/tasks/generate", genWang);
        assertEquals(HttpStatus.CREATED, tWang.getStatusCode());
        long wangTaskId;
        try {
            wangTaskId = mapper.readTree(tWang.getBody()).get("id").asLong();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 王巡检只能看到甲班/自己任务，看不到乙班任务
        long wangVisible = count(get("wang", "/api/inspection/tasks").getBody());
        long wangListLi = count(get("wang", "/api/inspection/tasks?assigneeId=" + userIds.get("lixj")).getBody());
        assertEquals(0, wangListLi, "巡检员不能借 assigneeId 过滤看到他人任务");

        // 管理员/审计可见全部
        long adminVisible = count(get("admin", "/api/inspection/tasks").getBody());
        assertTrue(adminVisible >= 2);
        assertEquals(adminVisible, count(get("qian", "/api/inspection/tasks").getBody()));

        // 跨班组 ID 猜测：王直接访问李的任务 -> 403（存在，不是404）
        assertEquals(HttpStatus.FORBIDDEN, get("wang", "/api/inspection/tasks/" + liTaskId).getStatusCode());
        // 不存在 -> 404
        assertEquals(HttpStatus.NOT_FOUND, get("wang", "/api/inspection/tasks/99999").getStatusCode());
        // 也不能开始/执行别人的任务
        assertEquals(HttpStatus.FORBIDDEN,
                post("wang", "/api/inspection/tasks/" + liTaskId + "/start", Map.of()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                post("wang", "/api/inspection/tasks/skip",
                        Map.of("taskId", liTaskId, "taskPointId", 1, "reason", "x")).getStatusCode());

        // 李可以访问并开始自己的任务
        assertEquals(HttpStatus.OK, get("li", "/api/inspection/tasks/" + liTaskId).getStatusCode());
        assertEquals(HttpStatus.OK,
                post("li", "/api/inspection/tasks/" + liTaskId + "/start", Map.of()).getStatusCode());
        // 王可以开始自己的任务
        assertEquals(HttpStatus.OK,
                post("wang", "/api/inspection/tasks/" + wangTaskId + "/start", Map.of()).getStatusCode());

        // 统计执行轨迹同样受保护：王猜测李任务轨迹 -> 403
        assertEquals(HttpStatus.FORBIDDEN,
                get("wang", "/api/inspection/stats/tasks/" + liTaskId + "/trace").getStatusCode());

        // 巡检员不能生成任务/取消任务（角色与对象边界）
        assertEquals(HttpStatus.FORBIDDEN,
                post("wang", "/api/inspection/tasks/generate", Map.of("planId", 1)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                post("wang", "/api/inspection/tasks/" + liTaskId + "/cancel", Map.of()).getStatusCode());
        // 系统调度仅管理员
        assertEquals(HttpStatus.FORBIDDEN,
                post("plannerAll", "/api/inspection/tasks/detect-missed-timeout", Map.of()).getStatusCode());
        assertEquals(HttpStatus.OK,
                post("admin", "/api/inspection/tasks/detect-missed-timeout", Map.of()).getStatusCode());
    }

    // ------------------------------------------------------------------
    // 计划/模板角色门槛与区域
    // ------------------------------------------------------------------
    @Test
    @Order(5)
    void planTemplateGates() {
        // 计划区域：plan1 注塑A、plan2 动力站
        assertEquals(HttpStatus.OK, get("plannerInj", "/api/inspection/plans/1").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, get("plannerInj", "/api/inspection/plans/2").getStatusCode());

        // 王巡检可读本班组计划(plan1 甲班)
        assertEquals(HttpStatus.OK, get("wang", "/api/inspection/plans/1").getStatusCode());

        // 计划员孙不能改动力站计划
        Map<String, Object> update = Map.of("name", "被越权修改");
        assertEquals(HttpStatus.FORBIDDEN, put("plannerInj", "/api/inspection/plans/2", update).getStatusCode());

        // 模板：所有登录角色可读；仅管理员/计划员可写
        assertEquals(HttpStatus.OK, get("wang", "/api/inspection/templates").getStatusCode());
        Map<String, Object> tpl = Map.of("code", "TPL-X", "name", "x");
        assertEquals(HttpStatus.FORBIDDEN, post("wang", "/api/inspection/templates", tpl).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, post("qian", "/api/inspection/templates", tpl).getStatusCode());
        assertEquals(HttpStatus.CREATED, post("plannerInj", "/api/inspection/templates", tpl).getStatusCode());
    }

    // ------------------------------------------------------------------
    // 统计口径与列表一致
    // ------------------------------------------------------------------
    @Test
    @Order(6)
    void statsMatchVisibleScope() {
        long wangEquip = count(get("wang", "/api/equipments").getBody());
        long wangTasks = count(get("wang", "/api/inspection/tasks").getBody());

        ResponseEntity<String> dash = get("wang", "/api/dashboard/stats");
        assertEquals(HttpStatus.OK, dash.getStatusCode());
        try {
            JsonNode d = mapper.readTree(dash.getBody());
            assertEquals(wangEquip, d.get("equipment_total").asLong(),
                    "仪表盘设备总数必须与设备列表范围一致");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ResponseEntity<String> overview = get("wang", "/api/inspection/stats/overview");
        assertEquals(HttpStatus.OK, overview.getStatusCode());
        try {
            JsonNode o = mapper.readTree(overview.getBody());
            assertEquals(wangTasks, o.get("totalTasks").asLong(),
                    "巡检总览任务数必须与任务列表范围一致");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 审计员总览任务数 = 管理员（跨区域只读）
        long adminTasks = count(get("admin", "/api/inspection/tasks").getBody());
        ResponseEntity<String> auditorOverview = get("qian", "/api/inspection/stats/overview");
        try {
            assertEquals(adminTasks, mapper.readTree(auditorOverview.getBody()).get("totalTasks").asLong());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 设备巡检历史：王猜测动力站设备 -> 403
        assertEquals(HttpStatus.FORBIDDEN,
                get("wang", "/api/inspection/stats/equipment/2/history").getStatusCode());
        assertEquals(HttpStatus.OK,
                get("wang", "/api/inspection/stats/equipment/1/history").getStatusCode());
    }

    // ------------------------------------------------------------------
    // 管理员人员授权接口
    // ------------------------------------------------------------------
    @Test
    @Order(7)
    void adminUserManagementProtected() {
        assertEquals(HttpStatus.FORBIDDEN, get("plannerAll", "/api/admin/users").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, get("qian", "/api/admin/users").getStatusCode());
        assertEquals(HttpStatus.OK, get("admin", "/api/admin/users").getStatusCode());
    }

    // ------------------------------------------------------------------
    // 禁用账号：旧令牌立即 401，登录被拒
    // ------------------------------------------------------------------
    @Test
    @Order(8)
    void disabledAccountTokenImmediatelyInvalid() {
        long id = userIds.get("zhang");
        String tokenBefore = tokens.get("zhang");
        // 管理员禁用
        ResponseEntity<String> dis = patch("admin", "/api/admin/users/" + id + "/enabled",
                Map.of("enabled", false));
        assertEquals(HttpStatus.OK, dis.getStatusCode());

        // 旧令牌立即失效
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(tokenBefore);
        ResponseEntity<String> old = rest.exchange(url("/api/equipments"), HttpMethod.GET,
                new HttpEntity<>(h), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, old.getStatusCode());
        // 登录被拒（403）
        ResponseEntity<String> relogin = post(null, "/api/auth/login",
                Map.of("username", "zhangwb", "password", "123456"));
        assertEquals(HttpStatus.FORBIDDEN, relogin.getStatusCode());

        // 恢复
        ResponseEntity<String> en = patch("admin", "/api/admin/users/" + id + "/enabled",
                Map.of("enabled", true));
        assertEquals(HttpStatus.OK, en.getStatusCode());
        // 权限版本已因禁用/启用两次自增，旧令牌仍失效；重新登录恢复
        ResponseEntity<String> old2 = rest.exchange(url("/api/equipments"), HttpMethod.GET,
                new HttpEntity<>(h), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, old2.getStatusCode());
        String fresh = login("zhangwb", "123456");
        tokens.put("zhang", fresh);
        HttpHeaders h2 = new HttpHeaders();
        h2.setBearerAuth(fresh);
        ResponseEntity<String> now = rest.exchange(url("/api/equipments"), HttpMethod.GET,
                new HttpEntity<>(h2), String.class);
        assertEquals(HttpStatus.OK, now.getStatusCode());
    }

    // ------------------------------------------------------------------
    // 权限变更（角色/区域）：旧令牌立即失效
    // ------------------------------------------------------------------
    @Test
    @Order(9)
    void permissionChangeInvalidatesOldToken() {
        long id = userIds.get("wang");
        String oldToken = tokens.get("wang");

        // 管理员调整王的可管理区域 -> 权限版本自增
        ResponseEntity<String> upd = put("admin", "/api/admin/users/" + id,
                Map.of("managedAreas", "动力站"));
        assertEquals(HttpStatus.OK, upd.getStatusCode());

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(oldToken);
        ResponseEntity<String> stale = rest.exchange(url("/api/equipments"), HttpMethod.GET,
                new HttpEntity<>(h), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, stale.getStatusCode(), "权限变更后旧令牌必须立即失效");

        // 用新令牌登录：王现在能看到动力站设备，看不到注塑A
        String fresh = login("wangxj", "123456");
        tokens.put("wang", fresh);
        assertEquals(2, count(get("wang", "/api/equipments").getBody()), "动力站有2台设备");

        // 还原，避免影响其他用例（顺序上本用例靠后）
        put("admin", "/api/admin/users/" + id, Map.of("managedAreas", "注塑车间A区"));
        tokens.put("wang", login("wangxj", "123456"));
    }
}
