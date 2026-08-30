package dev.safetext2sql.sql.validation;

import java.util.List;
import java.util.Objects;

/**
 * 전체 AST 정책 검증을 통과한 단일 SELECT 문을 나타내는 값 객체.
 * <p>
 * 생성자는 의도적으로 패키지 가시성(package-private)으로 제한된다.
 * 따라서 검증기(validator) 패키지 외부의 코드는 이 값을 소비(실행)할 수는 있지만,
 * 검증 없이 신뢰할 수 없는 문자열로부터 임의로 생성할 수는 없다.
 * 이는 검증에 성공한 SQL만 Native Query로 실행되도록 하는 보안 불변 조건을 강제하는 설계이다.
 * </p>
 */
public final class ValidatedSelect {

    private final String sql;
    private final List<String> projectedColumns;

    /**
     * 검증된 SELECT를 생성한다. 패키지 내부에서만 호출 가능하여 외부에서의 위조를 방지한다.
     * sql은 원문 그대로 보존되며, projectedColumns는 방어적 복사된다.
     */
    ValidatedSelect(String sql, List<String> projectedColumns) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.projectedColumns = List.copyOf(projectedColumns);
    }

    /** 검증된 원문 SQL을 반환한다. 이 값만이 Native Query 실행에 사용될 수 있다. */
    public String sql() {
        return sql;
    }

    /** SELECT 절의 프로젝션 별칭 목록을 반환한다. 순서가 보장된 불변 리스트이다. */
    public List<String> projectedColumns() {
        return projectedColumns;
    }
}
