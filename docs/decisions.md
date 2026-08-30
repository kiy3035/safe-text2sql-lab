# 기술 결정 기록

## 1단계

- Java 21, Spring Boot 3.5.16, Gradle Wrapper 8.12.1을 사용한다. 시스템 Gradle 설치 없이 저장소 명령으로 같은 버전을 재현하기 위해서다.
- PostgreSQL 16.9 이미지를 고정한다. 로컬 실행마다 메이저·패치 버전이 암묵적으로 바뀌지 않게 한다.
- 업무 테이블은 `analytics` 스키마에 둔다. Flyway 계정은 스키마 소유와 migration을 담당하고 애플리케이션 계정은 `USAGE`와 허용 컬럼의 `SELECT`만 받는다.
- 기본 Flyway location에는 빠른 smoke seed 100개 주문만 둔다. 50,000개 주문의 선택형 benchmark seed는 `classpath:db/benchmark`를 location에 추가할 때만 적재한다.
- 운영 비밀값에는 기본값을 두지 않는다. `.env.example`은 변수 이름만 제공하고 `.env`는 Git에서 제외한다.

## 2단계

- `SqlGenerator`는 SQL 생성 경계만 표현하고 안전성 판정을 하지 않는다. Ollama와 Fake가 반환한 SQL은 모두 동일하게 신뢰할 수 없는 값으로 취급하며, 허용 여부는 3단계 AST gate가 결정한다.
- Ollama adapter는 Spring `RestClient`를 사용해 `/api/generate`를 `stream=false`로 호출한다. 별도 제품 HTTP client 의존성을 추가하지 않고 connect/read timeout을 명시적으로 적용할 수 있기 때문이다.
- `SQL_GENERATOR_PROVIDER` 기본값은 `disabled`로 둔다. Ollama와 모델이 없는 로컬 환경에서도 애플리케이션과 자동 테스트가 시작되어야 하기 때문이다.
- Ollama model, base URL, temperature, timeout은 환경 설정으로만 주입한다. base URL의 user-info와 loopback 외 host를 거부해 URL에 인증 정보를 넣거나 원격 LLM 서비스로 연결하지 못하게 한다.
- HTTP 상태와 전송/프로토콜 오류는 안정적인 예외로 구분하되 응답 본문이나 malformed JSON 원문을 예외 메시지·cause에 보존하지 않는다.
- 재생성 feedback은 자유 형식 문장이 아니라 대문자 stable code만 허용한다. 질문과 feedback을 프롬프트에서 명확히 분리하지만 이를 보안 경계로 간주하지 않는다.
- `FakeSqlGenerator`와 순차 성공/실패를 표현하는 `ScriptedSqlGenerator`는 `src/testFixtures`에 둔다. 제품 runtime bean으로 등록하지 않으면서 후속 단계 테스트에서 재사용하기 위해서다.
- 실제 Ollama 대신 WireMock 3.13.1로 요청 JSON, 정상 응답, 4xx/5xx, timeout, malformed/incomplete 응답을 검증한다.
