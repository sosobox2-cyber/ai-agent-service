const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('src/main/resources/static/app.js', 'utf8');
function element(tag, text, className) {
  return { tag, text: text ?? '', className, dataset: {}, children: [],
    append(...nodes) { this.children.push(...nodes); } };
}
const context = vm.createContext({ element });
vm.runInContext(source.slice(source.indexOf('function unitMappingData('), source.indexOf('function showResult(')), context);
const request = JSON.parse(fs.readFileSync('examples/ai-request.json', 'utf8'));
const product = request.product || request;
assert.equal(product.options.length, 4);
const mappings = product.options.flatMap(option => [
  { optionId: option.optionId, targetPurchaseOptionName: '색상', value: '블랙' },
  { optionId: option.optionId, targetPurchaseOptionName: '패션의류/잡화 사이즈', value: option.optionName1.split('/')[1] }
]);
context.request = request;
context.response = { success: true, optionMappings: mappings };
const run = name => vm.runInContext(`${name}(request, response)`, context);
const descendants = node => [node, ...node.children.flatMap(descendants)];
let data = run('unitMappingData');
assert.equal(data.rows.length, 4);
assert.deepEqual(Array.from(data.rows, row => row.mappings.length), [2, 2, 2, 2]);
let nodes = descendants(run('unitResultsPanel'));
assert.equal(nodes.filter(node => node.tag === 'tr').length, 5);
assert.equal(nodes.filter(node => node.className === 'unit-mapping').length, 8);
assert.deepEqual(nodes.filter(node => node.className === 'unit-original').map(node => node.text), product.options.map(option => option.optionName1));
assert.deepEqual(nodes.filter(node => node.className === 'unit-mapping-value').map(node => node.text), ['블랙', '90', '블랙', '95', '블랙', '100', '블랙', '105']);

// Single mapping, missing ID, and unknown ID; duplicates stay visible for review.
context.response = { success: false, errorCode: 'REVIEW_REQUIRED', optionMappings: [],
  aiAssessment: { mappings: [mappings[0], mappings[2], mappings[2], { ...mappings[0], optionId: 'unknown' }] } };
data = run('unitMappingData');
assert.equal(data.rows.length, 4);
assert.deepEqual(Array.from(data.rows, row => row.mappings.length), [1, 2, 0, 0]);
assert.deepEqual(Array.from(data.unknown), ['unknown']);
nodes = descendants(run('unitResultsPanel'));
assert.equal(nodes.filter(node => node.text === '매핑 없음').length, 2);
assert.match(nodes.find(node => node.className === 'unit-result-warning').text, /unknown/);
assert.match(nodes.find(node => node.tag === 'h3').text, /검토용 AI 제안/);

// Thirty long options, three mappings each; no truncation or raw HTML interpolation.
context.request = { product: { options: Array.from({ length: 30 }, (_, i) => ({ optionId: String(i + 1), optionName1: '<img src=x onerror=alert(1)>\n' + '긴 원본 '.repeat(50) })) } };
context.response = { success: true, optionMappings: context.request.product.options.flatMap(option => ['색상', '사이즈', '긴 구매옵션명'.repeat(30)].map(name => ({ optionId: option.optionId, targetPurchaseOptionName: name, value: '긴 값'.repeat(50) }))) };
nodes = descendants(run('unitResultsPanel'));
assert.equal(nodes.filter(node => node.tag === 'tr').length, 31);
assert.equal(nodes.filter(node => node.className === 'unit-mapping').length, 90);
assert.equal(nodes.find(node => node.className === 'unit-original').text, context.request.product.options[0].optionName1);
assert.ok(!nodes.some(node => node.tag === 'img'));

// Mappings must be direct children of their own row's third cell, never siblings of the table.
function assertCellMappings(panel, expected) {
  const grid = panel.children.find(node => node.tag === 'table');
  const rows = grid.children.find(node => node.tag === 'tbody').children;
  assert.equal(rows.length, expected.length);
  rows.forEach((row, index) => {
    assert.equal(row.children.length, 3);
    const cell = row.children[2];
    assert.equal(cell.tag, 'td');
    const mappings = cell.children.filter(node => node.className === 'unit-mapping');
    assert.equal(mappings.length, expected[index].length);
    assert.ok(mappings.every(node => node.tag === 'div'));
    assert.deepEqual(mappings.map(node => node.children[2].text), expected[index]);
    if (!expected[index].length) assert.equal(cell.children[0].text, '매핑 없음');
  });
  assert.equal(descendants(panel).filter(node => node.className === 'unit-mapping').length,
    expected.flat().length);
}
context.request = { options: [{ optionId: '1', optionName1: '단일상품' }] };
context.response = { success: true, optionMappings: [
  { optionId: '1', targetPurchaseOptionName: '화면크기', value: '75인치' },
  { optionId: '1', targetPurchaseOptionName: '모델명/품번', value: '75TR3DQ' },
  { optionId: '1', targetPurchaseOptionName: '스탠드/벽걸이 구분', value: '스탠드' }
] };
assertCellMappings(run('unitResultsPanel'), [['75인치', '75TR3DQ', '스탠드']]);
context.request = { options: ['1', '2', '3'].map(optionId => ({ optionId, optionName1: optionId })) };
context.response.optionMappings = ['1', '2', '3'].map(optionId => ({ optionId, targetPurchaseOptionName: '색상', value: `색상${optionId}` }));
assertCellMappings(run('unitResultsPanel'), [['색상1'], ['색상2'], ['색상3']]);
context.response.optionMappings.splice(1, 1);
assertCellMappings(run('unitResultsPanel'), [['색상1'], [], ['색상3']]);
context.response = { success: false, errorCode: 'REVIEW_REQUIRED', optionMappings: [], aiAssessment: { mappings: context.response.optionMappings } };
assertCellMappings(run('unitResultsPanel'), [['색상1'], [], ['색상3']]);

// String IDs stay exact, including leading zeros; numeric transport IDs compare safely.
context.request = { options: [{ optionId: '01', optionName1: '원문' }, { optionId: '2', optionName1: '원문2' }] };
context.response = { success: true, optionMappings: [{ ...mappings[0], optionId: '1' }, { ...mappings[0], optionId: 2 }] };
data = run('unitMappingData');
assert.equal(data.rows[0].mappings.length, 0);
assert.equal(data.rows[1].mappings.length, 1);
assert.deepEqual(Array.from(data.unknown), ['1']);
assert.match(source, /resultDetails\.append\(unitResultsPanel\(request, response\)\)/);
assert.match(source, /querySelector\('#request-json'\)\.textContent = JSON\.stringify\(request/);
assert.match(source, /querySelector\('#response-json'\)\.textContent = JSON\.stringify\(response/);
assert.match(fs.readFileSync('src/main/resources/static/index.html', 'utf8'), /요청·응답 JSON 보기/);
console.log('Unit results: clothing, single/multiple/missing/unknown mappings, review, 30 long options and JSON preservation verified');
