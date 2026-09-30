const form = document.querySelector('#inference-form');
const rows = document.querySelector('#option-rows');
const rowTemplate = document.querySelector('#option-row-template');
const resultPanel = document.querySelector('#result-panel');
const resultStatus = document.querySelector('#result-status');
const resultSummary = document.querySelector('#result-summary');
const resultDetails = document.querySelector('#result-details');
const submitButton = document.querySelector('#submit-button');
const testModeInput = document.querySelector('#test-mode');
const requiredInput = form.elements.requiredPurchaseOptions;

const examples = {
  sample: {
    goodsId: '100000123', goodsName: '남성 배기핏 바지', brand: 'ABC',
    categoryName: '패션 > 남성의류 > 바지', coupangCategoryId: '123456',
    coupangCategoryName: '남성 바지', allowedPurchaseOptions: ['핏', '색상', '사이즈'],
    requiredPurchaseOptions: ['핏', '색상', '사이즈'],
    options: [
      { optionId: '1', optionName1: '배기핏 남색 100 ' },
      { optionId: '2', optionName1: '배기핏 남색 150' },
      { optionId: '3', optionName1: '배기핏 남색 200' }
    ]
  }
};

function addRow(option = {}) {
  if (rows.children.length >= 200) {
    showMessage('단품은 최대 200개까지 입력할 수 있습니다.');
    return;
  }
  const row = rowTemplate.content.firstElementChild.cloneNode(true);
  row.querySelectorAll('[data-field]').forEach(input => {
    input.value = option[input.dataset.field] ?? '';
  });
  row.querySelector('.remove-row').addEventListener('click', () => {
    if (rows.children.length > 1) row.remove();
    else row.querySelectorAll('input').forEach(input => { input.value = ''; });
  });
  rows.append(row);
}

function loadExample(example) {
  for (const field of ['goodsId', 'goodsName', 'brand', 'categoryName', 'coupangCategoryId', 'coupangCategoryName']) {
    form.elements[field].value = example[field] ?? '';
  }
  form.elements.allowedPurchaseOptions.value = example.allowedPurchaseOptions.join(', ');
  requiredInput.value = example.requiredPurchaseOptions?.join(', ') ?? '';
  rows.replaceChildren();
  example.options.forEach(addRow);
  resultPanel.hidden = true;
}

function splitNames(text) {
  return text.split(/[,\r\n]+/).map(name => name.trim()).filter(Boolean);
}

function makeRequest() {
  const request = {};
  for (const field of ['goodsId', 'goodsName', 'categoryName', 'coupangCategoryId', 'coupangCategoryName']) {
    request[field] = form.elements[field].value.trim();
  }
  const brand = form.elements.brand.value.trim();
  if (brand) request.brand = brand;
  request.allowedPurchaseOptions = splitNames(form.elements.allowedPurchaseOptions.value);
  if (requiredInput.value.trim()) request.requiredPurchaseOptions = splitNames(requiredInput.value);
  request.options = [...rows.querySelectorAll('tr')].map(row => {
    const option = {};
    row.querySelectorAll('[data-field]').forEach(input => {
      const value = input.value;
      if (value.trim()) option[input.dataset.field] = input.dataset.field === 'optionId' ? value.trim() : value;
    });
    return option;
  });
  return request;
}

function validateRequest(request) {
  if (request.allowedPurchaseOptions.length < 2) return '허용 옵션명을 두 개 이상 입력하세요.';
  if (request.allowedPurchaseOptions.length > 20) return '허용 옵션명은 최대 20개입니다.';
  if (request.requiredPurchaseOptions?.some(name => !request.allowedPurchaseOptions.includes(name))) {
    return '필수 옵션명은 허용 옵션명 목록에 있어야 합니다.';
  }
  if (request.options.some(option => !option.optionName1?.trim())) {
    return '각 단품의 원본 옵션명을 입력하세요.';
  }
  return null;
}

function element(tag, value, className) {
  const node = document.createElement(tag);
  node.textContent = value ?? '';
  if (className) node.className = className;
  return node;
}

function table(headers, rowsData) {
  const wrapper = element('div', '', 'table-scroll');
  const tableNode = element('table', '', 'result-table');
  const head = document.createElement('thead');
  const headRow = document.createElement('tr');
  headers.forEach(header => headRow.append(element('th', header)));
  head.append(headRow);
  const body = document.createElement('tbody');
  rowsData.forEach(cells => {
    const row = document.createElement('tr');
    cells.forEach(cell => row.append(element('td', cell)));
    body.append(row);
  });
  tableNode.append(head, body);
  wrapper.append(tableNode);
  return wrapper;
}

function showResult(request, response, httpStatus) {
  resultPanel.hidden = false;
  resultDetails.replaceChildren();
  const success = response?.success === true;
  const simulated = response?.inferenceSource === 'TEST' && response?.errorCode === 'TEST_MODE';
  resultStatus.className = `status ${simulated ? 'review' : success ? 'success' : httpStatus === 0 || httpStatus >= 400 ? 'error' : 'review'}`;
  resultStatus.textContent = simulated ? '테스트 결과 · 모의' : success ? '매핑 성공' : httpStatus === 0 ? '연결 오류' : httpStatus >= 400 ? `요청 오류 · HTTP ${httpStatus}` : '검토 필요';
  resultSummary.textContent = response?.reason || (success ? '구매옵션 매핑이 완료되었습니다.' : '결과를 확인하세요.');
  const metrics = element('div', '', 'metrics');
  for (const [label, value] of [
    ['추론 방식', response?.inferenceSource ?? '—'],
    ['신뢰도', simulated ? '측정 안 함' : typeof response?.confidence === 'number' ? `${(response.confidence * 100).toFixed(1)}%` : '—'],
    ['자동 적용 후보', response?.autoApplyCandidate ? '예' : '아니요'],
    ['오류 코드', response?.errorCode ?? '없음']
  ]) {
    const metric = element('div', '', 'metric');
    metric.append(element('span', label), element('strong', value));
    metrics.append(metric);
  }
  resultDetails.append(metrics);
  if (response?.validationErrors?.length) {
    resultDetails.append(element('h3', '확인할 항목'));
    const list = element('ul', '', 'validation-errors');
    response.validationErrors.forEach(error => list.append(element('li', error)));
    resultDetails.append(list);
  }
  if (response?.optionMappings?.length) {
    resultDetails.append(element('h3', '옵션명 매핑'));
    resultDetails.append(table(['단품 ID', '원본 옵션명', '쿠팡 구매옵션명', '추출 값', '신뢰도'], response.optionMappings.map(mapping => [
      mapping.optionId, mapping.sourceOptionName || '(이름 없음)', mapping.targetPurchaseOptionName, mapping.value,
      simulated ? '—' : `${(mapping.confidence * 100).toFixed(1)}%`
    ])));
  }
  if (response?.items?.length) {
    resultDetails.append(element('h3', `단품 결과 · ${response.items.length}개`));
    resultDetails.append(table(['단품 ID', '구매옵션'], response.items.map(item => [
      item.optionId, Object.entries(item.purchaseOptions || {}).map(([name, value]) => `${name}: ${value}`).join(' · ')
    ])));
  }
  document.querySelector('#request-json').textContent = JSON.stringify(request, null, 2);
  document.querySelector('#response-json').textContent = JSON.stringify(response, null, 2);
  resultPanel.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function showMessage(message) {
  showResult(makeRequest(), { success: false, reason: message, errorCode: 'INPUT_ERROR' }, 400);
}

document.querySelector('#add-row').addEventListener('click', () => addRow());
document.querySelectorAll('[data-example]').forEach(button => {
  button.addEventListener('click', () => loadExample(examples[button.dataset.example]));
});
form.addEventListener('submit', async event => {
  event.preventDefault();
  const request = makeRequest();
  const validationError = validateRequest(request);
  if (validationError) { showMessage(validationError); return; }
  submitButton.disabled = true;
  submitButton.textContent = '처리 중…';
  try {
    const url = `/api/v1/coupang/purchase-options/infer${testModeInput.checked ? '?testMode=true' : ''}`;
    const response = await fetch(url, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request)
    });
    const body = await response.json();
    showResult(request, body, response.status);
  } catch (error) {
    showResult(request, { success: false, reason: '서버에 연결할 수 없거나 응답을 읽지 못했습니다.', errorCode: 'NETWORK_ERROR' }, 0);
  } finally {
    submitButton.disabled = false;
    submitButton.textContent = '매핑 결과 확인 →';
  }
});

loadExample(examples.sample);
