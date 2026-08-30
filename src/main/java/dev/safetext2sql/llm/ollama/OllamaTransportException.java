package dev.safetext2sql.llm.ollama;

/**
 * 로컬 Ollama 프로세스와 네트워크 통신 자체가 실패했을 때 발생하는 예외.
 * <p>
 * 연결 타임아웃, 읽기 타임아웃, 소켓 예외 등이 해당한다.
 * 외부 유료 API와 달리 로컬 루프백 통신이므로, 이 예외는 Ollama 미기동이나
 * 모델 미설치 같은 환경 문제로 발생하는 경우가 많다.
 * </p>
 */
public final class OllamaTransportException extends RuntimeException {

    public OllamaTransportException(Throwable cause) {
        super("Could not communicate with local Ollama", cause);
    }
}
