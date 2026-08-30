# 작업 진행 상황

마지막 갱신: 2026-08-30 (Asia/Seoul)

## 1. 완료한 작업

### 1~4단계

- Java 21, Spring Boot 3.5.16, Gradle 8.12.1, PostgreSQL 16.9 기반 프로젝트와 합성 DB를 구성했다.
- Flyway migration 계정 `text2sql_migration`과 최소 컬럼 SELECT 권한만 가진 `text2sql_ro`를 분리했다.
- Ollama HTTP adapter, Scripted/Fake LLM, WireMock 검증을 구현했다. Ollama와 모델은 설치하지 않았다.
- JSqlParser AST 기반 SELECT-only 검증, schema/table/column/function Allowlist, alias·서브쿼리 검증과
  악의적 SQL 20건 실행기 미호출 테스트를 구현했다.
- `ValidatedSelect`만 받는 Native Query 실행기, 결과 행 상한, PostgreSQL statement timeout,
  최대 3회 호출과 200ms/400ms 백오프, REST API를 구현했다.
- 3단계 PR #1과 4단계 PR #2는 사용자가 확인해 `master`에 병합했다.

### 5단계: 측정 실험

- `experiments/nl-questions.jsonl`에 자연어 질문 정확히 50건을 고정했다.
  - 난이도와 `SCALAR`, `ORDERED`, `UNORDERED` 결과 비교 모드를 각 질문에 명시했다.
- `experiments/golden-sql.jsonl`에 질문과 1:1로 연결된 Golden SQL 50건을 분리했다.
  - 문자열 일치가 아니라 고정 seed DB의 결과 집합을 숫자 정규화·순서·중복 정책에 따라 비교한다.
  - fixture 개수, ID 중복/누락, 난이도 분포, 모든 Golden SQL의 AST 검증을 자동 테스트한다.
- 자연어 정확도 실험 runner를 구현했다.
  - 생성 SQL은 기존 `Text2SqlQueryService`의 검증·재시도·실행 경계를 그대로 통과한다.
  - Golden SQL도 AST 검증과 read-only Native Query 실행기를 통과한다.
  - Ollama가 없으면 Golden 50건만 검증·실행하고 정확도는 `PENDING`으로 기록한다.
  - `metadata.json`, `nl-summary.json`, `nl-results.json`, `nl-results.csv`를 생성한다.
- 인덱스 전후 실험 runner를 구현했다.
  - 50,100개 주문 benchmark seed가 없으면 실행을 거부한다.
  - 외부 입력이 아닌 고정 SELECT 3개와 고정 후보 인덱스 `(status, ordered_at)`만 사용한다.
  - 전후 각 조회를 1회 예열하고 10회 측정한다.
  - `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` 원본 60개와 전체 측정 JSON·요약 CSV를 남긴다.
  - DDL은 migration 계정, EXPLAIN SELECT는 read-only 계정으로 분리하고 finally에서 후보 인덱스를 제거한다.
- WireMock 재시도 측정 runner를 구현했다.
  - `500→500→200`, `500→500→500`, `400`을 각각 10회 측정했다.
  - 실제 Ollama HTTP adapter와 실제 200ms/400ms sleep을 사용하고 요청 간격·전체 경과시간을 기록했다.
- 모든 결과에 commit SHA, 실행 시각, OS, CPU, RAM, Java, PostgreSQL, Ollama/model/temperature,
  prompt hash와 데이터 건수를 해당되는 범위에서 기록했다. DB 비밀번호와 URL은 기록하지 않았다.
- 실험 중 발견한 PostgreSQL Native Query의 LIMIT 중복 적용 결함을 수정했다.
  - 최상위 LIMIT/FETCH 여부를 문자열이 아닌 검증된 AST에서 `ValidatedSelect`에 기록한다.
  - 명시적 제한은 AST에서 최대 200행으로 제한되므로 Hibernate의 중복 `setMaxResults`를 생략한다.
  - 실행 설정이 더 작으면 응답은 계속 해당 상한으로 자르고 `truncated=true`를 반환한다.

## 2. 실제 실행한 테스트와 결과

### 최종 전체 테스트

- 명령:
  `$env:DOCKER_HOST='npipe:////./pipe/docker_engine'; .\gradlew.bat --no-daemon test --rerun-tasks`
- 결과: `BUILD SUCCESSFUL`, 91개 통과, 실패 0, 오류 0, 건너뜀 0.
- 포함 확인:
  - 악의적 SQL fixture 20건 모두 Native Query executor 호출 0회
  - Golden SQL 50건 모두 PostgreSQL 16.9 고정 smoke seed에서 문법·권한 오류 없이 실행
  - LIMIT 5 정상 실행, 실행 상한 10보다 큰 LIMIT 20은 10행만 반환하고 `truncated=true`
  - `500→500→200` 정확히 3회 후 성공, `500→500→500` 3회 후 종료, `400` 즉시 종료
  - 단순 조회, JOIN, 집계, 서브쿼리 허용 및 금지 테이블·컬럼·함수·UNION·다중 문장 차단

### 자연어 50건 실험

- 결과 디렉터리: `results/stage5-nl-pending-20260830`
- 코드 SHA: `5c94665238b49b41a214ac8247c0aa59929f74b6`
- Golden SQL 50건 AST 검증과 read-only Native Query 실행: 성공.
- 상태: `PENDING`, 사유: `OLLAMA_NOT_CONFIGURED`.
- 정확도·생성 성공률·첫 시도 검증 통과율은 측정하지 않았으며 모두 `null`로 기록했다.
- Ollama 프로그램과 로컬 모델을 설치·실행하지 않았고 Fake 결과를 실측 정확도로 사용하지 않았다.

### 인덱스 전후 실험

- 결과 디렉터리: `results/stage5-index-20260830`
- 코드 SHA: `5c94665238b49b41a214ac8247c0aa59929f74b6`
- 데이터: orders/order_items/payments 각 50,100행, PostgreSQL 16.9.
- 고정 조회 3개 × 전후 2조건 × 10회 = 원본 실행계획 JSON 60개 생성.
- 이번 로컬 실행의 중앙 실행시간:
  - IDX-001: Seq Scan 3.5685ms → Bitmap Heap Scan 0.3445ms
  - IDX-002: Seq Scan 4.1380ms → Index Scan 0.0945ms
  - IDX-003: Seq Scan 5.0655ms → Bitmap Heap Scan 1.7835ms
- 위 수치는 한 환경의 10회 측정 결과이며 다른 환경의 성능을 보장하지 않는다. 최소·최대·p95와
  planner/actual rows, buffer hit/read 수치는 `index-summary.csv`와 원본 JSON에 함께 보존했다.

### LLM HTTP 재시도 측정

- 결과 디렉터리: `results/stage5-retry-20260830`
- 코드 SHA: `5c94665238b49b41a214ac8247c0aa59929f74b6`
- 각 시나리오 10회, 총 30회 측정. 모든 반복에서 예상 HTTP 호출 수와 결과 유형을 만족했다.
- 중앙값:
  - `500→500→200`: 전체 680.0ms, 첫/둘째 요청 간격 229.0ms/426.0ms
  - `500→500→500`: 전체 672.0ms, 첫/둘째 요청 간격 229.0ms/427.0ms
  - `400`: 전체 12.0ms, HTTP 호출 1회, 재시도 없음
- 첫 성공 시나리오에는 JVM/HTTP 초기화 비용이 포함되어 max와 p95가 1497.0ms다. 원본 30건을
  삭제하지 않고 `retry-measurements.json`에 보존했다.

### 실행 환경

- OS: Windows 11 10.0 amd64
- CPU: Intel64 Family 6 Model 140 Stepping 1, GenuineIntel
- RAM: 8,379,490,304 bytes
- Java: Eclipse Temurin 21.0.8
- Docker Engine: 24.0.7
- PostgreSQL: 16.9 Alpine image
- Ollama: `NOT_INSTALLED`
- 로컬 LLM 모델: `PENDING`

## 3. 현재 정상 동작하는 기능

- 자연어 50건과 Golden SQL 50건의 형식·연결·AST·DB 실행을 자동 검증한다.
- Ollama가 준비되면 기존 보안 파이프라인으로 50건을 실행해 결과 집합 정확도와 세부 실패를 기록한다.
- Ollama가 없으면 실제 정확도를 만들지 않고 명시적인 PENDING 결과만 생성한다.
- benchmark DB에서 인덱스 전후 실행계획 원본과 반복 통계를 재현 가능하게 생성한다.
- WireMock으로 실제 HTTP 재시도 호출 수와 200ms/400ms 지연을 반복 측정한다.
- 1~4단계의 DB 최소 권한, AST gate, 실행 상한, timeout, 오류별 재시도와 API가 계속 동작한다.

## 4. 미완료 작업

- 자연어 50건의 실제 로컬 LLM 정확도 측정은 Ollama와 모델이 없으므로 `PENDING`이다.
- 5단계 구현·자동 테스트·Golden 실행·인덱스 및 WireMock 측정은 완료했다. PR의 사용자 검토·병합이 남았다.
- 아키텍처 문서, 위협 모델과 남은 한계, 실험 결과 보고서, 블로그 초안, README 빈 환경 재현 검증은 6단계다.
- 측정 결과를 블로그 결론으로 해석하거나 일반화하는 6단계 작업은 시작하지 않았다.

## 5. 발생한 오류와 확인된 원인

- 첫 자연어 PENDING 실행에서 Golden의 기존 `LIMIT` 뒤에 Hibernate가 `FETCH`를 덧붙여 PostgreSQL
  문법 오류가 발생했다. 최상위 LIMIT/FETCH를 AST 결과에 기록해 중복 제한을 피하고, LIMIT 5/20
  PostgreSQL 회귀 테스트와 Golden 50건 실행으로 해결을 확인했다.
- Windows Testcontainers는 named pipe를 자동 탐지하지 못해
  `DOCKER_HOST=npipe:////./pipe/docker_engine`가 필요했다.
- 실험 도중 Docker Desktop daemon이 종료되어 통합 테스트가 1회 초기화 실패했다. 기존 Docker
  Desktop만 다시 시작했으며 설치·관리자 권한 변경 없이 같은 테스트가 통과했다.
- 제한된 sandbox에서 실행한 Docker 없는 전체 테스트는 통합 테스트 8건이 초기화 실패했다.
  코드 실패가 아니라 Docker 환경 부재임을 XML에서 확인했고 named pipe 설정 실행으로 검증했다.
- Ollama가 설치되지 않아 자연어 정확도는 측정할 수 없었다. 규칙에 따라 수치를 추정하지 않고 PENDING으로 남겼다.

## 6. 다음 대화에서 바로 시작할 작업

5단계 PR을 사용자가 확인·병합하고 `계속 진행해`라고 요청한 경우에만 6단계를 시작한다.

1. `AGENTS.md`, `PROJECT_SPEC.md`, 이 파일을 끝까지 다시 읽는다.
2. 결과 파일을 근거로 아키텍처와 위협 모델·남은 한계를 문서화한다.
3. 인덱스와 재시도 측정 결과 보고서를 작성하고 모든 수치를 원본 CSV/JSON과 대조한다.
4. 자연어 정확도는 Ollama 실측 전까지 PENDING임을 유지한다.
5. 실제 측정값만 사용한 블로그 초안과 빈 환경 README 재현 절차를 검증한다.

## 7. 실행 및 재현 명령어

PowerShell 공통 DB 설정(비밀번호는 로컬 값만 사용):

```powershell
$env:DB_NAME = 'text2sql'
$env:DB_PORT = '5432'
$env:DB_MIGRATION_PASSWORD = '<local-migration-password>'
$env:APP_DB_PASSWORD = '<local-readonly-password>'
$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'
```

전체 테스트:

```powershell
.\gradlew.bat test --rerun-tasks
```

Ollama 없이 Golden 50건 실행과 PENDING 결과 생성:

```powershell
$env:SQL_GENERATOR_PROVIDER = 'disabled'
$env:OLLAMA_VERSION = 'NOT_INSTALLED'
$env:EXPERIMENT_RUN_ID = 'my-nl-pending-run'
docker compose up -d --wait postgres
.\gradlew.bat nlExperiment
docker compose down
```

50,100행 benchmark DB에서 인덱스 전후 실험:

```powershell
$env:DB_FLYWAY_LOCATIONS = 'classpath:db/migration,classpath:db/benchmark'
$env:EXPERIMENT_RUN_ID = 'my-index-run'
docker compose up -d --wait postgres
.\gradlew.bat indexExperiment
docker compose down
```

WireMock 재시도 측정(Docker·Ollama 불필요):

```powershell
$env:EXPERIMENT_RUN_ID = 'my-retry-run'
.\gradlew.bat retryExperiment
```

이미 준비된 Ollama로 실제 자연어 50건을 측정할 때만 다음을 추가한다. 모델 설치 명령은 이 단계에서 실행하지 않았다.

```powershell
$env:SQL_GENERATOR_PROVIDER = 'ollama'
$env:OLLAMA_BASE_URL = 'http://localhost:11434'
$env:OLLAMA_MODEL = '<local-model-name-and-version>'
$env:OLLAMA_VERSION = '<installed-ollama-version>'
$env:LLM_TEMPERATURE = '0'
$env:EXPERIMENT_RUN_ID = 'my-nl-ollama-run'
.\gradlew.bat nlExperiment
```

## 8. 변경한 주요 파일

- fixture: `experiments/nl-questions.jsonl`, `experiments/golden-sql.jsonl`
- 공통 메타데이터: `src/main/java/dev/safetext2sql/experiment/*`
- 자연어 실험: `src/main/java/dev/safetext2sql/experiment/nl/*`
- 인덱스 실험: `src/main/java/dev/safetext2sql/experiment/index/*`
- 재시도 측정: `src/test/java/dev/safetext2sql/experiment/retry/RetryMeasurementMain.java`
- 실행 태스크: `build.gradle`
- LIMIT 회귀 수정: `ValidatedSelect.java`, `AstSqlValidator.java`, `NativeQueryExecutor.java`
- 테스트: `src/test/java/dev/safetext2sql/experiment/*`, `DatabaseIntegrationTest.java`, `AstSqlValidatorTest.java`
- 결과: `results/stage5-nl-pending-20260830`, `results/stage5-index-20260830`,
  `results/stage5-retry-20260830`
- 문서: `README.md`, `docs/decisions.md`, `PROGRESS.md`
