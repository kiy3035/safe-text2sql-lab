package dev.safetext2sql.sql.validation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.safetext2sql.execution.QueryResult;
import dev.safetext2sql.execution.SqlQueryExecutor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 버전 관리되는 악의적 SQL 20건이 실행 경계보다 앞에서 모두 차단되는지 검증한다.
 *
 * <p>예외만 확인하면 검증 실패 뒤 실행기가 잘못 호출되는 회귀를 놓칠 수 있으므로,
 * 각 fixture 처리 직후 테스트 실행 경계 호출 횟수가 정확히 0인지 함께 확인한다.</p>
 */
class MaliciousSqlFixtureTest {

    private final SqlValidator validator = new AstSqlValidator(AnalyticsSqlPolicy.defaultPolicy());

    @Test
    void blocksAllTwentyVersionedAttacksBeforeTheExecutorBoundary() throws IOException {
        List<MaliciousSqlCase> fixtures = loadFixtures();
        RecordingSqlQueryExecutor executor = new RecordingSqlQueryExecutor();

        assertThat(fixtures).hasSize(20);
        assertThat(fixtures).extracting(MaliciousSqlCase::id).doesNotHaveDuplicates();
        assertThat(fixtures).extracting(MaliciousSqlCase::category).doesNotHaveDuplicates();

        for (MaliciousSqlCase fixture : fixtures) {
            assertThatThrownBy(() -> validateThenExecute(fixture.sql(), executor))
                    .as("%s (%s)", fixture.id(), fixture.category())
                    .isInstanceOf(SqlValidationException.class)
                    .extracting(exception -> ((SqlValidationException) exception).reason().name())
                    .isEqualTo(fixture.expectedReason());
            assertThat(executor.invocationCount())
                    .as("executor invocation count after %s", fixture.id())
                    .isZero();
        }
    }

    private void validateThenExecute(String untrustedSql, SqlQueryExecutor executor) {
        ValidatedSelect validated = validator.validate(untrustedSql);
        executor.execute(validated);
    }

    private List<MaliciousSqlCase> loadFixtures() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/security/malicious-sql.json")) {
            if (stream == null) {
                throw new IllegalStateException("malicious SQL fixture is missing");
            }
            return Arrays.asList(new ObjectMapper().readValue(stream, MaliciousSqlCase[].class));
        }
    }

    private record MaliciousSqlCase(String id, String category, String sql, String expectedReason) {
    }

    /**
     * 실제 운영 실행기 인터페이스의 호출 여부만 기록하는 테스트용 가짜(Fake) 구현체다.
     * <p>
     * 검증 실패가 {@link SqlQueryExecutor} 경계까지 도달하지 않는지 확인하기 위해
     * {@code invocationCount}만 기록한다. 보안 테스트의 핵심은 예외 발생 여부만이 아니라
     * {@code executor 호출 횟수 == 0}임을 보장하는 것이다.
     * </p>
     */
    private static final class RecordingSqlQueryExecutor implements SqlQueryExecutor {

        private final AtomicInteger invocationCount = new AtomicInteger();

        @Override
        public QueryResult execute(ValidatedSelect validatedSelect) {
            if (validatedSelect == null) {
                throw new IllegalArgumentException("validatedSelect");
            }
            invocationCount.incrementAndGet();
            return new QueryResult(List.of("unused"), List.of(), false);
        }

        private int invocationCount() {
            return invocationCount.get();
        }
    }
}
