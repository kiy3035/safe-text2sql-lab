package dev.safetext2sql.experiment.nl;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** 자연어 질문 한 건의 생성·검증·실행·결과 비교 기록이다. */
public record NlQuestionResult(
        String id,
        QuestionDifficulty difficulty,
        ResultComparisonMode comparison,
        String status,
        boolean correct,
        int attemptCount,
        boolean generationSucceeded,
        boolean firstAttemptValidationPassed,
        String failureCode,
        long elapsedMs,
        List<GeneratedSqlAttempt> generatedSql,
        List<List<Object>> expectedRows,
        List<List<Object>> actualRows
) {

    public NlQuestionResult {
        generatedSql = List.copyOf(generatedSql);
        expectedRows = copyNullableRows(expectedRows);
        actualRows = copyNullableRows(actualRows);
    }

    private static List<List<Object>> copyNullableRows(List<List<Object>> rows) {
        if (rows == null) {
            return null;
        }
        List<List<Object>> copied = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            // SQL 결과에는 null이 정상 값으로 포함될 수 있어 List.copyOf를 사용하지 않는다.
            copied.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        return Collections.unmodifiableList(copied);
    }
}
