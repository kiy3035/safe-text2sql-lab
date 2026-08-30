package dev.safetext2sql.execution;

import java.util.Objects;

/**
 * Native Query 실행 실패를 안정적인 코드만으로 전달하는 예외다.
 *
 * <p>SQLException 메시지에는 실행 SQL, 객체명, 드라이버 정보가 포함될 수 있으므로 cause와
 * 원문 메시지를 보존하지 않는다. 내부 재시도 계층은 {@link #failure()}만 사용한다.</p>
 */
public final class QueryExecutionException extends RuntimeException {

    private final QueryExecutionFailure failure;

    public QueryExecutionException(QueryExecutionFailure failure) {
        super(Objects.requireNonNull(failure, "failure").name());
        this.failure = failure;
    }

    public QueryExecutionFailure failure() {
        return failure;
    }
}
