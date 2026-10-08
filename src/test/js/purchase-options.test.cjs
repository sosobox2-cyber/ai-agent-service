const fs = require('node:fs');

function run(source) {
  function check(condition, label) { if (!condition) throw new Error(label); }
  const messages = [];
  const fields = Object.fromEntries(['goodsId','goodsName','categoryName','productNoticeText','productCompositionText'].map(name => [name, {value:'', style:{}, offsetHeight:0, clientHeight:0, scrollHeight:0}]));
  const container = () => ({
    children: [], append(row) { row.parent = this; this.children.push(row); },
    replaceChildren() { this.children = []; }, querySelectorAll() { return this.children; }
  });
  const purchaseRows = container(), sourceRows = container();
  function template(names, dataKey) {
    return {content:{firstElementChild:{cloneNode() {
      const inputs = names.map(name => ({value:'', dataset:{[dataKey]:name}}));
      const button = {addEventListener(event, fn) { this.click = fn; }};
      return {inputs, button, querySelectorAll() { return inputs; },
        querySelector() { return button; },
        remove() { this.parent.children.splice(this.parent.children.indexOf(this), 1); }};
    }}}};
  }
  const elements = {
    '#inference-form':{elements:fields}, '#option-rows':sourceRows,
    '#purchase-option-rows':purchaseRows,
    '#option-row-template':template(['optionId','optionName1'],'field'),
    '#purchase-option-row-template':template(['purchaseOptionName','defaultUnit','unitOptions'],'purchaseField')
  };
  const document = {querySelector(selector) { return elements[selector] || {}; }};
  const prefix = source.slice(0, source.indexOf('function element('));
  const api = new Function('document','showMessage', prefix + '\nreturn {examples, loadExample, makeRequest, validateRequest, addPurchaseOptionRow};')(document, message => messages.push(message));
  let examplesChecked = 0;
  for (const example of Object.values(api.examples)) {
    api.loadExample(example);
    const request = api.makeRequest();
    check(!api.validateRequest(request), 'Example must validate');
    check(JSON.stringify(request.allowedPurchaseOptions) === JSON.stringify(example.allowedPurchaseOptions), 'Allowed names preserved');
    check(JSON.stringify(request.options) === JSON.stringify(example.options), 'Source options preserved');
    check(request.productNoticeText === example.productNoticeText, 'Notice preserved');
    const expected = new Map((example.purchaseOptionUnits || []).map(row => [row.purchaseOptionName,row]));
    check((request.purchaseOptionUnits || []).length === expected.size, 'Unit row count preserved');
    for (const row of request.purchaseOptionUnits || []) check(JSON.stringify(row) === JSON.stringify(expected.get(row.purchaseOptionName)), 'Unit settings preserved');
    examplesChecked++;
  }
  api.loadExample(api.examples.sample);
  check(!('purchaseOptionUnits' in api.makeRequest()), 'Names alone must omit units');
  purchaseRows.children[0].inputs[1].value = '개';
  check(api.validateRequest(api.makeRequest()).includes('선택지'), 'Default alone must fail');
  purchaseRows.children[0].inputs[1].value = '';
  purchaseRows.children[0].inputs[2].value = '개, 박스';
  check(api.validateRequest(api.makeRequest()).includes('기본단위'), 'Choices alone must fail');
  purchaseRows.children[0].inputs[1].value = '개';
  purchaseRows.children[0].inputs[0].value = '수량';
  check(api.makeRequest().purchaseOptionUnits[0].purchaseOptionName === '수량', 'Renamed name and units stay linked');
  check(!api.validateRequest(api.makeRequest()), 'Mixed configured and unconfigured rows must pass');
  purchaseRows.children[1].inputs[0].value = '수량';
  check(api.validateRequest(api.makeRequest()).includes('중복'), 'Duplicate names must fail');
  purchaseRows.children[1].button.click();
  check(api.makeRequest().allowedPurchaseOptions.length === 1, 'Delete removes allowed name');
  purchaseRows.children[0].button.click();
  check(purchaseRows.children.length === 1 && purchaseRows.children[0].inputs.every(input => !input.value), 'Last deletion clears row');
  check(api.validateRequest(api.makeRequest()), 'Empty row must fail');
  api.loadExample(api.examples.sample);
  for (let i=2; i<200; i++) api.addPurchaseOptionRow({purchaseOptionName:'name'+i});
  api.addPurchaseOptionRow();
  check(purchaseRows.children.length === 200 && messages.at(-1).includes('200'), 'Two hundred row limit enforced');
  return {examplesChecked, checks:'names-only, units, incomplete units, duplicates, rename, delete, row limit'};
}

console.log(run(fs.readFileSync('src/main/resources/static/app.js', 'utf8')));
