'use strict';
const { app, BrowserWindow, ipcMain, dialog, shell, Menu, session } = require('electron');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { execFile } = require('child_process');

const SITE_HOST = 'raseledutools.github.io';
const MAX_FILE = 200 * 1024 * 1024;
const PACKAGED = app.isPackaged;

let mainWin = null;

if (!app.requestSingleInstanceLock()) { app.quit(); }
app.on('second-instance', () => {
  if (mainWin) { if (mainWin.isMinimized()) mainWin.restore(); mainWin.focus(); }
});

/* ───────── Screenshot / screen-capture protection ─────────
   Windows: SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE) – the window is
   invisible to screenshots, Snipping Tool, OBS, Zoom/Teams share, etc.      */
function protect(win) {
  const apply = () => { try { win.setContentProtection(true); } catch (_) {} };
  apply();
  win.on('show', apply);
  win.on('restore', apply);
  win.on('focus', apply);
}

function guardInput(wc) {
  wc.on('before-input-event', (e, input) => {
    if (input.type !== 'keyDown') return;
    const k = (input.key || '').toLowerCase();
    const ctrl = input.control || input.meta;
    if (ctrl && (k === 'p' || k === 's' || k === 'u')) e.preventDefault();      // print / save / view-source
    if (PACKAGED && (k === 'f12' || (ctrl && input.shift && (k === 'i' || k === 'j' || k === 'c')))) e.preventDefault();
  });
  wc.on('context-menu', (e) => e.preventDefault());
}

function openChild(url) {
  const win = new BrowserWindow({
    width: 1100, height: 760, autoHideMenuBar: true, parent: mainWin || undefined,
    webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false, devTools: !PACKAGED },
  });
  protect(win);
  win.loadURL(url);
  return win;
}

app.on('web-contents-created', (_e, wc) => {
  guardInput(wc);
  wc.setWindowOpenHandler(({ url }) => {
    try {
      const u = new URL(url);
      if (u.protocol === 'https:') openChild(url);
      else if (u.protocol === 'mailto:' || u.protocol === 'tel:') shell.openExternal(url);
    } catch (_) {}
    return { action: 'deny' };
  });
  wc.on('will-navigate', (e, url) => {
    if (wc === mainWin?.webContents && !url.startsWith('file://')) e.preventDefault();
  });
});

function createMain() {
  mainWin = new BrowserWindow({
    width: 1280, height: 820, minWidth: 920, minHeight: 600,
    backgroundColor: '#f5f3ff', title: 'Rajib Tutorial', autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true, sandbox: true, nodeIntegration: false, devTools: !PACKAGED,
    },
  });
  protect(mainWin);
  mainWin.loadFile(path.join(__dirname, 'shell', 'index.html'));
  mainWin.on('close', (e) => { if (focusActive() && focus.lock) e.preventDefault(); });
  mainWin.on('blur', () => { if (focusActive() && focus.lock) setTimeout(() => mainWin && mainWin.focus(), 150); });
  mainWin.on('closed', () => { mainWin = null; });
}

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  session.defaultSession.setPermissionRequestHandler((_wc, perm, cb) =>
    cb(['media', 'fullscreen', 'clipboard-sanitized-write', 'notifications'].includes(perm)));
  createMain();
  loadFocus();
  setInterval(focusTick, 3000);
  if (PACKAGED) {
    try { require('electron-updater').autoUpdater.checkForUpdatesAndNotify().catch(() => {}); } catch (_) {}
  }
});
app.on('window-all-closed', () => app.quit());

/* ───────── File helpers (renderer never touches fs directly) ───────── */
const safeName = (n) => path.basename(String(n || 'file')).replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').slice(0, 120) || 'file';

function uniquePath(dir, name) {
  let p = path.join(dir, name);
  const { name: base, ext } = path.parse(name);
  for (let i = 1; fs.existsSync(p); i++) p = path.join(dir, `${base} (${i})${ext}`);
  return p;
}

ipcMain.handle('app:info', () => ({ version: app.getVersion(), platform: process.platform }));

ipcMain.handle('file:open', async (_e, opts = {}) => {
  const r = await dialog.showOpenDialog(mainWin, {
    properties: ['openFile', ...(opts.multi ? ['multiSelections'] : [])],
    filters: Array.isArray(opts.filters) ? opts.filters : [],
  });
  if (r.canceled) return [];
  const out = [];
  for (const p of r.filePaths) {
    const st = fs.statSync(p);
    if (st.size > MAX_FILE) continue;
    out.push({ name: path.basename(p), bytes: new Uint8Array(fs.readFileSync(p)) });
  }
  return out;
});

ipcMain.handle('file:save', async (_e, { defaultName, filters, bytes }) => {
  const r = await dialog.showSaveDialog(mainWin, { defaultPath: safeName(defaultName), filters: filters || [] });
  if (r.canceled || !r.filePath) return null;
  fs.writeFileSync(r.filePath, Buffer.from(bytes));
  return r.filePath;
});

ipcMain.handle('file:saveMany', async (_e, { files }) => {
  const r = await dialog.showOpenDialog(mainWin, { properties: ['openDirectory', 'createDirectory'] });
  if (r.canceled || !r.filePaths[0]) return null;
  const dir = r.filePaths[0];
  for (const f of files) fs.writeFileSync(uniquePath(dir, safeName(f.name)), Buffer.from(f.bytes));
  return dir;
});

ipcMain.handle('win:open', (_e, url) => { try { if (new URL(url).protocol === 'https:') openChild(url); } catch (_) {} });

/* ───────── Focus mode: timed session, app blocking, optional full lock ───────── */
const DENY = new Set(['explorer.exe', 'csrss.exe', 'winlogon.exe', 'svchost.exe', 'system', 'lsass.exe',
  'services.exe', 'dwm.exe', 'smss.exe', 'wininit.exe', 'taskmgr.exe', 'rajib tutorial.exe', 'electron.exe']);
let focus = { endAt: 0, lock: false, blocked: [], pin: null };
const focusFile = () => path.join(app.getPath('userData'), 'focus.json');
const hashPin = (pin, salt) => crypto.scryptSync(String(pin), salt, 32).toString('hex');

function loadFocus() {
  try { focus = { ...focus, ...JSON.parse(fs.readFileSync(focusFile(), 'utf8')) }; } catch (_) {}
  if (focusActive()) applyLock(focus.lock); else if (focus.endAt) endFocus();
}
function saveFocus() { try { fs.writeFileSync(focusFile(), JSON.stringify(focus)); } catch (_) {} }
function focusActive() { return focus.endAt > Date.now(); }
function checkPin(pin) {
  if (!focus.pin) return false;
  const a = Buffer.from(hashPin(pin, focus.pin.salt)), b = Buffer.from(focus.pin.hash);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
function statusOf() {
  return { active: focusActive(), endAt: focus.endAt, lock: focus.lock, blocked: focus.blocked, hasPin: !!focus.pin };
}
function applyLock(on) {
  if (!mainWin) return;
  mainWin.setKiosk(!!on);
  mainWin.setAlwaysOnTop(!!on, 'screen-saver');
  mainWin.setMinimizable(!on);
}
function endFocus() {
  focus.endAt = 0; focus.lock = false; saveFocus(); applyLock(false);
}
function focusTick() {
  if (!focus.endAt) return;
  if (!focusActive()) return endFocus();
  if (process.platform !== 'win32' || !focus.blocked.length) return;
  execFile('tasklist', ['/fo', 'csv', '/nh'], { windowsHide: true }, (err, out) => {
    if (err) return;
    const running = new Set(out.split(/\r?\n/).map((l) => (l.match(/^"([^"]+)"/) || [])[1]).filter(Boolean).map((s) => s.toLowerCase()));
    for (const name of focus.blocked) {
      if (running.has(name)) execFile('taskkill', ['/f', '/im', name], { windowsHide: true }, () => {});
    }
  });
}

ipcMain.handle('focus:status', () => statusOf());

ipcMain.handle('focus:setPin', (_e, { pin, oldPin }) => {
  if (!/^\d{4,8}$/.test(String(pin))) return { error: 'PIN must be 4-8 digits' };
  if (focusActive()) return { error: 'Cannot change PIN during a session' };
  if (focus.pin && !checkPin(oldPin)) return { error: 'Current PIN is wrong' };
  const salt = crypto.randomBytes(16).toString('hex');
  focus.pin = { salt, hash: hashPin(pin, salt) };
  saveFocus();
  return { ok: true };
});

ipcMain.handle('focus:start', (_e, cfg = {}) => {
  if (!focus.pin) return { error: 'no-pin' };
  if (focusActive()) return { error: 'A session is already running' };
  const minutes = Math.floor(Number(cfg.minutes));
  if (!(minutes >= 1 && minutes <= 720)) return { error: 'Duration must be 1 to 720 minutes' };
  const blocked = [...new Set((Array.isArray(cfg.blocked) ? cfg.blocked : [])
    .map((s) => String(s).trim().toLowerCase())
    .filter((s) => /^[\w.\- ]{1,64}\.exe$/.test(s) && !DENY.has(s)))].slice(0, 50);
  focus.endAt = Date.now() + minutes * 60000;
  focus.lock = !!cfg.lock;
  focus.blocked = blocked;
  saveFocus();
  applyLock(focus.lock);
  focusTick();
  return statusOf();
});

ipcMain.handle('focus:stop', (_e, { pin }) => {
  if (!focusActive()) return statusOf();
  if (!checkPin(pin)) return { error: 'Wrong PIN' };
  endFocus();
  return statusOf();
});
