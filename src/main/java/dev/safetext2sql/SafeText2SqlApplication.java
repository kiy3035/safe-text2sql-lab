package dev.safetext2sql;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Safe Text2SQL Lab 애플리케이션 진입점.
 * <p>
 * Spring Boot 3.x 기반으로 구동되며, 자연어 → LLM SQL 생성 → SQL 검증 → 읽기 전용 실행
 * 전체 파이프라인을 로컬 환경(Docker PostgreSQL + Ollama)에서 재현 가능하게 구성한다.
 * Flyway 마이그레이션, 검증기(Validator), Ollama 연동 등 모든 빈은 자동 설정으로 로드된다.
 * </p>
 */
@SpringBootApplication
public class SafeText2SqlApplication {

    public static void main(String[] args) {
        SpringApplication.run(SafeText2SqlApplication.class, args);
    }
}
