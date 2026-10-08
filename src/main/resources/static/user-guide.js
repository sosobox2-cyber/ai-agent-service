// 서비스에 포함된 MD의 제목·문단·표·목록·코드 블록을 표시한다. 원문 HTML은 실행하지 않는다.
function guideNode(tag, text) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  return node;
}

function guideInline(node, text) {
  const pattern = /(`[^`]+`|\*\*[^*]+\*\*)/g;
  let cursor = 0;
  for (const match of text.matchAll(pattern)) {
    node.append(document.createTextNode(text.slice(cursor, match.index)));
    const code = match[0].startsWith('`');
    node.append(guideNode(code ? 'code' : 'strong', match[0].slice(code ? 1 : 2, code ? -1 : -2)));
    cursor = match.index + match[0].length;
  }
  node.append(document.createTextNode(text.slice(cursor)));
}

function renderGuide(markdown, root, toc) {
  const lines = markdown.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').split('\n');
  root.replaceChildren();
  toc.replaceChildren(guideNode('h2', '목차'));
  const tocList = guideNode('ol');
  toc.append(tocList);
  let section = 0;
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) { i++; continue; }
    if (line.startsWith('```')) {
      const language = line.slice(3).trim();
      const content = [];
      i++;
      while (i < lines.length && !lines[i].startsWith('```')) content.push(lines[i++]);
      i++;
      const pre = guideNode('pre');
      const code = guideNode('code', content.join('\n'));
      code.dataset.language = language;
      pre.append(code);
      root.append(pre);
      continue;
    }
    const heading = /^(#{1,3})\s+(.+)$/.exec(line);
    if (heading) {
      const node = guideNode(`h${heading[1].length}`);
      guideInline(node, heading[2]);
      if (heading[1].length === 2) {
        node.id = `guide-section-${++section}`;
        const item = guideNode('li');
        const link = guideNode('a', heading[2]);
        link.href = `#${node.id}`;
        item.append(link);
        tocList.append(item);
      }
      root.append(node);
      i++;
      continue;
    }
    if (line.startsWith('|') && /^\|(?:\s*:?-+:?\s*\|)+\s*$/.test(lines[i + 1] || '')) {
      const wrapper = guideNode('div');
      wrapper.className = 'table-scroll';
      const table = guideNode('table');
      const head = guideNode('thead');
      const body = guideNode('tbody');
      const addRow = (text, tag, parent) => {
        const row = guideNode('tr');
        for (const cell of text.split('|').slice(1, -1)) {
          const node = guideNode(tag);
          if (tag === 'th') node.scope = 'col';
          guideInline(node, cell.trim());
          row.append(node);
        }
        parent.append(row);
      };
      addRow(line, 'th', head);
      i += 2;
      while (i < lines.length && lines[i].startsWith('|')) addRow(lines[i++], 'td', body);
      table.append(head, body);
      wrapper.append(table);
      root.append(wrapper);
      continue;
    }
    const list = /^(\d+\.|-)\s+(.+)$/.exec(line);
    if (list) {
      const ordered = list[1] !== '-';
      const node = guideNode(ordered ? 'ol' : 'ul');
      while (i < lines.length) {
        const item = /^(\d+\.|-)\s+(.+)$/.exec(lines[i]);
        if (!item || (item[1] !== '-') !== ordered) break;
        const li = guideNode('li');
        guideInline(li, item[2]);
        node.append(li);
        i++;
      }
      root.append(node);
      continue;
    }
    const paragraph = guideNode('p');
    guideInline(paragraph, line);
    root.append(paragraph);
    i++;
  }
  toc.hidden = section === 0;
}

async function loadGuide() {
  const root = document.querySelector('#guide-content');
  const source = root.dataset.source || '/user-guide.md';
  const label = root.dataset.label || '사용설명서';
  try {
    const response = await fetch(source);
    if (!response.ok) throw new Error('guide unavailable');
    renderGuide(await response.text(), root, document.querySelector('#guide-toc'));
  } catch {
    root.replaceChildren(guideNode('p', `${label}를 불러오지 못했습니다. 새로고침 후 다시 확인하세요.`));
  }
}

loadGuide();
