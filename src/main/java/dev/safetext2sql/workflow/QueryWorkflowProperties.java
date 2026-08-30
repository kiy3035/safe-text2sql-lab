package dev.safetext2sql.workflow;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 생성부터 검증·실행까지 한 요청에 적용하는 전체 예산이다.
 *
 * @param maxAttempts 최초 호출을 포함한 최대 LLM 호출 수. 보안 규칙상 3을 넘을 수 없다.
 * @param initialBackoff 두 번째 LLM 호출 직전의 기본 지연
 * @param maxQuestionLength 허용할 자연어 질문 최대 길이
 */
@ConfigurationProperties("text2sql.workflow")
public record QueryWorkflowProperties(
        int maxAttempts,
        Duration initialBackoff,
        int maxQuestionLength
) {

    public QueryWorkflowProperties {
        if (maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException("max attempts must be between 1 and 3");
        }
        if (initialBackoff == null
                || initialBackoff.isZero()
                || initialBackoff.isNegative()
                || initialBackoff.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("initial backoff must be between 1ms and 10s");
        }
        if (maxQuestionLength < 1 || maxQuestionLength > 10_000) {
            throw new IllegalArgumentException("max question length must be between 1 and 10000");
        }
    }
}
