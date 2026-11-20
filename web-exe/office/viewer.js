'use strict';
/* Office Reader — SumatraPDF-style tabbed reader. Everything is rendered locally; nothing is uploaded.
   PDF (pdf.js in an iframe) · Word · Excel/CSV · PowerPoint · images · video · audio · Markdown · plain text. */
const $ = (id) => document.getElementById(id);

/* ------------------------------------------------------------------ file types */
const KIND_BY_EXT = {
  pdf: 'pdf', docx: 'docx', xlsx: 'xlsx', xls: 'xlsx', csv: 'xlsx', pptx: 'pptx', doc: 'olddoc', ppt: 'olddoc',
  png: 'image', jpg: 'image', jpeg: 'image', gif: 'image', webp: 'image', bmp: 'image', svg: 'image', ico: 'image', avif: 'image',
  mp4: 'video', webm: 'video', mkv: 'video', mov: 'video', m4v: 'video', ogv: 'video',
  mp3: 'audio', wav: 'audio', ogg: 'audio', m4a: 'audio', flac: 'audio', aac: 'audio',
  md: 'md', markdown: 'md',
  txt: 'text', log: 'text', json: 'text', xml: 'text', ini: 'text', cfg: 'text', yml: 'text', yaml: 'text', js: 'text', css: 'text', html: 'text', py: 'text', java: 'text', c: 'text', cpp: 'text', h: 'text', sql: 'text', bat: 'text', tsv: 'text',
};
const BADGE = {
  pdf: ['PDF', '#d93025'], docx: ['DOC', '#2b579a'], xlsx: ['XLS', '#217346'], pptx: ['PPT', '#c43e1c'], image: ['IMG', '#8e44ad'],
  video: ['VID', '#e67e22'], audio: ['AUD', '#16a085'], md: ['MD', '#34495e'], text: ['TXT', '#607d8b'], olddoc: ['DOC', '#7f8c8d'],
};
const extOf = (n) => { const m = /\.([a-z0-9]+)$/i.exec(n || ''); return m ? m[1].toLowerCase() : ''; };
const kindOf = (n) => KIND_BY_EXT[extOf(n)] || '';
const baseName = (n) => String(n || '').replace(/^.*[\\/]/, '');
const MAX_BLOB = 150 * 1024 * 1024;   // bigger files are not kept in the "frequently read" library (re-pick them instead)
const MAX_ROWS = 3000, MAX_COLS = 200, MAX_TEXT = 8 * 1024 * 1024;

/* ------------------------------------------------------------------ small helpers */
function msg(t) { const m = $('msg'); m.textContent = t || ''; m.style.display = t ? 'block' : 'none'; if (t) { clearTimeout(msg.t); msg.t = setTimeout(() => msg(''), 6500); } }
function el(tag, cls, html) { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const readBuf = (blob) => blob.arrayBuffer();
const readText = (blob) => blob.slice(0, MAX_TEXT).text();

/* ------------------------------------------------------------------ library (frequently read) — IndexedDB */
const DB = { db: null };
function dbOpen() {
  if (DB.p) return DB.p;
  DB.p = new Promise((res) => {
    try {
      const rq = indexedDB.open('rt-reader', 1);
      rq.onupgradeneeded = () => rq.result.createObjectStore('files', { keyPath: 'id' });
      rq.onsuccess = () => res(rq.result);
      rq.onerror = () => res(null);
    } catch (_) { res(null); }
  });
  return DB.p;
}
async function dbAll() {
  const db = await dbOpen(); if (!db) return [];
  return new Promise((res) => { try { const r = db.transaction('files').objectStore('files').getAll(); r.onsuccess = () => res(r.result || []); r.onerror = () => res([]); } catch (_) { res([]); } });
}
async function dbPut(rec) {
  const db = await dbOpen(); if (!db) return;
  return new Promise((res) => { try { const tx = db.transaction('files', 'readwrite'); tx.objectStore('files').put(rec); tx.oncomplete = () => res(); tx.onerror = () => res(); tx.onabort = () => res(); } catch (_) { res(); } });
}
async function dbGet(id) {
  const db = await dbOpen(); if (!db) return null;
  return new Promise((res) => { try { const r = db.transaction('files').objectStore('files').get(id); r.onsuccess = () => res(r.result || null); r.onerror = () => res(null); } catch (_) { res(null); } });
}
async function dbDel(id) {
  const db = await dbOpen(); if (!db) return;
  return new Promise((res) => { try { const tx = db.transaction('files', 'readwrite'); tx.objectStore('files').delete(id); tx.oncomplete = () => res(); tx.onerror = () => res(); } catch (_) { res(); } });
}
async function dbClear() {
  const db = await dbOpen(); if (!db) return;
  return new Promise((res) => { try { const tx = db.transaction('files', 'readwrite'); tx.objectStore('files').clear(); tx.oncomplete = () => res(); tx.onerror = () => res(); } catch (_) { res(); } });
}
const keyOf = (name, size) => name.toLowerCase() + '|' + size;

async function libTouch(name, kind, blob) {
  const id = keyOf(name, blob.size);
  let rec = await dbGet(id);
  if (!rec) rec = { id, name, kind, size: blob.size, count: 0, thumb: '', blob: null };
  rec.count = (rec.count || 0) + 1; rec.last = Date.now(); rec.kind = kind;
  if (!rec.blob && blob.size <= MAX_BLOB) rec.blob = blob;
  await dbPut(rec);
  return rec;
}
async function libThumb(id, thumb) {
  const rec = await dbGet(id); if (!rec) return;
  rec.thumb = thumb; await dbPut(rec); if (cur === 'home') renderHome();
}
async function libPrune() {
  const all = await dbAll();
  if (all.length <= 60) return;
  all.sort((a, b) => (a.count - b.count) || (a.last - b.last));
  for (const r of all.slice(0, all.length - 60)) await dbDel(r.id);
}

/* ------------------------------------------------------------------ tabs */
const tabs = [];            // { id, name, kind, blob, url, ownsUrl, pane, h (handler), libId, btn }
let cur = 'home';           // 'home' or a tab object
let seq = 0;

function renderTabStrip() {
  const strip = $('tabs'); strip.textContent = '';
  const hb = el('button', 'tab home' + (cur === 'home' ? ' on' : ''), '<span class="t">Home</span>');
  hb.setAttribute('role', 'tab'); hb.onclick = () => activate('home'); strip.appendChild(hb);
  tabs.forEach((t) => {
    const b = el('div', 'tab' + (cur === t ? ' on' : ''));
    b.setAttribute('role', 'tab'); b.title = t.name;
    const s = el('span', 't'); s.textContent = t.name; b.appendChild(s);
    const x = el('button', 'x', '&times;'); x.title = 'Close (Ctrl+W)'; x.onclick = (e) => { e.stopPropagation(); closeTab(t); }; b.appendChild(x);
    b.onclick = () => activate(t);
    b.onauxclick = (e) => { if (e.button === 1) { e.preventDefault(); closeTab(t); } };
    strip.appendChild(b); t.btn = b;
  });
}
function activate(t) {
  cur = t;
  $('home').classList.toggle('on', t === 'home');
  tabs.forEach((x) => x.pane.classList.toggle('on', x === t));
  document.body.classList.toggle('notome', t !== 'home');
  document.title = t === 'home' ? 'Office Reader' : t.name;
  renderTabStrip(); updateToolbar();
  if (t === 'home') renderHome();
  else if (t.h && t.h.onShow) t.h.onShow();
}
function closeTab(t) {
  const i = tabs.indexOf(t); if (i < 0) return;
  try { if (t.h && t.h.dispose) t.h.dispose(); } catch (_) {}
  if (t.ownsUrl && t.url) { try { URL.revokeObjectURL(t.url); } catch (_) {} }
  t.pane.remove(); tabs.splice(i, 1);
  if (cur === t) activate(tabs[i] || tabs[i - 1] || 'home'); else renderTabStrip();
}

/* ------------------------------------------------------------------ toolbar */
const TB = {
  page: ['pageIn', 'tPrev', 'tNext'], fit: ['tFitW', 'tFitP'], rot: ['tRotL', 'tRotR'], zoom: ['tZOut', 'tZIn'], find: ['findIn', 'tFPrev', 'tFNext', 'tCase'],
};
let caseSens = false;
function updateToolbar() {
  const h = cur !== 'home' && cur.h ? cur.h : null;
  const caps = (h && h.caps) || [];
  Object.keys(TB).forEach((k) => TB[k].forEach((id) => { $(id).disabled = !caps.includes(k); }));
  $('tPrint').disabled = !(h && caps.includes('print'));
  $('tOpen').disabled = false;
  $('pageLbl').style.opacity = $('pageOf').style.opacity = caps.includes('page') ? 1 : .35;
  $('findLbl').style.opacity = caps.includes('find') ? 1 : .35;
  refreshInfo();
}
function refreshInfo() {
  const h = cur !== 'home' && cur.h ? cur.h : null;
  const i = h && h.info ? h.info() : null;
  const pg = $('pageIn');
  if (document.activeElement !== pg) pg.value = i && i.pages ? String(i.page) : '';
  $('pageOf').textContent = i && i.pages ? '/ ' + i.pages : '';
  $('zv').textContent = i && i.zoom ? Math.round(i.zoom * 100) + '%' : '';
}
const H = () => (cur !== 'home' && cur.h ? cur.h : null);
$('tOpen').onclick = () => pickFiles();
$('tPrint').onclick = () => { const h = H(); if (h && h.print) h.print(); };
$('tPrev').onclick = () => { const h = H(); if (h && h.prev) h.prev(); refreshInfo(); };
$('tNext').onclick = () => { const h = H(); if (h && h.next) h.next(); refreshInfo(); };
$('tFitW').onclick = () => { const h = H(); if (h && h.fit) h.fit('w'); refreshInfo(); };
$('tFitP').onclick = () => { const h = H(); if (h && h.fit) h.fit('p'); refreshInfo(); };
$('tRotL').onclick = () => { const h = H(); if (h && h.rotate) h.rotate(-90); };
$('tRotR').onclick = () => { const h = H(); if (h && h.rotate) h.rotate(90); };
$('tZOut').onclick = () => { const h = H(); if (h && h.zoom) h.zoom(-1); refreshInfo(); };
$('tZIn').onclick = () => { const h = H(); if (h && h.zoom) h.zoom(1); refreshInfo(); };
$('tCase').onclick = () => { caseSens = !caseSens; $('tCase').classList.toggle('on', caseSens); runFind('new'); };
$('tFNext').onclick = () => runFind(1);
$('tFPrev').onclick = () => runFind(-1);
$('pageIn').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { const h = H(); const n = parseInt($('pageIn').value, 10); if (h && h.goto && n > 0) h.goto(n); $('pageIn').blur(); refreshInfo(); }
  e.stopPropagation();
});
$('pageIn').addEventListener('blur', refreshInfo);
$('findIn').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); runFind(e.shiftKey ? -1 : 1); } e.stopPropagation(); });
$('findIn').addEventListener('input', () => runFind('new'));
function runFind(dir) { const h = H(); if (!h || !h.find) return; h.find($('findIn').value, dir, caseSens); }
setInterval(() => { if (cur !== 'home') refreshInfo(); }, 400);

/* generic text find for in-page documents (Chromium's window.find) */
function pageFind(q, dir, cs) {
  if (!q) { try { getSelection().removeAllRanges(); } catch (_) {} return; }
  const back = dir === -1;
  try { if (!window.find(q, !!cs, back, true, false, false, false)) msg('"' + q + '" paoa jayni'); } catch (_) {}
}

/* ------------------------------------------------------------------ pane factory */
function newPane(cls) { const p = el('div', 'pane ' + (cls || '')); $('panes').appendChild(p); return p; }
function scrollHandler(t, getUnits, unitSel) {
  /* page navigation for documents that scroll (Word pages / PowerPoint slides) */
  const units = () => [...t.pane.querySelectorAll(unitSel)];
  const curIdx = () => {
    const u = units(); if (!u.length) return 0;
    const top = t.pane.getBoundingClientRect().top + 40;
    for (let i = 0; i < u.length; i++) if (u[i].getBoundingClientRect().bottom > top) return i;
    return u.length - 1;
  };
  const go = (i) => { const u = units(); if (!u.length) return; i = clamp(i, 0, u.length - 1); const r = u[i].getBoundingClientRect(); t.pane.scrollTop += r.top - t.pane.getBoundingClientRect().top - 10; };
  return { count: () => units().length, idx: curIdx, go };
}

/* ------------------------------------------------------------------ renderers */
const R = {};

/* ---- PDF: pdf.js viewer in an iframe, with its own toolbar hidden (ours drives it) ---- */
R.pdf = async (t) => {
  t.pane.classList.add('fixed');
  const fr = document.createElement('iframe'); fr.setAttribute('allowfullscreen', '');
  t.pane.appendChild(fr);
  let app = null, bus = null, pages = 0, page = 1, scale = 1;
  const ready = new Promise((res, rej) => {
    fr.addEventListener('load', async () => {
      try {
        const w = fr.contentWindow; app = w.PDFViewerApplication; await app.initializedPromise; bus = app.eventBus;
        const lk = w.document.createElement('link'); lk.rel = 'stylesheet'; lk.href = 'rt-embed.css';   // (pdf.js CSP allows same-origin stylesheets only, no inline <style>)
        w.document.head.appendChild(lk);
        bus.on('pagechanging', (e) => { page = e.pageNumber; });
        bus.on('scalechanging', (e) => { scale = e.scale; });
        bus.on('documentloaded', () => { pages = app.pagesCount; page = app.page || 1; scale = app.pdfViewer.currentScale; });
        // keys pressed while focus is inside the PDF still drive the reader
        w.document.addEventListener('keydown', (e) => { if (forwardKey(e)) { e.preventDefault(); e.stopPropagation(); } }, true);
        res();
      } catch (e) { rej(e); }
    });
  });
  fr.src = '../pdfjs/web/viewer.html';   // no ?file=: pdf.js rejects blob: URLs on a custom scheme (origin check), so the bytes are handed over directly
  try { await ready; } catch (e) { throw new Error('PDF viewer start hoyni'); }
  try { await app.open({ data: new Uint8Array(await readBuf(t.blob)), originalUrl: t.name }); } catch (e) { throw new Error('PDF ta khola jayni (' + ((e && e.message) || e) + ')'); }
  const pv = () => app.pdfViewer;
  t.h = {
    caps: ['page', 'fit', 'rot', 'zoom', 'find', 'print'],
    info: () => ({ page, pages, zoom: scale }),
    prev: () => { if (app.page > 1) app.page--; },
    next: () => { if (app.page < app.pagesCount) app.page++; },
    goto: (n) => { app.page = clamp(n, 1, app.pagesCount || 1); },
    fit: (m) => { pv().currentScaleValue = m === 'w' ? 'page-width' : 'page-fit'; },
    zoom: (d) => { d > 0 ? app.zoomIn() : app.zoomOut(); },
    rotate: (d) => { try { app.rotatePages(d); } catch (_) {} },
    find: (q, dir, cs) => {
      if (!q) { bus.dispatch('findbarclose', { source: window }); return; }
      bus.dispatch('find', { source: window, type: dir === 'new' ? '' : 'again', query: q, caseSensitive: !!cs, entireWord: false, highlightAll: true, findPrevious: dir === -1, matchDiacritics: false });
    },
    print: () => { try { app.triggerPrinting ? app.triggerPrinting() : fr.contentWindow.print(); } catch (_) { fr.contentWindow.print(); } },
    onShow: () => { try { fr.contentWindow.focus(); } catch (_) {} },
    focus: () => { try { fr.contentWindow.focus(); } catch (_) {} },
    dispose: () => { try { fr.src = 'about:blank'; } catch (_) {} },
  };
  return true;
};

/* ---- Word ---- */
R.docx = async (t) => {
  t.pane.classList.add('doc');
  const inner = el('div', 'inner'); t.pane.appendChild(inner);
  await docx.renderAsync(await readBuf(t.blob), inner, null, { className: 'docx', inWrapper: true, breakPages: true, ignoreLastRenderedPageBreak: false });
  let z = 1;
  /* docx-preview only breaks pages at explicit page breaks, so a long flowing section is split into virtual pages of one paper-height */
  const vpages = () => {
    const out = [], pt = t.pane.getBoundingClientRect().top;
    inner.querySelectorAll('section.docx').forEach((s) => {
      const r = s.getBoundingClientRect(), ph = (parseFloat(getComputedStyle(s).minHeight) || 1056) * z, n = Math.max(1, Math.round(r.height / ph));
      for (let i = 0; i < n; i++) out.push(r.top - pt + t.pane.scrollTop + i * ph);
    });
    return out;
  };
  const sc = {
    count: () => vpages().length,
    idx: () => { const v = vpages(); let k = 0; for (let i = 0; i < v.length; i++) if (v[i] <= t.pane.scrollTop + 60) k = i; return k; },
    go: (i) => { const v = vpages(); if (v.length) t.pane.scrollTop = v[clamp(i, 0, v.length - 1)] - 10; },
  };
  const setZ = (v) => { z = clamp(v, 0.25, 3); inner.style.zoom = z; };
  const fitW = () => { inner.style.zoom = 1; const s = inner.querySelector('section.docx'); const w = s ? s.getBoundingClientRect().width : 0; setZ(w ? Math.min(1.6, (t.pane.clientWidth - 40) / w) : 1); };
  t.h = {
    caps: ['page', 'fit', 'zoom', 'find', 'print'],
    info: () => ({ page: sc.idx() + 1, pages: sc.count(), zoom: z }),
    prev: () => sc.go(sc.idx() - 1), next: () => sc.go(sc.idx() + 1), goto: (n) => sc.go(n - 1),
    fit: (m) => { if (m === 'w') fitW(); else { inner.style.zoom = 1; const s = inner.querySelector('section.docx'); const r = s ? s.getBoundingClientRect() : null; setZ(r ? Math.min((t.pane.clientHeight - 30) / r.height, (t.pane.clientWidth - 40) / r.width) : 1); } },
    zoom: (d) => setZ(z + d * 0.1), find: pageFind, print: () => window.print(), onShow: () => {},
    wheelZoom: (d) => setZ(z + d * 0.1),
  };
  setTimeout(fitW, 60);
};

/* ---- Excel / CSV ---- */
R.xlsx = async (t) => {
  const box = el('div', 'sheetbox'); t.pane.classList.add('fixed'); t.pane.appendChild(box);
  const sh = el('div', 'sh'), tabsEl = el('div', 'sheettabs'); box.append(sh, tabsEl);
  const wb = XLSX.read(await readBuf(t.blob), { type: 'array', cellDates: true });
  if (!wb.SheetNames.length) throw new Error('khali workbook');
  let idx = 0, z = 1;
  const draw = (i) => {
    idx = clamp(i, 0, wb.SheetNames.length - 1);
    const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[idx]], { header: 1, raw: false, defval: '' });
    let cols = 0; rows.forEach((r) => { if (r.length > cols) cols = r.length; });
    cols = Math.min(Math.max(cols, 1), MAX_COLS);
    const shown = Math.min(rows.length, MAX_ROWS);
    const wrap = el('div', 'sheet'); const tb = document.createElement('table');
    const hr = tb.insertRow(); const cn = document.createElement('th'); cn.className = 'corner'; hr.appendChild(cn);
    for (let c = 0; c < cols; c++) { const th = document.createElement('th'); th.textContent = XLSX.utils.encode_col(c); hr.appendChild(th); }
    for (let r = 0; r < shown; r++) { const tr = tb.insertRow(); const rn = tr.insertCell(); rn.className = 'rn'; rn.textContent = String(r + 1); for (let c = 0; c < cols; c++) tr.insertCell().textContent = rows[r][c] == null ? '' : String(rows[r][c]); }
    wrap.appendChild(tb);
    if (rows.length > shown) wrap.appendChild(el('div', 'note', 'Prothom ' + MAX_ROWS + ' row dekhano hocche (' + rows.length + ' ta row ache).'));
    wrap.style.zoom = z; sh.textContent = ''; sh.appendChild(wrap); sh.scrollTop = sh.scrollLeft = 0;
    [...tabsEl.children].forEach((b, k) => b.classList.toggle('on', k === idx));
  };
  wb.SheetNames.forEach((n, i) => { const b = el('button'); b.textContent = n; b.onclick = () => draw(i); tabsEl.appendChild(b); });
  if (wb.SheetNames.length < 2) tabsEl.style.display = 'none';
  draw(0);
  const setZ = (v) => { z = clamp(v, 0.3, 3); const w = sh.querySelector('.sheet'); if (w) w.style.zoom = z; };
  t.h = {
    caps: ['page', 'fit', 'zoom', 'find', 'print'],
    info: () => ({ page: idx + 1, pages: wb.SheetNames.length, zoom: z }),
    prev: () => draw(idx - 1), next: () => draw(idx + 1), goto: (n) => draw(n - 1),
    fit: () => setZ(1), zoom: (d) => setZ(z + d * 0.1), find: pageFind, print: () => window.print(), onShow: () => {},
    wheelZoom: (d) => setZ(z + d * 0.1),
  };
};

/* ---- PowerPoint ---- */
R.pptx = async (t) => {
  t.pane.classList.add('doc');
  const inner = el('div', 'inner'); t.pane.appendChild(inner);
  const pv = pptxPreview.init(inner, { width: 960, height: 540, mode: 'list' });
  await pv.preview(await readBuf(t.blob));
  let z = 1; const sc = scrollHandler(t, null, '.pptx-preview-slide-wrapper');
  const setZ = (v) => { z = clamp(v, 0.25, 3); inner.style.zoom = z; };
  const fitW = () => { inner.style.zoom = 1; const s = inner.querySelector('.pptx-preview-slide-wrapper'); const w = s ? s.getBoundingClientRect().width : 960; setZ(Math.min(1.8, (t.pane.clientWidth - 40) / w)); };
  t.h = {
    caps: ['page', 'fit', 'zoom', 'find', 'print'],
    info: () => ({ page: sc.idx() + 1, pages: sc.count(), zoom: z }),
    prev: () => sc.go(sc.idx() - 1), next: () => sc.go(sc.idx() + 1), goto: (n) => sc.go(n - 1),
    fit: (m) => { if (m === 'w') fitW(); else { inner.style.zoom = 1; const s = inner.querySelector('.pptx-preview-slide-wrapper'); const r = s ? s.getBoundingClientRect() : null; setZ(r ? Math.min((t.pane.clientHeight - 30) / r.height, (t.pane.clientWidth - 40) / r.width) : 1); } },
    zoom: (d) => setZ(z + d * 0.1), find: pageFind, print: () => window.print(), onShow: () => {},
    wheelZoom: (d) => setZ(z + d * 0.1),
  };
  setTimeout(fitW, 80);
};

/* ---- images ---- */
R.image = async (t) => {
  t.pane.classList.add('imgpane');
  const sb = el('div', 'stagebox'); const wrap = el('div'); wrap.style.cssText = 'position:relative;flex-shrink:0'; const img = new Image();
  img.style.cssText = 'position:absolute;left:50%;top:50%;'; img.draggable = false;
  wrap.appendChild(img); sb.appendChild(wrap); t.pane.appendChild(sb);
  await new Promise((res, rej) => { img.onload = res; img.onerror = () => rej(new Error('Image khola jayni')); img.src = t.url; });
  const nw = img.naturalWidth || 300, nh = img.naturalHeight || 300;
  let z = 1, rot = 0;
  const apply = () => {
    const sw = rot % 180 ? nh : nw, shh = rot % 180 ? nw : nh;
    wrap.style.width = Math.round(sw * z) + 'px'; wrap.style.height = Math.round(shh * z) + 'px';
    img.style.width = Math.round(nw * z) + 'px'; img.style.height = Math.round(nh * z) + 'px';
    img.style.transform = 'translate(-50%,-50%) rotate(' + rot + 'deg)';
  };
  const fit = (m) => {
    const sw = rot % 180 ? nh : nw, shh = rot % 180 ? nw : nh;
    const aw = t.pane.clientWidth - 40, ah = t.pane.clientHeight - 40;
    z = m === 'w' ? aw / sw : Math.min(aw / sw, ah / shh);
    z = clamp(z, 0.05, 8); apply();
  };
  const setZ = (v) => { z = clamp(v, 0.05, 8); apply(); };
  t.h = {
    caps: ['page', 'fit', 'rot', 'zoom', 'print'],
    info: () => ({ page: 1, pages: 1, zoom: z }),
    prev: () => {}, next: () => {}, goto: () => {},
    fit, zoom: (d) => setZ(z * (d > 0 ? 1.2 : 1 / 1.2)), rotate: (d) => { rot = (rot + d + 360) % 360; apply(); fit('p'); },
    print: () => window.print(), onShow: () => {},
    wheelZoom: (d) => setZ(z * (d > 0 ? 1.12 : 1 / 1.12)),
  };
  fit('p'); if (z > 1) setZ(1);
};

/* ---- video / audio ---- */
function mediaRenderer(tag) {
  return async (t) => {
    t.pane.classList.add('mediapane', 'fixed');
    const m = document.createElement(tag); m.controls = true; m.preload = 'metadata'; m.src = t.url;
    if (tag === 'audio') { const box = el('div', 'aud', '<div style="font-size:64px">🎵</div>'); const n = el('div'); n.textContent = t.name; box.append(n, m); t.pane.appendChild(box); }
    else t.pane.appendChild(m);
    m.addEventListener('error', () => msg('Ei ' + (tag === 'video' ? 'video' : 'audio') + ' format/codec ei reader e chole na (' + extOf(t.name).toUpperCase() + ').'));
    t.h = {
      caps: tag === 'video' ? ['fit'] : [],
      info: () => null, fit: () => { try { m.requestFullscreen(); } catch (_) {} },
      onShow: () => {}, focus: () => { try { m.focus(); } catch (_) {} },
      dispose: () => { try { m.pause(); m.removeAttribute('src'); m.load(); } catch (_) {} },
    };
  };
}
R.video = mediaRenderer('video');
R.audio = mediaRenderer('audio');

/* ---- Markdown / text ---- */
function sanitize(html) {
  const d = new DOMParser().parseFromString(html, 'text/html');
  d.querySelectorAll('script,iframe,object,embed,link,meta,style,form,base').forEach((n) => n.remove());
  d.querySelectorAll('*').forEach((n) => {
    [...n.attributes].forEach((a) => {
      const k = a.name.toLowerCase(), v = String(a.value).trim().toLowerCase();
      if (k.startsWith('on') || ((k === 'href' || k === 'src' || k === 'xlink:href') && /^(javascript|vbscript|data:text\/html)/.test(v))) n.removeAttribute(a.name);
    });
    if (n.tagName === 'A') { n.setAttribute('rel', 'noopener'); n.removeAttribute('target'); }
  });
  return d.body.innerHTML;
}
function textHandler(t, host) {
  let z = 1;
  const setZ = (v) => { z = clamp(v, 0.5, 3); host.style.zoom = z; };
  t.h = { caps: ['fit', 'zoom', 'find', 'print'], info: () => ({ zoom: z }), fit: () => setZ(1), zoom: (d) => setZ(z + d * 0.1), find: pageFind, print: () => window.print(), onShow: () => {}, wheelZoom: (d) => setZ(z + d * 0.1) };
}
R.md = async (t) => {
  t.pane.classList.add('mdpane');
  const box = el('div', 'md'); const raw = await readText(t.blob);
  box.innerHTML = sanitize(marked.parse(raw, { gfm: true, breaks: false }));
  const wrap = el('div'); wrap.style.padding = '20px 0'; wrap.appendChild(box); t.pane.appendChild(wrap);
  textHandler(t, wrap);
};
R.text = async (t) => {
  const pre = el('pre', 'txt'); pre.textContent = await readText(t.blob);
  t.pane.appendChild(pre); textHandler(t, pre);
};
R.olddoc = async () => { throw new Error('Purano .doc / .ppt format support kora hoy na. Word/PowerPoint e file ta khule .docx / .pptx hishebe Save As kore abar khulun.'); };

/* ------------------------------------------------------------------ open a file */
async function openBlob(blob, name, opt) {
  opt = opt || {};
  name = baseName(name || blob.name || 'file');
  const kind = kindOf(name);
  if (!kind) { msg('Ei file type (.' + (extOf(name) || '?') + ') support kora hoy na.'); return null; }
  // already open? just switch to it
  const dup = tabs.find((x) => x.name === name && x.size === blob.size);
  if (dup && !opt.forceNew) { activate(dup); return dup; }
  const t = { id: ++seq, name, kind, size: blob.size, blob, url: opt.url || '', ownsUrl: false, pane: newPane(), h: null, libId: '' };
  if (!t.url && (kind === 'image' || kind === 'video' || kind === 'audio')) { t.url = URL.createObjectURL(blob); t.ownsUrl = true; }
  tabs.push(t); activate(t);
  try {
    await R[kind](t);
  } catch (e) {
    closeTab(t); msg('File ta khola jayni: ' + ((e && e.message) || e)); return null;
  }
  updateToolbar();
  if (t.h && t.h.focus) setTimeout(() => t.h.focus(), 50);
  if (!opt.noLib) {
    libTouch(name, kind, blob).then((rec) => {
      t.libId = rec.id; libPrune();
      if (!rec.thumb) makeThumb(t).then((th) => { if (th) libThumb(rec.id, th); }).catch(() => {});
    });
  }
  return t;
}
async function openUrl(url, name, kindHint) {
  try {
    name = baseName(name || decodeURIComponent(url.split('/').pop().split('?')[0]));
    const kind = kindOf(name) || kindHint;
    if (kind === 'video' || kind === 'audio') {          // stream big media straight from the (Range-capable) URL, no copy into memory
      const t = { id: ++seq, name, kind, size: 0, blob: null, url, ownsUrl: false, pane: newPane(), h: null, libId: '' };
      tabs.push(t); activate(t);
      try { await R[kind](t); } catch (e) { closeTab(t); msg(String((e && e.message) || e)); }
      updateToolbar(); return t;
    }
    const r = await fetch(url); if (!r.ok) throw new Error('read failed');
    const b = await r.blob();
    return openBlob(new File([b], name, { type: b.type }), name);
  } catch (e) { msg('File ta khola jayni: ' + ((e && e.message) || e)); return null; }
}
async function openFiles(list) {
  const arr = [...(list || [])];
  for (const f of arr) await openBlob(f, f.name);
}

/* ------------------------------------------------------------------ thumbnails for the Home grid */
const TW = 424, TH = 300;
function canvasFrom(w, h) { const c = document.createElement('canvas'); c.width = w; c.height = h; return c; }
function drawCover(ctx, src, sw, sh, topAlign) {
  const s = Math.max(TW / sw, TH / sh); const dw = sw * s, dh = sh * s;
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, TW, TH);
  ctx.drawImage(src, (TW - dw) / 2, topAlign ? 0 : (TH - dh) / 2, dw, dh);
}
let pdfLib = null;
async function loadPdfLib() {
  if (pdfLib) return pdfLib;
  pdfLib = import('../pdfjs/build/pdf.mjs').then((m) => { m.GlobalWorkerOptions.workerSrc = new URL('../pdfjs/build/pdf.worker.mjs', location.href).href; return m; });
  return pdfLib;
}
async function makeThumb(t) {
  const b = t.blob; if (!b) return '';
  try {
    if (t.kind === 'image') {
      const url = URL.createObjectURL(b);
      try {
        const img = await new Promise((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = url; });
        const c = canvasFrom(TW, TH), x = c.getContext('2d'); drawCover(x, img, img.naturalWidth || TW, img.naturalHeight || TH, false);
        return c.toDataURL('image/jpeg', 0.72);
      } finally { URL.revokeObjectURL(url); }
    }
    if (t.kind === 'pdf') {
      const lib = await loadPdfLib();
      const doc = await lib.getDocument({ data: new Uint8Array(await readBuf(b)), standardFontDataUrl: new URL('../pdfjs/web/standard_fonts/', location.href).href, cMapUrl: new URL('../pdfjs/web/cmaps/', location.href).href, cMapPacked: true, isEvalSupported: false }).promise;
      const pg = await doc.getPage(1); const v0 = pg.getViewport({ scale: 1 }); const s = TW / v0.width;
      const vp = pg.getViewport({ scale: s }); const c = canvasFrom(Math.ceil(vp.width), Math.ceil(Math.min(vp.height, TH * 2)));
      const x = c.getContext('2d'); x.fillStyle = '#fff'; x.fillRect(0, 0, c.width, c.height);
      await pg.render({ canvasContext: x, viewport: vp }).promise;
      try { doc.destroy(); } catch (_) {}
      const o = canvasFrom(TW, TH), ox = o.getContext('2d'); ox.fillStyle = '#fff'; ox.fillRect(0, 0, TW, TH); ox.drawImage(c, 0, 0);
      return o.toDataURL('image/jpeg', 0.72);
    }
    if (t.kind === 'video') {
      const url = t.url || URL.createObjectURL(b);
      try {
        const v = document.createElement('video'); v.muted = true; v.preload = 'auto'; v.src = url;
        await new Promise((res, rej) => { v.onloadeddata = res; v.onerror = rej; setTimeout(rej, 5000); });
        v.currentTime = Math.min(1, (v.duration || 2) / 10);
        await new Promise((res, rej) => { v.onseeked = res; setTimeout(res, 2500); });
        const c = canvasFrom(TW, TH), x = c.getContext('2d'); x.fillStyle = '#000'; x.fillRect(0, 0, TW, TH);
        const s = Math.min(TW / v.videoWidth, TH / v.videoHeight); x.drawImage(v, (TW - v.videoWidth * s) / 2, (TH - v.videoHeight * s) / 2, v.videoWidth * s, v.videoHeight * s);
        return c.toDataURL('image/jpeg', 0.7);
      } finally { if (!t.url) URL.revokeObjectURL(url); }
    }
  } catch (_) {}
  return '';   // other types use a generated type card
}

/* ------------------------------------------------------------------ Home (Frequently Read) */
let hideFreq = false;
try { hideFreq = localStorage.getItem('rt_hidefreq') === '1'; } catch (_) {}
async function renderHome() {
  const grid = $('grid'); const home = $('home');
  let all = await dbAll();
  all = all.filter((r) => r.kind && BADGE[r.kind]);
  all.sort((a, b) => ((b.count || 0) - (a.count || 0)) || ((b.last || 0) - (a.last || 0)));
  all = all.slice(0, 15);
  home.classList.toggle('noitems', !all.length);
  home.classList.toggle('hidefreq', hideFreq);
  $('hideFreq').textContent = hideFreq ? 'Show frequently read' : 'Hide frequently read';
  grid.textContent = '';
  all.forEach((r) => {
    const [lbl, col] = BADGE[r.kind] || ['FILE', '#555'];
    const c = el('div', 'card'); c.title = r.name + (r.count ? '  ·  ' + r.count + 'x' : '');
    const th = el('div', 'th');
    if (r.thumb) { const im = new Image(); im.src = r.thumb; im.draggable = false; if (r.kind === 'video') im.className = 'fit'; th.appendChild(im); }
    else { const g = el('div', 'gen'); g.style.background = col; g.innerHTML = '<b>' + lbl + '</b><i>.' + extOf(r.name) + '</i>'; th.appendChild(g); }
    const rm = el('button', 'rm', '&times;'); rm.title = 'List theke soriye din';
    rm.onclick = async (e) => { e.stopPropagation(); await dbDel(r.id); renderHome(); };
    th.appendChild(rm);
    const nm = el('div', 'nm'); const bd = el('span', 'badge'); bd.textContent = lbl; bd.style.background = col;
    const n = el('span', 'n'); n.textContent = r.name; nm.append(bd, n);
    c.append(th, nm);
    c.onclick = async () => {
      const rec = await dbGet(r.id);
      if (rec && rec.blob) { const f = rec.blob instanceof File ? rec.blob : new File([rec.blob], rec.name, { type: rec.blob.type }); openBlob(f, rec.name); }
      else { msg('"' + r.name + '" ei file ta list e rakha nei (boro file). File ta abar select korun.'); pickFiles(); }
    };
    grid.appendChild(c);
  });
}
$('hideFreq').onclick = (e) => { e.preventDefault(); hideFreq = !hideFreq; try { localStorage.setItem('rt_hidefreq', hideFreq ? '1' : '0'); } catch (_) {} renderHome(); };
$('openDoc').onclick = (e) => { e.preventDefault(); pickFiles(); };

/* ------------------------------------------------------------------ pick / drag-drop / menu */
function pickFiles() { $('fi').click(); }
$('fi').onchange = (e) => { const l = [...e.target.files]; e.target.value = ''; openFiles(l); };
let depth = 0;
const stage = $('stage');
window.addEventListener('dragenter', (e) => { e.preventDefault(); depth++; stage.classList.add('drag'); });
window.addEventListener('dragover', (e) => e.preventDefault());
window.addEventListener('dragleave', () => { if (--depth <= 0) { depth = 0; stage.classList.remove('drag'); } });
window.addEventListener('drop', (e) => { e.preventDefault(); depth = 0; stage.classList.remove('drag'); openFiles(e.dataTransfer.files); });

const menu = $('menu');
$('menuBtn').onclick = (e) => { e.stopPropagation(); menu.classList.toggle('hide'); };
document.addEventListener('click', (e) => { if (!menu.contains(e.target)) menu.classList.add('hide'); });
menu.addEventListener('click', async (e) => {
  const b = e.target.closest('button'); if (!b) return; menu.classList.add('hide');
  const a = b.dataset.a;
  if (a === 'open') pickFiles();
  else if (a === 'close') { if (cur !== 'home') closeTab(cur); }
  else if (a === 'print') { const h = H(); if (h && h.print) h.print(); }
  else if (a === 'clear') { await dbClear(); renderHome(); }
});
document.addEventListener('click', (e) => { const a = e.target.closest && e.target.closest('a'); if (a && !a.hasAttribute('data-ok')) e.preventDefault(); }, true);   // links inside documents never navigate the reader

/* ------------------------------------------------------------------ keyboard / wheel */
function nextTab(d) { const all = ['home'].concat(tabs); const i = all.indexOf(cur); activate(all[(i + d + all.length) % all.length]); }
function forwardKey(e) {
  const c = e.ctrlKey || e.metaKey, k = e.key;
  if (c && (k === 'o' || k === 'O')) { pickFiles(); return true; }
  if (c && (k === 'w' || k === 'W' || k === 'F4')) { if (cur !== 'home') closeTab(cur); return true; }
  if (c && k === 'Tab') { nextTab(e.shiftKey ? -1 : 1); return true; }
  if (c && (k === 'f' || k === 'F')) { if (!$('findIn').disabled) { $('findIn').focus(); $('findIn').select(); } return true; }
  if (k === 'F3') { runFind(e.shiftKey ? -1 : 1); return true; }
  if (c && /^[1-9]$/.test(k)) { const all = ['home'].concat(tabs); activate(all[Math.min(+k - 1, all.length - 1)]); return true; }
  return false;
}
document.addEventListener('keydown', (e) => {
  if (forwardKey(e)) { e.preventDefault(); return; }
  const h = H(); const c = e.ctrlKey || e.metaKey; const k = e.key;
  if (k === 'Escape') { menu.classList.add('hide'); return; }
  if (!h) return;
  if (c && (k === '+' || k === '=')) { e.preventDefault(); h.zoom && h.zoom(1); refreshInfo(); }
  else if (c && (k === '-' || k === '_')) { e.preventDefault(); h.zoom && h.zoom(-1); refreshInfo(); }
  else if (c && k === '0') { e.preventDefault(); h.fit && h.fit('w'); refreshInfo(); }
  else if (c && (k === 'p' || k === 'P')) { e.preventDefault(); h.print && h.print(); }
  else if (!c && (k === 'PageDown' || k === 'PageUp') && (cur.kind === 'image' || cur.kind === 'xlsx')) { e.preventDefault(); k === 'PageDown' ? h.next() : h.prev(); refreshInfo(); }
}, true);
document.addEventListener('wheel', (e) => {
  if (!e.ctrlKey) return; const h = H(); if (!h || !h.wheelZoom) return;
  e.preventDefault(); h.wheelZoom(e.deltaY < 0 ? 1 : -1); refreshInfo();
}, { passive: false });
/* Alt+Left / Alt+Right etc. do nothing; stop the default browser back/forward gestures */
window.addEventListener('keydown', (e) => { if (e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) e.preventDefault(); });
window.addEventListener('resize', refreshInfo);

/* ------------------------------------------------------------------ host bridge + boot */
// index.html (the host page) calls this when Windows / the host asks us to open another file, so open tabs are never lost.
window.__rtOpen = (url, kind, name) => openUrl(url, name, kind);
async function boot() {
  renderTabStrip(); updateToolbar(); renderHome();
  const q = new URLSearchParams(location.search);
  if (q.get('file')) await openUrl(q.get('file'), q.get('name') || ('file.' + (q.get('type') || '')), q.get('type') || '');
}
boot();
