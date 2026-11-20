'use strict';
// PDF Reader: pdf.js viewer (Acrobat-style UI) shown inside the app layout (<webview>, partition PDF_PARTITION),
// served from ./pdfjs through the rtpdf:// protocol. Also the "open with" target for .pdf files from Windows Explorer.
//   rtpdf://app/pdfjs/...            viewer files (read-only, only inside ./pdfjs)
//   rtpdf://app/f/<token>/<name>.pdf a PDF the user picked / Windows launched us with (token -> path, nothing else is readable)
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { pathToFileURL } = require('url');

const PDF_PARTITION = 'persist:rtpdf';
const PDF_SRC = 'rtpdf://app/pdfjs/web/viewer.html';
const ROOT = path.join(__dirname, 'pdfjs');
const MIME = {
  '.html': 'text/html; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.gif': 'image/gif', '.wasm': 'application/wasm', '.bcmap': 'application/octet-stream', '.pfb': 'application/octet-stream',
  '.ttf': 'font/ttf', '.icc': 'application/octet-stream', '.ftl': 'text/plain; charset=utf-8', '.properties': 'text/plain; charset=utf-8',
};
const tokens = new Map();   // token -> absolute path of a PDF
let pendingUrl = null;       // a PDF Windows asked us to open before the site was ready

function isPdfPath(p) { return typeof p === 'string' && /\.pdf$/i.test(p); }
// the first .pdf argument of a command line (Explorer: "Rajib Tutorial.exe" "C:\x\file.pdf")
function pdfFromArgv(argv) {
  for (const a of (argv || []).slice(1)) {
    const s = String(a || '').replace(/^"|"$/g, '');
    if (isPdfPath(s)) { try { if (fs.statSync(s).isFile()) return path.resolve(s); } catch (_) {} }
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
    if (!p.startsWith('/pdfjs/')) return new Response('not found', { status: 404 });
    const full = path.normalize(path.join(__dirname, p));
    if (!full.startsWith(ROOT + path.sep)) return new Response('forbidden', { status: 403 });
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
    const r = await ctx.dialog.showOpenDialog(w, { title: 'Open PDF', properties: ['openFile'], filters: [{ name: 'PDF', extensions: ['pdf'] }] });
    if (r.canceled || !r.filePaths[0] || !isPdfPath(r.filePaths[0])) return null;
    return { url: makeUrl(r.filePaths[0]), name: path.basename(r.filePaths[0]) };
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
  if (w && !w.isDestroyed() && ctx.ready()) w.webContents.send('pdf:open', { url, name: path.basename(file) });
  else pendingUrl = { url, name: path.basename(file) };   // site still loading: delivered by flush()
}
function flush(ctx) {
  const w = ctx.getWin();
  if (!pendingUrl || !w || w.isDestroyed()) return;
  const p = pendingUrl; pendingUrl = null;
  w.webContents.send('pdf:open', p);
}

module.exports = { PDF_PARTITION, PDF_SRC, init, openFile, flush, pdfFromArgv };
