package dev.safetext2sql.api;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.safetext2sql.execution.QueryResult;
import dev.safetext2sql.llm.GeneratedSql;
import dev.safetext2sql.llm.SqlGenerator;
import dev.safetext2sql.llm.ollama.OllamaHttpException;
import dev.safetext2sql.sql.validation.AnalyticsSqlPolicy;
import dev.safetext2sql.sql.validation.AstSqlValidator;
import dev.safetext2sql.workflow.ExponentialBackoffPolicy;
import dev.safetext2sql.workflow.QueryWorkflowProperties;
import dev.safetext2sql.workflow.Text2SqlQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API가 성공 결과와 내부 원문 없는 안정적인 실패 응답을 만드는지 검증한다. */
class Text2SqlQueryApiTest {

    @Test
    void returnsRowsAndAttemptMetadataWithoutExposingSql() throws Exception {
        MockMvc mockMvc = mockMvc(request -> new GeneratedSql(
                "SELECT order_id FROM orders ORDER BY order_id LIMIT 1", "fake-model"));

        mockMvc.perform(post("/api/v1/text2sql/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"첫 주문을 알려줘\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.columns[0]").value("order_id"))
                .andExpect(jsonPath("$.rows[0][0]").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.attemptCount").value(1))
                .andExpect(jsonPath("$.elapsedMs").isNumber())
                .andExpect(jsonPath("$.sql").doesNotExist());
    }

    @Test
    void returnsBadRequestForBlankOrMalformedQuestions() throws Exception {
        MockMvc mockMvc = mockMvc(request -> new GeneratedSql(
                "SELECT order_id FROM orders LIMIT 1", "fake-model"));

        mockMvc.perform(post("/api/v1/text2sql/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUESTION"))
                .andExpect(jsonPath("$.attemptCount").value(0));

        mockMvc.perform(post("/api/v1/text2sql/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUESTION"));
    }

    @Test
    void mapsLlmClientErrorsToAStableBadGatewayResponse() throws Exception {
        MockMvc mockMvc = mockMvc(request -> {
            throw new OllamaHttpException(400);
        });

        mockMvc.perform(post("/api/v1/text2sql/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"주문을 알려줘\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LLM_REQUEST_REJECTED"))
                .andExpect(jsonPath("$.attemptCount").value(1))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.sql").doesNotExist());
    }

    @Test
    void returnsBadGatewayAfterExactlyThreeServerErrors() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MockMvc mockMvc = mockMvc(request -> {
            calls.incrementAndGet();
            throw new OllamaHttpException(500);
        });

        mockMvc.perform(post("/api/v1/text2sql/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"주문을 알려줘\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LLM_UNAVAILABLE"))
                .andExpect(jsonPath("$.attemptCount").value(3))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.sql").doesNotExist());

        assertThat(calls).hasValue(3);
    }

    private MockMvc mockMvc(SqlGenerator generator) {
        Text2SqlQueryService service = new Text2SqlQueryService(
                generator,
                new AstSqlValidator(AnalyticsSqlPolicy.defaultPolicy()),
                validated -> new QueryResult(List.of("order_id"), List.of(List.of(1L)), false),
                new QueryWorkflowProperties(3, Duration.ofMillis(200), 1_000),
                new ExponentialBackoffPolicy(Duration.ofMillis(200)),
                duration -> { },
                System::nanoTime
        );
        return MockMvcBuilders.standaloneSetup(new Text2SqlQueryController(service))
                .setControllerAdvice(new Text2SqlExceptionHandler())
                .build();
    }
}
