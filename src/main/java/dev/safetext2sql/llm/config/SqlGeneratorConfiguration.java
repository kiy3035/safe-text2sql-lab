package dev.safetext2sql.llm.config;

import dev.safetext2sql.llm.SqlGenerator;
import dev.safetext2sql.llm.ollama.OllamaSqlGenerator;
import dev.safetext2sql.llm.prompt.SqlPromptTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "text2sql.llm", name = "provider", havingValue = "ollama")
@EnableConfigurationProperties(OllamaProperties.class)
public class SqlGeneratorConfiguration {

    @Bean
    SqlPromptTemplate sqlPromptTemplate() {
        return new SqlPromptTemplate();
    }

    @Bean
    SqlGenerator ollamaSqlGenerator(OllamaProperties properties, SqlPromptTemplate promptTemplate) {
        return new OllamaSqlGenerator(properties, promptTemplate);
    }
}
