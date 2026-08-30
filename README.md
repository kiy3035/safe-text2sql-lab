# Safe Text2SQL Lab

합성 커머스 데이터를 사용해 `자연어 → SQL 생성 → 검증 → 읽기 전용 실행`을 단계별로 재현하는 개인 실험 프로젝트다. 현재는 2단계까지 구현되어 Spring Boot/PostgreSQL 기반과 로컬 Ollama용 SQL 생성 adapter를 제공한다. SQL 검증과 실행은 아직 구현되지 않았다.

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
- 생성된 SQL은 신뢰할 수 없는 문자열일 뿐이다. 3단계 AST 검증 전에는 실행할 수 없다.
- `400`과 `500`, 전송 오류, 응답 형식 오류를 구분하지만 재시도는 4단계에서 단일 attempt budget으로 구현한다.

WireMock 기반 adapter 테스트와 Scripted/Fake 테스트 대역도 전체 테스트에 포함된다.
