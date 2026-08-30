package dev.safetext2sql.workflow;

import java.time.Duration;

/** 실제 애플리케이션에서 현재 요청 스레드를 지정된 시간만큼 대기시키는 구현체다. */
public final class ThreadSleeper implements Sleeper {

    @Override
    public void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration);
    }
}
