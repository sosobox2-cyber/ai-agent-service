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
    "goodsId": "68535109",
    "goodsName": "[아이그너](방송에서만 이가격) 아이그너 레터링 자카드 니트탑",
    "brand": "아이그너",
    "categoryName": "스포츠/레저>스포츠패션/슈즈/아웃도어>스포츠의류(여성)긴팔",
    "coupangCategoryId": "1007572",
    "coupangCategoryName": "스포츠 의류>긴팔>여성 긴팔>",
    "allowedPurchaseOptions": [
      "패션의류/잡화 사이즈",
      "색상"
    ],
    "requiredPurchaseOptions": [
      "패션의류/잡화 사이즈",
      "색상"
    ],
    "productNoticeText": "1. 제품 소재 : 소재 : 비스코스 레이온 47% + 폴리에스터 27% + 나일론 22% + 폴리우레탄 4%\n2. 색상 : 블랙\n3. 치수 : S(90) / M(95) / L(100) /XL(105)\n4. 제조자,수입품의 경우 수입자를 함께 표기 : ㈜엘케이플래닝 OEM\n5. 제조국: 베트남\n6. 세탁방법 및 취급시 주의사항: 단독손세탁\n7. 제조연월: 2025년 07월\n8. 품질보증기준: 관련 법 및 소비자 분쟁 해결 기준을 따름\n9. A/S 책임자와 전화번호: 유닉유니온/ 070-8656-3977",
    "options": [
      {
        "optionId": "1",
        "optionName1": "블랙/90"
      },
      {
        "optionId": "2",
        "optionName1": "블랙/95"
      },
      {
        "optionId": "3",
        "optionName1": "블랙/100"
      },
      {
        "optionId": "4",
        "optionName1": "블랙/105"
      }
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
  for (const field of ['goodsId', 'goodsName', 'brand', 'categoryName', 'coupangCategoryId', 'coupangCategoryName', 'productNoticeText']) {
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
  const productNoticeText = form.elements.productNoticeText.value;
  if (productNoticeText.trim()) request.productNoticeText = productNoticeText;
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
