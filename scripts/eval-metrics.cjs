// 평가 원장과 실행 결과로 버전별 판정 지표를 집계한다. AI·서버를 호출하지 않는다.
// 사용법: node scripts/eval-metrics.cjs logs/<평가폴더> [계획파일] [--json]
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { accounting, BUDGET_USD } = require('./eval-diverse.cjs');

const ROOT = path.resolve(__dirname, '..');
const MATCH = new Set(['EXACT', 'EQUIVALENT']);
const readLines = file => fs.existsSync(file) ? fs.readFileSync(file, 'utf8').trim().split(/\r?\n/).filter(Boolean).map(JSON.parse) : [];

// ValueComparison.java와 같은 규칙. 실행 기록의 비교는 실행 당시 검토 기준이므로 최신 검토로 다시 채점할 때 쓴다.
const SCALE = { kg: [1000, 'weight'], g: [1, 'weight'], mg: [0.001, 'weight'], l: [1000, 'volume'], ml: [1, 'volume'],
  m: [1000, 'length'], cm: [10, 'length'], mm: [1, 'length'] };
const normalizeValue = v => v.normalize('NFKC').toLowerCase().replace(/[\s\p{Z}]+/gu, '').replace(/(?<=\d),(?=\d{3}(?:\D|$))/g, '');
function compareValue(expected, actual) {
  if (expected == null || !expected.trim()) return 'NO_REFERENCE';
  if (actual == null || !actual.trim()) return 'MISSING';
  if (expected === actual) return 'EXACT';
  const left = normalizeValue(expected), right = normalizeValue(actual);
  if (left === right) return 'EQUIVALENT';
  const amount = /^([0-9]+(?:\.[0-9]+)?)(kg|g|mg|l|ml|m|cm|mm)$/, a = left.match(amount), b = right.match(amount);
  if (a && b && SCALE[a[2]][1] === SCALE[b[2]][1]) {
    const x = Number(a[1]) * SCALE[a[2]][0], y = Number(b[1]) * SCALE[b[2]][0];
    if (Math.abs(x - y) <= 1e-9 * Math.max(1, Math.abs(x))) return 'EQUIVALENT';
  }
  return 'DIFFERENT';
}

const EMPTY_MARK = '[정답=값없음]', EMPTY_VALUE = '(값 없음)';
// 최신 검토로 비교를 다시 계산한다. 확정값이 없는 REFERENCE_ISSUE 행은 기준이 틀렸다는 뜻이므로 채점에서 뺀다.
function rescore(run, reviews) {
  if (!run || !reviews || !Array.isArray(run.comparisons)) return run;
  return { ...run, comparisons: run.comparisons.map(c => {
    const review = reviews.get(c.row);
    if (c.status === 'DUPLICATE') return c;
    // 검토가 취소된 행은 원본 등록값 기준으로 되돌린다.
    if (!review) return c.confirmedValue ? { ...c, confirmedValue: '', status: compareValue(c.baseline, c.actual) } : c;
    const confirmed = (review.confirmedValue || '').trim();
    // 화면의 확정값은 빈 값을 저장할 수 없다. 메모에 EMPTY_MARK가 있으면 "정답은 값 없음"으로 채점한다.
    // 예: 사이즈만 다른 묶음 상품의 색상. AI가 생략하면 정답, 값을 내면 오답이다.
    if (!confirmed && (review.note || '').includes(EMPTY_MARK)) {
      const empty = c.actual == null || !String(c.actual).trim();
      return { ...c, confirmedValue: EMPTY_VALUE, status: empty ? 'EXACT' : 'REGRESSION' };
    }
    if (!confirmed) return review.status === 'REFERENCE_ISSUE' ? { ...c, confirmedValue: '', status: 'NO_REFERENCE' } : c;
    let status = compareValue(confirmed, c.actual);
    if (!MATCH.has(status)) status = 'REGRESSION';
    return { ...c, confirmedValue: confirmed, status };
  }) };
}

// export-evaluation.ps1의 git-snapshot JSON에서 상품별 검토를 읽는다.
function loadReviews(snapshot) {
  const result = new Map();
  for (const item of snapshot.cases || []) {
    const rows = new Map();
    for (const r of item.reviews || []) if (r && Number.isInteger(r.row) && r.status !== 'UNREVIEWED') rows.set(r.row, r);
    if (rows.size) result.set(item.testCase.id, rows);
  }
  return result;
}

// 상품 단위 판정. 기준값이 있는 행만 채점하고, 거절된 실행은 AI 제안값으로 비교된 결과를 쓴다.
// 원본 등록값에는 `핑크/FREE`, 색상 칸의 숫자 코드 같은 잡음이 많다. gold는 사람이 확정한 행만 채점한다.
const scoredRows = (run, gold) => (run.comparisons || []).filter(c => c.status !== 'NO_REFERENCE'
  && (!gold || (c.confirmedValue && c.confirmedValue.trim())));
function classify(run, gold = false) {
  if (!run || run.error || !run.response) return 'ERROR';
  const scored = scoredRows(run, gold);
  if (!scored.length) return 'UNSCORED';
  const correct = scored.every(c => MATCH.has(c.status));
  // 자동 적용 기준. 검증은 통과했지만 정책으로 보류된 결과(autoApplyCandidate=false)는 검토로 센다.
  if (run.response.success === true && run.response.autoApplyCandidate !== false) return correct ? 'APPROVED_CORRECT' : 'FALSE_APPROVAL';
  return correct ? 'REVIEW_BUT_CORRECT' : 'REVIEW_AND_WRONG';
}

// 서버 비교(compareValue)는 바꾸지 않는다. 등록값 기준 오답 중 표기만 다를 가능성이 큰 행을 따로 센다.
// 예: `킹`↔`킹(K)`, `M4W6(230mm)`↔`230mm`, `핑크/FREE`↔`핑크`, `화이트`↔`화이트계열`, `24개입`↔`24매입`.
// 정답으로 바꾸지 않고 "표기 차이 의심"으로만 보고해 사람 검토 우선순위와 지표의 상한 추정에 쓴다.
const COUNT_UNITS = new Set(['개', '개입', '매', '매입', '입', 'ea', 'p']);
function nearMatch(expected, actual) {
  if (!expected || !actual) return false;
  const a = normalizeValue(expected), b = normalizeValue(actual);
  if (a === b) return false;
  const forms = v => {
    const out = new Set([v]);
    const paren = v.match(/^(.+?)\((.+)\)$/);
    if (paren) { out.add(paren[1]); out.add(paren[2]); }
    out.add(v.replace(/\/(free|f|프리)$/, ''));
    out.add(v.replace(/계열$/, ''));
    return out;
  };
  const left = forms(a), right = forms(b);
  for (const x of left) if (x && right.has(x)) return true;
  const ca = a.match(/^([0-9]+)([^0-9]+)$/), cb = b.match(/^([0-9]+)([^0-9]+)$/);
  return !!(ca && cb && ca[1] === cb[1] && COUNT_UNITS.has(ca[2]) && COUNT_UNITS.has(cb[2]));
}

// 등록값이 여러 값의 목록(`블랙, 그린, 레드`, `12종`)인데 AI가 단품 값을 생략한 행. 사이즈만 다른 단품에
// 상품 전체 색상 목록을 기준으로 넣은 경우가 많아, AI 생략이 오히려 맞을 수 있다.
function listReferenceOmitted(expected, actual) {
  return !!expected && (actual == null || !String(actual).trim())
    && (/[,，/]\s*\S/.test(expected) || /^\d+\s*종$/.test(expected.trim()));
}
const suspectRow = (expected, actual) => nearMatch(expected, actual) || listReferenceOmitted(expected, actual);

// 작은 표본의 비율은 점추정만으로 비교하지 않는다. Wilson 95% 구간을 함께 낸다.
function wilson(k, n) {
  if (!n) return null;
  const z = 1.96, p = k / n, d = 1 + z * z / n;
  const center = (p + z * z / (2 * n)) / d, half = z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d;
  return { rate: p, low: Math.max(0, center - half), high: Math.min(1, center + half) };
}

function summarize(entries, gold = false) {
  const latest = new Map(); let repeats = 0;
  for (const e of entries) { if (latest.has(e.caseId)) repeats++; latest.set(e.caseId, e); }
  const counts = { APPROVED_CORRECT: 0, FALSE_APPROVAL: 0, REVIEW_BUT_CORRECT: 0, REVIEW_AND_WRONG: 0, UNSCORED: 0, ERROR: 0 };
  const targets = {}, reasons = {};
  // 오답 판정 상품 중 틀린 행이 모두 표기 차이 의심인 상품 수. 이 수만큼이 지표의 낙관적 상한이다.
  const nearOnly = { FALSE_APPROVAL: 0, REVIEW_AND_WRONG: 0 }; let nearRows = 0;
  let cost = 0, costed = 0;
  for (const e of entries) if (typeof e.costUsd === 'number') { cost += e.costUsd; costed++; }
  for (const { run } of latest.values()) {
    const kind = classify(run, gold); counts[kind]++;
    if (kind === 'REVIEW_BUT_CORRECT' || kind === 'REVIEW_AND_WRONG')
      for (const code of run.response.validationErrors || []) {
        const key = String(code).split(':')[0].trim().slice(0, 60); reasons[key] = (reasons[key] || 0) + 1;
      }
    if (kind === 'ERROR' || kind === 'UNSCORED') continue;
    const approved = run.response.success === true && run.response.autoApplyCandidate !== false;
    if (kind === 'FALSE_APPROVAL' || kind === 'REVIEW_AND_WRONG') {
      const wrong = scoredRows(run, gold).filter(c => !MATCH.has(c.status));
      // 사람 확정값은 이미 사람이 판단한 기준이므로 의심 분류에서 뺀다.
      const near = gold ? [] : wrong.filter(c => suspectRow(c.baseline, c.actual));
      nearRows += near.length;
      if (near.length === wrong.length) nearOnly[kind]++;
    }
    for (const c of scoredRows(run, gold)) {
      const t = targets[c.target] ||= { approvedRows: 0, approvedWrong: 0, reviewedRows: 0, reviewedCorrect: 0 };
      if (approved) { t.approvedRows++; if (!MATCH.has(c.status)) t.approvedWrong++; }
      else { t.reviewedRows++; if (MATCH.has(c.status)) t.reviewedCorrect++; }
    }
  }
  const scored = counts.APPROVED_CORRECT + counts.FALSE_APPROVAL + counts.REVIEW_BUT_CORRECT + counts.REVIEW_AND_WRONG;
  const approved = counts.APPROVED_CORRECT + counts.FALSE_APPROVAL, reviewed = scored - approved;
  return { cases: latest.size, repeats, scored, counts,
    falseApprovalAmongApproved: wilson(counts.FALSE_APPROVAL, approved),
    autoApprovalRate: wilson(approved, scored),
    correctButReviewed: wilson(counts.REVIEW_BUT_CORRECT, reviewed),
    aiCorrectRate: wilson(counts.APPROVED_CORRECT + counts.REVIEW_BUT_CORRECT, scored),
    notation: { rows: nearRows, falseApprovalOnly: nearOnly.FALSE_APPROVAL, reviewWrongOnly: nearOnly.REVIEW_AND_WRONG,
      falseApprovalIfNotation: wilson(counts.FALSE_APPROVAL - nearOnly.FALSE_APPROVAL, approved) },
    targets, topReviewReasons: Object.entries(reasons).sort((a, b) => b[1] - a[1]).slice(0, 8),
    meanCostUsd: costed ? cost / costed : null, costUsd: cost };
}

function collect(dir, trialIds, reviews = null) {
  const events = readLines(path.join(dir, 'ledger.jsonl'));
  const byVersion = new Map();
  for (const e of events) {
    if (e.event !== 'completed' || (trialIds && !trialIds.has(e.trialId))) continue;
    const file = path.join(dir, e.id + '.json');
    const stored = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : null;
    const run = reviews ? rescore(stored, reviews.get(e.caseId)) : stored;
    const version = run?.response?.promptVersion || '(응답 없음)';
    if (!byVersion.has(version)) byVersion.set(version, []);
    byVersion.get(version).push({ caseId: e.caseId, costUsd: e.costUsd, run });
  }
  const ledger = accounting(events);
  return { versions: Object.fromEntries([...byVersion].map(([v, list]) =>
      [v, { gold: summarize(list, true), baseline: summarize(list, false) }])),
    budget: { limitUsd: BUDGET_USD, accountedUsd: ledger.spent, remainingUsd: BUDGET_USD - ledger.spent, pending: ledger.pending.length } };
}

// 운영 표본처럼 로컬 원장이 없는 데이터셋은 git-snapshot의 사례별 최신 실행과 검토로 바로 집계한다.
function collectSnapshot(snapshot) {
  const reviews = loadReviews(snapshot), byVersion = new Map();
  for (const item of snapshot.cases || []) {
    const runs = (item.runs || []).filter(r => r.mode === 'AI').sort((a, b) => String(a.startedAt).localeCompare(String(b.startedAt)));
    const latest = runs[runs.length - 1];
    if (!latest) continue;
    const run = rescore(latest, reviews.get(item.testCase.id));
    const version = run?.response?.promptVersion || '(응답 없음)';
    if (!byVersion.has(version)) byVersion.set(version, []);
    byVersion.get(version).push({ caseId: item.testCase.id, costUsd: null, run });
  }
  return { versions: Object.fromEntries([...byVersion].map(([v, list]) =>
      [v, { gold: summarize(list, true), baseline: summarize(list, false) }])), budget: null };
}

const pct = r => r ? `${(r.rate * 100).toFixed(1)}% [${(r.low * 100).toFixed(1)}–${(r.high * 100).toFixed(1)}]` : '-';

function render(result, planSize) {
  const lines = [];
  const rates = (title, s) => [`  [${title}] 채점 ${s.scored}건`,
    `    오승인율(승인 중 오답)  ${pct(s.falseApprovalAmongApproved)}   ${s.counts.FALSE_APPROVAL}건`,
    `    자동승인율            ${pct(s.autoApprovalRate)}`,
    `    정답인데 검토 전환    ${pct(s.correctButReviewed)}   ${s.counts.REVIEW_BUT_CORRECT}건`,
    `    AI 제안 정답률        ${pct(s.aiCorrectRate)}`,
    ...(title.startsWith('사람') ? [] : [`    기준값 의심(표기 차이·목록 기준 생략) ${s.notation.rows}행 · 이것만 틀린 오승인 ${s.notation.falseApprovalOnly}건`
      + ` → 모두 기준값 문제라면 오승인율 ${pct(s.notation.falseApprovalIfNotation)}`])];
  for (const [version, view] of Object.entries(result.versions).sort()) {
    const s = view.baseline, g = view.gold;
    lines.push(`\n=== ${version}  상품 ${s.cases}건 (반복 호출 ${s.repeats}) ===`);
    lines.push(...(g.scored ? rates('사람 확정값 기준', g) : ['  [사람 확정값 기준] 확정된 행 없음 — 판정 품질을 말할 수 없음']));
    lines.push(...rates('원본 등록값 기준(잡음 포함)', s));
    lines.push(`  오류 ${s.counts.ERROR} / 기준값 없음 ${s.counts.UNSCORED}   비용 $${s.costUsd.toFixed(4)}`
      + (s.meanCostUsd ? ` (건당 $${s.meanCostUsd.toFixed(5)}${planSize ? `, ${planSize}건 예상 $${(s.meanCostUsd * planSize).toFixed(3)}` : ''})` : ''));
    const byTarget = g.scored ? g.targets : s.targets;
    lines.push(`  구매옵션별(${g.scored ? '확정값' : '등록값'} 기준, 승인 오답 많은 순 12개)`);
    for (const [t, v] of Object.entries(byTarget).sort((a, b) => b[1].approvedWrong - a[1].approvedWrong
        || b[1].reviewedCorrect - a[1].reviewedCorrect || a[0].localeCompare(b[0])).slice(0, 12))
      lines.push(`    ${t.padEnd(14)} 승인 ${v.approvedRows}행 중 오답 ${v.approvedWrong} | 검토 ${v.reviewedRows}행 중 정답 ${v.reviewedCorrect}`);
    if (s.topReviewReasons.length) lines.push('  검토 사유: ' + s.topReviewReasons.map(([k, n]) => `${k} ${n}`).join(', '));
  }
  const b = result.budget;
  if (b) lines.push(`\n예산 원장 $${b.accountedUsd.toFixed(4)} / $${b.limitUsd} (남은 $${b.remainingUsd.toFixed(4)}, 미완료 예약 ${b.pending})`);
  lines.push('구간은 Wilson 95%. ' + (result.reviewSnapshot
    ? `검토 스냅샷(${result.reviewSnapshot.cases}상품, ${result.reviewSnapshot.rows}행)으로 다시 채점했다.`
    : '실행 당시 비교 결과다. 최신 검토를 반영하려면 --reviews <git-snapshot.json>을 지정한다.'));
  return lines.join('\n');
}

function main() {
  const args = process.argv.slice(2), json = args.includes('--json');
  // --snapshot <git-snapshot.json>: 운영 표본처럼 원장 없는 데이터셋을 검토된 사례만으로 집계한다.
  const snap = args.indexOf('--snapshot');
  if (snap >= 0) {
    const file = path.resolve(args[snap + 1] || '');
    if (!args[snap + 1] || !file.startsWith(ROOT + path.sep)) throw Error('스냅샷은 이 저장소 안의 git-snapshot JSON이어야 합니다.');
    const snapshot = JSON.parse(fs.readFileSync(file, 'utf8').replace(/^﻿/, ''));
    const result = collectSnapshot(snapshot);
    const reviews = loadReviews(snapshot);
    result.reviewSnapshot = { cases: reviews.size, rows: [...reviews.values()].reduce((s, m) => s + m.size, 0) };
    console.log(json ? JSON.stringify(result, null, 2) : render(result, null));
    return;
  }
  const at = args.indexOf('--reviews'), reviewFile = at >= 0 ? args[at + 1] : null;
  const [folder, planFile] = args.filter((a, i) => a !== '--json' && (at < 0 || (i !== at && i !== at + 1)));
  if (!folder || (at >= 0 && !reviewFile)) throw Error(
    '사용법: node scripts/eval-metrics.cjs logs/<평가폴더> [계획파일] [--reviews <git-snapshot.json>] [--json]');
  const dir = path.resolve(folder);
  if (!dir.startsWith(path.join(ROOT, 'logs') + path.sep)) throw Error('평가 폴더는 이 저장소 logs 아래여야 합니다.');
  let trialIds = null, planSize = null;
  if (planFile) {
    if (!/^(plan|fixed)[a-z0-9-]*\.json$/.test(planFile)) throw Error('계획 파일 이름이 잘못되었습니다.');
    const plan = JSON.parse(fs.readFileSync(path.join(dir, planFile), 'utf8').replace(/^﻿/, ''));
    trialIds = new Set(plan.selected.map(s => s.trialId || s.caseId)); planSize = plan.selected.length;
  }
  let reviews = null;
  if (reviewFile) {
    const file = path.resolve(reviewFile);
    if (!file.startsWith(ROOT + path.sep)) throw Error('검토 스냅샷은 이 저장소 안에 있어야 합니다.');
    reviews = loadReviews(JSON.parse(fs.readFileSync(file, 'utf8').replace(/^﻿/, '')));
  }
  const result = collect(dir, trialIds, reviews);
  if (reviews) result.reviewSnapshot = { cases: reviews.size, rows: [...reviews.values()].reduce((s, m) => s + m.size, 0) };
  console.log(json ? JSON.stringify(result, null, 2) : render(result, planSize));
}

module.exports = { EMPTY_MARK, classify, wilson, summarize, collect, collectSnapshot, compareValue, nearMatch, listReferenceOmitted, rescore, loadReviews };
if (require.main === module) try { main(); } catch (e) { console.error(e.message); process.exitCode = 1; }
