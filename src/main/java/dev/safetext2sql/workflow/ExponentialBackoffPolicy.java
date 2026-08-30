package dev.safetext2sql.workflow;

import java.time.Duration;
import java.util.Objects;

/**
 * 두 번째 호출 200ms, 세 번째 호출 400ms 형태의 지수 백오프를 계산한다.
 *
 * <p>LLM 5xx 전용 카운터를 따로 두지 않고 전체 attempt 번호를 사용한다. 따라서 첫 실패가
 * 검증 오류이고 다음 실패가 5xx여도 세 번째 LLM 호출 전에는 일관되게 400ms를 적용한다.</p>
 */
public final class ExponentialBackoffPolicy implements BackoffPolicy {

    private final Duration initialDelay;

    public ExponentialBackoffPolicy(Duration initialDelay) {
        this.initialDelay = Objects.requireNonNull(initialDelay, "initialDelay");
        if (initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initial delay must be positive");
        }
    }

    @Override
    public Duration delayBeforeAttempt(int attemptNumber) {
        if (attemptNumber < 2) {
            throw new IllegalArgumentException("backoff is defined from attempt 2");
        }
        long multiplier = 1L << Math.min(attemptNumber - 2, 20);
        return initialDelay.multipliedBy(multiplier);
    }
}
