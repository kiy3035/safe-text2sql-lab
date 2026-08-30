# 실험 결과 보고서

## 1. 보고서 범위

이 문서는 저장소에 보존된 자동 테스트와 5단계 결과 파일만 요약한다. 모든 데이터는 합성
커머스 seed다. 자연어 50건의 실제 모델 정확도는 Ollama와 모델이 없어 측정하지 않았으며
`PENDING`이다.

측정 코드 SHA는 `5c94665238b49b41a214ac8247c0aa59929f74b6`이다. 이후 문서화 커밋은
측정 원본을 변경하지 않는다.

## 2. 실행 환경

| 항목 | 값 |
| --- | --- |
| 실행 시각 | 2026-08-30 UTC, run별 metadata에 나노초 단위 기록 |
| OS | Windows 11 10.0 amd64 |
| CPU | Intel64 Family 6 Model 140 Stepping 1, GenuineIntel |
| RAM | 8,379,490,304 bytes |
| Java | Eclipse Temurin 21.0.8 |
| Docker Engine | 24.0.7 |
| PostgreSQL | 16.9 Alpine |
| Ollama | `NOT_INSTALLED` |
| 로컬 LLM 모델 | `PENDING` |

환경 근거:

- [자연어 metadata](../results/stage5-nl-pending-20260830/metadata.json)
- [인덱스 metadata](../results/stage5-index-20260830/metadata.json)
- [재시도 metadata](../results/stage5-retry-20260830/metadata.json)

## 3. 자동 테스트와 보안 fixture

최종 전체 테스트 명령은 다음과 같다.

```powershell
$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'
.\gradlew.bat --no-daemon test --rerun-tasks
```

결과는 91개 통과, 실패 0, 오류 0, 건너뜀 0이었다.

악의적 SQL fixture는 정확히 20건이다. 각 항목에서 거절 사유뿐 아니라 Native Query executor 호출
횟수가 0인지 확인했다. 20건 모두 차단되고 실행기 호출은 모두 0회였다. 범주는 DDL/DML/DCL,
stacked statement, UNION 계열, 시스템 catalog, 금지 테이블·컬럼, 위험 함수, 데이터 변경 CTE와
주석 우회를 포함한다.

PostgreSQL 통합 테스트에서는 다음을 확인했다.

- Flyway migration 3개와 deterministic smoke seed 적재
- 실제 datasource 사용자 `text2sql_ro`
- JDBC read-only hint를 꺼도 INSERT가 SQLSTATE `42501`로 거부됨
- `customers.email`과 `admin_users` 조회가 SQLSTATE `42501`로 거부됨
- 검증된 조회의 10행 응답 상한과 `truncated=true`
- 100ms 테스트 statement timeout과 SQLSTATE `57014` 분류
- 최상위 LIMIT 5 정상 실행, LIMIT 20은 테스트 실행 상한 10행으로 제한
- Golden SQL 50건의 AST 검증과 read-only DB 실행

fixture 원본은 [malicious-sql.json](../src/test/resources/security/malicious-sql.json)이다.

## 4. 자연어 50건 정확도

### 4.1 준비된 실험

질문과 Golden SQL을 별도 JSONL 각 50건으로 고정했다. 질문 분포는 다음과 같다.

| 난이도 | SCALAR | ORDERED | UNORDERED | 합계 |
| --- | ---: | ---: | ---: | ---: |
| EASY | 17 | 4 | 2 | 23 |
| MEDIUM | 9 | 2 | 8 | 19 |
| HARD | 2 | 3 | 3 | 8 |
| 합계 | 28 | 9 | 13 | 50 |

생성 SQL 문자열을 Golden 문자열과 비교하지 않는다. 고정 seed에서 양쪽 SQL을 실행하고 숫자
정규화, 순서, 중복 행 정책에 맞춰 결과 집합을 비교한다. Golden SQL 50건은 모두 AST 게이트와
read-only Native Query 실행을 통과했다.

### 4.2 실제 상태

| 지표 | 결과 |
| --- | --- |
| 상태 | `PENDING` |
| 사유 | `OLLAMA_NOT_CONFIGURED` |
| 질문 수 | 50 |
| 정확도 | 측정 안 함 (`null`) |
| 생성 성공률 | 측정 안 함 (`null`) |
| 첫 시도 검증 통과율 | 측정 안 함 (`null`) |
| 평균·중앙 attempt | 측정 안 함 (`null`) |

Ollama 프로그램과 모델을 설치하지 않았기 때문에 Fake/Scripted 결과를 실제 정확도로 대체하지
않았다. 따라서 “50건 중 몇 건을 맞혔다”는 결론은 이 보고서에 없다.

원본:

- [질문 50건](../experiments/nl-questions.jsonl)
- [Golden SQL 50건](../experiments/golden-sql.jsonl)
- [PENDING 요약](../results/stage5-nl-pending-20260830/nl-summary.json)
- [자연어 CSV](../results/stage5-nl-pending-20260830/nl-results.csv)
- [자연어 JSON](../results/stage5-nl-pending-20260830/nl-results.json)

## 5. 인덱스 전후 실행계획

### 5.1 방법

- 데이터: orders/order_items/payments 각 50,100행
- 후보 인덱스: `analytics.orders(status, ordered_at)`
- 조회: 코드에 고정된 3개 SELECT
- 조건별 1회 warm-up 후 10회 측정
- 명령: `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`
- BEFORE 측정 후 인덱스 생성·ANALYZE, AFTER 측정 후 finally에서 인덱스 제거
- EXPLAIN SELECT는 `text2sql_ro`, DDL·ANALYZE는 migration 계정 사용

### 5.2 중앙값

| 조회 | 조건 | scan | planner rows | actual rows | result rows | filter 제거 rows | hit blocks | 실행 중앙값(ms) |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| IDX-001 | BEFORE | Seq Scan | 1,101 | 1,116 | 1 | 48,984 | 432 | 3.5685 |
| IDX-001 | AFTER | Bitmap Heap Scan | 1,118 | 1,116 | 1 | 0 | 48 | 0.3445 |
| IDX-002 | BEFORE | Seq Scan | 6,550 | 6,588 | 100 | 43,512 | 432 | 4.1380 |
| IDX-002 | AFTER | Index Scan | 6,559 | 100 | 100 | 0 | 8 | 0.0945 |
| IDX-003 | BEFORE | Seq Scan | 6,579 | 6,552 | 1 | 43,548 | 432 | 5.0655 |
| IDX-003 | AFTER | Bitmap Heap Scan | 6,572 | 6,552 | 1 | 0 | 262 | 1.7835 |

중앙값 비율을 단순 계산하면 AFTER는 BEFORE보다 IDX-001 약 10.36배, IDX-002 약 43.79배,
IDX-003 약 2.84배 짧았다. 이는 이 로컬 실행의 중앙값 비율이며 일반적인 성능 향상 보장이 아니다.

### 5.3 분산

| 조회 | 조건 | 최소(ms) | 최대(ms) | p95(ms) |
| --- | --- | ---: | ---: | ---: |
| IDX-001 | BEFORE | 2.692 | 4.407 | 4.407 |
| IDX-001 | AFTER | 0.292 | 0.508 | 0.508 |
| IDX-002 | BEFORE | 3.926 | 5.084 | 5.084 |
| IDX-002 | AFTER | 0.083 | 0.160 | 0.160 |
| IDX-003 | BEFORE | 4.039 | 6.056 | 6.056 |
| IDX-003 | AFTER | 1.670 | 3.433 | 3.433 |

표본이 조건별 10개이므로 nearest-rank 방식의 p95는 이번 결과에서 최대값과 같다. shared read
blocks 중앙값은 모든 조건에서 0이었다. 직전 migration과 반복 실행으로 데이터가 OS/PostgreSQL
캐시에 있었을 가능성이 크므로 디스크 I/O 개선 수치로 해석하면 안 된다.

원본:

- [인덱스 요약 CSV](../results/stage5-index-20260830/index-summary.csv)
- [전체 측정 JSON](../results/stage5-index-20260830/index-measurements.json)
- `results/stage5-index-20260830/plans/`의 실행계획 JSON 60개

## 6. LLM HTTP 재시도

### 6.1 방법

실제 `OllamaSqlGenerator`, `Text2SqlQueryService`, `ThreadSleeper`를 사용하고 HTTP 서버만 WireMock으로
대체했다. 기본 백오프 200ms/400ms를 실제로 기다리며 각 시나리오를 10회 실행했다.

### 6.2 결과

| 시나리오 | 반복 | HTTP 호출 | 결과 | 전체 중앙값(ms) | 최소 | 최대/p95 | 요청 간격 중앙값(ms) |
| --- | ---: | ---: | --- | ---: | ---: | ---: | --- |
| 500→500→200 | 10 | 매번 3 | SUCCESS 10회 | 680.0 | 670 | 1497/1497 | 229 / 426 |
| 500→500→500 | 10 | 매번 3 | LLM_UNAVAILABLE 10회 | 672.0 | 666 | 680/680 | 229 / 427 |
| 400 | 10 | 매번 1 | LLM_REQUEST_REJECTED 10회 | 12.0 | 11 | 25/25 | 재시도 없음 |

요청 간격은 HTTP 처리 오버헤드까지 포함하므로 설정값 200ms/400ms보다 길다. 첫
`500→500→200` 반복에는 JVM/HTTP 초기화 비용이 포함되어 최대와 p95가 1497ms였다. 이 값을
이상치로 삭제하지 않고 원본에 남겼다.

이 측정은 재시도 정책과 HTTP 호출 수를 보여준다. 실제 Ollama 추론 latency나 실제 모델 장애를
측정한 결과는 아니다.

원본:

- [재시도 요약 CSV](../results/stage5-retry-20260830/retry-summary.csv)
- [재시도 원본 JSON](../results/stage5-retry-20260830/retry-measurements.json)

## 7. 해석과 제한

1. 현재 fixture 20건에서 검증 실패 SQL의 실행기 호출은 0회였지만, 모든 SQL 문법에 대한 형식적
   안전성 증명은 아니다.
2. 인덱스 실험은 한 노트북·한 Docker 실행·고정 합성 분포의 결과다. 캐시, 실행 순서, Docker 자원,
   데이터 분포가 달라지면 계획과 시간이 달라질 수 있다.
3. WireMock 결과는 retry orchestration의 측정이며 실제 모델 추론 성능이 아니다.
4. 자연어 정확도는 아직 측정하지 않았다. Ollama와 지정 모델이 준비되기 전까지 계속
   `PENDING`으로 남긴다.
5. 이 실험은 로컬 개인 프로젝트이며 인증, rate limit, 운영 모니터링, 다중 tenant 격리를 포함하지
   않는다.

방어 범위의 상세 내용은 [위협 모델](threat-model.md)을 참고한다.
