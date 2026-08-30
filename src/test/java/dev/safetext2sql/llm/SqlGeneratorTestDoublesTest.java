package dev.safetext2sql.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.safetext2sql.llm.support.FakeSqlGenerator;
import dev.safetext2sql.llm.support.ScriptedSqlGenerator;
import org.junit.jupiter.api.Test;

class SqlGeneratorTestDoublesTest {

    @Test
    void fakeAlwaysReturnsConfiguredUntrustedSqlAndRecordsRequests() {
        var generated = new GeneratedSql("DROP TABLE orders", "fake-model");
        var fake = new FakeSqlGenerator(generated);
        var request = SqlGenerationRequest.firstAttempt("주문 수");

        assertThat(fake.generate(request)).isSameAs(generated);
        assertThat(fake.requests()).containsExactly(request);
    }

    @Test
    void scriptedGeneratorReturnsAndThrowsInConfiguredOrder() {
        var failure = new IllegalStateException("scripted failure");
        var scripted = ScriptedSqlGenerator.builder()
                .thenReturn("SELECT order_id FROM orders")
                .thenThrow(failure)
                .build();
        var first = SqlGenerationRequest.firstAttempt("주문 목록");
        var second = SqlGenerationRequest.firstAttempt("다시 생성");

        assertThat(scripted.generate(first).sql()).isEqualTo("SELECT order_id FROM orders");
        assertThatThrownBy(() -> scripted.generate(second)).isSameAs(failure);
        assertThat(scripted.requests()).containsExactly(first, second);
        assertThat(scripted.remainingStepCount()).isZero();
    }
}
