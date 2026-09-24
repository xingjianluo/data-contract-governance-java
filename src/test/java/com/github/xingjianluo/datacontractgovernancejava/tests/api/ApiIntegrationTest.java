package com.github.xingjianluo.datacontractgovernancejava.tests.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.datacontractgovernancejava.contract.store.ContractStore;
import com.github.xingjianluo.datacontractgovernancejava.lineage.store.LineageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 从 HTTP 入口验证注册结论、拒绝原因码、幂等与血缘传播在接口层保持一致。 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ContractStore contractStore;
    @Autowired
    private LineageStore lineageStore;

    @BeforeEach
    void clean() {
        contractStore.clear();
        lineageStore.clear();
    }

    private Map<String, Object> field(String name, String type, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        m.put("required", required);
        return m;
    }

    private String contractBody(String name, int version, String mode,
                                 List<Map<String, Object>> fields) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contractName", name);
        body.put("version", version);
        body.put("mode", mode);
        body.put("fields", fields);
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void registerThenReplayThenIncompatibleReject() throws Exception {
        String v1 = contractBody("orders", 1, "BACKWARD",
                List.of(field("id", "LONG", true), field("note", "STRING", false)));
        log.info("[API] register v1 input={}", v1);
        mockMvc.perform(post("/api/contracts/register").contentType(MediaType.APPLICATION_JSON).content(v1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CREATED"));

        // 幂等重放
        log.info("[API] replay v1 (identical)");
        mockMvc.perform(post("/api/contracts/register").contentType(MediaType.APPLICATION_JSON).content(v1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IDEMPOTENT_REPLAY"));

        // BACKWARD 下新增必填字段 -> 409 + 明确原因码
        String bad = contractBody("orders", 2, "BACKWARD",
                List.of(field("id", "LONG", true), field("note", "STRING", false),
                        field("new_required", "STRING", true)));
        log.info("[API] register incompatible v2 input={}", bad);
        MvcResult result = mockMvc
                .perform(post("/api/contracts/register").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isConflict())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        log.warn("[API] rejected response={}", json);
        assertTrue(json.get("rejectionCode").asText().equals("INCOMPATIBLE_CHANGE"));
        assertTrue(json.get("message").asText().contains("ADDED_REQUIRED_FIELD"));

        // 被拒绝后版本列表里仍只有 v1
        mockMvc.perform(get("/api/contracts/orders/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(1));
    }

    @Test
    void lineageRegisterRejectsCycleAndSensitivityEndpointPropagates() throws Exception {
        registerNode("a", 1, "x", "SECRET");
        registerNode("a", 1, "y", "UNKNOWN");

        String edge = edgeBody("a:1:x", "a:1:y", null);
        log.info("[API] edge input={}", edge);
        mockMvc.perform(post("/api/lineage/edges").contentType(MediaType.APPLICATION_JSON).content(edge))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REGISTERED"));

        // 反向登记成环
        log.info("[API] edge cycle input a:1:y -> a:1:x");
        MvcResult result = mockMvc
                .perform(post("/api/lineage/edges").contentType(MediaType.APPLICATION_JSON)
                        .content(edgeBody("a:1:y", "a:1:x", null)))
                .andExpect(status().isConflict())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        log.warn("[API] cycle rejected response={}", json);
        assertTrue(json.get("rejectionCode").asText().equals("CYCLE_DETECTED"));

        // 敏感级传播可通过接口查询
        mockMvc.perform(get("/api/lineage/nodes/{id}/sensitivity", "a:1:y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveLevel").value("SECRET"));
    }

    private void registerNode(String contract, int version, String field, String level) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contractName", contract);
        body.put("version", version);
        body.put("fieldName", field);
        body.put("declaredSensitivity", level);
        mockMvc.perform(post("/api/lineage/nodes").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private String edgeBody(String source, String target, String transform) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceId", source);
        body.put("targetId", target);
        body.put("transform", transform);
        return objectMapper.writeValueAsString(body);
    }
}
