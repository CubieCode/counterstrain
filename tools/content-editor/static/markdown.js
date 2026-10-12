// A small Markdown renderer for the editor's preview. It builds DOM nodes (never innerHTML),
// so file contents can never inject markup. Covers what the repository's text uses:
// ATX headings, paragraphs, ordered and unordered lists (nested by indent), block quotes,
// fenced code, tables, rules, and inline code, bold, italic, links and images (as text).

function el(tag, attrs, kids) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) node.setAttribute(k, v);
  for (const kid of kids || []) node.append(kid);
  return node;
}

export function slug(text) {
  return text.toLowerCase().replace(/[^\w\s-]/g, '').trim().replace(/\s+/g, '-');
}

// Inline spans: `code`, **bold**, *italic* / _italic_, [text](href), ![alt](src).
function inline(text) {
  const out = [];
  const pattern = /(`+)([\s\S]*?)\1|!\[([^\]]*)\]\(([^)]*)\)|\[([^\]]+)\]\(([^)\s]+)(?:\s+"[^"]*")?\)|\*\*([^*]+)\*\*|__([^_]+)__|\*([^*\s][^*]*)\*|(?<![\w])_([^_\s][^_]*)_(?![\w])/g;
  let last = 0;
  for (const m of text.matchAll(pattern)) {
    if (m.index > last) out.push(text.slice(last, m.index));
    if (m[1]) out.push(el('code', {}, [m[2]]));
    else if (m[3] !== undefined) out.push(el('span', { class: 'md-img', title: m[4] }, [`[image: ${m[3] || m[4]}]`]));
    else if (m[5] !== undefined) {
      const href = m[6];
      if (/^https?:\/\//i.test(href)) out.push(el('a', { href, target: '_blank', rel: 'noreferrer' }, inline(m[5])));
      else out.push(el('span', { class: 'md-link', title: href }, inline(m[5])));
    } else if (m[7] !== undefined || m[8] !== undefined) out.push(el('strong', {}, inline(m[7] ?? m[8])));
    else out.push(el('em', {}, inline(m[9] ?? m[10])));
    last = m.index + m[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

const LIST_ITEM = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/;

function list(lines, start) {
  const first = lines[start].match(LIST_ITEM);
  const indent = first[1].length;
  const ordered = /\d/.test(first[2]);
  const node = el(ordered ? 'ol' : 'ul', ordered && parseInt(first[2], 10) !== 1 ? { start: parseInt(first[2], 10) } : {});
  let i = start;
  while (i < lines.length) {
    const m = lines[i].match(LIST_ITEM);
    if (!m || m[1].length < indent) break;
    if (m[1].length > indent) {
      const [child, next] = list(lines, i);
      node.lastChild?.append(child);
      i = next;
      continue;
    }
    let text = m[3];
    i++;
    while (i < lines.length && lines[i].trim() && !LIST_ITEM.test(lines[i]) && /^\s+/.test(lines[i])) text += ` ${lines[i++].trim()}`;
    node.append(el('li', {}, inline(text)));
  }
  return [node, i];
}

function table(rows) {
  const cells = (row) => row.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
  const head = cells(rows[0]);
  const t = el('table', {}, [el('thead', {}, [el('tr', {}, head.map((c) => el('th', {}, inline(c))))])]);
  const body = el('tbody');
  for (const row of rows.slice(2)) body.append(el('tr', {}, cells(row).map((c) => el('td', {}, inline(c)))));
  t.append(body);
  return t;
}

// Renders Markdown into a DocumentFragment; headings get ids from their text.
export function render(source) {
  const lines = source.replace(/\r\n?/g, '\n').split('\n');
  const frag = document.createDocumentFragment();
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) { i++; continue; }
    let m;
    if ((m = line.match(/^(`{3,}|~{3,})(.*)$/))) {
      const fence = m[1];
      const code = [];
      i++;
      while (i < lines.length && !lines[i].startsWith(fence)) code.push(lines[i++]);
      i++;
      frag.append(el('pre', {}, [el('code', {}, [code.join('\n')])]));
    } else if ((m = line.match(/^(#{1,6})\s+(.*?)\s*#*\s*$/))) {
      frag.append(el(`h${m[1].length}`, { id: slug(m[2]) }, inline(m[2])));
      i++;
    } else if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) {
      frag.append(el('hr'));
      i++;
    } else if (line.startsWith('>')) {
      const quoted = [];
      while (i < lines.length && lines[i].startsWith('>')) quoted.push(lines[i++].replace(/^>\s?/, ''));
      const q = el('blockquote');
      q.append(render(quoted.join('\n')));
      frag.append(q);
    } else if (LIST_ITEM.test(line)) {
      const [node, next] = list(lines, i);
      frag.append(node);
      i = next;
    } else if (line.includes('|') && i + 1 < lines.length && /^\s*\|?\s*:?-{3,}/.test(lines[i + 1])) {
      const rows = [];
      while (i < lines.length && lines[i].includes('|')) rows.push(lines[i++]);
      frag.append(table(rows));
    } else {
      const para = [];
      while (i < lines.length && lines[i].trim() && !/^(#{1,6}\s|>|`{3}|~{3})/.test(lines[i]) && !LIST_ITEM.test(lines[i])) para.push(lines[i++].trim());
      frag.append(el('p', {}, inline(para.join(' '))));
    }
  }
  return frag;
}

// [{level, text, line}] for the outline.
export function headings(source) {
  const out = [];
  let fenced = false;
  source.split('\n').forEach((line, index) => {
    if (/^(`{3,}|~{3,})/.test(line)) fenced = !fenced;
    const m = !fenced && line.match(/^(#{1,6})\s+(.*?)\s*#*\s*$/);
    if (m) out.push({ level: m[1].length, text: m[2], line: index + 1 });
  });
  return out;
}

// Line diff (LCS) for the live "changes against main" view: [{op: ' ' | '-' | '+', text}].
export function lineDiff(before, after) {
  const a = before.split('\n'), b = after.split('\n');
  if (a.length * b.length > 4e6) return [{ op: '~', text: 'too large to diff live; see the Batch tab after saving' }];
  const n = a.length, mlen = b.length;
  const dp = Array.from({ length: n + 1 }, () => new Uint32Array(mlen + 1));
  for (let i = n - 1; i >= 0; i--) for (let j = mlen - 1; j >= 0; j--) dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const out = [];
  let i = 0, j = 0;
  while (i < n && j < mlen) {
    if (a[i] === b[j]) { out.push({ op: ' ', text: a[i] }); i++; j++; }
    else if (dp[i + 1][j] >= dp[i][j + 1]) out.push({ op: '-', text: a[i++] });
    else out.push({ op: '+', text: b[j++] });
  }
  while (i < n) out.push({ op: '-', text: a[i++] });
  while (j < mlen) out.push({ op: '+', text: b[j++] });
  return out;
}
