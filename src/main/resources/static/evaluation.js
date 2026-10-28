'use strict';
const $ = id => document.getElementById(id);
// 별도 페이지로 열리므로 iframe 여부는 보지 않는다. 메인에서 들어왔는지만 구분한다.
const pickerMode = new URLSearchParams(location.search).get('picker') === '1';
const labels = {UNTESTED:'미실행',MOCK:'모의 실행',MATCH:'기준값 일치 · 미확인',MISMATCH:'기준값 불일치',
  REGRESSION:'확정값 회귀',CONFIRMED:'전체 성공 확인',REVIEW_REQUIRED:'서버 검토 필요',INPUT_ISSUE:'입력 확인 필요',ERROR:'실행 오류',
  EXACT:'동일',EQUIVALENT:'표현만 다름',DIFFERENT:'불일치',MISSING:'결과 없음',NO_REFERENCE:'기준값 없음',DUPLICATE:'중복 결과',
  TESTED:'실행한 상품 전체',UNTESTED_ONLY:'실행하지 않은 상품 전체',MISMATCH_OR_REGRESSION:'기준값 불일치 / 회귀',HAS_CONFIRMED_ROW:'성공 확인한 행이 있는 상품'};
let datasets = [], datasetId = '', currentPage = 1, pageData = null, selected = new Set();
let detail = null, displayedRun = null, editingRow = null, busy = false, stopped = false;
let access = {managementRequiresApiKey:true,aiRequiresApiKey:true};
let focusRow = null, focusTarget = null, focusOpened = false;
const number = value => Number(value || 0).toLocaleString('ko-KR');
function node(tag, text, className) {
  const value = document.createElement(tag);
  if (text !== undefined && text !== null) value.textContent = String(text);
  if (className) value.className = className;
  return value;
}
function usageTable(headers, rows) {
  const wrapper = node('div', null, 'table-scroll'), table = node('table', null, 'result-table');
  const head = node('thead'), heading = node('tr'), body = node('tbody');
  for (const title of headers) { const cell = node('th', title); cell.scope = 'col'; heading.append(cell); }
  head.append(heading);
  for (const values of rows) { const row = node('tr'); for (const value of values) row.append(node('td', value ?? '—')); body.append(row); }
  table.append(head,body); wrapper.append(table); return wrapper;
}
function message(text, error = false, inDetail = false) {
  const target = $(inDetail ? 'detail-message' : 'message');
  target.textContent = text; target.classList.toggle('error', error);
}
function badge(status) {
  const color = ['CONFIRMED','EXACT','EQUIVALENT'].includes(status) ? 'good'
    : ['REGRESSION','ERROR','DIFFERENT','DUPLICATE'].includes(status) ? 'bad'
    : ['INPUT_ISSUE','REVIEW_REQUIRED','MISMATCH','MISSING','NO_REFERENCE'].includes(status) ? 'warn' : '';
  return node('span', labels[status] || status, `badge ${color}`);
}
function base(id = datasetId) { return `/api/v1/evaluation/datasets/${encodeURIComponent(id)}`; }
async function api(url, options = {}) {
  const headers = new Headers(options.headers);
  if (options.json) { headers.set('Content-Type', 'application/json'); options.body = JSON.stringify(options.json); delete options.json; }
  const response = await fetch(url, {...options, headers, cache:'no-store'});
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    const reason = response.status === 413 ? '업로드 요청 크기가 제한을 초과했습니다. 화면을 새로고침한 후 다시 가져오세요.'
      : response.status === 404 ? error.reason || '테스트 관리 기능 또는 데이터를 찾을 수 없습니다. 서버 설정을 확인하세요.'
      : response.status === 401 ? '이 서버의 접근 인증이 필요합니다. 서버 인증 설정을 확인하세요.' : error.reason || `요청 실패 (${response.status})`;
    throw new Error(reason);
  }
  return response;
}
async function json(url, options) { return (await api(url, options)).json(); }
async function connect(preferred) {
  datasets = await json('/api/v1/evaluation/datasets');
  const saved = preferred || datasetId || localStorage.getItem('evaluation.dataset');
  $('dataset').replaceChildren();
  if (!datasets.length) $('dataset').append(new Option('Excel 파일을 가져오세요', ''));
  for (const data of datasets) $('dataset').append(new Option(`${data.filename} · ${number(data.products)}상품 · ${new Date(data.importedAt).toLocaleString('ko-KR')}`, data.id));
  datasetId = datasets.some(data => data.id === saved) ? saved : datasets[0]?.id || '';
  $('dataset').value = datasetId;
  await chooseDataset();
}
async function chooseDataset() {
  datasetId = $('dataset').value; currentPage = 1; selected.clear();
  $('backup').disabled = !datasetId;
  $('git-snapshot').disabled = !datasetId;
  if (!datasetId) { $('stats').replaceChildren(); return; }
  localStorage.setItem('evaluation.dataset', datasetId);
  const data = datasets.find(value => value.id === datasetId);
  $('dataset-info').textContent = `${number(data.rows)} 기준 행 · ${number(data.products)} 상품 · ${number(data.options)} 단품 · 기준값 확인 표시 ${number(data.issueRows)}행. 원본 등록값은 그대로 보존합니다.`;
  await loadPage();
}
// 검색 영역을 접어도 어떤 조건으로 걸러진 목록인지 알 수 있게 제목 줄에 요약을 쓴다.
function updateFilterSummary() {
  const query = $('query').value.trim(), target = $('target').value.trim(), state = $('state').value;
  const parts = [];
  if (query) parts.push(`검색 "${query}"`);
  if (state) parts.push(labels[state] || state);
  if (target) parts.push(`구매옵션 ${target}`);
  $('filter-summary').textContent = parts.length ? ` · ${parts.join(' · ')}` : '';
}
async function loadPage() {
  if (!datasetId) return;
  updateFilterSummary();
  const params = new URLSearchParams({page:currentPage,size:30,q:$('query').value,state:$('state').value,target:$('target').value.trim()});
  const id = datasetId;
  const result = await json(`${base(id)}/cases?${params}`);
  if (id !== datasetId) return;
  pageData = result; selected.clear(); $('select-page').checked = false;
  renderStats(result.counts); renderCases();
}
function renderStats(counts) {
  // 세 번째 값은 눌렀을 때 적용할 진행 상태다. null이면 조회 조건으로 바꿀 수 없는 카드다.
  // '성공 확인한 기준 행'은 상품이 아니라 행 수라서 상품 목록 조건으로 쓸 수 없다.
  const stats = [
    ['전체 상품',          counts.TOTAL,                                     ''],
    ['실행한 상품',        counts.TESTED,                                    'TESTED'],
    ['미실행',             (counts.TOTAL || 0) - (counts.TESTED || 0),       'UNTESTED_ONLY'],
    ['기준값 불일치 / 회귀', (counts.MISMATCH || 0)+(counts.REGRESSION || 0),  'MISMATCH_OR_REGRESSION'],
    ['전체 성공 확인 상품',  counts.CONFIRMED,                                 'CONFIRMED'],
    ['성공 확인한 기준 행',  counts.CONFIRMED_ROWS,                            'HAS_CONFIRMED_ROW',
      '행 수입니다. 누르면 확인된 행이 하나라도 있는 상품을 조회하므로 건수와 다를 수 있습니다.'],
  ];
  // 검색어나 구매옵션명이 함께 걸려 있으면 목록이 더 걸러진 상태이므로 선택 표시를 하지 않는다.
  const detailed = !!($('query').value.trim() || $('target').value.trim());
  $('stats').replaceChildren(...stats.map(([title,value,state,note]) => {
    const card = node(state === null ? 'div' : 'button', null, 'stat');
    card.append(node('span',title),node('strong',number(value)));
    if (note) card.title = note;
    if (state === null) return card;
    card.type = 'button';
    card.dataset.state = state;
    card.disabled = busy;
    card.setAttribute('aria-pressed', String(!detailed && $('state').value === state));
    card.addEventListener('click',() => {
      if (busy) return;
      $('state').value = state; currentPage = 1; handle(loadPage());
    });
    return card;
  }));
}
function renderCases() {
  $('cases').replaceChildren();
  if (!pageData.rows.length) { const cell = node('td','조건에 맞는 사례가 없습니다.','empty'); cell.colSpan = 8; const row = node('tr'); row.append(cell); $('cases').append(row); }
  for (const item of pageData.rows) {
    const row = node('tr'), choiceCell = node('td'), checkbox = node('input');
    checkbox.type = 'checkbox'; checkbox.setAttribute('aria-label',`${item.goodsId} 실행 선택`); checkbox.disabled = busy || item.state === 'INPUT_ISSUE';
    checkbox.addEventListener('change', () => { if (checkbox.checked) selected.add(item.id); else selected.delete(item.id); updateSelection(); }); choiceCell.append(checkbox);
    const product = node('td'); product.append(node('div',item.goodsName,'product-name'),node('span',`${item.goodsId} · ${item.categoryName}`,'subtext'));
    const state = node('td'); state.append(badge(item.state)); if (item.issues) state.append(node('span',`데이터 확인 ${item.issues}건`,'subtext'));
    const reviewed = node('td',`${item.confirmed} / ${item.references}`); if (item.regressions) reviewed.append(node('span',`회귀 ${item.regressions}건`,'subtext'));
    const action = node('td'), button = node('button','상세 확인','button quiet'); button.type = 'button'; button.addEventListener('click',() => openDetail(item.id).catch(error => message(error.message,true)));
    // 체크 후 상단 버튼으로 가지 않고 이 행만 바로 실행한다. 실행 경로는 선택 실행과 같다.
    const run = node('button','실행','button quiet run-case'); run.type = 'button';
    run.disabled = busy || item.state === 'INPUT_ISSUE';
    run.title = item.state === 'INPUT_ISSUE' ? '입력 확인이 필요한 사례는 실행할 수 없습니다.' : '이 상품만 실제 AI로 실행합니다.';
    run.addEventListener('click',() => handle(runCases([item.id])));
    action.append(run,button);
    row.append(choiceCell,product,node('td',`${item.options} / ${item.references}`),state,node('td',item.mode ? `${item.matched} / ${item.differences}` : '—'),reviewed,
      node('td',item.lastRunAt ? new Date(item.lastRunAt).toLocaleString('ko-KR') : '—'),action);
    $('cases').append(row);
  }
  const pages = Math.max(1,Math.ceil(pageData.total / pageData.pageSize));
  $('page-info').textContent = `조회 ${number(pageData.total)}상품 · ${currentPage} / ${pages}페이지`;
  $('previous').disabled = busy || currentPage <= 1; $('next').disabled = busy || currentPage >= pages;
  updateSelection();
}
function updateSelection() {
  $('selection-count').textContent = `${selected.size}상품 선택 · 한 번에 최대 20개`;
  $('run-selected').disabled = busy || !selected.size || selected.size > 20;
  // 일괄 확인은 AI를 호출하지 않으므로 20개 제한을 적용하지 않는다.
  $('confirm-selected').disabled = busy || !selected.size;
}
function setBusy(value) {
  busy = value;
  for (const id of ['connect','dataset','upload','load-sample','query','state','target','select-page','run-detail','backup','git-snapshot']) $(id).disabled = value || (['backup','git-snapshot'].includes(id) && !datasetId);
  for (const control of document.querySelectorAll('#filters button,#stats button,#cases input,#cases .run-case,#review-form button,#comparisons input,#comparisons button')) control.disabled = value;
  $('stop').hidden = !value; updateSelection();
}
function runCommand() {
  return {mode:'AI',allowPaidCall:true};
}
async function runCases(ids) {
  if (busy) return;
  if (!ids.length || ids.length > 20) throw new Error('한 번에 1~20개 상품을 선택하세요.');
  const command = runCommand(), id = datasetId;
  stopped = false; setBusy(true);
  $('stop').disabled = false; $('stop').textContent = '현재 사례 완료 후 중지';
  const region = document.querySelector('.progress-region'); region.hidden = false;
  $('progress').max = ids.length; $('progress').value = 0;
  let completed = 0, executionError = '';
  try {
    for (const caseId of ids) {
      if (stopped) break;
      $('progress-label').textContent = `${completed + 1} / ${ids.length} 실행 중 · ${command.mode === 'AI' ? '실제 AI' : '모의'}`;
      const endpoint = command.mode === 'MOCK' ? 'mock-runs' : 'runs';
      const result = await json(`${base(id)}/cases/${caseId}/${endpoint}`,{method:'POST',json:command});
      completed++; $('progress').value = completed;
      if (result.error) { executionError = result.error; break; }
      if (ids.length > completed && !stopped) await new Promise(resolve => setTimeout(resolve,2500));
    }
    $('progress-label').textContent = `${completed} / ${ids.length} 완료${stopped ? ' · 중지됨' : ''}`;
    message(executionError ? `${completed}건 실행 후 중지: ${executionError}`
      : `${command.mode === 'AI' ? '실제 AI' : '모의'} ${completed}상품 실행 결과를 저장했습니다. 상세에서 비교 결과를 검토하세요.`,!!executionError);
    if (detail && $('detail-dialog').open) await openDetail(detail.testCase.id);
  } finally { setBusy(false); await loadPage(); if (detail && $('detail-dialog').open) renderDetail(); }
}
// 등록 기준값과 일치한 행만 한 번에 성공 확인한다.
// 자동 확정이 아니라 사람이 묶음 단위로 판단하는 흐름이다. 확정 기준값은 회귀 판정의 기준이 되므로
// 불일치·중복·모의 실행·서버 미승인 결과는 건드리지 않는다.
async function confirmSelected(ids) {
  if (busy) return;
  if (!ids.length) throw new Error('성공 확인할 상품을 선택하세요.');
  stopped = false; setBusy(true);
  $('stop').disabled = false; $('stop').textContent = '현재 사례 완료 후 중지';
  const region = document.querySelector('.progress-region'); region.hidden = false;
  $('progress').max = ids.length; $('progress').value = 0;
  let done = 0, confirmedRows = 0, skippedCases = 0, skippedRows = 0;
  try {
    for (const caseId of ids) {
      if (stopped) break;
      $('progress-label').textContent = `${done + 1} / ${ids.length} 확인 중`;
      const info = await json(`${base()}/cases/${encodeURIComponent(caseId)}`);
      const run = info.latest;
      // 상세 화면의 성공 확인 체크박스와 같은 자격 조건을 쓴다.
      if (!run || run.mode !== 'AI' || !run.response?.success || run.error) { skippedCases++; done++; $('progress').value = done; continue; }
      const results = new Map((run.comparisons || []).map(value => [value.row, value]));
      for (const reference of info.testCase.references) {
        const comparison = results.get(reference.row);
        if (!comparison || !comparison.actual || !['EXACT','EQUIVALENT'].includes(comparison.status)) { skippedRows++; continue; }
        const review = info.reviews?.[reference.row];
        if (review && review.status === 'SUCCESS' && review.runId === run.id) continue;
        await json(`${base()}/cases/${encodeURIComponent(caseId)}/reviews/${reference.row}`,
          {method:'PUT', json:{status:'SUCCESS', note:review?.note || '', confirmedValue:'', runId:run.id}});
        confirmedRows++;
      }
      done++; $('progress').value = done;
    }
    $('progress-label').textContent = `${done} / ${ids.length} 완료${stopped ? ' · 중지됨' : ''}`;
    message(`${confirmedRows}개 기준 행을 성공 확인했습니다.`
      + (skippedCases ? ` 실제 AI 승인 결과가 없어 건너뛴 상품 ${skippedCases}개.` : '')
      + (skippedRows ? ` 일치하지 않아 건너뛴 행 ${skippedRows}개.` : ''));
  } finally { setBusy(false); await loadPage(); }
}
async function openDetail(caseId) {
  detail = await json(`${base()}/cases/${encodeURIComponent(caseId)}`);
  displayedRun = detail.latest; editingRow = null; $('review-form').hidden = true;
  $('detail-message').textContent = '';
  const request = detail.testCase.request;
  $('detail-code').textContent = `${request.goodsId} · ${request.categoryName}`; $('detail-title').textContent = request.goodsName;
  $('history').replaceChildren();
  if (!detail.history.length) $('history').append(new Option('아직 실행하지 않았습니다',''));
  detail.history.forEach((run,index) => $('history').append(new Option(`${index === 0 ? '최근 · ' : ''}${run.mode === 'AI' ? '실제 AI' : '모의'} · ${new Date(run.startedAt).toLocaleString('ko-KR')}`,run.id)));
  renderSourceInputs(request);
  renderDetail(); if (!$('detail-dialog').open) $('detail-dialog').showModal();
}
// AI가 왜 그렇게 판단했는지와 서버가 왜 승인·반려했는지를 보여준다.
// 행별 근거만으로는 전체 판단 이유를 알 수 없고, 반려된 결과는 원본 JSON에만 남아 있었다.
function renderJudgment(run) {
  const box = $('detail-judgment');
  box.replaceChildren();
  box.hidden = !run;
  if (!run) return;

  const response = run.response, ai = response?.aiAssessment, server = response?.serverAssessment;
  const add = (title, body, extra) => {
    const block = node('div', null, 'judgment-block');
    block.append(node('h3', title), node('p', body));
    if (extra) block.append(node('span', extra, 'subtext'));
    box.append(block);
  };

  if (ai) {
    // certain은 AI가 스스로 확신했는지, confidence는 그 확신의 정도다. 둘 다 서버 승인과는 별개다.
    const certain = ai.certain === true ? '확실' : ai.certain === false ? '불확실' : '확인 불가';
    const confidence = Number.isFinite(ai.confidence) ? ai.confidence.toFixed(2) : '—';
    add('AI 판단', ai.reason || '설명이 없습니다.', `${certain} · confidence ${confidence}`);
  } else if (run.mode === 'MOCK') {
    add('AI 판단', '모의 실행이라 AI 판단 데이터가 없습니다.');
  } else {
    add('AI 판단', response?.reason || '확인할 수 있는 AI 판단 데이터가 없습니다.');
  }

  if (server) {
    const threshold = Number.isFinite(server.confidenceThreshold) ? server.confidenceThreshold.toFixed(2) : '—';
    add('서버 판정', server.reason || '설명이 없습니다.', `${server.decisionCode || '—'} · 자동적용 기준 ${threshold}`);
  }
  if (run.error) add('실행 오류', run.error);
}
// 메인 화면의 01~03 입력을 같은 구성으로 모두 보여준다.
// JSON 덩어리로 두면 허용 구매옵션이 빠진 것도 눈에 띄지 않는다.
function renderSourceInputs(request) {
  const box = $('source-inputs');
  box.replaceChildren();
  const add = (title, body) => box.append(node('h3', title), body);

  // usageTable은 null만 —로 바꾸므로 빈 문자열도 null로 넘긴다.
  add('상품 정보', usageTable(['상품 ID','상품명','내부 카테고리'],
    [[request.goodsId, request.goodsName, request.categoryName || null]]));
  add('상품정보고시', node('pre', request.productNoticeText || '(없음)'));
  add('상품 기술서 · 구성', node('pre', request.productCompositionText || '(없음)'));

  const options = request.options || [];
  add('스토아 단품 옵션', options.length
    ? usageTable(['단품 ID','원본 옵션명'], options.map(option => [option.optionId, option.optionName1 || null]))
    : node('p','단일상품으로 처리합니다.','hint'));

  // 단위 설정은 구매옵션명별로 붙으므로 허용 목록을 기준으로 합쳐 메인 03 표와 같은 열로 만든다.
  const units = new Map((request.purchaseOptionUnits || []).map(unit => [unit.purchaseOptionName, unit]));
  const allowed = request.allowedPurchaseOptions || [];
  add('쿠팡 구매옵션명', allowed.length
    ? usageTable(['구매옵션명','기본단위','단위 선택지'], allowed.map(name => {
        const unit = units.get(name);
        return [name, unit?.defaultUnit || null, (unit?.unitOptions || []).join(', ') || null];
      }))
    : node('p','허용 구매옵션이 없습니다.','hint'));
}
function renderDetail() {
  const run = displayedRun, current = !run || run.id === detail.latest?.id;
  // 승인·모의·오류를 색으로 구분한다. 글만으로는 작은 배너에서 잘 읽히지 않는다.
  const tone = !run ? '' : run.error || (run.mode === 'AI' && !run.response?.success) ? 'bad'
    : run.mode === 'MOCK' ? 'warn' : 'good';
  $('detail-status').className = `detail-status${tone ? ' ' + tone : ''}`;
  $('detail-status').textContent = !run ? '미실행 · 원본 등록값을 검토하거나 실제 AI를 실행하세요.'
    : `${run.mode === 'MOCK' ? '모의 실행 · 실제 AI 성능 평가와 성공 확인에 포함하지 않습니다.' : run.response?.success ? '서버 승인 · 일치 여부와 별도로 내용을 검토해 성공 체크하세요.' : '서버 미승인 · AI 제안은 진단용이며 성공 체크할 수 없습니다.'}${current ? '' : ' · 이전 실행 이력 (검토는 최근 결과에서만 가능)'}${run.error ? ` · ${run.error}` : ''}`;
  $('detail-issues').textContent = detail.testCase.issues.join('\n');
  renderJudgment(run);
  $('run-detail').disabled = busy || !!detail.testCase.issues.length;
  $('comparisons').replaceChildren();
  const results = new Map((run?.comparisons || []).map(value => [value.row,value]));
  for (const reference of detail.testCase.references) {
    const comparison = results.get(reference.row), review = detail.reviews[reference.row], row = node('tr');
    const identity = node('td',`${reference.row}행 · ${reference.optionId}`); identity.append(node('span',reference.sourceDetail || reference.colorSizeName,'subtext'));
    const baseline = node('td',reference.baseline || '(빈 값)'); if (reference.issues.length) baseline.append(node('span',reference.issues.join(' · '),'subtext'));
    const confirmed = review?.confirmedValue || comparison?.confirmedValue;
    const successCell = node('td'), checkbox = node('input'); checkbox.type = 'checkbox'; checkbox.setAttribute('aria-label',`${reference.row}행 성공 확인`);
    checkbox.checked = review?.status === 'SUCCESS' && review.runId === run?.id;
    checkbox.disabled = busy || !current || !run || run.mode !== 'AI' || !run.response?.success || !!run.error || !comparison?.actual || comparison.status === 'DUPLICATE';
    checkbox.addEventListener('change',async () => {
      checkbox.disabled = true;
      try { await saveReview(reference.row,{status:checkbox.checked?'SUCCESS':'UNREVIEWED',note:review?.note || '',confirmedValue:''}); }
      catch (error) { message(error.message,true,true); renderDetail(); }
    }); successCell.append(checkbox);
    const reviewCell = node('td'), edit = node('button','검토 / 교정','button quiet'); edit.type='button'; edit.disabled = busy || !current;
    edit.addEventListener('click',() => editReview(reference)); reviewCell.append(edit);
    if (review) reviewCell.append(node('span',`${review.status === 'SUCCESS' ? '성공 확인' : review.status === 'NEEDS_FIX' ? '시스템 개선 필요' : '기준값 확인 필요'}${review.runId && review.runId !== run?.id ? ' · 이전 검토' : ''}${review.note ? ' · '+review.note : ''}`,'subtext'));
    const statusCell = node('td'); if (comparison) statusCell.append(badge(comparison.status)); else statusCell.textContent = '미실행';
    const actual = node('td',comparison?.actual ?? '—'); if (comparison?.evidence) actual.append(node('span',comparison.evidence,'subtext'));
    row.append(identity,node('td',reference.target),baseline,node('td',confirmed || '—'),actual,statusCell,successCell,reviewCell); $('comparisons').append(row);
    if (focusRow === reference.row) { row.classList.add('focused'); focusTarget = {row, reference}; }
  }
  // 링크로 지정된 행은 한 번만 강조하고 검토 입력을 연다. 이후 다시 그릴 때는 강조만 유지한다.
  if (focusTarget) {
    const target = focusTarget; focusTarget = null;
    target.row.scrollIntoView({block:'center'});
    if (!focusOpened && current) { focusOpened = true; editReview(target.reference); }
  }
  $('raw-request').textContent = JSON.stringify(run?.request || detail.testCase.request,null,2);
  $('raw-response').textContent = run ? JSON.stringify(run.response || {error:run.error},null,2) : '아직 실행하지 않았습니다.';
  $('detail-copy-status').textContent = '';
  // 실행 전에는 빈 박스를 두지 않고 영역째 숨긴다.
  $('detail-usage').hidden = !run;
  if (run) {
    const usage = renderAiUsage(run.response || {inferenceSource:run.mode === 'MOCK' ? 'TEST' : 'AI'},node,usageTable);
    const summary = usage.querySelector('summary');
    const heading = node('h3',summary.textContent);
    heading.id = 'detail-usage-title';
    summary.remove();
    $('detail-usage').replaceChildren(heading,...Array.from(usage.childNodes));
  } else {
    $('detail-usage').replaceChildren();
  }
}
document.querySelectorAll('[data-detail-copy]').forEach(button => button.addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText($(button.dataset.detailCopy).textContent);
    $('detail-copy-status').textContent = `${button.dataset.detailCopy === 'raw-request' ? '요청' : '응답'}을 복사했습니다.`;
  } catch { $('detail-copy-status').textContent = '복사하지 못했습니다. JSON 내용을 선택해 복사하세요.'; }
}));
function editReview(reference) {
  editingRow = reference.row; const review = detail.reviews[reference.row];
  $('review-title').textContent = `${reference.row}행 · ${reference.optionId} · ${reference.target}`;
  $('review-state').value = review?.status || 'REFERENCE_ISSUE'; $('review-value').value = review?.confirmedValue || '';
  $('review-note').value = review?.note || ''; $('review-form').hidden = false; $('review-note').focus();
}
async function saveReview(row, command) {
  await json(`${base()}/cases/${detail.testCase.id}/reviews/${row}`,{method:'PUT',json:{...command,runId:detail.latest?.id || null}});
  const caseId = detail.testCase.id;
  await openDetail(caseId); await loadPage(); message('검토 결과를 저장했습니다.',false,true);
}
function handle(promise, inDetail = false) { promise.catch(error => message(error.message,true,inDetail)); }
$('connect').addEventListener('click',() => handle(connect()));
$('load-sample').addEventListener('click',() => {
  if (busy) return;
  setBusy(true); $('stop').hidden = true;
  $('load-sample').disabled = true; $('load-sample').textContent = '샘플 데이터 불러오는 중…';
  handle((async () => {
    try {
      const result = await json('/api/v1/evaluation/sample',{method:'POST'});
      $('query').value = ''; $('state').value = ''; $('target').value = '';
      await connect(result.id);
      message(`${result.filename} · ${number(result.products)}상품, ${number(result.rows)} 기준 행을 불러왔습니다. 기존 테스트·검토 이력은 유지됩니다.`);
    } finally { setBusy(false); if (pageData) renderCases(); $('load-sample').textContent = '전달한 샘플 데이터 불러오기'; }
  })());
});
$('dataset').addEventListener('change',() => handle(chooseDataset()));
$('filters').addEventListener('submit',event => { event.preventDefault(); if (busy) return; currentPage=1; handle(loadPage()); });
$('previous').addEventListener('click',() => { currentPage--; handle(loadPage()); });
$('next').addEventListener('click',() => { currentPage++; handle(loadPage()); });
$('select-page').addEventListener('change',() => {
  const checked = $('select-page').checked;
  Array.from(document.querySelectorAll('#cases input:not(:disabled)')).forEach((input,index) => {
    input.checked = checked && index < 20; input.dispatchEvent(new Event('change'));
  });
});
$('run-selected').addEventListener('click',() => handle(runCases([...selected])));
$('confirm-selected').addEventListener('click',() => {
  const ids = [...selected];
  // 확정 기준값은 되돌리려면 행마다 검토를 초기화해야 하므로 한 번 확인받는다.
  if (!confirm(`선택한 ${ids.length}개 상품에서 등록 기준값과 일치한 행을 성공 확인으로 저장합니다.\n확정 기준값으로 저장되어 다음 실행부터 회귀 비교의 기준이 됩니다.`)) return;
  handle(confirmSelected(ids));
});
$('run-detail').addEventListener('click',() => handle(runCases([detail.testCase.id]),true));
$('stop').addEventListener('click',() => { stopped = true; $('stop').disabled = true; $('stop').textContent = '현재 사례 완료 후 중지합니다'; });
$('close-detail').addEventListener('click',() => { focusRow = null; $('detail-dialog').close(); });
$('history').addEventListener('change',() => { displayedRun = detail.history.find(value => value.id === $('history').value) || null; $('review-form').hidden = true; renderDetail(); });
$('cancel-review').addEventListener('click',() => $('review-form').hidden = true);
$('review-form').addEventListener('submit',event => { event.preventDefault(); if (busy) return;
  handle(saveReview(editingRow,{status:$('review-state').value,note:$('review-note').value,confirmedValue:$('review-value').value}),true);
});
$('upload-form').addEventListener('submit',event => { event.preventDefault(); if (busy) return;
  const file = $('upload-file').files[0]; if (!file) return;
  if (file.size > 32*1024*1024) { message('32MB 이하의 파일을 선택하세요.',true); return; }
  const restoring = file.name.toLowerCase().endsWith('.zip');
  setBusy(true); $('stop').hidden = true; message(restoring ? '백업을 별도 데이터 세트로 복원하는 중입니다…' : 'Excel 데이터를 가져오는 중입니다…');
  handle((async () => { try {
    let result;
    if (restoring || file.size > 3*1024*1024) {
      const ticket = await json('/api/v1/evaluation/imports',{method:'POST',json:{filename:file.name,size:file.size}});
      for(let part=0;part<ticket.parts;part++) {
        message(`파일 업로드 ${part+1} / ${ticket.parts}`);
        await api(`/api/v1/evaluation/imports/${ticket.id}/parts/${part}`,{method:'PUT',headers:{'Content-Type':'application/octet-stream'},body:file.slice(part*1024*1024,(part+1)*1024*1024)});
      }
      message(restoring ? '백업의 입력·실행·검토 이력을 검증하고 복원하는 중입니다…' : '업로드한 Excel 데이터를 분석하고 저장하는 중입니다…');
      result = await json(`/api/v1/evaluation/imports/${ticket.id}/complete`,{method:'POST'});
    } else {
      const data = new FormData(); data.append('file',file);
      result = await json('/api/v1/evaluation/datasets',{method:'POST',body:data});
    }
    await connect(result.id);
    message(restoring ? `${number(result.products)}상품의 백업을 복원했습니다. 실행·검토 이력을 유지하며 기존 데이터는 변경하지 않았습니다.`
      : `${number(result.products)}상품 · ${number(result.rows)} 기준 행을 가져왔습니다. 확인 표시 ${number(result.issueRows)}행은 원본 기준값을 검토하세요.`);
  } finally { setBusy(false); if(pageData) renderCases(); } })());
});
$('backup').addEventListener('click',() => handle((async () => {
  const response = await api(`${base()}/backup`); const url = URL.createObjectURL(await response.blob());
  const link = node('a'); link.href = url; link.download = `evaluation-${datasetId}.zip`; link.click(); setTimeout(() => URL.revokeObjectURL(url),1000);
})()));
$('git-snapshot').addEventListener('click',() => handle((async () => {
  const snapshot = await json(`${base()}/git-snapshot`);
  const url = URL.createObjectURL(new Blob([JSON.stringify(snapshot,null,2)+'\n'],{type:'application/json'}));
  const link = node('a'); link.href = url; link.download = `evaluation-${datasetId}.json`; link.click();
  setTimeout(() => URL.revokeObjectURL(url),1000);
  message(`검토한 ${number(snapshot.cases.length)}상품을 내보냈습니다. 저장소의 evals 폴더에 같은 파일명으로 저장해 변경을 비교하세요.`);
})()));
async function initialize() {
  access = await json('/api/v1/evaluation/access');
  $('access-hint').textContent = access.managementRequiresApiKey
    ? '이 서버의 접근 인증이 필요합니다. 서버 인증 설정을 확인하세요.'
    : '';
  $('access-hint').hidden = !access.managementRequiresApiKey;
  // 검토 목록 CSV의 링크(?dataset=&case=&row=)로 들어오면 해당 상품과 행을 바로 연다.
  const link = deepLink(location.search);
  await connect(link.dataset);
  if (link.dataset && link.dataset !== datasetId) { message('링크의 데이터 세트가 이 서버에 없습니다. 복원된 데이터 세트 ID를 확인하세요.',true); return; }
  if (link.caseId) { focusRow = link.row; await openDetail(link.caseId); }
}
function deepLink(search) {
  const params = new URLSearchParams(search), row = Number(params.get('row'));
  const dataset = params.get('dataset') || '', caseId = params.get('case') || '';
  return { dataset: /^[a-f0-9-]{36}$/.test(dataset) ? dataset : '', caseId: /^[A-Za-z0-9_-]{1,64}$/.test(caseId) ? caseId : '',
    row: Number.isInteger(row) && row > 0 ? row : null };
}
handle(initialize());
