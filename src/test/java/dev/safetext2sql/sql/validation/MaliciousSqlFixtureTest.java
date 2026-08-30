package dev.safetext2sql.sql.validation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        RecordingNativeQueryExecutorBoundary executor = new RecordingNativeQueryExecutorBoundary();

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

    private void validateThenExecute(String untrustedSql, RecordingNativeQueryExecutorBoundary executor) {
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
     * 테스트 전용 실행 경계(seam) - 호출 여부만 기록하는 가짜(Fake) 구현체.
     * <p>
     * 현재는 검증 통과 여부를 확인하기 위해 {@code invocationCount}만 기록한다.
     * 4단계(SQL 실행과 재시도)에서 실제 {@code EntityManager.createNativeQuery(...)} 기반
     * 구현체로 교체될 예정이다. 보안 테스트의 핵심은 예외 발생 여부만이 아니라
     * {@code executor 호출 횟수 == 0}임을 보장하는 것이다.
     * </p>
     */
    private static final class RecordingNativeQueryExecutorBoundary {

        private final AtomicInteger invocationCount = new AtomicInteger();

        private void execute(ValidatedSelect validatedSelect) {
            if (validatedSelect == null) {
                throw new IllegalArgumentException("validatedSelect");
            }
            invocationCount.incrementAndGet();
        }

        private int invocationCount() {
            return invocationCount.get();
        }
    }
}
