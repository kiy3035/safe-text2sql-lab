package dev.safetext2sql.experiment;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 결과를 다른 실행과 비교할 수 있도록 로컬 환경과 합성 DB 정보를 수집한다.
 *
 * <p>DB 비밀번호나 JDBC URL은 결과에 포함하지 않는다. commit SHA는 환경 변수
 * {@code EXPERIMENT_COMMIT_SHA}가 없으면 인자가 없는 고정 명령 {@code git rev-parse HEAD}로
 * 확인하며, 확인할 수 없으면 측정을 중단해 출처 불명 결과를 만들지 않는다.</p>
 */
@Component
public final class ExperimentMetadataCollector {

    private final JdbcTemplate jdbcTemplate;

    public ExperimentMetadataCollector(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** 현재 DB·JVM·Git 기준의 메타데이터를 만든다. */
    public ExperimentMetadata collect(
            String runId,
            String modelName,
            Double temperature,
            String systemPrompt
    ) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new String[] {
                "categories", "products", "customers", "orders", "order_items", "payments"
        }) {
            // 테이블명은 외부 입력이 아니라 이 고정 목록에서만 가져온다.
            Long count = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
            counts.put(table, count == null ? 0L : count);
        }

        return new ExperimentMetadata(
                runId,
                Instant.now(),
                resolveCommitSha(),
                System.getProperty("os.name") + " " + System.getProperty("os.version")
                        + " (" + System.getProperty("os.arch") + ")",
                resolveCpu(),
                resolveRamBytes(),
                System.getProperty("java.version"),
                jdbcTemplate.queryForObject("show server_version", String.class),
                environmentOrDefault("OLLAMA_VERSION", "PENDING"),
                modelName,
                temperature,
                systemPrompt == null ? null : sha256(systemPrompt),
                counts
        );
    }

    private String resolveCommitSha() {
        String explicit = System.getenv("EXPERIMENT_COMMIT_SHA");
        if (explicit != null && explicit.matches("[0-9a-fA-F]{7,40}")) {
            return explicit;
        }
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() == 0 && output.matches("[0-9a-fA-F]{40}")) {
                return output;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot resolve experiment commit SHA", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("commit SHA lookup was cancelled", exception);
        }
        throw new IllegalStateException("EXPERIMENT_COMMIT_SHA or a Git checkout is required");
    }

    private String resolveCpu() {
        String processor = System.getenv("PROCESSOR_IDENTIFIER");
        if (processor != null && !processor.isBlank()) {
            return processor;
        }
        return Runtime.getRuntime().availableProcessors() + " logical processors";
    }

    private long resolveRamBytes() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
            return extended.getTotalMemorySize();
        }
        return -1L;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available in Java 21", exception);
        }
    }

    private String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
