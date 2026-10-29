// 로컬 저장 응답만 준비·재채점한다. 네트워크 호출과 로그·원장 쓰기는 없다.
// node scripts/replay-proposals.cjs prepare <평가폴더> <계획.json> <target/입력.json>
// node scripts/replay-proposals.cjs summarize <target/입력.json> <target/재생.json> <검토스냅샷.json>
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { rescore, loadReviews, classify } = require('./eval-metrics.cjs');
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'));
function prepare(directory, planFile, output) {
  const target = path.resolve(__dirname, '../target') + path.sep;
  if (!path.resolve(output).startsWith(target)) throw Error('출력은 target 아래여야 합니다.');
  const trials = new Set(read(planFile).selected.map(x => x.trialId));
  const latest = new Map();
  for (const event of fs.readFileSync(path.join(directory, 'ledger.jsonl'), 'utf8').trim().split(/\r?\n/).map(JSON.parse))
    if (event.event === 'completed' && trials.has(event.trialId)) latest.set(event.trialId, event);
  const rows = [];
  let missing = 0;
  for (const event of latest.values()) {
    // 로컬 파일명은 원장 이벤트 id이며 운영 실행 runId와 다르다.
    const run = read(path.join(directory, event.id + '.json'));
    if (!run.response?.aiAssessment) { missing++; continue; }
    rows.push({ caseId: event.caseId, request: run.request, proposal: run.response.aiAssessment,
      before: run.response.success, comparisons: run.comparisons });
  }
  fs.writeFileSync(output, JSON.stringify(rows), { flag: 'wx' });
  return { proposals: rows.length, missing };
}
function summarize(input, replay, snapshot) {
  const reviews = loadReviews(snapshot), results = new Map(replay.map(r => [r.caseId, r]));
  const summary = { replayed: input.length, beforeApproved: 0, afterApproved: 0,
    goldBefore: {}, goldAfter: {}, newFalseRejections: [], changes: [] };
  for (const row of input) {
    const result = results.get(row.caseId);
    if (!result) throw Error('재생 누락: ' + row.caseId);
    const before = rescore({ comparisons: row.comparisons, response: { success: row.before } }, reviews.get(row.caseId));
    const after = { ...before, response: { success: result.after } };
    const a = classify(before, true), b = classify(after, true);
    summary.beforeApproved += Number(row.before); summary.afterApproved += Number(result.after);
    summary.goldBefore[a] = (summary.goldBefore[a] || 0) + 1;
    summary.goldAfter[b] = (summary.goldAfter[b] || 0) + 1;
    if (a === 'APPROVED_CORRECT' && !result.after) summary.newFalseRejections.push(row.caseId);
    if (row.before !== result.after) summary.changes.push({ caseId: row.caseId, before: a, after: b, errors: result.errors });
  }
  return summary;
}
module.exports = { prepare, summarize };
if (require.main === module) {
  const [mode, a, b, c] = process.argv.slice(2);
  if (!a || !b || !c || !['prepare', 'summarize'].includes(mode)) throw Error('prepare/summarize와 세 경로가 필요합니다.');
  console.log(JSON.stringify(mode === 'prepare' ? prepare(a, b, c) : summarize(read(a), read(b), read(c)), null, 2));
}
