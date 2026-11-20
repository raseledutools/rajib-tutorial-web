'use strict';
/* Safe Browser menu helpers (main process): Downloads list, Delete browsing data, Save page as.
   Everything is only reachable from the app's own window (trusted(e)). */
const path = require('path');
const fs = require('fs');

// files a click in the Downloads page must never launch directly (shown in the folder instead)
const RISKY = /\.(exe|msi|bat|cmd|com|scr|pif|ps1|psm1|vbs|vbe|js|jse|wsf|wsh|hta|lnk|reg|jar|dll|cpl|msc|appx|msix)$/i;

module.exports.register = function register({ ipcMain, app, session, shell, dialog, webContents, getWin, partition, trusted }) {
  const downloads = [];     // newest first: { id, name, url, path, total, received, state, ts, _item }
  let seq = 0;

  const pub = (d) => ({ id: d.id, name: d.name, url: d.url, path: d.path, total: d.total, received: d.received, state: d.state, ts: d.ts, risky: RISKY.test(d.name) });
  const tell = (msg) => { try { const w = getWin(); if (w && !w.isDestroyed()) w.webContents.send('bt:dl', JSON.stringify(msg)); } catch (_) {} };

  // call for the Safe Browser session (and for incognito sessions)
  function hook(ses) {
    ses.on('will-download', (_ev, item) => {
      try {
        const dir = app.getPath('downloads');
        const clean = String(item.getFilename() || 'download').replace(/[\\/:*?"<>|]/g, '_').slice(0, 150) || 'download';
        const ext = path.extname(clean), base = path.basename(clean, ext);
        let p = path.join(dir, clean), n = 1;
        while (fs.existsSync(p)) p = path.join(dir, base + ' (' + (n++) + ')' + ext);
        item.setSavePath(p);
        const rec = { id: ++seq, name: path.basename(p), url: item.getURL(), path: p, total: item.getTotalBytes(), received: 0, state: 'progressing', ts: Date.now(), _item: item };
        downloads.unshift(rec);
        if (downloads.length > 100) downloads.length = 100;
        tell({ type: 'start', name: rec.name });
        item.on('updated', (_e, st) => {
          rec.received = item.getReceivedBytes(); rec.total = item.getTotalBytes();
          rec.state = st === 'interrupted' ? 'interrupted' : (item.isPaused() ? 'paused' : 'progressing');
        });
        item.on('done', (_e, st) => {
          rec.received = item.getReceivedBytes(); rec.state = st; rec._item = null;
          tell({ type: 'done', name: rec.name, state: st });
        });
      } catch (_) {}
    });
  }
  // session.fromPartition() only works after the app is ready (calling it earlier crashed the app at start)
  app.whenReady().then(() => hook(session.fromPartition(partition)));

  ipcMain.handle('dl:list', (e) => (trusted(e) ? downloads.map(pub) : []));
  ipcMain.handle('dl:open', (e, id) => {
    if (!trusted(e)) return null;
    const d = downloads.find((x) => x.id === Number(id));
    if (!d || d.state !== 'completed') return { ok: false, error: 'File is not ready.' };
    if (!fs.existsSync(d.path)) return { ok: false, error: 'File was moved or deleted.' };
    if (RISKY.test(d.name)) { shell.showItemInFolder(d.path); return { ok: true, shown: true }; }   // programs are never launched from here
    shell.openPath(d.path).catch(() => {});
    return { ok: true };
  });
  ipcMain.handle('dl:show', (e, id) => {
    if (!trusted(e)) return null;
    const d = downloads.find((x) => x.id === Number(id));
    if (d && fs.existsSync(d.path)) shell.showItemInFolder(d.path); else if (d) shell.openPath(path.dirname(d.path)).catch(() => {});
    return { ok: !!d };
  });
  ipcMain.handle('dl:cancel', (e, id) => {
    if (!trusted(e)) return null;
    const d = downloads.find((x) => x.id === Number(id));
    try { if (d && d._item) d._item.cancel(); } catch (_) {}
    return { ok: !!d };
  });
  ipcMain.handle('dl:remove', (e, id) => {
    if (!trusted(e)) return null;
    const i = downloads.findIndex((x) => x.id === Number(id));
    if (i >= 0 && !downloads[i]._item) downloads.splice(i, 1);
    return { ok: true };
  });
  ipcMain.handle('dl:clear', (e) => {
    if (!trusted(e)) return null;
    for (let i = downloads.length - 1; i >= 0; i--) if (!downloads[i]._item) downloads.splice(i, 1);
    return { ok: true };
  });

  // Delete browsing data: cache and / or cookies + site data of the Safe Browser session
  ipcMain.handle('browsing:clear', async (e, o) => {
    if (!trusted(e)) return { ok: false };
    o = o || {};
    const s = session.fromPartition(partition);
    try {
      if (o.cache) {
        await s.clearCache();
        try { await s.clearCodeCaches({}); } catch (_) {}
        await s.clearStorageData({ storages: ['cachestorage', 'shadercache'] });
      }
      if (o.cookies) await s.clearStorageData({ storages: ['cookies', 'localstorage', 'indexdb', 'serviceworkers', 'websql'] });
      return { ok: true };
    } catch (err) { return { ok: false, error: String((err && err.message) || err) }; }
  });

  // Save page as… (complete web page) for a tab of the Safe Browser <webview>
  ipcMain.handle('page:save', async (e, wcId) => {
    if (!trusted(e)) return { ok: false };
    const win = getWin();
    const wc = webContents.fromId(Number(wcId));
    if (!wc || wc.isDestroyed() || wc.getType() !== 'webview' || wc.hostWebContents !== win.webContents) return { ok: false, error: 'Open a web page first.' };
    const title = String(wc.getTitle() || 'page').replace(/[\\/:*?"<>|]/g, '_').slice(0, 80) || 'page';
    const r = await dialog.showSaveDialog(win, { defaultPath: path.join(app.getPath('downloads'), title + '.html'), filters: [{ name: 'Web page, complete', extensions: ['html'] }] });
    if (r.canceled || !r.filePath) return { ok: false, canceled: true };
    try { await wc.savePage(r.filePath, 'HTMLComplete'); return { ok: true }; }
    catch (err) { return { ok: false, error: String((err && err.message) || err) }; }
  });

  return { hook };
};
