'use strict';
const $ = id => document.getElementById(id);
const pickerMode = new URLSearchParams(location.search).get('picker') === '1' && window.parent !== window;
if (pickerMode) {
  document.querySelector('.page-header a').hidden = true;
  document.querySelector('.page-header p').hidden = true;
}
const labels = {UNTESTED:'미실행',MOCK:'모의 실행',MATCH:'기준값 일치 · 미확인',MISMATCH:'기준값 불일치',
  REGRESSION:'확정값 회귀',CONFIRMED:'전체 성공 확인',REVIEW_REQUIRED:'서버 검토 필요',INPUT_ISSUE:'입력 확인 필요',ERROR:'실행 오류',
  EXACT:'동일',EQUIVALENT:'표현만 다름',DIFFERENT:'불일치',MISSING:'결과 없음',NO_REFERENCE:'기준값 없음',DUPLICATE:'중복 결과'};
let datasets = [], datasetId = '', currentPage = 1, pageData = null, selected = new Set();
let detail = null, displayedRun = null, editingRow = null, busy = false, stopped = false;
let access = {managementRequiresApiKey:true,aiRequiresApiKey:true};
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
  const stats = [ ['전체 상품',counts.TOTAL],['미실행',counts.UNTESTED],['기준값 불일치 / 회귀',(counts.MISMATCH || 0)+(counts.REGRESSION || 0)],
    ['전체 성공 확인 상품',counts.CONFIRMED],['성공 확인한 기준 행',counts.CONFIRMED_ROWS] ];
  $('stats').replaceChildren(...stats.map(([title,value]) => {
    const card = node('div', null, 'stat'); card.append(node('span',title),node('strong',number(value))); return card;
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
}
function setBusy(value) {
  busy = value;
  for (const id of ['connect','dataset','upload','load-sample','query','state','target','select-page','run-detail','backup']) $(id).disabled = value || (id==='backup' && !datasetId);
  for (const control of document.querySelectorAll('#filters button,#cases input,#cases .run-case,#review-form button,#comparisons input,#comparisons button')) control.disabled = value;
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
window.addEventListener('message', event => {
  if (!pickerMode || event.origin !== location.origin || event.source !== window.parent
      || event.data?.type !== 'evaluation:refresh' || event.data.datasetId !== datasetId || busy) return;
  handle((async () => {
    await loadPage();
    if (detail && $('detail-dialog').open && detail.testCase.id === event.data.caseId) await openDetail(event.data.caseId);
  })());
});
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
$('run-detail').addEventListener('click',() => handle(runCases([detail.testCase.id]),true));
$('stop').addEventListener('click',() => { stopped = true; $('stop').disabled = true; $('stop').textContent = '현재 사례 완료 후 중지합니다'; });
$('close-detail').addEventListener('click',() => $('detail-dialog').close());
$('history').addEventListener('change',() => { displayedRun = detail.history.find(value => value.id === $('history').value) || null; $('review-form').hidden = true; renderDetail(); });
$('cancel-review').addEventListener('click',() => $('review-form').hidden = true);
$('review-form').addEventListener('submit',event => { event.preventDefault(); if (busy) return;
  handle(saveReview(editingRow,{status:$('review-state').value,note:$('review-note').value,confirmedValue:$('review-value').value}),true);
});
$('upload-form').addEventListener('submit',event => { event.preventDefault(); if (busy) return;
  const file = $('upload-file').files[0]; if (!file) return;
  if (file.size > 32*1024*1024) { message('32MB 이하의 파일을 선택하세요.',true); return; }
  setBusy(true); $('stop').hidden = true; message('Excel 데이터를 가져오는 중입니다…');
  handle((async () => { try {
    let result;
    if (file.size > 3*1024*1024) {
      const ticket = await json('/api/v1/evaluation/imports',{method:'POST',json:{filename:file.name,size:file.size}});
      for(let part=0;part<ticket.parts;part++) {
        message(`Excel 파일 업로드 ${part+1} / ${ticket.parts}`);
        await api(`/api/v1/evaluation/imports/${ticket.id}/parts/${part}`,{method:'PUT',headers:{'Content-Type':'application/octet-stream'},body:file.slice(part*1024*1024,(part+1)*1024*1024)});
      }
      message('업로드한 Excel 데이터를 분석하고 저장하는 중입니다…');
      result = await json(`/api/v1/evaluation/imports/${ticket.id}/complete`,{method:'POST'});
    } else {
      const data = new FormData(); data.append('file',file);
      result = await json('/api/v1/evaluation/datasets',{method:'POST',body:data});
    }
    await connect(result.id);
    message(`${number(result.products)}상품 · ${number(result.rows)} 기준 행을 가져왔습니다. 확인 표시 ${number(result.issueRows)}행은 원본 기준값을 검토하세요.`);
  } finally { setBusy(false); if(pageData) renderCases(); } })());
});
$('backup').addEventListener('click',() => handle((async () => {
  const response = await api(`${base()}/backup`); const url = URL.createObjectURL(await response.blob());
  const link = node('a'); link.href = url; link.download = `evaluation-${datasetId}.zip`; link.click(); setTimeout(() => URL.revokeObjectURL(url),1000);
})()));
async function initialize() {
  access = await json('/api/v1/evaluation/access');
  $('access-hint').textContent = access.managementRequiresApiKey
    ? '이 서버의 접근 인증이 필요합니다. 서버 인증 설정을 확인하세요.'
    : '';
  $('access-hint').hidden = !access.managementRequiresApiKey;
  await connect();
}
handle(initialize());
