package dev.safetext2sql.llm.ollama;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.safetext2sql.llm.SqlGenerationRequest;
import dev.safetext2sql.llm.config.OllamaProperties;
import dev.safetext2sql.llm.prompt.SqlPromptTemplate;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OllamaSqlGeneratorWireMockTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WireMockServer wireMock;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    @Test
    void sendsNonStreamingOllamaRequestAndReturnsUntrustedSql() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/generate"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "model": "local-test-model:1",
                                  "response": "SELECT order_id FROM orders",
                                  "done": true
                                }
                                """)));
        var generator = generator(Duration.ofSeconds(1), Duration.ofSeconds(2), 0.25);

        var generated = generator.generate(SqlGenerationRequest.firstAttempt("주문 번호를 알려줘"));

        assertThat(generated.sql()).isEqualTo("SELECT order_id FROM orders");
        assertThat(generated.model()).isEqualTo("local-test-model:1");
        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/generate")));

        var requestJson = objectMapper.readTree(
                wireMock.getAllServeEvents().getFirst().getRequest().getBodyAsString());
        assertThat(requestJson.path("model").asText()).isEqualTo("local-test-model:1");
        assertThat(requestJson.path("stream").asBoolean()).isFalse();
        assertThat(requestJson.path("options").path("temperature").asDouble()).isEqualTo(0.25);
        assertThat(requestJson.path("system").asText())
                .contains("exactly one PostgreSQL SELECT")
                .contains("analytics.orders")
                .contains("untrusted input");
        assertThat(requestJson.path("prompt").asText())
                .contains("--- QUESTION ---\n주문 번호를 알려줘\n--- END QUESTION ---")
                .endsWith("Return SQL only.");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500})
    void mapsHttpErrorsWithoutLeakingResponseBody(int status) {
        wireMock.stubFor(post(urlEqualTo("/api/generate"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"sensitive local server detail\"}")));

        var thrown = catchThrowable(() -> generator(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), 0)
                .generate(SqlGenerationRequest.firstAttempt("주문 수")));

        assertThat(thrown).isInstanceOf(OllamaHttpException.class);
        var httpException = (OllamaHttpException) thrown;
        assertThat(httpException.statusCode()).isEqualTo(status);
        assertThat(httpException.getMessage()).doesNotContain("sensitive local server detail");
        assertThat(httpException.isClientError()).isEqualTo(status == 400);
        assertThat(httpException.isServerError()).isEqualTo(status == 500);
        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/generate")));
    }

    @Test
    void rejectsIncompleteOllamaResponse() {
        wireMock.stubFor(post(urlEqualTo("/api/generate"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"model\":\"local-test-model:1\",\"done\":true}")));

        var thrown = catchThrowable(() -> generator(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), 0)
                .generate(SqlGenerationRequest.firstAttempt("주문 수")));

        assertThat(thrown)
                .isInstanceOf(OllamaProtocolException.class)
                .hasMessage("Ollama returned an incomplete response");
    }

    @Test
    void rejectsMalformedJsonWithoutRetainingRawResponse() {
        wireMock.stubFor(post(urlEqualTo("/api/generate"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"response\":\"sensitive malformed response")));

        var thrown = catchThrowable(() -> generator(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), 0)
                .generate(SqlGenerationRequest.firstAttempt("주문 수")));

        assertThat(thrown)
                .isInstanceOf(OllamaProtocolException.class)
                .hasMessage("Ollama response could not be decoded")
                .hasNoCause();
        assertThat(thrown.getMessage()).doesNotContain("sensitive malformed response");
    }

    @Test
    void appliesConfiguredReadTimeout() {
        wireMock.stubFor(post(urlEqualTo("/api/generate"))
                .willReturn(aResponse()
                        .withFixedDelay(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"model":"local-test-model:1","response":"SELECT 1","done":true}
                                """)));

        var thrown = catchThrowable(() -> generator(
                        Duration.ofSeconds(1), Duration.ofMillis(50), 0)
                .generate(SqlGenerationRequest.firstAttempt("주문 수")));

        assertThat(thrown)
                .isInstanceOf(OllamaTransportException.class)
                .hasMessage("Could not communicate with local Ollama");
    }

    private OllamaSqlGenerator generator(
            Duration connectTimeout, Duration readTimeout, double temperature) {
        var properties = new OllamaProperties(
                URI.create(wireMock.baseUrl()),
                "local-test-model:1",
                temperature,
                connectTimeout,
                readTimeout);
        return new OllamaSqlGenerator(properties, new SqlPromptTemplate());
    }
}
