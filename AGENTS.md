# ai-agent-service 에이전트 가이드

## 1. 프로젝트와 읽기 순서

- 상품의 원본 단품 옵션명과 상품 정보를 쿠팡 구매옵션으로 변환하는 Java 17 / Spring Boot 단일 Maven 모듈이다. 상품이나 DB를 직접 갱신하지 않는다.
- 버전과 의존성의 기준은 `pom.xml`이다. Java 8 기반 파트너 저장소의 설정을 복사하지 않는다.
- 시작점: `src/main/java/com/cware/ai/AiAgentServiceApplication.java`.
- 전체 구조는 [소스 읽기 지도](docs/source-reading-guide.md), API 계약은 [API 명세](src/main/resources/static/api-reference.md), AI 동작은 [내부 설계/운영](src/main/resources/static/ai-operations.md)을 읽는다. 문서와 코드가 다르면 실제 호출 경로와 테스트를 확인한다.

```text
ApiProtectionConfig → PurchaseOptionController → InferenceRequest / RequestValidator
  → PurchaseOptionInferenceService
    → testMode: MockOptionInferenceService
    → 실제 AI: PurchaseOptionAiService → 프롬프트 선택 / AI 호출 / 응답 파싱
  → ResultValidator → 최종 승인 또는 검토 필요 응답
```

| 위치 | 역할 |
|---|---|
| `src/main/java/com/cware/ai/controller`, `dto`, `service` | HTTP 계약, 입력 보정, 처리 조율과 최종 판정 |
| `src/main/java/com/cware/ai/inference` | AI 호출, LIGHT/FULL, 검증, 모의 추출 |
| `src/main/java/com/cware/ai/config`, `security`, `exception`, `util` | 설정, 인증·제한, 오류 응답, 입력 해시 |
| `src/main/resources/prompts` | 시스템·사용자 프롬프트 |
| `src/main/resources/static` | 웹 테스트 화면, 사용자·API·운영 문서 |
| `src/test/java`, `src/test/js` | Java 회귀 테스트, Node 기반 화면 로직 테스트 |
| `examples`, `scripts` | 호출 예제, AI 이력 집계 |

`agent`, `tool` 패키지는 확장용이다. 이름만 보고 현재 실행 경로라고 판단하지 않는다.

## 2. 유지할 동작과 변경 경계

- 실제 AI와 모의 추출의 호출 경로를 구분한다. `*Context`, `MockCalculation`이 실제 AI 승인에 사용된다고 추측하지 않는다.
- 실제 AI 최종 승인은 `certain=true`, 전체·개별 매핑 최저 신뢰도 기준 충족, 구조·단위 형식·단품 조합 검사 통과가 필요하다. 기본 신뢰도 기준은 0.80이며 설정을 확인한다.
- 승인된 상품이라도 `app.inference.review-only-targets`(기본: 사이즈, 모델명/품번, 개당 캡슐/정, 침구류사이즈) 대상이 허용돼 있으면 `autoApplyCandidate=false`, `ACCEPTED_REVIEW_POLICY`로 사람 확인을 요구한다. 허용 옵션 생략은 승인한다. 대상 목록은 확정값 평가 근거 없이 바꾸지 않는다.
- 정상 모의 결과도 `inferenceSource=TEST`, `errorCode=TEST_MODE`, `success=false`, `autoApplyCandidate=false`다. 모의 테스트 성공을 실제 AI 정확성으로 보고하지 않는다.
- 반려된 AI 제안은 `aiAssessment`에 보존하며 자동 적용 결과와 구분한다. `serverAssessment`, 오류 코드와 빈 결과 목록의 의미를 유지한다.
- 기본 LIGHT 재추론은 불확실 또는 검증 실패 시 FULL로 한 번만 전환한다. 통신 오류와 낮은 confidence만으로 재호출하지 않는다. 관련 설정과 `AiFailoverTest`를 함께 확인한다.
- 실제 AI 승인 경로는 일반적인 원문 숫자 추출이나 임의의 계산을 재수행하지 않는다. 단일 본품의 명시적 `낱개 용량 × 포장 내 개수 × 판매 포장 수`는 `PackCapacityNormalizer`와 `QuantityEvidenceValidator`가 포장당 용량·근거·계층을 좁게 계산·대조한다. 이 예외를 다른 옵션으로 확대하려면 요구사항과 회귀 영향을 먼저 확인한다.
- 서버가 만들거나 바꾼 매핑(`CountUnitNormalizer`, `ImplicitSingleQuantityPolicy`, `PackCapacityNormalizer`)에는 `serverNormalization`을 남기고 AI 응답의 같은 필드는 지운다. 포장식 인식 범위를 넓힐 때는 `ai-operations.md`의 지원 범위 표를 먼저 갱신하고, 특정 상품 하나만 맞추는 정규식 추가는 피한다.
- 구매옵션 단위는 명시된 설정에만 서버 규칙을 적용한다. 미설정 항목에 임의의 기본 단위를 붙이지 않는다.
- `goodsId`, `categoryName`은 식별·로그용이며 실제 AI 입력에서 제외한다. 상품 정보와 단품 옵션 등 실제 전송 항목은 입력 생성 코드로 확인한다.
- 프롬프트 수정 시 LIGHT/FULL, 사용자 프롬프트, JSON Schema, 파싱·정규화, 프롬프트 버전과 회귀 테스트를 함께 검토한다. FULL 계약을 LIGHT 최적화에 끌어들이지 않는다.
- 작은 변경에 패키지 재편·의존성 업그레이드를 섞지 않는다. 외부 필드명과 기존 한국어 업무 용어를 유지한다.

## 3. 설정, 외부 호출과 보안

- 실제 OpenAI 호출은 비용과 상품 정보 전송을 수반한다. 테스트는 기존 mock·fixture를 우선 사용하며 실제 호출은 해당 목적이 사용자 요청 범위에 포함될 때 수행한다.
- OpenAI 키와 서비스 API 키는 별개다. 인증·요청 제한을 검증 편의를 위해 운영 기본값에서 끄지 않는다.
- `.env`, 로컬 실행 구성, 비밀 관리 파일의 값을 출력하거나 문서·테스트·커밋에 복사하지 않는다. 설정은 필요한 키와 코드의 참조 위치를 확인하고 예시에는 가짜 값을 사용한다.
- JSONL 이력·로그에는 상품 식별정보가 있을 수 있다. 검증 결과에는 필요한 집계만 남기며 실제 요청·응답 본문과 인증정보를 불필요하게 옮기지 않는다.
- 로그·usage 변경 시 재추론 두 호출의 `inferenceId`, 실제 토큰 사용량, `elapsedMs`와 알 수 없는 비용·usage의 표현을 보존한다.
- 서버 배포·운영 업데이트는 사용자가 명시적으로 지시한 경우에만 수행한다. 코드 수정·테스트·커밋 요청을 배포 요청으로 해석하지 않는다.

## 4. 빌드와 검증

- Java 17과 Maven이 필요하다. 현재 저장소에는 Maven Wrapper가 없으므로 `mvnw`가 있다고 가정하지 않는다. 기본 PATH의 Java 버전을 먼저 확인한다.
- Java 변경은 관련 테스트를 먼저 실행하고 공통 승인·보안·프롬프트 경로 변경이면 Java 전체 회귀 테스트로 넓힌다.

```powershell
java -version
mvn -Dtest=InferenceServiceTest,AiFailoverTest,PurchaseOptionApiTest test
mvn test
mvn package
node --test src/test/js/ai-usage.test.cjs src/test/js/purchase-options.test.cjs src/test/js/unit-results.test.cjs src/test/js/eval-diverse.test.cjs
```

- 이 프로젝트는 `pom.xml`에 기본 테스트 생략 설정이 없다. 다른 파트너 저장소의 `skipTests=true` 규칙을 적용하지 않는다.
- 테스트의 AI 대역, 키 부재 동작, 프로필을 확인한다. 실행을 위해 실제 키를 주입하지 않는다.
- 정적 화면 변경은 관련 Node 테스트로 확인한다. 브라우저 확인이 필요하면 로컬 `testMode=true`로 검증하며 사용자가 실행 중인 서버를 임의로 종료하지 않는다.
- 문서만 변경하면 링크·경로·명령과 코드 설명의 일치를 검토한다. 불필요하게 서버나 AI를 실행하지 않는다.
- 실제 실행한 명령과 결과, 실행하지 못한 검증과 원인을 구분한다. 환경 실패를 코드 실패로 보고하거나 검증 성공을 추정하지 않는다.

## 5. 형상관리와 실행 전략

- 작업 전후 `git status --short`, `git diff`로 사용자 변경을 보존하고 의도한 파일만 수정한다. `target/`, 로그, IDE 설정과 임시 백업을 작업에 섞지 않는다.
- 사용자나 다른 작업의 미커밋 변경을 제거하는 `git reset --hard`, `git clean -fd`, 강제 restore/checkout과 광범위한 덮어쓰기·삭제를 명시적 요청 없이 수행하지 않는다.
- 명시적 요청 없이 commit, push, merge, tag 생성 또는 배포하지 않는다. 커밋 제목·본문과 사용자 보고서는 한국어로 작성한다.
- 코드·프롬프트·스키마·화면·설정 변경에는 [변경 작업 스킬](.agents/skills/ai-agent-change-workflow/SKILL.md)을 적용한다. 설명·조회에는 적용하지 않는다.
- 스킬 본문은 `.agents` 한 곳에서 유지한다. `.claude`의 동명 파일은 본문을 연결하는 진입점이다.
- 요구사항 정의 → 영향 분석 → 구현 → 테스트 검증 → 교차 리뷰는 논리적 단계다. 작은 작업은 한 컨텍스트에서 순차 수행하고 별도 에이전트를 기계적으로 생성하지 않는다.
- 위험도는 영향 경로와 외부 전송·비용, 승인 결과, API 호환성으로 판단한다. 승인·보안·공통 검증·재추론 변경은 고위험 후보로 본다.
- 독립 검토가 유익하고 현재 세션에서 위임이 허용될 때만 서브에이전트를 사용한다. 작업 범위·금지 영역·검증 조건을 전달하고 같은 파일의 동시 수정을 피한다. 메인 에이전트가 근거와 통합 결과를 책임진다.
- 역할별 상세 지침은 [docs/agents](docs/agents/requirements-definition.md), 산출물은 [보고서 규칙](docs/report/report-guide.md)을 따른다.

### 하네스의 역할과 실행 기준

- `AGENTS.md`는 전역 경계와 실행·위임 정책, 변경 스킬은 작업 유형별 경로와 완료 조건, `docs/agents/*.md`는 해당 역할을 맡을 때만 읽는 상세 지침이다. 메인 에이전트가 모든 단계와 최종 검증·보고를 책임진다.
- `요구사항 → 영향분석 → 구현 → 테스트 → 교차검증`은 결과를 누락하지 않기 위한 순서다. 작은 변경은 한 컨텍스트에서 수행하며, 수행하지 않은 역할의 보고서를 만들지 않는다.
- 낮은 위험의 문서·국소 변경은 관련 근거와 좁은 검증으로 끝낸다. 중간 위험은 API·Schema·화면·모의 경로의 교차 영향을 확인한다. 높은 위험의 최종 승인·공통 검증·보안·재추론·실제 AI 전송 변경은 구현 전 영향·비용·실패 시 결과를 정하고, 구현 후 전체 관련 회귀와 별도 관점의 diff 검토를 수행한다. 위험 등급은 파일 수가 아니라 승인 결과·외부 전송·복구 난이도로 다시 판단한다.
- 독립 분석·검증이 실제로 유익하고 현재 세션에서 위임이 허용될 때만 서브에이전트를 사용한다. 위임할 때는 목적, 읽기·수정 범위, 금지 영역, 외부 호출·비용 제한, 완료 조건을 전달한다. 같은 파일을 동시에 수정하지 않고, 서브에이전트의 결론을 메인 에이전트가 코드와 테스트로 확인한다. 같은 에이전트의 재검토를 독립 리뷰라고 부르지 않는다.
- 역할별 지침과 보고서는 실제 수행한 범위에 비례해 사용한다. 보고서에는 코드 근거, 실행 명령·결과, 실제 AI와 TEST_MODE의 구분, 비용·미측정 사용량, 미검증 범위를 남긴다. 인증정보·실제 요청/응답 본문·상품 식별정보는 불필요하게 옮기지 않는다.
- 소스·설정·로그·평가 데이터 같은 내부 자료를 외부 AI/API나 웹 서비스에 넘기는 행위는 개발용 외부 API 호출과 별도로 판단한다. 실제 OpenAI 추론에 필요한 상품 입력은 사용자가 해당 호출과 비용을 요청한 범위에서만 전송한다. 비밀값은 전송하지 않는다.
- 예상보다 영향 파일이나 승인 경로가 넓어지면 범위와 위험을 다시 판단한다. 실패가 나면 이번 변경의 결함인지 환경·동시 작업의 영향인지 먼저 구분하고 같은 원인으로 무작정 재실행하지 않는다.
