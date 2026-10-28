'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { selectCases, costOf, accounting, execute } = require('../../../scripts/eval-diverse.cjs');

function fixture(id, targets, name = id) {
  return { id, issues: [], references: targets.map(target => ({ target })), request: { goodsName: name,
    allowedPurchaseOptions: targets, options: [{ optionId: '1', optionName1: '단일상품' }],
    categoryName: '분류', purchaseOptionUnits: [], productNoticeText: '', productCompositionText: '' } };
}
function runResult() {
  return { id: 'run-1', response: { promptVersion: 'coupang-option-v27', success: true, aiUsage: [{
    model: 'gpt-4.1-mini', input_tokens: 1000, cached_tokens: 500, output_tokens: 100, estimated_cost_usd: 0.00041
  }] }, comparisons: [] };
}
function setup(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'eval-budget-'));
  t.after(() => fs.rmSync(dir, { recursive: true }));
  const c = fixture('c00001', ['수량']);
  const plan = { baseUrl: 'https://example.invalid/', datasetId: 'dataset', promptVersion: 'coupang-option-v27',
    reserveUsd: 0.08, budgetUsd: 0.95, selected: [{ caseId: c.id, requestHash: crypto.createHash('sha256').update(JSON.stringify(c.request)).digest('hex') }] };
  return { dir, c, plan };
}
test('희소 구매옵션을 빠뜨리지 않고 숫자만 다른 유사 상품은 한 번 선택한다', () => {
  const cases = [fixture('c1', ['색상'], '의류 1'), fixture('c2', ['색상'], '의류 2'), fixture('c3', ['ISBN'], '도서')];
  const result = selectCases(cases, 3);
  assert.equal(result.selected.length, 2);
  assert.deepEqual(result.uncovered, []);
});
test('누락·알 수 없는 모델·잘못된 단가를 0달러로 처리하지 않는다', () => {
  assert.equal(costOf(runResult()), 0.00041);
  for (const change of [{ cached_tokens: null }, { estimated_cost_usd: null }, { model: 'other' },
    { estimated_cost_usd: 0 }, { input_tokens: -1 }, { output_tokens: 4097 }]) {
    const r = runResult(); Object.assign(r.response.aiUsage[0], change); assert.equal(costOf(r), null);
  }
  assert.equal(costOf({ error: 'AI_TIMEOUT' }), null);
  const r = runResult(); r.response.aiUsage.push({ ...r.response.aiUsage[0] });
  assert.equal(costOf(r), 0.00082);
});
test('미완료 요청과 비용 미상 요청은 각각 예약금으로 남긴다', () => {
  const a = accounting([{ id: 'a', event: 'reserved', reserveUsd: 0.08 },
    { id: 'b', event: 'reserved', reserveUsd: 0.08 }, { id: 'b', event: 'completed', costUsd: null }]);
  assert.equal(a.spent, 0.16); assert.equal(a.pending.length, 1);
});

test('v28도 이전 비용 원장을 이어 쓰며 미검증 버전은 호출하지 않는다', async t => {
  const x = setup(t); x.plan.promptVersion = 'coupang-option-v28';
  fs.writeFileSync(path.join(x.dir, 'ledger.jsonl'), JSON.stringify({ id: 'prior', event: 'completed', costUsd: 0.3305084 }) + '\n');
  const state = await execute({ ...x, pause: async () => {}, fetcher: async (url, init) => {
    if (init.method !== 'POST') return { ok: true, json: async () => ({ testCase: x.c }) };
    const run = runResult(); run.response.promptVersion = 'coupang-option-v28';
    return { ok: true, status: 200, json: async () => run };
  } });
  assert.ok(Math.abs(state.spent - 0.3309184) < 1e-10);
  x.plan.promptVersion = 'coupang-option-v38';
  await assert.rejects(execute({ ...x, fetcher: async () => assert.fail('must not call') }), /검증된 실행 설정/);
});
test('v29의 늘어난 예산도 기존 비용을 포함하며 1.45달러에서 중단한다', async t => {
  const x = setup(t); x.plan.promptVersion = 'coupang-option-v29'; x.plan.budgetUsd = 1.45;
  fs.writeFileSync(path.join(x.dir, 'ledger.jsonl'), JSON.stringify({ id: 'prior', event: 'completed', costUsd: 1.36 }) + '\n');
  const state = await execute({ ...x, pause: async () => {}, fetcher: async (url, init) => {
    if (init.method !== 'POST') return { ok: true, json: async () => ({ testCase: x.c }) };
    const run = runResult(); run.response.promptVersion = 'coupang-option-v29';
    return { ok: true, status: 200, json: async () => run };
  } });
  assert.ok(Math.abs(state.spent - 1.36041) < 1e-10);
  fs.appendFileSync(path.join(x.dir, 'ledger.jsonl'), JSON.stringify({ id: 'unknown', event: 'completed', costUsd: null, reserveUsd: 0.08 }) + '\n');
  x.plan.selected[0].trialId = 'new-trial';
  await execute({ ...x, fetcher: async () => assert.fail('must not call') });
});
test('남은 예산으로 다음 요청의 최악 비용을 감당할 수 없으면 전송하지 않는다', async t => {
  const x = setup(t); fs.writeFileSync(path.join(x.dir, 'ledger.jsonl'), JSON.stringify({ id: 'old', caseId: 'old', event: 'completed', costUsd: 0.88 }) + '\n');
  let calls = 0;
  await execute({ ...x, fetcher: async () => { calls++; }, pause: async () => {} });
  assert.equal(calls, 0);
});
test('예약 저장 후에만 POST하고 같은 사례는 재실행하지 않는다', async t => {
  const x = setup(t); let posts = 0;
  const fetcher = async (url, init) => {
    if (init.method === 'POST') {
      posts++;
      const lines = fs.readFileSync(path.join(x.dir, 'ledger.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
      assert.equal(accounting(lines).pending.length, 1);
      return { ok: true, status: 200, json: async () => runResult() };
    }
    return { ok: true, json: async () => ({ testCase: x.c }) };
  };
  await execute({ ...x, fetcher, pause: async () => {} });
  await execute({ ...x, fetcher, pause: async () => {} });
  assert.equal(posts, 1);
});
test('응답 유실 시 자동 재호출 없이 예약금을 유지한다', async t => {
  const x = setup(t); let posts = 0;
  const state = await execute({ ...x, fetcher: async (url, init) => {
    if (init.method === 'POST') { posts++; throw new Error('connection lost'); }
    return { ok: true, json: async () => ({ testCase: x.c }) };
  }, pause: async () => {} });
  assert.equal(posts, 1); assert.equal(state.spent, 0.08);
});
test('이미 전송 중인 요청이 남아 있으면 재개하지 않는다', async t => {
  const x = setup(t);
  fs.writeFileSync(path.join(x.dir, 'ledger.jsonl'), JSON.stringify({ id: 'old', event: 'reserved', reserveUsd: 0.08 }) + '\n');
  await assert.rejects(execute({ ...x, fetcher: async () => assert.fail('must not call') }), /미완료 예약/);
});
test('대조 실험도 같은 예산 원장을 사용하고 다른 상품의 입력은 보내지 않는다', async t => {
  const x = setup(t); x.c.request.goodsId = 'goods-1';
  x.plan.selected[0].requestHash = crypto.createHash('sha256').update(JSON.stringify(x.c.request)).digest('hex');
  x.plan.selected[0].trialId = 'normalization-test';
  x.plan.selected[0].request = { ...x.c.request, goodsId: 'other' };
  await assert.rejects(execute({ ...x, fetcher: async (url, init) => {
    assert.notEqual(init.method, 'POST');
    return { ok: true, json: async () => ({ testCase: x.c }) };
  } }), /상품 또는 크기/);
});
