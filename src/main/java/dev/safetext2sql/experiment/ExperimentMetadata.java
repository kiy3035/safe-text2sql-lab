package dev.safetext2sql.experiment;

import java.time.Instant;
import java.util.Map;

/**
 * 모든 5단계 결과 파일에서 공통으로 보존하는 재현 환경 정보다.
 *
 * @param runId 한 번의 실행을 구분하는 사용자 지정 또는 시각 기반 ID
 * @param capturedAt 환경을 수집한 UTC 시각
 * @param commitSha 측정에 사용한 Git commit SHA
 * @param os 운영체제 이름·버전·아키텍처
 * @param cpu CPU 식별 문자열 또는 논리 프로세서 수
 * @param ramBytes JVM이 확인한 물리 메모리 바이트
 * @param javaVersion Java runtime 버전
 * @param postgresqlVersion 실제 연결한 PostgreSQL 버전
 * @param ollamaVersion Ollama가 제공되지 않으면 PENDING
 * @param modelName 실제 모델명·버전 또는 PENDING/NOT_APPLICABLE
 * @param temperature LLM 실험 temperature, LLM 미사용이면 null
 * @param promptSha256 LLM system prompt SHA-256, LLM 미사용이면 null
 * @param dataCounts 측정 DB의 합성 테이블별 행 수
 */
public record ExperimentMetadata(
        String runId,
        Instant capturedAt,
        String commitSha,
        String os,
        String cpu,
        long ramBytes,
        String javaVersion,
        String postgresqlVersion,
        String ollamaVersion,
        String modelName,
        Double temperature,
        String promptSha256,
        Map<String, Long> dataCounts
) {

    public ExperimentMetadata {
        dataCounts = Map.copyOf(dataCounts);
    }
}
