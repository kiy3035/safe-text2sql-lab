package dev.safetext2sql.experiment.index;

/** 대표 조회 한 건의 인덱스 전 또는 후 10회 통계다. */
public record IndexConditionSummary(
        String caseId,
        String condition,
        int repetitions,
        String scanType,
        double plannerEstimatedRowsMedian,
        double actualRowsMedian,
        double resultRowsMedian,
        double rowsRemovedByFilterMedian,
        double sharedHitBlocksMedian,
        double sharedReadBlocksMedian,
        double planningTimeMedianMs,
        double executionTimeMedianMs,
        double executionTimeMinMs,
        double executionTimeMaxMs,
        double executionTimeP95Ms
) {
}
