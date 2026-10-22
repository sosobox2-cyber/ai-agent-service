---
name: ai-agent-change-workflow
description: ai-agent-service의 API, AI 추론, 프롬프트·스키마, 모의 추출, 웹 화면과 설정 변경을 영향 분석부터 검증까지 수행한다. 단순 설명·조회에는 적용하지 않는다.
---

# AI Agent Service 변경 워크플로

프로젝트 경계와 실행·위임 정책은 [AGENTS.md](../../../AGENTS.md)를 따른다. 사용자 요청과 현재 세션의 권한을 확대하지 않는다.

## 작업 시작

현재 동작, 기대 동작과 완료 조건을 정리하고 실제 호출 경로에서 영향 범위를 확인한다. 위험도는 낮음·중간·높음으로 기록한다. 최종 승인, 공통 검증, 인증, AI 입력·외부 호출 또는 재추론 변경은 높은 위험 후보이며 파일 수만으로 판단하지 않는다.

필요한 역할 지침만 읽는다. 단계마다 에이전트를 생성할 필요는 없다.

- [요구사항 정의](../../../docs/agents/requirements-definition.md): 계약과 완료 조건을 정할 때
- [영향 분석](../../../docs/agents/impact-analysis.md): 실행 경로와 회귀 범위를 정할 때
- [구현](../../../docs/agents/implementation.md): 변경할 때
- [테스트 검증](../../../docs/agents/test-verification.md): 관측 가능한 결과를 확인할 때
- [교차 리뷰](../../../docs/agents/cross-review.md): 누락과 회귀 가능성을 검토할 때

## 변경 유형별 확인

| 변경 | 함께 확인할 범위 |
|---|---|
| DTO·API | 입력 보정, Bean Validation, 오류 HTTP 상태, API 문서·예제·화면 요청 |
| 최종 판정·검증 | certain, 전체·개별 confidence, 반려 제안, 빈 결과, 단위·단품 조합 |
| 프롬프트·AI 파싱 | LIGHT/FULL 선택, 사용자 입력, Schema, null·필수 필드, 버전, FULL 재추론 상한 |
| 모의 추출 | 실제 AI와의 경로 분리, TEST_MODE와 자동 적용 불가, 관련 예제 |
| usage·이력 | 호출별·합계 usage, 미제공 값, elapsedMs, inferenceId, 정보 노출 |
| 웹 화면 | 요청 JSON, 예제 로딩, 단위 입력, 결과·AI 제안·사용량 표시, Node 회귀 |
| 인증·설정 | 운영 기본값, 키 부재 동작, 요청 제한, 테스트 격리 |

## 완료

영향에 맞는 기존 mock·fixture 테스트를 실행하고 실제 AI 호출 여부를 구분한다. 문서만 변경하면 링크·경로와 기술적 사실을 확인한다. 실패는 코드와 환경으로 구분하고 수정 후 관련 검증을 다시 실행한다.

실제 수행한 단계는 [보고서 규칙](../../../docs/report/report-guide.md)에 따라 저장한다. 마지막으로 변경 범위를 확인하고 변경 내용, 검증 결과, 미검증 범위와 보고서 링크를 한국어로 전달한다.