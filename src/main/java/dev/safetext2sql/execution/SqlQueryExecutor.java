package dev.safetext2sql.execution;

import dev.safetext2sql.sql.validation.ValidatedSelect;

/**
 * 검증을 마친 SELECT를 데이터베이스에서 실행하는 경계다.
 *
 * <p>매개변수 타입을 {@link ValidatedSelect}로 제한해 LLM 원문 문자열이 검증기를 건너뛰고
 * 실행기로 직접 들어오는 호출 경로를 만들지 않는다. 구현체는 결과 행 상한과 DB timeout을
 * 함께 적용해야 하며, DB 원문 오류는 안정적인 {@link QueryExecutionFailure}로 축약한다.</p>
 */
@FunctionalInterface
public interface SqlQueryExecutor {

    /**
     * 검증된 단일 SELECT를 실행한다.
     *
     * @param validatedSelect AST와 Allowlist 검증을 모두 통과한 SELECT
     * @return 컬럼, 최대 허용 행, 잘림 여부를 포함한 결과
     * @throws QueryExecutionException DB 실행이 실패한 경우
     */
    QueryResult execute(ValidatedSelect validatedSelect);
}
