'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { verdicts, compare, mcnemar, binomialAtMost } = require('../../../scripts/eval-compare.cjs');

// 보고서의 실제 수치로 검산한다. docs/report/2026-10-29/holdout150-반복실행_단계.md 기준:
// v43→v44 불일치쌍 13 대 4, 동일 v44 반복 불일치쌍 7 대 2.
test('McNemar 정확검정은 불일치쌍만으로 p를 낸다', () => {
  // n=17, k=4: 2 * (1+17+136+680+2380) / 2^17
  const versionChange = mcnemar(13, 4);
  assert.equal(versionChange.discordant, 17);
  assert.ok(Math.abs(versionChange.p - 0.0490) < 0.0005, `p=${versionChange.p}`);

  // n=9, k=2: 2 * (1+9+36) / 2^9
  const repeat = mcnemar(7, 2);
  assert.ok(Math.abs(repeat.p - 0.1797) < 0.0005, `p=${repeat.p}`);

  // 버전 변경이 반복 변동보다 작은 p를 낸다는 것이 이 도구의 요점이다.
  assert.ok(versionChange.p < repeat.p);
});

test('불일치쌍이 대칭이거나 없으면 p는 1이다', () => {
  assert.equal(mcnemar(5, 5).p, 1);
  assert.deepEqual(mcnemar(0, 0), { b: 0, c: 0, discordant: 0, p: 1 });
  assert.equal(mcnemar(3, 0).p, 0.25); // 2 * (1/2^3)
});

test('이항 누적확률은 n이 커져도 1을 넘지 않는다', () => {
  assert.equal(binomialAtMost(0, 0), 1);
  assert.ok(Math.abs(binomialAtMost(10, 20) - 0.5881) < 0.0005);
  assert.ok(binomialAtMost(40, 40) <= 1 + 1e-12);
});

const arm = entries => new Map(entries.map(([caseId, verdict, version = 'coupang-option-v44']) =>
  [caseId, { verdict, version }]));

test('양쪽에 모두 있고 채점 가능한 사례만 짝짓는다', () => {
  const a = arm([['c1', 'APPROVED_CORRECT'], ['c2', 'FALSE_APPROVAL'], ['c3', 'ERROR'], ['c4', 'APPROVED_CORRECT']]);
  const b = arm([['c1', 'APPROVED_CORRECT'], ['c2', 'REVIEW_AND_WRONG'], ['c3', 'APPROVED_CORRECT'], ['c5', 'UNSCORED']]);
  const result = compare(a, b);
  assert.equal(result.common, 2);          // c1, c2
  assert.equal(result.changed, 1);         // c2
  assert.equal(result.skipped.onlyA, 1);   // c4
  assert.equal(result.skipped.onlyB, 1);   // c5
  assert.equal(result.skipped.unscored, 1); // c3
});

test('오승인이 승인 유지인 채로 정답으로 바뀌면 자동 적용은 불일치가 아니다', () => {
  const a = arm([['c1', 'FALSE_APPROVAL']]);
  const b = arm([['c1', 'APPROVED_CORRECT']]);
  const result = compare(a, b);
  assert.equal(result.outcomes.approved.discordant, 0);    // 양쪽 다 승인
  assert.equal(result.outcomes.falseApproval.aOnly, 1);    // A만 오승인
  assert.equal(result.outcomes.correct.bOnly, 1);          // B만 정답
});

test('같은 버전 두 팔은 영가설 띠로 표시한다', () => {
  const same = compare(arm([['c1', 'APPROVED_CORRECT']]), arm([['c1', 'FALSE_APPROVAL']]));
  assert.equal(same.sameVersion, true);
  const across = compare(arm([['c1', 'APPROVED_CORRECT', 'coupang-option-v43']]),
    arm([['c1', 'FALSE_APPROVAL', 'coupang-option-v44']]));
  assert.equal(across.sameVersion, false);
  assert.deepEqual(across.versions, { a: ['coupang-option-v43'], b: ['coupang-option-v44'] });
});

test('판정 전이를 사례 번호와 함께 센다', () => {
  const a = arm([['c1', 'APPROVED_CORRECT'], ['c2', 'APPROVED_CORRECT']]);
  const b = arm([['c1', 'REVIEW_BUT_CORRECT'], ['c2', 'REVIEW_BUT_CORRECT']]);
  const result = compare(a, b);
  assert.deepEqual(result.transitions['APPROVED_CORRECT → REVIEW_BUT_CORRECT'], ['c1', 'c2']);
  assert.equal(result.outcomes.approved.aOnly, 2);
  assert.equal(result.outcomes.approved.p, 0.5); // 2 * (1/2^2)
});

test('원장의 계획별 실행에서 사례 판정을 읽는다', t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'eval-compare-'));
  t.after(() => fs.rmSync(dir, { recursive: true }));
  const write = (id, caseId, success, status) => {
    fs.writeFileSync(path.join(dir, id + '.json'), JSON.stringify({ id,
      response: { success, autoApplyCandidate: success, promptVersion: 'coupang-option-v44', validationErrors: [] },
      comparisons: [{ row: 1, target: '수량', baseline: '3개', actual: '3개', status, confirmedValue: '3개' }] }));
    return { id, event: 'completed', caseId, trialId: `${caseId}-t`, costUsd: 0.002 };
  };
  const ledger = [write('r1', 'c1', true, 'EXACT'), write('r2', 'c2', true, 'DIFFERENT'),
    write('r3', 'c3', false, 'EXACT')];
  fs.writeFileSync(path.join(dir, 'ledger.jsonl'), ledger.map(e => JSON.stringify(e)).join('\n') + '\n');

  const plan = { selected: [{ caseId: 'c1', trialId: 'c1-t' }, { caseId: 'c2', trialId: 'c2-t' }] };
  const result = verdicts(dir, plan, null);
  assert.equal(result.size, 2);                                  // 계획에 없는 c3은 제외
  assert.equal(result.get('c1').verdict, 'APPROVED_CORRECT');
  assert.equal(result.get('c2').verdict, 'FALSE_APPROVAL');
  assert.equal(result.get('c1').version, 'coupang-option-v44');
});
