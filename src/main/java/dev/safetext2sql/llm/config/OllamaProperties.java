package dev.safetext2sql.llm.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ollama 로컬 LLM 연동 설정값을 담는 타입 안전 설정 레코드.
 * <p>
 * {@code application.yml}의 {@code text2sql.llm.ollama} 접두사 하위 값을 바인딩한다.
 * 모든 필드는 생성자에서 엄격히 검증되며, 실패 시 애플리케이션 기동 자체가 중단되어
 * 잘못된 설정으로 인한 비정상 동작을 사전에 차단한다(fail-fast).
 * </p>
 * <ul>
 *   <li>{@code baseUrl} - 반드시 http/https + 루프백 호스트(localhost/127.0.0.1/::1)만 허용, userInfo 금지 (SSRF 방지)</li>
 *   <li>{@code model} - Ollama 모델명 (예: llama3.1:8b), blank 불가</li>
 *   <li>{@code temperature} - 0~2 범위, 유한한 double만 허용</li>
 *   <li>{@code connectTimeout}/{@code readTimeout} - 양수 Duration만 허용</li>
 * </ul>
 */
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

    /**
     * Duration이 양수인지 검증한다. null, 0, 음수 모두 거부하여 타임아웃 미설정으로 인한 무한 대기를 방지한다.
     */
    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /**
     * 호스트가 루프백 주소인지 확인한다.
     * localhost, 127.0.0.1, ::1, 0:0:0:0:0:0:0:1 만 허용하여 외부 네트워크로의 SSRF를 원천 차단한다.
     */
    private static boolean isLoopbackHost(String host) {
        return host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1");
    }
}
