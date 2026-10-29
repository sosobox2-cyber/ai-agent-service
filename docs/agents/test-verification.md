# 테스트 검증

## 목적과 실행 경계

요구사항의 정상·반려 결과와 회귀 위험을 실제 실행 결과로 확인한다. 기본 PATH의 Java 버전을 확인하고 Java 17과 Maven을 사용한다. 이 저장소에는 Maven Wrapper가 없고 기본 `pom.xml`은 테스트를 생략하지 않는다. 실제 OpenAI 호출은 사용자 요청 범위와 비용 상한을 확인한 때만 수행하며 mock·fixture를 우선한다.

## 영향별 출발점

| 변경 | 우선 확인할 테스트 |
|---|---|
| API·입력·최종 판정 | `PurchaseOptionApiTest`, `RequestValidationTest`, `InferenceServiceTest`, `ResultValidatorTest` |
| AI 선택·Schema·재추론 | `PromptRoutingTest`, `LightSchemaTest`, `AiAdapterTest`, `AiFailoverTest`, `OpenAiTransportTest` |
| 수량·출처·포장 계층 | `QuantityEvidenceTest`, `PackCapacityNormalizerTest`, `PurchaseOptionUnitTest`, 관련 모의 구성 테스트 |
| 인증·키 부재·이력 | `ApiProtectionTest`, `NoApiKeyStartupTest`, AI usage·로그 관련 테스트 |
| 화면·평가 하네스 | `src/test/js/ai-usage.test.cjs`, `purchase-options.test.cjs`, `unit-results.test.cjs`, `eval-diverse.test.cjs` |

테스트 이름은 출발점이며 실제 단언과 실행 경로를 확인한다. 공통 승인·보안·프롬프트 변경은 관련 테스트를 먼저 돌리고 Java 전체 회귀로 확장한다. 정상값뿐 아니라 null·누락·출처 충돌·단위 동치·낱개/포장 혼합·여러 본품·신뢰도 경계·재추론 한도를 확인한다.

## 판정과 결과

- `PASS`: 요구한 동작과 영향 범위의 필수 검증 통과
- `CONDITIONAL PASS`: 환경 제약 등 미검증 범위가 있으나 실행 가능한 필수 검증 통과
- `FAIL`: 요구 불일치 또는 의미 있는 회귀 위험 확인

TEST_MODE는 `success=false`인 정상 모의 응답이며 실제 AI 정확도의 근거가 아니다. 운영 평가에서는 등록 기준과 사람 확정값, 서버 승인, 비교 일치를 별도로 집계한다. 비용 원장의 미완료 예약·usage 미측정·중복 trial을 확인하고 같은 실패 입력을 통과 목적으로 반복 호출하지 않는다. 테스트 역할은 운영 코드를 수정하지 않고 필요한 테스트 파일과 보고서만 다룬다. 명령·결과·실패 원인·외부 호출·비용·미실행 사유의 기록 여부와 통합 형식은 [보고서 규칙](../report/report-guide.md)을 따른다. 통과한 검사는 변경·실패·미해결 위험이 추가되지 않으면 반복하지 않는다.
