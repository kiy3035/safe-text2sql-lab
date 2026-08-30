package dev.safetext2sql.sql.validation;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL 검증에 사용되는 허용 목록(Allowlist) 정책 정의.
 * <p>
 * 검증기({@link AstSqlValidator})가 참조하는 단일 진실 공급원(Single Source of Truth)으로,
 * 허용된 스키마, 테이블별 컬럼, 허용 함수, 길이/JOIN/서브쿼리/행 수 제한을 모두 담는다.
 * 모든 식별자는 소문자로 정규화하여 대소문자·인용 식별자 우회를 방지한다.
 * 기본 정책({@link #defaultPolicy()})은 합성 커머스 분석 DB 스키마를 반영한다.
 * </p>
 */
public final class AnalyticsSqlPolicy {

    /** 허용된 유일한 스키마명. 다른 스키마 한정 참조는 즉시 거부된다. */
    private static final String ANALYTICS_SCHEMA = "analytics";

    private final String allowedSchema;
    private final Map<String, Set<String>> tableColumns;
    private final Set<String> allowedFunctions;
    private final int maxSqlLength;
    private final int maxJoins;
    private final int maxSubqueryDepth;
    private final long maxRows;

    /**
     * 내부 생성자 - 모든 식별자를 소문자로 정규화하여 저장한다.
     * 대소문자·인용 식별자 우회를 방지하기 위해 테이블명·컬럼명·함수명은 모두 normalize 후 비교된다.
     */
    private AnalyticsSqlPolicy(
            String allowedSchema,
            Map<String, Set<String>> tableColumns,
            Set<String> allowedFunctions,
            int maxSqlLength,
            int maxJoins,
            int maxSubqueryDepth,
            long maxRows
    ) {
        this.allowedSchema = normalize(allowedSchema);
        Map<String, Set<String>> normalizedTables = new LinkedHashMap<>();
        tableColumns.forEach((table, columns) -> normalizedTables.put(
                normalize(table),
                columns.stream().map(AnalyticsSqlPolicy::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet())
        ));
        this.tableColumns = Map.copyOf(normalizedTables);
        this.allowedFunctions = allowedFunctions.stream()
                .map(AnalyticsSqlPolicy::normalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.maxSqlLength = maxSqlLength;
        this.maxJoins = maxJoins;
        this.maxSubqueryDepth = maxSubqueryDepth;
        this.maxRows = maxRows;
    }

    /**
     * 합성 커머스 분석 DB 스키마를 반영한 기본 정책을 생성한다.
     * 6개 테이블·허용 함수 11개·길이 10,000·JOIN 4·깊이 3·행 200 제한을 적용한다.
     */
    public static AnalyticsSqlPolicy defaultPolicy() {
        // email/phone 컬럼과 admin_users/audit_logs 테이블은 의도적으로 목록에서 제외한다.
        // DB 권한과 AST 허용 목록을 독립적으로 겹쳐 적용해 한 계층의 실수가 바로 노출로 이어지지 않게 한다.
        return new AnalyticsSqlPolicy(
                ANALYTICS_SCHEMA,
                Map.of(
                        "categories", Set.of("category_id", "name"),
                        "products", Set.of("product_id", "category_id", "name", "price", "active"),
                        "customers", Set.of("customer_id", "region", "grade", "created_at"),
                        "orders", Set.of("order_id", "customer_id", "status", "ordered_at", "total_amount"),
                        "order_items", Set.of("order_item_id", "order_id", "product_id", "quantity", "unit_price"),
                        "payments", Set.of("payment_id", "order_id", "status", "paid_at", "amount")
                ),
                Set.of("count", "sum", "avg", "min", "max", "coalesce", "nullif", "date_trunc", "extract", "lower", "upper"),
                10_000,
                4,
                3,
                200
        );
    }

    /** 허용된 스키마명(analytics)을 반환한다. 패키지 내부 전용. */
    String allowedSchema() {
        return allowedSchema;
    }

    /** 지정 테이블의 허용 컬럼 집합을 반환한다. 없으면 null (TABLE_NOT_ALLOWED로 거부). */
    Set<String> columnsFor(String table) {
        return tableColumns.get(normalize(table));
    }

    /** 지정 함수가 Allowlist에 포함되어 있는지 확인한다. 대소문자 구분 없이 비교한다. */
    boolean allowsFunction(String function) {
        return allowedFunctions.contains(normalize(function));
    }

    /** SQL 최대 길이를 반환한다 (10,000자). */
    int maxSqlLength() {
        return maxSqlLength;
    }

    /** 허용 최대 JOIN 개수를 반환한다 (4). */
    int maxJoins() {
        return maxJoins;
    }

    /** 허용 최대 서브쿼리 깊이를 반환한다 (3). */
    int maxSubqueryDepth() {
        return maxSubqueryDepth;
    }

    /** LIMIT/FETCH 최대 행 수를 반환한다 (200). */
    long maxRows() {
        return maxRows;
    }

    /** 식별자를 소문자로 정규화한다. null은 그대로 null 반환. 대소문자 우회 방지의 핵심이다. */
    static String normalize(String identifier) {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }
}
