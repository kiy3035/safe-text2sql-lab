package dev.safetext2sql.execution;

/**
 * DB 예외 원문을 외부 계층에 전달하지 않고 재시도 여부만 판단하기 위한 안정적인 분류다.
 */
public enum QueryExecutionFailure {
    /** PostgreSQL 문법 또는 식별자 오류로, 새 SQL 생성으로 회복할 가능성이 있다. */
    RECOVERABLE_SQL(true),
    /** statement_timeout 또는 명시적 취소로 중단되었으며 재시도하지 않는다. */
    STATEMENT_TIMEOUT(false),
    /** read-only 계정 권한으로 거부되었으며 보안 경계를 약화하지 않고 즉시 종료한다. */
    PERMISSION_DENIED(false),
    /** 연결 장애나 분류할 수 없는 DB 오류로 재시도하지 않는다. */
    DATABASE_ERROR(false);

    private final boolean retryable;

    QueryExecutionFailure(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
