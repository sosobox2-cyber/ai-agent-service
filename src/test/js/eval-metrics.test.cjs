'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { collectSnapshot } = require('../../../scripts/eval-metrics.cjs');

test('운영 표본 스냅샷은 사례별 최신 AI 실행과 검토로 집계한다', () => {
  const runOf = (id, startedAt, actual, auto) => ({ id, mode: 'AI', startedAt,
    response: { success: true, autoApplyCandidate: auto, promptVersion: 'coupang-option-v45', validationErrors: [] },
    comparisons: [{ row: 1, target: '색상', baseline: '', actual, status: 'NO_REFERENCE', confirmedValue: '' }] });
  const snapshot = { cases: [
    { testCase: { id: 'p20261029-a' }, reviews: [{ row: 1, status: 'SUCCESS', confirmedValue: '블랙' }],
      runs: [runOf('r1', '2026-10-29T01:00:00Z', '블랙', true)] },
    { testCase: { id: 'p20261029-b' }, reviews: [{ row: 1, status: 'NEEDS_FIX', confirmedValue: '화이트', note: '오답' }],
      runs: [runOf('r2', '2026-10-29T02:00:00Z', '블랙', true)] },
    { testCase: { id: 'p20261029-c' }, reviews: [{ row: 1, status: 'SUCCESS', confirmedValue: '네이비' }],
      runs: [runOf('r3', '2026-10-29T03:00:00Z', '네이비', false)] }] };
  const gold = collectSnapshot(snapshot).versions['coupang-option-v45'].gold;
  assert.equal(gold.counts.APPROVED_CORRECT, 1);
  assert.equal(gold.counts.FALSE_APPROVAL, 1);
  assert.equal(gold.counts.REVIEW_BUT_CORRECT, 1);
  assert.equal(collectSnapshot(snapshot).budget, null);
});

const { classify, wilson, collect, compareValue, nearMatch, listReferenceOmitted, rescore, loadReviews, assertSameSource } = require('../../../scripts/eval-metrics.cjs');

// caseId는 가져오기 순번이라 원본이 바뀌면 같은 번호가 다른 상품이 된다. 재채점 전에 막아야 한다.
test('계획과 검토 스냅샷의 원본이 다르면 재채점하지 않는다', () => {
  const snapshot = source => ({ source: { sourceHash: source }, cases: [] });
  assert.throws(() => assertSameSource('aaa', snapshot('bbb')), /원본이 다릅니다/);
  // 같은 원본이거나, 한쪽에 sourceHash가 없으면(운영 표본) 통과시킨다.
  assertSameSource('aaa', snapshot('aaa'));
  assertSameSource(null, snapshot('bbb'));
  assertSameSource('aaa', snapshot(null));
  assertSameSource('aaa', { cases: [] });
});

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
  // 검증은 통과했지만 정책으로 자동 적용에서 빠진 결과는 검토로 센다.
  const held = run(true, [row('DIFFERENT')]); held.response.autoApplyCandidate = false;
  assert.equal(classify(held), 'REVIEW_AND_WRONG');
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

test('메모의 값없음 표식은 AI 생략을 정답, 값 제시를 오답으로 채점한다', () => {
  const reviews = loadReviews({ cases: [{ testCase: { id: 'c1' }, reviews: [
    { row: 1, status: 'REFERENCE_ISSUE', confirmedValue: '', note: 'AI 초안·사람 승인 [정답=값없음] 묶음 색상' },
    { row: 2, status: 'NEEDS_FIX', confirmedValue: '', note: '[정답=값없음]' }] }] });
  const fresh = rescore(run(true, [
    { row: 1, target: '색상', baseline: '블랙, 레드', actual: null, status: 'MISSING', confirmedValue: '' },
    { row: 2, target: '색상', baseline: '3종', actual: '차콜', status: 'DIFFERENT', confirmedValue: '' }]), reviews.get('c1'));
  assert.deepEqual(fresh.comparisons.map(c => c.status), ['EXACT', 'REGRESSION']);
  assert.equal(classify(fresh, true), 'FALSE_APPROVAL');
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
