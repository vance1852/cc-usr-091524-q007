package com.admin.equipment.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 授权相关集成测试基类：启动完整 Spring 上下文（H2）+ 真实过滤器链，构造带真实 JWT 的请求。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractApiIT {

    protected static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected TestDataSetup data;

    protected String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        return tree(r).get("access_token").asText();
    }

    /** 按 UTF-8 解析响应 JSON，避免 MockMvc 默认 ISO-8859-1 导致中文乱码。 */
    protected JsonNode tree(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsByteArray());
    }

    protected MvcResult get(String token, String url, int expectStatus) throws Exception {
        return perform(token, MockMvcRequestBuilders.get(url), expectStatus);
    }

    protected MvcResult post(String token, String url, String body, int expectStatus) throws Exception {
        return perform(token, MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "" : body), expectStatus);
    }

    protected MvcResult patch(String token, String url, String body, int expectStatus) throws Exception {
        return perform(token, MockMvcRequestBuilders.patch(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "" : body), expectStatus);
    }

    protected MvcResult put(String token, String url, String body, int expectStatus) throws Exception {
        return perform(token, MockMvcRequestBuilders.put(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "" : body), expectStatus);
    }

    protected MvcResult delete(String token, String url, int expectStatus) throws Exception {
        return perform(token, MockMvcRequestBuilders.delete(url), expectStatus);
    }

    private MvcResult perform(String token, MockHttpServletRequestBuilder req, int expectStatus) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mockMvc.perform(req)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(expectStatus))
                .andReturn();
    }

    /** 从数组响应中提取每个元素的 id。 */
    protected java.util.List<Long> arrayIds(MvcResult r) throws Exception {
        JsonNode arr = JSON.readTree(r.getResponse().getContentAsString());
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                if (n.has("id")) ids.add(n.get("id").asLong());
            }
        }
        return ids;
    }
}
