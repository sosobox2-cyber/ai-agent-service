# 테스트 검증

Java 17과 Maven 사용 여부를 확인한다. 저장소에는 Maven Wrapper가 없다. 관련 Java 테스트 또는 `src/test/js/*.test.cjs`를 영향에 맞춰 선택한다. 명령은 [AGENTS.md](../../AGENTS.md) 4절을 따른다.

| 변경 | 우선 검토할 기존 테스트 |
|---|---|
| API·입력·최종 판정 | PurchaseOptionApiTest, RequestValidationTest, InferenceServiceTest, ResultValidatorTest |
| AI 선택·Schema·재추론 | PromptRoutingTest, LightSchemaTest, AiAdapterTest, AiFailoverTest, OpenAiTransportTest |
| 모의 수량·단위·구성 | MockOptionInferenceTest, PurchaseOptionUnitTest, ProductCompositionTest와 관련 Context 테스트 |
| 인증·키 부재·이력 | ApiProtectionTest, NoApiKeyStartupTest, AiUsageLoggerTest, AiPurchaseOptionUsageLogTest |
| 화면 | ai-usage.test.cjs, purchase-options.test.cjs, unit-results.test.cjs |

정상 입력뿐 아니라 변경과 관련된 누락·null·경계값·반려·재추론 상한을 확인한다. 테스트 이름은 선택의 출발점이며 실제 검증 내용을 읽는다. mock 테스트를 실제 AI 품질 검증으로 해석하지 않는다.

실행 명령, 실제 결과, 실패 원인, 미실행 범위와 외부 호출 여부를 [보고서 규칙](../report/report-guide.md)에 기록한다. 문서 전용 변경에는 코드 테스트를 의무화하지 않는다.
