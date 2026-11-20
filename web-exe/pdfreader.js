'use strict';
// Office Reader: one SumatraPDF-style tabbed reader (./office/viewer.html) for PDF (pdf.js in a tab), Word, Excel, PowerPoint, images, video, audio, Markdown and text, all rendered locally.
// Shown inside the app layout (<webview>, partition PDF_PARTITION),
// served from ./pdfjs through the rtpdf:// protocol. Also the "open with" target for .pdf files from Windows Explorer.
//   rtpdf://app/pdfjs/...            viewer files (read-only, only inside ./pdfjs)
//   rtpdf://app/f/<token>/<name>.pdf a PDF the user picked / Windows launched us with (token -> path, nothing else is readable)
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { pathToFileURL } = require('url');

const PDF_PARTITION = 'persist:rtpdf';
const PDF_SRC = 'rtpdf://app/pdfjs/web/viewer.html';
const OFFICE_SRC = 'rtpdf://app/office/viewer.html';
const ROOTS = [path.join(__dirname, 'pdfjs'), path.join(__dirname, 'office')];
const KINDS = {};
[['pdf', 'pdf'], ['docx', 'docx'], ['xlsx', 'xlsx'], ['xls', 'xlsx'], ['csv', 'xlsx'], ['pptx', 'pptx'], ['doc', 'olddoc'], ['ppt', 'olddoc'],
  ['png', 'image'], ['jpg', 'image'], ['jpeg', 'image'], ['gif', 'image'], ['webp', 'image'], ['bmp', 'image'], ['svg', 'image'], ['ico', 'image'], ['avif', 'image'],
  ['mp4', 'video'], ['webm', 'video'], ['mkv', 'video'], ['mov', 'video'], ['m4v', 'video'], ['ogv', 'video'],
  ['mp3', 'audio'], ['wav', 'audio'], ['ogg', 'audio'], ['m4a', 'audio'], ['flac', 'audio'], ['aac', 'audio'],
  ['md', 'md'], ['markdown', 'md'], ['txt', 'text'], ['log', 'text'], ['json', 'text'], ['xml', 'text'], ['ini', 'text'], ['cfg', 'text'], ['yml', 'text'], ['yaml', 'text']].forEach(([e, k]) => { KINDS[e] = k; });
const PICK_FILTERS = [
  { name: 'All supported files', extensions: Object.keys(KINDS) },
  { name: 'PDF', extensions: ['pdf'] }, { name: 'Word', extensions: ['docx'] }, { name: 'Excel / CSV', extensions: ['xlsx', 'xls', 'csv'] }, { name: 'PowerPoint', extensions: ['pptx'] },
  { name: 'Images', extensions: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'ico', 'avif'] }, { name: 'Video', extensions: ['mp4', 'webm', 'mkv', 'mov', 'm4v', 'ogv'] },
  { name: 'Audio', extensions: ['mp3', 'wav', 'ogg', 'm4a', 'flac', 'aac'] }, { name: 'Markdown / Text', extensions: ['md', 'markdown', 'txt', 'log', 'json', 'xml', 'ini', 'cfg', 'yml', 'yaml'] },
];
const MIME = {
  '.html': 'text/html; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.gif': 'image/gif', '.wasm': 'application/wasm', '.bcmap': 'application/octet-stream', '.pfb': 'application/octet-stream',
  '.ttf': 'font/ttf', '.icc': 'application/octet-stream', '.ftl': 'text/plain; charset=utf-8', '.properties': 'text/plain; charset=utf-8',
};
const tokens = new Map();   // token -> absolute path of a PDF

function kindOf(p) { const m = /\.([a-z0-9]+)$/i.exec(String(p || '')); return m ? (KINDS[m[1].toLowerCase()] || null) : null; }
function isDocPath(p) { return !!kindOf(p); }
// every supported file argument of a command line (Explorer: "Rajib Tutorial.exe" "C:\\x\\file.pdf" "C:\\x\\photo.png")
function filesFromArgv(argv) {
  const out = [];
  for (const a of (argv || []).slice(1)) {
    const s = String(a || '').replace(/^"|"$/g, '');
    if (isDocPath(s)) { try { if (fs.statSync(s).isFile()) out.push(path.resolve(s)); } catch (_) {} }
  }
  return out;
}
function fileFromArgv(argv) { return filesFromArgv(argv)[0] || null; }
function makeUrl(file) {
  const t = crypto.randomBytes(9).toString('hex');
  tokens.set(t, file);
  return 'rtpdf://app/f/' + t + '/' + encodeURIComponent(path.basename(file));
}

function handler(request) {
  try {
    const u = new URL(request.url);
    const p = decodeURIComponent(u.pathname);
    if (p.startsWith('/f/')) {
      const file = tokens.get(p.split('/')[2]);
      if (!file || !fs.existsSync(file)) return new Response('not found', { status: 404 });
      const { net } = require('electron');
      return net.fetch(pathToFileURL(file).toString(), { headers: request.headers });   // keeps Range support
    }
    const full = path.normalize(path.join(__dirname, p));
    if (!ROOTS.some((r) => full.startsWith(r + path.sep))) return new Response('not found', { status: 404 });
    const data = fs.readFileSync(full);
    return new Response(data, { status: 200, headers: { 'content-type': MIME[path.extname(full).toLowerCase()] || 'application/octet-stream' } });
  } catch (_) { return new Response('not found', { status: 404 }); }
}

// ctx: { ipcMain, dialog, shell, session, protocol, getWin, trusted, ready() -> bool (site loaded in the main window), showMain }
function init(ctx) {
  const reg = (s) => { try { s.protocol.handle('rtpdf', handler); } catch (_) {} };
  reg(ctx.session.fromPartition(PDF_PARTITION));
  reg(ctx.session.defaultSession);
  ctx.ipcMain.handle('pdf:pick', async (e) => {
    if (!ctx.trusted(e)) return null;
    const w = ctx.getWin();
    const r = await ctx.dialog.showOpenDialog(w, { title: 'Open file', properties: ['openFile', 'multiSelections'], filters: PICK_FILTERS });
    if (r.canceled) return null;
    const list = r.filePaths.filter(isDocPath).map((f) => ({ url: makeUrl(f), name: path.basename(f), kind: kindOf(f) }));
    return list.length ? list : null;
  });
  // Windows does not let an app make itself the default: open its "Default apps" page (user picks Rajib Tutorial for .pdf there)
  ctx.ipcMain.handle('pdf:setDefault', (e) => {
    if (!ctx.trusted(e)) return false;
    ctx.shell.openExternal('ms-settings:defaultapps').catch(() => {});
    return true;
  });
}

// files were opened from outside (Explorer double-click, "Open with", second instance)
const pending = [];
function openFile(ctx, files) {
  const list = (Array.isArray(files) ? files : [files]).filter(Boolean);
  if (!list.length) return;
  const items = list.map((f) => ({ url: makeUrl(f), name: path.basename(f), kind: kindOf(f) }));
  ctx.showMain();
  const w = ctx.getWin();
  if (w && !w.isDestroyed() && ctx.ready()) items.forEach((o) => w.webContents.send('pdf:open', o));
  else pending.push(...items);   // site still loading: delivered by flush()
}
function flush(ctx) {
  const w = ctx.getWin();
  if (!pending.length || !w || w.isDestroyed()) return;
  pending.splice(0).forEach((o) => w.webContents.send('pdf:open', o));
}

module.exports = { PDF_PARTITION, PDF_SRC, OFFICE_SRC, init, openFile, flush, fileFromArgv, filesFromArgv };
