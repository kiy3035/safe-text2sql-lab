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

## 2. 실제 실행한 테스트와 결과

### 전체 자동 테스트

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
- 기준 commit SHA: `0f263ec` (이번 단계 변경은 아직 커밋하지 않음)
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

## 4. 미완료 작업

- 3단계 SQL AST 검증 gate, allowlist, alias/서브쿼리 해석, 악의적 SQL 20종 fixture는 시작하지 않았다.
- Native Query 실행, 재시도/API, 정확도·인덱스 실험, 보고서·블로그는 각 후속 단계 범위로 남아 있다.
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

## 6. 다음 대화에서 바로 시작할 작업

사용자가 `계속 진행해`라고 요청한 경우에만 3단계를 시작한다.

1. `AGENTS.md`, `PROJECT_SPEC.md`, 이 파일을 다시 끝까지 읽는다.
2. JSqlParser 등 SQL AST parser를 선정하고 의존성 추가 이유를 기록한다.
3. 단일 SELECT와 금지 statement/구문을 fail-closed로 검증한다.
4. schema/table/column/function allowlist와 scope별 alias/서브쿼리 해석을 구현한다.
5. 정확히 20개의 악의적 SQL fixture와 허용 SQL 테스트를 작성한다.
6. 모든 차단 fixture에서 Native Query executor 호출 0회를 검증할 수 있는 경계를 준비한다.

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
- Flyway: `src/main/resources/db/migration/V1__create_analytics_schema.sql`, `V2__load_smoke_seed.sql`, `V3__grant_readonly_privileges.sql`
- 선택형 seed: `src/main/resources/db/benchmark/R__load_benchmark_seed.sql`
- 테스트: `src/test/java/dev/safetext2sql/DatabaseIntegrationTest.java`, `src/test/java/dev/safetext2sql/llm/**/*Test.java`
- 문서: `README.md`, `docs/decisions.md`, `PROJECT_SPEC.md`, `CODEX_PROMPT.md`, `PROGRESS.md`
- 저장소 설정: `.gitignore`, `.gitattributes`, `.env.example`
