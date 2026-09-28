package com.github.xingjianluo.datacontractgovernancejava.tests;

import com.github.xingjianluo.datacontractgovernancejava.api.ContractController;
import com.github.xingjianluo.datacontractgovernancejava.api.GovernanceExceptionHandler;
import com.github.xingjianluo.datacontractgovernancejava.compat.CompatibilityChecker;
import com.github.xingjianluo.datacontractgovernancejava.registry.ContractRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 演进预检/注册的 HTTP 层契约：预检 200 且只读，拒绝 409 且按变更种类给出依据。
 */
class EvolutionApiTest {

    private static final Logger log = LoggerFactory.getLogger(EvolutionApiTest.class);

    private MockMvc mvc;
    private ContractRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ContractRegistry(new CompatibilityChecker());
        mvc = MockMvcBuilders.standaloneSetup(new ContractController(registry))
                .setControllerAdvice(new GovernanceExceptionHandler())
                .build();
    }

    @Test
    void precheckReturns200AndDoesNotPersist() throws Exception {
        String v1 = """
                {"name":"orders","compatibility":"BACKWARD","fields":[
                  {"name":"id","type":"long","required":true,"sensitivity":"PUBLIC"}]}
                """;
        mvc.perform(post("/api/contracts").contentType(MediaType.APPLICATION_JSON).content(v1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.version").value(1));

        // 破坏性候选（BACKWARD 新增必填字段）：预检仍返回 200，status=REJECTED
        String bad = """
                {"name":"orders","compatibility":"BACKWARD","fields":[
                  {"name":"id","type":"long","required":true,"sensitivity":"PUBLIC"},
                  {"name":"code","type":"string","required":true,"sensitivity":"PUBLIC"}]}
                """;
        mvc.perform(post("/api/contracts/precheck").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.resolvedVersion").doesNotExist())
                .andExpect(jsonPath("$.violationsByType.FIELD_ADDED[0].field").value("code"));

        log.info("[HTTP预检] REJECTED 预检返回 200 且含 violationsByType.FIELD_ADDED");

        // 预检不落库：历史仍只有 v1
        mvc.perform(get("/api/contracts/orders/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 同样内容正式注册 -> 409，响应体带分组依据，与预检一致
        mvc.perform(post("/api/contracts").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("INCOMPATIBLE_SCHEMA"))
                .andExpect(jsonPath("$.violationsByType.FIELD_ADDED[0].field").value("code"));
        log.info("[HTTP注册] 正式拒绝返回 409，依据与预检一致");
    }

    @Test
    void historicalReplayViaApiReturnsIdempotentStatus() throws Exception {
        String v1 = """
                {"name":"orders","compatibility":"BACKWARD","fields":[
                  {"name":"id","type":"long","required":true,"sensitivity":"PUBLIC"}]}
                """;
        String v2 = """
                {"name":"orders","compatibility":"BACKWARD","fields":[
                  {"name":"id","type":"long","required":true,"sensitivity":"PUBLIC"},
                  {"name":"note","type":"string","required":false,"sensitivity":"PUBLIC"}]}
                """;
        mvc.perform(post("/api/contracts").contentType(MediaType.APPLICATION_JSON).content(v1))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(post("/api/contracts").contentType(MediaType.APPLICATION_JSON).content(v2))
                .andExpect(jsonPath("$.version").value(2));

        // 补投 v1：预检与注册都应收敛到版本 1
        mvc.perform(post("/api/contracts/precheck").contentType(MediaType.APPLICATION_JSON).content(v1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDEMPOTENT_REPLAY"))
                .andExpect(jsonPath("$.resolvedVersion").value(1));
        mvc.perform(post("/api/contracts").contentType(MediaType.APPLICATION_JSON).content(v1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDEMPOTENT_REPLAY"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/contracts/orders/versions"))
                .andExpect(jsonPath("$.length()").value(2));
        log.info("[HTTP历史重放] 补投v1预检/注册均收敛到版本1，历史仍为2个版本");
    }
}
