# Safe Text2SQL Lab

합성 커머스 데이터를 사용해 `자연어 → SQL 생성 → 검증 → 읽기 전용 실행`을 단계별로 재현하는 개인 실험 프로젝트다. Spring Boot/PostgreSQL 기반, 로컬 Ollama용 SQL 생성 adapter, JSqlParser 기반 SQL 검증 gate, 제한된 Native Query 실행과 재시도 API, 자연어·인덱스·재시도 측정 runner와 실제 결과 기반 문서를 제공한다.

상세 문서:

- [아키텍처와 방어 계층](docs/architecture.md)
- [위협 모델과 남아 있는 한계](docs/threat-model.md)
- [실험 결과 보고서](docs/experiment-report.md)
- [한국어 블로그 초안](docs/blog-draft.md)
- [주요 기술 결정](docs/decisions.md)

## 요구 사항

- Java 21
- Docker와 Docker Compose
- Windows 예시 명령을 실행할 PowerShell

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

기본 실행은 주문 100개의 smoke seed를 사용한다. 인덱스 실험용 50,000개 주문을 추가하려면 새 로컬 DB에서 다음 값을 함께 설정한다. smoke seed를 포함해 `orders`, `order_items`, `payments`가 각각 50,100행이 된다.

```powershell
$env:DB_FLYWAY_LOCATIONS = 'classpath:db/migration,classpath:db/benchmark'
```

benchmark seed는 반복 가능한 Flyway migration이며, 일반 애플리케이션 DB와 분리한 일회성 로컬 실험 DB에서만 사용한다.

## 5단계 측정 실험

질문과 Golden SQL은 각각 [nl-questions.jsonl](experiments/nl-questions.jsonl),
[golden-sql.jsonl](experiments/golden-sql.jsonl)에 정확히 50건으로 고정되어 있다. 생성 SQL 문자열을
Golden 문자열과 비교하지 않고 같은 seed DB의 결과 집합을 `SCALAR`, `ORDERED`, `UNORDERED`
정책에 따라 비교한다.

Ollama 없이 Golden 50건의 검증·실행과 PENDING 결과만 생성하려면 DB 환경 변수를 설정하고 다음을 실행한다.

```powershell
$env:SQL_GENERATOR_PROVIDER = 'disabled'
$env:OLLAMA_VERSION = 'NOT_INSTALLED'
$env:EXPERIMENT_RUN_ID = 'my-nl-pending-run'
docker compose up -d --wait postgres
.\gradlew.bat nlExperiment
docker compose down
```

인덱스 전후 실험은 50,100행 benchmark seed를 사용하는 별도 DB에서 실행한다. 고정 조회 3개를
전후 각 1회 예열·10회 측정하고 원본 실행계획 60개와 요약 CSV를 만든다.

```powershell
$env:DB_FLYWAY_LOCATIONS = 'classpath:db/migration,classpath:db/benchmark'
$env:EXPERIMENT_RUN_ID = 'my-index-run'
docker compose up -d --wait postgres
.\gradlew.bat indexExperiment
docker compose down
```

WireMock 재시도 측정은 Docker와 Ollama 없이 실행할 수 있다.

```powershell
$env:EXPERIMENT_RUN_ID = 'my-retry-run'
.\gradlew.bat retryExperiment
```

검증된 5단계 결과는 다음 디렉터리에 보존했다.

- `results/stage5-nl-pending-20260830`: Golden 50건 실행 성공, 실제 LLM 정확도는 `PENDING`
- `results/stage5-index-20260830`: 10회 반복 요약과 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` 원본 60개
- `results/stage5-retry-20260830`: 세 HTTP 시나리오 각 10회, 총 30회 원본과 요약

세 결과의 metadata는 측정 코드 SHA `5c94665238b49b41a214ac8247c0aa59929f74b6`와
OS·CPU·RAM·Java·PostgreSQL·Ollama/model 정보를 기록한다. 비밀번호와 JDBC/Ollama URL은 기록하지 않는다.

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

### 실제 로컬 모델 측정 준비

이 저장소 작업에서는 Ollama 프로그램과 모델을 설치하지 않았다. 사용자가 Ollama를 별도로 준비한
뒤 모델을 설치하고 실행할 때만 다음 명령을 사용한다. `<local-model-name-and-version>`에는 재현
가능한 정확한 model tag를 넣고 결과 metadata에도 같은 값을 남긴다.

첫 번째 터미널:

```powershell
ollama serve
```

다른 터미널:

```powershell
ollama pull <local-model-name-and-version>
ollama list

$env:SQL_GENERATOR_PROVIDER = 'ollama'
$env:OLLAMA_BASE_URL = 'http://localhost:11434'
$env:OLLAMA_MODEL = '<local-model-name-and-version>'
$env:OLLAMA_VERSION = '<installed-ollama-version>'
$env:LLM_TEMPERATURE = '0'
$env:EXPERIMENT_RUN_ID = 'my-nl-ollama-run'
.\gradlew.bat nlExperiment
```

현재 저장된 자연어 결과는 Ollama 미설치 상태의 `PENDING`이며, 위 명령을 이 프로젝트 작업에서
실행한 것처럼 해석하면 안 된다.

## 제한된 SQL 실행과 재시도 API

- Native Query 실행기는 문자열이 아니라 `ValidatedSelect`만 입력으로 받으며
  `EntityManager.createNativeQuery(...)`에 검증된 SQL을 그대로 전달한다.
- datasource의 `text2sql_ro` 최소 권한에 더해 `@Transactional(readOnly = true)`를 적용한다.
- 기본 반환 상한은 200행이다. 명시적 LIMIT/FETCH가 없는 조회는 201행까지만 가져와 상한 초과를
  확인한다. 최상위 LIMIT/FETCH는 AST가 최대 200행으로 먼저 제한하며, 실행 설정이 더 작으면
  API 응답을 다시 해당 상한으로 자르고 `truncated`에 기록한다.
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

## 빈 환경 재현 점검

다음 순서는 기존 DB volume에 의존하지 않도록 별도 Compose project를 사용한다. 비밀번호는 예시
문자열을 복사하지 말고 로컬에서 새 값으로 정한다.

```powershell
git clone https://github.com/kiy3035/safe-text2sql-lab.git
Set-Location safe-text2sql-lab

$env:DB_NAME = 'text2sql'
$env:DB_PORT = '5432'
$env:DB_MIGRATION_PASSWORD = '<new-local-migration-password>'
$env:APP_DB_PASSWORD = '<new-local-readonly-password>'
$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'

docker compose -p safe-text2sql-repro up -d --wait postgres
.\gradlew.bat bootRun
```

두 번째 PowerShell에서 저장소로 이동해 health를 확인한다.

```powershell
Set-Location <clone-directory>\safe-text2sql-lab
Invoke-RestMethod http://localhost:8080/actuator/health
```

첫 번째 PowerShell로 돌아가 `Ctrl+C`로 `bootRun`을 종료한다. 같은 첫 번째 PowerShell에서 전체
테스트와 문서 수치 대조를 실행하면 앞서 설정한 환경 변수도 유지된다.

```powershell
.\gradlew.bat test --rerun-tasks
.\scripts\verify_documented_results.ps1
```

재현용 DB를 모두 확인한 뒤에만 해당 project의 volume을 제거한다. 이 명령은
`safe-text2sql-repro`에 속한 로컬 DB 데이터만 삭제한다.

```powershell
docker compose -p safe-text2sql-repro down --volumes
```
