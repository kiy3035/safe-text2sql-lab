package dev.safetext2sql.experiment.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.stereotype.Component;

/**
 * 로컬 benchmark DB에서 후보 인덱스 전·후 실행계획을 같은 조건으로 측정한다.
 *
 * <p>일반 API datasource는 read-only 계정으로 EXPLAIN SELECT만 실행한다. 인덱스 생성·삭제와
 * ANALYZE는 Flyway migration 계정의 별도 연결에서, 코드에 고정된 DDL만 실행한다. finally에서
 * 후보 인덱스를 제거하므로 실험 DB를 원래 schema 상태로 돌려놓는다.</p>
 */
@Component
public final class IndexBenchmarkRunner {

    private static final int WARMUP_RUNS = 1;
    private static final int MEASURED_RUNS = 10;
    private static final String EXPLAIN_PREFIX = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ";

    private final DataSource readOnlyDataSource;
    private final FlywayProperties flywayProperties;
    private final ObjectMapper objectMapper;
    private final PlanJsonParser planParser = new PlanJsonParser();

    public IndexBenchmarkRunner(
            DataSource readOnlyDataSource,
            FlywayProperties flywayProperties,
            ObjectMapper objectMapper
    ) {
        this.readOnlyDataSource = readOnlyDataSource;
        this.flywayProperties = flywayProperties;
        this.objectMapper = objectMapper;
    }

    /** 후보 인덱스 전·후에 각 조회를 1회 예열하고 10회 측정한다. */
    public IndexBenchmarkResult run(Path outputDirectory) throws Exception {
        IndexBenchmarkDefinition definition = IndexBenchmarkDefinition.defaultDefinition();
        ensureBenchmarkSeedLoaded();
        Files.createDirectories(outputDirectory.resolve("plans"));

        List<IndexPlanMeasurement> measurements = new ArrayList<>();
        try (Connection migration = migrationConnection();
                Connection readOnly = readOnlyDataSource.getConnection()) {
            configureTimeout(migration);
            configureTimeout(readOnly);
            executeTrustedStatement(migration, definition.dropIndexSql());
            executeTrustedStatement(migration, "ANALYZE analytics.orders");
            measureCondition(readOnly, definition, "BEFORE", outputDirectory, measurements);

            executeTrustedStatement(migration, definition.createIndexSql());
            executeTrustedStatement(migration, "ANALYZE analytics.orders");
            measureCondition(readOnly, definition, "AFTER", outputDirectory, measurements);
        } finally {
            // 측정 도중 실패해도 로컬 DB에 실험용 인덱스를 남기지 않는다.
            try (Connection cleanup = migrationConnection()) {
                configureTimeout(cleanup);
                executeTrustedStatement(cleanup, definition.dropIndexSql());
                executeTrustedStatement(cleanup, "ANALYZE analytics.orders");
            }
        }

        return new IndexBenchmarkResult(
                definition,
                WARMUP_RUNS,
                MEASURED_RUNS,
                measurements,
                summarize(measurements, definition)
        );
    }

    private void ensureBenchmarkSeedLoaded() throws SQLException {
        try (Connection connection = readOnlyDataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("select count(*) from analytics.orders")) {
            resultSet.next();
            if (resultSet.getLong(1) < 50_000) {
                throw new IllegalStateException(
                        "benchmark seed is required: include classpath:db/benchmark in DB_FLYWAY_LOCATIONS");
            }
        }
    }

    private Connection migrationConnection() throws SQLException {
        return DriverManager.getConnection(
                flywayProperties.getUrl(),
                flywayProperties.getUser(),
                flywayProperties.getPassword()
        );
    }

    private void configureTimeout(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET statement_timeout = '30s'");
        }
    }

    private void executeTrustedStatement(Connection connection, String sql) throws SQLException {
        /*
         * sql은 외부 입력이 아니라 IndexBenchmarkDefinition과 이 클래스의 고정 상수에서만 온다.
         * API 검증 gate에 DDL/EXPLAIN을 허용하지 않고 전용 migration 연결로 격리한다.
         */
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void measureCondition(
            Connection connection,
            IndexBenchmarkDefinition definition,
            String condition,
            Path outputDirectory,
            List<IndexPlanMeasurement> destination
    ) throws Exception {
        for (IndexBenchmarkCase benchmarkCase : definition.cases()) {
            for (int warmup = 0; warmup < WARMUP_RUNS; warmup++) {
                executeExplain(connection, benchmarkCase.sql());
            }
            for (int repetition = 1; repetition <= MEASURED_RUNS; repetition++) {
                JsonNode plan = executeExplain(connection, benchmarkCase.sql());
                String planFile = "plans/" + benchmarkCase.id().toLowerCase()
                        + "-" + condition.toLowerCase() + "-" + String.format("%02d", repetition) + ".json";
                writePlan(outputDirectory.resolve(planFile), plan);
                PlanJsonParser.ParsedPlan parsed = planParser.parse(plan);
                destination.add(new IndexPlanMeasurement(
                        benchmarkCase.id(), condition, repetition, planFile,
                        parsed.scanType(), parsed.plannerEstimatedRows(), parsed.actualRows(), parsed.resultRows(),
                        parsed.rowsRemovedByFilter(), parsed.sharedHitBlocks(), parsed.sharedReadBlocks(),
                        parsed.planningTimeMs(), parsed.executionTimeMs()
                ));
            }
        }
    }

    private JsonNode executeExplain(Connection connection, String trustedSelect) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(EXPLAIN_PREFIX + trustedSelect)) {
            if (!resultSet.next()) {
                throw new IllegalStateException("EXPLAIN returned no JSON");
            }
            return objectMapper.readTree(String.valueOf(resultSet.getObject(1)));
        }
    }

    private void writePlan(Path path, JsonNode plan) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), plan);
    }

    private List<IndexConditionSummary> summarize(
            List<IndexPlanMeasurement> measurements,
            IndexBenchmarkDefinition definition
    ) {
        List<IndexConditionSummary> summaries = new ArrayList<>();
        for (IndexBenchmarkCase benchmarkCase : definition.cases()) {
            for (String condition : List.of("BEFORE", "AFTER")) {
                List<IndexPlanMeasurement> subset = measurements.stream()
                        .filter(value -> value.caseId().equals(benchmarkCase.id()))
                        .filter(value -> value.condition().equals(condition))
                        .toList();
                summaries.add(summarizeCondition(benchmarkCase.id(), condition, subset));
            }
        }
        return List.copyOf(summaries);
    }

    private IndexConditionSummary summarizeCondition(
            String caseId,
            String condition,
            List<IndexPlanMeasurement> values
    ) {
        String scanTypes = values.stream().map(IndexPlanMeasurement::scanType)
                .distinct().sorted().collect(Collectors.joining("/"));
        List<Double> executionTimes = doubles(values, IndexPlanMeasurement::executionTimeMs);
        return new IndexConditionSummary(
                caseId,
                condition,
                values.size(),
                scanTypes,
                median(doubles(values, IndexPlanMeasurement::plannerEstimatedRows)),
                median(doubles(values, IndexPlanMeasurement::actualRows)),
                median(doubles(values, IndexPlanMeasurement::resultRows)),
                median(doubles(values, IndexPlanMeasurement::rowsRemovedByFilter)),
                median(doubles(values, value -> value.sharedHitBlocks())),
                median(doubles(values, value -> value.sharedReadBlocks())),
                median(doubles(values, IndexPlanMeasurement::planningTimeMs)),
                median(executionTimes),
                executionTimes.getFirst(),
                executionTimes.getLast(),
                percentile95(executionTimes)
        );
    }

    private List<Double> doubles(
            List<IndexPlanMeasurement> values,
            ToDoubleFunction<IndexPlanMeasurement> extractor
    ) {
        return values.stream().mapToDouble(extractor).boxed().sorted(Comparator.naturalOrder()).toList();
    }

    private double median(List<Double> sorted) {
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0
                : sorted.get(middle);
    }

    private double percentile95(List<Double> sorted) {
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1);
        return sorted.get(index);
    }
}
