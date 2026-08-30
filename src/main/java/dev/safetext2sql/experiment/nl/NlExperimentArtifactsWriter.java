package dev.safetext2sql.experiment.nl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.safetext2sql.experiment.ExperimentMetadata;

/** 자연어 실험의 메타데이터·요약·질문별 원본을 JSON과 CSV로 함께 기록한다. */
public final class NlExperimentArtifactsWriter {

    private final ObjectMapper objectMapper;

    public NlExperimentArtifactsWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(Path directory, ExperimentMetadata metadata, NlExperimentRun run) throws IOException {
        Files.createDirectories(directory);
        writeJson(directory.resolve("metadata.json"), metadata);
        writeJson(directory.resolve("nl-summary.json"), run.summary());
        writeJson(directory.resolve("nl-results.json"), run.results());
        Files.writeString(
                directory.resolve("nl-results.csv"),
                toCsv(run.results()),
                StandardCharsets.UTF_8
        );
    }

    private void writeJson(Path path, Object value) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private String toCsv(List<NlQuestionResult> results) {
        StringBuilder csv = new StringBuilder()
                .append("id,difficulty,comparison,status,correct,attemptCount,generationSucceeded,")
                .append("firstAttemptValidationPassed,failureCode,elapsedMs,models,generatedSql\n");
        for (NlQuestionResult result : results) {
            String models = result.generatedSql().stream()
                    .map(GeneratedSqlAttempt::model)
                    .distinct()
                    .collect(Collectors.joining(";"));
            String generatedSql = result.generatedSql().stream()
                    .map(attempt -> attempt.attempt() + ":" + attempt.sql())
                    .collect(Collectors.joining(" || "));
            csv.append(csv(result.id())).append(',')
                    .append(result.difficulty()).append(',')
                    .append(result.comparison()).append(',')
                    .append(result.status()).append(',')
                    .append(result.correct()).append(',')
                    .append(result.attemptCount()).append(',')
                    .append(result.generationSucceeded()).append(',')
                    .append(result.firstAttemptValidationPassed()).append(',')
                    .append(csv(result.failureCode())).append(',')
                    .append(result.elapsedMs()).append(',')
                    .append(csv(models)).append(',')
                    .append(csv(generatedSql)).append('\n');
        }
        return csv.toString();
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        return '"' + value.replace("\r", " ").replace("\n", " ").replace("\"", "\"\"") + '"';
    }
}
