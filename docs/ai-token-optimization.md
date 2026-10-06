# 구매옵션 AI 호출 분석과 토큰 최적화

## 변경 전 호출 구조

| 확인 항목 | 분석 결과 |
|---|---|
| System Prompt | `PurchaseOptionAiService` 생성자에서 `prompts/coupang-purchase-option-system.txt`를 UTF-8로 읽어 모든 요청에 사용 |
| User Prompt | 같은 생성자에서 `prompts/coupang-purchase-option-user.txt`를 읽고 호출 시 `\n`과 `{"product": request}` JSON을 덧붙임 |
| API | Spring AI 1.1.8 `OpenAiChatModel`, 비스트리밍 `POST https://api.openai.com/v1/chat/completions` |
| messages | 최초 호출은 `system`, `user`. 확실 판정이지만 단품 ID가 누락되면 기존 메시지 뒤에 이전 응답 `assistant`, 누락 보정 지시 `user`를 붙여 한 번 재요청 |
| model | `OPENAI_MODEL` 환경 변수. 미설정 시 `gpt-4.1-mini`. 실제 응답 모델은 새 usage 로그에서 확인 가능 |
| 주요 옵션 | `temperature=0`, `max_tokens=4096`, 연결 제한 5초, 읽기 제한 30초. HTTP 오류 자동 재시도 없음 |
| Structured Output | `response_format.type=json_schema`, `strict=true`, 이름 `purchase_option_mapping`. 요청의 허용 구매옵션명과 원본 단품 ID를 enum에 포함 |
| 응답 검증 | 기존 JSON 파싱, 전체 단품 처리, 허용 이름, 중복 매핑, 단위 설정, 신뢰도 및 최종 단품 조합 검증 |
| usage 수집 | 응답 메타데이터에 존재하지만 이전 서비스에서는 읽거나 저장·로그하지 않음 |
| input_tokens | Chat Completions의 `usage.prompt_tokens`. Spring AI `getPromptTokens()`로 접근 |
| output_tokens | `usage.completion_tokens`. Spring AI `getCompletionTokens()`로 접근 |
| total_tokens | `usage.total_tokens`. Spring AI `getTotalTokens()`로 접근 |
| cached_tokens | `usage.prompt_tokens_details.cached_tokens`. Spring AI `getNativeUsage()`의 `OpenAiApi.Usage`에 보존됨. 모의 HTTP 응답을 통한 실제 Spring AI 역직렬화 테스트로 확인 |

API에 전송되는 구조는 아래와 같다. JSON Schema는 메시지 본문과 별도로 전달되며 입력 토큰에 영향을 줄 수 있다.

```json
{
  "model": "gpt-4.1-mini",
  "temperature": 0,
  "max_tokens": 4096,
  "messages": [
    { "role": "system", "content": "선택된 System Prompt 원문" },
    { "role": "user", "content": "User Prompt 원문\n{\"product\": 요청 JSON}" }
  ],
  "response_format": {
    "type": "json_schema",
    "json_schema": { "name": "purchase_option_mapping", "strict": true, "schema": "기존 요청별 스키마 객체" }
  }
}
```

## 구현과 정확도 보호

기존 FULL 파일을 수정하거나 이동하지 않고 `coupang-purchase-option-system-light.txt`를 별도로 추가했다. `PurchaseOptionPromptSelector`가 모드를 고르고 `PurchaseOptionPromptProvider`가 원문을 제공한다. User Prompt, 상품 JSON, 모델, temperature, 출력 한도, JSON Schema, 보정 재요청과 서버 검증은 유지한다.

LIGHT는 모든 허용 이름이 `색상`, `컬러`, `COLOR`, `사이즈`, `SIZE`, `패션의류/잡화 사이즈`, `핏` 중 하나인 요청에만 적용한다. 영문 대소문자는 무시한다. 단위 설정이 있거나 원본 옵션명이 `단품`/`단일상품`이면 FULL을 사용한다. 계산형·알 수 없는 옵션명이 하나라도 섞이면 FULL이다. 부분 일치로 분류하지 않으므로 `화면크기(cm)`, `스타일` 등도 보수적으로 FULL을 사용한다.

LIGHT는 전체 단품 순회, 허용 이름, 원문 문자열 추출, 임의 값 생성 금지, 조합별 중복 금지, 동일 ID의 여러 매핑, 불확실 판정, confidence/reason 및 기존 스키마 규칙을 유지한다. 색상·사이즈 예시를 포함하고 계산형 옵션의 연산·포장 구성 규칙만 제외한다.

| 변경 파일 | 역할 |
|---|---|
| `PurchaseOptionPromptMode.java` | LIGHT/FULL 모드 |
| `PurchaseOptionPromptSelector.java` | 보수적인 요청별 선택 |
| `PurchaseOptionPromptProvider.java` | 기존 FULL/별도 LIGHT 로드, FULL 강제 설정 |
| `coupang-purchase-option-system-light.txt` | 일반 문자열 추출 규칙 |
| `PurchaseOptionAiService.java` | 선택 프롬프트 사용, 실제 호출별 usage 기록 |
| `AiUsageLogger.java` | 민감정보 없는 메트릭·비용 추정 |
| `application.yml`, `application-dev.yml` | 모드 및 개발환경 로깅 설정 |
| `PromptRoutingTest`, `AiUsageLoggerTest`, `OpenAiTransportTest` | 라우팅·네 단품·usage·로그 검증 |
| `PromptCostComparisonTest` | 오프라인 추정과 선택 실행 실제 비교 |

`AI_PROMPT_MODE=AUTO`가 기본이며 `AI_PROMPT_MODE=FULL`로 기존 상세 프롬프트만 사용하도록 즉시 되돌릴 수 있다. 계산 요청까지 LIGHT를 강제하는 설정은 제공하지 않는다. 응답의 프롬프트 버전은 `coupang-option-v18`이다.

## 개발환경 usage 로그

`SPRING_PROFILES_ACTIVE=dev`로 실행하면 usage 로그를 기본 활성화한다. 환경 변수 `AI_USAGE_LOG_ENABLED=true/false`로 별도 제어할 수 있다. 기본 실행에서는 비활성화다.

```text
ai_usage call_id=<무작위 ID> mode=LIGHT attempt=1 model=gpt-4.1-mini input_tokens=1000 cached_tokens=800 output_tokens=200 total_tokens=1200 estimated_cost_usd=0.00048
```

위 수치는 설명용이며 실측 결과가 아니다. 동일 `call_id`의 `attempt=2`는 단품 누락 보정 호출이다. 요청 한 건의 사용량과 비용은 모든 차수의 값을 합산해야 한다. 정상 종료가 아닌 응답도 반환된 usage는 파싱 전에 기록한다. 응답을 받지 못한 API 오류는 usage를 알 수 없다.

API 키, Authorization 헤더, 상품/정보고시, 요청·응답 JSON과 전체 메타데이터를 로그에 출력하지 않는다. usage나 cached_tokens가 없으면 `null`로 남기며 0으로 가정하지 않는다. 별도 로그 파일이나 DB 저장은 하지 않는다.

## 테스트 페이지에서 확인

최종 단품 결과 아래의 **AI 호출·토큰 사용량**을 펼치면 현재 요청의 호출별 모드·모델·입력·캐시·출력·총 토큰·예상 USD 비용을 볼 수 있다. 보정 재요청도 별도 행으로 표시하고 모든 호출을 합산한다. 일부 수치를 API가 제공하지 않으면 해당 합계도 `확인 불가`이며 0으로 취급하지 않는다. 캐시 입력은 이미 입력 토큰에 포함되므로 총 토큰에 다시 더하지 않는다.

응답 JSON의 `aiUsage`에 현재 요청의 안전한 메트릭만 추가한다. 콘솔 로깅 활성화 여부와 관계없이 표시되며 요청별 지역 목록으로 수집하므로 다른 요청의 사용량은 섞이지 않는다. 기존 추론 필드와 판정은 유지한다. 테스트 모드는 실제 AI 호출이 없어 `aiUsage=[]`이고 화면에 별도로 설명한다. 서버 검증에 실패한 실제 AI 응답도 받은 usage는 유지한다. API 오류 등으로 일반 추론 응답을 받지 못하면 화면에서 사용량을 확인하지 못할 수 있다.

UI 합계·재요청·누락된 메트릭·0 값·테스트 모드 검증은 `node src/test/js/ai-usage.test.cjs`로 실행한다.

## 비용 및 Prompt Caching

2026-10-06 확인한 [공식 GPT-4.1 mini 가격](https://developers.openai.com/api/docs/models/gpt-4.1-mini)은 Standard 텍스트 100만 토큰당 입력 $0.40, 캐시 입력 $0.10, 출력 $1.60이다. 예상 USD 비용은 다음 식으로 계산한다.

```text
((input_tokens - cached_tokens) × 0.40
 + cached_tokens × 0.10
 + output_tokens × 1.60) / 1,000,000
```

cached_tokens는 전체 입력에 포함된 부분집합이므로 중복 과금으로 계산하지 않는다. 알려진 `gpt-4.1-mini`/`gpt-4.1-mini-2025-04-14`만 비용을 추정하며 다른 모델이나 캐시 정보 누락은 비용 `null`로 남긴다. 가격 변경 시 `AiUsageLogger`의 단가도 갱신해야 한다. 이 값은 청구서, 세금, 통화 환율을 포함하지 않는다.

[공식 Prompt Caching 문서](https://developers.openai.com/api/docs/guides/prompt-caching)에 따라 고정 지시문을 앞에, 요청별 상품 JSON을 뒤에 유지한다. 캐시 적중은 보장하지 않는다. 스키마의 요청별 enum, 프롬프트 모드, 반복 호출 간격 등의 차이가 실제 캐시량에 영향을 줄 수 있다. 짧은 LIGHT가 항상 캐시 비용까지 더 저렴하다고 단정하지 않고 actual usage와 비용을 비교한다. 별도 캐시 파라미터나 API/모델 변경은 이번 범위에 포함하지 않는다.

## 전후 비교 실행

기본 `mvn verify`는 API 호출 없이 기존 테스트와 라우팅 테스트를 실행하고 `target/prompt-token-comparison.json`에 의류·수량·용량·중량·칫솔·TV 입력의 전후 텍스트 토큰 수를 생성한다. 기존 의존성의 JTokkit `o200k_base` 토크나이저를 사용한다. 메시지 문자열과 직렬화한 스키마 텍스트만 세므로 실제 API framing, 내부 스키마 처리와 숨은 토큰을 포함하지 않는 추정이다. output_tokens, cached_tokens, total_tokens와 최종 비용을 이 추정에서 만들어내지 않는다.

실제 usage와 정확도 비교는 환경에 API 키가 준비된 경우에만 아래 명령으로 실행한다. 키는 명령 인수·코드·파일에 넣지 않는다. 같은 의류 JSON을 FULL, AUTO 순서로 호출한다. 보정까지 포함하면 최대 네 번의 과금 호출이며 테스트 실패 때도 확보한 usage를 기록한다.

```powershell
$env:OPENAI_RUN_COST_COMPARISON = 'true'
mvn -Dtest=PromptCostComparisonTest test
Remove-Item Env:OPENAI_RUN_COST_COMPARISON
```

`target/prompt-actual-usage-comparison.json`에는 모드별 모든 호출의 input/cached/output/total_tokens와 예상 비용, 요청 단위 합계, 단품·매핑 개수 및 기대 값 일치 여부를 남긴다. 키와 상품 본문은 저장하지 않는다. 두 모드 모두 블랙 및 90/95/100/105의 8개 매핑을 반환하고 기존 검증을 통과해야 테스트가 성공한다. `OPENAI_RUN_COST_COMPARISON`이 없으면 과금 테스트는 건너뛴다.

이번 작업 환경에는 `OPENAI_API_KEY`가 없으므로 실제 usage·캐시·비용 비교 및 실제 모델 정확도 비교는 미실행이다. 결과는 오프라인 추정과 모의 응답 테스트로 구분해서 평가해야 한다.

## 이번 실행 결과

`mvn -o verify`에서 127개 테스트 중 126개 통과, 실제 API 비교 1개 건너뜀, 실패 0개로 JAR 빌드가 성공했다. 검증에는 네 의류 단품의 8개 매핑, 계산형/불명확 옵션의 FULL 선택, 단위 설정 보존, 동일한 User Prompt·JSON Schema, 모의 HTTP 응답의 캐시 usage 역직렬화 및 로그 비활성화/민감정보 제외가 포함된다. 실제 모델 정확도가 완전히 동일하다는 보장은 실측 비교 전에는 하지 않는다.

| 동일 요청 예제 | 선택 모드 | 기존 입력 텍스트 토큰 추정 | 변경 후 추정 | 감소 |
|---|---|---:|---:|---:|
| 아이그너 블랙/90·95·100·105 | LIGHT | 4,770 | 1,703 | 3,067 (64.3%) |
| 리르 패치 수량 | FULL | 5,263 | 5,263 | 0 |
| 선크림 용량 | FULL | 5,276 | 5,276 | 0 |
| 김치 세트 중량 | FULL | 5,099 | 5,099 | 0 |
| 칫솔 세트 수량 | FULL | 4,736 | 4,736 | 0 |
| 플럭스 TV 화면크기 | FULL | 4,923 | 4,923 | 0 |

System Prompt 자체는 3,702 → 635 텍스트 토큰(82.8% 감소)이다. 의류 요청의 캐시 없는 입력 비용만 위 추정에 적용하면 약 $0.001908 → $0.0006812가 된다. 이는 출력 비용, 실제 캐시, API framing을 포함하지 않는 입력 텍스트 비용 시나리오이며 요청당 최종 청구 비용이 아니다. cached/output/total_tokens 및 전체 예상 비용은 실제 비교 파일에서 확인해야 한다.

작업 시작과 종료 시 기존 FULL 파일의 SHA-256은 모두 `2B6A237F01F8FF0143E6E584826A3F2ADB53D9B5A367BE3495B612EC16B9EDF0`로 같았다. 이전 작업에서 추가한 상세 규칙·예시를 그대로 보존했다.
