package dev.safetext2sql.sql.validation;

import java.util.Objects;

/**
 * SQL 정책 검증 실패를 나타내는 예외.
 * <p>
 * 예외 메시지에는 거부된 SQL 원문을 포함하지 않는다.
 * LLM 출력이 로그에 그대로 남는 것을 방지하여, 민감 정보나 악성 페이로드가
 * 로그 시스템을 통해 유출·보존되는 위험을 줄인다.
 * 실제 거부 사유는 {@link SqlRejectionReason} enum 값으로만 전달된다.
 * </p>
 */
public final class SqlValidationException extends RuntimeException {

    private final SqlRejectionReason reason;

    /**
     * 지정된 거부 사유로 예외를 생성한다. 메시지는 사유 enum 이름만 포함하여 원문 SQL 노출을 방지한다.
     */
    public SqlValidationException(SqlRejectionReason reason) {
        super(Objects.requireNonNull(reason, "reason").name());
        this.reason = reason;
    }

    /** 타입 안전한 거부 사유를 반환한다. 로그·재시도 로직에서 이 값으로 분기한다. */
    public SqlRejectionReason reason() {
        return reason;
    }
}
