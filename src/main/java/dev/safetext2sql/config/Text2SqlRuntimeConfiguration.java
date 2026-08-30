package dev.safetext2sql.config;

import dev.safetext2sql.execution.NativeQueryExecutor;
import dev.safetext2sql.execution.SqlExecutionProperties;
import dev.safetext2sql.execution.SqlQueryExecutor;
import dev.safetext2sql.llm.SqlGenerator;
import dev.safetext2sql.sql.validation.SqlValidator;
import dev.safetext2sql.workflow.BackoffPolicy;
import dev.safetext2sql.workflow.ExponentialBackoffPolicy;
import dev.safetext2sql.workflow.NanoClock;
import dev.safetext2sql.workflow.QueryWorkflowProperties;
import dev.safetext2sql.workflow.Sleeper;
import dev.safetext2sql.workflow.Text2SqlQueryService;
import dev.safetext2sql.workflow.ThreadSleeper;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 4단계 실행·재시도 경계를 Spring 빈으로 조립한다.
 *
 * <p>기본 provider가 disabled이면 {@link SqlGenerator} 빈이 없지만 애플리케이션은 정상 기동한다.
 * 이 경우 서비스는 실제 요청에만 {@code LLM_UNAVAILABLE}을 반환한다. 테스트나 Ollama 설정에서
 * 생성기가 제공되면 같은 서비스가 그대로 전체 파이프라인을 실행한다.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SqlExecutionProperties.class, QueryWorkflowProperties.class})
public class Text2SqlRuntimeConfiguration {

    @Bean
    SqlQueryExecutor sqlQueryExecutor(EntityManager entityManager, SqlExecutionProperties properties) {
        return new NativeQueryExecutor(entityManager, properties);
    }

    @Bean
    BackoffPolicy backoffPolicy(QueryWorkflowProperties properties) {
        return new ExponentialBackoffPolicy(properties.initialBackoff());
    }

    @Bean
    Sleeper sleeper() {
        return new ThreadSleeper();
    }

    @Bean
    NanoClock nanoClock() {
        return System::nanoTime;
    }

    @Bean
    Text2SqlQueryService text2SqlQueryService(
            ObjectProvider<SqlGenerator> sqlGeneratorProvider,
            SqlValidator sqlValidator,
            SqlQueryExecutor queryExecutor,
            QueryWorkflowProperties properties,
            BackoffPolicy backoffPolicy,
            Sleeper sleeper,
            NanoClock nanoClock
    ) {
        return new Text2SqlQueryService(
                sqlGeneratorProvider.getIfAvailable(),
                sqlValidator,
                queryExecutor,
                properties,
                backoffPolicy,
                sleeper,
                nanoClock
        );
    }
}
