// 두 실행(버전 또는 반복)을 같은 사례끼리 짝지어 비교한다. AI·서버를 호출하지 않는다.
// 사용법: node scripts/eval-compare.cjs logs/<평가폴더> <계획A.json> <계획B.json> [--reviews <git-snapshot.json>] [--json]
//
// eval-metrics.cjs는 버전별 비율을 각각 Wilson 구간으로 낸다. 그 방식은 두 팔이 서로 다른 표본일 때의
// 비교라서, 같은 150상품을 두 번 돌린다는 사실을 쓰지 못한다. 여기서는 사례별 판정을 짝지어
// 불일치쌍만으로 McNemar 정확검정을 낸다. 같은 데이터에서 분해능이 올라간다.
//
// 중요: 같은 버전을 두 번 돌려도 판정은 흔들린다. 그 반복 비교가 영가설 띠이며,
// 버전 간 불일치가 그 띠를 넘지 못하면 개선이라고 말할 수 없다.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { classify, rescore, loadReviews, assertSameSource, readLines } = require('./eval-metrics.cjs');

const ROOT = path.resolve(__dirname, '..');

// 상품 단위 판정 5종을 이진 결과 3개로 바꾼다. UNSCORED/ERROR는 호출 전에 제외한다.
const APPROVED = new Set(['APPROVED_CORRECT', 'FALSE_APPROVAL']);
const CORRECT = new Set(['APPROVED_CORRECT', 'REVIEW_BUT_CORRECT']);
const OUTCOMES = [
  { key: 'approved', label: '자동 적용', of: v => APPROVED.has(v) },
  { key: 'correct', label: 'AI 제안 정답', of: v => CORRECT.has(v) },
  { key: 'falseApproval', label: '오승인', of: v => v === 'FALSE_APPROVAL' },
];

/** 계획 파일의 trialId로 원장을 걸러 사례별 판정을 만든다. 같은 사례가 여러 번이면 마지막 기록을 쓴다. */
function verdicts(dir, plan, reviews, gold = true) {
  const trialIds = new Set(plan.selected.map(s => s.trialId || s.caseId));
  const result = new Map();
  for (const e of readLines(path.join(dir, 'ledger.jsonl'))) {
    if (e.event !== 'completed' || !trialIds.has(e.trialId)) continue;
    const file = path.join(dir, e.id + '.json');
    const stored = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : null;
    const run = reviews ? rescore(stored, reviews.get(e.caseId)) : stored;
    result.set(e.caseId, { verdict: classify(run, gold), version: (run && run.response && run.response.promptVersion) || null });
  }
  return result;
}

/** 이항분포 B(n, 0.5)에서 k 이하가 나올 확률. n이 작아 정확히 더한다. */
function binomialAtMost(k, n) {
  let coefficient = 1, sum = 0;
  for (let i = 0; i <= k; i++) {
    if (i > 0) coefficient = coefficient * (n - i + 1) / i;
    sum += coefficient;
  }
  return sum / Math.pow(2, n);
}

/** McNemar 정확검정. b·c는 불일치쌍이고 일치쌍은 검정에 쓰이지 않는다. */
function mcnemar(b, c) {
  const n = b + c;
  if (!n) return { b, c, discordant: 0, p: 1 };
  const p = Math.min(1, 2 * binomialAtMost(Math.min(b, c), n));
  return { b, c, discordant: n, p };
}

function compare(a, b) {
  // 양쪽에 모두 있고 채점 가능한 사례만 쓴다. 한쪽에만 있거나 ERROR/UNSCORED면 짝이 성립하지 않는다.
  const skipped = { onlyA: 0, onlyB: 0, unscored: 0 };
  const common = [];
  for (const [caseId, left] of a) {
    const right = b.get(caseId);
    if (!right) { skipped.onlyA++; continue; }
    if (['ERROR', 'UNSCORED'].includes(left.verdict) || ['ERROR', 'UNSCORED'].includes(right.verdict)) { skipped.unscored++; continue; }
    common.push({ caseId, left: left.verdict, right: right.verdict });
  }
  for (const caseId of b.keys()) if (!a.has(caseId)) skipped.onlyB++;

  const outcomes = {};
  for (const outcome of OUTCOMES) {
    let bOnly = 0, cOnly = 0, both = 0, neither = 0;
    for (const row of common) {
      const left = outcome.of(row.left), right = outcome.of(row.right);
      if (left && !right) bOnly++; else if (!left && right) cOnly++; else if (left) both++; else neither++;
    }
    outcomes[outcome.key] = { label: outcome.label, aOnly: bOnly, bOnly: cOnly, both, neither,
      aRate: common.length ? (bOnly + both) / common.length : null,
      bRate: common.length ? (cOnly + both) / common.length : null,
      ...mcnemar(bOnly, cOnly) };
  }

  const transitions = {};
  for (const row of common) if (row.left !== row.right) {
    const key = `${row.left} → ${row.right}`;
    if (!transitions[key]) transitions[key] = [];
    transitions[key].push(row.caseId);
  }
  const versionOf = map => [...new Set([...map.values()].map(v => v.version).filter(Boolean))];
  const left = versionOf(a), right = versionOf(b);
  return { common: common.length, changed: common.filter(r => r.left !== r.right).length, skipped,
    versions: { a: left, b: right },
    sameVersion: left.length === 1 && right.length === 1 && left[0] === right[0],
    outcomes, transitions };
}

const pct = v => v === null ? '-' : `${(v * 100).toFixed(1)}%`;

function render(result) {
  const lines = [];
  const { a, b } = result.versions;
  lines.push(`공통 채점 상품 ${result.common}건 · 판정이 바뀐 상품 ${result.changed}건`
    + (result.common ? ` (${pct(result.changed / result.common)})` : ''));
  lines.push(`  A: ${a.join(', ') || '(버전 불명)'}   B: ${b.join(', ') || '(버전 불명)'}`);
  if (result.skipped.onlyA || result.skipped.onlyB || result.skipped.unscored)
    lines.push(`  제외: A에만 ${result.skipped.onlyA} · B에만 ${result.skipped.onlyB} · 미채점/오류 ${result.skipped.unscored}`);

  lines.push('');
  for (const outcome of OUTCOMES) {
    const o = result.outcomes[outcome.key];
    lines.push(`[${o.label}] A ${pct(o.aRate)} → B ${pct(o.bRate)}`);
    lines.push(`  불일치쌍 A만 ${o.aOnly} · B만 ${o.bOnly} (일치쌍 ${o.both + o.neither}건은 검정에 쓰이지 않음)`);
    lines.push(`  McNemar 정확검정 p = ${o.p.toFixed(4)}${o.p < 0.05 ? '' : '  (유의하지 않음)'}`);
  }

  lines.push('');
  const changes = Object.entries(result.transitions).sort((x, y) => y[1].length - x[1].length);
  if (changes.length) {
    lines.push('판정 전이:');
    for (const [key, ids] of changes) lines.push(`  ${ids.length}건  ${key}  ${ids.slice(0, 6).join(', ')}${ids.length > 6 ? ' …' : ''}`);
  }

  lines.push('');
  lines.push(result.sameVersion
    ? '동일 버전 반복이다. 여기서 나온 불일치는 버전 효과가 아니라 측정 변동이며, 버전 비교의 영가설 띠로 쓴다.'
    : '같은 버전을 두 번 돌린 비교를 함께 실행해, 이 불일치가 측정 변동을 넘는지 확인한다.');
  lines.push('짝지은 검정은 같은 사례의 전후 판정을 쓴다. 두 비율을 각각 Wilson 구간으로 비교하는 방식보다 분해능이 높다.');
  return lines.join('\n');
}

function main() {
  const args = process.argv.slice(2), json = args.includes('--json');
  const at = args.indexOf('--reviews'), reviewFile = at >= 0 ? args[at + 1] : null;
  const rest = args.filter((value, i) => value !== '--json' && (at < 0 || (i !== at && i !== at + 1)));
  const [folder, planA, planB] = rest;
  if (!folder || !planA || !planB || (at >= 0 && !reviewFile)) throw Error(
    '사용법: node scripts/eval-compare.cjs logs/<평가폴더> <계획A.json> <계획B.json> [--reviews <git-snapshot.json>] [--json]');
  const dir = path.resolve(folder);
  if (!dir.startsWith(path.join(ROOT, 'logs') + path.sep)) throw Error('평가 폴더는 이 저장소 logs 아래여야 합니다.');

  const readPlan = name => {
    if (!/^(plan|fixed)[a-z0-9-]*\.json$/.test(name)) throw Error(`계획 파일 이름이 잘못되었습니다: ${name}`);
    return JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8').replace(/^﻿/, ''));
  };
  const a = readPlan(planA), b = readPlan(planB);

  let reviews = null;
  if (reviewFile) {
    const file = path.resolve(reviewFile);
    if (!file.startsWith(ROOT + path.sep)) throw Error('검토 스냅샷은 이 저장소 안에 있어야 합니다.');
    const snapshot = JSON.parse(fs.readFileSync(file, 'utf8').replace(/^﻿/, ''));
    // 두 계획 모두 같은 원본이어야 caseId로 검토를 연결할 수 있다.
    assertSameSource(a.sourceHash || null, snapshot);
    assertSameSource(b.sourceHash || null, snapshot);
    reviews = loadReviews(snapshot);
  }

  const result = compare(verdicts(dir, a, reviews), verdicts(dir, b, reviews));
  if (!result.common) throw Error('두 계획에 공통으로 채점 가능한 사례가 없습니다.');
  console.log(json ? JSON.stringify(result, null, 2) : render(result));
}

module.exports = { verdicts, compare, mcnemar, binomialAtMost, render };
if (require.main === module) try { main(); } catch (e) { console.error(e.message); process.exitCode = 1; }
