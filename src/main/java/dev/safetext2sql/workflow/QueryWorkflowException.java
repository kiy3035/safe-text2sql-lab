package dev.safetext2sql.workflow;

import java.util.Objects;

/**
 * 전체 Text2SQL 흐름의 실패를 외부 응답용 코드와 시도 횟수로만 전달한다.
 *
 * <p>자연어 질문, 생성 SQL, DB 오류 메시지, Ollama 응답 본문은 포함하지 않는다.</p>
 */
public final class QueryWorkflowException extends RuntimeException {

    private final QueryWorkflowFailure failure;
    private final int attemptCount;

    public QueryWorkflowException(QueryWorkflowFailure failure, int attemptCount) {
        super(Objects.requireNonNull(failure, "failure").name());
        if (attemptCount < 0 || attemptCount > 3) {
            throw new IllegalArgumentException("attempt count must be between 0 and 3");
        }
        this.failure = failure;
        this.attemptCount = attemptCount;
    }

    public QueryWorkflowFailure failure() {
        return failure;
    }

    public int attemptCount() {
        return attemptCount;
    }
}
