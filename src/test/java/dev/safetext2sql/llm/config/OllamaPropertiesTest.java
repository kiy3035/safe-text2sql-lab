package dev.safetext2sql.llm.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OllamaPropertiesTest {

    @Test
    void rejectsRemoteCredentialsAndInvalidTemperature() {
        assertThatThrownBy(() -> properties(URI.create("http://username:placeholder@localhost:11434"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("omit user info");
        assertThatThrownBy(() -> properties(URI.create("http://localhost:11434"), Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 2");
        assertThatThrownBy(() -> properties(URI.create("https://example.com:11434"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local loopback host");
    }

    @Test
    void rejectsMissingModelAndNonPositiveTimeout() {
        assertThatThrownBy(() -> new OllamaProperties(
                        URI.create("http://localhost:11434"),
                        " ",
                        0,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model must not be blank");
        assertThatThrownBy(() -> new OllamaProperties(
                        URI.create("http://localhost:11434"),
                        "model:1",
                        0,
                        Duration.ZERO,
                        Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect timeout");
    }

    private static OllamaProperties properties(URI baseUrl, double temperature) {
        return new OllamaProperties(
                baseUrl,
                "model:1",
                temperature,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1));
    }
}
