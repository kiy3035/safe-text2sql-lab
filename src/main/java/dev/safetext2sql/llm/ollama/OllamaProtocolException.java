package dev.safetext2sql.llm.ollama;

/**
 * Ollama 응답을 프로토콜 수준에서 해석할 수 없을 때 발생하는 예외.
 * <p>
 * 예: HTTP 200이지만 body가 null이거나, {@code done} 플래그가 true가 아니거나,
 * JSON 디코딩에 실패한 경우. 이는 서버 오류가 아닌 프로토콜 위반으로 취급되어
 * 재시도 정책에 따라 다르게 처리될 수 있다.
 * </p>
 */
public final class OllamaProtocolException extends RuntimeException {

    public OllamaProtocolException(String message) {
        super(message);
    }

}
