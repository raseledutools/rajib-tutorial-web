'use strict';
const { app, BrowserWindow, Menu, session, shell, clipboard, globalShortcut, ipcMain } = require('electron');
const path = require('path');

const SITE = 'https://raseledutools.github.io/rajib-tutorial-web/';
const SITE_ORIGIN = new URL(SITE).origin;
const PACKAGED = app.isPackaged;
let win = null;

if (!app.requestSingleInstanceLock()) app.quit();
app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });

/* Screenshot / screen-record protection. */
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
    if (input.alt && (k === 'arrowleft' || k === 'arrowright')) {
      e.preventDefault();
      const h = wc.navigationHistory;
      if (k === 'arrowleft' && h.canGoBack()) h.goBack();
      if (k === 'arrowright' && h.canGoForward()) h.goForward();
      return;
    }
    if (k === 'f5' || (ctrl && k === 'r')) {
      e.preventDefault();
      if (wc.getURL().startsWith('file:') && win) win.loadURL(SITE);
      else wc.reloadIgnoringCache();
      return;
    }
    if (ctrl && ['p', 's', 'u'].includes(k)) e.preventDefault();
    if (PACKAGED && (k === 'f12' || (ctrl && input.shift && ['i', 'j', 'c'].includes(k)))) e.preventDefault();
  });
  wc.on('context-menu', (e) => e.preventDefault());
}

/* Open a URL in a child window (inside the app, not the system browser).
   Used for YouTube, Google, YouTube Studio and any external https link. */
function openChild(url, title) {
  // If a child with this URL already exists, just focus it
  for (const w of BrowserWindow.getAllWindows()) {
    if (w !== win && !w.isDestroyed()) {
      try { if (w.webContents.getURL() === url) { w.focus(); return; } } catch (_) {}
    }
  }

  const child = new BrowserWindow({
    width: 1200, height: 820,
    minWidth: 800, minHeight: 500,
    autoHideMenuBar: true,
    title: title || 'Rajib Tutorial',
    parent: win || undefined,
    webPreferences: {
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false,
      devTools: !PACKAGED,
    },
  });
  protect(child);
  child.setMenuBarVisibility(false);

  // Top bar with Back / Forward / Close — injected after every navigation
  child.webContents.on('did-finish-load', () => {
    child.webContents.executeJavaScript(`
      (function() {
        if (document.getElementById('__rt_child_bar')) return;
        var bar = document.createElement('div');
        bar.id = '__rt_child_bar';
        bar.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:2147483647;display:flex;align-items:center;gap:4px;padding:4px 8px;background:rgba(20,20,30,.92);box-shadow:0 2px 8px rgba(0,0,0,.4);backdrop-filter:blur(6px);font-family:system-ui,sans-serif;height:42px;';
        function btn(txt, title, fn) {
          var b = document.createElement('button');
          b.textContent = txt; b.title = title;
          b.style.cssText = 'width:32px;height:32px;border:0;border-radius:50%;background:rgba(255,255,255,.12);color:#fff;font-size:16px;cursor:pointer;flex-shrink:0;';
          b.onmouseenter = function(){ b.style.background='rgba(255,255,255,.28)'; };
          b.onmouseleave = function(){ b.style.background='rgba(255,255,255,.12)'; };
          b.onclick = fn; return b;
        }
        bar.appendChild(btn('←','Back', function(){ history.back(); }));
        bar.appendChild(btn('→','Forward', function(){ history.forward(); }));
        bar.appendChild(btn('⟳','Refresh', function(){ location.reload(); }));
        var title = document.createElement('span');
        title.style.cssText = 'flex:1;color:#ddd;font-size:13px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;text-align:center;';
        title.textContent = document.title || location.hostname;
        bar.appendChild(title);
        bar.appendChild(btn('✕','Close window', function(){ window.close(); }));
        (document.body||document.documentElement).prepend(bar);
        // Push content down so bar doesn't overlap
        document.documentElement.style.marginTop = '42px';
      })();
    `).catch(() => {});
  });

  child.loadURL(url);

  // Links inside the child that open new windows → load in same child
  child.webContents.setWindowOpenHandler(({ url: u }) => {
    try {
      const parsed = new URL(u);
      if (parsed.protocol === 'https:') { child.loadURL(u); }
      else if (parsed.protocol === 'mailto:' || parsed.protocol === 'tel:') { shell.openExternal(u); }
    } catch (_) {}
    return { action: 'deny' };
  });
}

function create() {
  win = new BrowserWindow({
    width: 1280, height: 820, minWidth: 900, minHeight: 600, show: false,
    backgroundColor: '#ffffff', title: 'Rajib Tutorial', autoHideMenuBar: true,
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      devTools: !PACKAGED,
      preload: path.join(__dirname, 'preload.js'),
    },
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
  // Links that open new windows from the main site → child window inside app
  wc.setWindowOpenHandler(({ url }) => {
    try {
      const u = new URL(url);
      if (u.protocol === 'https:') openChild(url);
      else if (u.protocol === 'mailto:' || u.protocol === 'tel:') shell.openExternal(url);
    } catch (_) {}
    return { action: 'deny' };
  });
  win.loadURL(SITE);
}

// IPC: website calls window.ElectronShell.openUrl(url) → openChild()
ipcMain.on('shell:openUrl', (_e, url) => {
  try {
    const u = new URL(url);
    if (u.protocol === 'https:') openChild(url);
    else if (u.protocol === 'mailto:' || u.protocol === 'tel:') shell.openExternal(url);
  } catch (_) {}
});

ipcMain.on('nav', (e, act) => {
  const wc = e.sender, h = wc.navigationHistory;
  if (act === 'back' && h.canGoBack()) h.goBack();
  else if (act === 'forward' && h.canGoForward()) h.goForward();
  else if (act === 'reload') {
    if (wc.getURL().startsWith('file:') && win) win.loadURL(SITE);
    else wc.reloadIgnoringCache();
  }
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
