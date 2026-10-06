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
  cached_tokens: 800, output_tokens: 200, total_tokens: 1200, estimated_cost_usd: 0.00048 };
context.calls = [call, { ...call, attempt: 2 }];
const sum = vm.runInContext('summarizeAiUsage(calls)', context);
assert.equal(sum.input_tokens, 2000);
assert.equal(sum.cached_tokens, 1600);
assert.equal(sum.total_tokens, 2400);
assert.equal(sum.estimated_cost_usd, 0.00096);
const panel = vm.runInContext('aiUsagePanel({ aiUsage: calls })', context);
assert.match(panel.children[0].text, /LIGHT · 2회/);
assert.match(panel.children[1].rows[1][0], /보정 재요청/);
assert.equal(panel.children[1].rows[2][0], '요청 전체 합계');
assert.equal(panel.children[1].rows[2][7], '$0.00096000');
context.calls = [{ ...call, cached_tokens: null, estimated_cost_usd: null }];
const unknown = vm.runInContext('aiUsagePanel({ aiUsage: calls })', context);
assert.equal(unknown.children[1].rows[1][4], '확인 불가');
assert.equal(unknown.children[1].rows[1][7], '확인 불가');
const simulated = vm.runInContext('aiUsagePanel({ inferenceSource: "TEST", aiUsage: [] })', context);
assert.match(simulated.children[1].text, /실제 AI를 호출하지/);
assert.equal(simulated.children.length, 2);
context.calls = [{ ...call, input_tokens: 0, cached_tokens: 0, output_tokens: 0,
  total_tokens: 0, estimated_cost_usd: 0 }];
const zero = vm.runInContext('summarizeAiUsage(calls)', context);
assert.equal(zero.cached_tokens, 0);
assert.equal(zero.estimated_cost_usd, 0);
console.log('AI usage UI: totals, retry, unknown values and test mode verified');
