package dev.safetext2sql.execution;

import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.safetext2sql.sql.validation.ValidatedSelect;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA {@link EntityManager}로 검증된 SELECT만 실행하는 PostgreSQL 구현체다.
 *
 * <p>{@code readOnly=true}는 트랜잭션 힌트일 뿐 권한 경계로 믿지 않는다. 실제 datasource는
 * 1단계에서 만든 {@code text2sql_ro} 계정을 사용하며, 이 구현체는 추가로 DB statement timeout과
 * 결과 행 상한을 적용한다. 검증된 SQL 원문에는 LIMIT을 이어 붙이지 않고 그대로 Native Query에
 * 전달해 문자열 조작으로 새로운 우회 경로가 생기지 않게 한다.</p>
 */
/*
 * Spring Boot 기본 AOP 설정은 @Transactional 메서드가 있는 구체 클래스를 CGLIB로 감싼다.
 * 따라서 이 클래스는 final로 닫지 않는다. 외부 호출 계약은 SqlQueryExecutor 인터페이스이고,
 * 실행 입력은 계속 생성이 제한된 ValidatedSelect뿐이므로 보안 경계는 변하지 않는다.
 */
public class NativeQueryExecutor implements SqlQueryExecutor {

    private static final String SET_LOCAL_TIMEOUT_SQL =
            "select set_config('statement_timeout', :timeout, true)";
    private static final String JPA_QUERY_TIMEOUT_HINT = "jakarta.persistence.query.timeout";
    private static final Set<String> RECOVERABLE_SQL_STATES = Set.of(
            "42601", // syntax_error
            "42702", // ambiguous_column
            "42703", // undefined_column
            "42883", // undefined_function
            "42P01"  // undefined_table
    );

    private final EntityManager entityManager;
    private final SqlExecutionProperties properties;

    public NativeQueryExecutor(EntityManager entityManager, SqlExecutionProperties properties) {
        this.entityManager = entityManager;
        this.properties = properties;
    }

    /**
     * 한 트랜잭션 안에서 timeout 설정과 SELECT 실행을 완료한다.
     *
     * <p>{@code set_config(..., true)}의 세 번째 인자는 설정 범위를 현재 트랜잭션으로 한정한다.
     * timeout 값은 검증된 Duration을 바인드 파라미터로 전달하므로 SQL 문자열에 설정값을
     * 연결하지 않는다. 최대 행보다 하나 더 요청해 결과가 잘렸는지 정확히 판단한다.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public QueryResult execute(ValidatedSelect validatedSelect) {
        try {
            applyStatementTimeout();

            int fetchLimit = Math.addExact(properties.maxRows(), 1);
            Query query = entityManager.createNativeQuery(validatedSelect.sql());
            query.setHint(JPA_QUERY_TIMEOUT_HINT, Math.toIntExact(properties.statementTimeout().toMillis()));
            if (!validatedSelect.hasExplicitRowLimit()) {
                query.setMaxResults(fetchLimit);
            }

            @SuppressWarnings("unchecked")
            List<Object> rawRows = query.getResultList();
            /*
             * 최상위 LIMIT/FETCH는 AST 정책이 최대 200행으로 먼저 제한한다. Hibernate가
             * 기존 LIMIT 뒤에 FETCH를 중복 생성하는 문제를 피하려고 setMaxResults를 생략해도
             * DB에서 무제한 결과를 가져오지 않는다. 실행 설정이 200보다 작다면 아래에서
             * 동일하게 maxRows까지만 반환하고 truncated=true를 기록한다.
             */
            boolean truncated = rawRows.size() > properties.maxRows();
            int returnedRowCount = Math.min(rawRows.size(), properties.maxRows());
            List<List<Object>> rows = new ArrayList<>(returnedRowCount);
            for (int index = 0; index < returnedRowCount; index++) {
                rows.add(normalizeRow(rawRows.get(index), validatedSelect.projectedColumns().size()));
            }
            return new QueryResult(validatedSelect.projectedColumns(), rows, truncated);
        } catch (QueryExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new QueryExecutionException(classify(exception));
        }
    }

    private void applyStatementTimeout() {
        String timeout = properties.statementTimeout().toMillis() + "ms";
        entityManager.createNativeQuery(SET_LOCAL_TIMEOUT_SQL)
                .setParameter("timeout", timeout)
                .getSingleResult();
    }

    private List<Object> normalizeRow(Object rawRow, int expectedWidth) {
        Object[] values = rawRow instanceof Object[] array ? array : new Object[] {rawRow};
        if (values.length != expectedWidth) {
            throw new QueryExecutionException(QueryExecutionFailure.DATABASE_ERROR);
        }

        List<Object> normalized = new ArrayList<>(values.length);
        for (Object value : values) {
            normalized.add(normalizeValue(value));
        }
        return normalized;
    }

    /**
     * JDBC 전용 타입을 Jackson이 안정적으로 직렬화할 수 있는 Java 표준 타입으로 변환한다.
     * 허용 스키마에는 바이너리 컬럼이 없지만, 드라이버가 byte 배열을 반환하더라도 JSON 배열로
     * 과도하게 펼쳐지지 않도록 Base64 문자열로 제한한다.
     */
    private Object normalizeValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof TemporalAccessor) {
            return value;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Time time) {
            return time.toLocalTime();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof UUID || value instanceof Character || value instanceof Enum<?>) {
            return value.toString();
        }
        return value.toString();
    }

    private QueryExecutionFailure classify(Throwable throwable) {
        SQLException sqlException = findSqlException(throwable);
        if (sqlException == null || sqlException.getSQLState() == null) {
            return QueryExecutionFailure.DATABASE_ERROR;
        }

        String sqlState = sqlException.getSQLState();
        if (sqlState.equals("57014")) {
            return QueryExecutionFailure.STATEMENT_TIMEOUT;
        }
        if (sqlState.equals("42501")) {
            return QueryExecutionFailure.PERMISSION_DENIED;
        }
        if (RECOVERABLE_SQL_STATES.contains(sqlState)) {
            return QueryExecutionFailure.RECOVERABLE_SQL;
        }
        return QueryExecutionFailure.DATABASE_ERROR;
    }

    private SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }
}
