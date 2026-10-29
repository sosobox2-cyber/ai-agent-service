# holdout150 및 gold150 v44 반복 실행

작성일: 2026-10-29. 수행 범위: 예산 변경, 사전 확인·백업, 실제 AI 평가, 읽기 전용 재채점, 결과 검토. [이전 운영 평가](gold150-v44-운영평가_단계.md) 후속이다.

## 요구사항·영향 및 변경

- 사용자 승인에 따라 누적 예산을 $3으로 올리고 gold150 미실행 2건, 처음 보는 holdout150, gold150 반복 150건을 실행했다. push·배포·검토 저장은 하지 않았다.
- 로컬 `scripts/eval-diverse.cjs`의 `BUDGET_USD=3.0`, 허용 예산에 3.0을 반영했다. 기존 0.95·1.45·2.0 계획 호환성을 유지하고 $3 테스트를 추가했다. 커밋: `60cef8d` (`평가 예산 상한 3달러`). src/main·프롬프트·서버 승인 정책은 변경하지 않았다.
- 영향은 로컬 평가 하네스의 비용 제한이다. 실제 상품 입력 전송과 비용이 발생하므로 trialId 중복 방지·예약·버전·비용을 배치별 확인했다. 시간 초과는 재호출하지 않으며 연속 오류 2건, 시간 초과 외 비용 미상 오류, 버전 불일치, 상한 도달 시 중단하는 조건으로 실행했다. 해당 중단 조건은 발생하지 않았다.

## 사전 확인 및 고정셋

- TEST_MODE: `promptVersion=coupang-option-v44`, `success=false`, `errorCode=TEST_MODE`. 실제 AI 정확성 검증과 구분한다.
- 운영 데이터셋: `5a5797a1-78d2-4a13-8755-6197c33eda9d`. 상품 10,270, sourceHash `daba71685498221030f5a14c3a3851fb8bae77c102897811597e643b1a29b27b`.
- 시작 원장 $1.9220908, 미완료 예약 0건. `backup-before-holdout-r2-v44.zip` 백업 후 ZIP 1,368개 항목을 읽어 확인했다(5,363,629바이트).
- 기존 `plan-gold150-v44.json`을 보존하고 budgetUsd만 3.0으로 바꾼 `plan-gold150-v44b3.json`을 만들었다. trialId를 유지해 기존 148건을 건너뛰고 c01030·c06906만 실행했다.
- `freeze ... 150 holdout150` 결과는 gold150과 교집합 0이었지만 과거 기본 실행 상품 c02456·c02546이 포함됐다. freeze는 개별 시험(trialId≠caseId)을 제외하므로 기본 실행 상품은 남을 수 있었다. **실제 holdout 호출 전**, 기존 `sampleFixed`와 동일 seed를 사용하고 이전 실행 전체 247개 caseId를 제외해 다시 고정했다. 148건을 유지하고 c05725·c10145를 추가했다. 코드·원장은 바꾸지 않았다. 최초 파일은 `fixed-holdout150-freeze-original.json`, 변경 근거는 `holdout150-unseen-adjustment.json`에 보존했다. 최종 gold150 및 과거 실행 상품과 교집합은 모두 0이다.
- `fixed-gold150r2.json`은 원본에서 label만 변경했다. selected와 requestHash가 완전히 같은지 확인했다. 두 신규 계획 모두 운영 v44·동일 데이터셋·예산 $3이다.

## 실행 결과·비용

모든 결과 파일은 기존 `logs/diverse-eval-20261028/`에 저장했다. `node --use-system-ca`를 사용했고 인증서 검증을 끄지 않았다. 기존 run 명령을 20건씩 실행했다. 실제 API 요청 302건이며 같은 trial을 재호출하지 않았다. 서버 내부 LIGHT→FULL 전환과 평가 trial 재호출은 구분한다.

| 실행 | 이번 요청 | 실행 오류/시간 초과 | 확인된 비용 | 종료 원장 |
|---|---:|---:|---:|---:|
| gold150 v44 남은 2건 | 2 | 0 / 0 | $0.0087712 | $1.9308620 |
| holdout150 v44 | 150 | 0 / 0 | $0.3846300 | $2.3154920 |
| gold150r2 v44 | 150 | 0 / 0 | $0.3770380 | $2.6925300 |
| 이번 합계 | 302 | 0 / 0 | $0.7704392 | $2.6925300 |

상한 $3, 남은 한도 $0.3074700. 종료 미완료 예약 0건. 이번 실행에 비용 미상 오류는 없고 기존 원장의 과거 비용·usage 표현은 보존했다. 첫 회차 gold150 전체 150건 비용은 $0.3896136이다. `success=false`는 서버 검토 전환이며 실행 오류로 세지 않았다.

실행 명령:

```text
node --test src/test/js/*.test.cjs
node --use-system-ca scripts/eval-diverse.cjs run logs/diverse-eval-20261028 20 plan-gold150-v44b3.json
node --use-system-ca scripts/eval-diverse.cjs freeze logs/diverse-eval-20261028 150 holdout150
node --use-system-ca scripts/eval-diverse.cjs trial logs/diverse-eval-20261028 holdout150 coupang-option-v44 5a5797a1-78d2-4a13-8755-6197c33eda9d
node --use-system-ca scripts/eval-diverse.cjs run logs/diverse-eval-20261028 20 plan-holdout150-v44.json
node --use-system-ca scripts/eval-diverse.cjs trial logs/diverse-eval-20261028 gold150r2 coupang-option-v44 5a5797a1-78d2-4a13-8755-6197c33eda9d
node --use-system-ca scripts/eval-diverse.cjs run logs/diverse-eval-20261028 20 plan-gold150r2-v44.json
```

run은 완료까지 배치별 반복했다. Node 전체 28개 테스트 통과, 실패·건너뜀 0. Java 변경이 없어 Maven 검증은 수행하지 않았다.

## 동일 확정값으로 두 회차 비교

최신 git-snapshot `reviewed-snapshot-gold150-v44-r2.json`은 125상품·검토 312행이다. 이전 저장 스냅샷(120상품·299행)보다 늘었으나 **이번 작업 전 백업에도 동일한 312행이 있었고 최신 내용과 일치**했다. 이번 작업에서 확정값을 만들거나 저장하지 않았다. 두 회차 모두 최신 스냅샷을 사용해 `rescore` 후 `classify(run,true)`로 채점했다. gold150 중 채점 가능 116상품, 미채점 34상품이다.

```text
node scripts/eval-metrics.cjs logs/diverse-eval-20261028 plan-gold150-v44b3.json --reviews logs/diverse-eval-20261028/reviewed-snapshot-gold150-v44-r2.json
node scripts/eval-metrics.cjs logs/diverse-eval-20261028 plan-gold150r2-v44.json --reviews logs/diverse-eval-20261028/reviewed-snapshot-gold150-v44-r2.json
```

각 명령의 일반 출력과 `--json` 출력을 `metrics-gold150-v44b3-reviews-r2.{txt,json}`, `metrics-gold150r2-v44-reviews-r2.{txt,json}`에 저장했다. 아래 대괄호는 95% Wilson 구간이다.

| 지표 | 1회차 v44 (b3) | 2회차 v44 |
|---|---:|---:|
| 전체 승인 / 검토 | 57 / 93 | 52 / 98 |
| 확정값 오승인율 | 10/57 = 17.5% [9.8–29.4] | 7/52 = 13.5% [6.7–25.3] |
| 확정값 자동승인율 | 57/116 = 49.1% [40.2–58.1] | 52/116 = 44.8% [36.1–53.9] |
| 정답인데 검토 전환 / 검토 상품 | 25/59 = 42.4% [30.6–55.1] | 27/64 = 42.2% [30.9–54.4] |
| AI 제안 정답률 | 72/116 = 62.1% [53.0–70.4] | 72/116 = 62.1% [53.0–70.4] |
| 정답 승인 / 오승인 | 47 / 10 | 45 / 7 |
| 정답 제안 검토 / 오답 제안 검토 | 25 / 34 | 27 / 37 |

판정 동일은 **136/150**(미채점 동일 34 포함), 채점 가능 상품만 보면 **102/116**이다. 판정 변경 14상품 중 승인↔검토 변경은 **9/150 (6.0%)**, 승인은 유지하며 정오가 바뀐 3상품, 검토 상태에서 제안 정오가 바뀐 2상품이다.

| 상품 | 1회차 → 2회차 |
|---|---|
| c05871, c01889, c00514 | 정답 승인 → 정답 제안 검토 |
| c00627, c09923 | 정답 승인 → 오답 제안 검토 |
| c00025, c09550 | 오승인 → 오답 제안 검토 |
| c08562 | 정답 제안 검토 → 정답 승인 |
| c01792 | 오답 제안 검토 → 정답 승인 |
| c01262, c01281 | 오승인 → 정답 승인(승인 상태 유지) |
| c02607 | 정답 승인 → 오승인(승인 상태 유지) |
| c01818 | 오답 제안 검토 → 정답 제안 검토 |
| c03164 | 정답 제안 검토 → 오답 제안 검토 |

승인→검토 7상품, 검토→승인 2상품이다. 오승인 집합의 교집합은 c05696·c01527·c08138·c02575·c05349·c09081(6상품), 1회차에만 있는 것은 c00025·c01262·c01281·c09550(4상품), 2회차에만 있는 것은 c02607(1상품)이다. 상품별 판정과 전이 집계는 `holdout150-repeat-analysis.json`에 보존했다.

동일 버전·동일 입력·동일 확정값에서도 위 정도로 결과가 흔들렸다. 정답 승인 5건이 검토로 바뀌었고 신규 오승인도 생겼다. 오승인율 구간은 겹치므로 **개선으로 단정할 수 없음**. 버전 변경 효과를 해석할 때 이 반복 변동을 기준선으로 고려해야 한다. 두 번의 실행만으로 장기 변동 폭을 확정할 수 없다.

## holdout150 사람 검토 목록

holdout150은 150건 완료, 승인 62·검토 88상품이다. 확정값 스냅샷과 대조해 검토된 상품이 0임을 확인했다. 따라서 오승인율은 산출하지 않는다.

`review-unconfirmed-holdout150-v44.json`에 **428행**을 저장했다. 승인 상품은 누락 매핑도 검토할 수 있도록 모든 비교 행 251행, 검토 상품은 optionId·대상명으로 AI 제안 매핑이 존재하는 행 177행이다. 150상품이 포함되며 중복 caseId·row는 없다. 기존 목록과 같은 caseId·row·target·actual·baseline·status·url 형식에 `approved`를 반드시 포함했다. baseline은 기존 등록값이며 정답으로 간주하지 않았다. 사람이 정답을 확정하기 전에는 이 목록으로 정확도를 주장할 수 없다.

## 최종 검토·미검증 범위

동일 에이전트가 diff와 계획·원장·저장 응답을 대조했다(독립 리뷰 아님). 미실행 2건만 추가됐는지, 신규 두 계획 각 150 trial이 한 번씩 완료됐는지, 입력 해시와 고정셋 교집합, v44 버전, 비용·예약, 동일 스냅샷 채점 및 목록 중복을 확인했다. src/main·프롬프트·확정값·기존 원장 행을 변경하지 않았다. holdout 정답 및 장기 반복 분포는 미검증이다. 기존 사용자 수정 보고서·IDE 설정·백업 파일·logs는 커밋에 포함하지 않는다. 보고서만 커밋하며 push·배포하지 않는다.
