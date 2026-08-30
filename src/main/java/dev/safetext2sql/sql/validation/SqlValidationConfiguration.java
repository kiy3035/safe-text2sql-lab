package dev.safetext2sql.sql.validation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SQL 검증기 빈을 등록하는 Spring 설정 클래스.
 * <p>
 * {@link AnalyticsSqlPolicy#defaultPolicy()}를 기반으로 한 단일 {@link AstSqlValidator} 인스턴스를
 * 애플리케이션 전역에서 재사용한다. 검증기는 상태를 갖지 않으므로 싱글톤으로 안전하다.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
class SqlValidationConfiguration {

    @Bean
    SqlValidator sqlValidator() {
        return new AstSqlValidator(AnalyticsSqlPolicy.defaultPolicy());
    }
}
