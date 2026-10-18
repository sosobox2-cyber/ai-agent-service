// 메인 화면과 테스트 관리에서 동일한 사용량 표시를 사용한다.
function summarizeAiUsage(calls) {
  const summary = {};
  for (const field of ['input_tokens', 'cached_tokens', 'output_tokens', 'total_tokens', 'elapsedMs', 'estimated_cost_usd']) {
    summary[field] = calls.length && calls.every(call => Number.isFinite(call?.[field]) && call[field] >= 0)
      ? calls.reduce((total, call) => total + call[field], 0) : null;
  }
  return summary;
}

// 최초·재시도별 사용량과 합계를 보여 주고 캐시 입력이 전체 입력의 일부임을 안내한다.
function renderAiUsage(response, element, table) {
  const calls = Array.isArray(response?.aiUsage) ? response.aiUsage : [];
  const details = element('details', '', 'usage-details');
  const modes = calls.map(call => call?.mode || '—').join(' → ');
  details.append(element('summary', `AI 호출·토큰 사용량${calls.length ? ` · ${modes} · ${calls.length}회` : ''}`));
  if (!calls.length) {
    details.append(element('p', response?.inferenceSource === 'TEST'
      ? '테스트 모드는 실제 AI를 호출하지 않으므로 토큰 사용량과 비용이 없습니다.'
      : '이 응답에는 AI 호출 사용량 정보가 없습니다.', 'usage-note'));
    return details;
  }
  const number = value => Number.isFinite(value) && value >= 0 ? value.toLocaleString('ko-KR') : '확인 불가';
  const cost = value => Number.isFinite(value) && value >= 0 ? `$${value.toFixed(8)}` : '확인 불가';
  const elapsed = value => Number.isFinite(value) && value >= 0 ? `${number(value)} ms` : '확인 불가';
  const values = call => [number(call.input_tokens), number(call.cached_tokens), number(call.output_tokens),
    number(call.total_tokens), elapsed(call.elapsedMs), cost(call.estimated_cost_usd)];
  const label = (call, index) => call.retryCount > 0 ? `재추론 (${call.attempt || index + 1}차)`
    : call.attempt === 1 ? '최초 호출' : `${call.attempt || index + 1}차 호출`;
  const usageRows = calls.map((call, index) => [label(call, index),
    call.mode, call.model, ...values(call)]);
  if (calls.length > 1) {
    usageRows.push(['합계', '—', '—', ...values(summarizeAiUsage(calls))]);
  }
  details.append(table(['호출', '모드', '모델', '입력 토큰', '캐시 입력', '출력 토큰', '총 토큰', '처리시간', '예상 비용 (USD)'], usageRows),
    element('p', '캐시 입력은 입력 토큰에 포함됩니다. '
      + (calls.length > 1 ? '합계에는 모든 호출이 포함됩니다. 처리시간 합계는 호출 시간의 합산입니다. ' : '')
      + '예상 비용은 지원 모델의 단가로 계산하며, 제공되지 않은 수치는 확인 불가로 표시합니다.', 'usage-note'));
  calls.forEach((call, index) => {
    if (!(call.retryCount > 0)) return;
    const reasons = Array.isArray(call.retryReasons) ? call.retryReasons : call.retryReason ? [call.retryReason] : [];
    if (reasons.length) details.append(element('p', `${label(call, index)} 사유: ${reasons.join(', ')}`, 'usage-note'));
  });
  return details;
}

