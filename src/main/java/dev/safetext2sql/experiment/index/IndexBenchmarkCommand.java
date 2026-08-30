package dev.safetext2sql.experiment.index;

import java.nio.file.Path;
import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.safetext2sql.experiment.ExperimentMetadata;
import dev.safetext2sql.experiment.ExperimentMetadataCollector;
import dev.safetext2sql.experiment.ExperimentRunDirectory;
import org.springframework.stereotype.Component;

/** 인덱스 benchmark 실행과 결과 파일 생성을 조립하는 명령 객체다. */
@Component
public final class IndexBenchmarkCommand {

    private final IndexBenchmarkRunner runner;
    private final ExperimentMetadataCollector metadataCollector;
    private final ObjectMapper objectMapper;

    public IndexBenchmarkCommand(
            IndexBenchmarkRunner runner,
            ExperimentMetadataCollector metadataCollector,
            ObjectMapper objectMapper
    ) {
        this.runner = runner;
        this.metadataCollector = metadataCollector;
        this.objectMapper = objectMapper;
    }

    /** 측정을 수행하고 생성된 결과 디렉터리를 반환한다. */
    public Path run() throws Exception {
        ExperimentRunDirectory.Resolved output = ExperimentRunDirectory.resolve("index", Clock.systemUTC());
        IndexBenchmarkResult result = runner.run(output.directory());
        ExperimentMetadata metadata = metadataCollector.collect(
                output.runId(), "NOT_APPLICABLE", null, null);
        new IndexBenchmarkArtifactsWriter(objectMapper).write(output.directory(), metadata, result);
        return output.directory();
    }
}
