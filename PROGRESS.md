# 작업 진행 상황

마지막 갱신: 2026-08-31 (Asia/Seoul)

## 1. 완료한 작업

### 1~6단계

- Java 21, Spring Boot 3.5.16, Gradle 8.12.1, PostgreSQL 16.9 기반 애플리케이션과 합성 DB를 구성했다.
- Flyway migration 계정과 최소 컬럼 SELECT 권한만 가진 `text2sql_ro` 실행 계정을 분리했다.
- Ollama/Fake/Scripted `SqlGenerator`, JSqlParser AST allowlist, `ValidatedSelect` Native Query 실행 경계를 구현했다.
- 결과 200행 상한, PostgreSQL statement timeout, 최대 3회 LLM 호출과 200ms/400ms 백오프를 구현했다.
- 악의적 SQL 20종, 자연어/Golden SQL 각 50건, 인덱스 전후 60개 plan, WireMock 재시도 30회 원본을 보존했다.
- 아키텍처, 위협 모델, 실험 보고서, 블로그 초안과 빈 환경 재현 절차를 작성했다.
- 6단계 PR #4가 merge commit `a3b984bd7a9ac5eef05a114126a555ab26c8247d`로 병합됐다.

### 7단계: 실제 Ollama 자연어 실측

- 사용자 승인 후 Windows Winget 공식 패키지로 Ollama 0.33.2를 사용자 영역에 설치했다.
- RAM 7.8GB 환경을 고려해 2.5GB `qwen3:4b-instruct` 4.0B Q4_K_M 모델을 선택하고 내려받았다.
- 모델 전체 digest, 크기, quantization, Apache-2.0 라이선스와 공식 출처를 `model-manifest.json`에 기록했다.
- 모델을 프로젝트와 같은 `/api/generate`, `stream=false`, temperature 0 방식으로 예열했다.
  - 예열 응답은 설명 없는 단일 SELECT였다.
  - `ollama ps`에서 `100% CPU`, context 4,096으로 확인됐다.
- 별도 Compose project와 새 PostgreSQL volume에서 자연어 50건을 실제 제품 파이프라인으로 실행했다.
- 결과를 `results/stage7-qwen3-4b-instruct-20260831`에 CSV/JSON으로 보존했다.
- 실제 정확도 23/50 (46.0%), 생성 성공 50/50, 첫 시도 검증 통과 49/50을 측정했다.
- 보고서, 블로그, 위협 모델, README와 기술 결정 문서를 실제 결과에 맞게 갱신했다.
- 문서 수치 검증 스크립트가 PENDING 역사 원본과 7단계 실측 원본을 모두 검증하도록 확장했다.

## 2. 실제 실행한 테스트와 결과

### 자연어 50건 실제 모델 실험

- 명령 환경:
  - `SQL_GENERATOR_PROVIDER=ollama`
  - `OLLAMA_MODEL=qwen3:4b-instruct`
  - `OLLAMA_VERSION=0.33.2`
  - `LLM_TEMPERATURE=0`
  - `OLLAMA_READ_TIMEOUT=120s`
- 최종 명령: `.\gradlew.bat --no-daemon nlExperiment`
- 결과: `BUILD SUCCESSFUL in 12m 14s`
- 측정 코드 SHA: `a3b984bd7a9ac5eef05a114126a555ab26c8247d`
- prompt SHA-256: `57ece3fc6553cda7c89d146ad647aea2c309abf8c094829e49f4f02f25e4970f`
- 정확도: 23/50 (46.0%)
- 생성 성공률: 50/50 (100.0%)
- 첫 시도 검증 통과율: 49/50 (98.0%)
- 평균/중앙 attempt: 1.02/1.0, 전체 생성 SQL 51개
- 실패: `RESULT_MISMATCH` 26건, `DB_TIMEOUT` 1건
- 난이도별:
  - EASY 11/23 (47.8%)
  - MEDIUM 8/19 (42.1%)
  - HARD 4/8 (50.0%)
- 비교 방식별:
  - SCALAR 11/28 (39.3%)
  - ORDERED 5/9 (55.6%)
  - UNORDERED 7/13 (53.8%)
- timeout으로 `elapsedMs=0`인 한 건을 제외한 49건 latency:
  - 평균 12,668.4ms, 중앙값 10,358ms
  - 최소 6,077ms, 최대 65,355ms, nearest-rank p95 22,498ms
- `NL-045` 첫 SQL은 금지 컬럼/별칭 해석 실패로 실행 전에 거절됐고 200ms 뒤 두 번째 SQL이 실행됐다.
- `NL-021`은 DB statement timeout으로 종료됐으며 정책대로 재시도하지 않았다.

### 7단계 최종 전체 테스트

- 명령:
  `$env:DOCKER_HOST='npipe:////./pipe/docker_engine'; .\gradlew.bat --no-daemon test --rerun-tasks`
- 결과: `BUILD SUCCESSFUL in 2m 5s`, 91개 통과, 실패 0, 오류 0, 건너뜀 0.
- 포함 확인:
  - 악의적 SQL fixture 20건에서 Native Query executor 호출 0회
  - `500→500→200` 3회 후 성공, `500→500→500` 3회 후 종료, `400` 즉시 종료
  - 허용 SELECT/JOIN/집계/서브쿼리 실행
  - 금지 테이블·컬럼·함수·UNION·다중 문장 차단
  - PostgreSQL read-only 권한, 행 상한, statement timeout, LIMIT 회귀

### 보존된 5단계 결과

- Ollama 미설치 자연어 원본은 당시 사실대로 `PENDING / OLLAMA_NOT_CONFIGURED` 상태를 유지한다.
- 인덱스 중앙 실행시간:
  - IDX-001: 3.5685ms → 0.3445ms
  - IDX-002: 4.1380ms → 0.0945ms
  - IDX-003: 5.0655ms → 1.7835ms
- 재시도 중앙값:
  - `500→500→200`: 680.0ms
  - `500→500→500`: 672.0ms
  - `400`: 12.0ms

## 3. 현재 정상 동작하는 기능

- 자연어 질문을 Ollama SQL 생성, AST 검증, read-only Native Query 순서로 처리한다.
- 실패 SQL은 실행되지 않고, 재생성 SQL도 동일한 gate를 다시 통과한다.
- 로컬 Ollama가 없으면 기본 provider `disabled`로 애플리케이션과 자동 테스트를 실행할 수 있다.
- Ollama가 준비된 환경에서는 동일 runner로 실제 50건 결과 집합 정확도를 측정할 수 있다.
- 문서의 자연어·인덱스·재시도·테스트 수치를 원본 CSV/JSON/XML로 역추적할 수 있다.

## 4. 미완료 작업

- 7단계 변경의 커밋, push, PR 생성과 사용자 검토·병합이 남았다.
- 자연어 정확도는 한 모델의 한 번 실행뿐이다. 반복 run의 분산과 다른 모델 비교는 측정하지 않았다.
- 인증·사용자별 권한·rate limit·운영 모니터링·TLS·다중 tenant 격리는 프로젝트 범위 밖이다.
- 모델 공급망 전체의 독립 감사나 모델 바이너리 안전성 검증은 수행하지 않았다.

## 5. 발생한 오류와 확인된 원인

- 첫 50건 실행은 대화 중단으로 약 29건 지점에서 Java 프로세스가 종료됐다. artifact가 생성되지
  않았음을 확인하고 해당 Compose project의 컨테이너·volume만 제거한 뒤 처음부터 다시 실행했다.
- 중단 후 Ollama와 Docker API가 잠시 응답하지 않았지만 설치 파일·모델은 보존돼 있었다. 두 로컬
  서비스의 API와 버전을 재확인한 뒤 새 DB volume으로 재실행했다.
- 최종 run 첫 질문은 CPU cold load 때문에 65,355ms가 걸렸다. 기본 30초로는 실패할 수 있어 실험
  환경에만 read timeout 120초를 주입했다.
- 실험 중 Hikari가 닫힌 연결을 교체한다는 경고가 반복됐고 `NL-021`은 단순 집계임에도 DB 2초
  timeout으로 실패했다. 같은 4코어 CPU에서 Ollama와 Docker가 경합한 정황은 있지만 정확한 원인은
  확정하지 못했으므로 실패를 삭제하거나 재실행으로 대체하지 않았다.
- `NL-045` 두 번째 SQL은 의미가 틀렸지만 고정 seed에서 Golden과 같은 0을 반환해 정답 처리됐다.
  결과 집합 비교가 의미 정확성을 완전히 보장하지 못하는 사례로 문서화했다.

## 6. 다음 대화에서 바로 시작할 작업

7단계 PR을 생성한 뒤에는 사용자 확인·병합 전까지 새 주요 작업을 시작하지 않는다.

병합 후 사용자가 추가 측정을 명시적으로 요청하는 경우에만 다음 후보를 진행한다.

1. 동일 모델 반복 run으로 정확도와 latency 분산 측정
2. 동일 자원 범위의 다른 Apache-2.0 소형 모델 비교
3. enum 값·날짜 경계 정보를 prompt에 보강하기 전후의 별도 실험

현재 46.0% run을 보기 좋게 만들기 위해 같은 run ID로 덮어쓰거나 실패만 재실행하지 않는다.

## 7. 실행 및 재현 명령어

PowerShell 공통 DB 설정:

```powershell
$env:DB_NAME = 'text2sql'
$env:DB_PORT = '5432'
$env:DB_MIGRATION_PASSWORD = '<local-migration-password>'
$env:APP_DB_PASSWORD = '<local-readonly-password>'
$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'
docker compose up -d --wait postgres
```

실제 모델 자연어 실험:

```powershell
winget install --id Ollama.Ollama --exact
ollama pull qwen3:4b-instruct
ollama list

$env:SQL_GENERATOR_PROVIDER = 'ollama'
$env:OLLAMA_BASE_URL = 'http://127.0.0.1:11434'
$env:OLLAMA_MODEL = 'qwen3:4b-instruct'
$env:OLLAMA_VERSION = '0.33.2'
$env:LLM_TEMPERATURE = '0'
$env:OLLAMA_CONNECT_TIMEOUT = '2s'
$env:OLLAMA_READ_TIMEOUT = '120s'
$env:EXPERIMENT_RUN_ID = 'my-qwen3-4b-run'
.\gradlew.bat nlExperiment
```

전체 테스트와 문서 수치 검증:

```powershell
.\gradlew.bat test --rerun-tasks
.\scripts\verify_documented_results.ps1
```

종료:

```powershell
docker compose down --volumes
ollama stop qwen3:4b-instruct
```

## 8. 변경한 주요 파일

- 실제 자연어 원본: `results/stage7-qwen3-4b-instruct-20260831/`
- 모델 식별 정보: `results/stage7-qwen3-4b-instruct-20260831/model-manifest.json`
- 실험 보고서: `docs/experiment-report.md`
- 블로그 초안: `docs/blog-draft.md`
- 위협 모델: `docs/threat-model.md`
- 수치 검증: `scripts/verify_documented_results.ps1`
- 설치·재현 절차: `README.md`
- 기술 결정: `docs/decisions.md`
- 진행 기록: `PROGRESS.md`
