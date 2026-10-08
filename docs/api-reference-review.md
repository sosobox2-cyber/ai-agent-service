# 구매옵션 매핑 API 명세 검토 기록

2026-10-09, 현재 저장소 코드 기준입니다. 배포 서버를 호출하거나 유료 OpenAI 추론을 실행하지 않았습니다. 문서 정리를 위해 API 동작·DTO·프롬프트·설정·테스트 코드는 변경하지 않았습니다.

## 문서 구성

| 변경 파일 | 내용 |
| --- | --- |
| src/main/resources/static/api-reference.md | API 연동 중심 전면 재구성 |
| src/main/resources/static/ai-operations.md | 내부 설계/운영 정본 신규 작성 |
| src/main/resources/static/api-reference.html | 내부 문서 탐색 링크 |
| src/main/resources/static/ai-operations.html | 내부 문서 보기 페이지 신규 작성 |
| src/main/resources/static/user-guide.css | 이동한 Request 컬럼 폭, 사용설명서 전용 글꼴 범위 |
| README.md | 두 문서와 검토 기록의 진입점 |
| docs/ai-inference-failover.md | 실제 JSONL 필드로 수정 |
| docs/ai-token-optimization.md | 실제 JSONL 필드로 수정, 과거 측정 기록 보존 |
| docs/api-reference-review.md | 코드 대조·불일치·운영 TODO 기록 |

- [API 연동 명세서](../src/main/resources/static/api-reference.md): Endpoint, 결과 처리 규칙, 요청·응답, 예제, 오류, 테스트 모드, 인증 주의
- [AI 내부 설계/운영](../src/main/resources/static/ai-operations.md): 선택·재추론·승인·프롬프트·스키마·검증 한계·usage·JSONL·설정·배포 TODO
- `/api-reference.html`에서 `/ai-operations.html`로 이동하는 문서 탐색 링크 추가
- API 명세의 Request가 4절로 이동하여 문서 CSS의 타입·필수 컬럼 폭 대상도 4절로 수정
- 기존 failover·토큰 최적화 자료의 잘못된 JSONL 필드 설명 수정. 과거 측정 자료와 실행 방법은 보존

## 대조 결과

| 항목 | 코드 근거 | 확인 / 문서 반영 |
| --- | --- | --- |
| Endpoint / Method / testMode | PurchaseOptionController | POST /api/v1/coupang/purchase-options/infer, query boolean 기본 false. 본문은 상품 객체 |
| JSON 계약 | spring.jackson 설정, GlobalExceptionHandler | application/json 호출 예제, 모르는 필드 INVALID_JSON. 일반 MVC·플랫폼 오류까지 같은 HTTP/JSON 계약을 보장하지 않음 |
| 요청 DTO | InferenceRequest / SourceOption / PurchaseOptionUnit | 필수 6개, 선택 2개. 상품 ID100·상품명/카테고리500·정보고시/구성20,000. 허용 이름/단품/단위설정200, 단위 선택지30 |
| 업무 검증 | RequestValidator | 정확한 문자열 중복·포함 검사, ID 중복 금지, 단위 앞뒤 공백 금지. 기본단위는 선택지 밖 허용 |
| success / 후보 | PurchaseOptionInferenceService | 최종 승인 시 둘 다 true. HTTP 성공과 구분. 실제 상품 등록/수정 없음 |
| items / optionMappings | assemble / optionMappings | 요청 단품 순서의 최종 결과와 원본 포함 상세. 보류·결과 실패 시 빈 목록, TEST_MODE에는 미승인 모의 결과 가능 |
| AI / 서버 판단 | diagnose / review / assess | AI 제안은 최종 승인과 별개. 어댑터가 certain·reason을 바꿀 수 있음. 검토에는 null/잘못된 제안이 남을 수 있음 |
| 누락 / 조합 | ResultValidator | 각 ID의 최소 한 매핑, 허용 이름, 중복 매핑·동일 최종 조합 검사. 모든 허용 구매옵션 채움은 요구하지 않음 |
| 신뢰도 | assess / AiInferenceValidator | 최저 confidence, 최종 기본0.80와 재추론 옵션0.7을 분리. 자체 평가이고 정답 확률 아님 |
| LIGHT / FULL | PromptSelector / Provider | 단순 이름 집합·단위설정·단품/단일상품으로 선택. 포괄적인 의미 난도 판별 없음. AUTO/FULL 서버 설정 |
| Failover | PurchaseOptionAiService / AiRetryProperties | 검증 실패 LIGHT만 설정에 따라 FULL 1회. 통신 장애 재시도 없음. 낮은 신뢰도만으로 기본 재추론하지 않음 |
| 단위 / calculation | UnitSelection / ResultValidator | 숫자+허용/기본단위 형식 및 outputUnit 일치. 숫자·단위 자동 변경·연산 재계산 없음 |
| 구성 원문 | user prompt / DTO / InputHashService | 선택·null/빈 문자열 허용, 원문을 AI 참고로 제공, 해시에 포함. 본품/사은품·충돌 판단은 프롬프트 지침 |
| Usage | AiCallUsage / AiUsageLogger / AiUsagePricing | 응답 snake_case·로그 camelCase, 실제 SDK usage, 캐시 없으면 null. 외부 단가의 예상 비용. 예외 응답 usage는 빈 배열 |
| 오류 / Status | SafeErrorHandler / GlobalExceptionHandler / 서비스 | 400·429·500·502·503·504 및 HTTP200 업무 판단. 파싱 실패는 보통 INVALID_RESPONSE 검토 경로 |
| 로그 | AiPurchaseOptionUsageLog / AiUsageLogger | 실제 DTO 필드, 분리 Appender, 일별 gzip·30일/압축1GB, 실패 WARN 격리 |
| 인증 / 배포 | pom.xml / config / Controller / Compose / Dockerfiles | 앱 인증·호출량 제한·CORS 설정 없음. 로컬 기본127.0.0.1, 이미지 내부0.0.0.0. 플랫폼의 보호 여부는 확인 불가 |

## 바로잡은 설명과 정보 이동

1. JSONL의 `validationErrors`, `minimumMappingConfidence`는 현재 DTO에 없어 해당 수집 설명을 수정했습니다. FULL 행의 retryReasons는 최초 LIGHT 전환 사유이며 FULL 자체의 모든 검증 사유를 로그에서 복원할 수 없습니다.
2. 기존 성공 예제의 aiUsage에 빠진 inferenceId·initialPromptMode·finalPromptMode·retryCount·retryReasons·status를 채웠습니다. 4단품 요청과 1단품 응답 예제의 관계를 명시하고 해시는 64자리 설명용 값으로 변경했습니다.
3. 기본 주소를 확정된 운영 사실로 표현하지 않았습니다. 기존 배포 URL은 보존하되 가용성·버전·플랫폼 인증은 저장소 검토로 확인할 수 없다고 표시했습니다.
4. 공백 '불가'를 공백뿐인 문자열 불가와 단위 앞뒤 공백 불가로 구분했습니다. 일반 필드의 앞뒤 공백은 자동 제거되지 않습니다.
5. 모든 허용 이름의 매핑을 보장하지 않음, calculation이 서버 재계산 결과가 아님, inputHash가 캐시/idempotency 보장이 아님을 명시했습니다.
6. LIGHT 선택을 '모호한 요청은 FULL'로 일반화하지 않고 실제 단순 집합·옵션명·단위 설정 기준을 내부 문서에 기록했습니다.
7. PromptSelector, Failover 사유 코드·retry properties·두 confidence 기준·User/System Prompt 및 JSON Schema 구조·구성 해석 지침·JSONL 경로/설정은 내부 문서로 분리했습니다. 응답에 노출되는 aiUsage 계약과 호출 비용 주의는 API 문서에 남겼습니다.

## 구현상 추가 발견 / 운영 TODO

- Calculation DTO의 주석에는 서버가 원문 대조·재계산한다고 적혀 있지만 실제 AI 승인 경로는 그렇게 검증하지 않습니다. 유틸리티 존재와 실제 호출 경로를 구분했고 주석이나 동작은 변경하지 않았습니다.
- 단위 숫자 정규식은 0도 허용합니다. 양수 설명만으로 0이 거부된다고 가정하면 안 됩니다.
- GlobalExceptionHandler의 포괄 처리 때문에 미처리 메서드·Content-Type 등 MVC 예외도 INTERNAL_ERROR로 변환될 수 있습니다. 표 밖의 404/405/415 계약을 추측해서 추가하지 않았습니다.
- 키 미설정으로 model.call이 실패해도 API_ERROR 로그가 만들어질 수 있어 JSONL 호출 시도 수가 곧 과금된 원격 호출 수는 아닙니다.
- 인증 없는 외부 공개는 실제 AI 비용을 발생시킬 수 있습니다. 운영 공개 전 인증/접근 제한 및 호출량·비용 제한을 마련해야 합니다. 현재 배포 플랫폼에 별도 보호가 있는지는 확인되지 않습니다.
- 이미지의 /app는 root가 만들고 app 사용자로 실행하며 logs 디렉터리·권한·볼륨을 구성하지 않습니다. 기본 로그 경로에 기록할 수 없거나 컨테이너 교체로 로그가 유실될 수 있습니다. 쓰기 가능 경로·영속 보관을 배포 환경에서 확인해야 합니다.
- 응답을 받지 못한 뒤 재호출하면 별도 요청·과금이 발생할 수 있습니다. 현재 중복 요청 억제 기능은 없습니다.
- 이번 NoApiKeyStartupTest 실행 중 기본 로그 파일의 날짜별 Rolling에서 rename 경고를 관찰했습니다. 파일 잠금·동일 파일 공유·권한 등 원인은 이번 문서 작업에서 확정하지 않았습니다. 테스트 통과가 운영 파일의 정상 보관까지 보증하지 않으므로 별도 확인이 필요합니다.

## 검증 범위

기존 API·요청 검증·Failover·AI 어댑터·Usage·구성 관련 테스트 94개와 프롬프트 선택·최종 승인·단위·해시·키 미설정 테스트 44개, 총 138개가 모두 성공했습니다. 실패·오류·건너뜀은 0개입니다. 유료 실 API·배포 서버 상태는 검증하지 않았습니다.

문서 JSON 예제 9건의 구문과 요청 필드·전체 응답 필드·aiUsage·JSONL의 DTO 필드명을 검사했습니다. 결과 처리 JavaScript 예제의 승인·검토·결과 검증 실패·테스트·HTTP 오류·비JSON 오류 6개 분기를 확인했습니다. 공용 설명서 렌더러의 DOM 구조로 각각 11개 목차와 앵커·API 표 14개·내부 문서 표 7개를 확인했습니다. 브라우저 스크린샷을 통한 시각 검증은 수행하지 않았습니다. 문서용 HTML/CSS만 조정했으며 Java·API 계약·프롬프트·application.yml은 이번 작업에서 변경하지 않았습니다.
