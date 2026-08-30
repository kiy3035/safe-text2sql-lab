package dev.safetext2sql.llm.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.safetext2sql.llm.SqlGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SqlGeneratorConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(SqlGeneratorConfiguration.class);

    @Test
    void doesNotCreateGeneratorWhenProviderIsDisabled() {
        contextRunner
                .withPropertyValues("text2sql.llm.provider=disabled")
                .run(context -> assertThat(context).doesNotHaveBean(SqlGenerator.class));
    }

    @Test
    void createsOllamaGeneratorOnlyWithInjectedSettings() {
        contextRunner
                .withPropertyValues(
                        "text2sql.llm.provider=ollama",
                        "text2sql.llm.ollama.base-url=http://localhost:11434",
                        "text2sql.llm.ollama.model=local-test-model:1",
                        "text2sql.llm.ollama.temperature=0",
                        "text2sql.llm.ollama.connect-timeout=1s",
                        "text2sql.llm.ollama.read-timeout=5s")
                .run(context -> {
                    assertThat(context).hasSingleBean(SqlGenerator.class);
                    assertThat(context).hasSingleBean(OllamaProperties.class);
                });
    }
}
