package dev.safetext2sql.llm;

import java.util.Objects;

/**
 * LLM이 생성한 SQL과 생성에 사용된 모델 정보를 담는 불변 값 객체.
 * <p>
 * {@code sql}은 아직 검증되지 않은 신뢰할 수 없는(untrusted) 문자열이다.
 * 반드시 {@link dev.safetext2sql.sql.validation.SqlValidator}를 통과한 뒤에만
 * {@link dev.safetext2sql.sql.validation.ValidatedSelect}로 변환되어 실행 경계에 도달할 수 있다.
 * {@code model}은 재현성을 위해 실험 결과(CSV/JSON)에 함께 기록되는 모델 식별자이다.
 * </p>
 *
 * @param sql   LLM이 반환한 원문 SQL (검증 전)
 * @param model 생성에 사용된 Ollama 모델명 (예: llama3.1:8b)
 */
public record GeneratedSql(String sql, String model) {

    public GeneratedSql {
        Objects.requireNonNull(sql, "sql must not be null");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
    }
}
