package dev.safetext2sql.workflow;

import java.time.Duration;

import dev.safetext2sql.execution.SqlExecutionProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 외부 설정이 보안·자원 상한을 넘어 실행 정책을 약화할 수 없는지 검증한다.
 *
 * <p>환경 변수는 편의를 위한 조절점일 뿐이며, 최대 3회 호출·1,000행·30초라는 절대 상한은
 * 구성 객체 생성 시점에 실패하도록 고정한다.</p>
 */
class RuntimePropertiesTest {

    @Test
    void acceptsDocumentedDefaults() {
        assertThatCode(() -> new SqlExecutionProperties(200, Duration.ofSeconds(2)))
                .doesNotThrowAnyException();
        assertThatCode(() -> new QueryWorkflowProperties(3, Duration.ofMillis(200), 1_000))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsExecutionLimitsOutsideAbsoluteBounds() {
        assertThatThrownBy(() -> new SqlExecutionProperties(0, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SqlExecutionProperties(1_001, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SqlExecutionProperties(200, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SqlExecutionProperties(200, Duration.ofSeconds(31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRetryBudgetsAboveThreeCalls() {
        assertThatThrownBy(() -> new QueryWorkflowProperties(4, Duration.ofMillis(200), 1_000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
