# 구매옵션 매핑 API 연동 명세서

현재 저장소의 Java 구현과 `application.yml`을 기준으로 작성했습니다. 상품 데이터와 단품 옵션명을 보내면 구매옵션 분석 결과와 서버의 최종 판단을 반환합니다. 예제의 상품 데이터·신뢰도·사용량·비용은 설명용이며 실제 AI 결과를 보장하지 않습니다.

## 1. API 개요

SK스토아 상품의 단품 옵션명을 쿠팡 구매옵션별 값으로 구분하는 동기식 API입니다. 한 요청에 상품 하나와 단품 1~200개를 전달합니다. 쿠팡 상품 등록·수정은 수행하지 않습니다.

서버가 요청을 보고 분석 방식을 자동 선택합니다. 설정과 첫 분석 결과에 따라 상세 분석을 한 번 더 수행할 수 있습니다. 클라이언트가 LIGHT/FULL을 지정하는 요청 필드는 없습니다. 추가 분석은 응답 시간과 AI 호출 비용을 늘릴 수 있습니다.

## 2. 호출 방법

| 항목 | 사양 |
| --- | --- |
| 메서드 | `POST` |
| 경로 | `/api/v1/coupang/purchase-options/infer` |
| 로컬 기본 주소 | `http://127.0.0.1:8081` — 기본 설정, 실행 환경에서 변경 가능 |
| 기존 안내의 배포 주소 | `https://ai-agent-service-seven.vercel.app` — 저장소의 배포 안내에 기재된 주소이며 현재 가용성·인증·배포 버전은 이번 코드 검토로 확인하지 않음 |
| 요청 헤더 | `Content-Type: application/json`, 인증 ON일 때 `X-API-Key` |
| 응답 | JSON, 비스트리밍 |
| 인코딩 | 요청 JSON 파일은 UTF-8로 저장 |
| 인증 | 서비스 전용 API Key 인증. 기본 ON, dev 프로필에서는 기본 OFF |

> **중요 — OpenAI API Key는 서버 전용입니다.**
>
> **이 API 요청에 OpenAI Key를 보내지 마세요.** `OPENAI_API_KEY`는 서버 환경변수 또는 Secret으로 설정합니다. 요청의 `X-API-Key`에는 별개의 **서비스 전용 Key**를 전달합니다.
>
> 서버에 `OPENAI_API_KEY`가 없으면 실제 추론은 **HTTP 503 (`AI_NOT_CONFIGURED`)**으로 반환됩니다. 모의 테스트 모드는 OpenAI Key 없이 사용할 수 있지만, **서비스 API Key 인증이 켜져 있으면 테스트 모드도 인증이 필요합니다.**

### API 인증 및 호출 제한

**API Key 인증**

추론 API는 서비스 전용 API Key를 사용하여 요청을 인증합니다.

API Key 인증이 활성화된 환경에서는 요청 시 `X-API-Key` Header를 반드시 전달해야 합니다.

| Header | 필수 | 설명 |
| :--- | :---: | :--- |
| `Content-Type` | Y | `application/json` |
| `X-API-Key` | Y | AI Agent Service 운영 담당자가 설정·제공한 API Key. 인증 ON일 때 필수 |

> `X-API-Key`에 사용하는 Key는 OpenAI API Key와 별개의 서비스 전용 Key입니다.

요청 예시:

```http
POST /api/v1/coupang/purchase-options/infer
Content-Type: application/json
X-API-Key: <service-api-key>
```

위 예시는 요청 헤더이며 본문에는 아래 Request 규격의 상품 JSON을 전달합니다. 실제 Key를 소스코드·브라우저 HTML/JavaScript·로그에 기록하지 않습니다. Key가 없거나 비어 있거나 잘못되면 HTTP 401 / `UNAUTHORIZED`로 반환되며 OpenAI를 호출하지 않습니다. 모의 테스트 모드도 동일하게 인증합니다.

**호출 횟수 제한**

기본값은 API Key ID별 첫 요청부터 60초 동안 30회입니다. `AI_AGENT_RATE_LIMIT_ENABLED`와 `AI_AGENT_RATE_LIMIT_PER_MINUTE`로 사용 여부와 허용 횟수를 설정합니다. 초과 시 HTTP 429 / `RATE_LIMIT_EXCEEDED`와 남은 대기시간(초)을 나타내는 `Retry-After` Header를 반환합니다. 인증 실패는 요청 수를 소비하지 않습니다.

Rate Limit은 서버 인스턴스별 메모리 기준으로, 다중 서버/컨테이너의 전역 제한이 아닙니다. 인증·호출 제한으로 차단된 요청은 OpenAI 호출과 AI Usage 로그를 생성하지 않습니다. 정상 AI 호출의 JSONL에는 실제 Key 대신 `apiKeyId`를 기록합니다.

### 쿼리 매개변수

| 이름 | 타입 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `testMode` | boolean | `false` | `true`이면 실제 AI를 호출하지 않고 모의 추출 |

연동 시 `true` 또는 `false`를 명시하세요. boolean으로 변환할 수 없는 값은 HTTP 400의 `INVALID_REQUEST`입니다. 본문에 `testMode`를 넣지 않습니다.

### curl

5절의 요청을 UTF-8 `request.json`으로 저장한 다음 호출합니다. 다음 예제는 과금이 없는 테스트 모드입니다.

```bash
curl --request POST 'http://127.0.0.1:8081/api/v1/coupang/purchase-options/infer?testMode=true' \
  --header 'Content-Type: application/json' \
  --header 'X-API-Key: <service-api-key>' \
  --data-binary '@request.json'
```

실제 AI 분석은 `testMode=false`로 바꾸거나 쿼리를 생략합니다. PowerShell에서는 `curl.exe`와 한 줄 명령을 사용할 수 있습니다. 운영 호출은 담당자가 확인한 기본 주소로 교체하세요.

## 3. 연동 시 중요: 결과 처리 규칙

**HTTP 200만으로 적용 가능한 결과라고 판단하면 안 됩니다.**

| 확인 사항 | 처리 규칙 |
| --- | --- |
| `success` | HTTP 요청 성공이 아니라 추론 결과의 서버 최종 승인 여부 |
| 적용 후보 | 기본적으로 `success=true && autoApplyCandidate=true`인 경우만 사용 |
| 후속 처리 데이터 | `items` 사용. `purchaseOptions`의 키는 구매옵션명, 값은 추출 문자열 |
| 최종 상세 | `optionMappings`는 최종 결과의 원본 단품·구매옵션·값을 보여주는 상세 매핑 |
| AI 제안 | `aiAssessment.mappings`는 판단·검토 자료. 최종 적용 데이터로 사용하지 않음 |
| `REVIEW_REQUIRED` | 정상적으로 반환되는 업무 판단 결과. 시스템 장애로 간주하지 않음 |
| `RESULT_VALIDATION_FAILED` | 결과 검증 실패. HTTP 200이어도 최종 목록은 비어 있으므로 검토 |
| `TEST_MODE` | 모의 결과가 있어도 실제 적용 대상으로 사용하지 않음 |

현재 구현은 승인 시 두 플래그를 모두 true, 그 외에는 모두 false로 반환합니다. `autoApplyCandidate=true`는 후속 시스템의 적용 후보라는 뜻이며 **이 API가 쿠팡 상품을 등록하거나 수정했다는 뜻이 아닙니다.**

허용 목록은 사용할 수 있는 구매옵션의 범위입니다. 서버는 단품마다 최소 하나의 AI 매핑을 요구합니다. 최종 `items.purchaseOptions`에서는 AI가 매핑하지 않은 항목 중 기본단위 설정이 없는 항목을 `"없음"`으로 채우며, 기본단위가 설정된 누락 항목은 생략합니다. 후속 시스템이 반드시 요구하는 구매옵션은 실제 값이 추출되었는지 호출자가 추가로 확인하세요.

## 4. Request

상품 객체를 요청 본문으로 직접 보냅니다. `product` 객체로 감싸지 않습니다. 아래에 없는 필드(`brand`, `coupangCategoryId`, `coupangCategoryName`, `promptMode` 등)를 보내면 현재 Jackson 설정에 따라 `INVALID_JSON`이 발생합니다.

`goodsId`와 `categoryName`은 서버에서 요청 식별·로그·집계에 사용하며 실제 AI에는 전송하지 않습니다. LIGHT, FULL, 추가 FULL 재추론 모두 동일합니다. AI에는 상품명, 허용 구매옵션, 단위 설정, 정보고시, 구성, 단품 ID와 원본 옵션명만 전달합니다. 최종 응답의 상품 ID와 로그의 상품·카테고리 정보는 유지합니다.

| 필드 | 타입 | 필수 | 제한·설명 |
| --- | --- | --- | --- |
| `goodsId` | string | 예 | 상품 ID, 공백뿐인 값 불가, 최대 100자 |
| `goodsName` | string | 예 | 상품명, 공백뿐인 값 불가, 최대 500자 |
| `categoryName` | string | 아니요 | 내부 / SK스토아 카테고리명, 누락·null·빈 문자열·공백 허용, 최대 500자 |
| `allowedPurchaseOptions` | string[] | 예 | 1~200개, 각 이름 공백뿐인 값 불가·최대 100자, 중복 불가 |
| `options` | object[] | 예 | 단품 1~200개, null 항목 불가 |
| `productNoticeText` | string | 예 | SK스토아 상품정보고시 원문, 공백뿐인 값 불가, 최대 20,000자 |
| `purchaseOptionUnits` | object[] | 아니오 | 0~200개, null 항목 불가. 생략·null이면 빈 목록으로 처리 |
| `productCompositionText` | string 또는 null | 아니오 | 상품 기술서·구성 원문, 최대 20,000자. 생략·null·빈 문자열·공백 문자열 허용 |

필수 문자열 필드는 누락·null·빈 문자열·공백뿐인 문자열을 허용하지 않습니다. 필수 목록은 누락·null·빈 목록을 허용하지 않습니다. 두 모드에서 동일하게 요청을 검증합니다.

길이는 Java 문자열의 길이 기준입니다. 일반 문자열은 자동으로 앞뒤 공백을 제거하지 않으며 중복·이름 포함 여부는 대소문자를 포함한 정확한 문자열로 비교합니다. 표시명을 그대로 전달하세요.

### options 항목

| 필드 | 타입 | 필수 | 제한·설명 |
| --- | --- | --- | --- |
| `optionId` | string | 예 | 공백뿐인 값 불가, 최대 100자, 같은 요청에서 중복 불가 |
| `optionName1` | string | 예 | 실제 단품 옵션명, 공백뿐인 값 불가, 최대 500자 |

옵션명 자체의 중복은 요청 검증에서 막지 않습니다. 다만 서로 다른 단품의 최종 구매옵션 조합이 같으면 결과 검증이 실패할 수 있습니다.

### purchaseOptionUnits 항목

| 필드 | 타입 | 필수 | 제한·설명 |
| --- | --- | --- | --- |
| `purchaseOptionName` | string | 예 | 공백뿐인 값 불가·최대 100자. 허용 목록에 정확히 포함, 같은 이름의 설정 중복 불가 |
| `defaultUnit` | string | 예 | 공백뿐인 값·앞뒤 공백 불가, 최대 30자 |
| `unitOptions` | string[] | 예 | 1~30개. 각 항목 공백뿐인 값·앞뒤 공백 불가·최대 30자, 중복 불가 |

기본단위는 `unitOptions`에 없어도 됩니다. 색상·일반 사이즈는 단위 설정 없이 이름만 전달할 수 있습니다. 단위 선택지에 적합한 단위가 없으면 기본단위를 사용하도록 AI에 지시합니다. 서버가 단위를 자동 변환하거나 숫자를 재계산하지는 않습니다.

단위 설정이 있는 결과는 숫자 바로 뒤에 선택지 또는 기본단위가 붙어야 합니다. 예: `6개`, `1,000ml`, `5.2kg`. `6 개`, 음수, 부호를 붙인 수, 지수 표기는 현재 검증 패턴에서 통과하지 않습니다. `calculation`이 있으면 `outputUnit`도 결과 값의 단위와 같아야 합니다.

단위 설정이 없으면 값과 표기를 AI 제안 그대로 사용합니다. 화면크기의 `109cm`, `43인치`와 같은 값을 임의로 `개`로 바꾸지 않습니다.

### productCompositionText

상품정보고시와 별도로 기술서의 상품 구성을 원문으로 전달합니다. 생략해도 기존 요청은 유효합니다. 필요한 수량·용량·중량 항목은 `allowedPurchaseOptions`에 포함하세요.

본품·사은품·묶음 구성 구분, 일반 색상·사이즈의 원문 유지 등은 AI 프롬프트의 판단 지침입니다. 구성 원문이 있다고 모든 항목을 반환하거나, 서버가 원문 충돌·계산 정확성을 보증하지는 않습니다. 상세 지침은 별도 API 내부 설계/운영 문서에서 설명합니다.

## 5. Request 예제

### 색상·사이즈

```json
{
  "goodsId": "sample-clothing",
  "goodsName": "블랙 니트탑",
  "categoryName": "의류>상의",
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

### 기술서 구성과 단위 설정

```json
{
  "goodsId": "sample-sunscreen",
  "goodsName": "선크림 구성 상품",
  "categoryName": "뷰티>선크림",
  "productNoticeText": "용량: 50ml",
  "productCompositionText": "본품 선크림 50ml 7개 + 사은품 파우치 1개",
  "allowedPurchaseOptions": ["수량", "개당 용량"],
  "purchaseOptionUnits": [
    {"purchaseOptionName": "수량", "defaultUnit": "개", "unitOptions": ["개", "박스", "세트"]},
    {"purchaseOptionName": "개당 용량", "defaultUnit": "ml", "unitOptions": ["ml", "L"]}
  ],
  "options": [{"optionId": "1", "optionName1": "단일상품"}]
}
```

본품 `수량=7개`, `개당 용량=50ml`를 기대하는 설명용 요청입니다. 사은품을 합친 `8개`나 총용량 `350ml`를 개당 용량으로 쓰는 값은 기대값과 다릅니다. 실제 AI의 값과 근거를 별도로 확인하세요.

## 6. Response

### 주요 필드 역할

| 필드 | 역할 | 적용 시 사용 |
| --- | --- | --- |
| `items` | 서버가 조립·검증한 단품별 최종 구매옵션 결과 | 두 승인 플래그를 확인한 후 후속 처리에 사용 |
| `optionMappings` | 최종 매핑의 단품 ID·원본 옵션명·구매옵션명·값·근거 상세 | 결과 추적·검토에 사용 |
| `aiAssessment` | AI 제안과 검토 정보. 최종 승인과 별개이며 실패 시에도 제안이 남을 수 있음 | 적용 데이터로 사용하지 않음 |
| `serverAssessment` | 서버 최종 판단 코드·사유·신뢰도 기준 | 승인·보류 사유 확인 |

### 최상위 필드

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `goodsId` | string 또는 null | 요청 상품 ID. 예외 처리 응답에서는 null |
| `success` | boolean | 추론 결과의 최종 승인 여부. HTTP 요청 성공 여부와 다름 |
| `autoApplyCandidate` | boolean | 후속 시스템의 적용 후보 여부. 실제 쿠팡 등록·수정은 수행하지 않음 |
| `confidence` | number | 전체·유효한 개별 매핑 신뢰도의 최솟값. 유효한 전체 신뢰도가 없으면 0, 테스트·예외 응답은 0 |
| `optionMappings` | object[] | 최종 상세 매핑. 결과 보류·실패 시 빈 배열 |
| `items` | object[] | 최종 단품 결과. 결과 보류·실패 시 빈 배열 |
| `reason` | string | 결과 사유. 문자열을 프로그램 분기용 코드로 해석하지 않음 |
| `validationErrors` | string[] | 서버 검증 오류. 없으면 빈 배열이며 검토 필요여도 비어 있을 수 있음 |
| `errorCode` | string 또는 null | 승인 시 null, 테스트·검토·오류 시 코드 |
| `inputHash` | string 또는 null | 입력의 SHA-256 해시. 예외 처리에서는 null. 캐시 적중·중복 요청 억제를 뜻하지 않음 |
| `promptVersion` | string | 서버 프롬프트 버전. 고정 문자열로 가정하지 않음 |
| `inferenceSource` | string | `AI`, `TEST`, `NONE` |
| `aiAssessment` | object 또는 null | 테스트·예외 응답은 null. 실제 분석에서는 최종 제안 또는 검토 정보 |
| `serverAssessment` | object | 서버 최종 판단 |
| `aiUsage` | object[] | 현재 요청의 AI 호출별 사용량. 호출이 없거나 예외 처리 응답이면 빈 배열 |

`items`는 요청 `options`의 순서로 조립됩니다. JSON 객체 `purchaseOptions`의 키 순서에는 의존하지 마세요. 승인 시 신뢰도는 0~1이며 기본 승인 기준은 0.80입니다. 실제 기준은 `serverAssessment.confidenceThreshold`로 확인하세요. 신뢰도는 AI 자체 평가이며 정답 확률이 아닙니다.

### items 항목

기본단위 `defaultUnit`이 `"없음"`이면 해당 구매옵션의 단위 설정 전체를 미입력으로 처리합니다. 앞뒤 공백은 제거해 비교하며 단위 선택지가 함께 있어도 설정을 적용하지 않습니다. 이 경우 원문에서 판단한 단위를 그대로 반환하고, 값이 추출되지 않으면 최종 결과에 `"없음"`을 넣습니다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `optionId` | string | 요청 단품 ID |
| `purchaseOptions` | object | 구매옵션명 → 추출 값. 키와 값은 모두 문자열 |

AI가 매핑하지 않은 허용 구매옵션 중 기본단위 설정이 없는 항목은 최종 `items.purchaseOptions`에서 `"없음"`으로 채웁니다. 단품별로 적용하며, 기본단위가 설정된 누락 항목은 기존처럼 생략합니다. 예를 들어 길이·수량·색상을 요청했는데 수량만 추출했고 길이·색상에 기본단위 설정이 없다면 `{"길이":"없음","수량":"10개","색상":"없음"}`을 반환합니다. `"없음"`은 값이 추출되지 않았다는 표시입니다. `optionMappings`와 `aiAssessment.mappings`에는 AI가 실제 반환한 매핑만 포함되므로 서버가 채운 항목의 신뢰도나 근거를 만들지 않습니다. 검토·오류 응답은 기존처럼 빈 `items`를 반환합니다.

```json
{"optionId": "1", "purchaseOptions": {"색상": "블랙", "패션의류/잡화 사이즈": "90"}}
```

### optionMappings 항목

길이에 기본단위 설정이 없으면 `88 X 110 X 12mm` 같은 복합 치수는 전체를 하나의 길이 규격 문자열로 반환하도록 AI에 지시합니다. 숫자 하나로 축약하거나 합산하지 않습니다. 단품이 하나이고 정보고시의 제품 치수가 해당 단품에 적용되는 경우 그 치수를 근거로 사용할 수 있습니다. 여러 단품의 공통 치수는 각 단품과의 대응이 명확할 때만 사용합니다. 원문끼리 충돌하거나 치수가 불명확하면 기존 검토 규칙을 따릅니다. 이 규칙은 실제 AI 모드에 적용되며 테스트 모드는 복합 치수를 추출하지 않습니다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `optionId` | string | 원본 단품 ID |
| `sourceOptionName` | string | 요청의 원본 단품 옵션명 |
| `targetPurchaseOptionName` | string | 허용 구매옵션명 |
| `value` | string | 추출 값 |
| `confidence` | number | 개별 신뢰도 |
| `evidenceSource` | string 또는 null | AI 스키마의 출처는 `goodsName`, `productNoticeText`, `productCompositionText` 또는 null |
| `evidenceText` | string 또는 null | AI가 제안한 근거. 원문 일치 여부를 서버가 보증하지 않음 |
| `calculation` | object 또는 null | AI가 제안한 연산 설명. 서버 재계산 결과가 아님 |

### aiAssessment / serverAssessment

| 객체·필드 | 타입 | 설명 |
| --- | --- | --- |
| `aiAssessment.certain` | boolean 또는 null | AI의 확실성 판단 또는 검토 전환 결과 |
| `aiAssessment.confidence` | number 또는 null | 제안 전체 신뢰도. 최상위 최저 신뢰도와 다를 수 있음 |
| `aiAssessment.mappings` | object[] 또는 null | 제안 매핑. 항목 필드는 `optionMappings`와 같으나 `sourceOptionName` 없음. 실패 정보에서는 null 항목이나 잘못된 값이 남을 수 있음 |
| `aiAssessment.reason` | string 또는 null | AI 판단 또는 검토 사유 |
| `serverAssessment.decisionCode` | string | `ACCEPTED`, `AI_UNCERTAIN`, `LOW_CONFIDENCE`, `RESULT_VALIDATION_FAILED`, `TEST_MODE` 또는 예외 오류 코드 |
| `serverAssessment.reason` | string | 서버 승인·보류 사유 |
| `serverAssessment.confidenceThreshold` | number | 서버의 최종 승인 신뢰도 기준 |

최종 AI 검증 실패 시 추론 어댑터가 `certain=false`와 검토 사유로 제안을 바꿀 수 있습니다. 따라서 `aiAssessment`는 AI 응답 원문 그대로라고 가정하면 안 됩니다. 파싱하지 못한 응답은 빈 제안 목록으로 나타날 수 있습니다. 낮은 신뢰도로 보류한 경우에는 `aiAssessment.certain=true`가 남아 있을 수도 있습니다.

### calculation

`키친타올 140매x12롤`은 개당 수량 `140매`, 판매 롤 수 `12롤`로 구분합니다. 개당 수량 선택지에 `매`가 있고 수량 선택지에 `롤`이 없으며 기본단위가 `개`이면 `140매`와 `12개`로 반환하도록 AI에 지시합니다. 테스트 모드도 단품 하나의 명시된 매수×롤수 패턴을 지원합니다. 추가 팩 수가 있는 다층 구성은 이 단순 패턴으로 추정하지 않습니다.

`22m x 30롤 x 4팩(총 120롤)`처럼 길이·포장당 개수·판매 포장 수가 함께 있는 원문은 서로 다른 값을 구분하도록 AI에 지시합니다. 개당 수량 단위 선택지에 `롤`이 있으면 `30롤`, 길이 선택지에 `m`이 있으면 `22m`, 수량 선택지에 `팩`이 없고 기본단위가 `개`이면 `4개`를 반환합니다. `총 120롤`은 전체 롤 수이며 길이 `120m`나 포장당 수량을 뜻하지 않습니다. 단일상품이라는 옵션명도 수량 `1`의 근거가 아닙니다.

테스트 모드도 단품 하나의 옵션명이 `단일상품` 또는 `단품`이고 `길이 × 포장당 롤 수 × 판매 포장 수`가 명시된 패턴을 지원합니다. 단위 선택지·기본단위를 적용하고 원문 단위를 계산 근거에 보존합니다. 여러 단품에 공통 구성을 임의 적용하거나 자료 간 충돌·전체 롤 수 불일치를 추정해 해결하지 않습니다. 결과는 `TEST_MODE`, `success=false`, `autoApplyCandidate=false`이며 실제 AI 호출 사용량은 없습니다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `operation` | string | `DIRECT`, `CONVERT`, `SUM`, `PACK_COUNT`, `PACK_CONTENT` |
| `outputUnit` | string | 출력 단위 |
| `operands` | object[] | 각 피연산자는 문자열 `amount`, 문자열 `unit`, 객체 `evidence` |
| `context` | object 또는 null | 구성 판단의 근거 |

`evidence`와 `context`는 문자열 `source`, `text`를 갖습니다. AI 스키마의 source는 `goodsName`, `productNoticeText`, `productCompositionText`, `optionName1` 중 하나입니다. DIRECT는 직접 추출, CONVERT는 단위 변환, SUM은 합산, PACK_COUNT는 묶음 수량, PACK_CONTENT는 묶음 내부 수량을 뜻합니다. 의미·원문 근거·연산 정확성은 AI의 판단이며 서버가 직접 재검증하지 않습니다.

### aiUsage

응답의 토큰·비용 필드는 **snake_case**입니다. 서버 내부 JSONL 로그의 camelCase 필드와 혼동하지 마세요.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `model` | string | 응답 모델명. 확인 불가하거나 허용된 모델명 형식 밖이면 `unknown` |
| `mode` | string | 호출에 사용한 `LIGHT` 또는 `FULL`. 클라이언트 선택값이 아님 |
| `attempt` | integer | 최초 호출 1, 추가 상세 분석 2 |
| `input_tokens` | integer 또는 null | API 응답 usage의 입력 토큰 |
| `cached_tokens` | integer 또는 null | API가 제공한 캐시 입력. 입력 토큰에 이미 포함됨 |
| `output_tokens` | integer 또는 null | API 응답 usage의 출력 토큰 |
| `total_tokens` | integer 또는 null | API 응답 usage의 전체 토큰 |
| `elapsedMs` | integer 또는 null | 서버에서 측정한 해당 AI 호출 소요시간(ms). JSONL과 동일한 값이며 전체 요청·서버 검증 시간은 포함하지 않음 |
| `estimated_cost_usd` | number 또는 null | 서버의 외부 단가 설정으로 계산한 예상 USD 비용 |
| `inferenceId` | string 또는 null | 같은 상품 추론 요청의 호출을 연결하는 UUID. OpenAI request ID가 아님 |
| `initialPromptMode` | string | 최초 선택 모드 |
| `finalPromptMode` | string | 현재 호출 기준 마지막 모드 또는 예정 모드. FULL 전환 예정 LIGHT 행도 FULL |
| `retryCount` | integer | 최초 0, 추가 분석 1 |
| `retryReasons` | string[] | 진단용 검증·전환 사유 코드. 응답 처리 분기는 최종 승인 필드를 기준으로 함 |
| `status` | string 또는 null | 호출별 `SUCCESS`, `VALIDATION_ERROR`, `REVIEW_REQUIRED`. 상품 요청의 최종 승인과 별도로 해석 |

null은 확인 불가이며 실제 0과 다릅니다. 여러 호출의 사용량은 합산하되 캐시 토큰을 입력 토큰에 다시 더하지 마세요. 합산 대상 중 null이 있으면 알려진 값의 부분합과 완전한 합계를 구분하세요. 단가·입력·캐시·출력 중 필요한 값이 없으면 예상 비용은 null입니다.

API 호출 중 예외가 발생하면 응답은 `aiUsage=[]`입니다. 첫 번째 호출이 과금된 뒤 추가 호출이 실패해도 응답에서 첫 호출의 사용량을 받을 수 없습니다. 빈 배열을 무료 호출의 증거로 사용하지 마세요. 서버 운영 담당자는 내부 호출 이력을 확인할 수 있습니다.

## 7. Response 예제

### 실제 AI 승인 형태

다음은 5절 의류 요청에서 `options`를 ID 1 하나로 줄였을 때의 설명용 응답입니다. 네 단품 요청 전체에 대한 응답을 의미하지 않습니다. 해시·버전·사유·토큰·비용은 예시입니다.

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
  "items": [
    {
      "optionId": "1",
      "purchaseOptions": {
        "색상": "블랙",
        "패션의류/잡화 사이즈": "90"
      }
    }
  ],
  "reason": "원본 옵션명에서 색상과 사이즈를 확인했습니다.",
  "validationErrors": [],
  "errorCode": null,
  "inputHash": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "promptVersion": "coupang-option-v27",
  "inferenceSource": "AI",
  "aiAssessment": {
    "certain": true,
    "confidence": 0.95,
    "mappings": [
      {
        "optionId": "1",
        "targetPurchaseOptionName": "색상",
        "value": "블랙",
        "confidence": 0.95,
        "evidenceSource": null,
        "evidenceText": null,
        "calculation": null
      },
      {
        "optionId": "1",
        "targetPurchaseOptionName": "패션의류/잡화 사이즈",
        "value": "90",
        "confidence": 0.95,
        "evidenceSource": null,
        "evidenceText": null,
        "calculation": null
      }
    ],
    "reason": "원본 옵션명에서 색상과 사이즈를 확인했습니다."
  },
  "serverAssessment": {
    "decisionCode": "ACCEPTED",
    "reason": "AI의 확실 판정, 신뢰도 기준 및 서버의 기본 구조 검증을 통과했습니다. 값과 근거는 AI 판단을 사용합니다.",
    "confidenceThreshold": 0.8
  },
  "aiUsage": [
    {
      "model": "gpt-4.1-mini-2025-04-14",
      "mode": "LIGHT",
      "attempt": 1,
      "input_tokens": 2000,
      "cached_tokens": 0,
      "output_tokens": 500,
      "total_tokens": 2500,
      "estimated_cost_usd": 0.0016,
      "inferenceId": "5d6d0000-0000-4000-8000-000000000001",
      "initialPromptMode": "LIGHT",
      "finalPromptMode": "LIGHT",
      "retryCount": 0,
      "retryReasons": [],
      "status": "SUCCESS"
    }
  ]
}
```

### 구성 근거와 calculation 상세 발췌

선크림 요청의 수량 매핑 설명용 예제입니다. 실제 AI가 다른 근거를 선택할 수도 있습니다.

```json
{
  "optionId": "1",
  "sourceOptionName": "단일상품",
  "targetPurchaseOptionName": "수량",
  "value": "7개",
  "confidence": 0.95,
  "evidenceSource": "productCompositionText",
  "evidenceText": "본품 선크림 50ml 7개",
  "calculation": {
    "operation": "DIRECT",
    "outputUnit": "개",
    "operands": [
      {
        "amount": "7",
        "unit": "개",
        "evidence": {
          "source": "productCompositionText",
          "text": "본품 선크림 50ml 7개"
        }
      }
    ],
    "context": null
  }
}
```

### 추가 분석도 검토 필요인 경우 — 주요 필드 발췌

최종 적용 목록은 비어 있지만 검토용 제안은 남을 수 있습니다. 전체 응답에는 해시·버전·출처·`aiUsage` 등 나머지 필드도 포함됩니다.

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
    "certain": false,
    "confidence": 0.95,
    "mappings": [
      {
        "optionId": "1",
        "targetPurchaseOptionName": "색상",
        "value": "블랙",
        "confidence": 0.95,
        "evidenceSource": null,
        "evidenceText": null,
        "calculation": null
      }
    ],
    "reason": "REVIEW_REQUIRED: [CERTAIN_FALSE]; certain=false; AI: 원문만으로 판단하기 어렵습니다."
  },
  "serverAssessment": {
    "decisionCode": "AI_UNCERTAIN",
    "reason": "AI가 certain=false로 판단하여 서버가 적용을 보류했습니다.",
    "confidenceThreshold": 0.8
  }
}
```

### 추가 분석 시 aiUsage 발췌

두 호출의 UUID는 같고 사용량은 각각 기록됩니다. 첫 행의 `VALIDATION_ERROR`만 보고 최종 실패로 처리하지 마세요.

```json
[
  {
    "model": "gpt-4.1-mini",
    "mode": "LIGHT",
    "attempt": 1,
    "input_tokens": 1000,
    "cached_tokens": 800,
    "output_tokens": 200,
    "total_tokens": 1200,
    "estimated_cost_usd": 0.00048,
    "inferenceId": "5d6d0000-0000-4000-8000-000000000001",
    "initialPromptMode": "LIGHT",
    "finalPromptMode": "FULL",
    "retryCount": 0,
    "retryReasons": [
      "MISSING_OPTION_MAPPING",
      "INVALID_RESPONSE"
    ],
    "status": "VALIDATION_ERROR"
  },
  {
    "model": "gpt-4.1-mini",
    "mode": "FULL",
    "attempt": 2,
    "input_tokens": 1000,
    "cached_tokens": 800,
    "output_tokens": 200,
    "total_tokens": 1200,
    "estimated_cost_usd": 0.00048,
    "inferenceId": "5d6d0000-0000-4000-8000-000000000001",
    "initialPromptMode": "LIGHT",
    "finalPromptMode": "FULL",
    "retryCount": 1,
    "retryReasons": [
      "MISSING_OPTION_MAPPING",
      "INVALID_RESPONSE"
    ],
    "status": "SUCCESS"
  }
]
```

## 8. 오류 코드 / HTTP Status

| HTTP | errorCode | 의미·권장 대응 |
| --- | --- | --- |
| 200 | null | 실제 AI 결과 승인. 두 승인 플래그를 확인하고 `items` 사용 |
| 200 | `TEST_MODE` | 모의 결과. 실제 적용 금지 |
| 200 | `REVIEW_REQUIRED` | 최종 AI 불확실·검증 실패 또는 신뢰도 기준 미달. 입력과 판단 사유 검토 |
| 200 | `RESULT_VALIDATION_FAILED` | 결과 구조·조립 검증 실패. 테스트 모드 및 서비스의 방어 분기에서 반환 가능 |
| 400 | `INVALID_REQUEST` | 필수·길이·개수 제약, 중복·단위 설정 또는 쿼리 변환 오류 |
| 400 | `INVALID_JSON` | 잘못된 JSON, 모르는 필드, 본문 구조·타입 오류 등 |
| 401 | `UNAUTHORIZED` | 서비스 API Key 없음·빈 값·오류·중복 Header. 호출자 Key 설정 확인 |
| 429 | `RATE_LIMIT_EXCEEDED` | 서비스 요청 한도 초과. `Retry-After` 대기 후 다시 호출 |
| 429 | `AI_RATE_LIMIT` | 외부 AI 요청 제한 |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 서버 예외 |
| 502 | `AI_UPSTREAM_ERROR` | 외부 AI 호출 실패. 외부의 다른 HTTP 오류도 이 코드로 변환 |
| 503 | `AI_NOT_CONFIGURED` | 서버의 OpenAI 키 미설정. 서버 담당자 확인 |
| 504 | `AI_TIMEOUT` | 감지된 시간 초과 또는 외부 408·504 |

표는 현재 컨트롤러·서비스·예외 처리기에서 확인되는 코드입니다. 잘못된 URL·메서드·Content-Type, 프록시/배포 플랫폼 오류까지 같은 JSON 계약을 보장하는 목록은 아닙니다. 현재 포괄 예외 처리기는 처리되지 않은 프레임워크 예외도 `INTERNAL_ERROR`로 바꿀 수 있으므로 임의로 404·405·415 등의 JSON 계약을 가정하지 마세요.

AI가 돌려준 JSON을 파싱하지 못하거나 응답이 완료되지 않았으면 현재 추론 경로에서 검토 결과로 처리합니다. 해당 파싱 예외의 내부 코드 `AI_INVALID_JSON`을 일반적인 외부 오류 계약으로 사용하지 마세요. 상세 경로는 내부 문서에 정리되어 있습니다.

### 잘못된 요청 — 전체 응답 설명용 예제

중복 단품 ID로 요청 검증이 실패한 경우입니다. 예외 처리 응답은 상품 ID·해시를 보존하지 않습니다.

```json
{
  "goodsId": null, "success": false, "autoApplyCandidate": false, "confidence": 0,
  "optionMappings": [], "items": [],
  "reason": "입력 데이터를 확인하세요.",
  "validationErrors": ["optionId는 중복될 수 없습니다."],
  "errorCode": "INVALID_REQUEST", "inputHash": null,
  "promptVersion": "coupang-option-v27", "inferenceSource": "NONE",
  "aiAssessment": null,
  "serverAssessment": {"decisionCode": "INVALID_REQUEST", "reason": "입력 데이터를 확인하세요.", "confidenceThreshold": 0.8},
  "aiUsage": []
}
```

업무 보류 결과와 통신 실패를 구분하세요. 클라이언트 자동 재호출은 서버가 제공하는 계약이 아닙니다. 응답을 못 받았더라도 이미 AI 호출·과금이 진행되었을 수 있으며 같은 입력을 다시 보내면 별도 요청으로 처리합니다.

## 9. 테스트 모드

`testMode=true`는 OpenAI를 호출하지 않으므로 API 키와 AI 비용이 필요하지 않습니다. 기본 색상·숫자 사이즈·핏, 일부 명시된 수량·용량·중량·화면크기 패턴을 모의 추출합니다. 복잡한 구성이나 실제 AI 정확도를 검증하는 기능이 아닙니다.

모의 결과 검증을 통과하면 `errorCode=TEST_MODE`, `inferenceSource=TEST`, `success=false`, `autoApplyCandidate=false`, `confidence=0`, `aiAssessment=null`, `aiUsage=[]`입니다. 이 경우에만 모의 `items`와 `optionMappings`가 제공됩니다.

검증에 실패하면 HTTP 200의 `RESULT_VALIDATION_FAILED`와 빈 최종 목록을 반환합니다. 요청이 잘못된 경우에는 실제 AI 모드와 마찬가지로 HTTP 400입니다. 모의 모드에서도 필수 상품정보고시를 보내세요. 내부 카테고리는 선택 입력입니다.

## 10. 결과 처리 예제

같은 서버의 화면에서 사용하는 JavaScript 예제입니다. 다른 시스템에서는 확인된 기본 주소를 붙이세요. 브라우저의 다른 origin에서 호출할 경우 별도의 CORS 구성이 필요할 수 있으며 현재 프로젝트는 CORS 허용 설정을 제공하지 않습니다.

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
  // 프록시 오류 등 비JSON 응답과 네트워크 예외는 호출자가 별도로 처리합니다.
  let result;
  try {
    result = await response.json();
  } catch {
    throw new Error(`JSON 응답을 확인할 수 없습니다. HTTP ${response.status}`);
  }
  if (!response.ok) {
    throw new Error(`${result.errorCode ?? 'HTTP_ERROR'}: ${result.reason ?? response.status}`);
  }
  if (result.inferenceSource === 'TEST' || result.errorCode === 'TEST_MODE') {
    return {kind: 'TEST', previewItems: result.items ?? [], result};
  }
  if (result.success === true && result.autoApplyCandidate === true) {
    // 필요하면 후속 시스템의 필수 구매옵션도 여기서 확인합니다.
    return {kind: 'APPLY_CANDIDATE', items: result.items, result};
  }
  // REVIEW_REQUIRED / RESULT_VALIDATION_FAILED: 제안 매핑을 적용하지 않습니다.
  return {
    kind: 'REVIEW',
    reason: result.serverAssessment?.reason ?? result.reason,
    errors: result.validationErrors ?? [],
    result
  };
}
```

이 예제는 적용 후보 데이터를 반환할 뿐 쿠팡 등록·수정을 실행하지 않습니다. 오류 메시지와 판단 사유는 진단용 문구이므로 내용 전체를 파싱해 업무 상태를 판단하지 마세요.

## 11. 참고 사항 / 보안·운영 주의

**서비스 API Key 인증과 호출량 제한이 기본 활성화됩니다.** 기본 로컬 주소는 127.0.0.1이며 Docker 배포 이미지는 0.0.0.0으로 수신합니다. 운영에서는 `AI_AGENT_API_KEY`를 서버 Secret으로 설정하고 HTTPS로 호출하세요. 인증 ON에서 Key가 미설정이면 서버 시작이 실패합니다.

브라우저 테스트 페이지에는 서비스 Key를 보관하거나 자동으로 전달하지 않습니다. 로컬에서는 dev 프로필 또는 `AI_AGENT_SECURITY_ENABLED=false`로 테스트할 수 있습니다. 운영에 dev 프로필을 적용하지 마세요. 비용 제한이나 다중 인스턴스의 중앙 호출 제한은 운영 환경에서 별도로 마련해야 합니다.

서버는 호출별 최소 사용량 메타데이터를 JSONL로 기록할 수 있습니다. 클라이언트는 로그 파일에 직접 접근할 필요가 없으며 응답의 `aiUsage`를 사용합니다. 장애 문의는 상품 ID·실행 시각·오류 코드·판단 사유를 전달하세요. 예외 응답에 inferenceId가 없을 수 있습니다.

AI 프롬프트에 전달되는 상품정보와 호출 이력의 기록 범위는 다릅니다. 로그에 원문이 없다고 AI에 원문이 전달되지 않는 것은 아닙니다. 상품정보를 실제 AI 모드로 보낼 때 이 점을 고려하세요.

내부 선택 기준·재추론·프롬프트·스키마·검증·로그·설정은 별도 **API 구매옵션 추론 내부 설계/운영 문서**를 참고하세요. 화면 상단의 내부 설계/운영 링크 또는 `/ai-operations.html`에서 열 수 있습니다. 저장소 원문은 `src/main/resources/static/ai-operations.md`입니다.
