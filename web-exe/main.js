'use strict';
const { app, BrowserWindow, Menu, session, shell, clipboard, globalShortcut, ipcMain, Notification, protocol, net } = require('electron');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');

const SITE = 'https://raseledutools.github.io/rajib-tutorial-web/';
const SITE_ORIGIN = new URL(SITE).origin;
const PACKAGED = app.isPackaged;
let win = null;

if (!app.requestSingleInstanceLock()) app.quit();
app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });


/* ---------- Offline cache for the website shell ----------
   Network-first: the page (and the Firebase SDK it imports) is always fetched fresh when online,
   and a copy is saved in userData/site-cache. Offline, the saved copy is served, so the app still opens.
   Lesson data itself is kept offline by Firestore's own persistent cache (see index.html). */
const CACHE_DIR = () => path.join(app.getPath('userData'), 'site-cache');
const NET_TIMEOUT_MS = 6000;
function cacheable(u) {
  return u.origin === SITE_ORIGIN || (u.hostname === 'www.gstatic.com' && u.pathname.startsWith('/firebasejs/'));
}
function cacheFile(url) {
  return path.join(CACHE_DIR(), crypto.createHash('sha1').update(url).digest('hex'));
}
async function cachedFetch(request) {
  const u = new URL(request.url);
  const hdrs = Object.fromEntries(request.headers);
  // YouTube embeds need the site as Referer
  if (/^(www\.youtube(-nocookie)?\.com)$/.test(u.hostname) && u.pathname.startsWith('/embed/')) hdrs['Referer'] = SITE;
  const pass = () => net.fetch(request.url, {
    method: request.method, headers: hdrs, body: request.body, duplex: 'half',
    bypassCustomProtocolHandlers: true,
  });
  if (request.method !== 'GET' || !cacheable(u) || hdrs['range'] || hdrs['Range']) return pass();

  const file = cacheFile(request.url);
  const readCache = () => {
    try {
      const meta = JSON.parse(fs.readFileSync(file + '.json', 'utf8'));
      const body = fs.readFileSync(file + '.bin');
      return new Response(body, { status: 200, headers: { 'content-type': meta.type || 'application/octet-stream' } });
    } catch (_) { return null; }
  };
  const hasCache = fs.existsSync(file + '.bin');
  try {
    const ctl = new AbortController();
    const t = hasCache ? setTimeout(() => ctl.abort(), NET_TIMEOUT_MS) : null;
    const res = await net.fetch(request.url, { headers: hdrs, bypassCustomProtocolHandlers: true, signal: ctl.signal });
    if (t) clearTimeout(t);
    if (res.ok) {
      const buf = Buffer.from(await res.arrayBuffer());
      try {
        fs.mkdirSync(CACHE_DIR(), { recursive: true });
        fs.writeFileSync(file + '.bin', buf);
        fs.writeFileSync(file + '.json', JSON.stringify({ type: res.headers.get('content-type') || '' }));
      } catch (_) {}
      return new Response(buf, { status: 200, headers: { 'content-type': res.headers.get('content-type') || 'application/octet-stream' } });
    }
    return readCache() || res;
  } catch (err) {
    const c = readCache();
    if (c) return c;
    throw err;
  }
}

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
    backgroundColor: '#0f172a', title: 'Rajib Tutorial', autoHideMenuBar: true,
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
  // Local splash paints almost instantly, so the window appears right after the click.
  win.once('ready-to-show', () => { win.maximize(); win.show(); });
  win.on('closed', () => { win = null; });
  win.setMenuBarVisibility(false);

  const wc = win.webContents;
  // Start loading the real site as soon as the splash is up.
  let siteStarted = false;
  wc.on('did-finish-load', () => {
    if (!siteStarted) { siteStarted = true; win.loadURL(SITE); }
  });
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
  win.loadFile(path.join(__dirname, 'splash.html'));
}


/* ---------- Auto update (GitHub Releases) ----------
   CI publishes a new version on every push to web-exe/**.
   The app silently checks every 5 seconds; when a newer version exists it shows a
   Windows notification and a banner at the top. A fixed button (bottom-right) lets the
   user check manually. Clicking the update button downloads and restarts into the new version. */
const CHECK_EVERY_MS = 5000;
let autoUpdater = null;
let checking = false;
let flashTimer = null;
let updateState = { status: 'idle', version: null, percent: 0, current: app.getVersion() };

function sendUpdateState() {
  if (win && !win.isDestroyed()) win.webContents.send('update:state', updateState);
}
function setUpdate(patch) { updateState = Object.assign({}, updateState, patch); sendUpdateState(); }
// short-lived message on the manual button (up to date / error), then back to idle
function flash(status) {
  setUpdate({ status });
  clearTimeout(flashTimer);
  flashTimer = setTimeout(() => { if (updateState.status === status) setUpdate({ status: 'idle' }); }, 3500);
}

function startDownload() {
  if (!autoUpdater || updateState.status !== 'available') return;
  setUpdate({ status: 'downloading', percent: 0 });
  autoUpdater.downloadUpdate().catch(() => setUpdate({ status: 'available' }));
}

// manual = true when the user pressed the check button (shows progress/result)
function checkNow(manual) {
  if (!autoUpdater) { if (manual) flash('uptodate'); return; }
  const st = updateState.status;
  if (checking || st === 'downloading' || st === 'installing') return;
  if (manual) { clearTimeout(flashTimer); setUpdate({ status: 'checking' }); }
  checking = true;
  autoUpdater.checkForUpdates()
    .then((r) => {
      // update-available / update-not-available events do the state change; this covers the manual "no update" case
      if (manual && updateState.status === 'checking') flash('uptodate');
      return r;
    })
    .catch(() => { if (manual) flash('error'); else if (updateState.status === 'checking') setUpdate({ status: 'idle' }); })
    .finally(() => { checking = false; });
}

function initUpdater() {
  if (!PACKAGED) return; // no updates in dev (npm start)
  try { autoUpdater = require('electron-updater').autoUpdater; } catch (_) { return; }
  autoUpdater.autoDownload = false;
  autoUpdater.autoInstallOnAppQuit = true;

  autoUpdater.on('update-available', (info) => {
    const first = updateState.version !== info.version;
    setUpdate({ status: 'available', version: info.version, percent: 0 });
    if (first && Notification.isSupported()) {
      const n = new Notification({
        title: 'Rajib Tutorial — নতুন আপডেট',
        body: 'Version ' + info.version + ' এসেছে। আপডেট করতে ক্লিক করুন।',
        icon: path.join(__dirname, 'build', 'icon.png'),
      });
      n.on('click', () => { if (win) { if (win.isMinimized()) win.restore(); win.show(); win.focus(); } startDownload(); });
      n.show();
    }
  });
  autoUpdater.on('update-not-available', () => {
    if (updateState.status === 'available') setUpdate({ status: 'idle', version: null });
  });
  autoUpdater.on('download-progress', (p) => setUpdate({ status: 'downloading', percent: Math.round(p.percent || 0) }));
  autoUpdater.on('update-downloaded', () => {
    setUpdate({ status: 'installing', percent: 100 });
    // silent install, then relaunch the new version
    setTimeout(() => autoUpdater.quitAndInstall(true, true), 800);
  });
  autoUpdater.on('error', () => {
    if (updateState.status === 'downloading') setUpdate({ status: 'available' });
  });

  checkNow(false);
  setInterval(() => checkNow(false), CHECK_EVERY_MS).unref();
}

ipcMain.on('update:install', () => startDownload());
ipcMain.on('update:check', () => checkNow(true));
ipcMain.on('update:get', () => sendUpdateState());

// IPC: website → native Windows notification (admin panel push)
ipcMain.on('notify:show', (_e, title, body) => {
  if (!Notification.isSupported()) return;
  const n = new Notification({
    title: String(title || 'Rajib Tutorial'),
    body: String(body || ''),
    icon: path.join(__dirname, 'build', 'icon.png'),
  });
  n.on('click', () => {
    if (win) { if (win.isMinimized()) win.restore(); win.show(); win.focus(); }
  });
  n.show();
});

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

app.setAppUserModelId('com.rajibtutorial.webexe'); // needed for Windows notifications
app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  try { session.defaultSession.protocol.handle('https', cachedFetch); } catch (_) {}
  session.defaultSession.setPermissionRequestHandler((_wc, perm, cb) =>
    cb(['media', 'fullscreen', 'clipboard-sanitized-write', 'notifications'].includes(perm)));
  session.defaultSession.webRequest.onBeforeSendHeaders(
    { urls: ['https://www.youtube.com/embed/*', 'https://www.youtube-nocookie.com/embed/*'] },
    (d, cb) => { d.requestHeaders['Referer'] = SITE; cb({ requestHeaders: d.requestHeaders }); });
  try { session.defaultSession.preconnect({ url: SITE_ORIGIN, numSockets: 2 }); } catch (_) {}
  create();
  setTimeout(initUpdater, 1500);
  try { globalShortcut.register('PrintScreen', () => { try { clipboard.clear(); } catch (_) {} }); } catch (_) {}
});
app.on('will-quit', () => { try { globalShortcut.unregisterAll(); } catch (_) {} });
app.on('window-all-closed', () => app.quit());
