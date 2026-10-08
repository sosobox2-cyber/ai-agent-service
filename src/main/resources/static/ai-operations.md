# API 구매옵션 추론 내부 설계/운영 문서

현재 저장소의 Java 17 / Spring Boot 3.5.16 / Spring AI 1.1.8 구현 기준입니다. API 연동 계약은 별도 `/api-reference.html`에서 확인하세요. 이 문서의 설정은 서버 설정이며 요청 JSON의 필드가 아닙니다. 상품·신뢰도·비용·사용량 예제는 설명용입니다.

## 1. 처리 흐름과 책임

```text
PurchaseOptionController
  → 요청 DTO Bean Validation
  → PurchaseOptionInferenceService.infer()
  → RequestValidator / InputHashService
  → testMode=true: MockOptionInferenceService
  → testMode=false: PurchaseOptionAiService
      → PromptSelector / PromptProvider
      → model.call() / parse() / AiInferenceValidator
      → 필요 시 FULL 한 번 / 최종 review()
  → ResultValidator / 최종 승인 판단 / items 조립
  → InferenceResponse
```

요청은 상품 객체 자체지만 내부 User Prompt에는 `{"product": request}`로 감쌉니다. 클라이언트에 이 내부 포맷을 요구하지 않습니다. DB나 결과 캐시 저장은 구현되어 있지 않습니다.

`InputHashService`는 `input-v8` 기준으로 상품 ID·상품명·내부 카테고리·정보고시·구성 원문·허용 이름·단위 설정·단품 정보를 SHA-256으로 해시합니다. 허용 이름·단위 설정 및 단품을 정렬한 정규화 객체를 사용합니다. `productCompositionText`의 생략/null과 빈 문자열은 다른 해시를 만들 수 있습니다. 이 해시는 OpenAI request ID, idempotency key 또는 캐시 적중 표시가 아닙니다.

## 2. LIGHT / FULL 선택

`PurchaseOptionPromptProvider`의 `app.ai.prompt-mode`는 `AUTO` 또는 `FULL`만 허용합니다. 기본 AUTO이며 `AI_PROMPT_MODE=FULL`이면 모든 요청을 FULL로 시작합니다. LIGHT 강제 설정은 없습니다.

AUTO의 `PurchaseOptionPromptSelector`는 다음 순서로 선택합니다.

1. 단위 설정이 하나라도 있거나 허용 이름·단품이 비어 있으면 FULL입니다. 빈 목록은 일반 HTTP 요청에서는 먼저 검증에서 차단됩니다.
2. 옵션명이 null/blank이거나 공백 제거 후 `단품`, `단일상품`이면 FULL입니다.
3. 모든 허용 이름이 아래 단순 이름 집합에 포함될 때만 LIGHT, 하나라도 아니면 FULL입니다.

```text
색상, 컬러, color, 사이즈, size, 패션의류/잡화 사이즈, 핏
```

영문 이름은 Locale.ROOT로 소문자 변환 후 비교하지만 앞뒤 공백을 제거하지 않습니다. 카테고리, 상품명, 정보고시·구성 원문의 내용, 단품 개수로 분석 난도를 판별하지 않습니다. 따라서 '모호한 요청은 모두 FULL'이라는 일반화는 실제 선택 기준보다 넓습니다. 구성 원문만 추가해도 무조건 FULL이 되지는 않습니다.

## 3. LIGHT → FULL Failover

최초 모드는 한 번 선택합니다. 첫 결과가 `AiInferenceValidator` 검증을 통과하면 반환하고, 실패한 LIGHT에 한해 재추론 설정이 허용하면 같은 상품 데이터로 FULL을 한 번 호출합니다. 반복문·재귀나 추가 보정 대화는 없습니다. 처음부터 FULL인 요청에는 재추론하지 않습니다.

| 시작·결과 | 프롬프트 분석 호출 수 |
| --- | --- |
| 정상 LIGHT | 1 |
| 검증 실패 LIGHT, 재추론 활성화 | 최대 2 |
| 처음부터 FULL | 1 |
| LIGHT에서 API 장애 | 1, FULL 전환 없음 |
| 재추론 비활성화 | 1 |

FULL 전환은 System Prompt만 바꾸고 동일한 User Prompt·상품 JSON·JSON Schema를 사용합니다. 이전 AI 응답을 다음 요청에 덧붙이지 않습니다. FULL도 실패하면 `review()`가 `certain=false`로 전환하고 코드·검증 사유·AI 사유를 2,000자 이내로 묶습니다. 원래 매핑은 검토용으로 남길 수 있지만 최종 `items`·`optionMappings`는 비웁니다.

API 통신 오류, timeout, 429, 외부 5xx는 프롬프트 검증 실패로 처리하지 않습니다. `AiClientConfig`의 Spring AI RetryTemplate은 `maxAttempts=1`이므로 별도의 통신 자동 재시도도 없습니다.

### 재추론 설정

```yaml
purchase-option:
  ai:
    retry:
      enabled: true
      max-retries: 1
      confidence-threshold-enabled: false
      confidence-threshold: 0.7
```

`enabled=false` 또는 `max-retries=0`이면 FULL 전환을 하지 않습니다. `max-retries`는 0 또는 1만 허용하며 잘못된 설정은 빈 생성 시 실패합니다. 재추론 신뢰도는 0~1의 유한한 수입니다.

### 내부 검증 / 재추론 사유 코드

| 코드 | 조건 |
| --- | --- |
| `CERTAIN_FALSE` | AI의 certain=false |
| `MISSING_OPTION_MAPPING` | 입력 optionId에 매핑 없음 |
| `UNKNOWN_OPTION_ID` | 요청에 없는 optionId |
| `INVALID_PURCHASE_OPTION` | 허용 이름 밖의 targetPurchaseOptionName |
| `DUPLICATE_MAPPING` | 같은 optionId·구매옵션명 중복 |
| `EMPTY_VALUE` | null 매핑 또는 필수 ID·구매옵션명·값이 null/blank |
| `INVALID_RESPONSE` | 응답 파싱·완료 검증 또는 ResultValidator의 구조·단위·최종 조합 검사 실패 |
| `LOW_CONFIDENCE` | 재추론 신뢰도 기준을 켰을 때 최저 confidence가 기준 미달 |

한 결과에 여러 코드가 남을 수 있습니다. `retryReasons`를 단일 원인이라고 가정하지 마세요. FULL 호출 행의 retryReasons는 FULL 검증 사유가 아니라 최초 LIGHT의 전환 사유입니다.

## 4. 신뢰도와 최종 승인

재추론 검증과 최종 승인은 별개입니다. 기본 재추론 신뢰도 기준은 꺼져 있으므로 구조가 정상인 LIGHT의 confidence가 0.5여도 추가 FULL 호출 없이 최종 검토 필요로 반환합니다. 재추론 신뢰도를 켜면 전체·유효한 개별 매핑의 최솟값을 0.7과 비교하며 같은 값은 통과합니다.

최종 승인은 `app.inference.confidence-threshold=0.80` 기준입니다. `certain=true`, 전체 및 개별 confidence 유효·기준 이상, 제안 검증과 조립 결과 검증 통과가 필요합니다. 최상위 response.confidence는 유효한 전체 및 개별 신뢰도의 최솟값입니다. 숫자는 AI 자체 평가이며 통계적 정답 확률이 아닙니다.

`PurchaseOptionInferenceService.assess()`의 판단 순서는 다음과 같습니다.

1. 제안 구조 오류 목록과 최저 confidence를 계산합니다.
2. certain=false이며 전체 confidence·사유가 유효하면 REVIEW_REQUIRED / AI_UNCERTAIN입니다. 구조 오류가 함께 있어도 해당 판단이 우선할 수 있습니다.
3. 나머지 구조 오류가 있으면 RESULT_VALIDATION_FAILED입니다.
4. 확실성·최종 신뢰도 기준에 미달하면 REVIEW_REQUIRED / LOW_CONFIDENCE입니다.
5. items를 조립해 검증하고 실패하면 RESULT_VALIDATION_FAILED, 통과하면 ACCEPTED입니다.

실제 어댑터의 검증 실패는 대부분 review()를 통해 2번으로 들어갑니다. RESULT_VALIDATION_FAILED 분기는 테스트 모드 또는 서비스의 방어 경로에도 존재하므로 오류 코드 문서에서 삭제하지 않습니다.

호출 로그의 SUCCESS는 AiInferenceValidator와 신뢰도 기준 통과입니다. 최종 API 응답은 서비스가 다시 평가합니다. 적용 여부는 로그 상태가 아니라 응답 success·autoApplyCandidate를 기준으로 판단합니다.

## 5. 프롬프트와 JSON Schema

| **리소스** | **역할** |
| :--- | :--- |
| `prompts/coupang-purchase-option-system-light.txt` | 일반 색상·사이즈·핏 원문 추출 |
| `prompts/coupang-purchase-option-system.txt` | 구성·수량·용량·중량·단위 등 상세 판단 |
| `prompts/coupang-purchase-option-user.txt` | 공통 지시문 및 상품 JSON 전달 |

일반 색상·사이즈·핏은 `optionName1`의 원문을 기준으로 추출하도록 지시합니다. 상품정보고시는 판단 근거로 활용하지만, 원본 옵션에 없는 값을 임의로 생성하거나 새로운 조합을 만들지 않도록 제한합니다.

`productCompositionText`는 본품·사은품·묶음 구성·수량·용량 등을 판단하는 보조 정보로 사용합니다. 사은품·증정품은 본품 계산에서 제외하고, 입력 정보가 충돌하거나 명확하지 않으면 `certain=false`로 판단하도록 지시합니다.

서버는 요청마다 JSON Schema를 생성합니다. 요청의 `optionId`와 `allowedPurchaseOptions`가 각각 허용 가능한 단품 ID와 구매옵션명으로 제한되며, 응답은 `strict=true`의 JSON Schema 형식을 사용합니다.

### AI 메시지 구성

AI에는 다음 두 개의 메시지를 전달합니다.

```text
SystemMessage: 선택된 LIGHT 또는 FULL System Prompt
UserMessage: 공통 User Prompt + {"product": 상품 요청 JSON}
```

상품 JSON은 클라이언트의 HTTP 요청 원문을 그대로 사용하는 것이 아니라, 서버가 처리한 상품 요청 데이터를 `product` 객체로 감싸 전달합니다.

아래는 실제 HTTP 요청 전문이 아니라 **AI에 전달되는 주요 구조를 설명하기 위한 예시**입니다.

```json
{
  "model": "gpt-4.1-mini",
  "temperature": 0,
  "max_tokens": 4096,
  "messages": [
    {
      "role": "system",
      "content": "선택된 LIGHT 또는 FULL System Prompt"
    },
    {
      "role": "user",
      "content": "공통 User Prompt\n{\"product\": 상품 요청 JSON}"
    }
  ],
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "purchase_option_mapping",
      "strict": true,
      "schema": "요청별로 생성된 JSON Schema"
    }
  }
}
```

모델명·temperature·max_tokens는 서버 설정으로 관리하며 현재 기본값은 `gpt-4.1-mini`, `0`, `4096`입니다.

### LIGHT → FULL 재추론

LIGHT 결과가 검증을 통과하지 못해 FULL 재추론이 실행되면 **System Prompt만 FULL로 변경하고 나머지 입력 구조는 그대로 사용합니다.**

| **구분** | **최초 LIGHT** | **재추론 FULL** |
| :--- | :--- | :--- |
| System Prompt | LIGHT | FULL |
| User Prompt | 동일 | 동일 |
| 상품 JSON | 동일 | 동일 |
| JSON Schema | 동일 | 동일 |
| 이전 AI 응답 | - | 전달하지 않음 |
| 별도 보정 Prompt | - | 추가하지 않음 |
| 서버 검증 | 동일 | 동일 |

즉, FULL 재추론은 **이전 LIGHT 응답을 수정시키는 방식이 아니라 동일한 상품 데이터를 더 상세한 FULL System Prompt로 처음부터 다시 분석하는 새로운 AI 호출**입니다.

LIGHT와 FULL은 동일한 응답 구조와 서버 검증 과정을 사용하며, 재추론 여부와 관계없이 최종 결과는 동일한 승인·조립·검증 절차를 거칩니다.

## 6. 서버 검증의 범위와 한계

서버는 **요청 데이터 검증 → AI 응답 검증 → 최종 결과 검증**의 단계로 결과의 유효성을 확인합니다.

### 요청 데이터 검증

`RequestValidator`는 다음 항목을 검사합니다.

- 허용 구매옵션명의 중복 여부
- 단품 ID의 중복 여부
- 단위 설정의 중복 여부
- 단위 설정 대상이 허용 구매옵션에 포함되어 있는지
- 단위 값의 앞뒤 공백 등 설정 형식

필수값, 문자열 길이, 단품 개수, null 여부 등 기본적인 요청 형식은 Controller의 Bean Validation에서 먼저 검사합니다.

### AI 응답 검증

`ResultValidator.validateProposal()`은 AI가 반환한 결과에 대해 다음 항목을 검사합니다.

- `certain`, `confidence`, `reason` 형식
- 필수 ID·구매옵션명·값의 존재 여부
- 요청에 존재하는 `optionId`인지 여부
- 허용된 구매옵션명인지 여부
- 동일 단품·구매옵션의 중복 매핑 여부
- 단품별 최소 하나 이상의 매핑 존재 여부
- 단위가 설정된 값의 숫자·단위 형식

AI가 `allowedPurchaseOptions`에 포함된 모든 구매옵션을 반드시 반환해야 하는 것은 아닙니다.

### 최종 결과 검증

검증을 통과한 AI 제안은 `assemble()`에서 단품별 최종 구매옵션 Map으로 구성합니다.

이후 `validateItems()`에서 다음 항목을 다시 확인합니다.

- 요청 단품 수와 결과 단품 수의 일치 여부
- 단품 ID의 누락·중복 여부
- 허용된 구매옵션명인지 여부
- AI 제안과 최종 조립된 값의 일치 여부
- 서로 다른 단품 간 최종 구매옵션 조합의 중복 여부

따라서 AI가 올바른 JSON 형식으로 응답했더라도 최종 결과 검증을 통과하지 못하면 자동 적용 가능한 결과로 승인되지 않습니다.

### 서버 검증의 한계

현재 서버 검증은 주로 **응답 구조와 값의 형식·일관성**을 확인합니다.

다음 내용은 서버에서 직접 검증하지 않습니다.

- 추출된 값이 실제 상품 원문에 존재하는지
- `evidenceText`가 실제 입력 원문과 일치하는지
- 상품정보고시와 상품 구성 정보가 서로 충돌하는지
- `calculation`의 계산 과정이 수학적으로 정확한지

따라서 AI가 제공하는 근거와 계산 정보는 **판단 과정의 확인 및 검토를 위한 정보**이며, 서버가 해당 내용의 정확성까지 보증하는 것은 아닙니다.

### 단위 값 검증

단위가 설정된 구매옵션은 `숫자 + 허용 단위` 형식인지 검사합니다.

정수·소수·천 단위 쉼표는 허용하지만 음수·공백·지수 표기는 허용하지 않습니다.

서버는 AI가 반환한 값의 형식을 검증할 뿐, 단위나 수치를 자동으로 변환하거나 재계산하지 않습니다.

## 7. OpenAI 호출과 장애 처리

Spring AI OpenAiChatModel / Chat Completions를 사용합니다. 현재 기본 모델은 gpt-4.1-mini, temperature=0, max-tokens=4096이며 서버 설정으로 변경 가능합니다. 연결 timeout은 5s, 읽기 timeout은 30s입니다. 전체 HTTP 요청의 고정 SLA나 두 호출 합산 제한을 보장하는 값은 아닙니다.

API 키가 없으면 OpenAI를 호출하는 대신 AI_NOT_CONFIGURED/503을 던지는 ChatModel을 제공합니다. 테스트 모드는 이 모델을 호출하지 않습니다. Logger의 API_ERROR는 실제 원격 요청 전에 이 모델이 실패한 경우에도 남을 수 있어 로그 행 수가 반드시 과금된 원격 요청 수와 같지는 않습니다.

| 상황 | 외부 응답 |
| --- | --- |
| 외부 429 | AI_RATE_LIMIT / 429 |
| 외부 408·504 또는 감지된 SocketTimeoutException·HttpTimeoutException | AI_TIMEOUT / 504 |
| 그 밖의 외부 오류 | AI_UPSTREAM_ERROR / 502 |
| 입력 DTO·업무 검증 또는 매개변수 변환 실패 | INVALID_REQUEST / 400 |
| 요청 JSON 해석 실패 | INVALID_JSON / 400 |
| 미처리 예외 | INTERNAL_ERROR / 500 |

SafeErrorHandler는 외부 오류 본문·헤더를 읽어 에러에 포함하지 않습니다. GlobalExceptionHandler는 예외 응답의 상품 ID·해시를 null, 출처를 NONE, 사용량을 빈 목록으로 반환합니다. 다른 미처리 MVC 예외도 포괄 예외 처리기에 들어갈 수 있습니다. 메서드·미디어 타입 오류 등의 HTTP 계약을 추가하려면 별도 구현·테스트가 필요하며 이번 작업에서는 변경하지 않았습니다.

## 8. AI Usage와 예상 비용

Usage는 ChatResponse.metadata.usage의 실제 입력·출력·전체 값을 읽습니다. native OpenAiApi.Usage의 promptTokensDetails가 있으면 cachedTokens를 읽습니다. EmptyUsage·없음 또는 캐시 상세 없음은 null이며 임의로 0을 만들거나 프롬프트 길이로 추정하지 않습니다. 모델명이 없거나 정해진 안전한 모델명 패턴 밖이면 unknown입니다.

응답 aiUsage는 요청 지역 목록으로 누적합니다. 첫 LIGHT와 추가 FULL은 같은 inferenceId와 각각 attempt=1/2를 갖습니다. 예외가 발생하면 GlobalExceptionHandler가 빈 사용량 목록을 반환하므로 앞선 과금 호출은 운영 로그로 확인해야 합니다. 최종 파싱·검증 실패라도 수신된 실제 usage는 보존합니다.

모델 가격은 application.yml의 `app.ai.usage-pricing.models` 맵으로 관리합니다. input-per-million·cached-per-million·output-per-million을 사용하며 캐시·입력·출력이 없거나 단가 미설정·음수·캐시>입력인 경우 비용은 null입니다.

```text
((inputTokens - cachedTokens) × inputPerMillion
 + cachedTokens × cachedPerMillion
 + outputTokens × outputPerMillion) / 1,000,000
```

기본 YAML에는 gpt-4.1-mini와 gpt-4.1-mini-2025-04-14의 기존 단가가 있습니다. 이것은 최신 시장 가격을 검증한 결과가 아닙니다. 운영 담당자가 적용 단가를 갱신하세요. AI_PRICE_INPUT_PER_MILLION·AI_PRICE_CACHED_PER_MILLION·AI_PRICE_OUTPUT_PER_MILLION으로 두 모델의 기본 단가를 덮어쓸 수 있습니다. 세금·환율·실제 청구 확정 비용은 포함하지 않습니다.

## 9. JSONL 호출 이력과 보관

전용 `AiPurchaseOptionUsageLog` record로 한 API 호출 시도당 한 JSON 객체를 한 줄에 기록합니다. pretty print 없이 null 필드를 포함하며 UTF-8 Jackson 직렬화를 사용합니다. DB를 사용하지 않습니다.

기본 활성 파일은 프로세스 작업 디렉터리 기준 `logs/ai-usage.jsonl`입니다. 서버 시간대의 날짜별 Rolling, `logs/ai-usage.YYYY-MM-DD.jsonl.gz` 압축, 기본 30일·압축 보관 파일 합계 1GB입니다. 활성 파일에는 이 1GB 상한이 적용되지 않습니다. 시작·Rolling 시 정리하며 날짜 변경 후 첫 이벤트에서 회전합니다.

전용 Logger는 `AI_PURCHASE_OPTION_USAGE.<인스턴스 UUID>`, Appender는 AI_PURCHASE_OPTION_USAGE이며 additive=false입니다. 기존 Logback XML은 없으며 Java에서 RollingFileAppender와 TimeBasedRollingPolicy를 구성합니다. encoder는 `%msg%n`입니다.

### JSONL 필드

| 필드 | 의미 |
| --- | --- |
| timestamp | 기록 시각, ISO-8601 offset 포함 |
| inferenceId / goodsId | 상품 요청 UUID / 상품 ID |
| coupangCategoryId | 현재 요청 DTO에 없으므로 항상 null |
| promptMode / model | 이번 호출 모드 / 응답 모델명 또는 unknown |
| inputTokens / cachedTokens / outputTokens / totalTokens | 실제 응답 usage, 없으면 null |
| certain / confidence / mappingCount | 파싱한 제안의 판단·전체 신뢰도·매핑 수, 없으면 null |
| elapsedMs | model.call() 직전부터 응답 또는 예외까지의 밀리초, 파싱·검증·저장 제외 |
| status | SUCCESS / VALIDATION_ERROR / REVIEW_REQUIRED / API_ERROR |
| initialPromptMode / finalPromptMode | 최초 모드 / 현재 또는 예정 마지막 모드 |
| retryCount | 최초 0, 추가 FULL 1 |
| retryReason / retryReasons | 첫 사유 / 사유 목록 |
| validationPassed | AiInferenceValidator 통과 여부, 검증 못 하면 null |
| estimatedCostUsd | 실제 사용량과 외부 단가로 계산한 추정치 |

**현재 DTO에는 validationErrors와 minimumMappingConfidence가 없습니다.** 기존 일부 문서의 해당 필드 설명을 바로잡았습니다. FULL 행에는 최초 LIGHT의 전환 사유를 유지하므로 FULL 자체의 상세 검증 사유를 JSONL에서 모두 복원할 수 없습니다. 응답 reason·validationErrors는 서버 최종 판단 자료이며 JSONL 필드가 아닙니다.

| status | 의미 |
| --- | --- |
| SUCCESS | 제안 검증·최종 confidence 기준 통과 |
| VALIDATION_ERROR | LIGHT 검증 실패, FULL 전환 예정 |
| REVIEW_REQUIRED | 더 호출하지 않고 불확실·검증 실패·신뢰도 미달 |
| API_ERROR | model.call() 예외, 받은 usage와 제안 없음 |

finalPromptMode=FULL인 LIGHT 행은 예정 상태입니다. 프로세스 중단이나 로그 유실로 FULL 행이 없을 수 있습니다. 같은 inferenceId의 마지막 행과 retryCount를 함께 확인하세요. UUID는 OpenAI request ID가 아닙니다.

### 설명용 JSONL 한 줄

```json
{"timestamp":"2026-10-09T10:00:00.123+09:00","inferenceId":"5d6d0000-0000-4000-8000-000000000001","goodsId":"sample-clothing","coupangCategoryId":null,"promptMode":"LIGHT","model":"gpt-4.1-mini","inputTokens":1000,"cachedTokens":800,"outputTokens":200,"totalTokens":1200,"certain":true,"confidence":0.95,"mappingCount":2,"elapsedMs":823,"status":"SUCCESS","initialPromptMode":"LIGHT","finalPromptMode":"LIGHT","retryCount":0,"retryReason":null,"retryReasons":[],"validationPassed":true,"estimatedCostUsd":0.00048}
```

API 키·Authorization·System/User Prompt 전체·요청/응답 전체·상품 JSON·정보고시·AI reason·검증 메시지 원문을 기록하지 않습니다. 식별자는 Jackson으로 이스케이프합니다. 로그 초기화·파일 쓰기·직렬화 오류는 application logger에 WARN으로 알리고 추론을 계속합니다. 유실 로그의 복구·재전송 기능은 없습니다.

## 10. 운영 설정·집계·배포 주의

| 설정 | 환경 변수 / 기본값 |
| --- | --- |
| app.ai.prompt-mode | AI_PROMPT_MODE / AUTO |
| app.ai.usage-log-enabled | AI_USAGE_LOG_ENABLED / false, dev 프로필 true |
| app.ai.usage-jsonl-enabled | AI_USAGE_JSONL_ENABLED / true |
| app.ai.usage-jsonl-path | AI_USAGE_JSONL_PATH / logs/ai-usage.jsonl |
| app.ai.usage-jsonl-max-history | AI_USAGE_JSONL_MAX_HISTORY / 30, 구현에서 최소 1로 보정 |
| app.ai.usage-jsonl-total-size-cap | AI_USAGE_JSONL_TOTAL_SIZE_CAP / 1GB |
| spring.ai.openai.api-key | OPENAI_API_KEY / 빈 값 |
| spring.ai.openai.chat.options.model | OPENAI_MODEL / gpt-4.1-mini |
| server.address / server.port | SERVER_ADDRESS / 127.0.0.1, PORT / 8081 |

개발 프로필은 application-dev.yml에서 콘솔 메트릭만 기본 활성화합니다. 파일 기록과 독립적입니다. 여러 인스턴스가 같은 파일에 동시에 쓰지 않도록 인스턴스별 경로를 설정하세요.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\measure-ai-failover.ps1 -Path .\logs\ai-usage.jsonl
```

집계 스크립트는 현재 camelCase와 이전 snake_case 로그를 지원합니다. 호출 수와 LIGHT/FULL 비율, 알려진 토큰·confidence·elapsedMs 평균, 불확실 호출 수, inferenceId별 요청·LIGHT 시작 성공·FULL 전환·최종 검토·API 오류·미완료·비용을 계산합니다. null은 확인 불가로 분리하고 0은 포함합니다. 전환 비율 분모는 LIGHT 시작 건수이며 추가 비용은 retryCount=1 행입니다. 여러 날짜는 gzip 해제 후 필요한 JSONL을 합쳐 분석합니다.

현재 애플리케이션에는 인증·호출량 제한·CORS 허용 설정이 없습니다. 기본 로컬 바인딩은 127.0.0.1, Compose 호스트 포트도 기본 로컬이지만 HOST_ADDRESS로 외부 공개할 수 있습니다. Docker 이미지와 Vercel 이미지 내부는 0.0.0.0입니다. 외부 공개 전 인증·접근 제한·비용 제한이 필요합니다. 외부 플랫폼의 보호 여부는 저장소에서 확인되지 않습니다.

Docker 이미지의 /app는 root가 만들고 실행자는 app 사용자이며 logs 폴더·쓰기 권한을 별도로 마련하지 않습니다. 현재 Compose에는 로그 볼륨도 없습니다. 파일 생성이 실패할 수 있고 컨테이너 교체 시 로컬 로그가 유실될 수 있으므로 실제 배포 환경의 쓰기 가능 경로·영속 저장 정책을 확인하세요. JSONL의 30일 설정만으로 배포 환경의 영속 보관을 보장하지 않습니다. 이번 작업은 이를 문서 TODO로만 기록하며 배포 설정을 변경하지 않습니다.

## 11. 관련 자료와 검증 근거

정본 API 계약: `src/main/resources/static/api-reference.md`. 화면 상단의 API 명세서 링크에서 읽을 수 있습니다.

기존 상세 자료는 삭제하지 않고 유지합니다.

- `docs/project-flow.md`: 클래스별 전체 처리 흐름과 검증
- `docs/ai-inference-failover.md`: 전환 조건·설정·집계
- `docs/ai-call-history.md`: 로그 필드·Appender·Rolling·실패 처리
- `docs/ai-token-optimization.md`: 프롬프트 비교·오프라인 추정·비용 비교 실행 방법 및 과거 측정 결과

과거 문서에 있는 모델 가격·테스트 수·프롬프트 토큰 추정은 당시 기록이며 현재 가격·실제 호출 사용량·정확도를 보장하지 않습니다. 유료 비교는 OPENAI_RUN_COST_COMPARISON이 필요한 별도 테스트이며 이번 명세 검토에서는 수행하지 않습니다.

이번 대조 기준은 Controller·요청/응답 DTO·RequestValidator·ResultValidator·PurchaseOptionInferenceService·PurchaseOptionAiService·PromptSelector/Provider·AiInferenceValidator·AiClientConfig·AiUsageLogger·AiUsagePricing·application.yml·배포 파일입니다. API·Failover·PromptMode·Usage·요청 검증 관련 기존 테스트로 현재 계약을 확인합니다. 실제 배포 서버의 가용성·런타임 설정·유료 모델 정확도는 확인 범위 밖입니다.
