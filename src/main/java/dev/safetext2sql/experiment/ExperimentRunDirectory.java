package dev.safetext2sql.experiment;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 측정 결과가 저장될 안전한 run ID와 디렉터리를 결정한다. */
public final class ExperimentRunDirectory {

    private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private ExperimentRunDirectory() {
    }

    /**
     * 환경 변수의 run ID에는 경로 구분자를 허용하지 않는다. 기본값은 UTC 시각이다.
     * 결과 루트는 기본적으로 저장소의 ignore 대상인 {@code results}다.
     */
    public static Resolved resolve(String prefix, Clock clock) {
        String explicitRunId = System.getenv("EXPERIMENT_RUN_ID");
        String runId = explicitRunId == null || explicitRunId.isBlank()
                ? prefix + "-" + RUN_ID_FORMAT.format(clock.instant())
                : explicitRunId;
        if (!runId.matches("[A-Za-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("EXPERIMENT_RUN_ID contains unsupported characters");
        }

        String explicitRoot = System.getenv("EXPERIMENT_RESULTS_DIR");
        Path root = explicitRoot == null || explicitRoot.isBlank()
                ? Path.of("results")
                : Path.of(explicitRoot);
        return new Resolved(runId, root.resolve(runId).normalize());
    }

    /** 실행 ID와 해당 결과 디렉터리다. */
    public record Resolved(String runId, Path directory) {
    }
}
