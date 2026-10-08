# AI 호출 이력 JSONL

Java 17, Spring AI 1.1.8의 `OpenAiChatModel` / Chat Completions를 사용한다. `PurchaseOptionAiService.attempt()`에서 실제 `model.call()`마다 한 줄을 기록한다. DB, 프롬프트, JSON Schema, 선택 기준, API 호출 옵션과 추론 결과는 변경하지 않는다. 기존 LIGHT → FULL 재추론은 유지하며 새 재추론 기능은 추가하지 않는다. 테스트 모드는 OpenAI 호출이 없어 이력을 만들지 않는다.

## 저장과 보관

기존 로그 정책의 활성 파일 경로 `logs/ai-usage.jsonl`을 유지한다. 프로젝트 루트에서 실행하면 절대 경로는 `C:\Users\sosob\OneDrive\문서\ai-agent-service\logs\ai-usage.jsonl`이다. 상대 경로는 서버 프로세스의 작업 디렉터리를 기준으로 한다.

- 현재 파일: `logs/ai-usage.jsonl`
- 날짜별 보관 파일: `logs/ai-usage.2026-10-08.jsonl.gz`
- 서버 시간대 기준 일별 Rolling. 날짜가 바뀐 뒤 첫 로그 이벤트에서 이전 파일을 gzip으로 압축한다.
- 기본 30일 보관, 압축 보관 파일 합계 1GB 상한. 활성 파일은 합계 상한에 포함하지 않는다.
- 시작 시와 Rolling 시 보관 파일 정리. 로그가 발생하지 않는 동안은 서버가 주기적 삭제 작업을 실행하지 않는다.

`AI_PURCHASE_OPTION_USAGE` 전용 Logback RollingFileAppender를 Java에서 구성한다. Logger 이름은 `AI_PURCHASE_OPTION_USAGE.<인스턴스 UUID>`이며 `additive=false`로 일반 application logger와 분리한다. 기존 Logback XML이 없는 프로젝트이고, 파일 생성 실패가 Boot 로깅 초기화 오류로 서버 시작을 막지 않도록 서비스 빈에서 구성한다. UTF-8 `%msg%n`, Jackson의 pretty print 비활성화, null 필드 포함을 강제한다. 기존 선택적 콘솔 메트릭은 `AI_USAGE_LOG_ENABLED`로 별도 제어한다.

| 설정 | 환경 변수 | 기본값 |
| --- | --- | --- |
| `app.ai.usage-jsonl-enabled` | `AI_USAGE_JSONL_ENABLED` | `true` |
| `app.ai.usage-jsonl-path` | `AI_USAGE_JSONL_PATH` | `logs/ai-usage.jsonl` |
| `app.ai.usage-jsonl-max-history` | `AI_USAGE_JSONL_MAX_HISTORY` | `30` |
| `app.ai.usage-jsonl-total-size-cap` | `AI_USAGE_JSONL_TOTAL_SIZE_CAP` | `1GB` |

한 프로세스에 한 출력 파일을 사용한다. 여러 서버 인스턴스가 동시에 같은 파일에 기록하지 않도록 인스턴스별 경로를 설정한다.

## 필드

`AiPurchaseOptionUsageLog` record가 JSONL 구조를 고정한다. API 응답의 기존 `aiUsage` 필드명은 변경하지 않고, 파일의 토큰 필드는 camelCase로 기록한다. 기존 파일의 snake_case 행과 새 행이 섞여도 집계 스크립트는 모두 지원한다.

| 필드 | 의미 |
| --- | --- |
| `timestamp` | 기록 시각, 서버 시간대의 ISO-8601 offset 포함 |
| `goodsId` | 요청 상품 ID |
| `coupangCategoryId` | 현재 요청 DTO에서 제거된 항목이므로 항상 `null`. 로깅을 위해 API 필드를 복원하지 않음 |
| `inferenceId` | 한 상품 추론 요청의 UUID. OpenAI request ID가 아님. 기존 재추론의 두 호출에서 동일 |
| `promptMode` / `model` | 실제 LIGHT/FULL 모드, 응답 모델명. 응답을 얻지 못하면 모델은 `unknown` |
| `inputTokens` | `response.metadata.usage.getPromptTokens()` |
| `outputTokens` | `response.metadata.usage.getCompletionTokens()` |
| `totalTokens` | `response.metadata.usage.getTotalTokens()` |
| `cachedTokens` | native `OpenAiApi.Usage.promptTokensDetails().cachedTokens()` |
| `certain` / `confidence` | 파싱한 AI 결과. confidence가 유효한 숫자가 아니면 `null` |
| `mappingCount` | 파싱한 매핑 개수, 제안 결과가 없으면 `null` |
| `elapsedMs` | API 호출 직전부터 응답 도착 또는 예외 발생까지의 시간. `System.nanoTime()` 기준 밀리초. 직렬화·파싱·검증·로그 저장 시간 제외 |
| `status` | 아래 상태 중 해당 호출의 상태 |
| `initialPromptMode` / `finalPromptMode` | 기존 재추론 흐름의 시작/마지막 예정 모드 |
| `retryCount` | 최초 호출 0, 기존 FULL 재추론 1 |
| `retryReason` / `retryReasons` | 재추론/검증 사유 코드. 자유 텍스트 오류 메시지는 기록하지 않음 |
| `validationPassed` | 구조 검증 통과 여부, 검증하지 못했으면 `null` |
| `estimatedCostUsd` | 외부 단가 설정과 실제 usage로 계산한 추정 비용. 가격 또는 usage 누락 시 `null` |

토큰 Usage가 없거나 `EmptyUsage`이면 수치를 `null`로 기록한다. 캐시 상세가 없을 때에도 `null`이며 실제 API의 0과 구별한다. 문자열 길이·토크나이저로 Usage를 계산하지 않는다. 테스트 모드 호출은 집계 대상에 포함되지 않는다.

| 상태 | 현재 흐름 |
| --- | --- |
| `SUCCESS` | AI 결과 검증과 적용 confidence 기준 통과 |
| `VALIDATION_ERROR` | LIGHT 결과가 기존 검증에 실패해 FULL로 넘어가는 호출 |
| `REVIEW_REQUIRED` | 최종 결과 불확실, 검증 실패 또는 적용 confidence 기준 미달 |
| `API_ERROR` | API 호출 예외. Usage와 AI 결과는 `null` |

`finalPromptMode=FULL`인 LIGHT 행은 FULL 전환 예정이라는 뜻이다. 프로세스가 중단되면 FULL 행이 없을 수 있다. 분석 시 동일 `inferenceId`의 마지막 행으로 완료 여부를 확인한다.

## 비용과 실패 처리

기존 Java 코드의 고정 가격은 `app.ai.usage-pricing.models`로 이동했다. 모델 키별 `input-per-million`, `cached-per-million`, `output-per-million`을 관리한다. 기존 기본 단가를 유지했으며 새 시장 가격을 조회해 확정한 값은 아니다. 운영자는 실제 적용 단가를 갱신해야 한다. `AI_PRICE_INPUT_PER_MILLION`, `AI_PRICE_CACHED_PER_MILLION`, `AI_PRICE_OUTPUT_PER_MILLION`으로 기존 두 모델 단가를 변경할 수 있다. 모델 추가는 YAML 맵에 항목을 추가한다.

```text
((inputTokens - cachedTokens) * inputPerMillion
 + cachedTokens * cachedPerMillion
 + outputTokens * outputPerMillion) / 1,000,000
```

캐시 토큰은 입력 토큰의 부분집합이다. API 키, Authorization, System/User Prompt, 요청/응답 본문, 상품 JSON, 정보고시, AI reason과 검증 오류 원문은 기록하지 않는다. 식별자 내 줄바꿈은 Jackson이 이스케이프해 한 이벤트가 여러 JSONL 행으로 분리되지 않는다.

파일 생성·쓰기·JSON 직렬화 실패는 기존 application logger에 WARN으로 기록한다. 예외 메시지와 스택은 출력하지 않는다. 정상 AI 결과와 기존 API 오류 처리는 그대로 진행하며 로그 오류로 재추론하거나 비즈니스 예외를 발생시키지 않는다. 장애 후 유실한 이력을 복구하거나 재전송하는 기능은 없다.

## 검증과 집계

`mvn test`에서 LIGHT/FULL 로그, 실제 SDK Usage, 캐시 null/0, API 오류, JSON 직렬화 및 파일 장애의 격리, 일별 gzip Rolling, 30일 설정, 외부 가격 바인딩과 기존 추론 테스트를 확인한다. 유료 실제 API는 호출하지 않고 MockRestServiceServer와 ChatResponse fixture로 검증한다.

실제로 테스트 Appender가 출력한 한 줄은 `target/ai-usage-log-example.jsonl`에 저장된다. 이는 모의 API Usage를 사용하는 테스트 결과이며 운영 API 호출 기록은 아니다.

```powershell
.\scripts\measure-ai-failover.ps1 -Path .\logs\ai-usage.jsonl
```

총 호출, LIGHT/FULL 호출과 비율, 알려진 토큰/캐시/출력/신뢰도/응답시간의 평균, 불확실 호출 수, 기존 재추론 지표와 비용을 집계한다. `null`은 평균에서 제외하고 실제 0은 포함한다. 여러 날짜를 집계할 때는 gzip을 해제하고 필요한 JSONL을 합친 파일을 지정한다.

향후 재추론 기능을 확장할 때는 기존 `inferenceId`, `initialPromptMode`, `finalPromptMode`, `retryCount`, `retryReason`/`retryReasons`를 활용한다. 각 API 호출별 행을 유지해 상품 요청 수와 유료 호출 수를 구분한다.

## 이번 작업의 변경 파일

- 추가: `src/main/java/com/cware/ai/dto/AiPurchaseOptionUsageLog.java`, `src/main/java/com/cware/ai/config/AiUsagePricing.java`, `src/test/java/com/cware/ai/AiPurchaseOptionUsageLogTest.java`, `docs/ai-call-history.md`
- 구현·설정: `src/main/java/com/cware/ai/inference/AiUsageLogger.java`, `src/main/java/com/cware/ai/inference/PurchaseOptionAiService.java`, `src/main/resources/application.yml`
- 테스트: `src/test/java/com/cware/ai/Fixtures.java`, `src/test/java/com/cware/ai/AiAdapterTest.java`, `src/test/java/com/cware/ai/AiFailoverTest.java`, `src/test/java/com/cware/ai/AiUsageLoggerTest.java`, `src/test/java/com/cware/ai/OpenAiTransportTest.java`, `src/test/java/com/cware/ai/PurchaseOptionApiTest.java`
- 집계·안내: `scripts/measure-ai-failover.ps1`, `README.md`, `docs/ai-inference-failover.md`, `docs/ai-token-optimization.md`, `docs/project-flow.md`

검증 결과: 테스트 169개 중 168개 성공, 유료 실제 API 조건이 필요한 1개 건너뜀. 생성된 샘플 JSONL을 집계하여 LIGHT 1회, 입력 1,000, 캐시 800, 출력 200, 응답시간 25ms를 확인했다. 집계 스크립트의 JSONL null 값은 평균에서 제외된다. 응답시간은 테스트 실행에 따라 달라진다.
