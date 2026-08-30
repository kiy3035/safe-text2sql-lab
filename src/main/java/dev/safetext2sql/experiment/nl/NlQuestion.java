package dev.safetext2sql.experiment.nl;

import java.util.Objects;

/**
 * 자연어 정확도 fixture 한 건이다.
 *
 * @param id 버전 관리되는 질문 식별자
 * @param question 로컬 합성 스키마에 대한 자연어 질문
 * @param goldenSqlId 정답 결과를 만드는 Golden SQL 식별자
 * @param comparison 결과 비교 방식
 * @param difficulty 난이도별 정확도 집계를 위한 분류
 */
public record NlQuestion(
        String id,
        String question,
        String goldenSqlId,
        ResultComparisonMode comparison,
        QuestionDifficulty difficulty
) {

    public NlQuestion {
        requireText(id, "id");
        requireText(question, "question");
        requireText(goldenSqlId, "goldenSqlId");
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(difficulty, "difficulty");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
