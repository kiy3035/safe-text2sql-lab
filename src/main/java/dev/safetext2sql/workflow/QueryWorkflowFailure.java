package dev.safetext2sql.workflow;

/** API가 내부 원문 없이 HTTP 상태와 안정적인 오류 코드를 선택할 수 있게 하는 실패 분류다. */
public enum QueryWorkflowFailure {
    INVALID_QUESTION,
    SQL_GENERATION_FAILED,
    LLM_REQUEST_REJECTED,
    LLM_UNAVAILABLE,
    DB_TIMEOUT,
    DB_PERMISSION_DENIED,
    DB_UNAVAILABLE,
    CANCELLED
}
