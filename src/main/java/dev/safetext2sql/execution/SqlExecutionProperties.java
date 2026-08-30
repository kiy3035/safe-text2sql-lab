package dev.safetext2sql.execution;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 검증된 SELECT 실행 시 강제할 자원 제한이다.
 *
 * @param maxRows API가 반환할 최대 행 수. 실행기는 한 행을 더 조회해 잘림 여부를 판별한다.
 * @param statementTimeout PostgreSQL 트랜잭션 로컬 statement_timeout
 */
@ConfigurationProperties("text2sql.execution")
public record SqlExecutionProperties(int maxRows, Duration statementTimeout) {

    private static final int ABSOLUTE_MAX_ROWS = 1_000;
    private static final Duration ABSOLUTE_MAX_TIMEOUT = Duration.ofSeconds(30);

    public SqlExecutionProperties {
        if (maxRows < 1 || maxRows > ABSOLUTE_MAX_ROWS) {
            throw new IllegalArgumentException("max rows must be between 1 and " + ABSOLUTE_MAX_ROWS);
        }
        if (statementTimeout == null
                || statementTimeout.isZero()
                || statementTimeout.isNegative()
                || statementTimeout.compareTo(ABSOLUTE_MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("statement timeout must be between 1ms and 30s");
        }
    }
}
