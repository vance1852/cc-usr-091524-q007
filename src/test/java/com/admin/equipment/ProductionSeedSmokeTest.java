package com.admin.equipment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用生产 DataSeeder（非 test profile）在 H2 上启动，验证真实种子数据下
 * 五类账号登录、健康接口、管理员可用及跨班组隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("smoke")
class ProductionSeedSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JSON.readTree(r.getResponse().getContentAsByteArray()).get("access_token").asText();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsByteArray());
    }

    @Test
    @DisplayName("健康接口公开；内置管理员 admin/admin123 可用")
    void healthAndAdmin() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
        String admin = login("admin", "admin123");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        // 管理员可见全部 8 台设备
        MvcResult r = mockMvc.perform(get("/api/equipments").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn();
        assertEquals(8, body(r).size());
    }

    @Test
    @DisplayName("五类角色均可登录并取得角色快照")
    void allFiveRolesLogin() throws Exception {
        assertRole("admin", "admin123", "ADMIN");
        assertRole("zhaogcs", "123456", "PLANNER");
        assertRole("wangxj", "123456", "INSPECTOR");
        assertRole("zhangwb", "123456", "MAINTAINER");
        assertRole("chenaudit", "123456", "AUDITOR");
    }

    @Test
    @DisplayName("生产种子下：甲班巡检员只见本人/本班组任务，乙班猜甲班任务 403")
    void teamIsolationOnProductionSeed() throws Exception {
        String wang = login("wangxj", "123456");
        String li = login("lixj", "123456");

        // 王（甲班）的任务列表
        MvcResult wangList = mockMvc.perform(get("/api/inspection/tasks")
                        .header("Authorization", "Bearer " + wang))
                .andExpect(status().isOk()).andReturn();
        JsonNode wangTasks = body(wangList);
        org.junit.jupiter.api.Assertions.assertTrue(wangTasks.size() >= 1, "甲班巡检员应有任务");
        long wangTaskId = wangTasks.get(0).get("id").asLong();

        // 乙班巡检员直取甲班任务 ID → 403（不是 404）
        mockMvc.perform(get("/api/inspection/tasks/" + wangTaskId)
                        .header("Authorization", "Bearer " + li))
                .andExpect(status().isForbidden());
        // 开始甲班任务 → 403
        mockMvc.perform(post("/api/inspection/tasks/" + wangTaskId + "/start")
                        .header("Authorization", "Bearer " + li).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    private void assertRole(String username, String password, String role) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(role, body(r).get("role").asText());
    }

    private static void assertEquals(long expected, long actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
