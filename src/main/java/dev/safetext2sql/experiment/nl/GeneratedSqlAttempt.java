package dev.safetext2sql.experiment.nl;

/** 실험 결과에 보존하는 LLM 호출 번호와 생성 SQL이다. 결과 디렉터리는 기본적으로 Git에서 제외된다. */
public record GeneratedSqlAttempt(int attempt, String model, String sql) {
}
