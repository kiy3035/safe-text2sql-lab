# 작업 진행 상황

마지막 갱신: 2026-08-30 (Asia/Seoul)

## 1. 완료한 작업

### 1단계: 프로젝트와 로컬 DB 환경

- Java 21, Spring Boot 3.5.16, Gradle Wrapper 8.12.1 프로젝트를 구성했다.
- PostgreSQL 16.9 Docker Compose, Flyway migration 계정 `text2sql_migration`, 애플리케이션
  read-only 계정 `text2sql_ro`를 분리했다.
- `analytics` 합성 커머스 테이블 8개와 deterministic smoke seed를 만들었다.
  - 조회 허용 6개: `categories`, `products`, `customers`, `orders`, `order_items`, `payments`
  - 전체 금지 2개: `admin_users`, `audit_logs`
  - seed: categories 5, products 20, customers 50, orders/order_items/payments 각 100행
- read-only 계정에는 공개 컬럼의 `SELECT`만 부여하고 쓰기, 민감 컬럼, 금지 테이블 접근을 차단했다.
- 5단계용 선택형 50,000 주문 benchmark seed를 기본 migration과 분리했다. 아직 적재·측정하지 않았다.

### 2단계: LLM 연동

- `SqlGenerator`, Ollama HTTP 구현체, Fake/Scripted 구현체, prompt와 설정을 구현했다.
- Ollama endpoint를 loopback host로 제한하고 model·temperature·timeout을 설정으로 주입했다.
- 응답 본문과 malformed JSON 원문을 예외 메시지·cause에 남기지 않도록 했다.
- WireMock으로 정상 응답, 400/500, timeout, malformed/incomplete JSON을 검증했다.
- 기본 provider는 `disabled`이며 Ollama 프로그램과 모델은 설치·실행하지 않았다.

### 3단계: SQL 검증 gate

- JSqlParser 5.3 기반 `AstSqlValidator`와 위조가 제한된 `ValidatedSelect`를 구현했다.
- 단일 SELECT, schema/table/column/function Allowlist, alias·파생/상관 서브쿼리를 AST 전체에서 검사한다.
- DDL/DML/DCL, 다중 문장, CTE, 집합 연산, 잠금, SELECT INTO, wildcard, 시스템 catalog와
  해석 불가능한 구문을 fail-closed로 거절한다.
- 악의적 SQL fixture 정확히 20건을 고정하고 모두 실행기 호출 0회로 차단했다.
- 3단계 PR #1은 사용자가 확인해 `master`에 병합했다.

### 4단계: SQL 실행과 재시도

- `SqlQueryExecutor`와 `NativeQueryExecutor`를 구현했다.
  - 입력은 `ValidatedSelect`만 허용
  - `EntityManager.createNativeQuery(...)`와 `@Transactional(readOnly = true)` 사용
  - 기본 200행, 최대 1,000행의 결과 상한 및 한 행 추가 조회를 통한 `truncated` 판별
  - 트랜잭션 로컬 PostgreSQL `statement_timeout` 기본 2초, 최대 30초
  - SQLSTATE를 회복 가능한 식별자 오류, timeout/취소, 권한 오류, 기타 DB 오류로 축약
- `Text2SqlQueryService`에 단일 attempt budget을 구현했다.
  - 최초 호출 포함 최대 3회
  - attempt 2/3 직전 기본 200ms/400ms 지수 백오프
  - `Sleeper`, `BackoffPolicy`, `NanoClock` 주입으로 시간 없는 결정적 테스트 가능
  - Ollama 5xx, 검증 실패, 회복 가능한 DB 식별자 오류만 재생성
  - Ollama 4xx, DB 권한 오류, statement timeout/취소, 기타 DB 오류는 즉시 종료
  - 매 재생성 SQL에 같은 AST 검증 gate 적용, 거절 SQL은 실행기 미호출
- `POST /api/v1/text2sql/query`를 구현했다.
  - 성공: `columns`, `rows`, `truncated`, `attemptCount`, `elapsedMs`
  - 실패: 안정적인 `code`, `attemptCount`
  - 질문·SQL·LLM 응답·DB 오류 원문 비노출
- 시도별 로그에는 correlation ID, attempt, 결과 코드, backoff, elapsed time만 기록한다.
- 새 Java 소스와 보안 판단에 상세한 한글 Javadoc·주석을 작성했다.

## 2. 실제 실행한 테스트와 결과

### 이전 단계 기준

- 2단계 전체: 20개 통과, 실패 0.
- 3단계 전체: 57개 통과, 실패 0.
- 3단계 세부: AST 검증 36개, 악의적 fixture 20건 실행기 호출 0회.

### 4단계 단위·API 테스트

- 명령:
  `$env:JAVA_TOOL_OPTIONS=...; .\gradlew.bat --no-daemon test --tests 'dev.safetext2sql.workflow.*' --tests 'dev.safetext2sql.api.*' --tests 'dev.safetext2sql.sql.validation.MaliciousSqlFixtureTest' --rerun-tasks`
- 결과: `BUILD SUCCESSFUL`.
- 확인 내용:
  - `500 → 500 → valid SELECT`: LLM 3회, 실행기 1회, backoff `[200ms, 400ms]`, 성공
  - `500 → 500 → 500`: LLM 정확히 3회, 실행기 0회, API 502
  - `400`: LLM 1회, 실행기 0회, 즉시 API 502
  - 검증 실패 후 재생성과 3회 실패 시 모든 거절 SQL 실행기 호출 0회
  - 회복 가능한 DB 오류만 재생성
  - DB 권한 오류, timeout, 기타 DB 오류, backoff 중 취소는 재시도 0회
  - 빈 질문·길이 초과는 LLM과 실행기 모두 0회
  - 외부 설정이 3회·1,000행·30초 절대 상한을 넘으면 시작 시 거부

### 4단계 PostgreSQL 통합 테스트

- 명령:
  `$env:DOCKER_HOST='npipe:////./pipe/docker_engine'; .\gradlew.bat --no-daemon test --tests dev.safetext2sql.DatabaseIntegrationTest --info`
- 결과: `BUILD SUCCESSFUL`, PostgreSQL 통합 테스트 6개 통과(마지막 API 배선 테스트 추가 전 실행).
- 실제 확인 내용:
  - PostgreSQL 16.9 Testcontainers 및 Flyway migration 3개
  - datasource 실제 사용자 `text2sql_ro`
  - JDBC read-only hint를 해제해도 INSERT가 SQLSTATE `42501`로 거부
  - `customers.email`, `admin_users`가 SQLSTATE `42501`로 거부
  - 검증된 조회가 Native Query로 실행되고 10행 반환 후 `truncated=true`
  - `pg_sleep(1)`에 테스트용 100ms DB statement timeout 적용, SQLSTATE `57014`를 `STATEMENT_TIMEOUT`으로 분류

### 4단계 최종 전체 테스트

- 명령:
  `$env:DOCKER_HOST='npipe:////./pipe/docker_engine'; .\gradlew.bat --no-daemon test`
- 결과: `BUILD SUCCESSFUL`, 테스트 79개 통과, 실패 0, 오류 0, 건너뜀 0.
- 테스트 결과 XML 12개를 합산해 수치를 확인했다.
- PostgreSQL 통합 테스트 7개가 모두 통과했으며, 마지막 API 배선 테스트도 포함한다.

### 실행 환경

- OS: Microsoft Windows 11 Home 64비트, build 26200
- CPU: 11th Gen Intel Core i5-1135G7 @ 2.40GHz
- RAM: 8,379,490,304 bytes
- Java: Eclipse Temurin 21.0.8
- Docker Engine: 24.0.7
- Docker Compose: v2.23.3-desktop.2
- PostgreSQL: 16.9 Alpine image
- 4단계 기준 브랜치: `codex/stage-4-query-execution` (병합 기준 `master` SHA `41a6c4f`)
- Ollama: 사용자 요청에 따라 설치·실행·버전 확인하지 않음
- 로컬 LLM 모델: `PENDING`

## 3. 현재 정상 동작하는 기능

- migration과 read-only DB 계정이 분리되고 DB 권한이 쓰기·민감 데이터 접근을 차단한다.
- Ollama, Fake, Scripted generator가 같은 `SqlGenerator` 경계 뒤에서 동작한다.
- SQL이 AST와 Allowlist를 통과해야만 `ValidatedSelect`가 된다.
- Native Query 실행기는 `ValidatedSelect`만 받고 결과 행 상한과 DB timeout을 강제한다.
- 전체 LLM 호출을 3회로 제한하고 오류 종류에 따라 재시도 여부를 fail-closed로 결정한다.
- 재생성된 SQL도 매번 같은 검증 gate를 통과하며 실패 SQL은 실행되지 않는다.
- REST API가 결과와 시도 메타데이터만 반환하고 내부 원문은 노출하지 않는다.
- Ollama가 없는 기본 설정에서도 애플리케이션이 기동되고 요청 시 안정적인 502를 반환한다.

## 4. 미완료 작업

- 4단계 구현과 검증은 완료했다. 공개 저장소 점검 후 기능 단위 커밋과 PR로 제출하며,
  사용자의 검토·병합만 남긴다.
- 자연어 50건 정확도, Golden SQL 결과 비교, 인덱스 전후 실행계획과 500 오류 측정은 5단계다.
- 아키텍처·위협 모델·실험 보고서·블로그 초안은 6단계다.
- benchmark seed는 아직 적재·측정하지 않았다.
- Ollama와 로컬 모델은 설치하거나 실행하지 않았다.

## 5. 발생한 오류와 확인된 원인

- Windows에서 Testcontainers가 named pipe를 자동 탐지하지 못해 `DOCKER_HOST=npipe:////./pipe/docker_engine`를 명시했다.
- 4단계 첫 통합 테스트에서 `NativeQueryExecutor`가 `final`이라 Spring CGLIB가 `@Transactional`
  프록시를 만들지 못했다. 구현 클래스만 프록시 가능하게 열고 입력 인터페이스와 `ValidatedSelect`
  생성 제한은 유지했으며 재실행에서 통과했다.
- 제한된 sandbox에서 Docker named pipe 접근이 거부된 실행 1회가 있었다. 승인된 동일 통합 테스트
  명령으로 실행해 실제 Docker Engine 24.0.7과 PostgreSQL 16.9에서 성공을 확인했다.
- PostgreSQL timeout 테스트에는 운영 Allowlist가 금지하는 `pg_sleep`이 필요했다. 운영 코드에 우회
  생성자를 추가하지 않고 검증 패키지의 테스트 소스에만 `ValidatedSelectTestFactory`를 두었다.
- 이전 단계의 Gradle 네트워크, Flyway 이력 개수, web starter, WireMock timeout, JSqlParser 5.3 API
  차이는 각 단계에서 원인을 확인해 해결했으며 현재 전체 소스에 반영되어 있다.

## 6. 다음 대화에서 바로 시작할 작업

4단계 PR을 사용자가 확인·병합하고 `계속 진행해`라고 요청한 경우에만 5단계를 시작한다.

1. `AGENTS.md`, `PROJECT_SPEC.md`, 이 파일을 끝까지 다시 읽는다.
2. 자연어 질의 50건과 Golden SQL/비교 모드를 작성한다.
3. 고정 seed DB에서 Ollama 로컬 모델의 실제 결과 집합 정확도를 측정한다.
4. 인덱스 전후 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`을 보존하고 요약 CSV를 만든다.
5. LLM 500 재시도 시나리오를 반복 측정해 중앙값·최소/최대 또는 p95를 기록한다.

Ollama와 모델이 준비되지 않았으면 설치를 임의로 진행하거나 측정한 척하지 않고 자동 테스트까지만
완료한 뒤 실측 결과를 `PENDING`으로 유지한다.

## 7. 실행 및 재현 명령어

PowerShell에서 비밀번호는 로컬 값으로 직접 설정한다.

```powershell
$env:DB_NAME = 'text2sql'
$env:DB_PORT = '5432'
$env:DB_MIGRATION_PASSWORD = '<local-migration-password>'
$env:APP_DB_PASSWORD = '<local-readonly-password>'
docker compose up -d --wait postgres
.\gradlew.bat bootRun
```

다른 터미널에서 health/API를 확인한다.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
$body = @{ question = '첫 주문을 알려줘' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/text2sql/query `
  -ContentType 'application/json' -Body $body
```

전체 테스트와 종료:

```powershell
$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'
.\gradlew.bat test --rerun-tasks
docker compose down
```

Docker와 Ollama 없이 4단계 단위 테스트만 실행:

```powershell
.\gradlew.bat test `
  --tests 'dev.safetext2sql.sql.validation.*' `
  --tests 'dev.safetext2sql.workflow.*' `
  --tests 'dev.safetext2sql.api.*'
```

Ollama adapter 설정은 Ollama와 모델이 이미 준비된 경우에만 직접 주입한다.

```powershell
$env:SQL_GENERATOR_PROVIDER = 'ollama'
$env:OLLAMA_BASE_URL = 'http://localhost:11434'
$env:OLLAMA_MODEL = '<local-model-name-and-version>'
$env:LLM_TEMPERATURE = '0'
```

## 8. 변경한 주요 파일

- 실행: `src/main/java/dev/safetext2sql/execution/*`
- 워크플로: `src/main/java/dev/safetext2sql/workflow/*`
- API: `src/main/java/dev/safetext2sql/api/*`
- Spring 조립: `src/main/java/dev/safetext2sql/config/Text2SqlRuntimeConfiguration.java`
- 설정: `src/main/resources/application.yml`
- 실행/DB 통합 테스트: `src/test/java/dev/safetext2sql/DatabaseIntegrationTest.java`
- 재시도 테스트: `src/test/java/dev/safetext2sql/workflow/*Test.java`
- API 테스트: `src/test/java/dev/safetext2sql/api/Text2SqlQueryApiTest.java`
- 보안 경계 테스트: `src/test/java/dev/safetext2sql/sql/validation/MaliciousSqlFixtureTest.java`
- 테스트 전용 검증 값 팩터리:
  `src/test/java/dev/safetext2sql/sql/validation/ValidatedSelectTestFactory.java`
- 문서: `README.md`, `docs/decisions.md`, `PROGRESS.md`
