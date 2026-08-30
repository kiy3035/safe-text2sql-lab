package dev.safetext2sql.experiment.index;

import java.util.List;

/** 후보 인덱스 정의, 60개 원본 측정치, 조건별 요약을 함께 보존한다. */
public record IndexBenchmarkResult(
        IndexBenchmarkDefinition definition,
        int warmupRunsPerCondition,
        int measuredRunsPerCondition,
        List<IndexPlanMeasurement> measurements,
        List<IndexConditionSummary> summaries
) {

    public IndexBenchmarkResult {
        measurements = List.copyOf(measurements);
        summaries = List.copyOf(summaries);
    }
}
