package dev.safetext2sql.experiment.nl;

import java.util.Map;

/**
 * 50건 실험의 집계 결과다. Ollama가 없으면 수치를 0으로 꾸미지 않고 nullable 지표를 PENDING으로 둔다.
 */
public record NlExperimentSummary(
        String status,
        String pendingReason,
        int totalQuestions,
        Integer correctCount,
        Double accuracy,
        Integer generationSuccessCount,
        Double generationSuccessRate,
        Integer firstAttemptValidationPassCount,
        Double firstAttemptValidationPassRate,
        Double averageAttemptCount,
        Double medianAttemptCount,
        Map<QuestionDifficulty, DifficultyAccuracy> byDifficulty,
        Map<String, Integer> failureCounts
) {

    public NlExperimentSummary {
        byDifficulty = Map.copyOf(byDifficulty);
        failureCounts = Map.copyOf(failureCounts);
    }

    public static NlExperimentSummary pending(int totalQuestions, String reason) {
        return new NlExperimentSummary(
                "PENDING", reason, totalQuestions,
                null, null, null, null, null, null, null, null,
                Map.of(), Map.of()
        );
    }
}
