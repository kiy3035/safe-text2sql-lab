package dev.safetext2sql.experiment.nl;

import java.util.List;

/** JSON/CSV로 함께 기록할 자연어 실험 요약과 질문별 원본 결과다. */
public record NlExperimentRun(NlExperimentSummary summary, List<NlQuestionResult> results) {

    public NlExperimentRun {
        results = List.copyOf(results);
    }
}
