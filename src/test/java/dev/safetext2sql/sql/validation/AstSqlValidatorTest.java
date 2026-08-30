package dev.safetext2sql.sql.validation;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 정상 SELECT의 허용 경로와 AST 위치별 거절 경로를 함께 검증한다.
 *
 * <p>SELECT 절만 확인하지 않고 JOIN, WHERE, GROUP BY, HAVING, ORDER BY와
 * 중첩 서브쿼리에 숨은 금지 컬럼도 동일한 정책으로 차단되는지 확인한다.</p>
 */
class AstSqlValidatorTest {

    private final SqlValidator validator = new AstSqlValidator(AnalyticsSqlPolicy.defaultPolicy());

    @Test
    void acceptsSimpleSelectAndPreservesTheValidatedText() {
        String sql = "SELECT order_id, status FROM analytics.orders "
                + "WHERE total_amount > 10 ORDER BY ordered_at DESC LIMIT 20";

        ValidatedSelect validated = validator.validate(sql);

        assertThat(validated.sql()).isEqualTo(sql);
        assertThat(validated.projectedColumns()).containsExactly("order_id", "status");
        assertThat(validated.hasExplicitRowLimit()).isTrue();
    }

    @Test
    void acceptsOnlyAnExactSqlMarkdownFence() {
        ValidatedSelect validated = validator.validate("""
                ```sql
                SELECT order_id FROM orders
                ```
                """);

        assertThat(validated.sql()).isEqualTo("SELECT order_id FROM orders");
    }

    @Test
    void resolvesAliasesAcrossJoinAggregationAndOrderByProjectionAlias() {
        String sql = """
                SELECT c.region, COUNT(*) AS order_count, SUM(o.total_amount) AS revenue
                FROM customers c
                JOIN orders o ON o.customer_id = c.customer_id
                WHERE o.status = 'PAID'
                GROUP BY c.region
                HAVING COUNT(*) > 0
                ORDER BY revenue DESC
                """;

        ValidatedSelect validated = validator.validate(sql);

        assertThat(validated.projectedColumns()).containsExactly("region", "order_count", "revenue");
    }

    @Test
    void validatesCorrelatedAndDerivedSubqueriesRecursively() {
        String sql = """
                SELECT recent.customer_id
                FROM (
                    SELECT o.customer_id
                    FROM orders o
                    WHERE EXISTS (
                        SELECT p.payment_id
                        FROM payments p
                        WHERE p.order_id = o.order_id AND p.status = 'COMPLETED'
                    )
                ) recent
                """;

        ValidatedSelect validated = validator.validate(sql);

        assertThat(validated.projectedColumns()).containsExactly("customer_id");
    }

    @Test
    void normalizesQuotedIdentifiersWithoutChangingTheSqlToExecute() {
        String sql = "SELECT \"O\".\"ORDER_ID\" FROM \"ANALYTICS\".\"ORDERS\" \"O\"";

        ValidatedSelect validated = validator.validate(sql);

        assertThat(validated.sql()).isEqualTo(sql);
        assertThat(validated.projectedColumns()).containsExactly("order_id");
    }

    @Test
    void doesNotTreatAFromSubqueryLimitAsTheOuterQueryRowLimit() {
        ValidatedSelect validated = validator.validate("""
                SELECT recent.order_id
                FROM (SELECT order_id FROM orders ORDER BY order_id LIMIT 10) recent
                """);

        // 안쪽 LIMIT은 바깥 SELECT의 최대 행 수를 보장하지 않으므로 실행기의 상한 적용을 생략하면 안 된다.
        assertThat(validated.hasExplicitRowLimit()).isFalse();
    }

    @Test
    void acceptsFetchWhenItsLiteralRowCountIsWithinThePolicyLimit() {
        ValidatedSelect validated = validator.validate(
                "SELECT order_id FROM orders ORDER BY order_id FETCH FIRST 10 ROWS ONLY");

        assertThat(validated.projectedColumns()).containsExactly("order_id");
        assertThat(validated.hasExplicitRowLimit()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedSql")
    void failsClosedForUnsupportedOrUnsafeAst(String description, String sql, SqlRejectionReason reason) {
        assertThatThrownBy(() -> validator.validate(sql))
                .isInstanceOf(SqlValidationException.class)
                .extracting(exception -> ((SqlValidationException) exception).reason())
                .isEqualTo(reason);
    }

    private static Stream<Arguments> rejectedSql() {
        return Stream.of(
                Arguments.of("empty", " ", SqlRejectionReason.EMPTY_SQL),
                Arguments.of("select star", "SELECT * FROM orders", SqlRejectionReason.WILDCARD_NOT_ALLOWED),
                Arguments.of("table star", "SELECT o.* FROM orders o", SqlRejectionReason.WILDCARD_NOT_ALLOWED),
                Arguments.of("locking", "SELECT order_id FROM orders FOR UPDATE", SqlRejectionReason.LOCKING_NOT_ALLOWED),
                Arguments.of("cte", "WITH q AS (SELECT order_id FROM orders) SELECT order_id FROM q", SqlRejectionReason.CTE_NOT_ALLOWED),
                Arguments.of("union", "SELECT order_id FROM orders UNION SELECT order_id FROM payments", SqlRejectionReason.SET_OPERATION_NOT_ALLOWED),
                Arguments.of("select into", "SELECT order_id INTO synthetic_export FROM orders", SqlRejectionReason.SELECT_INTO_NOT_ALLOWED),
                Arguments.of("forbidden schema", "SELECT tablename FROM pg_catalog.pg_tables", SqlRejectionReason.SCHEMA_NOT_ALLOWED),
                Arguments.of("forbidden table", "SELECT user_id FROM admin_users", SqlRejectionReason.TABLE_NOT_ALLOWED),
                Arguments.of("forbidden select column", "SELECT email FROM customers", SqlRejectionReason.COLUMN_NOT_ALLOWED),
                Arguments.of("forbidden join column", "SELECT o.order_id FROM orders o JOIN customers c ON c.email = o.status", SqlRejectionReason.COLUMN_NOT_ALLOWED),
                Arguments.of("forbidden where column", "SELECT order_id FROM orders WHERE internal_note = 'x'", SqlRejectionReason.COLUMN_NOT_ALLOWED),
                Arguments.of("forbidden group column", "SELECT status FROM orders GROUP BY internal_note, status", SqlRejectionReason.COLUMN_NOT_ALLOWED),
                Arguments.of("forbidden having column", "SELECT status FROM orders GROUP BY status HAVING internal_note = 'x'", SqlRejectionReason.COLUMN_NOT_ALLOWED),
                Arguments.of("forbidden order column", "SELECT order_id FROM orders ORDER BY internal_note", SqlRejectionReason.COLUMN_NOT_ALLOWED),
                Arguments.of("forbidden subquery column", "SELECT order_id FROM orders WHERE EXISTS (SELECT email FROM customers)", SqlRejectionReason.COLUMN_NOT_ALLOWED),
                Arguments.of("ambiguous column", "SELECT status FROM orders o JOIN payments p ON p.order_id = o.order_id", SqlRejectionReason.AMBIGUOUS_COLUMN),
                Arguments.of("function", "SELECT pg_sleep(1) AS delayed FROM orders", SqlRejectionReason.FUNCTION_NOT_ALLOWED),
                Arguments.of("function qualification", "SELECT pg_catalog.count(order_id) AS value FROM orders", SqlRejectionReason.FUNCTION_NOT_ALLOWED),
                Arguments.of("cross join", "SELECT o.order_id FROM orders o CROSS JOIN customers c", SqlRejectionReason.JOIN_NOT_ALLOWED),
                Arguments.of("distinct on", "SELECT DISTINCT ON (internal_note) order_id FROM orders", SqlRejectionReason.UNSUPPORTED_SELECT),
                Arguments.of("table alias column list", "SELECT c.customer_id FROM customers c(email)", SqlRejectionReason.UNSUPPORTED_SELECT),
                Arguments.of("derived alias column list", "SELECT recent.exposed FROM (SELECT customer_id FROM customers) recent(exposed)", SqlRejectionReason.UNSUPPORTED_SELECT),
                Arguments.of("large limit", "SELECT order_id FROM orders LIMIT 201", SqlRejectionReason.LIMIT_EXCEEDED),
                Arguments.of("parameter limit", "SELECT order_id FROM orders LIMIT ?", SqlRejectionReason.LIMIT_EXCEEDED),
                Arguments.of("calculated projection without alias", "SELECT total_amount + 1 FROM orders", SqlRejectionReason.PROJECTION_ALIAS_REQUIRED),
                Arguments.of("duplicate projection", "SELECT order_id, order_id FROM orders", SqlRejectionReason.DUPLICATE_PROJECTION),
                Arguments.of("duplicate relation", "SELECT orders.order_id FROM orders JOIN orders ON orders.order_id = orders.order_id", SqlRejectionReason.DUPLICATE_RELATION),
                Arguments.of("too many joins", """
                        SELECT o.order_id FROM orders o
                        JOIN customers c ON c.customer_id = o.customer_id
                        JOIN payments p ON p.order_id = o.order_id
                        JOIN order_items i ON i.order_id = o.order_id
                        JOIN products pr ON pr.product_id = i.product_id
                        JOIN categories ca ON ca.category_id = pr.category_id
                        """, SqlRejectionReason.QUERY_COMPLEXITY_EXCEEDED),
                Arguments.of("too many nested subqueries", """
                        SELECT order_id FROM orders WHERE EXISTS (
                          SELECT payment_id FROM payments WHERE EXISTS (
                            SELECT customer_id FROM customers WHERE EXISTS (
                              SELECT product_id FROM products WHERE EXISTS (
                                SELECT category_id FROM categories
                              )
                            )
                          )
                        )
                        """, SqlRejectionReason.QUERY_COMPLEXITY_EXCEEDED)
        );
    }
}
