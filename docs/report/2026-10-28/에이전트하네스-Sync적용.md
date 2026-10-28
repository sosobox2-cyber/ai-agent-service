# 에이전트 하네스 Sync 기준 적용

- 작성일: 2026-10-28 (Asia/Seoul)
- 범위: `AGENTS.md`, 변경 작업 스킬, 역할 문서 5개, 보고서 규칙
- 성격: 문서·하네스 변경. 애플리케이션 동작과 운영 상태 변경 없음.

## 요구사항

`stoa-partner-sync`의 세부 하네스를 기준으로 AI 서비스의 작업 지침을 구체화한다. 대상은 변경 규모 판정, 역할별 확인·인계, 보고서 이력, 형상관리와 검증 경계다. Java 8, Oracle DB, 스케줄러, 파트너별 동기화 규칙은 이 저장소에 적용하지 않는다.

## 영향분석

문서와 스킬에만 영향이 있으며 API·추론 코드·프롬프트·설정·배포 상태는 이 작업에서 수정하지 않았다. 이 저장소의 실제 호출 경로, TEST_MODE의 `success=false`, 실제 AI 호출의 비용·상품 정보 전송, 승인 조건과 수량 계층을 기준으로 역할 문서를 적었다. 위험도는 낮음으로 판단했다.

## 구현

`stoa-partner-sync`의 작은 변경 판정, 단계별 보고서, 사용자 변경 보호, 실패 원인 구분과 추측 금지 원칙을 AI 서비스 용어와 경계에 맞게 적용했다. 작은 문서 변경은 통합 보고서 1개를 사용하며, 중간 이상 변경은 단계별 파일로 기록한다. 코드 변경이 아닌 문서 변경에도 현재 코드 설명과 경로를 확인하도록 했다.

## 테스트

- 수정 문서 9개에서 상대 링크 25개를 검사했고 누락은 없었다.
- 변경 스킬의 `name`·`description` frontmatter를 확인했다.
- `QuantityEvidenceValidator`, `PackCapacityNormalizer`, `eval-diverse.test.cjs` 등 명시한 파일과 테스트 경로의 존재를 확인했다.
- 문서 변경분의 `git diff --check`는 통과했다. Git의 LF→CRLF 경고만 출력됐다.
- 문서 전용 변경이므로 Java/Maven 테스트, 서버 실행, 실제 AI 호출은 수행하지 않았다. Python 실행기는 WindowsApps 별칭만 있어 스킬 자동 검증 스크립트는 실행하지 못했다.

## 교차검증

동일 에이전트가 검토했으며 독립 리뷰로 표시하지 않는다. Sync 저장소의 DB 변경·외부 동기화·Java 8·`skipTests=true` 규칙이 이 저장소에 유입되지 않았는지 확인했다. 작업 중 다른 변경이 있는 Java 코드와 테스트, 정적 문서, IDE 설정과 백업 파일은 수정하지 않았다.

[변경 작업 스킬](../../../.agents/skills/ai-agent-change-workflow/SKILL.md) · [보고서 규칙](../report-guide.md) · [역할 지침](../../agents/requirements-definition.md)
