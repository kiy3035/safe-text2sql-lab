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

public final class OllamaSqlGenerator implements SqlGenerator {

    private final RestClient restClient;
    private final OllamaProperties properties;
    private final SqlPromptTemplate promptTemplate;

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

    private static String stripTrailingSlash(String value) {
        var end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

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

    private record OllamaGenerateRequest(
            String model,
            String system,
            String prompt,
            boolean stream,
            OllamaOptions options) {}

    private record OllamaOptions(double temperature) {}

    private record OllamaGenerateResponse(String model, String response, Boolean done) {}
}
