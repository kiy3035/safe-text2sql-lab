package dev.safetext2sql.experiment.nl;

import java.nio.file.Path;
import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.safetext2sql.experiment.ExperimentMetadata;
import dev.safetext2sql.experiment.ExperimentMetadataCollector;
import dev.safetext2sql.experiment.ExperimentRunDirectory;
import dev.safetext2sql.llm.SqlGenerator;
import dev.safetext2sql.llm.config.OllamaProperties;
import dev.safetext2sql.llm.prompt.SqlPromptTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 자연어 50건 실험의 fixture 로딩, 실행, 결과 파일 기록을 한 번에 수행하는 명령 객체다.
 *
 * <p>Ollama provider가 꺼져 있으면 Golden SQL 50건의 검증·DB 실행은 수행하되 정확도는
 * {@code PENDING}으로 기록한다. Fake 결과를 실제 모델 정확도처럼 저장하지 않는다.</p>
 */
@Component
public final class NlAccuracyExperimentCommand {

    private final ObjectMapper objectMapper;
    private final ObjectProvider<SqlGenerator> generatorProvider;
    private final ObjectProvider<OllamaProperties> ollamaPropertiesProvider;
    private final NlAccuracyExperimentRunner runner;
    private final ExperimentMetadataCollector metadataCollector;

    public NlAccuracyExperimentCommand(
            ObjectMapper objectMapper,
            ObjectProvider<SqlGenerator> generatorProvider,
            ObjectProvider<OllamaProperties> ollamaPropertiesProvider,
            NlAccuracyExperimentRunner runner,
            ExperimentMetadataCollector metadataCollector
    ) {
        this.objectMapper = objectMapper;
        this.generatorProvider = generatorProvider;
        this.ollamaPropertiesProvider = ollamaPropertiesProvider;
        this.runner = runner;
        this.metadataCollector = metadataCollector;
    }

    /** 실험을 수행하고 생성된 결과 디렉터리를 반환한다. */
    public Path run() throws Exception {
        ExperimentRunDirectory.Resolved output = ExperimentRunDirectory.resolve("nl", Clock.systemUTC());
        NlFixtureSet fixtures = new NlFixtureLoader(objectMapper).load(
                Path.of("experiments", "nl-questions.jsonl"),
                Path.of("experiments", "golden-sql.jsonl")
        );

        SqlGenerator generator = generatorProvider.getIfAvailable();
        OllamaProperties ollama = ollamaPropertiesProvider.getIfAvailable();
        String model = ollama == null ? "PENDING" : ollama.model();
        Double temperature = ollama == null ? null : ollama.temperature();
        String prompt = new SqlPromptTemplate().systemPrompt();

        NlExperimentRun run = runner.run(fixtures, generator);
        ExperimentMetadata metadata = metadataCollector.collect(
                output.runId(), model, temperature, prompt);
        new NlExperimentArtifactsWriter(objectMapper).write(output.directory(), metadata, run);
        return output.directory();
    }
}
