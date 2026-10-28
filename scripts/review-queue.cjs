// 검토 목록 CSV를 사람 검토 순서로 정렬하고 평가 화면 바로가기 링크를 붙인다. AI·서버를 호출하지 않는다.
// 사용법: node scripts/review-queue.cjs <입력.csv> <출력.csv> <운영데이터셋ID> [우선구매옵션,...]
'use strict';
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const BASE = 'https://ai-agent-service-production-7624.up.railway.app/evaluation.html';
// 빨리 끝나는 판단(기준값 깨짐, 표기 차이)을 먼저 두고 판단이 어려운 행을 뒤로 보낸다.
const ORDER = ['REFERENCE_BROKEN', 'AI_LIKELY_CORRECT', 'AI_LIKELY_WRONG', 'UNSURE'];

function parseCsv(text) {
  const rows = []; let row = [], field = '', quoted = false;
  text = text.replace(/^﻿/, '');
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++; } else if (c === '"') quoted = false; else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(field); rows.push(row); row = []; field = '';
    } else field += c;
  }
  if (field || row.length) { row.push(field); rows.push(row); }
  return rows;
}
const csvField = v => `"${String(v ?? '').replace(/"/g, '""')}"`;

function link(datasetId, caseId, row) {
  return `${BASE}?${new URLSearchParams({ dataset: datasetId, case: caseId, row: String(row) })}`;
}

function queue(rows, datasetId, priority) {
  const [header, ...body] = rows;
  const col = name => header.indexOf(name);
  const [caseCol, rowCol, targetCol, linkCol, kindCol] = ['caseId', 'row', '구매옵션명', '평가 화면 링크', '제안 분류'].map(col);
  if ([caseCol, rowCol, targetCol, linkCol].some(i => i < 0)) throw Error('검토 목록 CSV의 열 이름이 다릅니다.');
  const rank = r => [priority.includes(r[targetCol]) ? priority.indexOf(r[targetCol]) : priority.length,
    kindCol < 0 ? 0 : (ORDER.indexOf(r[kindCol]) + 1 || ORDER.length + 1), r[caseCol], Number(r[rowCol])];
  const sorted = body.filter(r => r.length > 1).sort((a, b) => {
    const x = rank(a), y = rank(b);
    for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return x[i] < y[i] ? -1 : 1;
    return 0;
  });
  return [['순번', ...header], ...sorted.map((r, i) => {
    const copy = [...r]; copy[linkCol] = link(datasetId, r[caseCol], r[rowCol]); return [String(i + 1), ...copy];
  })];
}

function main() {
  const [input, output, datasetId, targets] = process.argv.slice(2);
  if (!input || !output || !/^[a-f0-9-]{36}$/.test(datasetId || ''))
    throw Error('사용법: node scripts/review-queue.cjs <입력.csv> <출력.csv> <운영데이터셋ID> [우선구매옵션,...]');
  const out = path.resolve(output);
  if (!out.startsWith(path.join(ROOT, 'logs') + path.sep)) throw Error('출력은 이 저장소 logs 아래여야 합니다.');
  const priority = (targets || '').split(',').map(t => t.trim()).filter(Boolean);
  const result = queue(parseCsv(fs.readFileSync(path.resolve(input), 'utf8')), datasetId, priority);
  fs.writeFileSync(out, '﻿' + result.map(r => r.map(csvField).join(',')).join('\r\n') + '\r\n', { flag: 'wx' });
  const counts = {};
  const targetCol = result[0].indexOf('구매옵션명');
  for (const r of result.slice(1)) counts[r[targetCol]] = (counts[r[targetCol]] || 0) + 1;
  console.log(JSON.stringify({ rows: result.length - 1, priority: priority.map(t => [t, counts[t] || 0]) }));
}

module.exports = { parseCsv, queue, link };
if (require.main === module) try { main(); } catch (e) { console.error(e.message); process.exitCode = 1; }
