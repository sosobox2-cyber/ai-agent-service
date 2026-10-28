'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { parseCsv, queue, link } = require('../../../scripts/review-queue.cjs');

test('따옴표 안의 쉼표·줄바꿈·따옴표를 보존해 CSV를 읽는다', () => {
  const rows = parseCsv('﻿a,b\r\n"x, y","줄\n바꿈 ""인용"""\r\n');
  assert.deepEqual(rows, [['a', 'b'], ['x, y', '줄\n바꿈 "인용"']]);
});

test('우선 구매옵션과 빠른 판단 순서로 정렬하고 상품·행 바로가기를 붙인다', () => {
  const header = ['caseId', 'row', '구매옵션명', '평가 화면 링크', '제안 분류'];
  const rows = [header,
    ['c2', '5', '개당 중량', '', 'REFERENCE_BROKEN'],
    ['c1', '3', '수량', '', 'UNSURE'],
    ['c1', '2', '색상', '', 'AI_LIKELY_WRONG'],
    ['c3', '9', '색상', '', 'REFERENCE_BROKEN']];
  const id = '74df8638-d622-498b-a242-f894e581c2e2';
  const result = queue(rows, id, ['색상', '수량']);
  assert.deepEqual(result.map(r => r[0]), ['순번', '1', '2', '3', '4']);
  assert.deepEqual(result.slice(1).map(r => `${r[1]}:${r[2]}`), ['c3:9', 'c1:2', 'c1:3', 'c2:5']);
  assert.equal(result[1][4], link(id, 'c3', '9'));
  assert.ok(result[1][4].endsWith(`?dataset=${id}&case=c3&row=9`));
  assert.throws(() => queue([['a']], id, []), /열 이름/);
});
