package dev.safetext2sql.workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import dev.safetext2sql.execution.QueryExecutionException;
import dev.safetext2sql.execution.QueryExecutionFailure;
import dev.safetext2sql.execution.QueryResult;
import dev.safetext2sql.execution.SqlQueryExecutor;
import dev.safetext2sql.llm.GeneratedSql;
import dev.safetext2sql.llm.SqlGenerationRequest;
import dev.safetext2sql.llm.SqlGenerator;
import dev.safetext2sql.llm.ollama.OllamaHttpException;
import dev.safetext2sql.llm.ollama.OllamaProtocolException;
import dev.safetext2sql.llm.ollama.OllamaTransportException;
import dev.safetext2sql.sql.validation.SqlValidationException;
import dev.safetext2sql.sql.validation.SqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 자연어 질문을 LLM 생성 → AST 검증 → 읽기 전용 실행 순서로 처리한다.
 *
 * <p>전체 LLM 호출 예산은 최대 3회이며, 재호출 직전에 attempt 번호에 맞는 200ms/400ms
 * 백오프를 적용한다. 검증 실패 SQL은 실행기로 전달하지 않고, 새로 생성된 SQL도 매번 같은
 * {@link SqlValidator}를 처음부터 통과해야 한다.</p>
 */
public final class Text2SqlQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(Text2SqlQueryService.class);

    private final SqlGenerator sqlGenerator;
    private final SqlValidator sqlValidator;
    private final SqlQueryExecutor queryExecutor;
    private final QueryWorkflowProperties properties;
    private final BackoffPolicy backoffPolicy;
    private final Sleeper sleeper;
    private final NanoClock nanoClock;

    public Text2SqlQueryService(
            SqlGenerator sqlGenerator,
            SqlValidator sqlValidator,
            SqlQueryExecutor queryExecutor,
            QueryWorkflowProperties properties,
            BackoffPolicy backoffPolicy,
            Sleeper sleeper,
            NanoClock nanoClock
    ) {
        // provider=disabled인 기본 로컬 실행도 Spring context가 시작되도록 null을 허용한다.
        // 실제 API 요청이 들어오면 SQL/질문 원문 없이 LLM_UNAVAILABLE로 안전하게 응답한다.
        this.sqlGenerator = sqlGenerator;
        this.sqlValidator = Objects.requireNonNull(sqlValidator, "sqlValidator");
        this.queryExecutor = Objects.requireNonNull(queryExecutor, "queryExecutor");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.backoffPolicy = Objects.requireNonNull(backoffPolicy, "backoffPolicy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public Text2SqlQueryResult query(String rawQuestion) {
        String question = validateQuestion(rawQuestion);
        if (sqlGenerator == null) {
            throw new QueryWorkflowException(QueryWorkflowFailure.LLM_UNAVAILABLE, 0);
        }

        long startedAt = nanoClock.nanoTime();
        String correlationId = UUID.randomUUID().toString();
        List<String> feedbackCodes = new ArrayList<>();

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            Duration backoff = waitBeforeRetry(attempt, correlationId, startedAt);

            GeneratedSql generatedSql;
            try {
                generatedSql = sqlGenerator.generate(new SqlGenerationRequest(question, feedbackCodes));
            } catch (OllamaHttpException exception) {
                logAttempt(correlationId, attempt, "LLM_HTTP_" + exception.statusCode(), backoff, startedAt);
                if (exception.isServerError() && attempt < properties.maxAttempts()) {
                    continue;
                }
                QueryWorkflowFailure failure = exception.isClientError()
                        ? QueryWorkflowFailure.LLM_REQUEST_REJECTED
                        : QueryWorkflowFailure.LLM_UNAVAILABLE;
                throw new QueryWorkflowException(failure, attempt);
            } catch (OllamaTransportException | OllamaProtocolException exception) {
                logAttempt(correlationId, attempt, "LLM_UNAVAILABLE", backoff, startedAt);
                throw new QueryWorkflowException(QueryWorkflowFailure.LLM_UNAVAILABLE, attempt);
            } catch (RuntimeException exception) {
                logAttempt(correlationId, attempt, "LLM_UNAVAILABLE", backoff, startedAt);
                throw new QueryWorkflowException(QueryWorkflowFailure.LLM_UNAVAILABLE, attempt);
            }
            if (generatedSql == null) {
                logAttempt(correlationId, attempt, "LLM_UNAVAILABLE", backoff, startedAt);
                throw new QueryWorkflowException(QueryWorkflowFailure.LLM_UNAVAILABLE, attempt);
            }

            try {
                var validatedSelect = sqlValidator.validate(generatedSql.sql());
                QueryResult queryResult = queryExecutor.execute(validatedSelect);
                logAttempt(correlationId, attempt, "SUCCESS", backoff, startedAt);
                return Text2SqlQueryResult.from(queryResult, attempt, elapsedMillis(startedAt));
            } catch (SqlValidationException exception) {
                logAttempt(
                        correlationId,
                        attempt,
                        "VALIDATION_" + exception.reason().name(),
                        backoff,
                        startedAt
                );
                feedbackCodes.add(exception.reason().name());
                if (attempt == properties.maxAttempts()) {
                    throw new QueryWorkflowException(QueryWorkflowFailure.SQL_GENERATION_FAILED, attempt);
                }
            } catch (QueryExecutionException exception) {
                logAttempt(
                        correlationId,
                        attempt,
                        "DB_" + exception.failure().name(),
                        backoff,
                        startedAt
                );
                if (exception.failure().retryable()) {
                    feedbackCodes.add("DB_" + exception.failure().name());
                    if (attempt < properties.maxAttempts()) {
                        continue;
                    }
                    throw new QueryWorkflowException(QueryWorkflowFailure.SQL_GENERATION_FAILED, attempt);
                }
                throw mapDatabaseFailure(exception.failure(), attempt);
            }
        }

        // for 조건과 최대 attempt 검증상 도달할 수 없지만, 향후 수정 시에도 성공으로 오인하지 않는다.
        throw new QueryWorkflowException(QueryWorkflowFailure.SQL_GENERATION_FAILED, properties.maxAttempts());
    }

    private String validateQuestion(String rawQuestion) {
        if (rawQuestion == null || rawQuestion.isBlank()) {
            throw new QueryWorkflowException(QueryWorkflowFailure.INVALID_QUESTION, 0);
        }
        String question = rawQuestion.trim();
        if (question.length() > properties.maxQuestionLength()) {
            throw new QueryWorkflowException(QueryWorkflowFailure.INVALID_QUESTION, 0);
        }
        return question;
    }

    private Duration waitBeforeRetry(int attempt, String correlationId, long startedAt) {
        if (attempt == 1) {
            return Duration.ZERO;
        }
        Duration delay = backoffPolicy.delayBeforeAttempt(attempt);
        try {
            sleeper.sleep(delay);
            return delay;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            // 대기 중 취소되면 새 LLM 호출은 시작되지 않았으므로 완료된 attempt 수를 기록한다.
            logAttempt(correlationId, attempt - 1, "CANCELLED", delay, startedAt);
            throw new QueryWorkflowException(QueryWorkflowFailure.CANCELLED, attempt - 1);
        }
    }

    /**
     * 질문과 SQL 원문 없이 시도 결과만 구조화된 key=value 형태로 남긴다.
     * correlation ID는 한 API 요청 안의 여러 시도를 연결하되 외부 입력을 포함하지 않는다.
     */
    private void logAttempt(
            String correlationId,
            int attempt,
            String resultCode,
            Duration backoff,
            long startedAt
    ) {
        LOGGER.info(
                "text2sql_attempt correlationId={} attempt={} resultCode={} backoffMs={} elapsedMs={}",
                correlationId,
                attempt,
                resultCode,
                backoff.toMillis(),
                elapsedMillis(startedAt)
        );
    }

    private QueryWorkflowException mapDatabaseFailure(QueryExecutionFailure failure, int attempt) {
        QueryWorkflowFailure workflowFailure = switch (failure) {
            case STATEMENT_TIMEOUT -> QueryWorkflowFailure.DB_TIMEOUT;
            case PERMISSION_DENIED -> QueryWorkflowFailure.DB_PERMISSION_DENIED;
            case DATABASE_ERROR -> QueryWorkflowFailure.DB_UNAVAILABLE;
            case RECOVERABLE_SQL -> QueryWorkflowFailure.SQL_GENERATION_FAILED;
        };
        return new QueryWorkflowException(workflowFailure, attempt);
    }

    private long elapsedMillis(long startedAt) {
        long elapsedNanos = Math.max(0, nanoClock.nanoTime() - startedAt);
        return Duration.ofNanos(elapsedNanos).toMillis();
    }

}
