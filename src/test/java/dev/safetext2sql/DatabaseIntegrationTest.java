package dev.safetext2sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.file.Path;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.safetext2sql.execution.QueryExecutionException;
import dev.safetext2sql.execution.QueryExecutionFailure;
import dev.safetext2sql.execution.SqlQueryExecutor;
import dev.safetext2sql.experiment.nl.NlFixtureLoader;
import dev.safetext2sql.sql.validation.SqlValidator;
import dev.safetext2sql.sql.validation.ValidatedSelectTestFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DatabaseIntegrationTest {

    private static final String DATABASE_NAME = "text2sql";
    private static final String MIGRATION_USERNAME = "text2sql_migration";
    private static final String READ_ONLY_USERNAME = "text2sql_ro";
    private static final String MIGRATION_PASSWORD = randomPassword();
    private static final String READ_ONLY_PASSWORD = randomPassword();
    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16.9-alpine")
                .withDatabaseName(DATABASE_NAME)
                .withUsername(MIGRATION_USERNAME)
                .withPassword(MIGRATION_PASSWORD);
        POSTGRES.start();

        var createRoleSql = "CREATE ROLE " + READ_ONLY_USERNAME
                + " LOGIN PASSWORD '" + READ_ONLY_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION";
        try {
            var result = POSTGRES.execInContainer(
                    "psql",
                    "--username", MIGRATION_USERNAME,
                    "--dbname", DATABASE_NAME,
                    "--set", "ON_ERROR_STOP=1",
                    "--command", createRoleSql);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Failed to create the read-only test role");
            }
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        var jdbcUrl = POSTGRES.getJdbcUrl() + "&currentSchema=analytics";
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> READ_ONLY_USERNAME);
        registry.add("spring.datasource.password", () -> READ_ONLY_PASSWORD);
        registry.add("spring.flyway.url", () -> jdbcUrl);
        registry.add("spring.flyway.user", () -> MIGRATION_USERNAME);
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        // 4단계 실행기 검증에서는 작은 상한과 timeout을 사용해 테스트 시간을 줄인다.
        // 이 설정은 실행기 쿼리에만 적용되므로 Flyway 마이그레이션에는 영향을 주지 않는다.
        registry.add("text2sql.execution.max-rows", () -> 10);
        registry.add("text2sql.execution.statement-timeout", () -> "100ms");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqlValidator sqlValidator;

    @Autowired
    private SqlQueryExecutor sqlQueryExecutor;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void applicationUsesReadOnlyRoleAndLoadsDeterministicSmokeSeed() {
        assertThat(jdbcTemplate.queryForObject("select current_user", String.class))
                .isEqualTo(READ_ONLY_USERNAME);
        assertThat(jdbcTemplate.queryForObject("select count(*) from categories", Long.class))
                .isEqualTo(5L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from products", Long.class))
                .isEqualTo(20L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from customers", Long.class))
                .isEqualTo(50L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from orders", Long.class))
                .isEqualTo(100L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from order_items", Long.class))
                .isEqualTo(100L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from payments", Long.class))
                .isEqualTo(100L);
    }

    @Test
    void flywayRunsAllStageOneMigrationsWithMigrationRole() throws SQLException {
        try (var connection = DriverManager.getConnection(
                        migrationJdbcUrl(), MIGRATION_USERNAME, MIGRATION_PASSWORD);
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery(
                        "select count(*) from analytics.flyway_schema_history "
                                + "where success and version is not null")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(3L);
        }
    }

    @Test
    void databaseRejectsWritesEvenWhenClientDisablesJdbcReadOnlyHint() {
        assertThatThrownBy(() -> executeAsReadOnlyRole(
                        "insert into orders "
                                + "(order_id, customer_id, status, ordered_at, total_amount) "
                                + "values (9999, 1, 'PAID', now(), 1000)"))
                .isInstanceOf(SQLException.class)
                .extracting(throwable -> ((SQLException) throwable).getSQLState())
                .isEqualTo("42501");
    }

    @Test
    void databaseRejectsSensitiveColumnsAndForbiddenTables() {
        assertThatThrownBy(() -> executeAsReadOnlyRole("select customer_id, email from customers"))
                .isInstanceOf(SQLException.class)
                .extracting(throwable -> ((SQLException) throwable).getSQLState())
                .isEqualTo("42501");

        assertThatThrownBy(() -> executeAsReadOnlyRole("select admin_id from admin_users"))
                .isInstanceOf(SQLException.class)
                .extracting(throwable -> ((SQLException) throwable).getSQLState())
                .isEqualTo("42501");
    }

    @Test
    void nativeQueryExecutorRunsOnlyValidatedSelectAndDetectsTruncation() {
        var validatedSelect = sqlValidator.validate(
                "SELECT order_id FROM orders ORDER BY order_id");

        var result = sqlQueryExecutor.execute(validatedSelect);

        assertThat(result.columns()).containsExactly("order_id");
        assertThat(result.rows()).hasSize(10);
        assertThat(result.rows().getFirst()).containsExactly(1L);
        assertThat(result.rows().getLast()).containsExactly(10L);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void nativeQueryExecutorEnforcesDatabaseStatementTimeout() {
        /*
         * pg_sleep은 운영 Allowlist에서 금지되므로 정상 검증 경로로는 이 실행기에 도달할 수 없다.
         * 여기서는 실행기 자체의 자원 제한만 독립적으로 검증하기 위해 테스트 패키지 전용 팩터리로
         * ValidatedSelect를 만든다. 운영 소스에는 임의 SQL을 감싸는 공개 생성 경로가 없다.
         */
        var delayedSelect = ValidatedSelectTestFactory.create(
                "SELECT pg_sleep(1) AS delayed", "delayed");

        assertThatThrownBy(() -> sqlQueryExecutor.execute(delayedSelect))
                .isInstanceOf(QueryExecutionException.class)
                .extracting(exception -> ((QueryExecutionException) exception).failure())
                .isEqualTo(QueryExecutionFailure.STATEMENT_TIMEOUT);
    }

    @Test
    void applicationApiStartsWithoutOllamaAndReturnsStableUnavailableError() throws Exception {
        mockMvc.perform(post("/api/v1/text2sql/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"첫 주문을 알려줘\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LLM_UNAVAILABLE"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.sql").doesNotExist());
    }

    @Test
    void allFiftyGoldenSqlQueriesExecuteAgainstTheDeterministicSeed() throws Exception {
        var fixtures = new NlFixtureLoader(new ObjectMapper()).load(
                Path.of("experiments", "nl-questions.jsonl"),
                Path.of("experiments", "golden-sql.jsonl")
        );

        for (var question : fixtures.questions()) {
            var validated = sqlValidator.validate(fixtures.goldenSqlFor(question).sql());
            assertThatCode(() -> jdbcTemplate.queryForList(validated.sql()))
                    .as("%s golden SQL DB execution", question.id())
                    .doesNotThrowAnyException();
        }
    }

    private static void executeAsReadOnlyRole(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        readOnlyJdbcUrl(), READ_ONLY_USERNAME, READ_ONLY_PASSWORD);
                var statement = connection.createStatement()) {
            connection.setReadOnly(false);
            statement.execute(sql);
        }
    }

    private static String migrationJdbcUrl() {
        return POSTGRES.getJdbcUrl() + "&currentSchema=analytics";
    }

    private static String readOnlyJdbcUrl() {
        return POSTGRES.getJdbcUrl() + "&currentSchema=analytics";
    }

    private static String randomPassword() {
        return "test-" + UUID.randomUUID();
    }
}
