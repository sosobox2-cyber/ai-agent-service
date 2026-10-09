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
    '#inference-form':{elements:fields, reset() { Object.values(fields).forEach(field => { field.value = ''; }); }}, '#option-rows':sourceRows,
    '#purchase-option-rows':purchaseRows,
    '#test-mode':{checked:true},
    '.composition-details':{open:false}, '#result-panel':{hidden:false},
    '#result-status':{textContent:'old'}, '#result-summary':{textContent:'old'},
    '#result-details':container(), '#request-json':{textContent:'old'},
    '#response-json':{textContent:'old'}, '#copy-json-status':{textContent:'old'},
    '#option-row-template':template(['optionId','optionName1'],'field'),
    '#purchase-option-row-template':template(['purchaseOptionName','defaultUnit','unitOptions'],'purchaseField')
  };
  const document = {querySelector(selector) { return elements[selector] || {}; }};
  const prefix = source.slice(0, source.indexOf('function element('));
  const api = new Function('document','showMessage', prefix + '\nreturn {examples, loadExample, makeRequest, validateRequest, addPurchaseOptionRow, resetInputs};')(document, message => messages.push(message));
  let examplesChecked = 0;
  for (const example of Object.values(api.examples)) {
    elements['#test-mode'].checked = false;
    api.loadExample(example);
    check(elements['#test-mode'].checked, 'Loading an example enables test mode');
    const request = api.makeRequest();
    check(!api.validateRequest(request), 'Example must validate');
    check(JSON.stringify(request.allowedPurchaseOptions) === JSON.stringify(example.allowedPurchaseOptions), 'Allowed names preserved');
    check(JSON.stringify(request.options) === JSON.stringify(example.options), 'Source options preserved');
    check(request.productNoticeText === example.productNoticeText, 'Notice preserved');
    check((request.productCompositionText || '') === (example.productCompositionText || ''), 'Composition preserved');
    const expected = new Map((example.purchaseOptionUnits || []).map(row => [row.purchaseOptionName,row]));
    check((request.purchaseOptionUnits || []).length === expected.size, 'Unit row count preserved');
    for (const row of request.purchaseOptionUnits || []) check(JSON.stringify(row) === JSON.stringify(expected.get(row.purchaseOptionName)), 'Unit settings preserved');
    examplesChecked++;
  }
  api.resetInputs();
  check(JSON.stringify(api.makeRequest().options) === JSON.stringify([{optionId:'1', optionName1:'단일상품'}]), 'Blank options default to a single product');
  check(!elements['#test-mode'].checked, 'Reset unchecks test mode');
  check(Object.values(fields).every(field => !field.value), 'Reset clears product information');
  check(sourceRows.children.length === 1 && sourceRows.children[0].inputs[0].value === '1'
    && !sourceRows.children[0].inputs[1].value, 'Reset starts with ID 1 and blank option name');
  check(purchaseRows.children.length === 1 && purchaseRows.children[0].inputs.every(input => !input.value), 'Reset clears purchase options and units');
  check(elements['.composition-details'].open && elements['#result-panel'].hidden, 'Reset opens composition and hides results');
  for (const selector of ['#result-status', '#result-summary', '#request-json', '#response-json', '#copy-json-status']) {
    check(!elements[selector].textContent, 'Reset clears previous result and JSON');
  }
  api.loadExample(api.examples.sample);
  for (const category of ['', ' \t\r\n ']) {
    fields.categoryName.value = category;
    check(!('categoryName' in api.makeRequest()), 'Blank category must be omitted');
    check(!api.validateRequest(api.makeRequest()), 'Category is optional');
  }
  fields.categoryName.value = ' 의류>상의 ';
  check(api.makeRequest().categoryName === '의류>상의', 'Provided category must be preserved and trimmed');
  check(!('purchaseOptionUnits' in api.makeRequest()), 'Names alone must omit units');
  purchaseRows.children[0].inputs[1].value = ' 없음 ';
  for (const choices of ['', 'mm, cm']) {
    purchaseRows.children[0].inputs[2].value = choices;
    check(!('purchaseOptionUnits' in api.makeRequest()), 'None default means no unit setting');
    check(!api.validateRequest(api.makeRequest()), 'None default does not require unit choices');
  }
  purchaseRows.children[0].inputs[2].value = '';
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
