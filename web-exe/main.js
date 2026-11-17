'use strict';
const { app, BrowserWindow, Menu, session, shell, clipboard, globalShortcut, ipcMain } = require('electron');
const path = require('path');

const SITE = 'https://raseledutools.github.io/rajib-tutorial-web/';
const SITE_ORIGIN = new URL(SITE).origin;
const PACKAGED = app.isPackaged;
let win = null;

if (!app.requestSingleInstanceLock()) app.quit();
app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });

/* Screenshot / screen-record protection.
   Windows 10 2004+/11: SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE) – the window is
   invisible to PrintScreen, Snipping Tool, OBS, Game Bar, Zoom/Teams/Meet share etc.
   (Older Windows: window shows as a black rectangle in captures.) */
function protect(w) {
  const apply = () => { try { w.setContentProtection(true); } catch (_) {} };
  apply();
  ['show', 'restore', 'focus', 'maximize', 'unmaximize', 'leave-full-screen'].forEach((ev) => w.on(ev, apply));
  setInterval(apply, 2000).unref();
}

function guard(wc) {
  wc.on('before-input-event', (e, input) => {
    const k = (input.key || '').toLowerCase();
    const ctrl = input.control || input.meta;
    if (k === 'printscreen') { e.preventDefault(); try { clipboard.clear(); } catch (_) {} return; }
    if (input.type !== 'keyDown') return;
    if (input.alt && (k === 'arrowleft' || k === 'arrowright')) { e.preventDefault(); const h = wc.navigationHistory; if (k === 'arrowleft' && h.canGoBack()) h.goBack(); if (k === 'arrowright' && h.canGoForward()) h.goForward(); return; }
    if (k === 'f5' || (ctrl && k === 'r')) { e.preventDefault(); if (wc.getURL().startsWith('file:') && win) win.loadURL(SITE); else wc.reloadIgnoringCache(); return; }
    if (ctrl && ['p', 's', 'u'].includes(k)) e.preventDefault();
    if (PACKAGED && (k === 'f12' || (ctrl && input.shift && ['i', 'j', 'c'].includes(k)))) e.preventDefault();
  });
  wc.on('context-menu', (e) => e.preventDefault());
}

function create() {
  win = new BrowserWindow({
    width: 1280, height: 820, minWidth: 900, minHeight: 600, show: false,
    backgroundColor: '#ffffff', title: 'Rajib Tutorial', autoHideMenuBar: true,
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: { contextIsolation: true, sandbox: true, nodeIntegration: false, devTools: !PACKAGED, preload: path.join(__dirname, 'preload.js') },
  });
  protect(win);
  win.once('ready-to-show', () => { win.show(); win.maximize(); });
  win.on('closed', () => { win = null; });
  win.setMenuBarVisibility(false);

  const wc = win.webContents;
  wc.on('did-fail-load', (_e, code, _d, url, isMain) => {
    if (isMain && code !== -3 && url.startsWith('http')) win.loadFile(path.join(__dirname, 'offline.html'));
  });
  wc.on('will-navigate', (e, url) => {
    if (url === 'app://retry') { e.preventDefault(); win.loadURL(SITE); }
  });
  wc.setWindowOpenHandler(({ url }) => {
    try {
      const u = new URL(url);
      if (u.protocol === 'https:') wc.loadURL(url); // same window; use ← to go back
      else if (u.protocol === 'mailto:' || u.protocol === 'tel:') shell.openExternal(url);
    } catch (_) {}
    return { action: 'deny' };
  });
  win.loadURL(SITE);
}

// Links that leave the site (payment pages, YouTube, WhatsApp …) also open in a protected window.
function openChild(url) {
  const c = new BrowserWindow({
    width: 1100, height: 760, autoHideMenuBar: true, parent: win || undefined,
    webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false, devTools: !PACKAGED },
  });
  protect(c);
  c.loadURL(url);
}

ipcMain.on('nav', (e, act) => {
  const wc = e.sender, h = wc.navigationHistory;
  if (act === 'back' && h.canGoBack()) h.goBack();
  else if (act === 'forward' && h.canGoForward()) h.goForward();
  else if (act === 'reload') { if (wc.getURL().startsWith('file:') && win) win.loadURL(SITE); else wc.reloadIgnoringCache(); }
});

app.on('web-contents-created', (_e, wc) => guard(wc));

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  session.defaultSession.setPermissionRequestHandler((_wc, perm, cb) =>
    cb(['media', 'fullscreen', 'clipboard-sanitized-write', 'notifications'].includes(perm)));
  session.defaultSession.webRequest.onBeforeSendHeaders(
    { urls: ['https://www.youtube.com/embed/*', 'https://www.youtube-nocookie.com/embed/*'] },
    (d, cb) => { d.requestHeaders['Referer'] = SITE; cb({ requestHeaders: d.requestHeaders }); });
  create();
  try { globalShortcut.register('PrintScreen', () => { try { clipboard.clear(); } catch (_) {} }); } catch (_) {}
});
app.on('will-quit', () => { try { globalShortcut.unregisterAll(); } catch (_) {} });
app.on('window-all-closed', () => app.quit());
