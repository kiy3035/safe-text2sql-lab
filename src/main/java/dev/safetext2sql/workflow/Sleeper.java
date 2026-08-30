package dev.safetext2sql.workflow;

import java.time.Duration;

/**
 * 실제 대기와 테스트용 기록 대기를 교체하기 위한 시간 경계다.
 */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;
}
