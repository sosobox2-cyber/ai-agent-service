const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('src/main/resources/static/app.js', 'utf8');
const context = vm.createContext({
  element(tag, text, className) {
    return { tag, text, className, children: [], append(...nodes) { this.children.push(...nodes); } };
  },
  table(headers, rows) { return { headers, rows }; }
});
vm.runInContext(source.slice(source.indexOf('function summarizeAiUsage('), source.indexOf('function showResult(')), context);
const call = { attempt: 1, mode: 'LIGHT', model: 'gpt-4.1-mini', input_tokens: 1000,
  cached_tokens: 800, output_tokens: 200, total_tokens: 1200, elapsedMs: 3733, estimated_cost_usd: 0.00048 };
context.calls = [call, { ...call, attempt: 2, retryCount: 1, mode: 'FULL', retryReasons: ['CERTAIN_FALSE'] }];
const sum = vm.runInContext('summarizeAiUsage(calls)', context);
assert.equal(sum.input_tokens, 2000);
assert.equal(sum.cached_tokens, 1600);
assert.equal(sum.total_tokens, 2400);
assert.equal(sum.estimated_cost_usd, 0.00096);
assert.equal(sum.elapsedMs, 7466);
const panel = vm.runInContext('aiUsagePanel({ aiUsage: calls })', context);
assert.match(panel.children[0].text, /LIGHT → FULL · 2회/);
assert.match(panel.children[1].rows[1][0], /재추론/);
assert.equal(panel.children[1].rows[2][0], '합계');
assert.equal(panel.children[1].rows[2][8], '$0.00096000');
assert.equal(panel.children[1].rows[0][7], '3,733 ms');
assert.equal(panel.children[1].rows[2][7], '7,466 ms');
assert.match(panel.children[3].text, /CERTAIN_FALSE/);
context.calls = [{ ...call, cached_tokens: null, elapsedMs: null, estimated_cost_usd: null }];
const unknown = vm.runInContext('aiUsagePanel({ aiUsage: calls })', context);
assert.equal(unknown.children[1].rows.length, 1);
assert.equal(unknown.children[1].rows[0][4], '확인 불가');
assert.equal(unknown.children[1].rows[0][7], '확인 불가');
assert.equal(unknown.children[1].rows[0][8], '확인 불가');
assert.doesNotMatch(unknown.children[2].text, /합계/);
context.calls = [{ ...call, mode: 'FULL' }];
const single = vm.runInContext('aiUsagePanel({ aiUsage: calls })', context);
assert.match(single.children[0].text, /FULL · 1회/);
assert.equal(single.children[1].rows.length, 1);
assert.equal(single.children[1].rows[0][0], '최초 호출');
assert.doesNotMatch(single.children[2].text, /합계/);
const simulated = vm.runInContext('aiUsagePanel({ inferenceSource: "TEST", aiUsage: [] })', context);
assert.match(simulated.children[1].text, /실제 AI를 호출하지/);
assert.equal(simulated.children.length, 2);
context.calls = [{ ...call, input_tokens: 0, cached_tokens: 0, output_tokens: 0,
  total_tokens: 0, estimated_cost_usd: 0 }];
const zero = vm.runInContext('summarizeAiUsage(calls)', context);
assert.equal(zero.cached_tokens, 0);
assert.equal(zero.estimated_cost_usd, 0);
context.calls = [call, { ...call, attempt: 2, elapsedMs: undefined, estimated_cost_usd: null, cached_tokens: null }];
const partial = vm.runInContext('aiUsagePanel({ aiUsage: calls })', context);
assert.match(partial.children[0].text, /LIGHT → LIGHT · 2회/);
assert.equal(partial.children[1].rows[1][0], '2차 호출');
assert.equal(partial.children[1].rows[2][4], '확인 불가');
assert.equal(partial.children[1].rows[2][7], '확인 불가');
assert.equal(partial.children[1].rows[2][8], '확인 불가');
assert.equal(partial.children.length, 3); // No retry label/reason inferred from order alone.
context.calls = [{ ...call, elapsedMs: 0 }];
assert.equal(vm.runInContext('aiUsagePanel({ aiUsage: calls })', context).children[1].rows[0][7], '0 ms');
context.calls = [call];
assert.match(vm.runInContext('aiUsagePanel({ aiUsage: calls })', context).children[0].text, /LIGHT · 1회/);
for (const [response, expected] of [
  [{ success: true, aiAssessment: { certain: true, confidence: 0.9 } }, 'AI 판단: 확정 · confidence 0.90'],
  [{ success: false, aiAssessment: { certain: false, confidence: 0.61 } }, 'AI 판단: 검토 필요 · confidence 0.61'],
  [{ success: false, errorCode: 'REVIEW_REQUIRED', validationErrors: ['DUPLICATE_MAPPING'], aiAssessment: { certain: true, confidence: 0.95 } }, 'AI 판단: 확정 · confidence 0.95'],
  [{ aiAssessment: { certain: null, confidence: null } }, 'AI 판단: 판정 없음 · confidence —'],
  [{ confidence: 0.9 }, 'AI 판단: 확인 불가'],
  [{ aiAssessment: { certain: true, confidence: 0 } }, 'AI 판단: 확정 · confidence 0.00']
]) {
  context.response = response;
  assert.equal(vm.runInContext('aiJudgmentText(response)', context), expected);
}
console.log('AI usage UI: totals, retry, unknown values and test mode verified');
