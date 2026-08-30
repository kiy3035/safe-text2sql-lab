package dev.safetext2sql.experiment.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.safetext2sql.experiment.ExperimentMetadata;

/** 인덱스 실험의 메타데이터, 원본 측정치와 요약 CSV를 기록한다. */
public final class IndexBenchmarkArtifactsWriter {

    private final ObjectMapper objectMapper;

    public IndexBenchmarkArtifactsWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(Path directory, ExperimentMetadata metadata, IndexBenchmarkResult result) throws IOException {
        Files.createDirectories(directory);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("metadata.json").toFile(), metadata);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                directory.resolve("index-measurements.json").toFile(), result);
        Files.writeString(
                directory.resolve("index-summary.csv"),
                toCsv(result),
                StandardCharsets.UTF_8
        );
    }

    private String toCsv(IndexBenchmarkResult result) {
        StringBuilder csv = new StringBuilder()
                .append("caseId,condition,repetitions,scanType,plannerEstimatedRowsMedian,")
                .append("actualRowsMedian,resultRowsMedian,rowsRemovedByFilterMedian,")
                .append("sharedHitBlocksMedian,sharedReadBlocksMedian,planningTimeMedianMs,")
                .append("executionTimeMedianMs,executionTimeMinMs,executionTimeMaxMs,executionTimeP95Ms\n");
        for (IndexConditionSummary summary : result.summaries()) {
            csv.append(summary.caseId()).append(',')
                    .append(summary.condition()).append(',')
                    .append(summary.repetitions()).append(',')
                    .append(summary.scanType()).append(',')
                    .append(summary.plannerEstimatedRowsMedian()).append(',')
                    .append(summary.actualRowsMedian()).append(',')
                    .append(summary.resultRowsMedian()).append(',')
                    .append(summary.rowsRemovedByFilterMedian()).append(',')
                    .append(summary.sharedHitBlocksMedian()).append(',')
                    .append(summary.sharedReadBlocksMedian()).append(',')
                    .append(summary.planningTimeMedianMs()).append(',')
                    .append(summary.executionTimeMedianMs()).append(',')
                    .append(summary.executionTimeMinMs()).append(',')
                    .append(summary.executionTimeMaxMs()).append(',')
                    .append(summary.executionTimeP95Ms()).append('\n');
        }
        return csv.toString();
    }
}
