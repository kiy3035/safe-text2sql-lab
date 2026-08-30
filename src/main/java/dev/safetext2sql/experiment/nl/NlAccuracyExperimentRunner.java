package dev.safetext2sql.experiment.nl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.safetext2sql.execution.QueryResult;
import dev.safetext2sql.execution.SqlQueryExecutor;
import dev.safetext2sql.llm.SqlGenerator;
import dev.safetext2sql.sql.validation.SqlValidator;
import dev.safetext2sql.workflow.BackoffPolicy;
import dev.safetext2sql.workflow.NanoClock;
import dev.safetext2sql.workflow.QueryWorkflowException;
import dev.safetext2sql.workflow.QueryWorkflowProperties;
import dev.safetext2sql.workflow.Sleeper;
import dev.safetext2sql.workflow.Text2SqlQueryResult;
import dev.safetext2sql.workflow.Text2SqlQueryService;
import org.springframework.stereotype.Component;

/**
 * 자연어 50건을 실제 제품 파이프라인으로 실행하고 Golden 결과와 비교한다.
 *
 * <p>Golden SQL도 같은 AST 정책과 read-only executor를 통과시킨다. 생성 SQL은 기존
 * {@link Text2SqlQueryService}를 그대로 사용하므로 매 시도 검증, 3회 예산, 오류별 재시도,
 * DB timeout을 실험 편의를 위해 우회하지 않는다.</p>
 */
@Component
public final class NlAccuracyExperimentRunner {

    private final SqlValidator validator;
    private final SqlQueryExecutor executor;
    private final QueryWorkflowProperties workflowProperties;
    private final BackoffPolicy backoffPolicy;
    private final Sleeper sleeper;
    private final NanoClock nanoClock;
    private final ResultSetComparator comparator = new ResultSetComparator();

    public NlAccuracyExperimentRunner(
            SqlValidator validator,
            SqlQueryExecutor executor,
            QueryWorkflowProperties workflowProperties,
            BackoffPolicy backoffPolicy,
            Sleeper sleeper,
            NanoClock nanoClock
    ) {
        this.validator = validator;
        this.executor = executor;
        this.workflowProperties = workflowProperties;
        this.backoffPolicy = backoffPolicy;
        this.sleeper = sleeper;
        this.nanoClock = nanoClock;
    }

    /**
     * @param fixtures 정확히 50건으로 검증된 질문·Golden SQL
     * @param generator 실제 Ollama generator. null이면 Golden 실행까지만 확인하고 PENDING을 반환한다.
     */
    public NlExperimentRun run(NlFixtureSet fixtures, SqlGenerator generator) {
        Map<String, QueryResult> goldenResults = executeAllGoldenSql(fixtures);
        if (generator == null) {
            return new NlExperimentRun(
                    NlExperimentSummary.pending(fixtures.questions().size(), "OLLAMA_NOT_CONFIGURED"),
                    List.of()
            );
        }

        List<NlQuestionResult> results = new ArrayList<>(fixtures.questions().size());
        for (NlQuestion question : fixtures.questions()) {
            results.add(runQuestion(question, goldenResults.get(question.goldenSqlId()), generator));
        }
        return new NlExperimentRun(summarize(results), results);
    }

    private Map<String, QueryResult> executeAllGoldenSql(NlFixtureSet fixtures) {
        Map<String, QueryResult> results = new LinkedHashMap<>();
        for (NlQuestion question : fixtures.questions()) {
            GoldenSql goldenSql = fixtures.goldenSqlFor(question);
            QueryResult result = executor.execute(validator.validate(goldenSql.sql()));
            if (result.truncated()) {
                throw new IllegalStateException("golden result exceeded the configured row limit");
            }
            results.put(goldenSql.id(), result);
        }
        return Map.copyOf(results);
    }

    private NlQuestionResult runQuestion(
            NlQuestion question,
            QueryResult goldenResult,
            SqlGenerator generator
    ) {
        RecordingSqlGenerator recording = new RecordingSqlGenerator(generator);
        Text2SqlQueryService service = new Text2SqlQueryService(
                recording,
                validator,
                executor,
                workflowProperties,
                backoffPolicy,
                sleeper,
                nanoClock
        );

        try {
            Text2SqlQueryResult actual = service.query(question.question());
            boolean correct = !actual.truncated() && comparator.matches(
                    question.comparison(), goldenResult.rows(), actual.rows());
            return new NlQuestionResult(
                    question.id(), question.difficulty(), question.comparison(),
                    correct ? "CORRECT" : "RESULT_MISMATCH",
                    correct,
                    actual.attemptCount(),
                    !recording.generated().isEmpty(),
                    firstAttemptPassedValidation(recording.generated()),
                    correct ? null : "RESULT_MISMATCH",
                    actual.elapsedMs(),
                    recording.generated(),
                    goldenResult.rows(),
                    actual.rows()
            );
        } catch (QueryWorkflowException exception) {
            return new NlQuestionResult(
                    question.id(), question.difficulty(), question.comparison(),
                    exception.failure().name(),
                    false,
                    exception.attemptCount(),
                    !recording.generated().isEmpty(),
                    firstAttemptPassedValidation(recording.generated()),
                    exception.failure().name(),
                    0,
                    recording.generated(),
                    goldenResult.rows(),
                    null
            );
        }
    }

    private boolean firstAttemptPassedValidation(List<GeneratedSqlAttempt> generated) {
        return generated.stream()
                .filter(attempt -> attempt.attempt() == 1)
                .findFirst()
                .map(attempt -> {
                    try {
                        validator.validate(attempt.sql());
                        return true;
                    } catch (RuntimeException exception) {
                        return false;
                    }
                })
                .orElse(false);
    }

    private NlExperimentSummary summarize(List<NlQuestionResult> results) {
        int total = results.size();
        int correct = (int) results.stream().filter(NlQuestionResult::correct).count();
        int generated = (int) results.stream().filter(NlQuestionResult::generationSucceeded).count();
        int firstPass = (int) results.stream().filter(NlQuestionResult::firstAttemptValidationPassed).count();
        List<Integer> attempts = results.stream()
                .map(NlQuestionResult::attemptCount)
                .sorted()
                .toList();

        Map<QuestionDifficulty, DifficultyAccuracy> byDifficulty = new EnumMap<>(QuestionDifficulty.class);
        for (QuestionDifficulty difficulty : QuestionDifficulty.values()) {
            List<NlQuestionResult> subset = results.stream()
                    .filter(result -> result.difficulty() == difficulty)
                    .toList();
            int subsetCorrect = (int) subset.stream().filter(NlQuestionResult::correct).count();
            byDifficulty.put(difficulty, new DifficultyAccuracy(
                    subset.size(), subsetCorrect, ratio(subsetCorrect, subset.size())));
        }

        Map<String, Integer> failureCounts = new LinkedHashMap<>();
        results.stream()
                .filter(result -> !result.correct())
                .map(NlQuestionResult::status)
                .sorted(Comparator.naturalOrder())
                .forEach(status -> failureCounts.merge(status, 1, Integer::sum));

        return new NlExperimentSummary(
                "COMPLETED",
                null,
                total,
                correct,
                ratio(correct, total),
                generated,
                ratio(generated, total),
                firstPass,
                ratio(firstPass, total),
                attempts.stream().mapToInt(Integer::intValue).average().orElse(0),
                median(attempts),
                byDifficulty,
                failureCounts
        );
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private double median(List<Integer> sorted) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0
                : sorted.get(middle);
    }
}
