package dev.safetext2sql.experiment.index;

/**
 * 인덱스 효과를 비교할 신뢰된 고정 SELECT 한 건이다.
 *
 * @param id 결과 파일 식별자
 * @param description 어떤 접근 패턴을 측정하는지 설명
 * @param sql 외부 입력이 아닌 저장소 고정 SELECT
 */
public record IndexBenchmarkCase(String id, String description, String sql) {
}
