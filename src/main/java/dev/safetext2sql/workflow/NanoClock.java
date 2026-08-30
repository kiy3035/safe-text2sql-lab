package dev.safetext2sql.workflow;

/** 응답 경과 시간을 측정하면서 테스트에서는 결정적인 값을 주입할 수 있게 하는 단조 시계 경계다. */
@FunctionalInterface
public interface NanoClock {

    long nanoTime();
}
