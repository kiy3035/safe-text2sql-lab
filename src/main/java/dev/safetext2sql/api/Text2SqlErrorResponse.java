package dev.safetext2sql.api;

/**
 * 내부 예외 메시지 대신 안정적인 코드와 완료된 LLM 호출 횟수만 반환하는 실패 응답이다.
 *
 * @param code 외부에 공개 가능한 고정 오류 코드
 * @param attemptCount 실패 전 완료된 LLM 호출 횟수
 */
public record Text2SqlErrorResponse(String code, int attemptCount) {
}
