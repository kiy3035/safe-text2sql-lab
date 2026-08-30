package dev.safetext2sql.llm.ollama;

public final class OllamaTransportException extends RuntimeException {

    public OllamaTransportException(Throwable cause) {
        super("Could not communicate with local Ollama", cause);
    }
}
