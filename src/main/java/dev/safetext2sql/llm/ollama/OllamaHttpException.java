package dev.safetext2sql.llm.ollama;

public final class OllamaHttpException extends RuntimeException {

    private final int statusCode;

    public OllamaHttpException(int statusCode) {
        super("Ollama returned HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }
}
