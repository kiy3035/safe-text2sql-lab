package dev.safetext2sql.experiment.nl;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.safetext2sql.execution.QueryResult;
import dev.safetext2sql.llm.GeneratedSql;
import dev.safetext2sql.llm.SqlGenerator;
import dev.safetext2sql.sql.validation.AnalyticsSqlPolicy;
import dev.safetext2sql.sql.validation.AstSqlValidator;
import dev.safetext2sql.workflow.ExponentialBackoffPolicy;
import dev.safetext2sql.workflow.QueryWorkflowProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 50건 전체를 Fake 결과로 통과시켜 집계 분모와 PENDING 처리를 검증한다. */
class NlAccuracyExperimentRunnerTest {

    @Test
    void summarizesAllFiftyMatchingResultsWithoutComparingSqlStrings() throws Exception {
        NlFixtureSet fixtures = fixtures();
        Map<String, String> sqlByQuestion = new HashMap<>();
        Map<String, QueryResult> resultBySql = new HashMap<>();
        int index = 0;
        for (NlQuestion question : fixtures.questions()) {
            String sql = fixtures.goldenSqlFor(question).sql();
            sqlByQuestion.put(question.question(), sql);
            resultBySql.put(sql, new QueryResult(
                    List.of("synthetic_value"), List.of(List.of((long) ++index)), false));
        }
        SqlGenerator generator = request -> new GeneratedSql(sqlByQuestion.get(request.question()), "fake-model");
        var validator = new AstSqlValidator(AnalyticsSqlPolicy.defaultPolicy());
        var runner = new NlAccuracyExperimentRunner(
                validator,
                validated -> resultBySql.get(validated.sql()),
                new QueryWorkflowProperties(3, Duration.ofMillis(200), 1_000),
                new ExponentialBackoffPolicy(Duration.ofMillis(200)),
                duration -> { },
                new IncrementingNanoClock()
        );

        NlExperimentRun run = runner.run(fixtures, generator);

        assertThat(run.results()).hasSize(50).allMatch(NlQuestionResult::correct);
        assertThat(run.summary().status()).isEqualTo("COMPLETED");
        assertThat(run.summary().correctCount()).isEqualTo(50);
        assertThat(run.summary().accuracy()).isEqualTo(1.0);
        assertThat(run.summary().averageAttemptCount()).isEqualTo(1.0);
        assertThat(run.summary().firstAttemptValidationPassCount()).isEqualTo(50);
    }

    @Test
    void executesGoldenSqlButLeavesAccuracyPendingWithoutOllama() throws Exception {
        NlFixtureSet fixtures = fixtures();
        Map<String, QueryResult> resultBySql = new HashMap<>();
        fixtures.goldenSqlById().values().forEach(golden -> resultBySql.put(
                golden.sql(), new QueryResult(List.of("value"), List.of(List.of(1L)), false)));
        int[] executions = {0};
        var runner = new NlAccuracyExperimentRunner(
                new AstSqlValidator(AnalyticsSqlPolicy.defaultPolicy()),
                validated -> {
                    executions[0]++;
                    return resultBySql.get(validated.sql());
                },
                new QueryWorkflowProperties(3, Duration.ofMillis(200), 1_000),
                new ExponentialBackoffPolicy(Duration.ofMillis(200)),
                duration -> { },
                new IncrementingNanoClock()
        );

        NlExperimentRun run = runner.run(fixtures, null);

        assertThat(executions[0]).isEqualTo(50);
        assertThat(run.results()).isEmpty();
        assertThat(run.summary().status()).isEqualTo("PENDING");
        assertThat(run.summary().pendingReason()).isEqualTo("OLLAMA_NOT_CONFIGURED");
        assertThat(run.summary().accuracy()).isNull();
    }

    private NlFixtureSet fixtures() throws Exception {
        return new NlFixtureLoader(new ObjectMapper()).load(
                Path.of("experiments", "nl-questions.jsonl"),
                Path.of("experiments", "golden-sql.jsonl")
        );
    }

    private static final class IncrementingNanoClock implements dev.safetext2sql.workflow.NanoClock {
        private long value;

        @Override
        public long nanoTime() {
            value += 1_000_000;
            return value;
        }
    }
}
