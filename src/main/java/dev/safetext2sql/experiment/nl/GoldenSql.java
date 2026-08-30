package dev.safetext2sql.experiment.nl;

/**
 * 고정 seed에서 정답 결과를 만드는 신뢰된 SQL fixture다.
 *
 * <p>실험 runner는 이 SQL도 AST 검증기에 통과시킨 뒤 일반 read-only 실행기로 실행한다.
 * 따라서 Golden 파일이 실수로 정책 밖의 테이블이나 컬럼을 참조하면 측정을 시작하지 않는다.</p>
 *
 * @param id 질문 fixture가 참조하는 식별자
 * @param sql 버전 관리되는 단일 SELECT
 */
public record GoldenSql(String id, String sql) {

    public GoldenSql {
        if (id == null || id.isBlank() || sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("golden SQL id and sql must not be blank");
        }
    }
}
