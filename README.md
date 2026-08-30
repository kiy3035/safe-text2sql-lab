# Safe Text2SQL Lab

합성 커머스 데이터를 사용해 `자연어 → SQL 생성 → 검증 → 읽기 전용 실행`을 단계별로 재현하는 개인 실험 프로젝트다. 현재는 4단계까지 구현되어 Spring Boot/PostgreSQL 기반, 로컬 Ollama용 SQL 생성 adapter, JSqlParser 기반 SQL 검증 gate, 제한된 Native Query 실행과 재시도 API를 제공한다.

## 요구 사항

- Java 21
- Docker와 Docker Compose

Gradle은 별도 설치하지 않고 저장소의 Wrapper를 사용한다. 기본 LLM provider는 `disabled`이므로 Ollama와 로컬 모델 없이 애플리케이션 및 자동 테스트를 실행할 수 있다.

## 환경 변수

PowerShell 예시(값은 로컬에서 직접 정한다):

```powershell
$env:DB_NAME = 'text2sql'
$env:DB_PORT = '5432'
$env:DB_MIGRATION_PASSWORD = '<local-migration-password>'
$env:APP_DB_PASSWORD = '<local-readonly-password>'
```

계정명은 `text2sql_migration`과 `text2sql_ro`로 고정되어 있다. 실제 비밀번호를 `.env.example`, 소스, 로그에 기록하지 않는다. `.env`를 사용할 경우 Git에서 제외되어 있다.

## 로컬 실행

```powershell
docker compose up -d --wait postgres
.\gradlew.bat bootRun
```

다른 터미널에서 health endpoint를 확인한다.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

자연어 질의 API는 다음 경로를 사용한다. 기본 provider는 `disabled`이므로 Ollama를 연결하지 않은
상태에서는 내부 정보 없이 `LLM_UNAVAILABLE`을 반환한다.

```powershell
$body = @{ question = '첫 주문을 알려줘' } | ConvertTo-Json
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/v1/text2sql/query `
  -ContentType 'application/json' `
  -Body $body
```

종료:

```powershell
docker compose down
```

데이터 볼륨까지 제거하면 seed가 다음 실행에서 다시 만들어진다. 이 명령은 로컬 DB 데이터를 삭제하므로 의도한 경우에만 실행한다.

```powershell
docker compose down --volumes
```

## 테스트

통합 테스트는 Testcontainers로 별도 PostgreSQL 컨테이너를 만든다.

```powershell
.\gradlew.bat test
```

이 작업 환경처럼 Windows에서 Testcontainers가 Docker named pipe를 자동 감지하지 못하면 테스트 전에 다음을 설정한다.

```powershell
$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'
```

Ollama와 Docker DB 없이 SQL 검증·재시도·API 단위 테스트만 실행할 수도 있다.

```powershell
.\gradlew.bat test `
  --tests 'dev.safetext2sql.sql.validation.*' `
  --tests 'dev.safetext2sql.workflow.*' `
  --tests 'dev.safetext2sql.api.*'
```

## SQL AST 검증 gate

LLM이 생성한 문자열은 JSqlParser 5.3으로 파싱한 뒤 명시적인 허용 정책과 대조한다.

- 정확히 한 개의 SELECT만 허용하며 DDL/DML/DCL, 다중 문장, CTE, 집합 연산, 잠금, SELECT INTO를 거부한다.
- `analytics`의 공개 합성 테이블 6개와 허용 컬럼·함수만 사용할 수 있다.
- SELECT, JOIN, WHERE, GROUP BY, HAVING, ORDER BY와 모든 서브쿼리의 참조를 재귀적으로 검사한다.
- 별칭과 파생 테이블을 SELECT scope별로 해석하며 모호하거나 해석할 수 없는 컬럼은 거부한다.
- `SELECT *`, `table.*`, alias column list와 검증 정책에서 지원하지 않는 확장 구문은 fail-closed로 처리한다.
- 통과한 SQL만 외부에서 임의 생성할 수 없는 `ValidatedSelect`가 된다.

악의적 SQL fixture는 [malicious-sql.json](src/test/resources/security/malicious-sql.json)에 정확히 20건으로 고정되어 있다.

## 선택형 benchmark seed

기본 실행은 주문 100개의 smoke seed를 사용한다. 향후 인덱스 실험용 50,000개 주문을 추가하려면 새 로컬 DB에서 다음 값을 함께 설정한다.

```powershell
$env:DB_FLYWAY_LOCATIONS = 'classpath:db/migration,classpath:db/benchmark'
```

benchmark DDL과 측정은 5단계 범위이며 아직 실행하지 않는다.

## 로컬 Ollama 연동 설정

2단계에서는 Ollama의 `/api/generate` HTTP adapter만 구현했다. Ollama 프로그램이나 모델을 자동으로 설치·실행하지 않는다. 이미 로컬에서 준비된 Ollama를 나중에 연결할 경우 다음 환경 변수를 지정한다.

```powershell
$env:SQL_GENERATOR_PROVIDER = 'ollama'
$env:OLLAMA_BASE_URL = 'http://localhost:11434'
$env:OLLAMA_MODEL = '<local-model-name-and-version>'
$env:LLM_TEMPERATURE = '0'
$env:OLLAMA_CONNECT_TIMEOUT = '2s'
$env:OLLAMA_READ_TIMEOUT = '30s'
```

- 요청은 `stream=false`로 보내며 SQL 문자열만 응답하도록 system prompt를 제공한다.
- model, temperature, connect/read timeout은 설정으로 주입한다.
- base URL은 `localhost`, `127.0.0.1`, IPv6 loopback만 허용해 원격 LLM 서비스 연결을 거부한다.
- HTTP 응답 본문과 생성 SQL은 adapter에서 로그로 남기지 않는다.
- 생성된 SQL은 신뢰할 수 없는 문자열이며, 3단계 AST 검증을 통과해 `ValidatedSelect`가 되기 전에는 실행할 수 없다.
- `400`과 `500`, 전송 오류, 응답 형식 오류를 구분하지만 재시도는 4단계에서 단일 attempt budget으로 구현한다.

WireMock 기반 adapter 테스트와 Scripted/Fake 테스트 대역도 전체 테스트에 포함된다.

## 제한된 SQL 실행과 재시도 API

- Native Query 실행기는 문자열이 아니라 `ValidatedSelect`만 입력으로 받으며
  `EntityManager.createNativeQuery(...)`에 검증된 SQL을 그대로 전달한다.
- datasource의 `text2sql_ro` 최소 권한에 더해 `@Transactional(readOnly = true)`를 적용한다.
- 기본 반환 상한은 200행이다. 실행기는 201행까지만 가져와 상한 초과 여부를 확인하고 응답의
  `truncated`에 기록한다.
- PostgreSQL `statement_timeout`은 기본 2초이며 현재 트랜잭션에만 적용한다.
- LLM 호출은 최초 호출을 포함해 최대 3회다. 두 번째·세 번째 호출 직전에 기본 200ms·400ms
  지수 백오프를 적용한다.
- Ollama 5xx, SQL 검증 실패, 회복 가능한 DB 식별자 오류만 재생성한다. Ollama 4xx, DB 권한 오류,
  timeout, 취소, 그 밖의 DB 오류는 재시도하지 않는다.
- 시도 로그에는 무작위 correlation ID, attempt, 안정적인 결과 코드, backoff, elapsed time만
  기록하며 질문·생성 SQL·DB 오류 원문은 기록하지 않는다.
- 성공 응답은 `columns`, `rows`, `truncated`, `attemptCount`, `elapsedMs`만 반환한다. 실패 응답도
  안정적인 `code`와 `attemptCount`만 반환한다.

설정 기본값은 다음 환경 변수로 바꿀 수 있지만, 호출 3회·1,000행·30초의 절대 상한은 넘을 수 없다.

```powershell
$env:QUERY_MAX_ROWS = '200'
$env:QUERY_STATEMENT_TIMEOUT = '2s'
$env:LLM_MAX_ATTEMPTS = '3'
$env:LLM_INITIAL_BACKOFF = '200ms'
$env:QUERY_MAX_QUESTION_LENGTH = '1000'
```
