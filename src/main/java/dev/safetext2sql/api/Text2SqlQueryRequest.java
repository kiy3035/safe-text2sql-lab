package dev.safetext2sql.api;

/**
 * 자연어 질의 API 요청이다. null/blank/길이 검증은 전체 정책을 아는 서비스에서 수행한다.
 *
 * @param question 합성 분석 스키마에 질의할 자연어 질문
 */
public record Text2SqlQueryRequest(String question) {
}
