/* Study Tools (web/exe) — same tools as the Android app's Study Tools screen.
   Everything runs in the browser, files never leave the device.
   PDF: Merge · Split · Compress · PDF→Photo · Photo→PDF
   Photo: Photo Resizer · Job Photo · Signature
   More: QR Generator · QR Reader · Age Calculator */
(function () {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const LIBS = {
    pdflib: 'https://cdnjs.cloudflare.com/ajax/libs/pdf-lib/1.17.1/pdf-lib.min.js',
    pdfjs: 'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js',
    pdfjsWorker: 'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js',
    qr: 'https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js',
    jsqr: 'https://cdnjs.cloudflare.com/ajax/libs/jsQR/1.4.0/jsQR.min.js'
  };
  const loaded = {};
  function lib(key) {
    if (!loaded[key]) loaded[key] = new Promise((res, rej) => {
      const s = document.createElement('script');
      s.src = LIBS[key]; s.onload = res; s.onerror = () => { delete loaded[key]; rej(new Error('Library load failed — check internet')); };
      document.head.appendChild(s);
    });
    return loaded[key];
  }
  async function pdfjs() {
    await lib('pdfjs');
    window.pdfjsLib.GlobalWorkerOptions.workerSrc = LIBS.pdfjsWorker;
    return window.pdfjsLib;
  }

  /* ───────── styles ───────── */
  const css = document.createElement('style');
  css.textContent = `
  .st-wrap{max-width:760px;margin:0 auto;padding:16px 16px 32px}
  .st-wrap h2{font-size:20px;font-weight:700;margin:0 0 4px;color:var(--text)}
  .st-sub{font-size:13px;color:var(--sub);margin-bottom:14px}
  .st-sec{font-size:12px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:var(--sub);margin:18px 0 8px}
  .st-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px}
  .st-card{display:flex;flex-direction:column;align-items:center;gap:8px;padding:16px 8px;border:1px solid var(--border);border-radius:14px;background:var(--card,#fff);cursor:pointer;font-size:13px;font-weight:600;color:var(--text);text-align:center;transition:transform .12s,box-shadow .12s}
  .st-card:hover{transform:translateY(-2px);box-shadow:0 6px 18px rgba(0,0,0,.08)}
  .st-ico{width:46px;height:46px;border-radius:13px;display:flex;align-items:center;justify-content:center;font-size:22px}
  .st-back{display:inline-flex;align-items:center;gap:6px;border:none;background:none;color:var(--accent);font-weight:700;font-size:14px;cursor:pointer;padding:4px 0;margin-bottom:8px}
  .st-box{border:1px solid var(--border);border-radius:14px;padding:16px;background:var(--card,#fff)}
  .st-row{display:flex;gap:10px;flex-wrap:wrap;margin:10px 0;align-items:center}
  .st-row label{font-size:12.5px;font-weight:600;color:var(--sub);display:flex;flex-direction:column;gap:4px;flex:1;min-width:110px}
  .st-box input[type=text],.st-box input[type=number],.st-box input[type=date],.st-box textarea,.st-box select{padding:9px 10px;border:1px solid var(--border);border-radius:9px;font-size:14px;background:#fff;color:#14121A;width:100%;font-family:inherit}
  .st-btn{padding:10px 18px;border:none;border-radius:999px;background:var(--accent);color:#fff;font-weight:700;font-size:14px;cursor:pointer}
  .st-btn.alt{background:transparent;color:var(--accent);border:1.5px solid var(--accent)}
  .st-btn:disabled{opacity:.5;cursor:default}
  .st-drop{border:2px dashed var(--border);border-radius:12px;padding:22px;text-align:center;color:var(--sub);font-size:13.5px;cursor:pointer}
  .st-drop:hover{border-color:var(--accent);color:var(--accent)}
  .st-files{margin:10px 0;display:flex;flex-direction:column;gap:6px}
  .st-file{display:flex;align-items:center;gap:8px;padding:8px 10px;border:1px solid var(--border);border-radius:9px;font-size:13px;background:#fff;color:#14121A}
  .st-file span{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .st-file button{border:none;background:#f1f1f4;border-radius:6px;cursor:pointer;padding:2px 8px;font-size:14px}
  .st-msg{font-size:13px;margin-top:10px;color:var(--sub);white-space:pre-wrap}
  .st-msg.ok{color:#15803d;font-weight:600}.st-msg.err{color:#b91c1c;font-weight:600}
  .st-out{margin-top:12px;display:flex;flex-wrap:wrap;gap:12px;align-items:flex-start}
  .st-out img,.st-out canvas{max-width:100%;border:1px solid var(--border);border-radius:8px}
  .st-prog{height:6px;border-radius:4px;background:var(--border);overflow:hidden;margin-top:10px;display:none}
  .st-prog i{display:block;height:100%;width:0;background:var(--accent);transition:width .2s}
  `;
  document.head.appendChild(css);

  /* ───────── helpers ───────── */
  function pick(accept, multiple) {
    return new Promise((res) => {
      const i = document.createElement('input');
      i.type = 'file'; i.accept = accept; i.multiple = !!multiple;
      i.onchange = () => res([...i.files]);
      i.click();
    });
  }
  function save(blob, name) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = name;
    document.body.appendChild(a); a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 4000);
  }
  const kb = (n) => n < 1048576 ? (n / 1024).toFixed(0) + ' KB' : (n / 1048576).toFixed(2) + ' MB';
  const base = (n) => n.replace(/\.[^.]+$/, '');
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const buf = (f) => f.arrayBuffer();
  function loadImg(file) {
    return new Promise((res, rej) => {
      const url = URL.createObjectURL(file), im = new Image();
      im.onload = () => { res(im); };
      im.onerror = () => rej(new Error('Cannot read image'));
      im.src = url;
    });
  }
  const toBlob = (cv, type, q) => new Promise((r) => cv.toBlob(r, type, q));
  /* encode canvas as JPEG no larger than maxKB (binary search on quality) */
  async function jpegUnder(cv, maxKB) {
    let lo = 0.05, hi = 0.95, best = await toBlob(cv, 'image/jpeg', lo);
    if (!maxKB) return await toBlob(cv, 'image/jpeg', 0.92);
    if (best.size > maxKB * 1024) return best; // even lowest quality is too big
    for (let i = 0; i < 8; i++) {
      const mid = (lo + hi) / 2, b = await toBlob(cv, 'image/jpeg', mid);
      if (b.size <= maxKB * 1024) { best = b; lo = mid; } else hi = mid;
    }
    return best;
  }
  function parseRanges(str, total) {
    const set = new Set();
    str.split(',').map((s) => s.trim()).filter(Boolean).forEach((p) => {
      const m = p.match(/^(\d+)\s*(?:-\s*(\d+))?$/);
      if (!m) throw new Error('Bad range: ' + p);
      let a = +m[1], b = m[2] ? +m[2] : a;
      if (a > b) [a, b] = [b, a];
      for (let i = Math.max(1, a); i <= Math.min(total, b); i++) set.add(i - 1);
    });
    return [...set].sort((x, y) => x - y);
  }

  /* ───────── tool UI builder ───────── */
  let body; // current tool body element
  const msg = (t, cls) => { const m = body.querySelector('.st-msg'); if (m) { m.className = 'st-msg ' + (cls || ''); m.textContent = t; } };
  const prog = (p) => { const b = body.querySelector('.st-prog'); if (!b) return; b.style.display = p == null ? 'none' : 'block'; b.firstElementChild.style.width = (p || 0) + '%'; };
  const shell = '<div class="st-prog"><i></i></div><div class="st-msg"></div><div class="st-out"></div>';
  const out = () => body.querySelector('.st-out');
  async function guard(fn, btn) {
    if (btn) btn.disabled = true;
    try { await fn(); } catch (e) { msg(e.message || String(e), 'err'); prog(null); }
    if (btn) btn.disabled = false;
  }

  /* multi-file list with reorder/remove */
  function fileList(el, files, onChange) {
    el.innerHTML = '';
    files.forEach((f, i) => {
      const d = document.createElement('div'); d.className = 'st-file';
      d.innerHTML = `<span>${esc(f.name)} · ${kb(f.size)}</span><button title="Up">↑</button><button title="Down">↓</button><button title="Remove">✕</button>`;
      const [up, dn, rm] = d.querySelectorAll('button');
      up.onclick = () => { if (i > 0) { [files[i - 1], files[i]] = [files[i], files[i - 1]]; fileList(el, files, onChange); onChange && onChange(); } };
      dn.onclick = () => { if (i < files.length - 1) { [files[i + 1], files[i]] = [files[i], files[i + 1]]; fileList(el, files, onChange); onChange && onChange(); } };
      rm.onclick = () => { files.splice(i, 1); fileList(el, files, onChange); onChange && onChange(); };
      el.appendChild(d);
    });
  }

  /* ───────── tools ───────── */
  const TOOLS = [
    { sec: 'PDF Tools', items: [
      { e: '📚', c: '#FDE8E8', l: 'Merge PDF', f: tMerge },
      { e: '✂️', c: '#FFF1E0', l: 'Split PDF', f: tSplit },
      { e: '🗜️', c: '#EFE7FD', l: 'Compress PDF', f: tCompress },
      { e: '🖼️', c: '#FCE7F3', l: 'PDF to Photo', f: tPdfToPhoto },
      { e: '📄', c: '#E3F6E8', l: 'Photo to PDF', f: tPhotoToPdf }
    ] },
    { sec: 'Photo Tools', items: [
      { e: '📐', c: '#E1EDFF', l: 'Photo Resizer', f: (b) => tResize(b, 0) },
      { e: '👤', c: '#DDF4F4', l: 'Job Photo', f: (b) => tResize(b, 1) },
      { e: '✍️', c: '#EFE7FD', l: 'Signature', f: (b) => tResize(b, 2) }
    ] },
    { sec: 'More Tools', items: [
      { e: '📱', c: '#FFF1E0', l: 'QR Generator', f: tQrGen },
      { e: '📷', c: '#DDF4F4', l: 'QR Reader', f: tQrRead },
      { e: '📅', c: '#EEF8D4', l: 'Age Calculator', f: tAge }
    ] }
  ];

  /* Merge */
  function tMerge(b) {
    const files = [];
    b.innerHTML = `<div class="st-drop">＋ Select PDF files (2 or more)</div><div class="st-files"></div><button class="st-btn" disabled>Merge & Save</button>${shell}`;
    const list = b.querySelector('.st-files'), btn = b.querySelector('.st-btn');
    const upd = () => btn.disabled = files.length < 2;
    b.querySelector('.st-drop').onclick = async () => { (await pick('application/pdf', true)).forEach((f) => files.push(f)); fileList(list, files, upd); upd(); };
    btn.onclick = () => guard(async () => {
      await lib('pdflib'); const { PDFDocument } = window.PDFLib;
      const doc = await PDFDocument.create();
      for (let i = 0; i < files.length; i++) {
        msg(`Merging ${i + 1}/${files.length}…`); prog((i / files.length) * 100);
        const src = await PDFDocument.load(await buf(files[i]), { ignoreEncryption: true });
        (await doc.copyPages(src, src.getPageIndices())).forEach((p) => doc.addPage(p));
      }
      const bytes = await doc.save(); prog(null);
      save(new Blob([bytes], { type: 'application/pdf' }), 'merged.pdf');
      msg(`Done — ${doc.getPageCount()} pages, ${kb(bytes.length)}`, 'ok');
    }, btn);
  }

  /* Split */
  function tSplit(b) {
    let file = null;
    b.innerHTML = `<div class="st-drop">＋ Select a PDF</div>
      <div class="st-row"><label>Pages to extract (e.g. 1-3, 5, 8-10)<input type="text" id="stRange" placeholder="1-3, 5"></label></div>
      <div class="st-row"><button class="st-btn" disabled>Extract & Save</button><button class="st-btn alt" disabled id="stEach">Every page separately</button></div>${shell}`;
    const drop = b.querySelector('.st-drop'), btn = b.querySelector('.st-btn'), each = $('stEach');
    drop.onclick = async () => { const f = (await pick('application/pdf'))[0]; if (!f) return; file = f; drop.textContent = f.name + ' · ' + kb(f.size); btn.disabled = each.disabled = false; };
    const run = (all) => guard(async () => {
      await lib('pdflib'); const { PDFDocument } = window.PDFLib;
      const src = await PDFDocument.load(await buf(file), { ignoreEncryption: true }), n = src.getPageCount();
      if (all) {
        for (let i = 0; i < n; i++) {
          const d = await PDFDocument.create(); d.addPage((await d.copyPages(src, [i]))[0]);
          save(new Blob([await d.save()], { type: 'application/pdf' }), `${base(file.name)}_p${i + 1}.pdf`);
          prog(((i + 1) / n) * 100); await new Promise((r) => setTimeout(r, 250));
        }
        prog(null); msg(`Saved ${n} files`, 'ok'); return;
      }
      const idx = parseRanges($('stRange').value, n);
      if (!idx.length) throw new Error(`Enter pages between 1 and ${n}`);
      const d = await PDFDocument.create();
      (await d.copyPages(src, idx)).forEach((p) => d.addPage(p));
      save(new Blob([await d.save()], { type: 'application/pdf' }), `${base(file.name)}_extract.pdf`);
      msg(`Extracted ${idx.length} of ${n} pages`, 'ok');
    }, all ? each : btn);
    btn.onclick = () => run(false); each.onclick = () => run(true);
  }

  /* Compress (re-renders pages as JPEG) */
  function tCompress(b) {
    let file = null;
    b.innerHTML = `<div class="st-drop">＋ Select a PDF</div>
      <div class="st-row"><label>Quality<select id="stQ"><option value="0.8|1.6">High (larger)</option><option value="0.6|1.3" selected>Medium</option><option value="0.4|1.0">Low (smallest)</option></select></label></div>
      <div class="st-sub">Pages are converted to images, so text can no longer be selected after compression.</div>
      <button class="st-btn" disabled>Compress & Save</button>${shell}`;
    const drop = b.querySelector('.st-drop'), btn = b.querySelector('.st-btn');
    drop.onclick = async () => { const f = (await pick('application/pdf'))[0]; if (!f) return; file = f; drop.textContent = f.name + ' · ' + kb(f.size); btn.disabled = false; };
    btn.onclick = () => guard(async () => {
      const [q, sc] = $('stQ').value.split('|').map(Number);
      const js = await pdfjs(); await lib('pdflib');
      const pdf = await js.getDocument({ data: await buf(file) }).promise;
      const doc = await window.PDFLib.PDFDocument.create();
      for (let i = 1; i <= pdf.numPages; i++) {
        msg(`Page ${i}/${pdf.numPages}…`); prog(((i - 1) / pdf.numPages) * 100);
        const pg = await pdf.getPage(i), vp = pg.getViewport({ scale: sc }), cv = document.createElement('canvas');
        cv.width = vp.width; cv.height = vp.height;
        const ctx = cv.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, cv.width, cv.height);
        await pg.render({ canvasContext: ctx, viewport: vp }).promise;
        const img = await doc.embedJpg(await (await toBlob(cv, 'image/jpeg', q)).arrayBuffer());
        const base1 = pg.getViewport({ scale: 1 });
        doc.addPage([base1.width, base1.height]).drawImage(img, { x: 0, y: 0, width: base1.width, height: base1.height });
      }
      const bytes = await doc.save(); prog(null);
      if (bytes.length >= file.size) { msg(`Result (${kb(bytes.length)}) is not smaller than the original (${kb(file.size)}) — try a lower quality.`, 'err'); return; }
      save(new Blob([bytes], { type: 'application/pdf' }), `${base(file.name)}_compressed.pdf`);
      msg(`${kb(file.size)} → ${kb(bytes.length)} (${Math.round((1 - bytes.length / file.size) * 100)}% smaller)`, 'ok');
    }, btn);
  }

  /* PDF → Photo */
  function tPdfToPhoto(b) {
    let file = null;
    b.innerHTML = `<div class="st-drop">＋ Select a PDF</div>
      <div class="st-row"><label>Format<select id="stFmt"><option value="image/jpeg">JPG</option><option value="image/png">PNG</option></select></label>
      <label>Sharpness<select id="stSc"><option value="1.5">Normal</option><option value="2.2" selected>High</option><option value="3">Very high</option></select></label></div>
      <button class="st-btn" disabled>Convert</button>${shell}`;
    const drop = b.querySelector('.st-drop'), btn = b.querySelector('.st-btn');
    drop.onclick = async () => { const f = (await pick('application/pdf'))[0]; if (!f) return; file = f; drop.textContent = f.name + ' · ' + kb(f.size); btn.disabled = false; };
    btn.onclick = () => guard(async () => {
      const js = await pdfjs(), pdf = await js.getDocument({ data: await buf(file) }).promise;
      const type = $('stFmt').value, ext = type === 'image/png' ? 'png' : 'jpg', sc = +$('stSc').value;
      out().innerHTML = '';
      for (let i = 1; i <= pdf.numPages; i++) {
        msg(`Page ${i}/${pdf.numPages}…`); prog(((i - 1) / pdf.numPages) * 100);
        const pg = await pdf.getPage(i), vp = pg.getViewport({ scale: sc }), cv = document.createElement('canvas');
        cv.width = vp.width; cv.height = vp.height;
        const ctx = cv.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, cv.width, cv.height);
        await pg.render({ canvasContext: ctx, viewport: vp }).promise;
        const blob = await toBlob(cv, type, 0.92), name = `${base(file.name)}_p${i}.${ext}`;
        const box = document.createElement('div'), im = new Image(); im.src = URL.createObjectURL(blob); im.style.width = '150px';
        const a = document.createElement('button'); a.className = 'st-btn alt'; a.style.cssText = 'display:block;margin-top:6px;padding:5px 12px;font-size:12px'; a.textContent = 'Save p' + i; a.onclick = () => save(blob, name);
        box.append(im, a); out().appendChild(box);
      }
      prog(null); msg(`Done — ${pdf.numPages} pages. Click "Save" under each image.`, 'ok');
    }, btn);
  }

  /* Photo → PDF */
  function tPhotoToPdf(b) {
    const files = [];
    b.innerHTML = `<div class="st-drop">＋ Select photos</div><div class="st-files"></div>
      <div class="st-row"><label>Page size<select id="stPg"><option value="a4">A4</option><option value="fit">Fit to image</option></select></label></div>
      <button class="st-btn" disabled>Create PDF</button>${shell}`;
    const list = b.querySelector('.st-files'), btn = b.querySelector('.st-btn');
    const upd = () => btn.disabled = !files.length;
    b.querySelector('.st-drop').onclick = async () => { (await pick('image/*', true)).forEach((f) => files.push(f)); fileList(list, files, upd); upd(); };
    btn.onclick = () => guard(async () => {
      await lib('pdflib'); const doc = await window.PDFLib.PDFDocument.create(), a4 = $('stPg').value === 'a4';
      for (let i = 0; i < files.length; i++) {
        msg(`Adding ${i + 1}/${files.length}…`); prog((i / files.length) * 100);
        const im = await loadImg(files[i]), cv = document.createElement('canvas'); cv.width = im.naturalWidth; cv.height = im.naturalHeight;
        const ctx = cv.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, cv.width, cv.height); ctx.drawImage(im, 0, 0);
        const jpg = await doc.embedJpg(await (await toBlob(cv, 'image/jpeg', 0.92)).arrayBuffer());
        if (a4) {
          const W = 595, H = 842, m = 24, s = Math.min((W - 2 * m) / jpg.width, (H - 2 * m) / jpg.height), w = jpg.width * s, h = jpg.height * s;
          doc.addPage([W, H]).drawImage(jpg, { x: (W - w) / 2, y: (H - h) / 2, width: w, height: h });
        } else doc.addPage([jpg.width, jpg.height]).drawImage(jpg, { x: 0, y: 0, width: jpg.width, height: jpg.height });
      }
      const bytes = await doc.save(); prog(null);
      save(new Blob([bytes], { type: 'application/pdf' }), 'photos.pdf'); msg(`Done — ${files.length} pages, ${kb(bytes.length)}`, 'ok');
    }, btn);
  }

  /* Photo Resizer / Job Photo / Signature */
  function tResize(b, mode) {
    const preset = [{ w: '', h: '', kb: '' }, { w: 300, h: 300, kb: 100 }, { w: 300, h: 80, kb: 60 }][mode];
    let file = null;
    b.innerHTML = `<div class="st-drop">＋ Select a photo</div>
      <div class="st-row"><label>Width (px)<input type="number" id="stW" min="1" value="${preset.w}"></label><label>Height (px)<input type="number" id="stH" min="1" value="${preset.h}"></label><label>Max size (KB)<input type="number" id="stK" min="5" value="${preset.kb}" placeholder="optional"></label></div>
      ${mode ? '<div class="st-sub">Preset for job-form uploads — change the numbers if your form needs a different size.</div>' : '<div class="st-sub">Leave width/height empty to keep the original size.</div>'}
      <button class="st-btn" disabled>Resize & Save</button>${shell}`;
    const drop = b.querySelector('.st-drop'), btn = b.querySelector('.st-btn');
    drop.onclick = async () => {
      const f = (await pick('image/*'))[0]; if (!f) return; file = f;
      const im = await loadImg(f); drop.textContent = `${f.name} · ${im.naturalWidth}×${im.naturalHeight} · ${kb(f.size)}`; btn.disabled = false;
      if (!mode) { $('stW').value = im.naturalWidth; $('stH').value = im.naturalHeight; }
    };
    btn.onclick = () => guard(async () => {
      const im = await loadImg(file), w = +$('stW').value || im.naturalWidth, h = +$('stH').value || im.naturalHeight, k = +$('stK').value || 0;
      const cv = document.createElement('canvas'); cv.width = w; cv.height = h;
      const ctx = cv.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, w, h); ctx.imageSmoothingQuality = 'high'; ctx.drawImage(im, 0, 0, w, h);
      const blob = await jpegUnder(cv, k);
      out().innerHTML = ''; const pv = new Image(); pv.src = URL.createObjectURL(blob); pv.style.maxHeight = '180px'; out().appendChild(pv);
      save(blob, `${base(file.name)}_${w}x${h}.jpg`);
      if (k && blob.size > k * 1024) msg(`${w}×${h} · ${kb(blob.size)} — could not get under ${k} KB at this size; try smaller dimensions.`, 'err');
      else msg(`${w}×${h} · ${kb(blob.size)}`, 'ok');
    }, btn);
  }

  /* QR Generator */
  function tQrGen(b) {
    b.innerHTML = `<div class="st-row"><label>Text or link<textarea id="stQt" rows="3" placeholder="https://…"></textarea></label></div>
      <button class="st-btn">Generate</button>${shell}`;
    const btn = b.querySelector('.st-btn');
    btn.onclick = () => guard(async () => {
      const t = $('stQt').value.trim(); if (!t) throw new Error('Enter some text first');
      await lib('qr'); const o = out(); o.innerHTML = '';
      const holder = document.createElement('div'); o.appendChild(holder);
      new window.QRCode(holder, { text: t, width: 256, height: 256, correctLevel: window.QRCode.CorrectLevel.M });
      await new Promise((r) => setTimeout(r, 80));
      const cv = holder.querySelector('canvas');
      const s = document.createElement('button'); s.className = 'st-btn alt'; s.textContent = 'Save PNG';
      s.onclick = async () => save(await toBlob(cv, 'image/png'), 'qr.png'); o.appendChild(s); msg('');
    }, btn);
  }

  /* QR Reader (from image, or live camera) */
  function tQrRead(b) {
    let stream = null, raf = 0;
    b.innerHTML = `<div class="st-row"><button class="st-btn" id="stQi">📁 From image</button><button class="st-btn alt" id="stQc">📷 Use camera</button></div><video id="stQv" playsinline muted style="display:none;width:100%;max-width:360px;border-radius:12px"></video>${shell}`;
    const show = (t) => { out().innerHTML = ''; const d = document.createElement('div'); d.className = 'st-box'; d.style.wordBreak = 'break-all';
      d.innerHTML = esc(t) + (/^https?:\/\//i.test(t) ? `<div class="st-row"><button class="st-btn alt" style="padding:5px 12px">Open</button></div>` : '') + `<div class="st-row"><button class="st-btn alt" style="padding:5px 12px">Copy</button></div>`;
      const bs = d.querySelectorAll('button'); const hasOpen = bs.length === 2;
      if (hasOpen) bs[0].onclick = () => { try { (window.ElectronShell && window.ElectronShell.openUrl) ? window.ElectronShell.openUrl(t) : window.open(t, '_blank', 'noopener'); } catch (e) { window.open(t, '_blank', 'noopener'); } };
      bs[bs.length - 1].onclick = () => { navigator.clipboard.writeText(t); msg('Copied', 'ok'); };
      out().appendChild(d); msg('QR decoded', 'ok'); };
    const decode = async (src, w, h) => {
      if ('BarcodeDetector' in window) { try { const r = await new window.BarcodeDetector({ formats: ['qr_code'] }).detect(src); if (r.length) return r[0].rawValue; } catch (e) { } }
      await lib('jsqr'); const cv = document.createElement('canvas'); cv.width = w; cv.height = h;
      const ctx = cv.getContext('2d', { willReadFrequently: true }); ctx.drawImage(src, 0, 0, w, h);
      const r = window.jsQR(ctx.getImageData(0, 0, w, h).data, w, h); return r ? r.data : null;
    };
    $('stQi').onclick = () => guard(async () => {
      const f = (await pick('image/*'))[0]; if (!f) return; const im = await loadImg(f);
      const t = await decode(im, im.naturalWidth, im.naturalHeight); if (!t) throw new Error('No QR code found in this image'); show(t);
    });
    const v = $('stQv');
    $('stQc').onclick = () => guard(async () => {
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
      v.srcObject = stream; v.style.display = 'block'; await v.play(); msg('Point the camera at a QR code…');
      const tick = async () => {
        if (!stream) return;
        if (v.videoWidth) { const t = await decode(v, v.videoWidth, v.videoHeight); if (t) { stop(); show(t); return; } }
        raf = setTimeout(tick, 250);
      }; tick();
    });
    const stop = () => { clearTimeout(raf); if (stream) stream.getTracks().forEach((t) => t.stop()); stream = null; v.style.display = 'none'; };
    b._cleanup = stop;
  }

  /* Age Calculator */
  function tAge(b) {
    const today = new Date().toISOString().slice(0, 10);
    b.innerHTML = `<div class="st-row"><label>Date of birth<input type="date" id="stD1" max="${today}"></label><label>Age on date<input type="date" id="stD2" value="${today}"></label></div>
      <button class="st-btn">Calculate</button>${shell}`;
    b.querySelector('.st-btn').onclick = () => guard(async () => {
      const a = new Date($('stD1').value), t = new Date($('stD2').value);
      if (isNaN(a) || isNaN(t)) throw new Error('Pick both dates'); if (a > t) throw new Error('Date of birth is after the other date');
      let y = t.getFullYear() - a.getFullYear(), m = t.getMonth() - a.getMonth(), d = t.getDate() - a.getDate();
      if (d < 0) { m--; d += new Date(t.getFullYear(), t.getMonth(), 0).getDate(); }
      if (m < 0) { y--; m += 12; }
      const days = Math.floor((t - a) / 864e5);
      let nb = new Date(t.getFullYear(), a.getMonth(), a.getDate()); if (nb <= t) nb.setFullYear(t.getFullYear() + 1);
      const left = Math.ceil((nb - t) / 864e5);
      out().innerHTML = `<div class="st-box" style="font-size:15px;line-height:1.7"><b style="font-size:20px">${y} years, ${m} months, ${d} days</b><br>Total: ${days.toLocaleString()} days (~${Math.floor(days / 7).toLocaleString()} weeks)<br>Next birthday: ${nb.toDateString()} (in ${left} days)</div>`; msg('');
    });
  }

  /* ───────── view / navigation ───────── */
  function render() {
    const v = $('viewStudy'); if (!v || v.dataset.ready) return; v.dataset.ready = '1';
    v.innerHTML = `<div class="st-wrap"><div id="stHome"><h2>🧰 Study Tools</h2><div class="st-sub">PDF, photo and QR utilities — work on your device, no upload.</div>${TOOLS.map((s) => `<div class="st-sec">${s.sec}</div><div class="st-grid">${s.items.map((t, i) => `<div class="st-card" data-s="${esc(s.sec)}" data-i="${i}"><div class="st-ico" style="background:${t.c}">${t.e}</div>${t.l}</div>`).join('')}</div>`).join('')}</div>
      <div id="stTool" style="display:none"><button class="st-back">← Study Tools</button><h2 id="stTitle"></h2><div class="st-box" id="stBody"></div></div></div>`;
    v.querySelectorAll('.st-card').forEach((c) => c.onclick = () => {
      const t = TOOLS.find((s) => s.sec === c.dataset.s).items[+c.dataset.i];
      $('stHome').style.display = 'none'; $('stTool').style.display = 'block'; $('stTitle').textContent = t.e + ' ' + t.l;
      body = $('stBody'); body.innerHTML = ''; t.f(body); window.scrollTo(0, 0);
    });
    v.querySelector('.st-back').onclick = closeTool;
  }
  function closeTool() { if (body && body._cleanup) body._cleanup(); $('stTool').style.display = 'none'; $('stHome').style.display = 'block'; }

  window.openStudyTools = function () {
    render();
    if (window.goView) window.goView('viewStudy');
    $('topbarTitle') && ($('topbarTitle').textContent = 'Study Tools');
    $('navStudy') && $('navStudy').classList.add('active');
    if ($('stTool') && $('stTool').style.display !== 'none') closeTool();
    if (window.closeSidebar) window.closeSidebar();
  };

  /* Android app already has the native Study Tools screen — only show the sidebar entry on exe / web */
  document.addEventListener('DOMContentLoaded', function () {
    const n = $('navStudy'); if (n && /Android/i.test(navigator.userAgent) && !/Electron\//.test(navigator.userAgent)) n.style.display = 'none';
  });
})();
