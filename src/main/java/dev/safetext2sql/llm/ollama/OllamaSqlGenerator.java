package dev.safetext2sql.llm.ollama;

import dev.safetext2sql.llm.GeneratedSql;
import dev.safetext2sql.llm.SqlGenerationRequest;
import dev.safetext2sql.llm.SqlGenerator;
import dev.safetext2sql.llm.config.OllamaProperties;
import dev.safetext2sql.llm.prompt.SqlPromptTemplate;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Ollama 로컬 LLM HTTP 구현체 - {@link SqlGenerator}의 실제 측정용 구현.
 * <p>
 * Spring {@code RestClient}로 {@code POST /api/generate}를 호출하여 SQL을 생성한다.
 * 타임아웃, HTTP 상태 코드, 프로토콜 오류를 각각 {@link OllamaTransportException},
 * {@link OllamaHttpException}, {@link OllamaProtocolException}으로 분류하여
 * 상위 재시도 정책이 5xx/타임아웃만 재시도하도록 돕는다.
 * baseUrl은 생성자에서 루프백 검증을 거친 값만 사용하며, trailing slash를 제거해 정규화한다.
 * </p>
 */
public final class OllamaSqlGenerator implements SqlGenerator {

    /** Ollama HTTP 호출에 사용되는 Spring RestClient. baseUrl 루프백 + 타임아웃이 설정된 상태로 생성된다. */
    private final RestClient restClient;
    /** Ollama 연결 설정 (baseUrl, model, temperature, 타임아웃). 생성자에서 검증된 값을 그대로 사용한다. */
    private final OllamaProperties properties;
    /** 시스템/사용자 프롬프트 생성 템플릿. */
    private final SqlPromptTemplate promptTemplate;

    /**
     * Ollama 생성기를 초기화한다.
     * SimpleClientHttpRequestFactory에 connect/read 타임아웃을 설정하고,
     * baseUrl의 trailing slash를 정규화하여 RestClient를 빌드한다.
     */
    public OllamaSqlGenerator(OllamaProperties properties, SqlPromptTemplate promptTemplate) {
        this.properties = properties;
        this.promptTemplate = promptTemplate;

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.baseUrl().toString()))
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 자연어 요청을 Ollama /api/generate 로 전송하여 SQL을 생성한다.
     * <p>
     * HTTP 4xx/5xx는 {@link OllamaHttpException}으로, 연결·타임아웃은 {@link OllamaTransportException}으로,
     * 그 외 디코딩 실패나 비정상 응답(done != true, null 필드)은 {@link OllamaProtocolException}으로 분류한다.
     * 이 분류는 상위 재시도 로직이 5xx/타임아웃만 재시도하도록 판단하는 근거가 된다.
     * </p>
     */
    @Override
    public GeneratedSql generate(SqlGenerationRequest request) {
        var ollamaRequest = new OllamaGenerateRequest(
                properties.model(),
                promptTemplate.systemPrompt(),
                promptTemplate.userPrompt(request),
                false,
                new OllamaOptions(properties.temperature()));

        OllamaGenerateResponse response;
        try {
            response = restClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ollamaRequest)
                    .retrieve()
                    .body(OllamaGenerateResponse.class);
        } catch (RestClientResponseException exception) {
            throw new OllamaHttpException(exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            throw new OllamaTransportException(exception);
        } catch (RestClientException exception) {
            if (hasCause(exception, SocketTimeoutException.class)
                    || hasCause(exception, HttpTimeoutException.class)) {
                throw new OllamaTransportException(exception);
            }
            throw new OllamaProtocolException("Ollama response could not be decoded");
        }

        if (response == null || response.response() == null || response.model() == null
                || response.model().isBlank() || !Boolean.TRUE.equals(response.done())) {
            throw new OllamaProtocolException("Ollama returned an incomplete response");
        }

        return new GeneratedSql(response.response(), response.model());
    }

    /**
     * URL 끝의 trailing slash를 제거한다. RestClient baseUrl + "/api/generate" 결합 시 이중 슬래시를 방지한다.
     */
    private static String stripTrailingSlash(String value) {
        var end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * 예외 체인에서 특정 타입의 원인 예외가 포함되어 있는지 검사한다.
     * Spring RestClient가 타임아웃을 다양한 래퍼 예외로 감싸기 때문에, cause 체인 전체를 순회해야 정확히 분류할 수 있다.
     */
    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        var current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Ollama /api/generate 요청 바디. stream=false로 단일 응답을 받는다. */
    private record OllamaGenerateRequest(
            String model,
            String system,
            String prompt,
            boolean stream,
            OllamaOptions options) {}

    /** Ollama 옵션 - temperature만 사용하며, 모델별 기본값은 Ollama 서버가 결정한다. */
    private record OllamaOptions(double temperature) {}

    /** Ollama /api/generate 응답 바디. done=true일 때만 완전한 응답으로 간주한다. */
    private record OllamaGenerateResponse(String model, String response, Boolean done) {}
}
