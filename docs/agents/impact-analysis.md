# 영향 분석

## 목적과 원칙

실제 호출 흐름과 테스트를 따라 변경 범위, 승인·비용·보안 위험과 회귀 지점을 정한다. 이름이 비슷한 `*Context` 또는 모의 계산을 실제 AI 경로로 취급하지 않는다. 분석 역할은 운영 코드와 외부 상태를 수정하지 않는다.

## 확인 항목

- `ApiProtectionConfig → PurchaseOptionController → RequestValidator → PurchaseOptionInferenceService → ResultValidator`의 호출·오류 흐름
- 실제 AI의 `PurchaseOptionAiService`와 모의 추출의 분기, 프롬프트 모드 선택·파싱·정규화·재추론 위치
- `InferenceRequest`, `MappingProposal`, `Calculation`, `QuantityEvidence`, 응답 DTO와 외부 필드 호환성
- `certain`·confidence·단위·근거·포장 계층 검사가 최종 승인과 반려 제안에 미치는 영향
- LIGHT/FULL 프롬프트, JSON Schema, 사용자 입력, 버전과 재호출 상한의 결합
- 실제 AI로 전송되는 상품 정보, 키 부재·인증·요청 제한, usage·로그·비용의 영향
- 정적 화면·API 문서·예제·Node 테스트 및 Java 관련 테스트의 범위
- 평가 하네스라면 입력 해시, 동일 trial 재실행 차단, 비용 예약, 원본 기준값과 검토 이력, 데이터셋 백업·복원 경계

## 위험과 결과

낮음·중간·높음 중 위험도를 적고 승인 결과, 외부 전송·비용, 복구 난이도를 근거로 설명한다. 정상·경계·실패·모의 결과를 어떤 테스트로 확인할지 정하고, 로컬에서 실제 AI를 호출할 수 없는 경우처럼 미검증 범위를 명시한다. 결과에는 실제 호출 흐름, 영향 파일·필드, 기존 패턴, 누락 가능 연관 로직, 위험과 구현 인계 사항을 코드 근거와 함께 적는다. 중간 이상은 `작업제목_영향분석.md`, 작은 변경은 `작업제목.md`를 사용한다.
