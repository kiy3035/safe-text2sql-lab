package dev.safetext2sql.experiment.retry;

import java.math.BigDecimal;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.safetext2sql.execution.QueryResult;
import dev.safetext2sql.experiment.ExperimentRunDirectory;
import dev.safetext2sql.llm.config.OllamaProperties;
import dev.safetext2sql.llm.ollama.OllamaSqlGenerator;
import dev.safetext2sql.llm.prompt.SqlPromptTemplate;
import dev.safetext2sql.sql.validation.AnalyticsSqlPolicy;
import dev.safetext2sql.sql.validation.AstSqlValidator;
import dev.safetext2sql.workflow.ExponentialBackoffPolicy;
import dev.safetext2sql.workflow.QueryWorkflowException;
import dev.safetext2sql.workflow.QueryWorkflowFailure;
import dev.safetext2sql.workflow.QueryWorkflowProperties;
import dev.safetext2sql.workflow.Text2SqlQueryService;
import dev.safetext2sql.workflow.ThreadSleeper;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;

/**
 * 실제 Ollama HTTP adapter와 WireMock, 실제 200ms/400ms sleep을 사용해 재시도 시간을 측정한다.
 *
 * <p>테스트 대역은 HTTP 상태와 정상 SQL만 제공한다. 제품의 RestClient, 오류 분류, 전체 attempt
 * budget과 {@link ThreadSleeper}는 그대로 사용하므로 단위 테스트의 recording sleeper와 별도로
 * 실제 요청 간격과 총 경과 시간을 10회씩 기록할 수 있다.</p>
 */
public final class RetryMeasurementMain {

    private static final int REPETITIONS = 10;
    private static final String ENDPOINT = "/api/generate";
    private static final String VALID_SQL = "SELECT order_id FROM orders ORDER BY order_id LIMIT 1";

    private RetryMeasurementMain() {
    }

    public static void main(String[] args) throws Exception {
        ExperimentRunDirectory.Resolved output = ExperimentRunDirectory.resolve("retry", Clock.systemUTC());
        Files.createDirectories(output.directory());
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            List<RetryMeasurement> measurements = new ArrayList<>();
            for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                measurements.add(runScenario(server, RetryScenario.SERVER_SERVER_SUCCESS, repetition));
                measurements.add(runScenario(server, RetryScenario.SERVER_SERVER_SERVER, repetition));
                measurements.add(runScenario(server, RetryScenario.CLIENT_ERROR, repetition));
            }

            List<RetrySummary> summaries = summarize(measurements);
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                    // 다른 실험 metadata와 마찬가지로 Instant를 사람이 읽을 수 있는 ISO-8601로 남긴다.
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                    output.directory().resolve("retry-measurements.json").toFile(), measurements);
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                    output.directory().resolve("metadata.json").toFile(), metadata(output.runId()));
            Files.writeString(
                    output.directory().resolve("retry-summary.csv"),
                    toCsv(summaries),
                    StandardCharsets.UTF_8
            );
            System.out.println("RETRY_EXPERIMENT_RESULT_DIR=" + output.directory().toAbsolutePath());
        } finally {
            server.stop();
        }
    }

    private static RetryMeasurement runScenario(
            WireMockServer server,
            RetryScenario scenario,
            int repetition
    ) {
        server.resetAll();
        configureScenario(server, scenario);
        Text2SqlQueryService service = service(server);

        long started = System.nanoTime();
        String outcome;
        int attemptCount;
        try {
            var result = service.query("첫 주문을 알려줘");
            outcome = "SUCCESS";
            attemptCount = result.attemptCount();
            if (scenario != RetryScenario.SERVER_SERVER_SUCCESS || attemptCount != 3) {
                throw new IllegalStateException("unexpected retry success outcome");
            }
        } catch (QueryWorkflowException exception) {
            outcome = exception.failure().name();
            attemptCount = exception.attemptCount();
            QueryWorkflowFailure expected = scenario == RetryScenario.CLIENT_ERROR
                    ? QueryWorkflowFailure.LLM_REQUEST_REJECTED
                    : QueryWorkflowFailure.LLM_UNAVAILABLE;
            if (exception.failure() != expected) {
                throw new IllegalStateException("unexpected retry failure outcome");
            }
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        List<Long> requestTimes = server.getAllServeEvents().stream()
                .map(event -> event.getRequest().getLoggedDate().getTime())
                .sorted()
                .toList();
        int expectedCalls = scenario == RetryScenario.CLIENT_ERROR ? 1 : 3;
        if (requestTimes.size() != expectedCalls || attemptCount != expectedCalls) {
            throw new IllegalStateException("retry call count did not match the scenario");
        }
        Long firstGap = requestTimes.size() >= 2 ? requestTimes.get(1) - requestTimes.get(0) : null;
        Long secondGap = requestTimes.size() >= 3 ? requestTimes.get(2) - requestTimes.get(1) : null;
        return new RetryMeasurement(
                scenario.name(), repetition, requestTimes.size(), outcome, attemptCount,
                elapsedMs, firstGap, secondGap
        );
    }

    private static void configureScenario(WireMockServer server, RetryScenario scenario) {
        if (scenario == RetryScenario.CLIENT_ERROR) {
            server.stubFor(post(urlEqualTo(ENDPOINT)).willReturn(aResponse().withStatus(400)));
            return;
        }
        if (scenario == RetryScenario.SERVER_SERVER_SERVER) {
            server.stubFor(post(urlEqualTo(ENDPOINT)).willReturn(aResponse().withStatus(500)));
            return;
        }

        server.stubFor(post(urlEqualTo(ENDPOINT))
                .inScenario("recover")
                .whenScenarioStateIs(STARTED)
                .willSetStateTo("second")
                .willReturn(aResponse().withStatus(500)));
        server.stubFor(post(urlEqualTo(ENDPOINT))
                .inScenario("recover")
                .whenScenarioStateIs("second")
                .willSetStateTo("success")
                .willReturn(aResponse().withStatus(500)));
        server.stubFor(post(urlEqualTo(ENDPOINT))
                .inScenario("recover")
                .whenScenarioStateIs("success")
                .withHeader("Content-Type", equalTo("application/json"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"model\":\"wiremock-model\",\"response\":\""
                                + VALID_SQL + "\",\"done\":true}")));
    }

    private static Text2SqlQueryService service(WireMockServer server) {
        OllamaProperties properties = new OllamaProperties(
                URI.create("http://127.0.0.1:" + server.port()),
                "wiremock-model",
                0,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)
        );
        var generator = new OllamaSqlGenerator(properties, new SqlPromptTemplate());
        return new Text2SqlQueryService(
                generator,
                new AstSqlValidator(AnalyticsSqlPolicy.defaultPolicy()),
                validated -> new QueryResult(
                        List.of("order_id"), List.of(List.of(1L)), false),
                new QueryWorkflowProperties(3, Duration.ofMillis(200), 1_000),
                new ExponentialBackoffPolicy(Duration.ofMillis(200)),
                new ThreadSleeper(),
                System::nanoTime
        );
    }

    private static List<RetrySummary> summarize(List<RetryMeasurement> measurements) {
        List<RetrySummary> summaries = new ArrayList<>();
        for (RetryScenario scenario : RetryScenario.values()) {
            List<RetryMeasurement> subset = measurements.stream()
                    .filter(value -> value.scenario().equals(scenario.name()))
                    .toList();
            List<Double> elapsed = sorted(subset.stream().map(value -> (double) value.elapsedMs()).toList());
            List<Double> firstGaps = sorted(subset.stream()
                    .map(RetryMeasurement::firstRequestGapMs)
                    .filter(java.util.Objects::nonNull)
                    .map(Long::doubleValue)
                    .toList());
            List<Double> secondGaps = sorted(subset.stream()
                    .map(RetryMeasurement::secondRequestGapMs)
                    .filter(java.util.Objects::nonNull)
                    .map(Long::doubleValue)
                    .toList());
            summaries.add(new RetrySummary(
                    scenario.name(), subset.size(),
                    median(elapsed), elapsed.getFirst(), elapsed.getLast(), percentile95(elapsed),
                    nullableMedian(firstGaps), nullableMedian(secondGaps)
            ));
        }
        return List.copyOf(summaries);
    }

    private static Map<String, Object> metadata(String runId) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runId", runId);
        metadata.put("capturedAt", Instant.now());
        metadata.put("commitSha", gitCommit());
        metadata.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        metadata.put("cpu", cpu());
        metadata.put("ramBytes", ramBytes());
        metadata.put("javaVersion", System.getProperty("java.version"));
        metadata.put("postgresqlVersion", "NOT_APPLICABLE");
        metadata.put("ollamaVersion", "WIREMOCK");
        metadata.put("modelName", "wiremock-model");
        metadata.put("temperature", BigDecimal.ZERO);
        metadata.put("repetitionsPerScenario", REPETITIONS);
        metadata.put("backoffMs", List.of(200, 400));
        return metadata;
    }

    /** Windows가 제공하는 CPU 식별자를 우선 사용하고, 없으면 논리 프로세서 수를 기록한다. */
    private static String cpu() {
        String identifier = System.getenv("PROCESSOR_IDENTIFIER");
        return identifier == null || identifier.isBlank()
                ? Runtime.getRuntime().availableProcessors() + " logical processors"
                : identifier;
    }

    /** Java 21 관리 bean에서 운영체제가 보고한 전체 물리 메모리를 byte 단위로 읽는다. */
    private static long ramBytes() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean extended
                ? extended.getTotalMemorySize()
                : -1L;
    }

    private static String gitCommit() throws Exception {
        Process process = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0 || !output.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalStateException("cannot resolve experiment commit SHA");
        }
        return output;
    }

    private static List<Double> sorted(List<Double> values) {
        return values.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static Double nullableMedian(List<Double> values) {
        return values.isEmpty() ? null : median(values);
    }

    private static double median(List<Double> sorted) {
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2
                : sorted.get(middle);
    }

    private static double percentile95(List<Double> sorted) {
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1));
    }

    private static String toCsv(List<RetrySummary> summaries) {
        StringBuilder csv = new StringBuilder()
                .append("scenario,repetitions,elapsedMedianMs,elapsedMinMs,elapsedMaxMs,")
                .append("elapsedP95Ms,firstRequestGapMedianMs,secondRequestGapMedianMs\n");
        for (RetrySummary summary : summaries) {
            csv.append(summary.scenario()).append(',')
                    .append(summary.repetitions()).append(',')
                    .append(summary.elapsedMedianMs()).append(',')
                    .append(summary.elapsedMinMs()).append(',')
                    .append(summary.elapsedMaxMs()).append(',')
                    .append(summary.elapsedP95Ms()).append(',')
                    .append(summary.firstRequestGapMedianMs()).append(',')
                    .append(summary.secondRequestGapMedianMs()).append('\n');
        }
        return csv.toString();
    }

    private enum RetryScenario {
        SERVER_SERVER_SUCCESS,
        SERVER_SERVER_SERVER,
        CLIENT_ERROR
    }

    private record RetryMeasurement(
            String scenario,
            int repetition,
            int httpCallCount,
            String outcome,
            int attemptCount,
            long elapsedMs,
            Long firstRequestGapMs,
            Long secondRequestGapMs
    ) {
    }

    private record RetrySummary(
            String scenario,
            int repetitions,
            double elapsedMedianMs,
            double elapsedMinMs,
            double elapsedMaxMs,
            double elapsedP95Ms,
            Double firstRequestGapMedianMs,
            Double secondRequestGapMedianMs
    ) {
    }
}
