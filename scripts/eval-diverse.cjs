// 운영 평가 API용 표본 선정·순차 실행 도구. 원문/응답은 Git 제외 logs 아래에만 저장한다.
// plan은 과금하지 않는다. run은 기존 데이터셋에 실제 AI 실행 이력을 추가한다.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');

const ROOT = path.resolve(__dirname, '..');
const SIMPLE = new Set(['색상', '컬러', 'color', '사이즈', 'size', '패션의류/잡화 사이즈', '핏']);
const MODEL = /^(gpt-4\.1-mini|gpt-4\.1-mini-2025-04-14)$/;
// v27/v28/v29/v30/v31, max_tokens=4096, 최대 2회 호출을 확인한 운영 환경 전용 상한.
// 입력 65,536 tokens/회 + 출력 4,096 tokens/회를 비캐시 단가로 계산하면 $0.065536.
const RESERVE_USD = 0.08;
const BUDGET_USD = 1.45; // 사용자가 늘린 누적 $1.50 중 $0.05는 추가 여유로 남긴다.
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const readLines = file => fs.existsSync(file) ? fs.readFileSync(file, 'utf8').trim().split(/\r?\n/).filter(Boolean).map(JSON.parse) : [];
const writeJson = (file, value) => fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });

function features(c) {
  const r = c.request, text = [r.goodsName, r.productNoticeText, r.productCompositionText, ...r.options.map(o => o.optionName1)].join(' ');
  const single = r.options.some(o => !o.optionName1 || /^(단품|단일상품)$/.test(o.optionName1.trim()));
  const light = !r.purchaseOptionUnits.length && !single && r.allowedPurchaseOptions.every(t => SIMPLE.has(t.toLowerCase()));
  const result = [light ? 'LIGHT예상' : 'FULL예상', r.options.length === 1 ? '단품1개' : r.options.length <= 4 ? '단품2~4개' : '단품5개이상'];
  if (single) result.push('상품문맥추론');
  if (r.purchaseOptionUnits.length) result.push('단위설정');
  if (/\d\s*(?:[xX×*+]\s*\d|(?:개|팩|박스|세트|정|캡슐|g|ml)\s*[xX×*+])/i.test(text)) result.push('곱셈/합산표기');
  if (/증정|사은품|본품/.test(text)) result.push('본품/증정구분');
  if (/박스|세트|묶음|팩/.test(text)) result.push('포장구분');
  if (/상세.{0,5}참조|별도.{0,3}표기/.test(text)) result.push('참조안내');
  if (r.productCompositionText?.trim()) result.push('기술서있음');
  if (/\d\s*(?:kg|g|ml|cm|mm|인치|inch)/i.test(text)) result.push('물리단위');
  return result;
}

function selectCases(cases, limit = 72) {
  const counts = new Map();
  for (const c of cases) for (const t of c.request.allowedPurchaseOptions) counts.set(t, (counts.get(t) || 0) + 1);
  const candidates = cases.filter(c => !c.issues.length && c.request.options.length <= 8
    && Buffer.byteLength(JSON.stringify(c.request)) <= 8192).map(c => ({ c, tags: features(c),
      combo: [...c.request.allowedPurchaseOptions].sort().join('|'),
      category: c.request.categoryName.split('>')[0].trim(),
      family: c.request.goodsName.toLowerCase().replace(/\d+(?:\.\d+)?/g, '#').replace(/\s+/g, '') }));
  const chosen = [], covered = new Map(), combos = new Map(), categories = new Map(), tagCounts = new Map(), families = new Set();
  while (chosen.length < limit && candidates.length) {
    let best = -1, bestScore = -Infinity;
    for (let i = 0; i < candidates.length; i++) {
      const x = candidates[i];
      if (families.has(x.family)) continue;
      let score = x.c.request.allowedPurchaseOptions.reduce((s, t) => s + (!(covered.get(t) || 0) ? 1000 + 100 / Math.sqrt(counts.get(t)) : covered.get(t) === 1 ? 20 / Math.sqrt(counts.get(t)) : 0), 0);
      score += 15 / (1 + (combos.get(x.combo) || 0)) + 10 / (1 + (categories.get(x.category) || 0));
      score += x.tags.reduce((s, t) => s + 3 / (1 + (tagCounts.get(t) || 0)), 0);
      // 숫자나 브랜드만 다른 상품을 반복하기보다 새로운 구매옵션 조합을 먼저 본다.
      if ((combos.get(x.combo) || 0) >= 3 && x.c.request.allowedPurchaseOptions.every(t => covered.has(t))) score -= 100;
      score -= x.c.request.options.length * 0.15;
      if (score > bestScore || (score === bestScore && hash(x.c.id) < hash(candidates[best].c.id))) { best = i; bestScore = score; }
    }
    if (best < 0) break;
    const x = candidates.splice(best, 1)[0]; chosen.push(x);
    families.add(x.family);
    for (const t of x.c.request.allowedPurchaseOptions) covered.set(t, (covered.get(t) || 0) + 1);
    combos.set(x.combo, (combos.get(x.combo) || 0) + 1);
    categories.set(x.category, (categories.get(x.category) || 0) + 1);
    for (const t of x.tags) tagCounts.set(t, (tagCounts.get(t) || 0) + 1);
  }
  return { selected: chosen.map(x => ({ caseId: x.c.id, targets: x.c.request.allowedPurchaseOptions,
    category: x.category, tags: x.tags, options: x.c.request.options.length, references: x.c.references.length,
    requestHash: hash(JSON.stringify(x.c.request)) })),
    population: cases.length, targetCounts: Object.fromEntries(counts),
    selectedTargetCounts: Object.fromEntries(covered), uncovered: [...counts.keys()].filter(t => !covered.has(t)),
    selectedCategories: Object.fromEntries(categories), selectedTags: Object.fromEntries(tagCounts),
    selectedCombinations: combos.size };
}

function costOf(run) {
  const calls = run?.response?.aiUsage;
  if (!Array.isArray(calls) || !calls.length || calls.length > 2) return null;
  let total = 0;
  for (const u of calls) {
    if (!MODEL.test(u.model) || !Number.isInteger(u.input_tokens) || u.input_tokens < 0 || u.input_tokens > 65536
      || !Number.isInteger(u.output_tokens) || u.output_tokens < 0 || u.output_tokens > 4096
      || !Number.isInteger(u.cached_tokens) || u.cached_tokens < 0 || u.cached_tokens > u.input_tokens
      || typeof u.estimated_cost_usd !== 'number' || !Number.isFinite(u.estimated_cost_usd) || u.estimated_cost_usd < 0) return null;
    const measured = ((u.input_tokens - u.cached_tokens) * 0.4 + u.cached_tokens * 0.1 + u.output_tokens * 1.6) / 1e6;
    if (Math.abs(measured - u.estimated_cost_usd) > 1e-8) return null;
    total += measured;
  }
  return total;
}

function accounting(events) {
  const requests = new Map();
  for (const e of events) requests.set(e.id, { ...requests.get(e.id), ...e });
  return { spent: [...requests.values()].reduce((s, e) => s + (typeof e.costUsd === 'number' ? e.costUsd : e.reserveUsd), 0),
    requests: [...requests.values()], pending: [...requests.values()].filter(e => e.event === 'reserved') };
}

async function execute({ dir, plan, maxNew = 1, fetcher = fetch, pause = delay }) {
  const base = new URL(plan.baseUrl);
  if (base.protocol !== 'https:' || base.username || base.password || base.pathname !== '/') throw Error('HTTPS origin이 필요합니다.');
  if (!['coupang-option-v27', 'coupang-option-v28', 'coupang-option-v29', 'coupang-option-v30', 'coupang-option-v31'].includes(plan.promptVersion) || plan.reserveUsd !== RESERVE_USD || ![0.95, BUDGET_USD].includes(plan.budgetUsd)) throw Error('검증된 실행 설정과 다릅니다.');
  const ledger = path.join(dir, 'ledger.jsonl'), lock = path.join(dir, 'running.lock');
  const lockFd = fs.openSync(lock, 'wx');
  const append = e => fs.appendFileSync(ledger, JSON.stringify(e) + '\n', { flush: true });
  try {
    let state = accounting(readLines(ledger));
    if (state.pending.length) throw Error('미완료 예약이 있습니다. 기존 요청 결과를 조회해 확인해야 합니다.');
    const done = new Set(state.requests.map(e => e.trialId || e.caseId));
    let n = 0;
    for (const selected of plan.selected) {
      const trialId = selected.trialId || selected.caseId;
      if (done.has(trialId)) continue;
      if (n >= maxNew || state.spent + RESERVE_USD > plan.budgetUsd + 1e-10) break;
      const route = new URL(`/api/v1/evaluation/datasets/${plan.datasetId}/cases/${selected.caseId}`, base);
      const detailResponse = await fetcher(route, { signal: AbortSignal.timeout(30000), redirect: 'error' });
      if (!detailResponse.ok) throw Error(`사례 조회 HTTP ${detailResponse.status}`);
      const detail = await detailResponse.json();
      if (hash(JSON.stringify(detail.testCase.request)) !== selected.requestHash) throw Error('표본 선정 후 입력이 바뀌었습니다.');
      if (detail.testCase.issues.length || Buffer.byteLength(JSON.stringify(detail.testCase.request)) > 8192) throw Error('입력 제한 초과');
      if (selected.request && (selected.request.goodsId !== detail.testCase.request.goodsId
        || Buffer.byteLength(JSON.stringify(selected.request)) > 8192)) throw Error('대조 실험 입력의 상품 또는 크기가 다릅니다.');
      const id = crypto.randomUUID(), startedAt = new Date().toISOString();
      // 요청 전 예약을 디스크에 확정한다. 중단/응답 유실 시에도 예산을 소비한 것으로 남는다.
      append({ id, event: 'reserved', caseId: selected.caseId, trialId, startedAt, reserveUsd: RESERVE_USD });
      let run, status = null, error = null;
      try {
        const response = await fetcher(new URL(route + '/runs'), { method: 'POST', redirect: 'error',
          headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mode: 'AI', allowPaidCall: true,
            ...(selected.request ? { request: selected.request } : {}) }),
          signal: AbortSignal.timeout(90000) });
        status = response.status;
        if (!response.ok) throw Error(`실행 HTTP ${status}`);
        run = await response.json();
        writeJson(path.join(dir, id + '.json'), run);
      } catch (e) { error = e.name === 'TimeoutError' ? '응답 시간 초과: 자동 재호출하지 않음' : e.message; }
      const costUsd = costOf(run);
      append({ id, event: 'completed', caseId: selected.caseId, trialId, finishedAt: new Date().toISOString(),
        status, runId: run?.id || null, costUsd, reserveUsd: RESERVE_USD,
        error: error || run?.error || null, success: run?.response?.success === true });
      state = accounting(readLines(ledger)); n++; done.add(trialId);
      console.log(JSON.stringify({ caseId: selected.caseId, trialId, runId: run?.id, success: run?.response?.success,
        costUsd, budgetAccountedUsd: Number(state.spent.toFixed(8)), calls: run?.response?.aiUsage?.length,
        comparisons: (run?.comparisons || []).reduce((a, c) => { a[c.status] = (a[c.status] || 0) + 1; return a; }, {}),
        error: error || run?.error || null }));
      // 비용/모델/버전이 불명확하거나 통신 실패이면 현재 배치를 중단한다. 예약금은 유지한다.
      if (costUsd === null || error || run?.error || costUsd > RESERVE_USD || run?.response?.promptVersion !== plan.promptVersion) break;
      await pause(2500);
    }
    return state;
  } finally { fs.closeSync(lockFd); fs.unlinkSync(lock); }
}

async function main() {
  const [command, folder, value] = process.argv.slice(2);
  if (!folder || !['plan', 'run', 'status'].includes(command)) throw Error('사용법: node scripts/eval-diverse.cjs plan|run|status logs/<평가폴더> [최대상품수]');
  const dir = path.resolve(folder);
  if (!dir.startsWith(path.join(ROOT, 'logs') + path.sep)) throw Error('산출물 폴더는 이 저장소 logs 아래여야 합니다.');
  if (command === 'plan') {
    const cases = readLines(path.join(dir, 'cases.jsonl')), metadata = readJson(path.join(dir, 'metadata.json'));
    const plan = { baseUrl: 'https://ai-agent-service-production-7624.up.railway.app/', datasetId: metadata.id,
      sourceHash: metadata.sourceHash, promptVersion: 'coupang-option-v27', reserveUsd: RESERVE_USD, budgetUsd: BUDGET_USD,
      createdAt: new Date().toISOString(), ...selectCases(cases, Number(value || 72)) };
    writeJson(path.join(dir, 'plan.json'), plan);
    const { selected, ...summary } = plan;
    console.log(JSON.stringify({ ...summary, selectedCount: selected.length }, null, 2));
  } else if (command === 'run') {
    const maxNew = Number(value || 1);
    if (!Number.isInteger(maxNew) || maxNew < 1 || maxNew > 100) throw Error('실행 수는 1~100 정수여야 합니다.');
    await execute({ dir, plan: readJson(path.join(dir, 'plan.json')), maxNew });
  } else console.log(JSON.stringify(accounting(readLines(path.join(dir, 'ledger.jsonl'))), null, 2));
}

module.exports = { features, selectCases, costOf, accounting, execute };
if (require.main === module) main().catch(e => { console.error(e.message); process.exitCode = 1; });
