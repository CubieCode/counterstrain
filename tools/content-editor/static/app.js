// Content editor front end (P1.5). Plain DOM, no build step. Every request carries the
// token from <meta name="editor-token">. All text goes in through text nodes.
import { modelPreview, mobPreview, slugId } from '/static/preview.js?t=__TOKEN__';
import { render as renderMarkdown, headings, lineDiff, slug } from '/static/markdown.js?t=__TOKEN__';

const TOKEN = document.querySelector('meta[name="editor-token"]').content;
const app = document.getElementById('app');
let STATE = null;
let ROWS = [];

// --- helpers -------------------------------------------------------------------

function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'style') el.style.cssText = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'value') el.value = v;
    else if (k === 'checked' || k === 'disabled' || k === 'selected') el[k] = !!v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  append(el, kids);
  return el;
}
function append(el, kids) {
  for (const kid of kids.flat(Infinity)) {
    if (kid === null || kid === undefined || kid === false) continue;
    el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return el;
}
const svgNS = 'http://www.w3.org/2000/svg';
function icon(paths, size = 14) {
  const svg = document.createElementNS(svgNS, 'svg');
  svg.setAttribute('width', size); svg.setAttribute('height', size); svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('fill', 'none'); svg.setAttribute('stroke', 'currentColor'); svg.setAttribute('stroke-width', '1.6');
  svg.setAttribute('aria-hidden', 'true');
  for (const d of paths) { const p = document.createElementNS(svgNS, 'path'); p.setAttribute('d', d); svg.append(p); }
  return svg;
}
const LOCK = ['M4 7.5h8v7H4z', 'M5.75 7.5V5a2.25 2.25 0 0 1 4.5 0v2.5'];
const BRANCH = ['M4 2v12', 'M12 5.5c0 3-3 3-8 6', 'M12 4a1.5 1.5 0 1 0 0 .01'];
const CHECK = ['m3 8.5 3.25 3L13 4.5'];

function url(path, params = {}) {
  const q = new URLSearchParams({ ...params, t: TOKEN });
  return `${path}?${q}`;
}
async function api(path, options = {}) {
  const init = { method: options.method || 'GET', headers: { 'X-Editor-Token': TOKEN } };
  if (options.body !== undefined) {
    init.method = options.method || 'POST';
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(options.body);
  }
  const res = await fetch(path, init);
  const data = await res.json().catch(() => ({ error: `${res.status} ${res.statusText}` }));
  if (!res.ok) throw new Error(data.error || `${res.status}`);
  return data;
}
function toast(message, error = false) {
  const el = h('div', { class: error ? 'error' : '' }, message);
  document.getElementById('toast').append(el);
  setTimeout(() => el.remove(), error ? 9000 : 4000);
}
// Runs an action; the button that started it stays disabled until the page re-renders,
// or comes back on failure, so a double click cannot send the request twice.
async function guard(fn) {
  const ev = window.event;
  const btn = ev && ev.target && ev.target.closest ? ev.target.closest('button') : null;
  if (btn) {
    if (btn.dataset.busy) return undefined;
    btn.dataset.busy = '1';
    btn.disabled = true;
  }
  try {
    return await fn();
  } catch (e) {
    if (btn) { delete btn.dataset.busy; btn.disabled = false; }
    toast(e.message, true);
    throw e;
  }
}
function store(key, fallback) {
  try { const v = localStorage.getItem(`ce.${key}`); return v ? JSON.parse(v) : fallback; } catch { return fallback; }
}
function keep(key, value) { try { localStorage.setItem(`ce.${key}`, JSON.stringify(value)); } catch { /* private mode */ } }
function chip(status) { return h('span', { class: `chip st-${status}` }, status); }
function splitPath(row, short = false) {
  if (row.key !== null && row.key !== undefined) return [row.file.split('/').pop() + ':', row.key];
  const i = row.path.lastIndexOf('/');
  let dir = row.path.slice(0, i + 1);
  if (short) {
    const parts = dir.split('/').filter(Boolean);
    const gen = dir.startsWith('mod/src/generated/') ? 'gen ' : '';
    dir = parts.length > 2 ? `${gen}…/${parts.slice(-2).join('/')}/` : dir;
  }
  return [dir, row.path.slice(i + 1)];
}
function textureId(path) {
  const m = path.match(/assets\/([^/]+)\/textures\/(.+)\.png$/);
  return m ? `${m[1]}:${m[2]}` : null;
}
function rowHref(id, params) {
  return `#/row/${encodeURIComponent(id)}${params ? `?${new URLSearchParams(params)}` : ''}`;
}
function bytesOf(file) { return file.arrayBuffer(); }
function base64(buffer) {
  const bytes = new Uint8Array(buffer);
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

// Minecraft's format pattern, as in contentedit/lang.py.
const FORMAT = /%(?:(\d+)\$)?([A-Za-z%]|$)/g;
function formatArgs(text) {
  const args = new Map();
  let next = 1;
  for (const m of text.matchAll(FORMAT)) {
    if (m[2] === '%') continue;
    const index = m[1] ? Number(m[1]) : next++;
    args.set(index, m[2] || '(missing)');
  }
  return args;
}
function sameArgs(a, b) {
  return a.size === b.size && [...a].every(([k, v]) => b.get(k) === v);
}
function tokens(text) {
  const out = [];
  let last = 0, next = 1;
  for (const m of text.matchAll(FORMAT)) {
    if (m.index > last) out.push(text.slice(last, m.index));
    if (m[2] === '%') out.push('%%');
    else {
      const n = m[1] ? Number(m[1]) : next++;
      out.push(h('span', { class: 'arg', title: `argument ${n}` }, m[0], h('sub', {}, n)));
    }
    last = m.index + m[0].length;
  }
  out.push(text.slice(last));
  return out;
}
const SAMPLES = ['120', '64', '-38', '2', '600', 'Zombie', '3', '9'];
function fill(text) {
  let next = 0;
  return text.replace(FORMAT, (all, pos, conv) => (conv === '%' ? '%' : SAMPLES[(pos ? Number(pos) : ++next) - 1] ?? '?'));
}

// --- chrome ----------------------------------------------------------------------

async function refresh() {
  [STATE, ROWS] = await Promise.all([api('/api/state'), api('/api/rows').then((r) => r.rows)]);
  chrome();
}

function chrome() {
  const pending = ROWS.filter((r) => r.pending).length;
  const here = location.hash.startsWith('#/batch') ? 'batch' : 'ledger';
  const nav = document.getElementById('nav');
  nav.replaceChildren(
    h('a', { href: '#/', 'aria-current': here === 'ledger' ? 'page' : null }, 'Ledger'),
    h('a', { href: '#/batch', 'aria-current': here === 'batch' ? 'page' : null }, 'Batch', pending ? h('span', { class: 'count' }, pending) : null),
  );
  const chipEl = document.getElementById('batch-chip');
  if (STATE.batch) {
    chipEl.replaceChildren(h('span', { class: 'chip-box', title: STATE.worktree }, icon(BRANCH, 12), ' ', STATE.branch));
  } else {
    chipEl.replaceChildren(h('span', { class: 'muted' }, 'read-only'), h('button', { class: 'btn sm primary', type: 'button', onclick: startDialog }, 'Start batch'));
  }
}

function startDialog() {
  const slug = h('input', { type: 'text', id: 'slug', placeholder: 'festered-stone', pattern: '[a-z0-9][a-z0-9-]{0,39}', required: true, style: 'width:100%' });
  const dialog = h('dialog', {},
    h('form', { method: 'dialog', class: 'stack', onsubmit: async (e) => {
      e.preventDefault();
      const submit = dialog.querySelector('button[type="submit"]');
      if (submit.disabled) return;
      submit.disabled = true; submit.textContent = 'Starting…';
      await guard(async () => {
        const r = await api('/api/batch/start', { body: { slug: slug.value.trim() } });
        dialog.close(); dialog.remove();
        toast(`Started ${r.branch}`);
        await refresh(); route();
      }).catch(() => { submit.disabled = false; submit.textContent = 'Start batch'; });
    } },
      h('h2', {}, 'Start a content batch'),
      h('p', { class: 'dim', style: 'margin:0' }, 'Edits go to a new worktree on content/', STATE.today, '-<name>, made from origin/main. This checkout and main are never written.'),
      h('label', { for: 'slug', class: 'small dim' }, 'Batch name (lower case, digits, hyphens)'), slug,
      STATE.batches.length ? h('div', { class: 'stack' }, h('span', { class: 'eyebrow' }, 'Or resume'),
        STATE.batches.map((b) => h('button', { type: 'button', class: 'btn sm', onclick: async () => {
          await guard(() => api('/api/batch/resume', { body: { path: b.path } }));
          dialog.close(); dialog.remove(); await refresh(); route();
        } }, b.branch))) : null,
      h('div', { class: 'row-flex' }, h('span', { class: 'grow' }),
        h('button', { type: 'button', class: 'btn', onclick: () => { dialog.close(); dialog.remove(); } }, 'Cancel'),
        h('button', { type: 'submit', class: 'btn primary' }, 'Start batch'))));
  document.body.append(dialog);
  dialog.showModal();
  slug.focus();
}

function batchGate() {
  if (STATE.batch) return null;
  return h('div', { class: 'callout neutral' }, icon(LOCK, 16),
    h('div', { class: 'grow' }, h('strong', {}, 'Read-only. '), 'Start a batch to edit: changes go to a content/<date>-<name> worktree, never this checkout.'),
    h('button', { class: 'btn sm primary', type: 'button', onclick: startDialog }, 'Start batch'));
}

// --- router ------------------------------------------------------------------------

let cleanup = [];
async function route() {
  cleanup.forEach((fn) => fn()); cleanup = [];
  const [path, qs] = (location.hash.slice(1) || '/').split('?');
  const params = new URLSearchParams(qs || '');
  chrome();
  try {
    if (path.startsWith('/row/')) await rowView(decodeURIComponent(path.slice(5)), params);
    else if (path === '/batch') await batchView();
    else queueView();
  } catch (e) {
    app.replaceChildren(h('div', { class: 'content' }, h('div', { class: 'callout error' }, h('strong', {}, 'Could not load this page. '), e.message)));
  }
  window.scrollTo(0, 0);
}
window.addEventListener('hashchange', route);

// --- queue ----------------------------------------------------------------------------

const TYPE_ORDER = ['texture', 'model', 'gui', 'icon', 'particle', 'sound', 'lang', 'quest', 'lore', 'journal', 'book', 'metadata', 'store-copy', 'store-image'];
const STATUS = ['placeholder', 'draft', 'final', 'removed'];
function originKind(origin) { const m = origin.match(/^(ai|procedural|human|licensed)/); return m ? m[1] : 'other'; }

function queueView() {
  const f = store('filters', { status: ['placeholder', 'draft'], type: [], item: [], origin: [], q: '' });
  const collapsed = new Set(store('collapsed', ['lore', 'metadata']));
  const count = (pred) => ROWS.filter(pred).length;
  const types = [...new Set(ROWS.map((r) => r.type))].sort((a, b) => (TYPE_ORDER.indexOf(a) + 99) % 99 - (TYPE_ORDER.indexOf(b) + 99) % 99);
  const items = [...new Set(ROWS.flatMap((r) => r.items))].sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
  const origins = ['ai', 'procedural', 'human', 'licensed'];

  const matches = (r) => (!f.status.length || f.status.includes(r.status))
    && (!f.type.length || f.type.includes(r.type))
    && (!f.item.length || r.items.some((i) => f.item.includes(i)) || (f.item.includes('none') && !r.items.length))
    && (!f.origin.length || f.origin.includes(originKind(r.origin)))
    && (!f.q || r.path.toLowerCase().includes(f.q.toLowerCase()));

  const facet = (name, key, values, label = (v) => v, dot = () => null) => h('fieldset', {},
    h('legend', { class: 'eyebrow' }, name),
    values.map((v) => {
      const n = count((r) => (key === 'item' ? (v === 'none' ? !r.items.length : r.items.includes(v)) : key === 'origin' ? originKind(r.origin) === v : r[key] === v));
      return h('label', { class: `facet${n ? '' : ' zero'}` },
        h('input', { type: 'checkbox', checked: f[key].includes(v), onchange: (e) => {
          f[key] = e.target.checked ? [...f[key], v] : f[key].filter((x) => x !== v);
          keep('filters', f); queueView();
        } }),
        dot(v) ? h('span', { class: 'dot', style: `background:var(${dot(v)})` }) : null,
        h('span', { class: key === 'item' ? 'mono small' : '' }, label(v)), h('span', { class: 'n' }, n));
    }));
  const statusDot = (s) => ({ placeholder: '--phf', draft: '--drf', final: '--okf', removed: '--f3' })[s];

  const shown = ROWS.filter(matches);
  const total = ROWS.length;
  const by = (s) => count((r) => r.status === s);
  const search = h('input', { type: 'search', placeholder: 'Search path or key', value: f.q, 'aria-label': 'Search path or key', class: 'mono small',
    style: 'flex:1 1 auto;min-width:0;border:0;height:30px;background:transparent', oninput: (e) => { f.q = e.target.value; keep('filters', f); renderTable(); } });

  const tableHost = h('div', { class: 'table-wrap' });
  function renderTable() {
    const visible = ROWS.filter(matches);
    const table = h('div', { class: 'table', role: 'table', 'aria-label': 'Ledger rows' },
      h('div', { class: 'tr th', role: 'row' }, ['', 'Path / ID', 'Item', 'Origin', 'Status', 'Notes (art direction)', ''].map((t) => h('span', { role: 'columnheader' }, t))));
    for (const type of types) {
      const rows = visible.filter((r) => r.type === type);
      if (!rows.length) continue;
      const closed = collapsed.has(type);
      table.append(h('button', { type: 'button', class: 'group', 'aria-expanded': String(!closed), onclick: () => {
        closed ? collapsed.delete(type) : collapsed.add(type); keep('collapsed', [...collapsed]); renderTable();
      } }, h('span', { class: 'tiny muted' }, closed ? '▶' : '▼'), h('b', {}, type), h('span', { class: 'mono tiny muted' }, rows.length),
        type === 'texture' ? h('span', { class: 'small muted' }, '2×2 #FF00FF marker on placeholders') :
        type === 'model' ? h('span', { class: 'small muted' }, 'datagen output is read-only here') :
        type === 'lang' ? h('span', { class: 'small muted' }, 'rows ending in * cover every matching key; an exact-key row overrides them') : null));
      if (closed) continue;
      for (const r of rows) table.append(rowLine(r));
    }
    if (!visible.length) table.append(h('p', { class: 'muted', style: 'padding:16px' }, 'No rows match these filters.'));
    tableHost.replaceChildren(table);
  }

  app.replaceChildren(h('div', { class: 'split' },
    h('aside', { class: 'rail', 'aria-label': 'Filters' },
      facet('Status', 'status', STATUS, undefined, statusDot),
      facet('Type', 'type', types),
      facet('Roadmap item', 'item', [...items, 'none']),
      facet('Origin', 'origin', origins),
      h('button', { type: 'button', class: 'btn sm ghost', onclick: () => { keep('filters', { status: [], type: [], item: [], origin: [], q: '' }); queueView(); } }, 'Clear filters')),
    h('section', { class: 'content', 'aria-label': 'Ledger' },
      h('div', { class: 'row-flex', style: 'align-items:flex-end' },
        h('div', { class: 'stack', style: 'gap:2px' }, h('h1', {}, 'Provenance ledger'),
          h('span', { class: 'mono small muted' }, `docs/CONTENT-PROVENANCE.md · ${total} rows · ${STATE.batch ? STATE.branch : `read-only at ${STATE.base}`}`)),
        h('span', { class: 'grow' }),
        h('div', { class: 'stack', style: 'gap:6px' },
          h('div', { class: 'statbar', 'aria-hidden': 'true' },
            h('span', { style: `width:${by('placeholder') / total * 100}%;background:var(--phf)` }),
            h('span', { style: `width:${by('draft') / total * 100}%;background:var(--drf)` }),
            h('span', { style: `width:${by('final') / total * 100}%;background:var(--okf)` })),
          h('div', { class: 'row-flex small dim', style: 'gap:16px' },
            h('span', {}, h('b', { style: 'color:var(--phf)' }, by('placeholder')), ' placeholder'),
            h('span', {}, h('b', { style: 'color:var(--drf)' }, by('draft')), ' draft'),
            h('span', {}, h('b', { style: 'color:var(--okf)' }, by('final')), ' final'),
            h('span', { class: 'muted' }, `${ROWS.filter((r) => r.pending).length} changed in this batch`)))),
      h('div', { class: 'row-flex' },
        h('label', { style: 'flex:1 1 320px;max-width:460px;display:flex;align-items:center;gap:8px;padding:0 10px;border:1px solid var(--bs);border-radius:6px;background:var(--sf)' },
          icon(['M7 2.5a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9z', 'M10.5 10.5 14 14']), search, h('kbd', {}, '/')),
        h('span', { class: 'small muted' }, `${shown.length} shown`),
        h('span', { class: 'grow' }),
        h('button', { type: 'button', class: 'btn sm', onclick: addRowDialog, disabled: !STATE.batch, title: STATE.batch ? null : 'Start a batch first' }, '+ Add row')),
      tableHost,
      h('footer', { class: 'hint' }, h('span', {}, 'Writes only to paths the ledger names'), h('span', {}, 'Generated paths are read-only'),
        h('span', { class: 'grow' }), h('span', { class: 'mono' }, '/ search · j k move · enter open')))));
  renderTable();

  let focus = -1;
  const onKey = (e) => {
    if (e.target.matches('input, textarea, select')) { if (e.key === 'Escape') e.target.blur(); return; }
    const links = [...tableHost.querySelectorAll('a.tr')];
    if (e.key === '/') { e.preventDefault(); search.focus(); }
    else if (e.key === 'j' || e.key === 'k') {
      focus = Math.max(0, Math.min(links.length - 1, focus + (e.key === 'j' ? 1 : -1)));
      links.forEach((l, i) => l.classList.toggle('focus', i === focus));
      links[focus]?.scrollIntoView({ block: 'nearest' });
    } else if (e.key === 'Enter' && links[focus]) location.hash = links[focus].getAttribute('href');
  };
  document.addEventListener('keydown', onKey);
  cleanup.push(() => document.removeEventListener('keydown', onKey));
}

function rowLine(r) {
  const [dir, leaf] = splitPath(r, true);
  let thumb;
  if (r.kind === 'texture' || (r.path.endsWith('.png') && !r.key)) {
    thumb = h('span', { class: 'thumb' }, h('img', { class: 'pixel', src: url('/api/file', { path: r.path }), alt: '', width: 32, height: 32, loading: 'lazy' }));
  } else {
    thumb = h('span', { class: 'thumb none' }, r.generated ? icon(LOCK) : r.kind === 'lang' ? 'Aa' : r.kind === 'markdown' ? 'md' : r.kind === 'properties' ? 'key' : r.kind === 'model' ? '{ }' : r.kind === 'sound' ? 'ogg' : '·');
  }
  let mark = null;
  if (r.generated) mark = h('span', { class: 'mark lock' }, 'read-only');
  else if (r.new) mark = h('span', { class: 'mark' }, '● new row');
  else if (r.pending) mark = h('span', { class: 'mark' }, '● edited');
  else if (r.wildcard && r.overridden) mark = h('span', { class: 'mark lock', title: 'keys with their own exact-key row' }, `${r.overridden}/${r.keyCount ?? '?'} split out`);
  else if (r.wildcard && r.keyCount !== null) mark = h('span', { class: 'mark lock' }, `${r.keyCount} keys`);
  return h('a', { class: 'tr', role: 'row', href: rowHref(r.id) },
    thumb,
    h('span', { class: 'ellipsis mono small', title: r.path }, r.key && !r.wildcard && ROWS.some((o) => o.wildcard && o.file === r.file && r.key.startsWith(o.key.slice(0, -1))) ? '↳ ' : '',
      h('span', { class: 'muted' }, dir), leaf),
    h('span', { class: 'mono small dim' }, r.items[0] || ''),
    h('span', { class: 'ellipsis small dim', title: r.origin }, r.origin),
    h('span', {}, chip(r.status)),
    h('span', { class: 'ellipsis small dim', title: r.notes }, r.notes),
    mark || h('span'));
}

function addRowDialog() {
  const sections = [...new Set(ROWS.map((r) => r.section))];
  const path = h('input', { type: 'text', id: 'np', class: 'mono small', style: 'width:100%', placeholder: 'mod/src/main/resources/assets/remission/textures/block/new.png' });
  const type = h('select', { id: 'nt' }, ['texture', 'model', 'sound', 'gui', 'icon', 'particle'].map((t) => h('option', {}, t)));
  const section = h('select', { id: 'ns' }, sections.map((s) => h('option', {}, s)));
  const notes = h('textarea', { id: 'nn', rows: 2, placeholder: 'What the art pass should aim for' });
  const dialog = h('dialog', {}, h('form', { method: 'dialog', class: 'stack', onsubmit: async (e) => {
    e.preventDefault();
    await guard(async () => {
      const r = await api('/api/rows/add', { body: { path: path.value.trim(), type: type.value, section: section.value, notes: notes.value } });
      dialog.close(); dialog.remove(); await refresh(); location.hash = rowHref(r.row.id);
    });
  } },
  h('h2', {}, 'Add a ledger row'),
  h('p', { class: 'small dim', style: 'margin:0' }, 'For a new asset under the mod\'s assets/ or pack/. The row starts as draft with you as origin; upload the file from its page.'),
  h('label', { for: 'np', class: 'small dim' }, 'Repository path'), path,
  h('div', { class: 'row-flex' }, h('label', { for: 'nt', class: 'small dim' }, 'Type'), type, h('label', { for: 'ns', class: 'small dim' }, 'Section'), section),
  h('label', { for: 'nn', class: 'small dim' }, 'Notes'), notes,
  h('div', { class: 'row-flex' }, h('span', { class: 'grow' }),
    h('button', { type: 'button', class: 'btn', onclick: () => { dialog.close(); dialog.remove(); } }, 'Cancel'),
    h('button', { type: 'submit', class: 'btn primary' }, 'Add row'))));
  document.body.append(dialog);
  dialog.showModal();
}

// --- row pages ------------------------------------------------------------------------

function rowHeader(detail, extra) {
  const r = detail.row;
  const [dir, leaf] = r.key ? [r.file + ':', r.key] : splitPath(r);
  return h('div', { class: 'row-flex', style: 'align-items:flex-start;padding:16px 24px 0;gap:10px 24px' },
    h('div', { class: 'stack', style: 'flex:1 1 560px;min-width:0;gap:6px' },
      h('a', { href: '#/', class: 'small', style: 'text-decoration:none' }, `← Ledger · ${r.type}`),
      h('h1', { class: 'path' }, h('span', { class: 'muted' }, dir), leaf),
      h('div', { class: 'row-flex small dim', style: 'gap:6px 14px' }, chip(r.status), h('span', {}, r.origin),
        r.items.map((i) => h('span', { class: 'mono' }, i)),
        r.generated ? h('span', { class: 'chip outline plain' }, icon(LOCK, 11), ' read-only') : null,
        h('span', { class: 'mono muted' }, `ledger line ${r.line}`), extra)),
    h('section', { class: 'guidance', 'aria-label': 'Guidance', style: 'flex:1 1 420px;max-width:600px' },
      h('div', { class: 'eyebrow' }, 'Guidance · ledger row notes'), h('p', {}, r.notes || 'No notes.')));
}

async function rowView(id, params) {
  const detail = await api(url('/api/row', { id }));
  if (detail.kind === 'lang') return langView(detail, params);
  if (detail.kind === 'markdown') return markdownView(detail);
  if (detail.kind === 'properties') return propertiesView(detail);
  if (detail.kind === 'texture') return textureView(detail);
  if (detail.kind === 'model') return modelView(detail);
  if (detail.kind === 'sound') return soundView(detail);
  app.replaceChildren(rowHeader(detail), h('div', { class: 'content' },
    h('div', { class: 'callout neutral' }, h('p', {}, 'The editor has no page for this type yet; edit the file by hand and update its row. Quest text (P11.1) and field journal entries (P10.7) get pages when their files exist.'))));
}

// --- lang -------------------------------------------------------------------------------

async function langView(detail, params) {
  const r = detail.row;
  const siblings = await Promise.all(detail.siblings.map((sid) => (sid === r.id ? detail : api(url('/api/row', { id: sid })))));
  const keyName = params.get('key') || detail.keys[0]?.key;
  const k = detail.keys.find((x) => x.key === keyName) || detail.keys[0];
  const keyCount = siblings.reduce((n, s) => n + (s.keys?.length || 0), 0);

  const list = h('nav', { class: 'keylist', 'aria-label': 'Keys' });
  for (const s of siblings) {
    if (!s.keys) continue;
    list.append(h('div', { class: 'rowname' }, h('a', { href: rowHref(s.row.id) }, s.row.key)));
    for (const sk of s.keys) {
      const mine = sk.owner !== s.row.id;
      if (mine && s.row.wildcard) {
        // Keys with their own exact row are listed under that row.
        if (siblings.some((o) => o.row.id === sk.owner)) continue;
      }
      let mark = null;
      if (sk.ownerStatus === 'final') mark = h('span', { class: 'tiny', style: 'color:var(--okf)' }, '✓ final');
      else if (sk.base !== null && sk.base !== sk.value) mark = h('span', { class: 'tiny', style: 'color:var(--ac)' }, '● edited');
      else if (sk.owner && sk.owner !== s.row.id) mark = h('span', { class: 'tiny', style: 'color:var(--ac)' }, '● own row');
      list.append(h('a', { href: rowHref(s.row.id, { key: sk.key }), 'aria-current': s.row.id === r.id && sk.key === k?.key ? 'true' : null },
        h('span', { class: 'ellipsis grow', title: sk.key }, s.row.wildcard && sk.key.startsWith(s.row.key.slice(0, -1))
          ? [h('span', { class: 'muted' }, '…'), sk.key.slice(s.row.key.length - 1)] : sk.key), mark));
    }
  }

  if (!k) {
    app.replaceChildren(rowHeader(detail), h('div', { class: 'content' }, h('p', { class: 'muted' }, 'No key in the file matches this row.')));
    return;
  }

  const currentArgs = formatArgs(k.value);
  const langVocab = vocabPanel(k.owner || r.id, () => edit.value);
  const ownerIsRow = k.owner === r.id;
  const sharedOthers = r.wildcard && ownerIsRow ? detail.keys.filter((x) => x.owner === r.id && x.key !== k.key).length : 0;
  const status = h('select', { id: 'st', 'aria-label': 'Status after replacing' }, h('option', { value: 'draft' }, 'draft'), h('option', { value: 'final' }, 'final'));
  const edit = h('textarea', { id: 'edit', rows: 3, style: 'font-size:14px;line-height:1.6' });
  edit.value = k.value;
  const argNote = h('span', { class: 'small dim' });
  const problem = h('div', { class: 'callout error hidden', role: 'alert' });
  const preview = h('div', { class: 'preview-line' });
  const replace = h('button', { type: 'button', class: 'btn lg primary' }, 'Replace');
  const onSave = h('div', { class: 'diffbox' });

  function update() {
    const value = edit.value;
    const args = formatArgs(value);
    const ok = sameArgs(currentArgs, args);
    argNote.textContent = `${args.size} argument${args.size === 1 ? '' : 's'}`;
    argNote.style.color = ok ? '' : 'var(--erf)';
    edit.classList.toggle('bad', !ok);
    problem.classList.toggle('hidden', ok);
    if (!ok) {
      problem.replaceChildren(icon(['M8 1.75 15 14.25H1L8 1.75Z', 'M8 6.5v3.5M8 12v.25'], 16), h('div', { class: 'stack', style: 'gap:4px' },
        h('strong', {}, `Arguments changed: ${currentArgs.size} %s in the current string, ${args.size} in the replacement`),
        h('span', {}, 'Minecraft fills %s in order, so values would print in the wrong place. Replace is blocked until they match. %1$s-style positions can reorder them.')));
    }
    const filled = fill(value);
    preview.replaceChildren(h('div', { class: 'eyebrow' }, `Preview with sample values ${SAMPLES.slice(0, Math.max(currentArgs.size, 1)).join(', ')}`), filled);
    replace.disabled = !STATE.batch || !ok || value === k.value || !value.trim();
    const human = `human (${STATE.reviewer}, ${STATE.today})`;
    if (sharedOthers > 0) {
      onSave.replaceChildren(
        h('div', { class: 'muted' }, `${r.file} · this key's line, key order and indent kept`),
        h('div', { class: 'muted' }, 'docs/CONTENT-PROVENANCE.md · new exact-key row below the * row it overrides'),
        h('div', {}, '  ', h('span', { class: 'dim' }, `| ${r.file.split('/').pop()}:${r.key} | … unchanged · ${sharedOthers} other key${sharedOthers === 1 ? '' : 's'} still governed by it`)),
        h('div', {}, h('span', { class: 'add' }, '+ '), `| ${r.file.split('/').pop()}:${k.key} | lang | `, h('b', { style: 'color:var(--ac);font-weight:500' }, human), ` | ${status.value} | notes copied from the * row |`),
        h('div', { class: 'muted' }, 'The most specific row wins: an exact key overrides a matching * row.'));
    } else {
      const owner = k.owner || r.id;
      onSave.replaceChildren(
        h('div', { class: 'muted' }, `${r.file} · this key's line, key order and indent kept`),
        h('div', { class: 'muted' }, `docs/CONTENT-PROVENANCE.md · row ${owner.split('/').pop()}`),
        h('div', {}, h('span', { class: 'del' }, '− '), 'origin ', h('span', { class: 'dim' }, k.ownerOrigin), ' · status ', k.ownerStatus),
        h('div', {}, h('span', { class: 'add' }, '+ '), 'origin ', h('b', { style: 'color:var(--ac);font-weight:500' }, human), ` · status ${status.value}`));
    }
  }
  edit.addEventListener('input', () => { update(); langVocab.update(); });
  status.addEventListener('change', update);
  replace.addEventListener('click', () => guard(async () => {
    await api('/api/lang/save', { body: { file: r.file, key: k.key, value: edit.value, status: status.value } });
    toast(`Replaced ${k.key}`);
    await refresh();
    const owner = ROWS.find((x) => x.id === `${r.file}:${k.key}`);
    location.hash = rowHref(owner ? owner.id : r.id, { key: k.key });
    route();
  }));
  const isFinal = (k.ownerStatus || r.status) === 'final';
  const accept = h('button', { type: 'button', class: 'btn lg ok', disabled: !STATE.batch || isFinal, onclick: () => guard(async () => {
    await api('/api/lang/accept', { body: { file: r.file, key: k.key } });
    toast(`Accepted ${k.key} as final`);
    await refresh(); route();
  }) }, icon(CHECK), isFinal ? 'Already final' : 'Accept as final');
  const acceptAll = r.wildcard ? h('button', { type: 'button', class: 'btn', disabled: !STATE.batch || r.status === 'final', onclick: () => guard(async () => {
    await api('/api/lang/accept', { body: { row: r.id } });
    toast(`Accepted every key of ${r.key} as final`);
    await refresh(); route();
  }) }, `Accept all ${detail.keys.filter((x) => x.owner === r.id).length} keys of this row`) : null;
  const ownerChip = k.owner && k.owner !== r.id ? h('a', { href: rowHref(k.owner, { key: k.key }), class: 'small' }, 'governed by its own row') : null;

  app.replaceChildren(h('div', { class: 'split' },
    h('aside', { class: 'rail', style: 'padding:0;gap:0;flex-basis:300px;max-width:340px', 'aria-label': 'Rows and keys' },
      h('div', { class: 'stack', style: 'padding:14px 16px 10px;gap:4px;border-bottom:1px solid var(--bd)' },
        h('a', { href: '#/', class: 'small', style: 'text-decoration:none' }, '← Ledger'),
        h('div', { class: 'row-flex', style: 'gap:8px' }, h('b', {}, `Lang${r.items.length ? ` · ${r.items.join(', ')}` : ''}`),
          h('span', { class: 'small muted' }, `${siblings.length} row${siblings.length === 1 ? '' : 's'} · ${keyCount} keys`))), list),
    h('section', { class: 'content', style: 'max-width:1000px', 'aria-label': 'String editor' },
      batchGate(),
      h('div', { class: 'stack', style: 'gap:6px' },
        h('h1', { class: 'path' }, k.key),
        h('div', { class: 'row-flex small dim', style: 'gap:6px 14px' }, chip(k.ownerStatus || r.status), h('span', {}, k.ownerOrigin || r.origin),
          r.items.map((i) => h('span', { class: 'mono' }, i)), h('span', { class: 'mono muted' }, `${r.file.split('/').pop()}:${k.line}`), ownerChip)),
      h('section', { class: 'guidance' }, h('div', { class: 'eyebrow' }, 'Guidance · ledger row notes'), h('p', {}, r.notes),
        r.wildcard ? h('p', { class: 'small dim', style: 'margin-top:6px' }, `Row ${r.key} covers ${detail.keys.length} keys; replacing or accepting one key while others share the row adds an exact-key row for it.`) : null),
      h('div', { class: 'stack' }, h('div', { class: 'row-flex' }, h('h2', { class: 'label' }, 'Current string'), h('span', { class: 'small muted' }, `${currentArgs.size} arguments`)),
        h('div', { class: 'string' }, tokens(k.value)),
        k.base !== null && k.base !== k.value ? h('span', { class: 'small dim' }, 'On main: ', h('span', { class: 'mono' }, k.base)) : null),
      h('div', { class: 'stack' }, h('div', { class: 'row-flex' }, h('label', { for: 'edit', class: 'small', style: 'font-weight:600;color:var(--f2)' }, 'Replacement'), argNote,
        h('span', { class: 'grow' }), h('label', { for: 'st', class: 'small dim' }, 'status'), status),
        edit, problem, preview, langVocab.el),
      h('div', { class: 'stack', style: 'gap:6px' }, h('h2', { class: 'label' }, 'On save'), onSave),
      h('div', { class: 'row-flex', style: 'padding-top:14px;border-top:1px solid var(--bd)' }, replace, accept,
        h('span', { class: 'small dim' }, `Accepting keeps the string; status → final, notes gain “Accepted as final by ${STATE.reviewer}, ${STATE.today}.”`),
        h('span', { class: 'grow' }), acceptAll))));
  update();
}

// --- vocabulary (glossary hits and the optional local review) ------------------------------

function vocabPanel(rowId, getText) {
  const hits = h('div', { class: 'stack', style: 'gap:4px' });
  const issues = h('div', { class: 'stack', style: 'gap:4px' });
  let timer = null;
  async function refreshHits() {
    const text = getText();
    const res = await api('/api/vocabulary', { body: { text } }).catch(() => null);
    if (!res) return;
    hits.replaceChildren(
      res.hits.length ? h('div', { class: 'callout error small', style: 'flex-direction:column;gap:4px' },
        h('strong', {}, `Glossary: ${res.hits.length} word${res.hits.length === 1 ? '' : 's'} to check`),
        res.hits.map((x) => h('span', {}, h('code', {}, x.term), x.line > 1 ? ` (line ${x.line})` : '', ` → ${x.suggestion}. `, h('span', { class: 'dim' }, x.reason)))) : null,
      res.used.length ? h('div', { class: 'row-flex small dim', style: 'gap:6px' }, 'Glossary terms used:', res.used.map((t) => h('span', { class: 'chip outline plain' }, t))) : null);
  }
  const ask = STATE.llm ? h('button', { type: 'button', class: 'btn sm', onclick: () => guard(async () => {
    issues.replaceChildren(h('span', { class: 'small muted' }, `Asking ${STATE.llm}… (uses foundry's GPU through Ollama)`));
    const res = await api('/api/review', { body: { row: rowId, text: getText() } });
    issues.replaceChildren(h('div', { class: 'callout info small', style: 'flex-direction:column;gap:4px' },
      h('strong', {}, res.issues.length ? `${res.model}: ${res.issues.length} thing${res.issues.length === 1 ? '' : 's'} to look at` : `${res.model}: nothing to flag`),
      res.issues.map((i) => h('span', {}, '“', h('b', {}, i.quote), '” ', i.problem)),
      h('span', { class: 'dim' }, 'Advisory only: the model points at problems and never writes text, so anything you save is recorded as yours.')));
    ask.disabled = false; delete ask.dataset.busy;
  }) }, `Ask ${STATE.llm}`) : null;
  const el = h('section', { class: 'stack', 'aria-label': 'Vocabulary' }, hits,
    ask ? h('div', { class: 'row-flex' }, ask, h('span', { class: 'small muted' }, 'Optional review by a local model against the glossary and this row’s notes.')) : null, issues);
  return { el, update: () => { clearTimeout(timer); timer = setTimeout(refreshHits, 300); } };
}

// --- properties ------------------------------------------------------------------------------

function propertiesView(detail) {
  const r = detail.row;
  const vocab = vocabPanel(r.id, () => edit.value);
  const edit = h('input', { type: 'text', id: 'pv', style: 'width:100%;font-size:14px' });
  edit.value = detail.value;
  const status = h('select', { 'aria-label': 'Status after replacing' }, h('option', { value: 'draft' }, 'draft'), h('option', { value: 'final' }, 'final'));
  const save = h('button', { type: 'button', class: 'btn lg primary', onclick: () => guard(async () => {
    await api('/api/properties/save', { body: { row: r.id, value: edit.value, status: status.value } });
    toast(`Replaced ${r.key}`); await refresh(); route();
  }) }, 'Replace');
  const isFinal = r.status === 'final';
  const accept = h('button', { type: 'button', class: 'btn lg ok', disabled: !STATE.batch || isFinal, onclick: () => guard(async () => {
    await api('/api/accept', { body: { row: r.id } });
    toast(`Accepted ${r.key} as final`); await refresh(); route();
  }) }, icon(CHECK), isFinal ? 'Already final' : 'Accept as final');
  const update = () => { save.disabled = !STATE.batch || !edit.value.trim() || edit.value.trim() === detail.value; vocab.update(); };
  edit.addEventListener('input', update);
  app.replaceChildren(rowHeader(detail), h('div', { class: 'content', style: 'max-width:1000px' }, batchGate(),
    detail.gate ? h('div', { class: 'callout neutral small' }, h('span', {}, h('strong', {}, 'Shared build file. '), `${r.file} is a serialization gate for roadmap lanes: rebase this batch from main and rerun the checks just before merging.`)) : null,
    h('div', { class: 'stack' }, h('h2', { class: 'label' }, `Current value · ${r.file}:${detail.line}`), h('div', { class: 'string' }, detail.value),
      detail.base !== null && detail.base !== detail.value ? h('span', { class: 'small dim' }, 'On main: ', h('span', { class: 'mono' }, detail.base)) : null),
    h('div', { class: 'stack' }, h('label', { for: 'pv', class: 'small', style: 'font-weight:600;color:var(--f2)' }, 'Replacement (one line)'), edit),
    vocab.el,
    h('div', { class: 'row-flex', style: 'padding-top:14px;border-top:1px solid var(--bd)' }, h('label', { class: 'row-flex small dim', style: 'gap:6px' }, 'status', status), save, accept,
      h('span', { class: 'small dim' }, 'Only this line changes; the separator and every other entry stay as they are.'))));
  update();
}

// --- markdown -------------------------------------------------------------------------------

function markdownView(detail) {
  const r = detail.row;
  const original = detail.text;
  let mode = store('mdmode', 'split');
  const source = h('textarea', { id: 'md', class: 'mono', spellcheck: 'true', 'aria-label': `${r.file} source`,
    style: 'min-height:560px;font-size:13px;line-height:1.6;tab-size:2' });
  source.value = original;
  const preview = h('article', { class: 'md-preview card pad', 'aria-label': 'Preview' });
  const outline = h('select', { 'aria-label': 'Jump to heading', onchange: (e) => jump(Number(e.target.value)) });
  const status = h('select', { 'aria-label': 'Status after saving' }, h('option', { value: 'draft' }, 'draft'), h('option', { value: 'final' }, 'final'));
  const save = h('button', { type: 'button', class: 'btn lg primary' }, 'Save');
  const accept = h('button', { type: 'button', class: 'btn lg ok' }, icon(CHECK), 'Accept as final');
  const revert = h('button', { type: 'button', class: 'btn lg', onclick: () => { source.value = original; update(); } }, 'Revert');
  const changes = h('div', { class: 'stack' });
  const warnings = h('div', { class: 'stack' });
  const counter = h('span', { class: 'mono tiny muted' });
  const grid = h('div', { style: 'display:grid;gap:16px;align-items:start' });
  const mdVocab = vocabPanel(r.id, () => source.value);

  function jump(line) {
    const before = source.value.split('\n').slice(0, line - 1).join('\n').length;
    source.focus();
    source.setSelectionRange(before + 1, before + 1);
    source.scrollTop = Math.max(0, (line - 3) * 20.8);
    const head = headings(source.value).find((x) => x.line === line);
    if (head) preview.querySelector(`#${CSS.escape(slug(head.text))}`)?.scrollIntoView({ block: 'start', behavior: 'smooth' });
  }
  function layout() {
    grid.style.gridTemplateColumns = mode === 'split' ? 'repeat(2, minmax(0, 1fr))' : 'minmax(0, 1fr)';
    source.parentElement.classList.toggle('hidden', mode === 'preview');
    preview.classList.toggle('hidden', mode === 'edit');
    seg.querySelectorAll('button').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.mode === mode)));
  }
  function showWarnings(list) {
    warnings.replaceChildren(...(list || []).map((w) => h('div', { class: 'callout error small' }, h('strong', {}, 'Link: '), w)));
  }
  function update() {
    const text = source.value;
    preview.replaceChildren(renderMarkdown(text));
    const heads = headings(text);
    outline.replaceChildren(h('option', { value: '' }, heads.length ? 'Jump to heading…' : 'No headings'),
      ...heads.map((x) => h('option', { value: x.line }, `${'  '.repeat(x.level - 1)}${x.text}`)));
    const dirty = text !== original;
    counter.textContent = `${text.split('\n').length} lines · ${new Blob([text]).size} B${dirty ? ' · unsaved' : ''}`;
    save.disabled = !STATE.batch || !dirty || !text.trim();
    const isFinal = r.status === 'final';
    accept.disabled = !STATE.batch || dirty || isFinal;
    accept.lastChild.textContent = isFinal ? 'Already final' : 'Accept as final';
    revert.disabled = !dirty;
    const base = detail.base ?? '';
    const diff = base === text.replace(/\s+$/, '') + '\n' || base === text ? [] : lineDiff(base.replace(/\n$/, ''), text.replace(/\n$/, ''));
    const changed = diff.filter((d) => d.op !== ' ');
    if (!changed.length) {
      changes.replaceChildren(h('span', { class: 'small muted' }, detail.base === null ? 'New file: not on main.' : 'Same as main.'));
    } else {
      // Show changed lines with one line of context, collapsing the rest.
      const keep = diff.map((d, i) => d.op !== ' ' || diff[i - 1]?.op !== ' ' && diff[i - 1] || diff[i + 1]?.op !== ' ' && diff[i + 1]);
      const rows = [];
      diff.forEach((d, i) => {
        if (keep[i]) rows.push(h('div', { class: d.op === '+' ? 'add' : d.op === '-' ? 'del' : 'muted' }, `${d.op} ${d.text}`));
        else if (keep[i - 1]) rows.push(h('div', { class: 'muted' }, '  …'));
      });
      changes.replaceChildren(h('div', { class: 'row-flex small' }, h('b', {}, 'Changes against main'),
        h('span', { class: 'add' }, `+${changed.filter((d) => d.op === '+').length}`), h('span', { class: 'del' }, `−${changed.filter((d) => d.op === '-').length}`)),
      h('div', { class: 'diffbox', style: 'max-height:280px;overflow:auto' }, rows));
    }
  }
  const seg = h('div', { class: 'seg', role: 'group', 'aria-label': 'View' }, ['edit', 'split', 'preview'].map((m) =>
    h('button', { type: 'button', 'data-mode': m, onclick: () => { mode = m; keep('mdmode', m); layout(); } }, m)));

  save.addEventListener('click', () => guard(async () => {
    const res = await api('/api/markdown/save', { body: { row: r.id, text: source.value, status: status.value } });
    toast(`Saved ${r.file}${res.warnings.length ? ` with ${res.warnings.length} link warning(s)` : ''}`);
    await refresh(); route();
  }));
  accept.addEventListener('click', () => guard(async () => {
    await api('/api/accept', { body: { row: r.id } });
    toast(`Accepted ${r.file} as final`);
    await refresh(); route();
  }));
  source.addEventListener('input', () => { update(); mdVocab.update(); });
  source.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 's') { e.preventDefault(); if (!save.disabled) save.click(); }
    if (e.key === 'Tab' && !e.shiftKey) {
      e.preventDefault();
      const { selectionStart: a, selectionEnd: b } = source;
      source.setRangeText('  ', a, b, 'end');
      update();
    }
  });
  const leave = (e) => { if (source.value !== original) { e.preventDefault(); e.returnValue = ''; } };
  window.addEventListener('beforeunload', leave);
  cleanup.push(() => window.removeEventListener('beforeunload', leave));

  grid.append(h('div', { class: 'stack', style: 'gap:6px' }, source, counter), preview);
  app.replaceChildren(rowHeader(detail),
    h('div', { class: 'content', style: 'padding-top:16px' }, batchGate(),
      h('div', { class: 'row-flex' }, seg, outline, h('span', { class: 'grow' }),
        h('span', { class: 'small muted' }, 'Ctrl+S saves · Tab indents')),
      grid, warnings, mdVocab.el, changes,
      h('div', { class: 'row-flex', style: 'padding-top:14px;border-top:1px solid var(--bd)' },
        h('label', { class: 'row-flex small dim', style: 'gap:6px' }, 'Status after saving', status), save, accept, revert,
        h('span', { class: 'small dim' }, `Saving records origin “human (${STATE.reviewer}, ${STATE.today})”; accepting keeps the text and adds “Accepted as final by ${STATE.reviewer}, ${STATE.today}.”`))));
  showWarnings(detail.warnings);
  layout();
  update();
}

// --- textures ------------------------------------------------------------------------------

async function textureView(detail) {
  const r = detail.row;
  const w = detail.width || 16, ht = detail.height || 16;
  const zoomDefault = Math.max(1, Math.min(8, Math.floor(256 / Math.max(w, ht))));
  const ui = { zoom: store('zoom', zoomDefault), grid: store('grid', true), checker: store('checker', true) };
  const upload = { buffer: null, name: null, staged: null, status: r.status === 'placeholder' ? 'draft' : r.status === 'final' ? 'final' : 'draft' };
  const texId = textureId(r.path);

  const paneHost = h('div', { class: 'panes' });
  const side = h('aside', { class: 'card side', 'aria-label': 'Checks and write' });
  const previewHost = h('div', { class: 'stack' });

  function zoomed(src, alt) {
    const z = Math.min(ui.zoom, Math.max(1, Math.floor(512 / Math.max(w, ht))));
    const box = h('div', { class: `zoomed ${ui.checker ? 'checker' : ''}`, style: `width:${w * z}px;height:${ht * z}px` },
      h('img', { class: 'pixel', src, alt, width: w * z, height: ht * z }));
    if (ui.grid && z >= 4) box.append(h('div', { class: 'grid-overlay', style: `background-size:${z}px ${z}px` }));
    return h('figure', { class: 'stack', style: 'margin:0;gap:6px' }, box,
      h('figcaption', { class: 'mono tiny muted' }, `${z}× · ${w}×${ht}`),
      h('div', { class: 'row-flex mono tiny muted', style: 'gap:8px' },
        h('img', { class: 'pixel', src, alt: '', width: w, height: ht }), '1×', h('img', { class: 'pixel', src, alt: '', width: w * 2, height: ht * 2 }), '2×'));
  }
  function tiles(src) {
    const tile = Math.max(1, Math.floor(300 / 3 / Math.max(w, ht)));
    return h('figure', { class: 'stack', style: 'margin:0;gap:6px' },
      h('div', { class: 'tiles' }, Array.from({ length: 9 }, () => h('img', { class: 'pixel', src, alt: '', width: w * tile, height: ht * tile }))),
      h('figcaption', { class: 'mono tiny muted' }, `3×3 tiled · ${tile}× · check the seams`));
  }

  function renderPanes() {
    const current = url('/api/file', { path: r.path });
    const panes = [h('section', { class: 'card pane', 'aria-label': 'Current' },
      h('div', { class: 'row-flex' }, h('h2', {}, 'Current'),
        h('span', { class: 'mono small muted' }, `${detail.bytes ?? '?'} B · ${w}×${ht}${detail.marker ? ` · marker at 0,0` : ''}${detail.mcmeta ? ' · animated' : ''}`)),
      h('div', { class: 'row-flex', style: 'align-items:flex-start;gap:16px' }, zoomed(current, `Current ${r.path.split('/').pop()}, magnified`), tiles(current)),
      r.pending ? h('span', { class: 'small dim' }, 'Changed in this batch. On main: ', h('img', { class: 'pixel', src: url('/api/file', { path: r.path, rev: 'base' }), alt: 'on main', width: 32, height: 32, style: 'vertical-align:middle' })) : null)];
    if (upload.staged) {
      const src = url('/api/upload', { id: upload.staged.id });
      panes.push(h('section', { class: 'card pane focus', 'aria-label': 'Replacement' },
        h('div', { class: 'row-flex' }, h('h2', { style: 'color:var(--ac)' }, 'Replacement'),
          h('span', { class: 'mono small muted' }, `${upload.name} · ${upload.staged.info.bytes} B`), h('span', { class: 'grow' }),
          h('button', { type: 'button', class: 'btn sm', onclick: () => fileInput.click() }, 'Choose another PNG…')),
        upload.staged.info.width ? h('div', { class: 'row-flex', style: 'align-items:flex-start;gap:16px' }, zoomed(src, 'Replacement, magnified'), tiles(src))
          : h('p', { class: 'muted' }, 'Not a readable PNG.')));
    } else {
      panes.push(h('section', { class: 'card pane', 'aria-label': 'Replacement' }, h('h2', {}, 'Replacement'), dropZone()));
    }
    paneHost.replaceChildren(...panes);
  }

  const fileInput = h('input', { type: 'file', accept: 'image/png', class: 'hidden', onchange: (e) => e.target.files[0] && take(e.target.files[0]) });
  function dropZone() {
    const zone = h('div', { class: 'drop' },
      h('b', {}, 'Drop a PNG here'),
      h('span', { class: 'small dim' }, `Same size as the original (${w}×${ht}) or its aspect ratio at a power-of-two scale. Nothing is written until you press “Write file and row”.`),
      h('button', { type: 'button', class: 'btn sm', disabled: !STATE.batch, onclick: () => fileInput.click() }, 'Choose PNG…'), fileInput);
    zone.addEventListener('dragover', (e) => { e.preventDefault(); zone.classList.add('over'); });
    zone.addEventListener('dragleave', () => zone.classList.remove('over'));
    zone.addEventListener('drop', (e) => { e.preventDefault(); zone.classList.remove('over'); if (e.dataTransfer.files[0]) take(e.dataTransfer.files[0]); });
    return zone;
  }
  async function take(file) {
    if (!STATE.batch) return toast('Start a batch first', true);
    upload.buffer = await bytesOf(file);
    upload.name = file.name;
    await stage();
  }
  const mcmeta = h('textarea', { rows: 3, class: 'mono small', placeholder: '{"animation": {"frametime": 4}}' });
  mcmeta.value = '';
  async function stage() {
    upload.staged = await guard(() => api('/api/upload', { body: { row: r.id, status: upload.status, data: base64(upload.buffer), mcmeta: mcmeta.value.trim() || null } }));
    renderPanes(); renderSide(); renderPreviews();
  }

  const note = h('textarea', { rows: 2, id: 'note', placeholder: 'Appended to the row notes, e.g. what changed' });
  const origin = { kind: 'human', source: '', license: '' };
  function renderSide() {
    const statuses = h('div', { class: 'seg wide', role: 'radiogroup', 'aria-label': 'Status after write' },
      ['placeholder', 'draft', 'final'].map((s) => h('label', { class: upload.status === s ? 'on' : '' },
        h('input', { type: 'radio', name: 'status', checked: upload.status === s, onchange: async () => { upload.status = s; if (upload.buffer) await stage(); else renderSide(); } }), s)));
    const checks = upload.staged ? upload.staged.checks : null;
    const passed = checks && checks.every((c) => c.state !== 'fail');
    const write = h('button', { type: 'button', class: 'btn lg primary grow', disabled: !STATE.batch || !passed, onclick: () => guard(async () => {
      await api('/api/upload/write', { body: { id: upload.staged.id, status: upload.status, origin, note: note.value } });
      toast(`Wrote ${r.path.split('/').pop()} (${upload.status})`);
      await refresh(); route();
    }) }, 'Write file and row');
    const licensed = h('div', { class: 'stack', style: `gap:6px;${origin.kind === 'licensed' ? '' : 'display:none'}` },
      h('input', { type: 'text', placeholder: 'Source (author, site or pack)', value: origin.source, 'aria-label': 'Licence source', oninput: (e) => { origin.source = e.target.value; } }),
      h('input', { type: 'text', placeholder: 'Licence, e.g. CC0-1.0', value: origin.license, 'aria-label': 'Licence', oninput: (e) => { origin.license = e.target.value; } }));
    side.replaceChildren(
      h('fieldset', {}, h('legend', { class: 'small', style: 'font-weight:600;color:var(--f2)' }, 'Status after write'), statuses),
      h('section', { class: 'checks', 'aria-label': 'Checks' },
        h('h2', { class: 'label', style: 'margin-bottom:6px' }, checks ? `Checks · ${checks.filter((c) => c.state === 'pass').length} of ${checks.filter((c) => c.state !== 'info').length} pass` : 'Checks run when you choose a file'),
        (checks || [
          { label: 'Valid PNG', detail: 'non-interlaced, under the upload limit', state: 'info' },
          { label: 'Size', detail: `${w}×${ht}, or its aspect ratio at a power-of-two scale`, state: 'info' },
          { label: 'Animation', detail: 'a strip needs a matching .mcmeta', state: 'info' },
          { label: 'Placeholder marker', detail: 'present exactly when the status is placeholder', state: 'info' },
        ]).map((c) => h('div', { class: 'check' },
          h('span', { class: `badge ${c.state}`, 'aria-hidden': 'true' }, c.state === 'pass' ? '✓' : c.state === 'fail' ? '×' : 'i'),
          h('div', { class: 'stack', style: 'gap:1px;min-width:0' }, h('span', { style: 'font-weight:500' }, c.label, h('span', { class: 'sr-only' }, ` ${c.state}`)), h('span', { class: 'small dim' }, c.detail)))),
        checks ? h('div', { class: 'check' }, h('span', { class: 'badge info', 'aria-hidden': 'true' }, 'i'),
          h('div', { class: 'stack', style: 'gap:1px' }, h('span', { style: 'font-weight:500' }, 'Tiling'), h('span', { class: 'small dim' }, 'Not checked automatically. Judge the seams in the 3×3 tile and the 3D preview.'))) : null),
      h('details', { open: detail.mcmeta ? true : null }, h('summary', { class: 'small dim' }, detail.mcmeta ? 'Animation (.mcmeta beside the file)' : 'Add an .mcmeta for an animation strip'),
        detail.mcmeta ? h('pre', { class: 'body' }, detail.mcmeta) : null, mcmeta,
        h('button', { type: 'button', class: 'btn sm', disabled: !upload.buffer, onclick: stage }, 'Re-check with this .mcmeta')),
      h('fieldset', {}, h('legend', { class: 'small', style: 'font-weight:600;color:var(--f2)' }, 'Origin'),
        h('label', { class: 'row-flex', style: 'gap:8px' }, h('input', { type: 'radio', name: 'origin', checked: origin.kind === 'human', onchange: () => { origin.kind = 'human'; renderSide(); } }), 'human', h('span', { class: 'mono small muted' }, STATE.reviewer)),
        h('label', { class: 'row-flex', style: 'gap:8px' }, h('input', { type: 'radio', name: 'origin', checked: origin.kind === 'licensed', onchange: () => { origin.kind = 'licensed'; renderSide(); } }), 'licensed', h('span', { class: 'small muted' }, 'needs source and licence')),
        licensed),
      h('label', { for: 'note', class: 'small', style: 'font-weight:600;color:var(--f2)' }, 'Note to append'), note,
      h('div', { class: 'row-flex' }, write, h('button', { type: 'button', class: 'btn lg', disabled: !upload.staged, onclick: () => { upload.staged = null; upload.buffer = null; renderPanes(); renderSide(); renderPreviews(); } }, 'Discard')),
      h('span', { class: 'small muted' }, STATE.batch ? `Goes into ${STATE.branch}; nothing is committed yet.` : 'Start a batch to write.'));
  }

  const viewports = [];
  async function renderPreviews() {
    viewports.length = 0;
    if (detail.host) return renderHost();
    const model = detail.usedBy.find((m) => m.path.includes('/models/block/')) || detail.usedBy[0];
    if (!model) {
      previewHost.replaceChildren(h('p', { class: 'small muted' }, 'No model in the repository names this texture, so there is no in-game preview.'));
      return;
    }
    const isBlock = model.path.includes('/models/block/');
    const sources = [{ title: 'Current', upload: null }];
    if (upload.staged && upload.staged.info.width) sources.push({ title: 'Replacement', upload: upload.staged.id });
    const grid = h('div', { class: 'panes' });
    previewHost.replaceChildren(h('section', { class: 'card pane', 'aria-label': 'In-game preview' },
      h('div', { class: 'row-flex' }, h('h2', {}, 'In-game preview'), h('span', { class: 'small dim mono' }, `${model.modelId}${model.generated ? ' (generated)' : ''}`),
        h('span', { class: 'grow' }), h('span', { class: 'small muted' }, 'Drag to orbit · scroll to zoom · double-click to reset')), grid,
      h('span', { class: 'small muted' }, 'Rendered by deepslate (vendored) with the textures the model names.')));
    for (const src of sources) {
      const views = h('div', { class: 'views' });
      grid.append(h('div', { class: 'stack' }, h('span', { class: 'small', style: `font-weight:600;color:${src.upload ? 'var(--ac)' : 'var(--f2)'}` }, src.title), views));
      for (const layout of isBlock ? ['block', 'wall'] : ['block']) {
        const canvas = h('canvas', { class: 'gl', role: 'img', 'aria-label': `${src.title} texture on ${layout === 'wall' ? 'a 3×3 wall' : 'the model'}` });
        views.append(h('figure', { class: 'view', style: 'margin:0' }, canvas, h('figcaption', { class: 'mono tiny muted' }, layout === 'wall' ? 'Wall 3×3' : isBlock ? 'Block' : 'Item')));
        modelPreview(canvas, {
          modelId: model.modelId, layout,
          fetchModel: (id) => api(url('/api/res', { kind: 'models', id })).catch(() => null),
          textureUrl: (id) => (src.upload && id === texId ? url('/api/upload', { id: src.upload }) : url('/api/res', { kind: 'textures', id })),
        }).then((v) => viewports.push(v)).catch((e) => canvas.replaceWith(h('p', { class: 'small', style: 'color:var(--erf)' }, `Preview failed: ${e.message}`)));
      }
    }
  }

  const hostUi = { mob: 'mannequin', intensity: 1, glow: true, show: 'replacement' };
  function renderHost() {
    const thisStage = /stage_(\d+)\.png$/.test(r.path) ? Number(r.path.match(/stage_(\d+)\.png$/)[1]) : 3;
    const useUpload = upload.staged && upload.staged.info.width && hostUi.show === 'replacement';
    const mob = detail.mobs.find((m) => m.id === hostUi.mob) || detail.mobs[0];
    const figs = h('div', { class: 'figs' });
    previewHost.replaceChildren(h('section', { class: 'card pane', 'aria-label': 'Host preview' },
      h('div', { class: 'row-flex' }, h('h2', {}, 'Host preview'), h('span', { class: 'chip outline plain' }, 'reference only'), h('span', { class: 'grow' }),
        h('label', { class: 'row-flex small dim', style: 'gap:6px' }, 'Mob', h('select', { onchange: (e) => { hostUi.mob = e.target.value; renderHost(); } },
          detail.mobs.map((m) => h('option', { value: m.id, selected: m.id === hostUi.mob, disabled: !m.available }, m.available ? m.label : `${m.label} (needs the client jar)`)))),
        h('label', { class: 'row-flex small dim', style: 'gap:8px' }, 'overlayIntensity',
          h('input', { type: 'range', min: 0, max: 1, step: 0.05, value: hostUi.intensity, onchange: (e) => { hostUi.intensity = Number(e.target.value); renderHost(); } }),
          h('span', { class: 'mono', style: 'color:var(--fg)' }, hostUi.intensity.toFixed(2))),
        h('label', { class: 'row-flex small dim', style: 'gap:6px' }, h('input', { type: 'checkbox', checked: hostUi.glow, onchange: (e) => { hostUi.glow = e.target.checked; renderHost(); } }), 'veins_glow from stage 3'),
        upload.staged ? h('div', { class: 'seg' }, ['replacement', 'current'].map((s) => h('button', { type: 'button', 'aria-pressed': String(hostUi.show === s), onclick: () => { hostUi.show = s; renderHost(); } }, s))) : null),
      figs,
      h('div', { class: 'callout info' }, h('span', { class: 'badge info', 'aria-hidden': 'true' }, 'i'), h('div', { class: 'stack small', style: 'gap:4px' },
        h('span', {}, 'Uses the same compositing as host_overlay.fsh: the pattern repeats one pattern texel per mob texel across the mob’s own texture, only where that texture is opaque (alpha ≥ 0.1), at overlayIntensity opacity; from stage 3, veins_glow.png goes on top at full brightness.'),
        h('span', { class: 'dim' }, 'Lighting, fog, animation and mob-specific models are approximate: confirm a final art pass in a harness scene. Vanilla mob textures are read at run time from the local NeoForge client resources jar and never copied into the repository.')))));
    for (const stage of [1, 2, 3]) {
      const src = url('/api/host', { stage, mob: mob.id, intensity: hostUi.intensity, glow: hostUi.glow ? 1 : 0, ...(useUpload ? { upload: upload.staged.id } : {}) });
      const canvas = h('canvas', { class: 'gl tall', role: 'img', 'aria-label': `${mob.label} at host stage ${stage}` });
      const here = stage === thisStage || (r.path.endsWith('veins_glow.png') && stage === 3);
      figs.append(h('figure', { class: `fig${here ? ' focus' : ''}`, style: 'margin:0' },
        h('figcaption', { class: 'row-flex', style: 'gap:8px' }, h('b', { style: here ? 'color:var(--ac)' : '' }, `Stage ${stage}`),
          h('span', { class: 'mono tiny muted' }, `stage_${stage}.png${stage >= 3 && hostUi.glow ? ' + veins_glow.png' : ''}${here ? ' · this file' : ''}`)),
        canvas,
        h('div', { class: 'row-flex', style: 'align-items:flex-end;gap:10px' },
          h('img', { class: `pixel checker`, src, alt: `Stage ${stage} composite on the ${mob.label} texture`, width: 128, height: mob.kind === 'quadruped' ? 64 : 128, style: 'outline:1px solid var(--bd)' }),
          h('span', { class: 'tiny muted' }, 'UV sheet at 2×: the pattern lands only on opaque texels'))));
      mobPreview(canvas, { kind: mob.kind, textureUrl: src }).then((v) => viewports.push(v))
        .catch((e) => canvas.replaceWith(h('p', { class: 'small', style: 'color:var(--erf)' }, `Preview failed: ${e.message}`)));
    }
  }

  const toolbar = h('div', { class: 'row-flex small dim', role: 'toolbar', 'aria-label': 'Preview' },
    'Zoom', h('div', { class: 'seg' }, [1, 2, 4, 8].map((z) => h('button', { type: 'button', 'aria-pressed': String(ui.zoom === z), onclick: () => { ui.zoom = z; keep('zoom', z); renderPanes(); toolbarSync(); } }, `${z}×`))),
    h('label', { class: 'row-flex', style: 'gap:6px' }, h('input', { type: 'checkbox', checked: ui.grid, onchange: (e) => { ui.grid = e.target.checked; keep('grid', ui.grid); renderPanes(); } }), 'Pixel grid'),
    h('label', { class: 'row-flex', style: 'gap:6px' }, h('input', { type: 'checkbox', checked: ui.checker, onchange: (e) => { ui.checker = e.target.checked; keep('checker', ui.checker); renderPanes(); } }), 'Checker behind alpha'),
    h('span', { class: 'grow' }), h('span', { class: 'mono muted' }, 'nearest-neighbour · never smoothed'));
  function toolbarSync() { toolbar.querySelectorAll('.seg button').forEach((b) => b.setAttribute('aria-pressed', String(b.textContent === `${ui.zoom}×`))); }

  app.replaceChildren(rowHeader(detail, detail.usedBy.length ? h('span', {}, 'Used by ', detail.usedBy.slice(0, 2).map((m, i) => [i ? ', ' : '', h('span', { class: 'mono' }, m.modelId)]), detail.usedBy.length > 2 ? ` +${detail.usedBy.length - 2}` : '') : null),
    h('div', { class: 'row-flex', style: 'align-items:flex-start;gap:20px;padding:18px 24px 24px' },
      h('div', { class: 'stack', style: 'flex:999 1 820px;min-width:0;gap:14px' }, batchGate(), toolbar, paneHost, previewHost), side));
  renderPanes(); renderSide(); renderPreviews();
  const redraw = () => viewports.forEach((v) => v.draw());
  window.addEventListener('resize', redraw);
  cleanup.push(() => window.removeEventListener('resize', redraw));
}

// --- models ----------------------------------------------------------------------------------

async function modelView(detail) {
  const r = detail.row;
  const lines = (detail.json || '').replace(/\n$/, '').split('\n');
  const code = h('div', { class: 'code' }, lines.map((l, i) => h('div', {}, h('span', { class: 'ln' }, i + 1), h('span', {}, l))));
  const views = h('div', { class: 'views' });
  const sources = [{ title: 'On main', rev: 'base' }, ...(STATE.batch ? [{ title: 'This batch', rev: 'worktree' }] : [])];
  for (const src of sources) {
    const canvas = h('canvas', { class: 'gl', role: 'img', 'aria-label': `${detail.modelId} ${src.title}` });
    views.append(h('figure', { class: 'view', style: 'margin:0' }, canvas, h('figcaption', { class: 'small dim' }, h('b', {}, src.title))));
    modelPreview(canvas, {
      modelId: detail.modelId, layout: 'block',
      fetchModel: (id) => api(url('/api/res', { kind: 'models', id, rev: src.rev })).catch(() => null),
      textureUrl: (id) => url('/api/res', { kind: 'textures', id, rev: src.rev }),
    }).catch((e) => canvas.replaceWith(h('p', { class: 'small', style: 'color:var(--erf)' }, `Preview failed: ${e.message}`)));
  }
  const textures = h('div', { class: 'card', style: 'overflow:hidden' }, (detail.textures || []).map((t) => h('div', { class: 'row-flex', style: 'padding:8px 12px;border-bottom:1px solid var(--bd)' },
    h('span', { class: 'mono small muted', style: 'width:60px' }, t.var),
    t.path ? h('img', { class: 'pixel', src: url('/api/file', { path: t.path }), alt: '', width: 32, height: 32 }) : h('span', { class: 'thumb none' }, t.exists ? 'mc' : '?'),
    h('span', { class: 'mono small ellipsis grow' }, t.id), t.status ? chip(t.status) : h('span', { class: 'small muted' }, t.exists ? 'vanilla' : 'missing'),
    t.row ? h('a', { class: 'btn sm primary', href: rowHref(t.row) }, 'Replace texture →') : null)));
  let main;
  if (r.generated) {
    main = h('div', { class: 'callout neutral' }, icon(LOCK, 18), h('div', { class: 'stack', style: 'gap:4px' },
      h('strong', {}, 'Datagen writes this file, so it can’t be edited here'),
      h('span', { class: 'dim' }, 'Everything under mod/src/generated/ is rewritten by ./gradlew runData from the providers. A hand edit would be overwritten on the next run, and CI rejects generated files that drift from their providers. The editor never writes generated paths.')));
  }
  const how = r.generated ? h('aside', { class: 'card side', 'aria-label': 'How to change it' },
    h('h2', {}, 'To change the model itself'),
    h('ol', { class: 'stack', style: 'margin:0;padding-left:18px;gap:10px' },
      h('li', {}, h('b', {}, 'Change the datagen provider. '), h('span', { class: 'small dim' }, 'Find the block or item in mod/src/main/java/com/cubiecode/remission/data/ (RemissionBlockStateProvider or RemissionItemModelProvider), edit it, run ./gradlew runData and commit the regenerated file.')),
      h('li', {}, h('b', {}, 'Or hand-author a model '), h('span', { class: 'small dim' }, 'under mod/src/main/resources/assets/remission/models/ and stop the provider emitting this one. Once it has a ledger row there, this editor can upload to it.'))),
    h('p', { class: 'small dim', style: 'margin:0;padding-top:10px;border-top:1px solid var(--bd)' }, 'Both are code changes for a roadmap lane, not a content batch. Most art changes to a cube block are texture changes, which are editable.'),
    h('button', { type: 'button', class: 'btn', disabled: true }, 'Upload JSON · disabled for generated paths')) : modelUploadSide(detail);
  app.replaceChildren(rowHeader(detail), main ? h('div', { style: 'padding:16px 24px 0' }, main) : '',
    h('div', { class: 'row-flex', style: 'align-items:flex-start;gap:20px;padding:18px 24px 24px' },
      h('div', { class: 'stack', style: 'flex:999 1 560px;min-width:0;gap:10px' }, batchGate(),
        h('div', { class: 'row-flex' }, h('h2', { class: 'label' }, 'File'), h('span', { class: 'mono small muted' }, `${lines.length} lines`)), code,
        h('div', { class: 'row-flex', style: 'margin-top:10px' }, h('h2', { class: 'label' }, 'Preview'), h('span', { class: 'small muted' }, 'this JSON, rendered with the textures it names'),
          h('span', { class: 'grow' }), h('span', { class: 'small muted' }, 'Drag to orbit · scroll to zoom')), views,
        h('h2', { class: 'label', style: 'margin-top:10px' }, 'Textures it names'), textures), how));
}

function modelUploadSide(detail) {
  const r = detail.row;
  const side = h('aside', { class: 'card side', 'aria-label': 'Upload model' });
  const state = { staged: null, status: r.status === 'final' ? 'final' : 'draft', text: '' };
  const area = h('textarea', { rows: 8, class: 'mono small', placeholder: 'Paste model JSON, or choose a file', oninput: (e) => { state.text = e.target.value; } });
  const file = h('input', { type: 'file', accept: '.json,application/json', onchange: async (e) => { const f = e.target.files[0]; if (f) { area.value = state.text = await f.text(); } } });
  const out = h('div', { class: 'checks' });
  async function check() {
    state.staged = await guard(() => api('/api/upload', { body: { row: r.id, status: state.status, data: base64(new TextEncoder().encode(state.text)) } }));
    out.replaceChildren(...state.staged.checks.map((c) => h('div', { class: 'check' }, h('span', { class: `badge ${c.state}` }, c.state === 'pass' ? '✓' : c.state === 'fail' ? '×' : 'i'),
      h('div', { class: 'stack', style: 'gap:1px' }, h('b', {}, c.label), h('span', { class: 'small dim' }, c.detail)))));
    write.disabled = !state.staged.ok;
    let model = null;
    try { model = JSON.parse(state.text); } catch { /* reported by the checks */ }
    if (model && detail.modelId) {
      const canvas = h('canvas', { class: 'gl', role: 'img', 'aria-label': 'Uploaded model preview' });
      upPreview.replaceChildren(h('span', { class: 'small', style: 'font-weight:600;color:var(--ac)' }, 'Uploaded, not written yet'), canvas);
      modelPreview(canvas, {
        modelId: detail.modelId, layout: 'block',
        fetchModel: (id) => (id === slugId(detail.modelId) ? Promise.resolve(model) : api(url('/api/res', { kind: 'models', id })).catch(() => null)),
        textureUrl: (id) => url('/api/res', { kind: 'textures', id }),
      }).catch((e) => canvas.replaceWith(h('p', { class: 'small', style: 'color:var(--erf)' }, `Preview failed: ${e.message}`)));
    }
  }
  const upPreview = h('div', { class: 'stack', style: 'gap:6px' });
  const write = h('button', { type: 'button', class: 'btn lg primary', disabled: true, onclick: () => guard(async () => {
    await api('/api/upload/write', { body: { id: state.staged.id, status: state.status, origin: { kind: 'human' } } });
    toast('Wrote the model'); await refresh(); route();
  }) }, 'Write file and row');
  side.append(h('h2', {}, 'Replace the model'), file, area,
    h('label', { class: 'row-flex small dim' }, 'Status', h('select', { onchange: (e) => { state.status = e.target.value; } }, ['draft', 'final'].map((s) => h('option', { selected: s === state.status }, s)))),
    h('button', { type: 'button', class: 'btn', disabled: !STATE.batch, onclick: check }, 'Check'), out, upPreview, write);
  return side;
}

// --- sounds ------------------------------------------------------------------------------------

async function soundView(detail) {
  const r = detail.row;
  const state = { staged: null, status: r.status === 'final' ? 'final' : 'draft' };
  const out = h('div', { class: 'checks' });
  const player = h('div');
  const write = h('button', { type: 'button', class: 'btn lg primary', disabled: true, onclick: () => guard(async () => {
    await api('/api/upload/write', { body: { id: state.staged.id, status: state.status, origin: { kind: 'human' } } });
    toast('Wrote the sound'); await refresh(); route();
  }) }, 'Write file and row');
  const file = h('input', { type: 'file', accept: '.ogg,audio/ogg', disabled: !STATE.batch, onchange: async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    state.staged = await guard(async () => api('/api/upload', { body: { row: r.id, status: state.status, data: base64(await f.arrayBuffer()) } }));
    out.replaceChildren(...state.staged.checks.map((c) => h('div', { class: 'check' }, h('span', { class: `badge ${c.state}` }, c.state === 'pass' ? '✓' : '×'), h('div', {}, h('b', {}, c.label), ' ', h('span', { class: 'small dim' }, c.detail)))));
    player.replaceChildren(h('audio', { controls: true, src: url('/api/upload', { id: state.staged.id }) }));
    write.disabled = !state.staged.ok;
  } });
  app.replaceChildren(rowHeader(detail), h('div', { class: 'content', style: 'max-width:900px' }, batchGate(),
    h('h2', { class: 'label' }, 'Current'), detail.bytes ? h('audio', { controls: true, src: url('/api/file', { path: r.path }) }) : h('p', { class: 'muted' }, 'No file yet.'),
    h('h2', { class: 'label' }, 'Replacement (.ogg Vorbis)'), file, player, out, write));
}

// --- batch ---------------------------------------------------------------------------------------

async function batchView() {
  if (!STATE.batch) {
    app.replaceChildren(h('div', { class: 'content', style: 'max-width:760px' },
      h('h1', {}, 'No batch yet'),
      h('p', { class: 'dim' }, 'A batch is a content/<date>-<name> branch in its own worktree, made from origin/main. Every edit lands there; “Save batch” runs the checks, commits and can open a draft PR. The editor never pushes to main, merges or publishes.'),
      h('div', {}, h('button', { class: 'btn primary', type: 'button', onclick: startDialog }, 'Start batch'))));
    return;
  }
  const summary = await api('/api/batch');
  const changes = h('section', { class: 'card', 'aria-labelledby': 'pend-h' },
    h('div', { class: 'card-head' }, h('h2', { id: 'pend-h' }, 'Pending changes'), h('span', { class: 'mono tiny muted' }, summary.changes.length)),
    summary.changes.length ? summary.changes.map(changeLine) : h('p', { class: 'muted', style: 'padding:14px;margin:0' }, 'Nothing changed yet. Edit rows from the ledger.'),
    h('div', { class: 'stack', style: 'padding:10px 14px;gap:4px;border-top:1px solid var(--bd)' }, h('span', { class: 'eyebrow' }, 'Files'),
      summary.files.length ? summary.files.map((f) => h('div', { class: 'filerow' }, h('span', { class: 'ellipsis grow' }, f.path),
        h('span', { class: 'add' }, f.added && f.added !== '-' ? `+${f.added}` : ''), h('span', { class: 'del' }, f.deleted && f.deleted !== '-' ? `−${f.deleted}` : ''),
        h('span', { class: 'muted' }, f.added === '-' ? 'binary' : ''))) : h('span', { class: 'small muted' }, 'none')));

  const checksBody = h('div');
  const commitBtn = h('button', { type: 'button', class: 'btn lg primary' }, 'Commit');
  const prBtn = h('button', { type: 'button', class: 'btn lg outline-ac' }, 'Open PR');
  const hint = h('span', { class: 'small' });
  let lastChecks = summary.checks;
  function renderChecks() {
    const ok = lastChecks && lastChecks.every((c) => c.ok);
    checksBody.replaceChildren(...(lastChecks || [{ cmd: 'tools/check-provenance.sh' }, { cmd: 'python3 -I tools/check-docs.py' }, { cmd: 'git diff --check' }]).map((c) => h('div', { class: 'stack', style: 'padding:10px 14px;border-bottom:1px solid var(--bd);gap:8px' },
      h('div', { class: 'row-flex', style: 'gap:10px' },
        h('span', { class: `badge ${c.ok === undefined ? 'info' : c.ok ? 'pass' : 'fail'}`, 'aria-hidden': 'true' }, c.ok === undefined ? '·' : c.ok ? '✓' : '×'),
        h('code', { class: 'small ellipsis grow' }, c.cmd),
        h('span', { class: 'small', style: `font-weight:500;color:${c.ok === undefined ? 'var(--f3)' : c.ok ? 'var(--okf)' : 'var(--erf)'}` }, c.ok === undefined ? 'not run' : c.ok ? 'passed' : 'failed'),
        c.seconds !== undefined ? h('span', { class: 'mono tiny muted' }, `${c.seconds} s`) : null),
      c.ok === false && c.output ? h('pre', { class: 'out' }, c.output) : null)));
    sum.textContent = lastChecks ? (ok ? `${lastChecks.length} of ${lastChecks.length} passed` : `${lastChecks.filter((c) => c.ok).length} passed, ${lastChecks.filter((c) => !c.ok).length} failed`) : 'not run';
    sum.style.color = lastChecks ? (ok ? 'var(--okf)' : 'var(--erf)') : '';
    const dirty = summary.uncommitted;
    const committed = summary.commits.length > 0;
    commitBtn.disabled = !dirty || !ok || !STATE.checked;
    prBtn.disabled = dirty || !committed;
    if (!dirty && committed) hint.textContent = 'Everything is committed. Open PR pushes this branch (never main) and opens a draft PR, or updates the branch\'s open PR.';
    else if (!dirty) hint.textContent = 'Nothing to commit yet.';
    else if (!lastChecks) hint.textContent = committed ? 'New changes since the last commit: run the checks to commit them.' : 'Run the checks to commit.';
    else hint.textContent = ok ? 'Checks pass: commit, then open the PR.' : 'Fix the failing check and run the checks again.';
    hint.style.color = lastChecks && !ok ? 'var(--erf)' : 'var(--f3)';
  }
  const sum = h('span', { class: 'small' });
  const run = h('button', { type: 'button', class: 'btn sm', onclick: () => guard(async () => {
    run.disabled = true; run.textContent = 'Running…';
    try {
      const res = await api('/api/batch/check', { body: {} });
      lastChecks = res.checks; STATE.checked = res.ok;
      renderChecks();
    } finally { run.disabled = false; run.textContent = 'Run checks'; }
  }) }, 'Run checks');
  const message = h('input', { type: 'text', id: 'msg', value: defaultMessage(summary), style: 'width:100%' });
  const draft = h('input', { type: 'checkbox', checked: true, id: 'draft' });
  commitBtn.addEventListener('click', () => guard(async () => {
    const res = await api('/api/batch/commit', { body: { message: message.value } });
    toast(`Committed ${res.commit}`);
    await refresh(); route();
  }));
  prBtn.addEventListener('click', () => guard(async () => {
    prBtn.disabled = true;
    try {
      const res = await api('/api/batch/pr', { body: { title: message.value, draft: draft.checked } });
      prResult.replaceChildren(h('span', {}, res.updated ? 'Pushed to the open PR ' : 'Opened ', h('a', { href: res.url, target: '_blank', rel: 'noreferrer' }, res.url)));
    } finally { prBtn.disabled = false; }
  }));
  const prResult = h('div', { class: 'small' });

  app.replaceChildren(
    h('div', { class: 'row-flex', style: 'padding:18px 24px 0;align-items:flex-end' },
      h('div', { class: 'stack', style: 'gap:4px' }, h('h1', {}, 'Save batch'),
        h('div', { class: 'row-flex small dim', style: 'gap:6px 10px' }, `${summary.changes.length} ledger rows · ${summary.files.length} files`, '·',
          h('span', { class: 'mono', style: 'color:var(--fg)' }, summary.branch), 'from', h('span', { class: 'mono' }, `main @ ${summary.base}`), '·',
          h('span', { class: 'mono ellipsis', title: summary.worktree }, summary.worktree))),
      h('span', { class: 'grow' }), h('span', { class: 'small muted' }, 'Never pushes to main, merges or publishes.')),
    h('div', { class: 'row-flex', style: 'padding:12px 24px 0' },
      h('div', { class: 'callout neutral', style: 'flex:1 1 auto;align-items:center' },
        h('span', { class: 'grow small' }, summary.behind
          ? [h('strong', {}, `main has ${summary.behind} new commit${summary.behind === 1 ? '' : 's'}. `), 'Update before merging; a batch touching en_us.json or texture references must be rebased and rechecked.']
          : 'Up to date with main as last fetched. Update from main fetches first.'),
        h('button', { type: 'button', class: 'btn sm', disabled: summary.uncommitted, title: summary.uncommitted ? 'Commit or undo changes first' : null, onclick: () => guard(async () => {
          const res = await api('/api/batch/update', { body: {} });
          toast(res.changed ? 'Rebased onto main; run the checks again' : 'Already up to date with main');
          await refresh(); route();
        }) }, 'Update from main'),
        h('button', { type: 'button', class: 'btn sm', onclick: finishDialog }, 'Finish batch…'))),
    h('div', { class: 'row-flex', style: 'align-items:flex-start;gap:20px;padding:16px 24px 24px' },
      h('div', { class: 'stack', style: 'flex:999 1 640px;min-width:0' }, changes),
      h('div', { class: 'stack', style: 'flex:1 1 420px;max-width:540px;gap:16px' },
        h('section', { class: 'card', 'aria-labelledby': 'chk-h' }, h('div', { class: 'card-head' }, h('h2', { id: 'chk-h' }, 'Checks'), sum, h('span', { class: 'grow' }), run), checksBody),
        h('section', { class: 'card pad stack', 'aria-labelledby': 'sub-h', style: 'gap:12px' }, h('h2', { id: 'sub-h' }, 'Commit and pull request'),
          h('label', { for: 'msg', class: 'small', style: 'font-weight:600;color:var(--f2)' }, 'Commit message (also the PR title)'), message,
          h('span', { class: 'small', style: 'font-weight:600;color:var(--f2)' }, 'PR body · generated from the changed rows'), h('pre', { class: 'body' }, summary.prBody),
          h('label', { class: 'row-flex', style: 'gap:8px', for: 'draft' }, draft, 'Open as draft'),
          h('div', { class: 'row-flex' }, commitBtn, prBtn, hint), prResult,
          summary.commits.length ? h('div', { class: 'stack', style: 'gap:2px' }, h('span', { class: 'eyebrow' }, `Commits on ${summary.branch}`),
            summary.commits.map((c) => h('span', { class: 'small' }, h('span', { class: 'mono muted' }, c.sha), ' ', c.subject))) : null))));
  renderChecks();
}

function finishDialog() {
  const name = h('input', { type: 'text', id: 'discard', class: 'mono small', style: 'width:100%', placeholder: STATE.branch });
  const dialog = h('dialog', {}, h('form', { method: 'dialog', class: 'stack', onsubmit: async (e) => {
    e.preventDefault();
    await guard(async () => {
      const res = await api('/api/batch/finish', { body: { discard: name.value.trim() || null } });
      dialog.close(); dialog.remove();
      toast(`Finished ${res.branch}${res.pr ? ` (PR ${res.pr.state.toLowerCase()})` : ', discarded'}`);
      await refresh(); location.hash = '#/'; route();
    });
  } },
  h('h2', {}, 'Finish this batch'),
  h('p', { class: 'small dim', style: 'margin:0' }, 'Removes the content worktree and its local branch. Once its PR is merged or closed this needs no confirmation; the PR and anything merged stay on GitHub.'),
  h('p', { class: 'small dim', style: 'margin:0' }, 'A batch with no PR, or with edits or commits its merged or closed PR does not contain, loses that work for good: type the branch name to discard it.'),
  h('label', { for: 'discard', class: 'small dim' }, `Branch name (${STATE.branch}), only to discard`), name,
  h('div', { class: 'row-flex' }, h('span', { class: 'grow' }),
    h('button', { type: 'button', class: 'btn', onclick: () => { dialog.close(); dialog.remove(); } }, 'Cancel'),
    h('button', { type: 'submit', class: 'btn primary' }, 'Finish batch'))));
  document.body.append(dialog);
  dialog.showModal();
}

function defaultMessage(summary) {
  const names = summary.changes.slice(0, 3).map((c) => (c.row.key || c.row.path.split('/').pop()).replace(/\.png$|\.json$|\.ogg$/, ''));
  return `Content: Replace ${names.join(', ')}${summary.changes.length > 3 ? ` and ${summary.changes.length - 3} more` : ''}`;
}

function changeLine(c) {
  const r = c.row, b = c.before;
  const [dir, leaf] = splitPath(r);
  const visual = c.kind === 'texture'
    ? h('div', { class: 'row-flex', style: 'gap:6px;flex:none' },
      b ? h('img', { class: 'pixel', src: url('/api/file', { path: r.path, rev: 'base' }), alt: 'before', width: 32, height: 32, style: 'outline:1px solid var(--bd)' }) : null,
      h('span', { class: 'muted', 'aria-hidden': 'true' }, '→'),
      h('img', { class: 'pixel', src: url('/api/file', { path: r.path }), alt: 'after', width: 32, height: 32, style: 'outline:1px solid var(--ac)' }))
    : h('span', { class: 'thumb none' }, c.new ? '+row' : c.kind === 'lang' ? 'Aa' : '·');
  let action = 'row updated';
  if (c.new) action = r.key ? 'new exact-key row' : 'new row';
  else if (b.status !== r.status && r.status === 'final' && b.origin === r.origin) action = r.wildcard ? 'accepted as final · * row updated' : 'accepted as final';
  else if (c.kind === 'texture' || c.kind === 'model' || c.kind === 'sound') action = `${c.kind} replaced`;
  else if (c.strings && c.strings.length) action = 'string replaced';
  else if (c.diff && c.diff.length) action = 'text replaced';
  return h('div', { class: 'change' }, visual,
    h('div', { class: 'stack grow', style: 'gap:5px;min-width:0' },
      h('div', { class: 'row-flex', style: 'gap:4px 10px' }, h('a', { href: rowHref(r.id), class: 'mono small', style: 'color:var(--fg);text-decoration:none;overflow-wrap:anywhere' }, h('span', { class: 'muted' }, dir), leaf),
        h('span', { class: 'small', style: 'font-weight:500;color:var(--ac)' }, action)),
      h('div', { class: 'row-flex small dim', style: 'gap:6px' }, b ? chip(b.status) : h('span', { class: 'muted' }, 'new'), h('span', { 'aria-hidden': 'true' }, '→'), chip(r.status),
        h('span', {}, b && b.origin !== r.origin ? `${b.origin} → ${r.origin}` : r.origin)),
      c.strings && c.strings.length ? h('div', { class: 'strings' }, c.strings.map((s) => [
        h('span', { class: 'dim' }, h('span', { class: 'mono del' }, '− '), h('s', {}, s.before ?? '(none)')),
        h('span', {}, h('span', { class: 'mono add' }, '+ '), s.after)])) : null,
      c.diff && c.diff.length ? h('details', {}, h('summary', { class: 'small dim' }, `Diff against main (${c.diff.filter((l) => /^[+-][^+-]/.test(l)).length} lines changed)`),
        h('div', { class: 'diffbox', style: 'max-height:320px;overflow:auto' }, c.diff.map((l) => h('div', { class: l.startsWith('+') && !l.startsWith('+++') ? 'add' : l.startsWith('-') && !l.startsWith('---') ? 'del' : 'muted' }, l)))) : null,
      b && b.notes !== r.notes ? h('span', { class: 'small dim' }, 'Notes: ', r.notes.startsWith(b.notes.replace(/\.?$/, '')) ? `…${r.notes.slice(b.notes.length)}` : r.notes) : null,
      c.new && r.key ? h('span', { class: 'small dim' }, 'Overrides the shared * row for this key; that row is unchanged.') : null),
    h('button', { type: 'button', class: 'btn sm', style: 'align-self:flex-start', onclick: () => guard(async () => {
      await api('/api/batch/undo', { body: { row: r.id } });
      toast(`Undid ${leaf}`); await refresh(); route();
    }) }, 'Undo'));
}

// --- theme and start -------------------------------------------------------------------------------

const themeButton = document.getElementById('theme');
const savedTheme = store('theme', null);
if (savedTheme) document.documentElement.dataset.theme = savedTheme;
themeButton.addEventListener('click', () => {
  const dark = document.documentElement.dataset.theme
    ? document.documentElement.dataset.theme === 'dark'
    : matchMedia('(prefers-color-scheme: dark)').matches;
  document.documentElement.dataset.theme = dark ? 'light' : 'dark';
  keep('theme', document.documentElement.dataset.theme);
});

refresh().then(route).catch((e) => {
  app.replaceChildren(h('div', { class: 'content' }, h('div', { class: 'callout error' }, h('strong', {}, 'The editor could not load: '), e.message)));
});
