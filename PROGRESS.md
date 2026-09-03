# 작업 진행 상황

마지막 갱신: 2026-09-03 (Asia/Seoul)

## 1. 완료한 작업

### 1~7단계

- Java 21, Spring Boot 3.5.16, Gradle 8.12.1, PostgreSQL 16.9 기반의 전체 Text2SQL 백엔드 흐름을 완성했다.
- Flyway migration 계정과 최소 컬럼 SELECT 권한만 가진 `text2sql_ro` 계정을 분리했다.
- Ollama/Fake/Scripted `SqlGenerator`, JSqlParser AST allowlist, `ValidatedSelect` Native Query 실행 경계를 구현했다.
- 최대 200행, statement timeout, 최대 3회 LLM 호출과 200ms/400ms 백오프를 구현했다.
- 악의적 SQL 20종, 자연어/Golden SQL 각 50건, 인덱스 plan 60개, WireMock 재시도 30회 원본을 보존했다.
- 실제 `qwen3:4b-instruct` 자연어 50건에서 결과 집합 정확도 23/50 (46.0%)을 측정하고 보고서와 블로그 초안에 반영했다.
- 7단계 PR #5는 merge commit `4fdba597a7d68ed19eae766344872206df5c9532`로 병합됐다.

### 8단계: 로컬 데모 UI와 간편 실행

- Spring Boot 정적 리소스로 자연어 입력, 예시 질문, 로딩 상태, 결과 표, 안전한 오류를 보여주는 단일 화면을 만들었다.
- UI는 기존 `POST /api/v1/text2sql/query`만 호출하며 서버 결과를 `textContent`로 렌더링한다.
- `scripts/start-local-demo.ps1`가 최초 실행에서 무작위 DB 비밀번호를 Git 제외 `.env`에 만들고 이후 자동으로 재사용하게 했다.
- 시작 스크립트가 Docker·Ollama·모델 준비 여부를 검사하고, 사용 중인 DB/HTTP 포트를 피한 실제 접속 주소를 출력하게 했다.
- 8GB 환경의 자원 경합을 줄이기 위해 `bootRun` 대신 `bootJar` 완료 후 `java -jar`만 실행하게 했다.
- `scripts/stop-local-demo.ps1`로 데모 Compose project만 종료하고, 명시적 `-RemoveData`에서만 volume을 삭제하게 했다.
- 외부 PowerShell의 `SQL_GENERATOR_PROVIDER=ollama`가 기본 API 통합 테스트 의미를 바꾸지 않도록 테스트 provider를 `disabled`로 고정했다.
- README, 프로젝트 명세, 기술 결정, 실험 보고서, 블로그 초안을 새 데모 흐름과 최종 테스트 수치에 맞게 갱신했다.
- 8단계 변경을 기능 단위 커밋으로 나누고 GitHub PR #6을 생성했다.

## 2. 실제 실행한 테스트와 결과

### 실제 사용자 흐름

- `scripts/start-local-demo.ps1`로 Docker PostgreSQL 16.9와 Spring Boot jar를 실행했다.
- 로컬 Ollama 0.33.2의 `qwen3:4b-instruct`를 사용했다.
- 브라우저에서 `전체 주문 수를 알려줘`를 입력했다.
- 결과: `total_orders=100`, LLM 호출 1회, 첫 측정 총 30,991ms.
- 화면 제목·폼·예시 버튼·로딩 상태·결과 표와 빈 입력 오류 패널을 실제 DOM/시각 상태로 확인했다.
- 검증 후 `Ctrl+C`로 애플리케이션을 종료하고 데모 Compose 컨테이너와 network만 제거했다. DB volume과 로컬 모델은 보존했다.

### 자동 테스트

- UI 계약 테스트:
  - 명령: `.\gradlew.bat --no-daemon test --tests dev.safetext2sql.ui.DemoUiContractTest --rerun-tasks`
  - 결과: `BUILD SUCCESSFUL in 1m 30s`, 3개 통과.
- 외부 provider 오염 회귀 통합 테스트:
  - 조건: 셸의 `SQL_GENERATOR_PROVIDER=ollama`, Docker Testcontainers 사용.
  - 명령: `.\gradlew.bat --no-daemon test --tests dev.safetext2sql.DatabaseIntegrationTest --rerun-tasks`
  - 결과: `BUILD SUCCESSFUL in 1m 19s`, 9개 통과.
- 최종 전체 테스트:
  - 조건: 셸의 `SQL_GENERATOR_PROVIDER=ollama`, `DOCKER_HOST=npipe:////./pipe/docker_engine`.
  - 명령: `.\gradlew.bat --no-daemon test --rerun-tasks`
  - 결과: `BUILD SUCCESSFUL in 1m 35s`, 94개 통과, 실패 0, 오류 0, 건너뜀 0.
  - 악의적 SQL 20건의 Native Query executor 호출 0회, 5xx/4xx 재시도 횟수, 허용 SELECT/JOIN/집계/서브쿼리, DB 최소 권한과 자원 제한을 포함한다.

## 3. 현재 정상 동작하는 기능

- 사용자는 환경 변수를 직접 입력하지 않고 시작 스크립트와 화면만으로 자연어 질의를 실행할 수 있다.
- 자연어 질문은 Ollama SQL 생성, AST fail-closed 검증, 읽기 전용 Native Query 순서로만 처리된다.
- UI를 우회해 API를 직접 호출해도 같은 서버 검증과 DB 권한이 적용된다.
- 앱·DB 포트가 사용 중이면 데모 전용 `.env`의 포트만 빈 값으로 변경하며 비밀번호는 노출하지 않는다.
- 자동 테스트는 개발자의 외부 Ollama provider 설정과 독립적으로 재현된다.
- 실제 실험 수치와 문서 수치는 보존된 CSV/JSON/XML로 역추적할 수 있다.

## 4. 미완료 작업

- 8단계 PR #6의 사용자 검토와 병합이 남았다.
- 인증·사용자별 권한·rate limit·TLS·다중 tenant 격리는 개인 로컬 실험 범위 밖이다.
- 자연어 정확도는 한 모델의 한 번 실행뿐이며 반복 run 분산이나 다른 모델 비교는 수행하지 않았다.
- UI는 포트폴리오 시연용 얇은 클라이언트이며 배포·로그인·질문 기록 저장 기능은 없다.

## 5. 발생한 오류와 확인된 원인

- 처음 데모를 `bootRun`으로 실행했을 때 Gradle JVM, 앱 JVM, Docker, Ollama가 동시에 떠 8GB Windows에서 시스템 자원 부족과 Ollama timeout이 발생했다. 실행 jar를 먼저 만들고 Gradle을 종료하는 방식으로 바꿨다.
- 55432와 18080 포트가 기존 프로세스에 점유돼 첫 실행이 충돌했다. IPv4/IPv6 LISTEN 소켓을 확인해 55433과 18081을 자동 선택하도록 했다.
- 첫 `java -jar` 시도는 `test-fixtures.jar`를 잘못 골라 manifest 오류로 종료됐다. `plain`과 `test-fixtures` jar를 모두 제외해 Spring Boot jar만 선택하도록 수정했다.
- 사용자의 셸에 `SQL_GENERATOR_PROVIDER=ollama`가 남으면 “Ollama 없이 시작” 테스트가 502 대신 200을 받아 실패했다. 해당 테스트 context에 `disabled` provider를 명시해 외부 상태를 격리했다.
- HTML `required` 기본 검증은 빈 질문에서 브라우저별 UI를 띄우고 이전 결과를 남겼다. `novalidate`로 일관된 자체 오류 패널을 사용하되 서버의 질문 검증은 그대로 유지했다.

## 6. 다음 대화에서 바로 시작할 작업

8단계 PR #6을 생성했다. 사용자 확인·병합 전까지 새 주요 작업을 시작하지 않는다.

PR 병합 후 프로젝트 필수 범위는 완료 상태다. 사용자가 명시적으로 요청할 때만 아래 선택 작업을 진행한다.

1. 블로그 게시용 실제 UI 스크린샷과 글 편집
2. 동일 모델 반복 run 또는 다른 무료 로컬 모델 비교
3. 인증/rate limit을 추가한 별도 확장 실험

## 7. 실행 및 재현 명령어

가장 간단한 실제 데모 실행:

```powershell
ollama pull qwen3:4b-instruct  # 모델이 없을 때 한 번만
.\scripts\start-local-demo.ps1
```

스크립트가 출력한 브라우저 주소를 연다. 종료할 때 앱 창에서 `Ctrl+C`를 누른 뒤 DB를 내린다.

```powershell
.\scripts\stop-local-demo.ps1
```

데모 DB volume까지 삭제하려는 경우에만 실행한다.

```powershell
.\scripts\stop-local-demo.ps1 -RemoveData
```

전체 테스트와 문서 수치 검증:

```powershell
$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'
.\gradlew.bat --no-daemon test --rerun-tasks
.\scripts\verify_documented_results.ps1
```

## 8. 변경한 주요 파일

- 데모 화면: `src/main/resources/static/index.html`
- 화면 스타일: `src/main/resources/static/styles.css`
- API 호출·안전한 렌더링: `src/main/resources/static/app.js`
- 간편 시작: `scripts/start-local-demo.ps1`
- 안전한 종료: `scripts/stop-local-demo.ps1`
- UI 계약 테스트: `src/test/java/dev/safetext2sql/ui/DemoUiContractTest.java`
- 외부 환경 격리: `src/test/java/dev/safetext2sql/DatabaseIntegrationTest.java`
- 재현 절차: `README.md`
- UI 범위: `PROJECT_SPEC.md`
- 선택 근거: `docs/decisions.md`
- 블로그·수치 문서: `docs/blog-draft.md`, `docs/experiment-report.md`
