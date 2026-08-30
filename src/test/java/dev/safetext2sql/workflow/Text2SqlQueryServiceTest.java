package dev.safetext2sql.workflow;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import dev.safetext2sql.execution.QueryExecutionException;
import dev.safetext2sql.execution.QueryExecutionFailure;
import dev.safetext2sql.execution.QueryResult;
import dev.safetext2sql.execution.SqlQueryExecutor;
import dev.safetext2sql.llm.SqlGenerator;
import dev.safetext2sql.llm.ollama.OllamaHttpException;
import dev.safetext2sql.llm.support.ScriptedSqlGenerator;
import dev.safetext2sql.sql.validation.AnalyticsSqlPolicy;
import dev.safetext2sql.sql.validation.AstSqlValidator;
import dev.safetext2sql.sql.validation.ValidatedSelect;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 전체 attempt 예산과 오류별 재시도 여부를 실제 sleep·DB 없이 결정적으로 검증한다.
 *
 * <p>각 테스트는 LLM 호출 수뿐 아니라 실행기 호출 수와 기록된 backoff를 함께 확인한다.
 * 검증 실패 SQL이나 4xx 응답이 실행기로 흘러가지 않는지가 핵심 보안 단언이다.</p>
 */
class Text2SqlQueryServiceTest {

    private static final String VALID_SQL = "SELECT order_id FROM orders ORDER BY order_id LIMIT 1";
    private static final QueryResult SUCCESS_RESULT = new QueryResult(
            List.of("order_id"), List.of(List.of(1L)), false);

    @Test
    void retriesTwoServerErrorsAndSucceedsOnTheThirdAttempt() {
        ScriptedSqlGenerator generator = ScriptedSqlGenerator.builder()
                .thenThrow(new OllamaHttpException(500))
                .thenThrow(new OllamaHttpException(503))
                .thenReturn(VALID_SQL)
                .build();
        RecordingExecutor executor = RecordingExecutor.succeeding();
        RecordingSleeper sleeper = new RecordingSleeper();

        Text2SqlQueryResult result = service(generator, executor, sleeper).query("첫 주문을 알려줘");

        assertThat(result.attemptCount()).isEqualTo(3);
        assertThat(generator.requests()).hasSize(3);
        assertThat(executor.invocations()).hasSize(1);
        assertThat(sleeper.delays()).containsExactly(Duration.ofMillis(200), Duration.ofMillis(400));
    }

    @Test
    void stopsAfterThreeServerErrorsWithoutCallingTheExecutor() {
        ScriptedSqlGenerator generator = ScriptedSqlGenerator.builder()
                .thenThrow(new OllamaHttpException(500))
                .thenThrow(new OllamaHttpException(500))
                .thenThrow(new OllamaHttpException(500))
                .build();
        RecordingExecutor executor = RecordingExecutor.succeeding();
        RecordingSleeper sleeper = new RecordingSleeper();

        assertWorkflowFailure(
                () -> service(generator, executor, sleeper).query("주문을 알려줘"),
                QueryWorkflowFailure.LLM_UNAVAILABLE,
                3
        );
        assertThat(generator.requests()).hasSize(3);
        assertThat(executor.invocations()).isEmpty();
        assertThat(sleeper.delays()).containsExactly(Duration.ofMillis(200), Duration.ofMillis(400));
    }

    @Test
    void doesNotRetryClientErrors() {
        ScriptedSqlGenerator generator = ScriptedSqlGenerator.builder()
                .thenThrow(new OllamaHttpException(400))
                .build();
        RecordingExecutor executor = RecordingExecutor.succeeding();
        RecordingSleeper sleeper = new RecordingSleeper();

        assertWorkflowFailure(
                () -> service(generator, executor, sleeper).query("주문을 알려줘"),
                QueryWorkflowFailure.LLM_REQUEST_REJECTED,
                1
        );
        assertThat(generator.requests()).hasSize(1);
        assertThat(executor.invocations()).isEmpty();
        assertThat(sleeper.delays()).isEmpty();
    }

    @Test
    void regeneratesAfterValidationFailureWithoutExecutingTheRejectedSql() {
        ScriptedSqlGenerator generator = ScriptedSqlGenerator.builder()
                .thenReturn("SELECT * FROM orders")
                .thenReturn(VALID_SQL)
                .build();
        RecordingExecutor executor = RecordingExecutor.succeeding();
        RecordingSleeper sleeper = new RecordingSleeper();

        Text2SqlQueryResult result = service(generator, executor, sleeper).query("첫 주문을 알려줘");

        assertThat(result.attemptCount()).isEqualTo(2);
        assertThat(executor.invocations()).hasSize(1);
        assertThat(executor.invocations().getFirst().sql()).isEqualTo(VALID_SQL);
        assertThat(generator.requests().get(1).feedbackCodes()).containsExactly("WILDCARD_NOT_ALLOWED");
        assertThat(sleeper.delays()).containsExactly(Duration.ofMillis(200));
    }

    @Test
    void exhaustsValidationFailuresWithoutCallingTheExecutor() {
        ScriptedSqlGenerator generator = ScriptedSqlGenerator.builder()
                .thenReturn("SELECT * FROM orders")
                .thenReturn("DELETE FROM orders")
                .thenReturn("SELECT email FROM customers")
                .build();
        RecordingExecutor executor = RecordingExecutor.succeeding();
        RecordingSleeper sleeper = new RecordingSleeper();

        assertWorkflowFailure(
                () -> service(generator, executor, sleeper).query("안전하지 않은 질의"),
                QueryWorkflowFailure.SQL_GENERATION_FAILED,
                3
        );
        assertThat(generator.requests()).hasSize(3);
        assertThat(executor.invocations()).isEmpty();
        assertThat(sleeper.delays()).containsExactly(Duration.ofMillis(200), Duration.ofMillis(400));
    }

    @Test
    void regeneratesAfterARecoverableDatabaseIdentifierError() {
        ScriptedSqlGenerator generator = ScriptedSqlGenerator.builder()
                .thenReturn(VALID_SQL)
                .thenReturn(VALID_SQL)
                .build();
        RecordingExecutor executor = new RecordingExecutor(List.of(
                new QueryExecutionException(QueryExecutionFailure.RECOVERABLE_SQL),
                SUCCESS_RESULT
        ));
        RecordingSleeper sleeper = new RecordingSleeper();

        Text2SqlQueryResult result = service(generator, executor, sleeper).query("첫 주문을 알려줘");

        assertThat(result.attemptCount()).isEqualTo(2);
        assertThat(executor.invocations()).hasSize(2);
        assertThat(generator.requests().get(1).feedbackCodes()).containsExactly("DB_RECOVERABLE_SQL");
        assertThat(sleeper.delays()).containsExactly(Duration.ofMillis(200));
    }

    @Test
    void doesNotRetryDatabasePermissionErrors() {
        assertNonRetryableDatabaseFailure(
                QueryExecutionFailure.PERMISSION_DENIED,
                QueryWorkflowFailure.DB_PERMISSION_DENIED
        );
    }

    @Test
    void doesNotRetryStatementTimeouts() {
        assertNonRetryableDatabaseFailure(
                QueryExecutionFailure.STATEMENT_TIMEOUT,
                QueryWorkflowFailure.DB_TIMEOUT
        );
    }

    @Test
    void rejectsBlankOrOversizedQuestionsBeforeCallingTheLlm() {
        ScriptedSqlGenerator generator = ScriptedSqlGenerator.builder().thenReturn(VALID_SQL).build();
        RecordingExecutor executor = RecordingExecutor.succeeding();

        assertWorkflowFailure(
                () -> service(generator, executor, new RecordingSleeper()).query("  "),
                QueryWorkflowFailure.INVALID_QUESTION,
                0
        );
        assertWorkflowFailure(
                () -> service(generator, executor, new RecordingSleeper()).query("가".repeat(1_001)),
                QueryWorkflowFailure.INVALID_QUESTION,
                0
        );
        assertThat(generator.requests()).isEmpty();
        assertThat(executor.invocations()).isEmpty();
    }

    @Test
    void reportsUnavailableWhenNoGeneratorIsConfigured() {
        RecordingExecutor executor = RecordingExecutor.succeeding();

        assertWorkflowFailure(
                () -> service(null, executor, new RecordingSleeper()).query("주문을 알려줘"),
                QueryWorkflowFailure.LLM_UNAVAILABLE,
                0
        );
        assertThat(executor.invocations()).isEmpty();
    }

    @Test
    void cancelsDuringBackoffWithoutStartingAnotherLlmAttempt() {
        ScriptedSqlGenerator generator = ScriptedSqlGenerator.builder()
                .thenReturn("SELECT * FROM orders")
                .thenReturn(VALID_SQL)
                .build();
        RecordingExecutor executor = RecordingExecutor.succeeding();
        Sleeper interruptingSleeper = duration -> {
            throw new InterruptedException("test cancellation");
        };

        try {
            assertWorkflowFailure(
                    () -> service(generator, executor, interruptingSleeper).query("주문을 알려줘"),
                    QueryWorkflowFailure.CANCELLED,
                    1
            );
            assertThat(generator.requests()).hasSize(1);
            assertThat(executor.invocations()).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // 다음 JUnit 테스트에 현재 스레드의 interrupt 상태가 전파되지 않도록 테스트가 직접 정리한다.
            Thread.interrupted();
        }
    }

    @Test
    void doesNotRetryUnclassifiedDatabaseErrors() {
        assertNonRetryableDatabaseFailure(
                QueryExecutionFailure.DATABASE_ERROR,
                QueryWorkflowFailure.DB_UNAVAILABLE
        );
    }

    private void assertNonRetryableDatabaseFailure(
            QueryExecutionFailure executionFailure,
            QueryWorkflowFailure workflowFailure
    ) {
        ScriptedSqlGenerator generator = ScriptedSqlGenerator.builder()
                .thenReturn(VALID_SQL)
                .thenReturn(VALID_SQL)
                .build();
        RecordingExecutor executor = new RecordingExecutor(List.of(new QueryExecutionException(executionFailure)));
        RecordingSleeper sleeper = new RecordingSleeper();

        assertWorkflowFailure(
                () -> service(generator, executor, sleeper).query("첫 주문을 알려줘"),
                workflowFailure,
                1
        );
        assertThat(generator.requests()).hasSize(1);
        assertThat(executor.invocations()).hasSize(1);
        assertThat(sleeper.delays()).isEmpty();
    }

    private Text2SqlQueryService service(
            SqlGenerator generator,
            SqlQueryExecutor executor,
            Sleeper sleeper
    ) {
        return new Text2SqlQueryService(
                generator,
                new AstSqlValidator(AnalyticsSqlPolicy.defaultPolicy()),
                executor,
                new QueryWorkflowProperties(3, Duration.ofMillis(200), 1_000),
                new ExponentialBackoffPolicy(Duration.ofMillis(200)),
                sleeper,
                new IncrementingNanoClock()
        );
    }

    private static void assertWorkflowFailure(
            ThrowingRunnable action,
            QueryWorkflowFailure failure,
            int attemptCount
    ) {
        assertThatThrownBy(action::run)
                .isInstanceOf(QueryWorkflowException.class)
                .satisfies(throwable -> {
                    QueryWorkflowException exception = (QueryWorkflowException) throwable;
                    assertThat(exception.failure()).isEqualTo(failure);
                    assertThat(exception.attemptCount()).isEqualTo(attemptCount);
                    assertThat(exception.getMessage()).isEqualTo(failure.name());
                });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    private static final class RecordingSleeper implements Sleeper {
        private final List<Duration> delays = new ArrayList<>();

        @Override
        public void sleep(Duration duration) {
            delays.add(duration);
        }

        private List<Duration> delays() {
            return List.copyOf(delays);
        }
    }

    private static final class IncrementingNanoClock implements NanoClock {
        private long current;

        @Override
        public long nanoTime() {
            current += 1_000_000;
            return current;
        }
    }

    private static final class RecordingExecutor implements SqlQueryExecutor {
        private final Deque<Object> outcomes;
        private final List<ValidatedSelect> invocations = new ArrayList<>();

        private RecordingExecutor(List<?> outcomes) {
            this.outcomes = new ArrayDeque<>(outcomes);
        }

        private static RecordingExecutor succeeding() {
            return new RecordingExecutor(List.of(SUCCESS_RESULT));
        }

        @Override
        public QueryResult execute(ValidatedSelect validatedSelect) {
            invocations.add(validatedSelect);
            Object outcome = outcomes.pollFirst();
            if (outcome instanceof QueryExecutionException exception) {
                throw exception;
            }
            if (outcome instanceof QueryResult result) {
                return result;
            }
            throw new IllegalStateException("No executor outcome remains");
        }

        private List<ValidatedSelect> invocations() {
            return List.copyOf(invocations);
        }
    }
}
