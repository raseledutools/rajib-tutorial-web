'use strict';
// Office Reader: PDF (pdf.js) + Word / Excel / PowerPoint (./office, rendered locally). Shown inside the app layout. pdf.js viewer (Acrobat-style UI) shown inside the app layout (<webview>, partition PDF_PARTITION),
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
const KINDS = { pdf: 'pdf', docx: 'docx', xlsx: 'xlsx', xls: 'xls', csv: 'csv', pptx: 'pptx', doc: 'doc', ppt: 'ppt' };
const MIME = {
  '.html': 'text/html; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.gif': 'image/gif', '.wasm': 'application/wasm', '.bcmap': 'application/octet-stream', '.pfb': 'application/octet-stream',
  '.ttf': 'font/ttf', '.icc': 'application/octet-stream', '.ftl': 'text/plain; charset=utf-8', '.properties': 'text/plain; charset=utf-8',
};
const tokens = new Map();   // token -> absolute path of a PDF
let pendingUrl = null;       // a PDF Windows asked us to open before the site was ready

function kindOf(p) { const m = /\.([a-z0-9]+)$/i.exec(String(p || '')); return m ? (KINDS[m[1].toLowerCase()] || null) : null; }
function isDocPath(p) { return !!kindOf(p); }
// the first supported file argument of a command line (Explorer: "Rajib Tutorial.exe" "C:\x\file.pdf")
function fileFromArgv(argv) {
  for (const a of (argv || []).slice(1)) {
    const s = String(a || '').replace(/^"|"$/g, '');
    if (isDocPath(s)) { try { if (fs.statSync(s).isFile()) return path.resolve(s); } catch (_) {} }
  }
  return null;
}
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
    const r = await ctx.dialog.showOpenDialog(w, { title: 'Open file', properties: ['openFile'], filters: [
      { name: 'PDF / Word / Excel / PowerPoint', extensions: ['pdf', 'docx', 'xlsx', 'xls', 'csv', 'pptx'] },
      { name: 'PDF', extensions: ['pdf'] }, { name: 'Word', extensions: ['docx'] }, { name: 'Excel', extensions: ['xlsx', 'xls', 'csv'] }, { name: 'PowerPoint', extensions: ['pptx'] },
    ] });
    if (r.canceled || !r.filePaths[0] || !isDocPath(r.filePaths[0])) return null;
    return { url: makeUrl(r.filePaths[0]), name: path.basename(r.filePaths[0]), kind: kindOf(r.filePaths[0]) };
  });
  // Windows does not let an app make itself the default: open its "Default apps" page (user picks Rajib Tutorial for .pdf there)
  ctx.ipcMain.handle('pdf:setDefault', (e) => {
    if (!ctx.trusted(e)) return false;
    ctx.shell.openExternal('ms-settings:defaultapps').catch(() => {});
    return true;
  });
}

// a PDF file was opened from outside (Explorer double-click, "Open with", second instance)
function openFile(ctx, file) {
  if (!file) return;
  const url = makeUrl(file);
  ctx.showMain();
  const w = ctx.getWin();
  if (w && !w.isDestroyed() && ctx.ready()) w.webContents.send('pdf:open', { url, name: path.basename(file), kind: kindOf(file) });
  else pendingUrl = { url, name: path.basename(file), kind: kindOf(file) };   // site still loading: delivered by flush()
}
function flush(ctx) {
  const w = ctx.getWin();
  if (!pendingUrl || !w || w.isDestroyed()) return;
  const p = pendingUrl; pendingUrl = null;
  w.webContents.send('pdf:open', p);
}

module.exports = { PDF_PARTITION, PDF_SRC, OFFICE_SRC, init, openFile, flush, fileFromArgv };
