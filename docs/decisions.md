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

## 3단계

- SQL 파서는 JSqlParser 5.3으로 고정한다. SQL 문자열이나 정규식으로 최종 허용 여부를 판단하지 않고 SELECT AST 전체를 재귀 순회하기 위해서다.
- 실행 계층은 원시 SQL이 아니라 package-private 생성자를 가진 `ValidatedSelect`만 받도록 경계를 만든다. 검증기 패키지 밖에서 검증 완료 값을 임의로 만들 수 없게 해 검증 우회 경로를 줄인다.
- 허용 정책은 `analytics` 스키마의 합성 테이블 6개, 공개 컬럼, 집계·문자열 함수 11개만 명시한다. 목록에 없거나 해석할 수 없는 식별자는 DB에 질의해 추측하지 않고 거절한다.
- 각 SELECT마다 별도 scope를 만들고 FROM 관계와 별칭을 등록한다. 한정 컬럼은 현재 scope에서 먼저 찾고, 상관 서브쿼리만 부모 scope를 탐색하며, 한정하지 않은 컬럼이 둘 이상의 관계에 존재하면 모호한 참조로 거절한다.
- `SELECT *`, CTE, 집합 연산, 잠금, SELECT INTO, 별칭 컬럼 목록, DISTINCT ON과 파서가 지원하더라도 정책에서 검증하지 않는 확장 구문은 fail-closed로 거절한다.
- 명시적인 LIMIT/FETCH는 200행, JOIN은 4개, 서브쿼리는 3단계, 입력 SQL은 10,000자로 제한한다. 실제 결과 행 제한과 statement timeout은 4단계 실행기에서도 별도로 강제한다.
- 악의적 SQL은 합성 이름만 사용한 JSON fixture 정확히 20건으로 고정한다. 각 fixture가 안정적인 거절 코드로 실패하고 테스트용 Native Query 실행 경계 호출 횟수가 0인지 함께 검증한다.
- 새 코드의 신뢰 경계와 보안 판단은 사용자가 소스를 따라갈 수 있도록 상세한 한글 Javadoc과 의도 중심 주석으로 기록한다.

## 4단계

- 실행 경계는 `SqlQueryExecutor.execute(ValidatedSelect)`로 고정한다. 검증기 패키지 밖에서는
  `ValidatedSelect`를 만들 수 없으므로 LLM 원문 문자열을 Native Query로 직접 보내는 공개 경로가 없다.
- 행 제한을 위해 SQL 뒤에 `LIMIT` 문자열을 붙이지 않는다. JPA `setMaxResults(maxRows + 1)`로
  최대 한 행만 더 가져와 `truncated`를 판별하고, API에는 상한 이내 행만 반환한다.
- PostgreSQL `statement_timeout`은 바인드 값과 `set_config(..., true)`를 사용해 현재 트랜잭션에만
  적용한다. JPA timeout hint도 함께 사용하되 보안·자원 경계의 기준은 DB timeout이다.
- `@Transactional(readOnly = true)`는 보조 힌트로만 사용한다. 실제 권한 경계는 1단계에서 만든
  `text2sql_ro` 계정의 테이블·컬럼 권한이며, 통합 테스트에서 계속 독립적으로 검증한다.
- 전체 LLM 호출 예산은 최대 3회이고, attempt 2와 3 직전에 주입 가능한 `Sleeper`와
  `BackoffPolicy`로 기본 200ms·400ms를 적용한다. 테스트에서는 실제로 기다리지 않고 기록한다.
- Ollama 5xx, AST 정책 실패, 회복 가능한 DB 문법·식별자 오류만 재생성한다. 4xx, 권한 오류,
  statement timeout·취소, 기타 DB 오류는 같은 요청을 반복하지 않고 즉시 안정적인 오류로 끝낸다.
- HTTP 응답과 예외에는 질문, SQL, Ollama 본문, PostgreSQL 메시지를 넣지 않는다. 구조화 로그도
  correlation ID, attempt, 결과 코드, backoff, elapsed time만 기록한다.
