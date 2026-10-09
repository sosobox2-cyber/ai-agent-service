# LIGHT → FULL 자동 재추론

v27에서는 각 호출 모드의 Schema를 사용합니다. LIGHT calculation은 null 전용이며 FULL 재추론은 기존 calculation Schema를 사용합니다. 선택 및 재추론 조건은 유지합니다. [변경 기록](light-token-optimization.md)

LIGHT 결과가 서버 검증에 실패한 경우에만 같은 상품 데이터로 FULL을 한 번 호출합니다. 기존 누락 보정 대화는 이 흐름으로 교체했습니다. PromptSelector는 시작 시 한 번 호출하고 재추론은 FULL을 직접 지정합니다. System Prompt, User Prompt, JSON Schema와 선택 기준은 변경하지 않습니다 (v27: LIGHT calculation=null; FULL Schema retained).

| 시작과 결과 | 최대 AI 호출 수 |
|---|---:|
| 정상 LIGHT | 1 |
| LIGHT 검증 실패 | 2 |
| 처음부터 FULL | 1 |
| LIGHT API 장애 | 1 |

FULL 결과도 같은 검증을 수행하며 실패하면 `errorCode=REVIEW_REQUIRED`, `success=false`, `autoApplyCandidate=false`, `aiAssessment.certain=false`를 반환합니다. 적용용 `items`와 `optionMappings`는 비우고 `reason`에 검증 코드·사유를 남깁니다. FULL 호출 자체가 실패하면 기존 API 오류 코드·HTTP 상태를 유지합니다.

## 검증

`AiInferenceValidator`가 `AiInferenceValidationResult`의 오류 목록을 반환합니다. 각 오류는 `ValidationError(code, message)`이며 여러 사유를 모두 보존합니다.

| 코드 | 조건 |
|---|---|
| CERTAIN_FALSE | certain=false |
| MISSING_OPTION_MAPPING | 입력 optionId의 매핑 누락 |
| UNKNOWN_OPTION_ID | 입력에 없는 optionId |
| INVALID_PURCHASE_OPTION | allowedPurchaseOptions에 없는 이름 |
| DUPLICATE_MAPPING | 같은 (optionId, targetPurchaseOptionName) 중복 |
| EMPTY_VALUE | mapping이 null 또는 필수 optionId·targetPurchaseOptionName·value가 null/blank |
| INVALID_RESPONSE | JSON 해석 실패·응답 미완료, 필수 응답 필드·confidence 형식 오류, 기존 단위 및 단품 조합 검증 실패 |
| LOW_CONFIDENCE | 선택적 재추론 confidence 기준을 활성화한 경우에만 사용 |

기존 `ResultValidator`의 구조·단위·조립 결과 검증도 재사용합니다. allowedPurchaseOptions의 모든 이름을 반환할 필요는 없지만 모든 입력 optionId에 최소 하나의 매핑이 있어야 합니다.

timeout, 429, 5xx, 네트워크 오류는 검증 실패로 처리하지 않습니다. 기존 OpenAI 클라이언트의 `maxAttempts=1`도 유지합니다. 반복문·재귀 없이 최초 호출과 조건부 FULL 호출만 있으며, `max-retries`는 0 또는 1만 허용합니다.

## 설정

```yaml
purchase-option:
  ai:
    retry:
      enabled: true
      max-retries: 1
      confidence-threshold-enabled: false
      confidence-threshold: 0.7
```

`enabled=false` 또는 `max-retries=0`이면 재호출하지 않습니다. 기본값에서는 낮은 confidence만으로 재호출하지 않습니다. 선택적 기준을 켜면 전체·개별 매핑의 최저 confidence를 사용하며 threshold와 같은 값은 통과합니다.

기존 최종 자동 적용 기준 `app.inference.confidence-threshold=0.80`은 별개로 유지합니다. 예를 들어 구조가 정상인 LIGHT 결과의 confidence가 0.5라면 FULL 재호출 없이 최종 검토 필요로 반환합니다. 이는 JSONL에서 `validationPassed=true`, `status=REVIEW_REQUIRED`로 구분됩니다.

## 호출별 JSONL

기본 경로는 `logs/ai-usage.jsonl`입니다. `AI_USAGE_JSONL_PATH`로 경로를 변경하고 `AI_USAGE_JSONL_ENABLED=false`로 파일 기록을 끌 수 있습니다. 콘솔 메트릭 설정 `AI_USAGE_LOG_ENABLED`와 독립적으로 기록합니다. JSONL은 UTF-8이며 API 키·헤더·상품 본문·AI 응답 본문은 기록하지 않습니다. 검증 사유 코드만 기록하며 검증 메시지 원문은 기록하지 않습니다. 파일 기록 실패는 경고로 알리고 추론 결과에는 영향을 주지 않습니다.

각 행은 `timestamp`, `inferenceId`, `goodsId`, `promptMode`, `initialPromptMode`, `finalPromptMode`, `retryCount`, `retryReason`, `retryReasons`, `status`, `validationPassed`, `certain`, `confidence`, `model`, `inputTokens`, `cachedTokens`, `outputTokens`, `totalTokens`, `elapsedMs`, `mappingCount`, `estimatedCostUsd`를 포함합니다.

`retryReason`은 첫 번째 사유, `retryReasons`는 모든 사유입니다. FULL 행에서는 LIGHT의 전환 사유를 유지합니다. 현재 JSONL DTO에는 `validationErrors`가 없으므로 FULL 자체의 상세 검증 사유는 이 파일에서 복원할 수 없습니다. 응답의 `reason`, `validationErrors`, `serverAssessment`를 함께 확인하세요. `retryCount`는 최초 0, 재호출 1입니다. 두 행 모두 같은 `inferenceId`를 사용합니다. API 오류 행은 `status=API_ERROR`, `validationPassed=null`이며 수신하지 못한 usage·비용은 null로 기록합니다.

| status | 의미 |
|---|---|
| SUCCESS | 검증 및 기존 최종 신뢰도 기준 통과 |
| VALIDATION_ERROR | LIGHT 검증 실패, FULL 전환 예정 |
| REVIEW_REQUIRED | 추가 호출 없이 검토 필요로 최종 처리 |
| API_ERROR | 기존 API 오류 처리 |

응답 `aiUsage`의 기존 mode·attempt·usage 필드는 유지하고 `inferenceId`, `initialPromptMode`, `finalPromptMode`, `retryCount`, `retryReasons`, `status`를 추가합니다. `attempt`는 기존처럼 1, 2입니다. 호출 중 예외로 반환되는 API 오류 응답은 기존 계약대로 usage를 제공하지 않지만, 먼저 수신한 LIGHT usage는 JSONL에 보존됩니다.

## 통계 집계

```powershell
.\scripts\measure-ai-failover.ps1 -Path .\logs\ai-usage.jsonl
```

Windows 실행 정책으로 스크립트 실행이 제한된 환경에서는 현재 프로세스에만 적용되는 다음 명령을 사용합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\measure-ai-failover.ps1 -Path .\logs\ai-usage.jsonl
```

상품 추론 건수는 goodsId 대신 inferenceId로 집계합니다. 동일 상품을 여러 번 요청해도 각각의 요청을 구분합니다. LIGHT → FULL 비율은 `retryCount=1`인 호출 수 / LIGHT 시작 건수입니다. LIGHT 성공률은 최초 LIGHT 행에서 SUCCESS인 건수 / LIGHT 시작 건수입니다. 구조 검증 통과율은 `validationPassed=true`를 사용해 별도로 볼 수 있습니다.

최종 검토 필요는 각 inferenceId의 마지막 행이 REVIEW_REQUIRED인 건수입니다. API 오류와 로그가 FULL 전환 예정 행에서 끝난 미완료 요청은 별도로 집계합니다. FULL 재추론 성공은 retryCount=1 및 SUCCESS인 행 수입니다.

추가 비용은 retryCount=1인 행의 estimatedCostUsd 합계이며, 전체 비용에는 LIGHT와 FULL 양쪽 행을 모두 합산합니다. 비용·usage가 null인 행은 무료로 간주하지 않고 확인 불가 건수로 따로 표시합니다. 로그에 기록되는 비용은 기존 모델별 추정치이며 실제 청구서는 별도로 확인합니다.


현재 JSONL 필드·Logback Appender·일별 gzip Rolling·보관 정책은 [AI 호출 이력 안내](ai-call-history.md)를 참고하세요.
