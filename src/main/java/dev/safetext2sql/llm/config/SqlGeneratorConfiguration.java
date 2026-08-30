package dev.safetext2sql.llm.config;

import dev.safetext2sql.llm.SqlGenerator;
import dev.safetext2sql.llm.ollama.OllamaSqlGenerator;
import dev.safetext2sql.llm.prompt.SqlPromptTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 연동 빈을 조건부로 등록하는 Spring 설정 클래스.
 * <p>
 * {@code text2sql.llm.provider=ollama} 일 때만 활성화되며, 그 외에는
 * 테스트용 Scripted/Fake 구현체가 별도로 주입된다.
 * {@code proxyBeanMethods=false}로 경량화하여 불필요한 CGLIB 프록시 생성을 방지한다.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "text2sql.llm", name = "provider", havingValue = "ollama")
@EnableConfigurationProperties(OllamaProperties.class)
public class SqlGeneratorConfiguration {

    /** 프롬프트 템플릿 빈 - 상태가 없으므로 싱글톤으로 공유해도 안전하다. */
    @Bean
    SqlPromptTemplate sqlPromptTemplate() {
        return new SqlPromptTemplate();
    }

    /** Ollama 기반 SqlGenerator 빈 - provider=ollama 일 때만 생성된다. */
    @Bean
    SqlGenerator ollamaSqlGenerator(OllamaProperties properties, SqlPromptTemplate promptTemplate) {
        return new OllamaSqlGenerator(properties, promptTemplate);
    }
}
