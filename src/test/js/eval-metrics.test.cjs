'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { classify, wilson, collect, compareValue, nearMatch, listReferenceOmitted, rescore, loadReviews } = require('../../../scripts/eval-metrics.cjs');

test('목록 기준값에서 AI가 생략한 행만 기준값 의심으로 본다', () => {
  assert.equal(listReferenceOmitted('블랙, 그린, 레드', null), true);
  assert.equal(listReferenceOmitted('12종', ''), true);
  assert.equal(listReferenceOmitted('블랙, 그린', '블랙'), false);
  assert.equal(listReferenceOmitted('화이트', null), false);
});

test('표기 차이 의심은 괄호 별칭·FREE·계열·개수 단위 동의어만 잡고 다른 값은 잡지 않는다', () => {
  for (const [a, b] of [['킹', '킹(K)'], ['M4W6(230mm)', '230mm'], ['핑크/FREE', '핑크'], ['화이트', '화이트계열'],
    ['24개입', '24매입'], ['XL(33~34)', 'XL']]) assert.equal(nearMatch(a, b), true, `${a} ${b}`);
  for (const [a, b] of [['4개', '12개'], ['3개', '3박스'], ['네츄럴', '월넛'], ['핑크', '핑크'], ['50g', '1000g'], ['', 'x']])
    assert.equal(nearMatch(a, b), false, `${a} ${b}`);
});
const { sampleFixed, tunedCases, trialPlan } = require('../../../scripts/eval-diverse.cjs');

const row = (status, confirmedValue = '') => ({ row: 1, target: '수량', status, confirmedValue });
const run = (success, comparisons, version = 'coupang-option-v42') =>
  ({ response: { success, promptVersion: version, validationErrors: [] }, comparisons });

test('승인·검토와 정답 여부로 상품을 분류하고 기준값 없는 행은 채점하지 않는다', () => {
  assert.equal(classify(run(true, [row('EXACT'), row('EQUIVALENT')])), 'APPROVED_CORRECT');
  assert.equal(classify(run(true, [row('EXACT'), row('DIFFERENT')])), 'FALSE_APPROVAL');
  assert.equal(classify(run(false, [row('EXACT')])), 'REVIEW_BUT_CORRECT');
  assert.equal(classify(run(false, [row('MISSING')])), 'REVIEW_AND_WRONG');
  assert.equal(classify(run(true, [row('NO_REFERENCE')])), 'UNSCORED');
  assert.equal(classify({ error: 'AI_TIMEOUT' }), 'ERROR');
});

test('확정값 기준은 사람이 확정한 행만 채점한다', () => {
  const r = run(true, [row('DIFFERENT'), row('EXACT', '4세트')]);
  assert.equal(classify(r), 'FALSE_APPROVAL');
  assert.equal(classify(r, true), 'APPROVED_CORRECT');
  assert.equal(classify(run(true, [row('DIFFERENT')]), true), 'UNSCORED');
  assert.equal(classify(run(true, [row('REGRESSION', '1개')]), true), 'FALSE_APPROVAL');
});

test('Wilson 구간은 작은 표본에서 넓다', () => {
  const small = wilson(0, 3), large = wilson(0, 300);
  assert.equal(small.rate, 0);
  assert.ok(small.high > 0.5 && large.high < 0.02);
  assert.equal(wilson(0, 0), null);
});

test('원장의 버전별 최신 실행만 집계하고 계획 파일로 걸러낸다', t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'eval-metrics-'));
  t.after(() => fs.rmSync(dir, { recursive: true }));
  const events = [];
  const add = (id, caseId, trialId, body, cost = 0.002) => {
    fs.writeFileSync(path.join(dir, id + '.json'), JSON.stringify(body));
    events.push({ id, event: 'reserved', caseId, trialId, reserveUsd: 0.08 },
      { id, event: 'completed', caseId, trialId, costUsd: cost, reserveUsd: 0.08 });
  };
  add('a', 'c1', 'c1', run(true, [row('DIFFERENT')]));
  add('b', 'c1', 'c1', run(true, [row('EXACT')]));
  add('c', 'c2', 'fixed-v42-c2', run(false, [row('EXACT')]));
  fs.writeFileSync(path.join(dir, 'ledger.jsonl'), events.map(e => JSON.stringify(e)).join('\n') + '\n');
  const all = collect(dir).versions['coupang-option-v42'].baseline;
  assert.equal(all.cases, 2);
  assert.equal(all.repeats, 1);
  assert.equal(all.counts.APPROVED_CORRECT, 1);
  assert.equal(all.counts.REVIEW_BUT_CORRECT, 1);
  assert.ok(Math.abs(all.costUsd - 0.006) < 1e-12);
  const filtered = collect(dir, new Set(['fixed-v42-c2'])).versions['coupang-option-v42'].baseline;
  assert.equal(filtered.cases, 1);
  assert.equal(collect(dir).budget.pending, 0);
});

test('고정셋은 시드로 재현되고 개선 시험 사례를 제외하며 버전별 trialId를 만든다', () => {
  const cases = Array.from({ length: 30 }, (_, i) => ({ id: 'c' + i, issues: i === 0 ? ['x'] : [], references: [{}],
    request: { goodsName: 'n' + i, categoryName: '식품 > 과자', allowedPurchaseOptions: ['수량'],
      options: [{ optionId: '1', optionName1: '단일상품' }], purchaseOptionUnits: [], productCompositionText: '' } }));
  const tuned = tunedCases([{ caseId: 'c1', trialId: 'c1' }, { caseId: 'c2', trialId: 'v40-boundary-c2' }]);
  assert.deepEqual([...tuned], ['c2']);
  const a = sampleFixed(cases, 10, 'gold', tuned), b = sampleFixed(cases, 10, 'gold', tuned);
  assert.deepEqual(a.selected.map(s => s.caseId), b.selected.map(s => s.caseId));
  assert.equal(a.eligible, 28);
  assert.ok(a.selected.every(s => s.caseId !== 'c0' && s.caseId !== 'c2'));
  const plan = trialPlan(a, { id: 'd', sourceHash: 'h' }, 'gold', 'coupang-option-v42');
  assert.equal(plan.selected[0].trialId, `gold-v42-${a.selected[0].caseId}`);
  assert.equal(plan.promptVersion, 'coupang-option-v42');
});

test('비교 규칙은 서버 ValueComparison과 같다', () => {
  assert.equal(compareValue('', 'x'), 'NO_REFERENCE');
  assert.equal(compareValue('1개', null), 'MISSING');
  assert.equal(compareValue('1,000 ml', '1000ml'), 'EQUIVALENT');
  assert.equal(compareValue('1.5L', '1500ml'), 'EQUIVALENT');
  assert.equal(compareValue('0.05L', '50ml'), 'EQUIVALENT');
  assert.equal(compareValue('ＢＬＡＣＫ', 'black'), 'EQUIVALENT');
  assert.equal(compareValue('1kg', '1000ml'), 'DIFFERENT');
  assert.equal(compareValue('핑크/FREE', '핑크'), 'DIFFERENT');
});

test('최신 검토 스냅샷으로 실행 당시 비교를 다시 채점한다', () => {
  const reviews = loadReviews({ cases: [{ testCase: { id: 'c1' }, reviews: [
    { row: 1, status: 'REFERENCE_ISSUE', confirmedValue: '4세트' },
    { row: 2, status: 'REFERENCE_ISSUE', confirmedValue: '' },
    { row: 3, status: 'NEEDS_FIX', confirmedValue: '2개' },
    { row: 9, status: 'UNREVIEWED', confirmedValue: '' }] }] });
  assert.equal(reviews.get('c1').size, 3);
  const stored = run(true, [
    { row: 1, target: '수량', baseline: '4박스', actual: '4세트', status: 'DIFFERENT', confirmedValue: '' },
    { row: 2, target: '색상', baseline: '898000', actual: '그레이', status: 'DIFFERENT', confirmedValue: '' },
    { row: 3, target: '수량', baseline: '2개', actual: '1개', status: 'DIFFERENT', confirmedValue: '' },
    { row: 4, target: '색상', baseline: '블랙', actual: '블랙', status: 'REGRESSION', confirmedValue: '화이트' }]);
  assert.equal(classify(stored), 'FALSE_APPROVAL');
  const fresh = rescore(stored, reviews.get('c1'));
  // 행 4는 검토가 취소돼 원본 등록값 기준으로 돌아간다.
  assert.deepEqual(fresh.comparisons.map(c => c.status), ['EXACT', 'NO_REFERENCE', 'REGRESSION', 'EXACT']);
  assert.equal(fresh.comparisons[3].confirmedValue, '');
  assert.equal(classify(fresh, true), 'FALSE_APPROVAL');
  assert.equal(rescore(stored, undefined), stored);
});

test('collect는 검토가 있으면 다시 채점한 결과로 집계한다', t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'eval-rescore-'));
  t.after(() => fs.rmSync(dir, { recursive: true }));
  fs.writeFileSync(path.join(dir, 'a.json'), JSON.stringify(run(true, [
    { row: 1, target: '수량', baseline: '4박스', actual: '4세트', status: 'DIFFERENT', confirmedValue: '' }])));
  fs.writeFileSync(path.join(dir, 'ledger.jsonl'), JSON.stringify(
    { id: 'a', event: 'completed', caseId: 'c1', trialId: 'c1', costUsd: 0.002 }) + '\n');
  const reviews = loadReviews({ cases: [{ testCase: { id: 'c1' },
    reviews: [{ row: 1, status: 'REFERENCE_ISSUE', confirmedValue: '4세트' }] }] });
  assert.equal(collect(dir).versions['coupang-option-v42'].gold.scored, 0);
  assert.equal(collect(dir, null, reviews).versions['coupang-option-v42'].gold.counts.APPROVED_CORRECT, 1);
});
