'use strict';
const SITE = 'https://raseledutools.github.io/rajib-tutorial-web/';
const $ = (s, r = document) => r.querySelector(s);
const el = (tag, props = {}, ...kids) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === 'class') e.className = v;
    else if (k === 'text') e.textContent = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v);
  }
  for (const c of kids.flat()) if (c != null) e.append(c);
  return e;
};
const btn = (text, fn, cls = 'btn') => el('button', { class: cls, text, onclick: fn });
const msg = (host) => { const m = el('div', { class: 'msg' }); host.append(m); return (t, kind = '') => { m.className = 'msg ' + kind; m.textContent = t; }; };
let toastT;
function toast(t) { const x = $('#toast'); x.textContent = t; x.hidden = false; clearTimeout(toastT); toastT = setTimeout(() => (x.hidden = true), 2600); }
const PDF = [{ name: 'PDF', extensions: ['pdf'] }];
const IMG = [{ name: 'Images', extensions: ['jpg', 'jpeg', 'png', 'webp', 'bmp', 'gif'] }];
const fmtKB = (n) => (n / 1024).toFixed(1) + ' KB';

/* ───────── Navigation ───────── */
const panes = {};
document.querySelectorAll('.pane').forEach((p) => (panes[p.id.replace('pane-', '')] = p));
let current = '';
function go(name) {
  current = name;
  document.querySelectorAll('.nav button').forEach((b) => b.classList.toggle('on', b.dataset.v === name));
  for (const [k, p] of Object.entries(panes)) p.classList.toggle('show', k === name);
  if (name === 'home') renderHome();
  if (name === 'native') renderNative();
  if (name === 'web') renderWeb();
  if (name === 'study') renderStudy();
  if (name === 'focus') renderFocus();
}
document.querySelectorAll('.nav button').forEach((b) => b.addEventListener('click', () => go(b.dataset.v)));

function renderHome() {
  const h = panes.home;
  h.replaceChildren(
    el('h1', { text: 'Rajib Tutorial' }),
    el('p', { class: 'sub', text: 'EEE courses, study tools and focus mode. This window is protected from screenshots and screen recording.' }),
    el('div', { class: 'big' },
      bigCard('📱', 'Native Section', 'Courses, live class, discussion, notifications', 'native'),
      bigCard('🌐', 'Web Section', 'Courses, live class, discussion, resources', 'web'),
      bigCard('🧰', 'Study Tools', 'PDF, photo, QR and age tools', 'study'),
      bigCard('🎯', 'Focus Mode', 'Timer, app blocking, full lock', 'focus')));
}
const bigCard = (ic, nm, ds, to) => el('div', { class: 'card', onclick: () => go(to) },
  el('div', { class: 'ic', text: ic }), el('div', { class: 'nm', text: nm }), el('div', { class: 'ds', text: ds }));

/* ───────── Web section (stays loaded when switching tabs) ───────── */
let frame = null;
function renderWeb() {
  const p = panes.web;
  if (frame) return;
  if (!navigator.onLine) {
    p.replaceChildren(el('div', { class: 'offline' },
      el('h2', { text: 'No internet connection' }),
      el('p', { class: 'sub', text: 'The web section needs internet.' }),
      btn('Retry', () => { frame = null; renderWeb(); })));
    return;
  }
  frame = el('iframe', { class: 'site', src: SITE, allow: 'fullscreen; autoplay; microphone; camera; clipboard-write' });
  p.replaceChildren(frame);
}
window.addEventListener('online', () => { if (current === 'web') { frame = null; renderWeb(); } });

/* ───────── Study tools ───────── */
const TOOLS = [
  { g: 'PDF', ic: '📖', nm: 'PDF Reader', ds: 'Read a PDF', run: toolReader },
  { g: 'PDF', ic: '📚', nm: 'Merge PDF', ds: 'Combine PDFs into one', run: toolMerge },
  { g: 'PDF', ic: '✂️', nm: 'Split PDF', ds: 'Extract pages or split all', run: toolSplit },
  { g: 'PDF', ic: '📄', nm: 'Photo to PDF', ds: 'Images into one PDF', run: toolPhotoPdf },
  { g: 'PDF', ic: '🗜️', nm: 'Compress PDF', ds: 'Coming soon', soon: true },
  { g: 'PDF', ic: '🖼️', nm: 'PDF to Photo', ds: 'Coming soon', soon: true },
  { g: 'Photo', ic: '📐', nm: 'Photo Resizer', ds: 'Any size, max KB', run: (h) => toolResize(h, 0) },
  { g: 'Photo', ic: '👤', nm: 'Job Photo', ds: '300×300, ≤100 KB', run: (h) => toolResize(h, 1) },
  { g: 'Photo', ic: '✍️', nm: 'Signature', ds: '300×80, ≤60 KB', run: (h) => toolResize(h, 2) },
  { g: 'More', ic: '📱', nm: 'QR Generator', ds: 'Text or link to QR', run: toolQr },
  { g: 'More', ic: '📷', nm: 'QR Reader', ds: 'Coming soon', soon: true },
  { g: 'More', ic: '📅', nm: 'Age Calculator', ds: 'Exact age and next birthday', run: toolAge },
];
function renderStudy() {
  const p = panes.study;
  const kids = [el('h1', { text: 'Study Tools' }), el('p', { class: 'sub', text: 'Everything runs on your computer. Files are not uploaded anywhere.' })];
  for (const g of ['PDF', 'Photo', 'More']) {
    kids.push(el('div', { class: 'group', text: g }));
    kids.push(el('div', { class: 'grid' }, TOOLS.filter((t) => t.g === g).map((t) =>
      el('div', { class: 'card' + (t.soon ? ' dis' : ''), onclick: () => !t.soon && openTool(t) },
        el('div', { class: 'ic', text: t.ic }), el('div', { class: 'nm', text: t.nm }), el('div', { class: 'ds', text: t.ds })))));
  }
  p.replaceChildren(...kids);
}
function openTool(t) {
  const host = el('div', { class: 'panel' });
  panes.study.replaceChildren(btn('← Back to tools', renderStudy, 'back'), el('h2', { text: t.ic + ' ' + t.nm }), host);
  t.run(host);
}
const pdfName = (n, suffix) => n.replace(/\.[^.]+$/, '') + suffix;
const savePdf = async (bytes, name) => (await api.saveFile({ defaultName: name, filters: PDF, bytes })) ? true : false;

function toolReader(host) {
  const holder = el('div');
  host.append(btn('Open PDF…', async () => {
    const [f] = await api.openFiles({ filters: PDF });
    if (!f) return;
    const url = URL.createObjectURL(new Blob([f.bytes], { type: 'application/pdf' }));
    holder.replaceChildren(el('embed', { class: 'pdf', type: 'application/pdf', src: url }));
  }), holder);
}

function toolMerge(host) {
  let files = [];
  const list = el('div');
  const say = msg(host);
  const go1 = btn('Merge and save', async () => {
    try {
      say('Working…');
      const out = await PDFLib.PDFDocument.create();
      for (const f of files) {
        const src = await PDFLib.PDFDocument.load(f.bytes, { ignoreEncryption: true });
        (await out.copyPages(src, src.getPageIndices())).forEach((pg) => out.addPage(pg));
      }
      say(await savePdf(await out.save(), 'merged.pdf') ? 'Saved ✓' : 'Cancelled', 'ok');
    } catch (e) { say('Failed: ' + e.message, 'er'); }
  });
  const draw = () => {
    list.replaceChildren(...files.map((f, i) => el('div', { class: 'row' },
      el('span', { text: `${i + 1}. ${f.name}` }), el('span', { class: 'sp' }),
      btn('↑', () => { if (i) { [files[i - 1], files[i]] = [files[i], files[i - 1]]; draw(); } }, 'btn ghost sm'),
      btn('↓', () => { if (i < files.length - 1) { [files[i + 1], files[i]] = [files[i], files[i + 1]]; draw(); } }, 'btn ghost sm'),
      btn('✕', () => { files.splice(i, 1); draw(); }, 'btn ghost sm'))));
    go1.disabled = files.length < 2;
  };
  host.prepend(el('div', { class: 'flex' }, btn('Add PDFs…', async () => { files = files.concat(await api.openFiles({ filters: PDF, multi: true })); draw(); }), go1), list);
  draw();
}

function parseRanges(str, total) {
  const out = [];
  for (const part of str.split(',').map((s) => s.trim()).filter(Boolean)) {
    const m = part.match(/^(\d*)\s*-\s*(\d*)$/) || part.match(/^(\d+)$/);
    if (!m) throw new Error('Bad range: ' + part);
    const a = m[2] === undefined ? +m[1] : (m[1] === '' ? 1 : +m[1]);
    const b = m[2] === undefined ? +m[1] : (m[2] === '' ? total : +m[2]);
    if (a < 1 || b > total || a > b) throw new Error(`Range ${part} is outside 1-${total}`);
    for (let i = a; i <= b; i++) out.push(i - 1);
  }
  if (!out.length) throw new Error('Enter page numbers');
  return out;
}
function toolSplit(host) {
  let file = null, doc = null;
  const info = el('div', { class: 'sub' });
  const rng = el('input', { placeholder: 'e.g. 1-3, 5, 8-' });
  const mode = el('select', {}, el('option', { value: 'range', text: 'Extract pages into one PDF' }), el('option', { value: 'each', text: 'Save every page as a separate PDF' }));
  const say = msg(host);
  const run = btn('Split', async () => {
    try {
      const total = doc.getPageCount();
      if (mode.value === 'each') {
        const files = [];
        for (let i = 0; i < total; i++) {
          const o = await PDFLib.PDFDocument.create();
          o.addPage((await o.copyPages(doc, [i]))[0]);
          files.push({ name: pdfName(file.name, `_page${i + 1}.pdf`), bytes: await o.save() });
        }
        say(await api.saveMany({ files }) ? `Saved ${total} files ✓` : 'Cancelled', 'ok');
      } else {
        const idx = parseRanges(rng.value, total);
        const o = await PDFLib.PDFDocument.create();
        (await o.copyPages(doc, idx)).forEach((pg) => o.addPage(pg));
        say(await savePdf(await o.save(), pdfName(file.name, '_split.pdf')) ? 'Saved ✓' : 'Cancelled', 'ok');
      }
    } catch (e) { say(e.message, 'er'); }
  });
  run.disabled = true;
  host.prepend(btn('Choose PDF…', async () => {
    const [f] = await api.openFiles({ filters: PDF });
    if (!f) return;
    try {
      doc = await PDFLib.PDFDocument.load(f.bytes, { ignoreEncryption: true }); file = f;
      info.textContent = `${f.name} — ${doc.getPageCount()} pages`; run.disabled = false;
    } catch (e) { say('Cannot read this PDF', 'er'); }
  }), info, el('label', { class: 'l', text: 'Mode' }), mode, el('label', { class: 'l', text: 'Pages (for extract mode)' }), rng, el('div', { class: 'flex' }, run));
}

async function toBitmap(bytes) { return createImageBitmap(new Blob([bytes])); }
const canvasBlob = (cv, type, q) => new Promise((r) => cv.toBlob(r, type, q));
function toolPhotoPdf(host) {
  let files = [];
  const list = el('div');
  const size = el('select', {}, el('option', { value: 'a4', text: 'A4 page (fit image)' }), el('option', { value: 'orig', text: 'Same size as image' }));
  const say = msg(host);
  const make = btn('Create PDF', async () => {
    try {
      say('Working…');
      const out = await PDFLib.PDFDocument.create();
      for (const f of files) {
        const bmp = await toBitmap(f.bytes);
        const cv = document.createElement('canvas'); cv.width = bmp.width; cv.height = bmp.height;
        const c = cv.getContext('2d'); c.fillStyle = '#fff'; c.fillRect(0, 0, cv.width, cv.height); c.drawImage(bmp, 0, 0);
        const jpg = await out.embedJpg(new Uint8Array(await (await canvasBlob(cv, 'image/jpeg', 0.92)).arrayBuffer()));
        if (size.value === 'orig') { out.addPage([bmp.width, bmp.height]).drawImage(jpg, { x: 0, y: 0, width: bmp.width, height: bmp.height }); }
        else {
          const W = 595.28, H = 841.89, m = 24, k = Math.min((W - 2 * m) / bmp.width, (H - 2 * m) / bmp.height);
          const w = bmp.width * k, h = bmp.height * k;
          out.addPage([W, H]).drawImage(jpg, { x: (W - w) / 2, y: (H - h) / 2, width: w, height: h });
        }
      }
      say(await savePdf(await out.save(), 'photos.pdf') ? 'Saved ✓' : 'Cancelled', 'ok');
    } catch (e) { say('Failed: ' + e.message, 'er'); }
  });
  const draw = () => {
    list.replaceChildren(...files.map((f, i) => el('div', { class: 'row' }, el('span', { text: `${i + 1}. ${f.name}` }), el('span', { class: 'sp' }),
      btn('↑', () => { if (i) { [files[i - 1], files[i]] = [files[i], files[i - 1]]; draw(); } }, 'btn ghost sm'),
      btn('✕', () => { files.splice(i, 1); draw(); }, 'btn ghost sm'))));
    make.disabled = !files.length;
  };
  host.prepend(el('div', { class: 'flex' }, btn('Add photos…', async () => { files = files.concat(await api.openFiles({ filters: IMG, multi: true })); draw(); })), list,
    el('label', { class: 'l', text: 'Page size' }), size, el('div', { class: 'flex' }, make));
  draw();
}

function toolResize(host, mode) {
  const fixed = mode === 1 ? [300, 300] : mode === 2 ? [300, 80] : null;
  let bmp = null, name = 'photo', result = null;
  const w = el('input', { type: 'number', min: 10, max: 8000, value: fixed ? fixed[0] : 600, style: 'width:100px' });
  const h = el('input', { type: 'number', min: 10, max: 8000, value: fixed ? fixed[1] : 600, style: 'width:100px' });
  const kb = el('input', { type: 'number', min: 5, value: mode === 1 ? 100 : mode === 2 ? 60 : '', placeholder: 'optional', style: 'width:110px' });
  if (fixed) { w.disabled = true; h.disabled = true; }
  const prev = el('img', { class: 'prev', hidden: '' });
  const say = msg(host);
  const save = btn('Save JPG', async () => {
    const ok = await api.saveFile({ defaultName: name.replace(/\.[^.]+$/, '') + '_resized.jpg', filters: [{ name: 'JPEG', extensions: ['jpg'] }], bytes: result });
    say(ok ? 'Saved ✓' : 'Cancelled', ok ? 'ok' : '');
  }); save.disabled = true;
  const proc = btn('Resize', async () => {
    const W = Math.round(+w.value), H = Math.round(+h.value), max = Math.round(+kb.value) || 0;
    if (!(W >= 10 && H >= 10)) return say('Enter width and height', 'er');
    const cv = document.createElement('canvas'); cv.width = W; cv.height = H;
    const c = cv.getContext('2d'); c.fillStyle = '#fff'; c.fillRect(0, 0, W, H); c.imageSmoothingQuality = 'high'; c.drawImage(bmp, 0, 0, W, H);
    let q = 0.92, blob;
    for (;;) { blob = await canvasBlob(cv, 'image/jpeg', q); if (!max || blob.size <= max * 1024 || q <= 0.15) break; q -= 0.07; }
    result = new Uint8Array(await blob.arrayBuffer());
    prev.src = URL.createObjectURL(blob); prev.hidden = false; save.disabled = false;
    const big = max && blob.size > max * 1024;
    say(`${W}×${H}, ${fmtKB(blob.size)}` + (big ? ` — could not get under ${max} KB at this size` : ' ✓'), big ? 'er' : 'ok');
  }); proc.disabled = true;
  const chips = fixed ? null : el('div', { class: 'chips' }, ['300×300', '300×80', '200×200', '413×531', '600×600'].map((s) =>
    el('span', { class: 'chip', text: s, onclick: () => { const [a, b] = s.split('×'); w.value = a; h.value = b; } })));
  host.prepend(btn('Choose photo…', async () => {
    const [f] = await api.openFiles({ filters: IMG }); if (!f) return;
    try { bmp = await toBitmap(f.bytes); name = f.name; proc.disabled = false; say(`${f.name} — ${bmp.width}×${bmp.height}, ${fmtKB(f.bytes.length)}`); } catch (e) { say('Cannot read this image', 'er'); }
  }), el('div', { class: 'flex' }, 'Width', w, 'Height', h, 'Max KB', kb), chips, el('div', { class: 'flex' }, proc, save), prev);
}

function drawQr(cv, text, px) {
  qrcode.stringToBytes = qrcode.stringToBytesFuncs['UTF-8'];
  const qr = qrcode(0, 'M'); qr.addData(text); qr.make();
  const n = qr.getModuleCount(), quiet = 2, cell = Math.max(1, Math.floor(px / (n + quiet * 2)));
  const size = cell * (n + quiet * 2);
  cv.width = size; cv.height = size;
  const c = cv.getContext('2d'); c.fillStyle = '#fff'; c.fillRect(0, 0, size, size); c.fillStyle = '#000';
  for (let r = 0; r < n; r++) for (let k = 0; k < n; k++) if (qr.isDark(r, k)) c.fillRect((k + quiet) * cell, (r + quiet) * cell, cell, cell);
}
function toolQr(host) {
  const t = el('textarea', { placeholder: 'Text or link…' });
  const size = el('select', {}, [256, 384, 512, 768].map((n) => el('option', { value: n, text: n + ' px' })));
  const cv = el('canvas', { class: 'prev', hidden: '' });
  const say = msg(host);
  host.prepend(t, el('div', { class: 'flex' }, size,
    btn('Generate', () => {
      if (!t.value.trim()) return say('Enter some text', 'er');
      try { drawQr(cv, t.value, +size.value); cv.hidden = false; say(''); } catch (e) { say('Text is too long for a QR code', 'er'); }
    }),
    btn('Save PNG', async () => {
      if (cv.hidden) return say('Generate first', 'er');
      const b = new Uint8Array(await (await canvasBlob(cv, 'image/png')).arrayBuffer());
      say(await api.saveFile({ defaultName: 'qr.png', filters: [{ name: 'PNG', extensions: ['png'] }], bytes: b }) ? 'Saved ✓' : 'Cancelled', 'ok');
    }, 'btn ghost')), cv);
}

function toolAge(host) {
  const today = new Date().toISOString().slice(0, 10);
  const dob = el('input', { type: 'date', max: today });
  const asOf = el('input', { type: 'date', value: today });
  const out = el('div', { class: 'msg ok' });
  host.append(el('label', { class: 'l', text: 'Date of birth' }), dob, el('label', { class: 'l', text: 'Age on' }), asOf,
    el('div', { class: 'flex' }, btn('Calculate', () => {
      if (!dob.value || !asOf.value) return (out.className = 'msg er', out.textContent = 'Pick both dates');
      const a = new Date(dob.value), b = new Date(asOf.value);
      if (a > b) return (out.className = 'msg er', out.textContent = 'Birth date is after the selected date');
      let y = b.getFullYear() - a.getFullYear(), m = b.getMonth() - a.getMonth(), d = b.getDate() - a.getDate();
      if (d < 0) { m--; d += new Date(b.getFullYear(), b.getMonth(), 0).getDate(); }
      if (m < 0) { y--; m += 12; }
      let nb = new Date(b.getFullYear(), a.getMonth(), a.getDate()); if (nb < b) nb = new Date(b.getFullYear() + 1, a.getMonth(), a.getDate());
      const days = Math.round((b - a) / 864e5), left = Math.round((nb - b) / 864e5);
      out.className = 'msg ok';
      out.textContent = `${y} years, ${m} months, ${d} days  •  ${days.toLocaleString()} days lived  •  next birthday in ${left} days`;
    })), out);
}

/* ───────── Focus mode ───────── */
const BLOCK_PRESETS = ['chrome.exe', 'msedge.exe', 'firefox.exe', 'Discord.exe', 'Steam.exe', 'vlc.exe', 'Telegram.exe'];
let fstat = { active: false };
const clock = (ms) => { const s = Math.max(0, Math.ceil(ms / 1000)); const p = (n) => String(n).padStart(2, '0'); return `${p(Math.floor(s / 3600))}:${p(Math.floor(s % 3600 / 60))}:${p(s % 60)}`; };

async function renderFocus() {
  const p = panes.focus;
  fstat = await api.focus.status();
  const kids = [el('h1', { text: 'Focus Mode' }), el('p', { class: 'sub', text: 'Start a timed session, block distracting apps, or lock this app until the time ends.' })];
  const host = el('div', { class: 'panel' });
  kids.push(host);
  p.replaceChildren(...kids);
  const say = msg(host);
  if (fstat.active) {
    host.prepend(el('h2', { text: 'Session running' }), el('div', { class: 'sub', id: 'fc', text: clock(fstat.endAt - Date.now()) }),
      el('div', { class: 'sub', text: fstat.blocked.length ? 'Blocked: ' + fstat.blocked.join(', ') : 'No apps blocked' }));
    const pin = el('input', { type: 'password', inputmode: 'numeric', maxlength: 8, placeholder: 'PIN to stop' });
    host.prepend(el('div', { class: 'flex' }, pin, btn('Stop session', async () => {
      const r = await api.focus.stop(pin.value); if (r.error) return say(r.error, 'er'); renderFocus();
    })));
    return;
  }
  if (!fstat.hasPin) {
    const pin = el('input', { type: 'password', inputmode: 'numeric', maxlength: 8, placeholder: '4-8 digits' });
    host.prepend(el('h2', { text: 'Set a PIN first' }), el('p', { class: 'sub', text: 'The PIN is needed to stop a session early. Keep it somewhere safe.' }),
      el('div', { class: 'flex' }, pin, btn('Save PIN', async () => { const r = await api.focus.setPin(pin.value); if (r.error) return say(r.error, 'er'); renderFocus(); })));
    return;
  }
  const hrs = el('input', { type: 'number', min: 0, max: 12, value: 1, style: 'width:80px' });
  const mins = el('input', { type: 'number', min: 0, max: 59, value: 0, style: 'width:80px' });
  const lock = el('input', { type: 'checkbox' });
  const apps = el('textarea', { placeholder: 'One app per line, e.g. chrome.exe' });
  apps.value = localStorage.getItem('focusApps') || '';
  host.prepend(
    el('div', { class: 'flex' }, 'Hours', hrs, 'Minutes', mins),
    el('label', { class: 'l' }, lock, ' Full lock (fullscreen, cannot be closed until the timer ends or the PIN is entered)'),
    el('label', { class: 'l', text: 'Apps to block (Windows process names)' }), apps,
    el('div', { class: 'chips' }, BLOCK_PRESETS.map((n) => el('span', { class: 'chip', text: '+ ' + n, onclick: () => { if (!apps.value.toLowerCase().split(/\s+/).includes(n.toLowerCase())) apps.value = (apps.value.trim() + '\n' + n).trim(); } }))),
    el('div', { class: 'flex' }, btn('Start session', async () => {
      localStorage.setItem('focusApps', apps.value);
      const minutes = (+hrs.value || 0) * 60 + (+mins.value || 0);
      const r = await api.focus.start({ minutes, lock: lock.checked, blocked: apps.value.split(/\r?\n/) });
      if (r.error) return say(r.error, 'er');
      renderFocus(); tick();
    })));
}
async function tick() {
  try { fstat = await api.focus.status(); } catch (_) { return; }
  const lk = $('#lock');
  const show = fstat.active && fstat.lock;
  lk.hidden = !show;
  if (show) $('#lockClock').textContent = clock(fstat.endAt - Date.now());
  const fc = $('#fc'); if (fc && fstat.active) fc.textContent = clock(fstat.endAt - Date.now());
  if (!fstat.active && fc && current === 'focus') renderFocus();
}
$('#lockStop').addEventListener('click', async () => {
  const r = await api.focus.stop($('#lockPin').value);
  $('#lockErr').textContent = r.error || ''; if (!r.error) { $('#lockPin').value = ''; tick(); }
});
setInterval(tick, 1000);

api.info().then((i) => ($('#ver').textContent = 'v' + i.version));
go('home');
tick();
