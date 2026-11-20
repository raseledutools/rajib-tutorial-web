'use strict';
// Office Reader: Word (.docx), Excel (.xlsx/.xls/.csv) and PowerPoint (.pptx), rendered locally (nothing is uploaded).
const $ = (id) => document.getElementById(id);
const THEME = { docx: ['W', '#2b579a'], xlsx: ['X', '#217346'], xls: ['X', '#217346'], csv: ['X', '#217346'], pptx: ['P', '#c43e1c'] };
const MAX_ROWS = 3000, MAX_COLS = 200;
let zoom = 1, kind = '', wb = null;

const extOf = (n) => { const m = /\.([a-z0-9]+)$/i.exec(n || ''); return m ? m[1].toLowerCase() : ''; };
function msg(t) { const m = $('msg'); m.textContent = t || ''; m.style.display = t ? 'block' : 'none'; }
function applyZoom() { $('content').style.zoom = zoom; $('zv').textContent = Math.round(zoom * 100) + '%'; }
function setZoom(z) { zoom = Math.min(3, Math.max(0.25, z)); applyZoom(); }
function theme(k, name) {
  const t = THEME[k] || ['O', '#374151'];
  $('bar').style.background = t[1]; $('badge').textContent = t[0]; $('badge').style.color = t[1];
  $('fname').textContent = name || 'Office Reader'; document.title = (name || 'Office Reader');
  $('tabs').style.display = 'none'; $('tabs').textContent = '';
}
function clearView() { $('content').textContent = ''; $('content').style.zoom = 1; zoom = 1; applyZoom(); msg(''); wb = null; }

async function showDocx(buf) {
  await docx.renderAsync(buf, $('content'), null, { className: 'docx', inWrapper: true, breakPages: true, ignoreLastRenderedPageBreak: false });
}
function renderSheet(name) {
  const ws = wb.Sheets[name];
  const rows = XLSX.utils.sheet_to_json(ws, { header: 1, raw: false, defval: '' });
  let cols = 0; rows.forEach((r) => { if (r.length > cols) cols = r.length; });
  cols = Math.min(Math.max(cols, 1), MAX_COLS);
  const shown = Math.min(rows.length, MAX_ROWS);
  const wrap = document.createElement('div'); wrap.className = 'sheet';
  const tb = document.createElement('table');
  const hr = tb.insertRow(); const corner = document.createElement('th'); corner.className = 'corner'; hr.appendChild(corner);
  for (let c = 0; c < cols; c++) { const th = document.createElement('th'); th.textContent = XLSX.utils.encode_col(c); hr.appendChild(th); }
  for (let r = 0; r < shown; r++) {
    const tr = tb.insertRow(); const rn = tr.insertCell(); rn.className = 'rn'; rn.textContent = String(r + 1);
    for (let c = 0; c < cols; c++) { tr.insertCell().textContent = rows[r][c] == null ? '' : String(rows[r][c]); }
  }
  wrap.appendChild(tb);
  if (rows.length > shown) { const n = document.createElement('div'); n.className = 'note'; n.textContent = 'Prothom ' + MAX_ROWS + ' row dekhano hocche (' + rows.length + ' ta row ache).'; wrap.appendChild(n); }
  const box = $('content'); box.textContent = ''; box.appendChild(wrap);
  $('main').scrollTop = 0; $('main').scrollLeft = 0;
  [...$('tabs').children].forEach((b) => b.classList.toggle('on', b.dataset.n === name));
}
function showSheets(buf) {
  wb = XLSX.read(buf, { type: 'array', cellDates: true });
  const tabs = $('tabs'); tabs.textContent = ''; tabs.style.display = 'flex';
  wb.SheetNames.forEach((n) => { const b = document.createElement('button'); b.textContent = n; b.dataset.n = n; b.onclick = () => renderSheet(n); tabs.appendChild(b); });
  if (!wb.SheetNames.length) throw new Error('empty workbook');
  renderSheet(wb.SheetNames[0]);
}
async function showPptx(buf) {
  const box = $('content');
  const pv = pptxPreview.init(box, { width: 960, height: 540, mode: 'list' });
  await pv.preview(buf);
}

async function show(buf, name) {
  const k = extOf(name);
  clearView(); theme(k, name); kind = k; $('landing').classList.add('hide');
  try {
    if (k === 'docx') await showDocx(buf);
    else if (k === 'xlsx' || k === 'xls' || k === 'csv') showSheets(buf);
    else if (k === 'pptx') await showPptx(buf);
    else if (k === 'doc' || k === 'ppt') msg('Purano .' + k + ' format support kora hoy na. Word/PowerPoint e file ta khule .' + k + 'x (docx/pptx) hishebe Save As kore abar khulun.');
    else if (k === 'pdf') msg('PDF file ta host bar er "Open file" button diye khulun.');
    else msg('Ei file type support kora hoy na.');
  } catch (e) { msg('File ta khola jayni: ' + ((e && e.message) || e)); }
  if (k === 'pptx' || k === 'docx') setTimeout(fit, 60);
}
function fit() {
  if (kind === 'xlsx' || kind === 'xls' || kind === 'csv') { setZoom(1); return; }
  const box = $('content'); box.style.zoom = 1;
  const el = box.querySelector('section.docx') || box.querySelector('.pptx-preview-wrapper') || box.firstElementChild;
  const w = el ? el.getBoundingClientRect().width : 0;
  const avail = $('main').clientWidth - 40;
  setZoom(w > 0 ? Math.min(1.5, avail / w) : 1);
}

async function loadFromUrl(url, name) {
  try { const r = await fetch(url); if (!r.ok) throw new Error('read failed'); await show(await r.arrayBuffer(), name); }
  catch (e) { clearView(); $('landing').classList.add('hide'); msg('File ta khola jayni: ' + ((e && e.message) || e)); }
}
function loadFile(f) { if (!f) return; const rd = new FileReader(); rd.onload = () => show(rd.result, f.name); rd.readAsArrayBuffer(f); }

$('zin').onclick = () => setZoom(zoom + 0.1);
$('zout').onclick = () => setZoom(zoom - 0.1);
$('zfit').onclick = fit;
$('print').onclick = () => window.print();
const pick = () => $('fi').click();
$('open').onclick = pick; $('open2').onclick = pick;
$('fi').onchange = (e) => { loadFile(e.target.files[0]); e.target.value = ''; };
document.addEventListener('click', (e) => { const a = e.target.closest && e.target.closest('a'); if (a) e.preventDefault(); }, true);   // links inside documents never navigate the reader
document.addEventListener('keydown', (e) => {
  if (!(e.ctrlKey || e.metaKey)) return;
  if (e.key === '+' || e.key === '=') { e.preventDefault(); setZoom(zoom + 0.1); }
  else if (e.key === '-') { e.preventDefault(); setZoom(zoom - 0.1); }
  else if (e.key === '0') { e.preventDefault(); fit(); }
});
$('main').addEventListener('wheel', (e) => { if (e.ctrlKey) { e.preventDefault(); setZoom(zoom + (e.deltaY < 0 ? 0.1 : -0.1)); } }, { passive: false });
let depth = 0;
window.addEventListener('dragenter', (e) => { e.preventDefault(); depth++; $('landing').classList.add('drag'); });
window.addEventListener('dragover', (e) => e.preventDefault());
window.addEventListener('dragleave', () => { if (--depth <= 0) { depth = 0; $('landing').classList.remove('drag'); } });
window.addEventListener('drop', (e) => { e.preventDefault(); depth = 0; $('landing').classList.remove('drag'); loadFile(e.dataTransfer.files[0]); });

const q = new URLSearchParams(location.search);
if (q.get('file')) loadFromUrl(q.get('file'), q.get('name') || ('file.' + (q.get('type') || '')));
