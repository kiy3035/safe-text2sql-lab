package dev.safetext2sql.workflow;

import java.time.Duration;

/**
 * 두 번째 이후 LLM 호출 직전에 적용할 지연을 계산한다.
 */
@FunctionalInterface
public interface BackoffPolicy {

    /**
     * @param attemptNumber 1부터 시작하는 전체 LLM 호출 번호
     * @return 해당 호출 직전에 기다릴 시간. 첫 호출에는 사용하지 않는다.
     */
    Duration delayBeforeAttempt(int attemptNumber);
}
