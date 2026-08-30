package dev.safetext2sql.sql.validation;

/**
 * 신뢰할 수 없는(untrusted) SQL 문자열을 쿼리 실행 경계에서 허용되는 유일한 값으로 변환하는 검증자.
 * <p>
 * LLM이 생성한 원문 SQL을 그대로 실행하지 않고, 이 인터페이스를 통해 검증된
 * {@link ValidatedSelect}만 실행 계층으로 전달되도록 강제한다.
 * 파싱 또는 정책 검증에 실패하면 예외를 던지며 fail-closed 원칙을 따른다.
 * </p>
 */
public interface SqlValidator {

    /**
     * 검증 전 SQL 전체를 파싱하고 정책과 대조한다.
     *
     * @param untrustedSql LLM 또는 외부 입력에서 받은 검증 전 SQL
     * @return 실행 경계로 전달할 수 있는 검증 완료 SELECT
     * @throws SqlValidationException 문법 또는 보안 정책을 확실히 허용할 수 없는 경우
     */
    ValidatedSelect validate(String untrustedSql);
}
