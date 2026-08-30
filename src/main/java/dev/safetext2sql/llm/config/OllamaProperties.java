package dev.safetext2sql.llm.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("text2sql.llm.ollama")
public record OllamaProperties(
        URI baseUrl,
        String model,
        double temperature,
        Duration connectTimeout,
        Duration readTimeout) {

    public OllamaProperties {
        if (baseUrl == null || baseUrl.getScheme() == null || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null
                || !(baseUrl.getScheme().equalsIgnoreCase("http")
                        || baseUrl.getScheme().equalsIgnoreCase("https"))
                || !isLoopbackHost(baseUrl.getHost())) {
            throw new IllegalArgumentException(
                    "Ollama base URL must use http or https, target a local loopback host, and omit user info");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Ollama model must not be blank when provider is enabled");
        }
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("Ollama temperature must be between 0 and 2");
        }
        requirePositive(connectTimeout, "Ollama connect timeout");
        requirePositive(readTimeout, "Ollama read timeout");
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static boolean isLoopbackHost(String host) {
        return host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1");
    }
}
