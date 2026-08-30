package dev.safetext2sql.experiment.index;

/** EXPLAIN JSON 한 번에서 추출한 원본 추적용 핵심 수치다. */
public record IndexPlanMeasurement(
        String caseId,
        String condition,
        int repetition,
        String planFile,
        String scanType,
        double plannerEstimatedRows,
        double actualRows,
        double resultRows,
        double rowsRemovedByFilter,
        long sharedHitBlocks,
        long sharedReadBlocks,
        double planningTimeMs,
        double executionTimeMs
) {
}
