package dev.safetext2sql.api;

import java.util.List;

import dev.safetext2sql.workflow.Text2SqlQueryResult;

/**
 * SQL 원문을 노출하지 않는 성공 응답이다.
 */
public record Text2SqlQueryResponse(
        List<String> columns,
        List<List<Object>> rows,
        boolean truncated,
        int attemptCount,
        long elapsedMs
) {

    static Text2SqlQueryResponse from(Text2SqlQueryResult result) {
        return new Text2SqlQueryResponse(
                result.columns(),
                result.rows(),
                result.truncated(),
                result.attemptCount(),
                result.elapsedMs()
        );
    }
}
