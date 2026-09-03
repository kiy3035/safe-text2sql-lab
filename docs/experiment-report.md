# 실험 결과 보고서

## 1. 보고서 범위

이 문서는 저장소에 보존된 자동 테스트, 5단계 인덱스·재시도 결과와 7단계 실제 로컬 모델
결과를 요약한다. 모든 데이터는 합성 커머스 seed다. 5단계에는 Ollama가 없어 자연어 정확도를
`PENDING`으로 남겼고, 7단계에는 사용자의 설치 승인 후 같은 50건을 실제 모델로 측정했다.

5단계 인덱스·재시도 측정 코드 SHA는 `5c94665238b49b41a214ac8247c0aa59929f74b6`이고,
7단계 자연어 측정 코드 SHA는 `a3b984bd7a9ac5eef05a114126a555ab26c8247d`이다. 이후 문서화
커밋은 두 측정 원본을 변경하지 않는다.

## 2. 실행 환경

| 항목 | 값 |
| --- | --- |
| 실행 시각 | 자연어 실측 2026-08-31T11:15:56Z, 나머지는 run별 metadata에 기록 |
| OS | Windows 11 10.0 amd64 |
| CPU | Intel64 Family 6 Model 140 Stepping 1, GenuineIntel |
| RAM | 8,379,490,304 bytes |
| Java | Eclipse Temurin 21.0.8 |
| Docker Engine | 24.0.7 |
| PostgreSQL | 16.9 Alpine |
| Ollama | 0.33.2 |
| 로컬 LLM 모델 | `qwen3:4b-instruct`, 4.0B, Q4_K_M, 2,497,293,803 bytes |
| 모델 digest | `0edcdef34593eac1aa2be9c7d06c432dcf81945adca5eca2f27662c18f168ba0` |
| 모델 라이선스 | Apache-2.0 |
| temperature | 0.0 |
| Ollama read timeout | 자연어 실험에서 120초 |

환경 근거:

- [7단계 자연어 metadata](../results/stage7-qwen3-4b-instruct-20260831/metadata.json)
- [모델 manifest](../results/stage7-qwen3-4b-instruct-20260831/model-manifest.json)
- [자연어 metadata](../results/stage5-nl-pending-20260830/metadata.json)
- [인덱스 metadata](../results/stage5-index-20260830/metadata.json)
- [재시도 metadata](../results/stage5-retry-20260830/metadata.json)

자연어 실측에서는 `ollama ps`가 모델 실행 장치를 `100% CPU`, context를 4,096으로 표시했다.
Windows 첫 예열 요청은 13,907ms였고, 실험 프로세스를 다시 시작한 뒤 첫 질문은 cold load를 포함해
65,355ms가 걸렸다. 이 값들은 저사양 CPU 로컬 환경이라는 결과 해석의 일부다.

## 3. 자동 테스트와 보안 fixture

최종 전체 테스트 명령은 다음과 같다.

```powershell
$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'
.\gradlew.bat --no-daemon test --rerun-tasks
```

8단계 UI 계약 테스트 3개를 추가한 최종 결과는 94개 통과, 실패 0, 오류 0, 건너뜀 0이었다.

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

### 4.2 7단계 실제 결과

| 지표 | 결과 |
| --- | --- |
| 상태 | `COMPLETED` |
| 질문 수 | 50 |
| 전체 정확도 | 23/50 (46.0%) |
| 생성 성공률 | 50/50 (100.0%) |
| 첫 시도 검증 통과율 | 49/50 (98.0%) |
| 평균·중앙 attempt | 1.02 / 1.0 |
| 실패 | 결과 불일치 26건, DB timeout 1건 |

난이도별 결과:

| 난이도 | 정답 | 정확도 |
| --- | ---: | ---: |
| EASY | 11/23 | 47.8% |
| MEDIUM | 8/19 | 42.1% |
| HARD | 4/8 | 50.0% |

비교 방식별 결과:

| 비교 방식 | 정답 | 정확도 |
| --- | ---: | ---: |
| SCALAR | 11/28 | 39.3% |
| ORDERED | 5/9 | 55.6% |
| UNORDERED | 7/13 | 53.8% |

DB timeout 1건은 실패 경로에서 `elapsedMs=0`으로 기록되므로 latency 집계에서 제외했다. 나머지
49건의 LLM 생성·검증·실행 전체 시간은 평균 12,668.4ms, 중앙값 10,358ms, 최소 6,077ms,
최대 65,355ms, nearest-rank p95 22,498ms였다. 첫 질문의 cold load와 CPU·Docker 경합이 포함된
한 번의 로컬 실행 결과이므로 모델 자체의 일반적인 처리량으로 해석하면 안 된다.

### 4.3 실패와 우연한 정답

결과 불일치 26건에서는 다음 패턴을 확인했다.

- 모델이 `PAID`, `CANCELLED`, `SEOUL`, `GOLD` 같은 합성 데이터 값을 소문자나 한국어로 추측했다.
- 질문이 요구한 projection보다 컬럼을 더 넣거나 빼서 행의 모양이 달라졌다.
- “7월 1일”을 하루가 아니라 7월 전체로, “이후” 경계를 초과가 아닌 포함에 가깝게 해석했다.
- 동률 정렬 기준을 추가하지 않거나 오름차순 질문에 내림차순을 사용했다.
- 시간대별 집계에서 `EXTRACT(hour ...)` 대신 날짜까지 포함하는 `DATE_TRUNC('hour', ...)`를 사용했다.

`NL-021`은 상태별 100행 집계 SQL이었지만 CPU에서 모델을 실행하는 동안 Docker DB가 기본 2초
statement timeout을 넘겨 `DB_TIMEOUT`으로 끝났다. 정책상 timeout은 재시도하지 않았으며 결과도
실패로 유지했다.

`NL-045`는 첫 SQL의 해석 불가능한 별칭을 AST gate가 차단했고 200ms 뒤 두 번째 SQL이 생성됐다.
두 번째 SQL은 `category_id NOT IN (product_id ...)`라 질문의 의미와 맞지 않지만, 고정 seed에서는
Golden과 똑같이 0을 반환해 정답으로 집계됐다. 따라서 46.0%는 **이 seed의 결과 집합 일치율**이지
SQL 의미 정확도의 완전한 측정값이 아니다.

5단계 원본 `stage5-nl-pending-20260830`은 `PENDING / OLLAMA_NOT_CONFIGURED` 상태를 그대로
보존했다. 7단계 실측이 과거에 실행되지 않은 측정을 실행한 것처럼 덮어쓰지 않기 위해서다.

원본:

- [질문 50건](../experiments/nl-questions.jsonl)
- [Golden SQL 50건](../experiments/golden-sql.jsonl)
- [실측 요약](../results/stage7-qwen3-4b-instruct-20260831/nl-summary.json)
- [실측 CSV](../results/stage7-qwen3-4b-instruct-20260831/nl-results.csv)
- [실측 JSON](../results/stage7-qwen3-4b-instruct-20260831/nl-results.json)
- [과거 PENDING 요약](../results/stage5-nl-pending-20260830/nl-summary.json)

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
4. 자연어 정확도 46.0%는 한 모델·temperature 0·한 번의 고정 seed 실행 결과다. 다른 모델이나
   반복 run의 분산을 나타내지 않으며, 우연히 같은 결과를 낸 의미 오류도 정답에 포함될 수 있다.
5. LLM과 PostgreSQL을 같은 4코어 CPU 환경에서 실행해 cold load와 자원 경합이 latency 및 timeout에
   영향을 줬다. 49건 latency를 모델 단독 benchmark로 일반화하면 안 된다.
6. 이 실험은 로컬 개인 프로젝트이며 인증, rate limit, 운영 모니터링, 다중 tenant 격리를 포함하지
   않는다.

방어 범위의 상세 내용은 [위협 모델](threat-model.md)을 참고한다.
