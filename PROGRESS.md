# 작업 진행 상황

마지막 갱신: 2026-08-30 (Asia/Seoul)

## 1. 완료한 작업

- 1단계 Spring Boot 프로젝트를 Java 21, Spring Boot 3.5.16, Gradle Wrapper 8.12.1로 구성했다.
- PostgreSQL 16.9 Docker Compose 서비스, healthcheck, named volume, 초기 read-only 계정 생성 스크립트를 구성했다.
- Flyway migration 계정 `text2sql_migration`과 애플리케이션 계정 `text2sql_ro`를 분리했다.
- `analytics` 스키마에 합성 커머스 테이블 8개를 만들었다.
  - 조회 허용: `categories`, `products`, `customers`, `orders`, `order_items`, `payments`
  - 전체 금지: `admin_users`, `audit_logs`
- deterministic smoke seed를 적재했다: categories 5, products 20, customers 50, orders/order_items/payments 각 100행.
- `customers.email`, `customers.phone`을 제외한 허용 컬럼에만 read-only 계정의 `SELECT` 권한을 부여했다.
- 향후 5단계에서 사용할 50,000개 주문의 선택형 benchmark seed를 기본 migration location과 분리했다. 아직 benchmark 측정은 실행하지 않았다.
- Actuator health endpoint와 JPA/Flyway datasource 설정을 구성했다.
- `PROJECT_SPEC.md`, `CODEX_PROMPT.md`의 외부 유료 LLM 인증 및 한 번에 전 단계를 수행하라는 전제를 AGENTS.md의 로컬 Ollama·단계별 작업 규칙에 맞게 정정했다.
- 실행 방법과 결정 근거를 `README.md`, `docs/decisions.md`에 기록했다.
- 2단계 `SqlGenerator` 인터페이스와 `SqlGenerationRequest`, `GeneratedSql` 모델을 Java로 구현했다.
- 로컬 Ollama `/api/generate` 연동 구현체를 추가했다.
  - `stream=false`
  - model, base URL, temperature, connect/read timeout 설정 주입
  - base URL을 local loopback host로 제한해 원격 LLM endpoint 연결 거부
  - HTTP 4xx/5xx, 전송 오류, 응답 프로토콜 오류 구분
  - 응답 본문과 malformed JSON 원문을 예외 메시지·cause에 보존하지 않음
- 허용 스키마/컬럼, 단일 SELECT, 명시적 컬럼, 금지 구문, 함수 목록, 최대 200행을 안내하는 system prompt를 구현했다.
- 재생성 feedback을 자유 문장이 아닌 대문자 stable code 목록으로 제한했다.
- 기본 LLM provider를 `disabled`로 두고, `SQL_GENERATOR_PROVIDER=ollama`일 때만 Ollama bean이 생성되도록 구성했다.
- `src/testFixtures`에 고정 응답 `FakeSqlGenerator`와 순차 성공/실패 `ScriptedSqlGenerator`를 구현했다.
- WireMock 3.13.1로 실제 Ollama 없이 HTTP 요청과 오류 동작을 검증했다.
- 3단계 SQL 검증을 위해 JSqlParser 5.3을 고정 의존성으로 추가했다.
- `SqlValidator` 검증 경계와 외부에서 임의 생성할 수 없는 `ValidatedSelect`를 구현했다.
- `AstSqlValidator`가 단일 SELECT AST 전체를 재귀적으로 검사하도록 구현했다.
  - DDL/DML/DCL, 다중 문장, CTE, 집합 연산, 잠금 구문, SELECT INTO 거부
  - `analytics` 스키마, 합성 테이블 6개, 공개 컬럼, 함수 11개의 명시적 Allowlist
  - SELECT, JOIN, WHERE, GROUP BY, HAVING, ORDER BY, 함수 인자, 서브쿼리의 컬럼 참조 검사
  - scope별 테이블/별칭/파생 테이블 해석과 상관 서브쿼리의 부모 scope 해석
  - 모호한 컬럼, alias column list, wildcard, DISTINCT ON과 미지원 확장 구문 fail-closed 처리
  - SQL 10,000자, JOIN 4개, 서브쿼리 깊이 3, 명시적 LIMIT/FETCH 200행 상한
- 합성 이름만 사용한 악의적 SQL fixture를 정확히 20건으로 고정하고, 모두 거절되며 테스트용 Native Query 실행 경계가 0회 호출되는지 검증했다.
- Java 소스의 역할과 보안 판단을 따라갈 수 있도록 상세한 한글 Javadoc과 주석을 추가하고 이 규칙을 `AGENTS.md`에 기록했다.
- 이후 주요 단계는 독립 브랜치와 PR로 제출하고, 변경 전·후 및 실제 검증 결과를 PR 본문에 기록한 뒤 사용자 병합을 기다리도록 작업 규칙을 추가했다.
- 3단계 변경을 `codex/stage-3-sql-validation` 브랜치의 [PR #1](https://github.com/kiy3035/safe-text2sql-lab/pull/1)로 제출했다. 현재 사용자 검토와 병합을 기다린다.

## 2. 실제 실행한 테스트와 결과

### 2단계 완료 시점 전체 자동 테스트

- 명령: `$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'; .\gradlew.bat --no-daemon test --rerun-tasks`
- 결과: `BUILD SUCCESSFUL`, 테스트 20개 통과, 실패 0, 오류 0, 건너뜀 0.
- 확인 내용:
  - Flyway versioned migration 3개 적용
  - 애플리케이션 datasource의 실제 사용자 `text2sql_ro`
  - smoke seed 행 수
  - JDBC read-only hint를 해제해도 INSERT가 PostgreSQL SQLSTATE `42501`로 거부됨
  - `customers.email`과 `admin_users` 조회가 SQLSTATE `42501`로 거부됨

### 2단계 LLM 자동 테스트

- 2단계 테스트: 16개 통과, 실패 0.
- WireMock 연동 테스트 6개:
  - Ollama 요청 JSON의 model/system/prompt/options/`stream=false`
  - 정상 SQL 응답 변환
  - HTTP 400과 500의 상태 분류 및 응답 본문 비노출
  - read timeout 적용
  - incomplete 및 malformed JSON 응답의 fail-closed 처리
- 설정 테스트 4개:
  - provider `disabled`일 때 `SqlGenerator` bean 미생성
  - provider `ollama`와 설정 주입 시 bean 생성
  - URL user-info/원격 host, 빈 model, 유효하지 않은 temperature/timeout 거부
- 프롬프트·요청 모델 테스트 4개:
  - 스키마/컬럼/금지 구문/행 제한 포함
  - 질문 delimiter와 stable feedback code 적용
  - blank 질문과 자유 형식 feedback 거부
- Fake/Scripted LLM 테스트 2개:
  - 고정 응답 및 호출 기록
  - 순차 성공/실패 및 남은 step 검증

### 3단계 SQL 검증 자동 테스트

- `AstSqlValidatorTest`: 36개 통과, 실패 0.
- 허용 확인: 단순 조회, JOIN, 집계, GROUP BY/HAVING, ORDER BY 프로젝션 별칭, 인용 식별자, 상관/파생 서브쿼리, LIMIT/FETCH.
- 거절 확인: 비조회 statement, 다중 문장, CTE, 집합 연산, 잠금, SELECT INTO, 금지 schema/table/column/function, 모든 주요 AST 위치의 금지 컬럼, 모호한 컬럼, alias column list, wildcard, 과도한 LIMIT/JOIN/서브쿼리.
- `MaliciousSqlFixtureTest`: 고정 fixture 정확히 20건을 모두 차단했고, 각 차단 직후 테스트용 Native Query 실행 경계 호출 횟수 0을 확인했다.
- fixture에는 DROP/ALTER/CREATE/TRUNCATE, INSERT/UPDATE/DELETE, GRANT/REVOKE, CALL/COPY, stacked statement, UNION/INTERSECT, 시스템 catalog, 금지 테이블/컬럼/함수, data-modifying CTE가 포함된다.

### 3단계 완료 후 전체 자동 테스트

- 명령: `$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'; .\gradlew.bat --no-daemon test --rerun-tasks`
- 결과: `BUILD SUCCESSFUL`, 테스트 57개 통과, 실패 0, 오류 0, 건너뜀 0.
- 세부 결과: 기존 PostgreSQL 통합 테스트 4개, 기존 LLM 테스트 16개, AST 검증 테스트 36개, 악의적 fixture 실행 경계 테스트 1개 모두 통과.

### 실제 Compose DB 및 애플리케이션 실행

- 고유한 일회용 Compose 프로젝트로 PostgreSQL을 띄우고 boot jar를 실행했다.
- 결과: `/actuator/health` 응답 `UP`.
- DB 확인: PostgreSQL `16.9`, `analytics.orders` 100행.
- Flyway 로그: schema version `v3`, migration 3개 성공.
- 실행 확인 뒤 이 검증에서 만든 컨테이너, 네트워크, 합성 DB 볼륨만 제거했다.

### 실행 환경

- OS: Microsoft Windows 11 Home 64비트, build 26200
- CPU: 11th Gen Intel Core i5-1135G7 @ 2.40GHz
- RAM: 8,379,490,304 bytes
- Java: Eclipse Temurin 21.0.8
- Docker Engine: 24.0.7
- Docker Compose: v2.23.3-desktop.2
- PostgreSQL: 16.9 Alpine image
- PR base commit SHA: `e38b8b1`
- 3단계 테스트 기준 commit SHA: `d9e0fb3`
- 3단계 PR: [#1 SQL AST 검증 게이트 추가](https://github.com/kiy3035/safe-text2sql-lab/pull/1) — 검토 대기
- Ollama: 사용자 요청에 따라 설치·실행·버전 확인하지 않음
- 로컬 LLM 모델: `PENDING`

## 3. 현재 정상 동작하는 기능

- Docker Compose가 migration 계정으로 PostgreSQL을 초기화하고 별도 read-only 로그인 역할을 만든다.
- 애플리케이션 시작 시 migration 계정으로 Flyway를 실행한 뒤 read-only datasource로 연결한다.
- deterministic smoke schema/seed를 매번 빈 볼륨에서 재현할 수 있다.
- DB 권한 계층이 쓰기, 민감 컬럼, 금지 테이블 접근을 애플리케이션 검증기와 독립적으로 차단한다.
- Spring Boot Actuator health endpoint로 애플리케이션과 datasource 상태를 확인할 수 있다.
- `SqlGenerator` 인터페이스 뒤에서 Ollama와 테스트 대역을 교체할 수 있다.
- Ollama가 설치되지 않은 기본 환경에서도 LLM provider가 비활성화되어 애플리케이션과 테스트가 동작한다.
- Ollama adapter가 설정된 model과 prompt로 non-streaming 요청을 만들고 오류 유형을 안정적으로 구분한다.
- Fake/Scripted generator로 실제 모델 없이 후속 검증·재시도 로직을 테스트할 수 있다.
- 원시 SQL은 JSqlParser AST 검증을 통과한 경우에만 `ValidatedSelect`로 변환된다.
- 검증기가 허용된 schema/table/column/function을 모든 주요 SELECT AST 위치에서 검사한다.
- 테이블 별칭, 파생 테이블, 상관 서브쿼리를 scope별로 해석하고 모호하거나 해석 불가능한 참조를 거절한다.
- 악의적 SQL 고정 fixture 20건이 테스트용 실행 경계에 도달하지 않는다.

## 4. 미완료 작업

- 4단계 EntityManager 기반 Native Query 실행기는 아직 구현하지 않았다. 현재 executor 0회 검증은 3단계 테스트용 기록 경계에서 수행한다.
- 결과 행 수의 실행 시점 강제와 PostgreSQL statement timeout은 4단계 범위로 남아 있다.
- 최대 3회 LLM 호출, 검증 실패 재생성, 주입 가능한 backoff, API는 4단계 범위로 남아 있다.
- 정확도·인덱스 실험, 결과 보고서·블로그는 각 후속 단계 범위로 남아 있다.
- benchmark seed 파일은 만들었지만 5단계 전에는 적재·측정하지 않는다.
- Ollama와 로컬 모델은 설치하거나 실행하지 않았다.

## 5. 발생한 오류와 확인된 원인

- 최초 Gradle 실행은 sandbox 내부에서 native library를 만들 수 없어 실패했다. Gradle user home과 Java 임시 디렉터리를 저장소의 ignore 경로로 지정해 해결했다.
- 제한된 네트워크에서는 Spring Boot plugin을 받을 수 없었다. 승인 후 공식 Gradle/Maven 저장소에서 프로젝트 의존성만 내려받았다. 운영체제 프로그램은 설치하지 않았다.
- 최초 Testcontainers 실행은 이 Windows 환경에서 Docker named pipe 자동 탐지에 실패했다. `DOCKER_HOST=npipe:////./pipe/docker_engine`를 명시해 해결했다.
- 최초 Flyway 이력 테스트는 schema 생성 이력까지 세어 기대값 3 대신 4가 나왔다. `version is not null`인 versioned migration만 세도록 수정했다.
- 최초 boot jar 실행은 web starter가 없어 비웹 애플리케이션으로 정상 종료했다. `spring-boot-starter-web`을 추가한 뒤 health endpoint `UP`을 확인했다.
- WireMock 의존성은 제한된 sandbox 네트워크에서 처음 내려받지 못했다. 승인 후 Maven Central에서 테스트 의존성만 받아 해결했다.
- 최초 timeout 테스트에서 Spring `RestClient`가 read timeout을 응답 디코딩 예외로 감쌌다. cause chain의 `SocketTimeoutException`/`HttpTimeoutException`을 전송 오류로 분류하도록 수정했다.
- 2단계 전체 테스트의 첫 실행에서 Docker Desktop 엔진이 꺼져 있어 LLM 테스트 16개는 통과하고 PostgreSQL 테스트 4개가 실패했다. 설치된 Docker Desktop을 시작한 뒤 전체 20개를 캐시 없이 재실행해 모두 통과했다.
- 3단계 검증기 첫 테스트에서 JSqlParser 5.3의 `Table.getDBLinkName()`이 일반 테이블에도 테이블명을 반환해 모든 테이블이 미지원 구문으로 오인됐다. catalog/database와 `nameParts`를 명시적으로 검사하도록 바꿔 해결했다.
- JSqlParser가 `COUNT(*)`의 `*`를 함수 파라미터의 `AllColumns`로 표현해 일반 wildcard와 함께 거절됐다. 함수가 정확히 `count`이고 인자가 단일 `AllColumns`인 경우만 별도로 허용했다.
- 3단계 전체 테스트 첫 실행은 Docker Desktop 엔진이 꺼져 있어 PostgreSQL 통합 테스트 4개만 실패했다. 설치된 Docker Desktop을 시작한 뒤 전체 57개를 캐시 없이 재실행해 모두 통과했다.
- JSqlParser 5.3의 deprecated LIMIT/FETCH 접근자 경고를 확인했다. 현재 expression API로 교체하고 `-Xlint:deprecation`을 활성화한 뒤 경고 없이 컴파일되는 것을 확인했다.

## 6. 다음 대화에서 바로 시작할 작업

3단계 [PR #1](https://github.com/kiy3035/safe-text2sql-lab/pull/1)을 사용자가 확인·병합하고 `계속 진행해`라고 요청한 경우에만 4단계를 시작한다.

1. `AGENTS.md`, `PROJECT_SPEC.md`, 이 파일을 다시 끝까지 읽는다.
2. `ValidatedSelect`만 입력으로 받는 EntityManager Native Query 실행기를 구현한다.
3. 실행 결과 최대 200행과 PostgreSQL statement timeout을 강제한다.
4. 최초 호출 포함 최대 3회 LLM 호출과 200ms/400ms backoff를 주입 가능한 정책으로 구현한다.
5. 5xx와 검증 실패만 재시도하고 4xx, DB 권한 오류, timeout, 취소는 즉시 종료한다.
6. Scripted/Fake LLM과 Testcontainers로 API, 재시도, 실행 미호출 조건을 검증한다.

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

다른 터미널에서:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

테스트와 종료:

```powershell
$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'
.\gradlew.bat test --rerun-tasks
docker compose down
```

Ollama와 Docker DB 없이 3단계 검증 테스트만 실행:

```powershell
.\gradlew.bat test --tests 'dev.safetext2sql.sql.validation.*' --rerun-tasks
```

Ollama adapter 설정은 Ollama와 모델이 이미 준비된 이후에만 다음처럼 주입한다. 2단계에서는 실제 Ollama를 실행하지 않았다.

```powershell
$env:SQL_GENERATOR_PROVIDER = 'ollama'
$env:OLLAMA_BASE_URL = 'http://localhost:11434'
$env:OLLAMA_MODEL = '<local-model-name-and-version>'
$env:LLM_TEMPERATURE = '0'
$env:OLLAMA_CONNECT_TIMEOUT = '2s'
$env:OLLAMA_READ_TIMEOUT = '30s'
```

데이터를 포함한 볼륨 삭제는 합성 DB를 초기화하려는 경우에만 `docker compose down --volumes`로 수행한다.

## 8. 변경한 주요 파일

- 빌드/실행: `build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- Docker: `compose.yaml`, `docker/postgres/init/001-create-readonly-user.sh`
- 애플리케이션: `src/main/java/dev/safetext2sql/SafeText2SqlApplication.java`, `src/main/resources/application.yml`
- LLM 경계: `src/main/java/dev/safetext2sql/llm/SqlGenerator.java`, `SqlGenerationRequest.java`, `GeneratedSql.java`
- Ollama: `src/main/java/dev/safetext2sql/llm/ollama/*`, `src/main/java/dev/safetext2sql/llm/config/*`
- 프롬프트: `src/main/java/dev/safetext2sql/llm/prompt/SqlPromptTemplate.java`
- LLM 테스트 대역: `src/testFixtures/java/dev/safetext2sql/llm/support/*`
- SQL 검증: `src/main/java/dev/safetext2sql/sql/validation/*`
- 보안 fixture와 테스트: `src/test/resources/security/malicious-sql.json`, `src/test/java/dev/safetext2sql/sql/validation/*Test.java`
- Flyway: `src/main/resources/db/migration/V1__create_analytics_schema.sql`, `V2__load_smoke_seed.sql`, `V3__grant_readonly_privileges.sql`
- 선택형 seed: `src/main/resources/db/benchmark/R__load_benchmark_seed.sql`
- 테스트: `src/test/java/dev/safetext2sql/DatabaseIntegrationTest.java`, `src/test/java/dev/safetext2sql/llm/**/*Test.java`
- 문서: `README.md`, `docs/decisions.md`, `PROJECT_SPEC.md`, `CODEX_PROMPT.md`, `PROGRESS.md`
- 저장소 설정: `.gitignore`, `.gitattributes`, `.env.example`
