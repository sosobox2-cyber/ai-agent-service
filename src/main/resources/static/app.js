const form = document.querySelector('#inference-form');
const rows = document.querySelector('#option-rows');
const rowTemplate = document.querySelector('#option-row-template');
const resultPanel = document.querySelector('#result-panel');
const resultStatus = document.querySelector('#result-status');
const resultSummary = document.querySelector('#result-summary');
const resultDetails = document.querySelector('#result-details');
const submitButton = document.querySelector('#submit-button');
const testModeInput = document.querySelector('#test-mode');
const noticeInput = form.elements.productNoticeText;

const examples = {
  "sample": {
    "goodsId": "68535109",
    "goodsName": "[아이그너]아이그너 레터링 자카드 니트탑",
    "brand": "아이그너",
    "categoryName": "스포츠/레저>스포츠패션/슈즈/아웃도어>스포츠의류(여성)긴팔",
    "coupangCategoryId": "1007572",
    "coupangCategoryName": "스포츠 의류>긴팔>여성 긴팔",
    "allowedPurchaseOptions": [
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
  },
  "quantity": {
    "goodsId": "64629042",
    "goodsName": "[리르]스마일 링클 패치 9박스 (1박스 10피스*5매)+무료체험분 10피스 1매",
    "categoryName": "뷰티>화장품/헤어/바디>스킨케어팩/마스크",
    "coupangCategoryId": "BC73050300",
    "coupangCategoryName": "화장품/향수>마스크/팩>마스크팩",
    "brand": "리르",
    "productNoticeText": "005:제조국:한국,008:품질보증기준:관련 법 및 소비자 분쟁 해결 기준을 따름,041:내용물의 용량 또는 중량:스마일 링클패치 9박스(1박스: 5매, 총 50패치)+ 무료체험분 1매(10패치)\n※1매 (미간 패치 x 6, 팔자 패치 x 4 = 총 10패치)\n모양별 개별 중량 0.2g(최외부 지지체 포함), 1매 PET 필름 포함 약 3.5g,042:제품 주요 사양:모든 피부용,043:사용기한 또는 개봉 후 사용기간:제조일로부터 36개월 ,044:사용방법:본품을 피부에 붙이고 8시간 후 떼어낸다.,045:화장품제조업자, 화장품책임판매업자 및 맞춤형 화장품판매업자:㈜코바스 / (주)아이더블유컴퍼니\n,046:「화장품법」에 따라 기재 표시하여야 하는 모든 성분:아크릴레이트코폴리머,1,2-헥산다이올,소듐아크릴레이트/소듐아크릴로일다이메틸타우레이트코폴리머,폴리아이소부텐,글루코노락톤,피브이피,부틸렌글라이콜,정제수,카프릴릴/카프릴글루코사이드,솔비탄올리에이트,아데노신,글리세린,육두구추출물,수크로오스,소듐하이알루로네이트,포타슘하이알루로네이트,베타-글루칸,하이드롤라이즈드옥수수전분,병풀추출물,하이드롤라이즈드소듐하이알루로,047:「화장품법」에 따른 기능성 화장품(미백, 주름개선, 자외선 차단제품 등)의 경우 “화장품법에 따른 기능성 화장품 심사(또는 보고)를 필함”의 문구:피부의 주름개선에 도움을 준다.,048:사용할 때의 주의사항:1) 화장품 사용 시 또는 사용 후 직사광선에 의하여 사용부위가 붉은 반점, 부어오름 또는 가려움증 등의 이상 증상이나 부작용이 있는 경우에는 전문의 등과 상담할 것\n2) 상처가 있는 부위 등에는 사용을 자제할 것\n3) 보관 및 취급시의 주의사항\n 가. 어린이의 손이 닿지 않는 곳에 보관할 것\n 나. 직사광선을 피해서 보관할 것\n4) 눈 주위를 피,049:소비자상담 관련 전화번호:070-4062-3616",
    "allowedPurchaseOptions": [
      "개당 수량",
      "수량"
    ],
    "options": [
      {
        "optionId": "1",
        "optionName1": "단일상품"
      }
    ]
  },
  "capacity": {
    "goodsId": "65578661",
    "goodsName": "[김소형헤밀레](미리주문)(최다구성패키지)김소형 본초순백 비단쌀 안색 선크림 50ml 7개",
    "categoryName": "뷰티>화장품/헤어/바디>썬케어선블록",
    "coupangCategoryId": "48050018",
    "coupangCategoryName": "뷰티>스킨케어>선케어>선크림",
    "brand": "김소형헤밀레",
    "productNoticeText": "005:제조국:한국,008:품질보증기준:관련 법 및 소비자 분쟁 해결 기준을 따름,041:내용물의 용량 또는 중량:김소형 본초순백 비단쌀 안색 선크림 50ml,042:제품 주요 사양:모든 피부 타입,043:사용기한 또는 개봉 후 사용기간:제조일로부터 36개월까지 (26년 2월 이후 제조)\n\n수시 생산 제품으로 각 상품마다 제조년월일/소비기한(유통기한)/품질유지기한이 상이합니다. 발송될 상품의 정확한 정보를 확인하시려면 판매자에게 문의 바랍니다. (정보고시상 판매자 연락처 참조),044:사용방법:본 품 적당량을 취하여 피부에 고르게 펴 바른다.,045:화장품제조업자, 화장품책임판매업자 및 맞춤형 화장품판매업자:화장품제조업자:유씨엘(주) / 화장품책임판매업자:본초테라피김소형헤밀레(주),046:「화장품법」에 따라 기재 표시하여야 하는 모든 성분:정제수, 쌀수(200,000ppm), 호모살레이트, 부틸렌글라이콜, 비스-에칠헥실옥시페놀메톡시페닐트리아진, 옥토크릴렌, 다이부틸아디페이트, 티타늄디옥사이드, 1,2-헥산다이올, C12-15알킬벤조에이트, 나이아신아마이드, 아이소데케인, 트라이메틸실록시실리케이트, 실리카, 베헤닐알코올, 카프릴릴메티콘, 비닐다이메티콘, 글리세린, 암모늄아크릴로일다이메,047:「화장품법」에 따른 기능성 화장품(미백, 주름개선, 자외선 차단제품 등)의 경우 “화장품법에 따른 기능성 화장품 심사(또는 보고)를 필함”의 문구:자외선 차단(SPF50+ PA++++)+미백+주름개선 3중 기능성 화장품,048:사용할 때의 주의사항:1. 화장품 사용 시 또는 사용 후 직사광선에 의하여 사용부위가 붉은 반점, 부어오름 또는 가려움증 등의 이상 증상이나 부작용이 있는 경우 전문의 등과 상담할 것\n\n2. 상처가 있는 부위 등에는 사용을 자제할 것\n\n3. 보관 및 취급시의 주의사항\n\n가. 어린이의 손이 닿지 않는 곳에 보관할 것\n\n나. 직사광선을 피해서 보관할 것,049:소비자상담 관련 전화번호:고객센터 1566-3393",
    "allowedPurchaseOptions": [
      "개당 용량",
      "수량"
    ],
    "options": [
      {
        "optionId": "1",
        "optionName1": "단일상품"
      }
    ]
  },
  "weight": {
    "goodsId": "65579064",
    "goodsName": "[종가][종가] 전라도 포기김치 4.2kg + 파김치 1kg",
    "categoryName": "식품>가공식품>김치포기김치",
    "coupangCategoryId": "BC15010600",
    "coupangCategoryName": "김치/반찬>김치>포기김치>",
    "brand": "종가",
    "productNoticeText": "049:소비자상담 관련 전화번호:080-080-8866,059:제조연월일, 소비기한 또는 품질유지기한:냉장보관 60일,066:식품의 유형:김치,067:생산자 및 소재지, 수입품의 경우 수입자를 함께 표기:대상(주)횡성공장,068:원재료명 (「농수산물의 원산지 표시 등에 관한 법률」에 따른 원산지 표시 포함) 및 함량 (원재료 함량 표시대상 식품에 한함):기타 수산물가공품, 양파, 액젓, 고춧가루, 마늘, 기타가공품, 무, 절임배추, 소스류, 소스류, 당류가공품, 기타가공품, 조미액젓, 갓, 곡류가공품, 액젓, 대파,069:영양성분(영양성분 표시대상 식품에 한함):전라도 포기김치 : 총내용량 4,200g, 100당 45kcal, 나트륨 580mg(29%), 탄수화물 6g(2%), 당류 3g(3%), 지방 1.2g(2%), 트랜스지방 0g, 포화지방 0g(0%), 콜레스테롤 0mg(0%), 단백질 2g(4%),070:유전자변형식품에 해당하는 경우의 표시:해당없음,071:영유아식 또는 체중조절식품 등에 해당하는 경우 표시광고 사전심의필:해당없음,072:포장단위별 내용량의 용량(중량), 수량:전라도 포기김치 4.2kg + 파1kg,157:소비자 안전을 위한 주의사항:- 대두, 밀, 새우, 잣 성분 혼입 가능\n\n- 계절에 따라 쪽파가 실파로 변경 될 수 있습니다. ,158:제품명:종가 칼칼하고 진하게 깊은 맛 전라도 포기김치,179:수입식품의 경우 \"수입식품안전관리 특별법에 따른 수입신고를 필함\" 의 문구:해당없음,185:품목제조보고번호:-",
    "allowedPurchaseOptions": [
      "개당 중량",
      "수량"
    ],
    "options": [
      {
        "optionId": "1",
        "optionName1": "단일상품"
      }
    ]
  },
  "set": {
    "goodsId": "65592697",
    "goodsName": "[해즈픽]시그니처 케어 칫솔 10개",
    "categoryName": "생활용품>욕실용품>구강용품/타월/목욕칫솔/치실",
    "coupangCategoryId": "49090020",
    "coupangCategoryName": "생활·주방>헤어·바디·구강·면도>구강케어>치실·치간칫솔",
    "brand": "해즈픽",
    "productNoticeText": "004:제조자,수입품의 경우 수입자를 함께 표기:(주)케이앤케이 ,019:품명 및 모델명:[해즈픽] 시그니처 케어 칫솔 10개 세트 \n*컬러: 민트, 핑크, 오렌지, 그레이, 블루 2개씩,049:소비자상담 관련 전화번호:070-4027-4667,126:법에 의한 인증·허가 등을 받았음을 확인할 수 있는 경우 그에 대한 사항:위생용품의 유형 : 일반용 칫솔,127:제조국 또는 원산지:한국",
    "allowedPurchaseOptions": [
      "개당 수량",
      "수량"
    ],
    "options": [
      {
        "optionId": "1",
        "optionName1": "단일상품"
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

function resizeNoticeInput() {
  noticeInput.style.height = '0px';
  const borders = noticeInput.offsetHeight - noticeInput.clientHeight;
  noticeInput.style.height = `${noticeInput.scrollHeight + borders}px`;
}

function loadExample(example) {
  for (const field of ['goodsId', 'goodsName', 'brand', 'categoryName', 'coupangCategoryId', 'coupangCategoryName', 'productNoticeText']) {
    form.elements[field].value = example[field] ?? '';
  }
  form.elements.allowedPurchaseOptions.value = example.allowedPurchaseOptions.join(', ');
  rows.replaceChildren();
  example.options.forEach(addRow);
  resultPanel.hidden = true;
  resizeNoticeInput();
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
  if (request.allowedPurchaseOptions.length < 1) return '허용 옵션명을 한 개 이상 입력하세요.';
  if (request.allowedPurchaseOptions.length > 20) return '허용 옵션명은 최대 20개입니다.';
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

function calculationText(calculation) {
  if (!calculation) return '원문 추출';
  const names = { DIRECT: '직접 추출', CONVERT: '단위 환산', SUM: '합산', PACK_COUNT: '판매 묶음 수', PACK_CONTENT: '묶음 내 수량' };
  const operands = (calculation.operands || []).map(operand => operand ? `${operand.amount}${operand.unit}` : '누락').join(' + ');
  const context = calculation.context;
  const source = { goodsName: '상품명', productNoticeText: '정보고시', optionName1: '원본 옵션명' }[context?.source] || context?.source;
  return `${names[calculation.operation] || calculation.operation} · ${operands || '구성 1묶음'} → ${calculation.outputUnit}`
    + (context?.text ? ` · ${source}: ${context.text}` : '');
}

function showResult(request, response, httpStatus) {
  resultPanel.hidden = false;
  resultDetails.replaceChildren();
  const success = response?.success === true;
  const simulated = response?.inferenceSource === 'TEST' && response?.errorCode === 'TEST_MODE';
  resultStatus.className = `status ${simulated ? 'review' : success ? 'success' : httpStatus === 0 || httpStatus >= 400 ? 'error' : 'review'}`;
  resultStatus.textContent = simulated ? '테스트 결과 · 모의' : success ? '매핑 성공' : httpStatus === 0 ? '연결 오류' : httpStatus >= 400 ? `요청 오류 · HTTP ${httpStatus}` : '검토 필요';
  resultSummary.textContent = response?.serverAssessment?.reason || response?.reason || (success ? '구매옵션 매핑이 완료되었습니다.' : '결과를 확인하세요.');
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
  if (response?.serverAssessment) {
    const server = response.serverAssessment;
    const section = element('section', '', 'assessment');
    section.append(element('h3', '서버 판정'), element('p', server.reason));
    const threshold = typeof server.confidenceThreshold === 'number' ? server.confidenceThreshold.toFixed(2) : '—';
    section.append(element('small', `판정 코드: ${server.decisionCode} · 신뢰도 기준: ${threshold}`));
    resultDetails.append(section);
  }
  const ai = response?.aiAssessment;
  if (ai) {
    const section = element('section', '', 'assessment');
    section.append(element('h3', 'AI 판단 · 서버 승인 전 제안'));
    const certainty = ai.certain === true ? '확실함' : ai.certain === false ? '불확실함' : '판정 없음';
    const confidence = typeof ai.confidence === 'number' ? ai.confidence.toFixed(2) : '—';
    section.append(element('small', `AI 확실 판정: ${certainty} · AI 전체 신뢰도: ${confidence}`));
    section.append(element('p', ai.reason || 'AI 판단 사유가 없습니다.'));
    if (!success) section.append(element('p', '아래 제안은 서버가 승인하지 않은 값입니다. 검토용으로 표시합니다.'));
    if (ai.mappings?.length) {
      section.append(table(['단품 ID', '구매옵션명', 'AI 제안 값', '개별 신뢰도', 'AI가 제출한 근거', '판단 구조'], ai.mappings.map(mapping => {
        if (!mapping) return ['—', '—', '누락된 제안', '—', '—', '—'];
        const source = { goodsName: '상품명', productNoticeText: '정보고시' }[mapping.evidenceSource] || mapping.evidenceSource || '원본 옵션명';
        return [mapping.optionId, mapping.targetPurchaseOptionName, mapping.value,
          typeof mapping.confidence === 'number' ? mapping.confidence.toFixed(2) : '—',
          mapping.evidenceText ? `${source}: ${mapping.evidenceText}` : source, calculationText(mapping.calculation)];
      })));
    } else section.append(element('p', 'AI가 제안한 매핑이 없습니다.'));
    resultDetails.append(section);
  } else if (response?.serverAssessment) {
    resultDetails.append(element('p', simulated ? '테스트 모드이므로 실제 AI 판단 데이터는 없습니다.' : '확인할 수 있는 AI 판단 데이터가 없습니다.', 'result-summary'));
  }
  if (response?.validationErrors?.length) {
    resultDetails.append(element('h3', '확인할 항목'));
    const list = element('ul', '', 'validation-errors');
    response.validationErrors.forEach(error => list.append(element('li', error)));
    resultDetails.append(list);
  }
  if (response?.optionMappings?.length) {
    resultDetails.append(element('h3', simulated ? '모의 옵션명 매핑' : '서버 승인 옵션명 매핑'));
    const hasEvidence = response.optionMappings.some(mapping => mapping.evidenceText);
    const hasCalculation = response.optionMappings.some(mapping => mapping.calculation);
    const headers = ['단품 ID', '원본 옵션명', '쿠팡 구매옵션명', '추출 값', '신뢰도'];
    if (hasEvidence) headers.push('판단 근거');
    if (hasCalculation) headers.push('서버가 검증한 구조');
    resultDetails.append(table(headers, response.optionMappings.map(mapping => [
      mapping.optionId, mapping.sourceOptionName || '(이름 없음)', mapping.targetPurchaseOptionName, mapping.value,
      simulated ? '—' : `${(mapping.confidence * 100).toFixed(1)}%`,
      ...(hasEvidence ? [mapping.evidenceText ? `${{ goodsName: '상품명', productNoticeText: '정보고시', optionName1: '원본 옵션명' }[mapping.evidenceSource] || mapping.evidenceSource}: ${mapping.evidenceText}` : '원본 옵션명'] : []),
      ...(hasCalculation ? [calculationText(mapping.calculation)] : [])
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

noticeInput.addEventListener('input', resizeNoticeInput);
window.addEventListener('resize', resizeNoticeInput);
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
