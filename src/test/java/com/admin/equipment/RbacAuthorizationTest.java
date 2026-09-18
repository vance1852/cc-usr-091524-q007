package com.admin.equipment;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.InspectionTask;
import com.admin.equipment.testsupport.AbstractApiIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 五类角色 + 班组/区域数据隔离 + 对象级 403 的端到端授权测试。
 * 方法按序执行：只读计数断言在前，会产生数据的写操作在后。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RbacAuthorizationTest extends AbstractApiIT {

    private Equipment equipByCode(String code) {
        return data.equipmentRepo.findAll().stream()
                .filter(e -> e.getCode().equals(code)).findFirst().orElseThrow();
    }

    private WorkOrder orderByTitle(String title) {
        return data.workOrderRepo.findAll().stream()
                .filter(w -> w.getTitle().contains(title)).findFirst().orElseThrow();
    }

    private List<InspectionTask> tasksByTeam(String team) {
        return data.taskRepo.findAll().stream()
                .filter(t -> team.equals(t.getTeamName())).toList();
    }

    private long userId(String username) {
        return data.userRepo.findByUsername(username).orElseThrow().getId();
    }

    // ------------------------------------------------------------------
    // 公开接口 / 凭证
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("健康与登录接口公开；无凭证访问受保护接口 401")
    void publicAndUnauthenticated() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/health"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        // 完全不带 Authorization 头
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/equipments"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        // 伪造/篡改令牌同样 401
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/equipments")
                        .header("Authorization", "Bearer not-a-valid-token"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // 设备：不同角色数据范围一致
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("设备列表与对象级范围：管理员/审计全量，计划员本区域，维修员本区域，巡检员仅任务设备")
    void equipmentScopes() throws Exception {
        String admin = login("admin", "admin123");
        String auditor = login("chenaudit", "123456");
        String planner = login("zhaogcs", "123456");
        String maintainer = login("zhangwb", "123456");
        String wang = login("wangxj", "123456");
        String li = login("lixj", "123456");

        assertEquals(5, arrayIds(get(admin, "/api/equipments", 200)).size());
        assertEquals(5, arrayIds(get(auditor, "/api/equipments", 200)).size());
        // 计划员：注塑A、注塑B、包装 = 3
        var plannerEquips = arrayIds(get(planner, "/api/equipments", 200));
        assertEquals(3, plannerEquips.size());
        assertTrue(plannerEquips.contains(equipByCode("EQ-A").getId()));
        assertTrue(plannerEquips.contains(equipByCode("EQ-C").getId()));
        assertFalse(plannerEquips.contains(equipByCode("EQ-P").getId()));
        // 维修员：动力站、电机房 = 2
        var maintainerEquips = arrayIds(get(maintainer, "/api/equipments", 200));
        assertEquals(2, maintainerEquips.size());
        assertTrue(maintainerEquips.contains(equipByCode("EQ-P").getId()));
        // 王巡检（甲班/planA 机器人）只见 EQ-A
        var wangEquips = arrayIds(get(wang, "/api/equipments", 200));
        assertEquals(List.of(equipByCode("EQ-A").getId()), wangEquips);
        // 李巡检（乙班/planB 泵）只见 EQ-P
        var liEquips = arrayIds(get(li, "/api/equipments", 200));
        assertEquals(List.of(equipByCode("EQ-P").getId()), liEquips);

        // 对象级：存在但越权统一 403（不是 404）
        get(wang, "/api/equipments/" + equipByCode("EQ-P").getId(), 403);
        get(planner, "/api/equipments/" + equipByCode("EQ-P").getId(), 403);
        get(maintainer, "/api/equipments/" + equipByCode("EQ-A").getId(), 403);
        // 不存在仍是 404
        get(admin, "/api/equipments/999999", 404);
        // 有权访问 200
        get(wang, "/api/equipments/" + equipByCode("EQ-A").getId(), 200);
    }

    @Test
    @Order(13)
    @DisplayName("功能级：仅管理员可写设备台账，其余角色写操作 403")
    void equipmentWriteRoles() throws Exception {
        String planner = login("zhaogcs", "123456");
        String auditor = login("chenaudit", "123456");
        String body = "{\"code\":\"EQ-X\",\"name\":\"越权设备\",\"location\":\"动力站\"}";
        post(planner, "/api/equipments", body, 403);
        post(auditor, "/api/equipments", body, 403);
        String admin = login("admin", "admin123");
        post(admin, "/api/equipments", body, 201);
    }

    // ------------------------------------------------------------------
    // 巡检任务：本人/本班组隔离，跨班组 ID 猜测 403
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("巡检任务只暴露本人或本班组；跨班组猜测 ID 返回 403 且无法执行")
    void taskTeamIsolation() throws Exception {
        String wang = login("wangxj", "123456");
        String li = login("lixj", "123456");
        String auditor = login("chenaudit", "123456");

        var wangTasks = arrayIds(get(wang, "/api/inspection/tasks", 200));
        var liTasks = arrayIds(get(li, "/api/inspection/tasks", 200));
        // 王：planA 两个任务（本人1 + 班组1）；李：planB 一个
        assertEquals(2, wangTasks.size());
        assertEquals(1, liTasks.size());
        assertTrue(wangTasks.containsAll(tasksByTeam("甲班巡检组").stream().map(InspectionTask::getId).toList()));
        // 审计员跨区域只读，可见全部 4 个
        assertEquals(4, arrayIds(get(auditor, "/api/inspection/tasks", 200)).size());

        // 李猜王的任务 ID → 403
        Long wangTaskId = tasksByTeam("甲班巡检组").get(0).getId();
        Long liTaskId = tasksByTeam("乙班动力组").get(0).getId();
        get(li, "/api/inspection/tasks/" + wangTaskId, 403);
        get(wang, "/api/inspection/tasks/" + liTaskId, 403);
        // 不存在 → 404
        get(wang, "/api/inspection/tasks/999999", 404);

        // 执行/开始跨班组 → 403；本人任务 → 可开始
        post(li, "/api/inspection/tasks/" + wangTaskId + "/start", "{}", 403);
        post(wang, "/api/inspection/tasks/" + wangTaskId + "/start", "{}", 200);

        // 周巡检组任务（无个人/班组关系）→ 王、李均 403
        Long weeklyTaskId = tasksByTeam("周巡检组").get(0).getId();
        get(wang, "/api/inspection/tasks/" + weeklyTaskId, 403);
        get(li, "/api/inspection/tasks/" + weeklyTaskId, 403);

        // 审计员只读：开始任务 403
        post(auditor, "/api/inspection/tasks/" + liTaskId + "/start", "{}", 403);
    }

    // ------------------------------------------------------------------
    // 工单：维修员获派/本区域，越权 403
    // ------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("工单范围与对象级一致：维修员只见本区域+获派工单，越权处理 403")
    void workOrderIsolation() throws Exception {
        String maintainer = login("zhangwb", "123456");
        String planner = login("zhaogcs", "123456");
        String inspector = login("wangxj", "123456");
        String auditor = login("chenaudit", "123456");

        var mOrders = arrayIds(get(maintainer, "/api/work-orders", 200));
        // 动力站本人派单 + 电机房班组派单 = 2；不含注塑A/包装
        assertEquals(2, mOrders.size());
        assertTrue(mOrders.contains(orderByTitle("动力站派给维修本人").getId()));
        assertTrue(mOrders.contains(orderByTitle("电机房派给维修班组").getId()));
        assertFalse(mOrders.contains(orderByTitle("注塑A区工单").getId()));

        // 计划员：注塑A/注塑B/包装 → 见注塑A、包装两个（动力/电机不可见）
        var pOrders = arrayIds(get(planner, "/api/work-orders", 200));
        assertTrue(pOrders.contains(orderByTitle("注塑A区工单").getId()));
        assertTrue(pOrders.contains(orderByTitle("包装车间工单").getId()));
        assertFalse(pOrders.contains(orderByTitle("动力站派给维修本人").getId()));

        // 巡检员只见其任务设备(EQ-A)相关工单
        var iOrders = arrayIds(get(inspector, "/api/work-orders", 200));
        assertEquals(List.of(orderByTitle("注塑A区工单").getId()), iOrders);

        // 审计员全量只读 4 个
        assertEquals(4, arrayIds(get(auditor, "/api/work-orders", 200)).size());

        // 对象级：维修员 PATCH 注塑A区工单（存在但越权）→ 403
        Long forbidden = orderByTitle("注塑A区工单").getId();
        patch(maintainer, "/api/work-orders/" + forbidden + "/status", "{\"status\":\"done\"}", 403);
        // 巡检员不能流转工单（功能级）→ 403
        patch(inspector, "/api/work-orders/" + orderByTitle("注塑A区工单").getId() + "/status",
                "{\"status\":\"done\"}", 403);
        // 维修员处理本人获派工单 → 200
        patch(maintainer, "/api/work-orders/" + orderByTitle("动力站派给维修本人").getId() + "/status",
                "{\"status\":\"in_progress\"}", 200);
        // 不存在 → 404
        patch(maintainer, "/api/work-orders/999999/status", "{\"status\":\"done\"}", 404);
    }

    // ------------------------------------------------------------------
    // 计划员：本范围计划/模板/派工
    // ------------------------------------------------------------------

    @Test
    @Order(7)
    @DisplayName("计划员只能为本范围计划生成任务、管理本区域巡检点，跨范围 403")
    void plannerScope() throws Exception {
        String planner = login("zhaogcs", "123456");
        // 计划可见：仅含本区域巡检点的 PLAN-A（甲班 robot 在注塑A）；PLAN-B 动力站不可见
        var plans = arrayIds(get(planner, "/api/inspection/plans", 200));
        Long planA = data.planRepo.findByCode("PLAN-A").orElseThrow().getId();
        Long planB = data.planRepo.findByCode("PLAN-B").orElseThrow().getId();
        assertTrue(plans.contains(planA));
        assertFalse(plans.contains(planB));
        get(planner, "/api/inspection/plans/" + planB, 403);

        // 为跨范围计划生成任务 → 403
        post(planner, "/api/inspection/tasks/generate",
                "{\"planId\":" + planB + ",\"assigneeId\":null}", 403);
        // 本范围计划 → 201
        post(planner, "/api/inspection/tasks/generate",
                "{\"planId\":" + planA + ",\"assigneeId\":null}", 201);

        // 巡检点写：动力站点（跨区域）修改 → 403；本区域点可改
        Long pumpPoint = data.pointRepo.findByCode("IP-P").orElseThrow().getId();
        Long aPoint = data.pointRepo.findByCode("IP-A").orElseThrow().getId();
        put(planner, "/api/inspection/points/" + pumpPoint,
                "{\"name\":\"越权改名\",\"location\":\"动力站\"}", 403);
        put(planner, "/api/inspection/points/" + aPoint,
                "{\"name\":\"A区点-改名\",\"location\":\"注塑车间A区\"}", 200);
    }

    // ------------------------------------------------------------------
    // 统计与列表数据范围一致
    // ------------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("仪表盘/巡检统计口径与各角色列表范围一致")
    void statsConsistentWithLists() throws Exception {
        for (String[] cred : new String[][]{
                {"admin", "admin123"}, {"chenaudit", "123456"}, {"zhaogcs", "123456"},
                {"zhangwb", "123456"}, {"wangxj", "123456"}, {"lixj", "123456"}}) {
            String token = login(cred[0], cred[1]);
            long equipCount = arrayIds(get(token, "/api/equipments", 200)).size();
            long woCount = arrayIds(get(token, "/api/work-orders", 200)).size();
            long taskCount = arrayIds(get(token, "/api/inspection/tasks", 200)).size();

            var dash = tree(get(token, "/api/dashboard/stats", 200));
            assertEquals(equipCount, dash.get("equipment_total").asLong(), "设备总数不一致:" + cred[0]);
            assertEquals(woCount, dash.get("work_order_total").asLong(), "工单总数不一致:" + cred[0]);

            var overview = tree(get(token, "/api/inspection/stats/overview", 200));
            assertEquals(taskCount, overview.get("totalTasks").asLong(), "任务总数不一致:" + cred[0]);
        }
    }

    @Test
    @Order(6)
    @DisplayName("统计中的设备历史/任务轨迹对象级越权返回 403")
    void statsObjectLevel403() throws Exception {
        String li = login("lixj", "123456");
        Long wangTaskId = tasksByTeam("甲班巡检组").get(0).getId();
        get(li, "/api/inspection/stats/tasks/" + wangTaskId + "/trace", 403);
        get(li, "/api/inspection/stats/equipment/" + equipByCode("EQ-A").getId() + "/history", 403);
        // 自己范围 200
        get(li, "/api/inspection/stats/equipment/" + equipByCode("EQ-P").getId() + "/history", 200);
    }

    // ------------------------------------------------------------------
    // 禁用账号 / 权限版本：旧令牌立即失效
    // ------------------------------------------------------------------

    @Test
    @Order(8)
    @DisplayName("禁用账号后旧令牌立即 401，且无法重新登录")
    void disabledAccountInvalidatesToken() throws Exception {
        String admin = login("admin", "admin123");
        // 管理员新建一个临时巡检员
        String body = "{\"username\":\"tmp_inspect\",\"password\":\"123456\",\"displayName\":\"临时\","
                + "\"role\":\"INSPECTOR\",\"teamName\":\"甲班巡检组\",\"managedAreas\":\"\"}";
        post(admin, "/api/admin/users", body, 201);
        String token = login("tmp_inspect", "123456");
        get(token, "/api/equipments", 200);
        long id = userId("tmp_inspect");
        // 禁用
        put(admin, "/api/admin/users/" + id, "{\"enabled\":false}", 200);
        // 旧令牌立即失效
        get(token, "/api/equipments", 401);
        // 重新登录被拒
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"tmp_inspect\",\"password\":\"123456\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    @Order(9)
    @DisplayName("权限变更（角色/区域/撤销）自增版本，旧令牌立即 401；重新登录获得新范围")
    void permissionChangeInvalidatesToken() throws Exception {
        String admin = login("admin", "admin123");
        String body = "{\"username\":\"tmp_scope\",\"password\":\"123456\",\"displayName\":\"临时范围\","
                + "\"role\":\"MAINTAINER\",\"teamName\":\"维修一班\",\"managedAreas\":\"动力站\"}";
        post(admin, "/api/admin/users", body, 201);
        String token = login("tmp_scope", "123456");
        // 初始仅动力站设备
        assertEquals(1, arrayIds(get(token, "/api/equipments", 200)).size());
        long id = userId("tmp_scope");
        // 调整可管理区域 → 版本自增
        put(admin, "/api/admin/users/" + id, "{\"managedAreas\":\"动力站,电机房\"}", 200);
        // 旧令牌立即失效
        get(token, "/api/equipments", 401);
        // 重新登录后范围扩大
        String token2 = login("tmp_scope", "123456");
        assertEquals(2, arrayIds(get(token2, "/api/equipments", 200)).size());
        // 撤销接口同样令旧令牌失效
        post(admin, "/api/admin/users/" + id + "/revoke", "", 200);
        get(token2, "/api/equipments", 401);
    }

    @Test
    @Order(10)
    @DisplayName("种子中已禁用账号无法登录（403）")
    void seededDisabledCannotLogin() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"disabled\",\"password\":\"123456\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    // ------------------------------------------------------------------
    // 管理员人员授权接口
    // ------------------------------------------------------------------

    @Test
    @Order(11)
    @DisplayName("人员授权仅管理员可用；视图不泄露密码哈希；内置管理员受保护")
    void adminOnlyUserManagement() throws Exception {
        String planner = login("zhaogcs", "123456");
        String auditor = login("chenaudit", "123456");
        get(planner, "/api/admin/users", 403);
        get(auditor, "/api/admin/users", 403);

        String admin = login("admin", "admin123");
        var resp = get(admin, "/api/admin/users", 200);
        String json = new String(resp.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"role\""));
        assertFalse(json.contains("passwordHash"), "用户列表不得泄露密码哈希");
        assertFalse(json.contains("password_hash"), "用户列表不得泄露密码哈希");

        long adminId = userId("admin");
        // 内置管理员不可禁用、不可降级
        put(admin, "/api/admin/users/" + adminId, "{\"enabled\":false}", 422);
        put(admin, "/api/admin/users/" + adminId, "{\"role\":\"INSPECTOR\"}", 422);
        // 非管理员创建用户 403
        post(planner, "/api/admin/users", "{\"username\":\"x\",\"password\":\"y\"}", 403);
    }

    @Test
    @Order(12)
    @DisplayName("登录返回角色/班组/区域快照，/me 与数据库一致")
    void loginSnapshotAndMe() throws Exception {
        var loginResp = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"zhangwb\",\"password\":\"123456\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        var node = tree(loginResp);
        assertEquals("MAINTAINER", node.get("role").asText());
        assertEquals("维修一班", node.get("team_name").asText());
        assertTrue(node.get("managed_areas").toString().contains("动力站"));

        String token = node.get("access_token").asText();
        var me = tree(get(token, "/api/auth/me", 200));
        assertEquals("MAINTAINER", me.get("role").asText());
        assertEquals(0L, me.get("permission_version").asLong());
    }

    @Test
    @Order(14)
    @DisplayName("审计员跨区域只读：可查看全部但任何写操作均 403；跨班组猜巡检点执行 403")
    void auditorReadOnlyAndPointGuessing() throws Exception {
        String auditor = login("chenaudit", "123456");
        // 只读：可查看全量设备/工单/任务/计划/模板/统计（数量与库一致，含其它用例新建数据）
        assertEquals(data.equipmentRepo.count(), arrayIds(get(auditor, "/api/equipments", 200)).size());
        get(auditor, "/api/inspection/stats/overview", 200);
        // 各类写操作一律 403
        post(auditor, "/api/inspection/tasks/generate", "{\"planId\":1}", 403);
        post(auditor, "/api/inspection/tasks/1/start", "{}", 403);
        post(auditor, "/api/inspection/tasks/execute",
                "{\"taskId\":1,\"taskPointId\":1}", 403);
        post(auditor, "/api/inspection/tasks/skip",
                "{\"taskId\":1,\"taskPointId\":1}", 403);
        post(auditor, "/api/inspection/tasks/abnormality/report",
                "{\"taskId\":1}", 403);
        post(auditor, "/api/inspection/tasks/abnormality/recheck",
                "{\"abnormalityId\":1,\"result\":\"passed\"}", 403);
        post(auditor, "/api/inspection/templates",
                "{\"code\":\"X\",\"name\":\"越权模板\"}", 403);
        post(auditor, "/api/inspection/plans",
                "{\"code\":\"X\",\"name\":\"越权计划\"}", 403);
        post(auditor, "/api/work-orders",
                "{\"equipmentId\":1,\"title\":\"越权工单\"}", 403);
        post(auditor, "/api/inspection/tasks/detect-missed-timeout", "", 403);

        // 跨班组猜测巡检点执行：李拿到自己任务点，去王的任务点上执行 → 403
        String li = login("lixj", "123456");
        Long liTaskId = tasksByTeam("乙班动力组").get(0).getId();
        Long wangTaskId = tasksByTeam("甲班巡检组").get(0).getId();
        // 王任务下的巡检点（李无权读 → 直接读点记录 403）
        var wangPoints = JSON.readTree(get(login("wangxj", "123456"),
                "/api/inspection/tasks/" + wangTaskId + "/points", 200).getResponse().getContentAsByteArray());
        long wangTaskPointId = wangPoints.get(0).get("id").asLong();
        // 李用自己任务ID + 王的巡检点ID → 点不属于任务/无权 → 403
        post(li, "/api/inspection/tasks/execute",
                "{\"taskId\":" + liTaskId + ",\"taskPointId\":" + wangTaskPointId + "}", 403);
        // 李用王的任务ID + 王的巡检点ID → 任务不属于李 → 403
        post(li, "/api/inspection/tasks/execute",
                "{\"taskId\":" + wangTaskId + ",\"taskPointId\":" + wangTaskPointId + "}", 403);
        // 李读取王巡检点下的记录 → 403
        get(li, "/api/inspection/tasks/points/" + wangTaskPointId + "/records", 403);
    }
}
