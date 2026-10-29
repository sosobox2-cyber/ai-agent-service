// 사람이 승인한 검토 초안을 운영 평가 데이터셋에 일괄 저장한다. AI는 호출하지 않는다.
// 기본은 미리보기이며 --apply일 때만 백업을 받은 뒤 저장한다.
// 사용법: node scripts/apply-reviews.cjs logs/<평가폴더> <초안.json> <계획파일> <운영데이터셋ID> [--apply]
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { EMPTY_MARK } = require('./eval-metrics.cjs');

const ROOT = path.resolve(__dirname, '..');
const BASE = 'https://ai-agent-service-production-7624.up.railway.app';
const NOTE_PREFIX = 'AI 초안·사람 승인';

// 초안 한 행을 평가 API 검토 명령으로 바꾼다. 빈 정답은 확정값을 비우고 메모 표식으로 남긴다.
function command(draft, runId) {
  const empty = draft.value == null || !String(draft.value).trim();
  // 사람이 행마다 보지 않은 초안은 notePrefix로 출처를 구분한다(예: "AI 초안·표본 확인").
  const note = `${draft.notePrefix || NOTE_PREFIX}${empty ? ' ' + EMPTY_MARK : ''} ${String(draft.reason || '').slice(0, 1500)}`.trim();
  return { status: draft.status, note, confirmedValue: draft.status === 'SUCCESS' || empty ? '' : String(draft.value), runId };
}

// 서버 상태가 초안 작성 시점과 같은지 확인한다. 다르면 저장하지 않고 사유를 돌려준다.
// replace=true이면 이 도구가 저장한 검토(메모가 초안 표식으로 시작)만 고쳐 쓴다. 사람이 화면에서 쓴 검토는 덮지 않는다.
const TOOL_NOTES = [NOTE_PREFIX, 'AI 초안·', '사람 답변'];
function check(draft, detail, expectedRunId, replace = false) {
  const latest = detail.latest;
  if (!latest || latest.id !== expectedRunId) return '최신 실행이 초안 작성 때와 다름';
  const prior = detail.reviews?.[draft.row];
  if (prior && prior.status !== 'UNREVIEWED'
      && !(replace && TOOL_NOTES.some(p => String(prior.note || '').startsWith(p)))) return `이미 검토됨(${prior.status})`;
  const cmp = (latest.comparisons || []).find(c => c.row === draft.row);
  if (!cmp) return '기준 행 없음';
  if (draft.status === 'SUCCESS' && (!latest.response?.success || cmp.actual !== draft.value)) return '성공 체크 조건 불일치';
  return null;
}

async function main() {
  const args = process.argv.slice(2), apply = args.includes('--apply'), replace = args.includes('--replace');
  const [folder, draftFile, planFile, datasetId] = args.filter(a => a !== '--apply' && a !== '--replace');
  if (!folder || !draftFile || !planFile || !/^[a-f0-9-]{36}$/.test(datasetId || ''))
    throw Error('사용법: node scripts/apply-reviews.cjs logs/<평가폴더> <초안.json> <계획파일> <운영데이터셋ID> [--apply]');
  const dir = path.resolve(folder);
  if (!dir.startsWith(path.join(ROOT, 'logs') + path.sep)) throw Error('평가 폴더는 이 저장소 logs 아래여야 합니다.');
  const drafts = JSON.parse(fs.readFileSync(path.join(dir, draftFile), 'utf8'));
  const plan = JSON.parse(fs.readFileSync(path.join(dir, planFile), 'utf8'));
  const trials = new Set(plan.selected.map(s => s.trialId));
  const runIds = new Map(fs.readFileSync(path.join(dir, 'ledger.jsonl'), 'utf8').trim().split(/\r?\n/).map(JSON.parse)
    .filter(e => e.event === 'completed' && trials.has(e.trialId) && e.runId).map(e => [e.caseId, e.runId]));
  const headers = process.env.AI_AGENT_API_KEY ? { 'X-API-Key': process.env.AI_AGENT_API_KEY } : {};
  const root = `${BASE}/api/v1/evaluation/datasets/${datasetId}`;
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  if (apply) {
    const backup = await fetch(`${root}/backup`, { headers, signal: AbortSignal.timeout(120000) });
    if (!backup.ok) throw Error(`백업 실패 HTTP ${backup.status}. 저장하지 않았습니다.`);
    const file = path.join(dir, `backup-before-review-${stamp}.zip`);
    fs.writeFileSync(file, Buffer.from(await backup.arrayBuffer()), { flag: 'wx' });
    console.log(JSON.stringify({ backup: path.basename(file), bytes: fs.statSync(file).size }));
  }
  const journal = path.join(dir, `review-apply-${apply ? 'applied' : 'preview'}-${stamp}.jsonl`);
  const details = new Map(), result = { saved: 0, wouldSave: 0, skipped: 0, failed: 0, reasons: {} };
  for (const draft of drafts) {
    if (!details.has(draft.caseId)) {
      const r = await fetch(`${root}/cases/${draft.caseId}`, { headers, signal: AbortSignal.timeout(30000) });
      if (!r.ok) throw Error(`사례 조회 HTTP ${r.status}: ${draft.caseId}`);
      details.set(draft.caseId, await r.json());
    }
    const skip = check(draft, details.get(draft.caseId), runIds.get(draft.caseId), replace);
    const body = command(draft, runIds.get(draft.caseId));
    let outcome = skip ? 'skipped' : apply ? 'saved' : 'wouldSave', error = skip;
    if (!skip && apply) {
      const r = await fetch(`${root}/cases/${draft.caseId}/reviews/${draft.row}`, { method: 'PUT',
        headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: AbortSignal.timeout(30000) });
      if (!r.ok) { outcome = 'failed'; error = `HTTP ${r.status} ${(await r.text()).slice(0, 200)}`; }
    }
    result[outcome]++;
    if (error) result.reasons[error] = (result.reasons[error] || 0) + 1;
    fs.appendFileSync(journal, JSON.stringify({ queueNo: draft.queueNo, caseId: draft.caseId, row: draft.row,
      status: body.status, confirmedValue: body.confirmedValue, empty: body.note.includes(EMPTY_MARK), outcome, error }) + '\n');
  }
  console.log(JSON.stringify({ mode: apply ? 'apply' : 'preview', journal: path.basename(journal), ...result }));
}

module.exports = { command, check };
if (require.main === module) main().catch(e => { console.error(e.message); process.exitCode = 1; });
