# 구매옵션 매핑 API 명세서

현재 서버 구현 기준의 연동 안내입니다. 상품 정보를 보내면 구매옵션별 추론 결과와 서버 검증 결과를 반환합니다. 쿠팡 상품 등록·수정 기능은 제공하지 않습니다.

## 1. 주소와 호출 방식

| 항목 | 사양 |
| --- | --- |
| 운영 기본 주소 | `https://ai-agent-service-seven.vercel.app` |
| 로컬 기본 주소 | `http://127.0.0.1:8081` |
| 메서드 | `POST` |
| 경로 | `/api/v1/coupang/purchase-options/infer` |
| 요청 헤더 | `Content-Type: application/json` |
| 요청·응답 형식 | UTF-8 JSON |
| 인증 | 현재 애플리케이션의 이 엔드포인트에는 별도 인증 헤더가 없습니다. OpenAI API 키는 서버에서 설정합니다. |

### 쿼리 매개변수

| 이름 | 타입 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `testMode` | boolean | `false` | `true`이면 OpenAI를 호출하지 않고 모의 추출합니다. |

실제 AI 호출은 서버의 API 키 설정이 필요하며 비용이 발생합니다. 테스트 모드는 API 키 없이 사용할 수 있지만 실제 AI의 판단과 동일한 결과를 보장하지 않습니다.

## 2. 요청 필드

요청 본문에는 상품 객체를 직접 보냅니다. 클라이언트에서 `product`로 감싸지 않습니다.

| 필드 | 타입 | 필수 | 제한·설명 |
| --- | --- | --- | --- |
| `goodsId` | string | 예 | 상품 ID, 공백 불가, 최대 100자 |
| `goodsName` | string | 예 | 상품명, 공백 불가, 최대 500자 |
| `brand` | string | 아니오 | 브랜드, 최대 200자 |
| `categoryName` | string | 예 | 원본 카테고리명, 공백 불가, 최대 500자 |
| `coupangCategoryId` | string | 예 | 쿠팡 카테고리 ID, 공백 불가, 최대 100자 |
| `coupangCategoryName` | string | 예 | 쿠팡 카테고리명, 공백 불가, 최대 500자 |
| `allowedPurchaseOptions` | string[] | 예 | 허용 구매옵션명 1~20개, 각 항목 공백 불가·최대 100자, 중복 불가 |
| `options` | object[] | 예 | 원본 단품 1~200개, null 항목 불가 |
| `productNoticeText` | string | 아니오 | 상품정보고시 원문, 최대 20,000자 |
| `productCompositionText` | string | 아니오 | 상품 기술서의 구성 원문, 최대 20,000자. 생략 또는 null 가능. 예: 본품 선크림 50ml 7개 + 사은품 파우치 1개 |
| `purchaseOptionUnits` | object[] | 아니오 | 구매옵션명별 단위 설정, 최대 20개. 생략 또는 null이면 빈 목록으로 처리 |

### options 항목

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `optionId` | string | 예 | 단품 ID, 공백 불가·최대 100자, 요청 안에서 중복 불가 |
| `optionName1` | string | 예 | 원본 단품 옵션명, 공백 불가·최대 500자 |

### purchaseOptionUnits 항목

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `purchaseOptionName` | string | 예 | 최대 100자. `allowedPurchaseOptions`에 있는 이름과 정확히 매핑, 같은 이름의 설정 중복 불가 |
| `defaultUnit` | string | 예 | 판단하기 어려울 때 사용하는 기본단위, 최대 30자 |
| `unitOptions` | string[] | 예 | 단위 선택지 1~30개, 각 항목 최대 30자, 중복 불가 |

단위 이름은 공백 문자열이나 앞뒤 공백을 포함할 수 없습니다. 기본단위는 단위 선택지에 포함되어 있지 않아도 됩니다. 설정된 구매옵션은 숫자와 선택지의 단위 또는 기본단위를 조합해 반환합니다. 적합한 선택지가 없으면 기본단위를 사용합니다.

단위 설정이 없는 구매옵션은 AI가 근거에 맞는 값을 도출합니다. 예를 들어 화면크기는 `109cm`, `43인치`처럼 반환할 수 있습니다. 단위를 모두 `개`로 강제하지 않습니다.

## 3. 요청 예제

### 색상·사이즈

```json
{
  "goodsId": "sample-clothing",
  "goodsName": "블랙 니트탑",
  "brand": "예제 브랜드",
  "categoryName": "의류>상의",
  "coupangCategoryId": "1007572",
  "coupangCategoryName": "의류>상의",
  "productNoticeText": "색상: 블랙, 치수: S(90) / M(95) / L(100) / XL(105)",
  "allowedPurchaseOptions": ["색상", "패션의류/잡화 사이즈"],
  "options": [
    {"optionId": "1", "optionName1": "블랙/90"},
    {"optionId": "2", "optionName1": "블랙/95"},
    {"optionId": "3", "optionName1": "블랙/100"},
    {"optionId": "4", "optionName1": "블랙/105"}
  ]
}
```

### 수량·용량과 단위 설정

```json
{
  "goodsId": "sample-sunscreen",
  "goodsName": "선크림 50ml 7개",
  "categoryName": "뷰티>선크림",
  "coupangCategoryId": "sample-category",
  "coupangCategoryName": "뷰티>선케어",
  "productNoticeText": "용량: 50ml",
  "allowedPurchaseOptions": ["수량", "개당 용량"],
  "purchaseOptionUnits": [
    {"purchaseOptionName": "수량", "defaultUnit": "개", "unitOptions": ["개", "박스", "세트"]},
    {"purchaseOptionName": "개당 용량", "defaultUnit": "ml", "unitOptions": ["ml", "L"]}
  ],
  "options": [{"optionId": "1", "optionName1": "단일상품"}]
}
```

예제의 카테고리 값은 호출 구조 설명용입니다. 실제 연동에서는 상품에 해당하는 카테고리 값을 전달하세요.

## 4. 호출 코드

### curl

앞의 요청 JSON을 UTF-8 파일 `request.json`으로 저장합니다. 다음 명령은 비용이 발생하지 않는 테스트 모드 호출입니다.

```bash
curl --request POST 'https://ai-agent-service-seven.vercel.app/api/v1/coupang/purchase-options/infer?testMode=true' \
  --header 'Content-Type: application/json' \
  --data-binary '@request.json'
```

실제 AI 추론은 `testMode=false`로 변경하거나 쿼리 매개변수를 생략합니다. PowerShell에서는 `curl.exe`를 사용하고 명령을 한 줄로 입력할 수 있습니다.

### JavaScript

```javascript
async function inferPurchaseOptions(product, testMode = true) {
  const response = await fetch(
    `/api/v1/coupang/purchase-options/infer?testMode=${testMode}`,
    {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(product)
    }
  );
  const result = await response.json();
  if (!response.ok) {
    throw new Error(`${result.errorCode}: ${result.reason}`);
  }
  // HTTP 200에서도 검토 필요·테스트 모드이면 success=false입니다.
  return result;
}
```

이 코드는 같은 서버에서 실행하는 화면 기준입니다. 다른 서버에서 호출할 때는 기본 주소를 붙이고 해당 환경의 접근 설정을 확인하세요.

## 5. 응답 필드

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `goodsId` | string 또는 null | 요청 상품 ID. 예외 처리 응답에서는 null일 수 있음 |
| `success` | boolean | 실제 AI 결과가 검증과 신뢰도 기준을 통과했는지 |
| `autoApplyCandidate` | boolean | 자동 적용 후보 여부. 현재 서비스가 상품에 직접 적용하지는 않음 |
| `confidence` | number | AI 전체 신뢰도와 유효한 개별 매핑 신뢰도의 최솟값, 0~1 |
| `optionMappings` | object[] | 최종 상세 매핑 목록 |
| `items` | object[] | 단품별 최종 구매옵션 결과 |
| `reason` | string | 결과 사유 |
| `validationErrors` | string[] | 검증 오류 목록, 없으면 빈 배열 |
| `errorCode` | string 또는 null | 정상 승인 시 null, 테스트·검토·실패 시 코드 |
| `inputHash` | string 또는 null | 입력 해시, 예외 처리 응답에서는 null일 수 있음 |
| `promptVersion` | string | 서버의 프롬프트 버전. 버전 문자열을 고정값으로 가정하지 말 것 |
| `inferenceSource` | string | `AI`, `TEST`, `NONE` |
| `aiAssessment` | object 또는 null | 서버 승인 여부와 별개인 AI 제안 |
| `serverAssessment` | object | 서버 판단 코드·사유·신뢰도 기준 |
| `aiUsage` | object[] | 해당 요청의 실제 AI 호출별 사용량, 호출이 없거나 예외 처리 응답이면 빈 배열 |

### optionMappings 항목

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `optionId` | string | 원본 단품 ID |
| `sourceOptionName` | string | 원본 옵션명 |
| `targetPurchaseOptionName` | string | 허용 구매옵션명 |
| `value` | string | 추출한 값 |
| `confidence` | number | 개별 매핑 신뢰도 |
| `evidenceSource` | string 또는 null | AI 응답은 `goodsName`, `productNoticeText`, `productCompositionText` 또는 null. 일반 옵션명 추출은 null |
| `evidenceText` | string 또는 null | 근거 원문. 일반 옵션명 추출은 null일 수 있음 |
| `calculation` | object 또는 null | 수량·단위 등의 연산 설명. 일반 문자열은 null일 수 있음 |

### items 항목

`optionId`는 단품 ID이며 `purchaseOptions`는 구매옵션명을 키, 추출 값을 값으로 갖는 객체입니다. 한 단품에 여러 구매옵션이 들어갈 수 있습니다.

```json
{
  "optionId": "1",
  "purchaseOptions": {"색상": "블랙", "패션의류/잡화 사이즈": "90"}
}
```

### AI·서버 판단

`aiAssessment`는 `certain`(boolean), `confidence`(number), `mappings`(배열), `reason`(문자열)을 포함합니다. 제안 매핑 필드는 `optionMappings`와 같지만 `sourceOptionName`은 없습니다. 검증 실패 시에도 AI 제안이 남아 있을 수 있으므로 승인된 결과로 사용하면 안 됩니다. 실제 AI의 최종 검증 실패 시 서버가 `certain=false`로 바꾸고 `reason`에 검토 사유를 남깁니다. 따라서 이 필드는 AI 원문 응답과 항상 같지는 않습니다. 파싱할 수 없는 최종 응답은 빈 제안 목록과 검토 사유로 나타납니다.

`serverAssessment`는 `decisionCode`, `reason`, `confidenceThreshold`를 포함합니다. 판단 코드는 `ACCEPTED`, `AI_UNCERTAIN`, `LOW_CONFIDENCE`, `RESULT_VALIDATION_FAILED`, `TEST_MODE` 또는 예외 오류 코드입니다. 기본 신뢰도 기준은 0.8이며 서버 설정에 따라 달라질 수 있습니다.

### calculation 객체

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `operation` | string | `DIRECT`, `CONVERT`, `SUM`, `PACK_COUNT`, `PACK_CONTENT` |
| `outputUnit` | string | 출력 단위 |
| `operands` | object[] | 피연산자 목록. 각 항목은 문자열 `amount`, 문자열 `unit`, 객체 `evidence` |
| `context` | object 또는 null | 구성 판단을 설명하는 근거 |

`evidence`와 `context`는 `source`와 `text` 문자열을 갖습니다. `DIRECT`는 직접 추출, `CONVERT`는 단위 변환, `SUM`은 합산, `PACK_COUNT`는 묶음 수량, `PACK_CONTENT`는 묶음 내부 수량을 나타냅니다. 응답은 AI의 판단을 담고 있으며 수학적 정확성을 보증하는 필드는 아닙니다.

## 6. 성공 응답 예제

다음은 단품 하나에 대한 실제 AI 승인 응답 형태의 설명용 예제입니다. 신뢰도·사유·해시·버전·사용량은 요청마다 달라집니다. 토큰 값과 비용은 예시입니다.

```json
{
  "goodsId": "sample-clothing",
  "success": true,
  "autoApplyCandidate": true,
  "confidence": 0.95,
  "optionMappings": [
    {
      "optionId": "1",
      "sourceOptionName": "블랙/90",
      "targetPurchaseOptionName": "색상",
      "value": "블랙",
      "confidence": 0.95,
      "evidenceSource": null,
      "evidenceText": null,
      "calculation": null
    },
    {
      "optionId": "1",
      "sourceOptionName": "블랙/90",
      "targetPurchaseOptionName": "패션의류/잡화 사이즈",
      "value": "90",
      "confidence": 0.95,
      "evidenceSource": null,
      "evidenceText": null,
      "calculation": null
    }
  ],
  "items": [{"optionId": "1", "purchaseOptions": {"색상": "블랙", "패션의류/잡화 사이즈": "90"}}],
  "reason": "원본 옵션명에서 색상과 사이즈를 확인했습니다.",
  "validationErrors": [],
  "errorCode": null,
  "inputHash": "요청별 입력 해시",
  "promptVersion": "coupang-option-v18",
  "inferenceSource": "AI",
  "aiAssessment": {
    "certain": true,
    "confidence": 0.95,
    "mappings": [
      {"optionId": "1", "targetPurchaseOptionName": "색상", "value": "블랙", "confidence": 0.95, "evidenceSource": null, "evidenceText": null, "calculation": null},
      {"optionId": "1", "targetPurchaseOptionName": "패션의류/잡화 사이즈", "value": "90", "confidence": 0.95, "evidenceSource": null, "evidenceText": null, "calculation": null}
    ],
    "reason": "원본 옵션명에서 색상과 사이즈를 확인했습니다."
  },
  "serverAssessment": {"decisionCode": "ACCEPTED", "reason": "서버 검증을 통과했습니다.", "confidenceThreshold": 0.8},
  "aiUsage": [{"model": "gpt-4.1-mini-2025-04-14", "mode": "LIGHT", "attempt": 1, "input_tokens": 2000, "cached_tokens": 0, "output_tokens": 500, "total_tokens": 2500, "estimated_cost_usd": 0.0016}]
}
```

## 7. AI 호출 사용량

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `model` | string | API 응답의 모델명 |
| `mode` | string | 선택한 프롬프트 모드 `LIGHT` 또는 `FULL` |
| `attempt` | integer | 1은 최초 호출, 2는 LIGHT 검증 실패 후 FULL 재추론 |
| `input_tokens` | integer 또는 null | 입력 토큰 수 |
| `cached_tokens` | integer 또는 null | 입력 중 캐시된 토큰 수. 입력에 이미 포함됨 |
| `output_tokens` | integer 또는 null | 출력 토큰 수 |
| `total_tokens` | integer 또는 null | API에서 보고한 전체 토큰 수 |
| `estimated_cost_usd` | number 또는 null | 서버에 등록된 모델 단가로 계산한 예상 USD 비용 |
| `inferenceId` | string 또는 null | 같은 상품 추론 요청의 호출을 연결하는 UUID |
| `initialPromptMode` | string | 최초 선택 모드 LIGHT/FULL |
| `finalPromptMode` | string | 최종 모드. 재추론 예정 LIGHT 행에서도 FULL |
| `retryCount` | integer | 최초 0, FULL 재추론 1 |
| `retryReasons` | string[] | 전환 사유 목록. FULL 행에는 최초 LIGHT의 실패 사유 유지 |
| `status` | string 또는 null | SUCCESS, VALIDATION_ERROR, REVIEW_REQUIRED. 예외 호출은 JSONL에 API_ERROR로 기록 |

알 수 없는 값은 null이며 0과 구분해야 합니다. 여러 호출이 있으면 각 호출을 합산합니다. 캐시 토큰을 입력 토큰에 다시 더하지 않습니다. 예상 비용은 청구서 확정 금액이 아니며 모델 단가나 사용량을 확인할 수 없으면 null입니다.

LIGHT는 단순 문자열 추출 요청에 사용합니다. 계산형 옵션·단위 설정·판단이 모호한 요청은 FULL을 사용합니다. 클라이언트 요청에 모드 선택 필드는 없습니다. 테스트 모드는 `aiUsage=[]`입니다. 호출 중 예외로 반환된 오류 응답의 빈 목록은 비용 발생 여부를 판단하는 근거로 사용할 수 없습니다.

기본 JSONL 경로는 `logs/ai-usage.jsonl`이며 `AI_USAGE_JSONL_PATH`로 변경합니다. 파일 기록은 `AI_USAGE_JSONL_ENABLED=true`가 기본이며 콘솔 로그 설정과 독립적입니다. JSONL에는 응답 메트릭 외에 `goodsId`, `promptMode`, `retryReason`(첫 사유), `validationPassed`, `validationErrors`, `certain`, `confidence`, `minimumMappingConfidence`, `timestamp`도 기록합니다. FULL 호출 자체가 실패해도 앞선 LIGHT 사용량은 이 파일에서 확인할 수 있습니다. 검증 실패 행은 실제 수신한 usage를 보존하고, API 오류로 알 수 없는 사용량은 null로 기록합니다.

## 8. 오류 코드와 HTTP 상태

### LIGHT → FULL Failover

서버는 최초 요청에서 PromptSelector로 LIGHT/FULL을 선택합니다. LIGHT 결과가 `certain=false`이거나 매핑·응답 검증에 실패한 경우 같은 상품 데이터로 FULL을 명시하여 한 번만 호출합니다. 재추론에서도 기존 User Prompt와 JSON Schema를 사용하며 이전 응답을 덧붙이지 않습니다. 처음부터 FULL인 요청에는 이 재호출을 적용하지 않습니다.

| 상황 | 최대 호출 수 |
| --- | --- |
| 정상 LIGHT | 1 |
| LIGHT 검증 실패 | 2 |
| 처음부터 FULL | 1 |

FULL도 검증에 실패하면 HTTP 200에서 `errorCode=REVIEW_REQUIRED`, `success=false`, `autoApplyCandidate=false`, `aiAssessment.certain=false`를 반환하며 `items`와 `optionMappings`는 비웁니다. FULL 호출 자체가 실패하면 기존 timeout·429·5xx 등의 오류 정책을 유지합니다. 이 장애에는 Prompt Failover를 추가 적용하지 않습니다.

기본 설정은 `purchase-option.ai.retry.enabled=true`, `max-retries=1`, `confidence-threshold-enabled=false`, `confidence-threshold=0.7`입니다. `max-retries`는 0 또는 1만 허용합니다. 클라이언트 요청 JSON에는 재추론 설정 필드가 없습니다. 낮은 confidence만으로는 재추론하지 않으며 기존 최종 승인 기준 `app.inference.confidence-threshold=0.80`은 별개로 유지합니다.

| 재추론 사유 코드 | 조건 |
| --- | --- |
| CERTAIN_FALSE | certain=false |
| MISSING_OPTION_MAPPING | 입력 optionId에 대한 매핑 누락 |
| UNKNOWN_OPTION_ID | 입력에 없는 optionId |
| INVALID_PURCHASE_OPTION | allowedPurchaseOptions 밖의 이름 |
| DUPLICATE_MAPPING | 동일 (optionId, targetPurchaseOptionName) 중복 |
| EMPTY_VALUE | mapping이 null 또는 필수 필드가 null/blank |
| INVALID_RESPONSE | 응답 형식·완료 여부 또는 기존 서버 검증 실패 |
| LOW_CONFIDENCE | 선택적 재추론 신뢰도 기준을 활성화한 경우에만 사용 |

### LIGHT → FULL 사용량 예제

다음은 LIGHT의 단품 매핑 누락을 FULL이 해결한 경우의 `aiUsage` 예시입니다. 수치와 UUID는 설명용이며 실제 요청의 사용량은 API 응답에서 가져옵니다.

```json
[
  {
    "model": "gpt-4.1-mini", "mode": "LIGHT", "attempt": 1,
    "input_tokens": 1000, "cached_tokens": 800, "output_tokens": 200,
    "total_tokens": 1200, "estimated_cost_usd": 0.00048,
    "inferenceId": "5d6d0000-0000-4000-8000-000000000001",
    "initialPromptMode": "LIGHT", "finalPromptMode": "FULL", "retryCount": 0,
    "retryReasons": ["MISSING_OPTION_MAPPING", "INVALID_RESPONSE"], "status": "VALIDATION_ERROR"
  },
  {
    "model": "gpt-4.1-mini", "mode": "FULL", "attempt": 2,
    "input_tokens": 1000, "cached_tokens": 800, "output_tokens": 200,
    "total_tokens": 1200, "estimated_cost_usd": 0.00048,
    "inferenceId": "5d6d0000-0000-4000-8000-000000000001",
    "initialPromptMode": "LIGHT", "finalPromptMode": "FULL", "retryCount": 1,
    "retryReasons": ["MISSING_OPTION_MAPPING", "INVALID_RESPONSE"], "status": "SUCCESS"
  }
]
```

### FULLでも失敗した場合の応答例

다음은 단품 1개 입력에서 최종 FULL도 불확실하다고 판단한 경우의 주요 필드 발췌입니다. 나머지 기존 응답 필드도 정상적으로 반환합니다.

```json
{
  "goodsId": "68535109",
  "success": false,
  "autoApplyCandidate": false,
  "confidence": 0.95,
  "optionMappings": [],
  "items": [],
  "reason": "REVIEW_REQUIRED: [CERTAIN_FALSE]; certain=false; AI: 원문만으로 판단하기 어렵습니다.",
  "validationErrors": [],
  "errorCode": "REVIEW_REQUIRED",
  "aiAssessment": {
    "certain": false, "confidence": 0.95,
    "mappings": [{"optionId": "1", "targetPurchaseOptionName": "색상", "value": "블랙", "confidence": 0.95,
      "evidenceSource": null, "evidenceText": null, "calculation": null}],
    "reason": "REVIEW_REQUIRED: [CERTAIN_FALSE]; certain=false; AI: 원문만으로 판단하기 어렵습니다."
  },
  "serverAssessment": {"decisionCode": "AI_UNCERTAIN", "reason": "AI가 certain=false로 판단하여 서버가 적용을 보류했습니다.", "confidenceThreshold": 0.8}
}
```

이 예시의 제안 매핑은 검토용입니다. 오류 상세는 `reason`, `validationErrors`와 각 호출 로그의 검증 결과를 함께 확인합니다. `CERTAIN_FALSE`만 있는 경우 기존 구조 검증 오류인 `validationErrors`는 빈 목록일 수 있습니다.

### 오류 코드

| HTTP | errorCode | 의미 |
| --- | --- | --- |
| 200 | null | 실제 AI 결과 승인 |
| 200 | `TEST_MODE` | 모의 추출 완료, 자동 적용 대상 아님 |
| 200 | `REVIEW_REQUIRED` | 실제 AI 최종 검증 실패·불확실 또는 신뢰도 기준 미달 |
| 200 | `RESULT_VALIDATION_FAILED` | 테스트 모드 등의 결과 검증 실패 |
| 400 | `INVALID_REQUEST` | 필드 제약, 중복 값 또는 쿼리 매개변수 오류 |
| 400 | `INVALID_JSON` | JSON 해석 실패 |
| 429 | `AI_RATE_LIMIT` | OpenAI 요청 제한 |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 서버 오류 |
| 502 | `AI_UPSTREAM_ERROR` | 외부 AI 호출 실패 |
| 503 | `AI_NOT_CONFIGURED` | 실제 AI 호출에 필요한 서버 API 키 미설정 |
| 504 | `AI_TIMEOUT` | 외부 시간 초과 또는 OpenAI 408·504 |

### 잘못된 요청 응답 예제

```json
{
  "goodsId": null,
  "success": false,
  "autoApplyCandidate": false,
  "confidence": 0,
  "optionMappings": [],
  "items": [],
  "reason": "입력 데이터를 확인하세요.",
  "validationErrors": ["optionId는 중복될 수 없습니다."],
  "errorCode": "INVALID_REQUEST",
  "inputHash": null,
  "promptVersion": "coupang-option-v18",
  "inferenceSource": "NONE",
  "aiAssessment": null,
  "serverAssessment": {"decisionCode": "INVALID_REQUEST", "reason": "입력 데이터를 확인하세요.", "confidenceThreshold": 0.8},
  "aiUsage": []
}
```

## 9. 결과 처리 규칙

1. HTTP 상태와 JSON 응답을 함께 확인합니다. HTTP 200만으로 성공을 판단하지 않습니다.
2. `success=true`이고 `autoApplyCandidate=true`인 경우 최종 `items`를 적용 후보로 사용합니다.
3. `REVIEW_REQUIRED`와 `RESULT_VALIDATION_FAILED`에서는 최종 결과 목록이 비워집니다. `aiAssessment.mappings`는 검토 자료입니다.
4. `TEST_MODE`는 `success=false`, `autoApplyCandidate=false`, `confidence=0`, `inferenceSource=TEST`이며 모의 `items`와 `optionMappings`가 반환될 수 있습니다.
5. LIGHT 검증 실패 시 FULL을 한 번 호출합니다. 정상 LIGHT와 처음부터 FULL은 1회, LIGHT 검증 실패는 최대 2회입니다. 최종 FULL 실패 후 추가 호출은 없습니다.
6. 재추론된 결과가 성공하면 FULL 결과만 적용 후보로 사용합니다. 두 호출의 비용은 모두 합산하며 연결에는 `inferenceId`를 사용합니다.

신뢰도는 AI 전체 confidence와 각 매핑 confidence 중 최솟값입니다. 실제 승인에는 `certain=true`, 서버의 신뢰도 기준 이상, 결과 검증 통과가 모두 필요합니다. AI 신뢰도는 자체 평가값이며 실제 정답 확률을 의미하지 않습니다.
