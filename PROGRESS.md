# 작업 진행 상황

마지막 갱신: 2026-08-31 (Asia/Seoul)

## 1. 완료한 작업

### 1~4단계: 실행 가능한 안전 경계

- Java 21, Spring Boot 3.5.16, Gradle 8.12.1, PostgreSQL 16.9 프로젝트와 합성 커머스 DB를 구성했다.
- Flyway migration 계정 `text2sql_migration`과 공개 컬럼 SELECT만 가능한 `text2sql_ro`를 분리했다.
- 로컬 Ollama `SqlGenerator`, Scripted/Fake LLM, WireMock adapter 테스트를 구현했다.
- JSqlParser AST 기반 단일 SELECT·Allowlist 검증과 악의적 SQL 20건 fixture를 구현했다.
- `ValidatedSelect`만 받는 Native Query 실행기, 행 상한, DB statement timeout, 최대 3회 호출과
  200ms/400ms 백오프, REST API를 구현했다.
- 3단계 PR #1과 4단계 PR #2가 `master`에 병합됐다.

### 5단계: 측정 실험

- 자연어 질문과 Golden SQL 각 50건, `SCALAR`/`ORDERED`/`UNORDERED` 결과 비교기를 구현했다.
- Ollama가 없을 때 Golden 50건만 검증·실행하고 정확도는 `PENDING`으로 남기는 runner를 구현했다.
- 50,100행에서 고정 SELECT 3개의 인덱스 전후 실행계획을 각 10회 측정하고 원본 JSON 60개를 보존했다.
- WireMock으로 `500→500→200`, `500→500→500`, `400`을 각 10회 실제 백오프와 함께 측정했다.
- 결과에 실행환경, 데이터 건수, 모델 상태, prompt hash, 측정 코드 SHA와 실행 시각을 기록했다.
- 실험에서 발견한 Hibernate의 기존 LIMIT 뒤 중복 FETCH 문제를 AST 표식과 회귀 테스트로 수정했다.
- 5단계 PR #3이 merge commit `c4e23bffb4dfbeb0bdd22fcbea0e9881f603f3b0`으로 병합됐다.

### 6단계: 문서와 블로그

- `docs/architecture.md`에 요청 흐름, 신뢰 경계, AST gate, 재시도, DB 계정과 실험 구조를 작성했다.
- `docs/threat-model.md`에 막도록 설계한 위협과 근거, 막지 못하는 정확성·추론·가용성·운영 한계를 분리했다.
- `docs/experiment-report.md`에 테스트, 자연어 PENDING, 인덱스, 재시도 결과를 원본 경로와 함께 기록했다.
- `docs/blog-draft.md`에 실제 구현·실패·측정값만 사용한 한국어 블로그 초안을 작성했다.
  - 회사·운영 경험처럼 표현하지 않았다.
  - 악의적 SQL 20건 차단을 모든 SQL 공격의 완전한 증명으로 일반화하지 않았다.
  - 자연어 50건 정확도를 `PENDING`으로 유지하고 Fake 결과를 실측값으로 쓰지 않았다.
  - 인덱스와 WireMock 수치의 로컬 환경·캐시·표본·초기화 비용 한계를 함께 적었다.
- `scripts/verify_documented_results.ps1`를 추가했다.
  - 질문/Golden 각 50건, 악의적 fixture 20건, plan 60개, retry 원본 30개를 확인한다.
  - 인덱스 중앙값·최소·최대·p95와 파생 비율, 재시도 요약, 측정 SHA를 보고서·블로그와 대조한다.
  - 자연어 정확도가 `PENDING/null`인지와 전체 Gradle 테스트 XML 수치를 확인한다.
- README에 문서 링크, 실제 로컬 모델을 사용자가 별도로 준비하는 명령, 별도 Compose project를
  사용하는 빈 환경 재현 절차와 문서 수치 검증 명령을 추가했다.
- `docs/decisions.md`에 문서 분리, 원본 수치 단일 출처, PENDING 유지, 재현 DB 격리 결정을 기록했다.

## 2. 실제 실행한 테스트와 결과

### 6단계 최종 전체 테스트

- 명령:
  `$env:DOCKER_HOST='npipe:////./pipe/docker_engine'; .\gradlew.bat --no-daemon test --rerun-tasks`
- 결과: `BUILD SUCCESSFUL`, 91개 통과, 실패 0, 오류 0, 건너뜀 0.
- 포함 확인:
  - 악의적 SQL fixture 20건 모두 Native Query executor 호출 0회
  - `500→500→200` 3회 후 성공, `500→500→500` 3회 후 종료, `400` 즉시 종료
  - 단순 SELECT, JOIN, 집계, 서브쿼리 허용
  - 금지 테이블·컬럼·함수·UNION·다중 문장 차단
  - PostgreSQL 실제 read-only 권한, 행 상한, statement timeout, LIMIT 회귀
  - Golden SQL 50건의 AST 검증과 고정 seed DB 실행

### 문서 수치와 링크 검증

- 명령: `.\scripts\verify_documented_results.ps1`
- 결과:
  `DOCUMENTED_RESULTS_VERIFIED questions=50 golden=50 malicious=20 plans=60 retry=30 tests=91 failures=0 skipped=0`
- Markdown 상대 링크 검사 결과: `MARKDOWN_LINKS_OK`.
- 자연어 결과의 `status=PENDING`, `pendingReason=OLLAMA_NOT_CONFIGURED`, 정확도 관련 null 값을 재확인했다.

### README 새 환경 재현

- 별도 Compose project `safe-text2sql-stage6-readme-check`와 새 volume을 사용했다.
- PostgreSQL 16.9 health 확인 후 Flyway migration 3개가 새 `analytics` schema에 적용됐다.
- `SQL_GENERATOR_PROVIDER=disabled`, 별도 포트에서 `bootRun`으로 애플리케이션을 기동했다.
- 실제 확인:
  - `GET /actuator/health`: `UP`
  - `POST /api/v1/text2sql/query`: HTTP 502, `LLM_UNAVAILABLE`, `attemptCount=0`
- 확인 후 장기 실행 중인 Java 프로세스를 종료했고, 해당 Compose project의 컨테이너·network·volume만 제거했다.

### 보존된 5단계 측정 결과

- 자연어: Golden 50건 실행 성공, 실제 모델 정확도 `PENDING`.
- 인덱스 중앙 실행시간:
  - IDX-001: 3.5685ms → 0.3445ms
  - IDX-002: 4.1380ms → 0.0945ms
  - IDX-003: 5.0655ms → 1.7835ms
- 재시도 중앙값:
  - `500→500→200`: 680.0ms, HTTP 3회 후 성공
  - `500→500→500`: 672.0ms, HTTP 3회 후 종료
  - `400`: 12.0ms, HTTP 1회 후 즉시 종료
- 세 결과의 측정 코드 SHA:
  `5c94665238b49b41a214ac8247c0aa59929f74b6`.

### 실행 환경

- OS: Windows 11 10.0 amd64
- CPU: Intel64 Family 6 Model 140 Stepping 1, GenuineIntel
- RAM: 8,379,490,304 bytes
- Java: Eclipse Temurin 21.0.8
- Docker Engine: 24.0.7
- PostgreSQL: 16.9 Alpine
- Ollama: `NOT_INSTALLED`
- 로컬 LLM 모델: `PENDING`

## 3. 현재 정상 동작하는 기능

- Spring API가 자연어 질문을 LLM 생성, AST 검증, read-only Native Query 순서로 처리한다.
- 거절 SQL은 실행기에 도달하지 않고, 재생성된 SQL도 매번 동일한 gate를 통과한다.
- DB 최소 권한, 결과 행 상한, statement timeout, 오류별 최대 3회 호출 정책이 독립적으로 동작한다.
- Ollama가 없는 기본 설정에서도 애플리케이션이 기동되고 안정적인 502 오류를 반환한다.
- 질문·Golden·보안 fixture와 세 측정 runner를 로컬 무료 도구로 재현할 수 있다.
- 아키텍처·위협 모델·보고서·블로그의 측정 수치를 원본 CSV/JSON으로 역추적할 수 있다.

## 4. 미완료 작업

- 자연어 50건의 실제 로컬 LLM 정확도는 Ollama와 모델이 없으므로 `PENDING`이다.
- Ollama 프로그램·모델 설치, 실제 추론 실행, 모델 출처·라이선스 검토는 수행하지 않았다.
- 인증·사용자별 권한·rate limit·운영 모니터링·TLS·다중 tenant 격리는 프로젝트 범위 밖이다.
- 6단계 구현과 검증은 완료했으며, 6단계 PR의 사용자 검토·병합이 남았다.

## 5. 발생한 오류와 확인된 원인

- Windows Testcontainers는 Docker named pipe를 자동 탐지하지 못해
  `DOCKER_HOST=npipe:////./pipe/docker_engine`를 명시했다.
- 5단계 첫 자연어 실행은 기존 LIMIT 뒤 Hibernate가 FETCH를 중복 생성해 실패했다. 최상위
  LIMIT/FETCH를 검증 AST 결과에 기록해 해결했고 PostgreSQL 회귀 테스트와 Golden 50건으로 확인했다.
- 6단계 README 재현에서 health/API 확인 후 장기 실행 중인 `bootRun` Java 프로세스를 의도적으로
  종료했다. 애플리케이션 기동 검증은 성공했지만 Gradle task는 강제 종료 코드 `-1`로 `FAILED`를
  표시했다. 테스트 실패나 애플리케이션 시작 실패가 아니다.
- Ollama가 없어 실제 자연어 정확도를 측정할 수 없었다. 수치를 만들지 않고 PENDING으로 남겼다.

## 6. 다음 대화에서 바로 시작할 작업

6단계 PR을 사용자가 확인·병합하기 전에는 추가 주요 작업을 시작하지 않는다.

병합 후에도 자동으로 새 단계를 시작하지 않는다. 사용자가 Ollama와 특정 로컬 모델을 직접 준비한
뒤 실제 자연어 50건 재측정을 명시적으로 요청하는 경우에만 다음을 수행한다.

1. 모델명·버전, Ollama 버전과 라이선스를 확인한다.
2. 기존 `nlExperiment`로 50건을 실행하고 새 run ID 결과를 추가한다.
3. 결과 보고서와 블로그의 PENDING 항목만 실제 원본 수치로 갱신한다.
4. 전체 테스트와 문서 수치 검증을 다시 실행한다.

## 7. 실행 및 재현 명령어

PowerShell 공통 설정:

```powershell
$env:DB_NAME = 'text2sql'
$env:DB_PORT = '5432'
$env:DB_MIGRATION_PASSWORD = '<local-migration-password>'
$env:APP_DB_PASSWORD = '<local-readonly-password>'
$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'
```

애플리케이션과 DB:

```powershell
docker compose up -d --wait postgres
.\gradlew.bat bootRun
```

전체 테스트와 문서 수치 검증:

```powershell
.\gradlew.bat test --rerun-tasks
.\scripts\verify_documented_results.ps1
```

Ollama 없는 자연어 PENDING, 인덱스, 재시도 실험:

```powershell
$env:SQL_GENERATOR_PROVIDER = 'disabled'
$env:OLLAMA_VERSION = 'NOT_INSTALLED'
$env:EXPERIMENT_RUN_ID = 'my-nl-pending-run'
.\gradlew.bat nlExperiment

$env:DB_FLYWAY_LOCATIONS = 'classpath:db/migration,classpath:db/benchmark'
$env:EXPERIMENT_RUN_ID = 'my-index-run'
.\gradlew.bat indexExperiment

$env:EXPERIMENT_RUN_ID = 'my-retry-run'
.\gradlew.bat retryExperiment
```

실제 모델 명령은 Ollama를 사용자가 별도로 준비한 경우에만 실행한다.

```powershell
ollama pull <local-model-name-and-version>
ollama list
$env:SQL_GENERATOR_PROVIDER = 'ollama'
$env:OLLAMA_MODEL = '<local-model-name-and-version>'
$env:OLLAMA_VERSION = '<installed-ollama-version>'
$env:LLM_TEMPERATURE = '0'
$env:EXPERIMENT_RUN_ID = 'my-nl-ollama-run'
.\gradlew.bat nlExperiment
```

## 8. 변경한 주요 파일

- 아키텍처: `docs/architecture.md`
- 위협 모델: `docs/threat-model.md`
- 실험 결과 보고서: `docs/experiment-report.md`
- 블로그 초안: `docs/blog-draft.md`
- 수치 검증: `scripts/verify_documented_results.ps1`
- 재현 절차와 문서 색인: `README.md`
- 기술 결정: `docs/decisions.md`
- 진행 기록: `PROGRESS.md`
