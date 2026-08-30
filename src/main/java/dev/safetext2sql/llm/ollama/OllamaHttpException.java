package dev.safetext2sql.llm.ollama;

/**
 * Ollama HTTP API가 4xx/5xx 상태 코드로 응답했을 때 발생하는 예외.
 * <p>
 * 재시도 정책에서 구분을 위해 상태 코드를 보존한다:
 * 5xx는 일시적 서버 오류로 재시도 대상, 4xx는 클라이언트 오류로 즉시 중단한다.
 * </p>
 */
public final class OllamaHttpException extends RuntimeException {

    /** Ollama가 반환한 HTTP 상태 코드. */
    private final int statusCode;

    public OllamaHttpException(int statusCode) {
        super("Ollama returned HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    /** HTTP 상태 코드를 반환한다. 재시도 정책에서 5xx/4xx 분기에 사용된다. */
    public int statusCode() {
        return statusCode;
    }

    /** 4xx 클라이언트 오류 여부 - 재시도하지 않고 즉시 실패해야 하는 경우. */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    /** 5xx 서버 오류 여부 - 일시적 오류로 재시도(최대 2회) 대상이 되는 경우. */
    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }
}
