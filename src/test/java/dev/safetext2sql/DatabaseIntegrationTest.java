package dev.safetext2sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
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
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
