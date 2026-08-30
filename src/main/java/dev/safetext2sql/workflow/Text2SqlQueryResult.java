package dev.safetext2sql.workflow;

import java.util.List;

import dev.safetext2sql.execution.QueryResult;

/**
 * 실행 결과에 전체 시도 횟수와 경과 시간을 결합한 서비스 계층 결과다.
 */
public record Text2SqlQueryResult(
        List<String> columns,
        List<List<Object>> rows,
        boolean truncated,
        int attemptCount,
        long elapsedMs
) {

    public static Text2SqlQueryResult from(QueryResult queryResult, int attemptCount, long elapsedMs) {
        return new Text2SqlQueryResult(
                queryResult.columns(),
                queryResult.rows(),
                queryResult.truncated(),
                attemptCount,
                elapsedMs
        );
    }
}
