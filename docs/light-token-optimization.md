# LIGHT 입력 토큰 최적화 (v27)

이번 변경은 LIGHT System Prompt와 응답 Schema에 한정한다. PromptSelector, User Prompt, 상품 JSON 및 productNoticeText, 서버 Validation, API DTO, usage 수집, 가격 정책은 유지한다.

## 규칙과 Schema

LIGHT에서 도달할 수 없는 수산물 개체당 중량, 화면크기, 설치지원방식, 모델명, 스탠드 및 관련 단일상품 규칙을 LIGHT에서 제외했다. 해당 규칙은 기존 FULL Prompt에 그대로 남아 있으며 FULL 파일은 변경하지 않았다. 공통 제품 치수·사이즈, 허용 오차, 정보고시 근거, 전체 단품 처리, 확실성·신뢰도·판단 이유 규칙은 유지했다.

LIGHT Schema는 기존 필드와 required를 유지하며 calculation만 `{"type":"null"}`로 제한한다. evidenceSource/evidenceText는 문자열과 null을 계속 허용한다. FULL Schema의 calculation 및 DIRECT/CONVERT/SUM/PACK_COUNT/PACK_CONTENT, operands/context/outputUnit은 변경하지 않았다. 각 호출의 모드로 Schema를 생성하므로 LIGHT 실패 후 FULL 호출에는 기존 FULL Schema가 전달된다.

Schema 내부 모든 객체 키를 재귀적으로 정렬한 LinkedHashMap으로 변환한다. enum, required, anyOf 등의 배열 순서와 내용은 유지한다. strict, additionalProperties 및 요청별 enum은 유지한다.

## 동일 의류 요청의 오프라인 비교

실제 SDK 요청을 모의 HTTP 서버에서 캡처하고 JTokkit o200k_base로 메시지 문자열과 직렬화된 Schema를 측정했다. 대표 입력은 examples/ai-request.json이며 변경 전후 User 메시지는 완전히 동일하다. API 내부 framing은 포함하지 않는다.

| 구성 | 변경 전 | 변경 후 | 감소 |
|---|---:|---:|---:|
| LIGHT System Prompt | 1,687 | 1,050 | 637 |
| User Prompt + 상품 JSON | 449 | 449 | 0 |
| JSON Schema | 564 | 271 | 293 |
| 전체 | 2,700 | 1,770 | 930 |

감소율은 34.44%다. 이전 분석의 Schema 약 563/전체 약 2,699와 1토큰 차이는 변경 전 Map.of 직렬화 순서의 변동 범위다. 보존한 실제 변경 전 요청을 기준으로 비교했으며 목표 수치에 맞춰 합계를 보정하지 않았다.

## 검증

전체 Maven verify 결과 211개 중 210개 통과, 실제 API 비교 1개 건너뜀, 실패 0개로 JAR 빌드에 성공했다. JavaScript 테스트 2개도 통과했다. 추가 검증은 의류 4단품의 8개 매핑, 정보고시 공통 치수의 evidence 보존, LIGHT calculation null 전용, FULL 계산 Schema 스냅샷 일치, 수량/중량/화면크기/설치지원방식/모델명의 FULL 선택, LIGHT 실패 후 FULL Schema 전환을 포함한다.

현재 프로세스 환경에 OPENAI_API_KEY가 없어 실제 API 호출은 하지 않았다. 모의 응답 테스트는 서버 계약과 전달 구조를 검증하며 실제 모델 정확도나 실제 usage 측정은 대체하지 않는다.

Schema description 축약, User Prompt의 계산 문구 정리, 정보고시 선별 및 추가 caching 최적화는 이번 변경에서 적용하지 않았다.
