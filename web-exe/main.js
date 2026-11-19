'use strict';
const { app, BrowserWindow, Menu, Tray, nativeImage, session, shell, clipboard, globalShortcut, ipcMain, Notification, protocol, net, safeStorage } = require('electron');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { Readable } = require('stream');
const vidstore = require('./vidstore');
const focus = require('./focus');

const SITE = 'https://raseledutools.github.io/rajib-tutorial-web/';
const SITE_ORIGIN = new URL(SITE).origin;
const PACKAGED = app.isPackaged;
let win = null;
let tray = null;
let forceQuit = false; // true only for a real quit (tray → Quit, installing an update, Windows shutdown)
let startHidden = process.argv.includes('--hidden'); // started by Windows at login: stay in the tray

// rtvid://  — plays saved lesson videos from disk (with Range support so seeking works)
protocol.registerSchemesAsPrivileged([
  { scheme: 'rtvid', privileges: { standard: true, secure: true, stream: true, supportFetchAPI: true, bypassCSP: true } },
  { scheme: 'rtfocus', privileges: { standard: true, secure: true } },   // Focus Mode page, shown inside the app layout (<webview> in index.html)
]);
const FOCUS_SRC = 'rtfocus://app/focus.html';
const FOCUS_PARTITION = 'rtfocus';

// Smooth video: keep GPU decoding / rasterization on and never let the renderer be throttled.
app.commandLine.appendSwitch('ignore-gpu-blocklist');
app.commandLine.appendSwitch('enable-gpu-rasterization');
app.commandLine.appendSwitch('enable-zero-copy');
app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.commandLine.appendSwitch('disable-features', 'CalculateNativeWinOcclusion');
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

if (!app.requestSingleInstanceLock()) app.quit();
app.on('second-instance', () => { showMain(); });


/* ---------- Offline cache for the website shell ----------
   ONLINE: no https interception at all (video / YouTube behave exactly like in a normal browser).
   The shell (page + Firebase SDK) is only copied to userData/site-cache in the background (warmCache).
   OFFLINE: the https handler is switched on and serves that copy; it is switched off again as soon as
   the site is reachable. Lesson data stays offline through Firestore's own persistent cache (index.html). */
const CACHE_DIR = () => path.join(app.getPath('userData'), 'site-cache');
const FB = 'https://www.gstatic.com/firebasejs/10.12.2/';
const WARM_URLS = [SITE, FB + 'firebase-app.js', FB + 'firebase-firestore.js', FB + 'firebase-messaging.js'];
function cacheable(u) {
  return u.origin === SITE_ORIGIN || (u.hostname === 'www.gstatic.com' && u.pathname.startsWith('/firebasejs/'));
}
function cacheFile(url) {
  return path.join(CACHE_DIR(), crypto.createHash('sha1').update(url).digest('hex'));
}
function hasCache(url) { return fs.existsSync(cacheFile(url) + '.bin'); }
function readCache(url) {
  const file = cacheFile(url);
  try {
    const meta = JSON.parse(fs.readFileSync(file + '.json', 'utf8'));
    return new Response(fs.readFileSync(file + '.bin'), { status: 200, headers: meta.headers || {} });
  } catch (_) { return null; }
}
let warming = false, warmedOnce = false;
async function warmCache() {
  if (warming) return;
  warming = true; warmedOnce = true;
  try {
    for (const url of WARM_URLS) {
      try {
        const res = await net.fetch(url, { bypassCustomProtocolHandlers: true });
        if (!res.ok) continue;
        const buf = Buffer.from(await res.arrayBuffer());
        const headers = {};
        res.headers.forEach((v, k) => { if (!['content-encoding', 'content-length', 'transfer-encoding'].includes(k)) headers[k] = v; });
        fs.mkdirSync(CACHE_DIR(), { recursive: true });
        fs.writeFileSync(cacheFile(url) + '.bin', buf);
        fs.writeFileSync(cacheFile(url) + '.json', JSON.stringify({ headers }));
      } catch (_) {}
    }
  } finally { warming = false; }
}

let offlineHandling = false, probeTimer = null;
async function offlineFetch(request) {
  const u = new URL(request.url);
  if (request.method === 'GET' && cacheable(u)) {
    const c = readCache(request.url) ||
      (u.origin === SITE_ORIGIN && /\/index\.html$/.test(u.pathname) ? readCache(SITE) : null);
    if (c) return c;
  }
  return net.fetch(request, { bypassCustomProtocolHandlers: true });
}
function setOfflineHandling(on) {
  if (on === offlineHandling) return;
  try {
    if (on) session.defaultSession.protocol.handle('https', offlineFetch);
    else session.defaultSession.protocol.unhandle('https');
    offlineHandling = on;
  } catch (_) { return; }
  clearInterval(probeTimer);
  if (on) {
    // while offline, probe the site; once reachable, drop the handler (back to plain browser behaviour)
    probeTimer = setInterval(async () => {
      try {
        const r = await net.fetch(SITE, { method: 'HEAD', bypassCustomProtocolHandlers: true, signal: AbortSignal.timeout(4000) });
        if (r.ok) { setOfflineHandling(false); warmCache(); }
      } catch (_) {}
    }, 5000);
    probeTimer.unref();
  }
}

/* ---------- Offline lesson videos ----------
   Direct video files are saved to disk by the main process (userData/offline-videos): resumable,
   not held in RAM, served back through rtvid:// . YouTube lessons can't be saved. */
let vids = null;
function trusted(e) { return !!win && !win.isDestroyed() && e.sender === win.webContents; }
// Random 32-byte key, stored sealed with Windows DPAPI (safeStorage): only this Windows user on this PC can unseal it.
function vidKey(keyFile) {
  const legacy = path.join(app.getPath('userData'), 'Cache', 'rt-k.bin');   // old location (inside Chromium's Cache dir)
  try {
    if (safeStorage && safeStorage.isEncryptionAvailable()) {
      // move the key out of Chromium's Cache folder (cleaners / Storage Sense can wipe it)
      if (!fs.existsSync(keyFile) && fs.existsSync(legacy)) { try { fs.mkdirSync(path.dirname(keyFile), { recursive: true }); fs.copyFileSync(legacy, keyFile); } catch (_) {} }
      if (fs.existsSync(keyFile)) return Buffer.from(safeStorage.decryptString(fs.readFileSync(keyFile)), 'hex');
      const k = require('crypto').randomBytes(32);
      fs.mkdirSync(path.dirname(keyFile), { recursive: true });
      fs.writeFileSync(keyFile, safeStorage.encryptString(k.toString('hex')));
      return k;
    }
  } catch (_) {}
  return null;   // no DPAPI: saved as plain (hidden) files
}
function initVideos() {
  const ud = app.getPath('userData');
  // Permanent home: userData/offline-store (NOT inside Chromium's "Cache" folder, which can be cleared).
  const store = path.join(ud, 'offline-store');
  const newDir = path.join(store, 'vd'), keyFile = path.join(store, 'k.bin');
  const oldDirs = [path.join(ud, 'Cache', 'rt-vd'), path.join(ud, 'offline-videos')];
  try {
    fs.mkdirSync(store, { recursive: true });
    if (!fs.existsSync(newDir)) {
      for (const od of oldDirs) { if (fs.existsSync(od)) { try { fs.renameSync(od, newDir); break; } catch (_) {} } }
    }
  } catch (_) {}
  vids = vidstore.create(newDir, (ev) => {
    if (win && !win.isDestroyed()) win.webContents.send('vid:event', ev);
  }, vidKey(keyFile));
  protocol.handle('rtvid', (request) => {
    try {
      const id = decodeURIComponent(new URL(request.url).pathname.slice(1));
      const r = vids.open(id, request.headers.get('range'));
      if (!r) return new Response('not found', { status: 404 });
      if (!r.stream) return new Response(null, { status: r.status, headers: r.headers });
      return new Response(Readable.toWeb(r.stream), { status: r.status, headers: r.headers });
    } catch (_) { return new Response('error', { status: 500 }); }
  });
}
ipcMain.handle('vid:list', (e) => (trusted(e) && vids ? vids.list() : []));
ipcMain.handle('vid:active', (e) => (trusted(e) && vids ? vids.activeList() : []));
ipcMain.handle('vid:has', (e, id, url) => (trusted(e) && vids ? vids.info(String(id), String(url || '')) : null));
ipcMain.handle('vid:start', (e, o) => (trusted(e) && vids ? vids.start(o) : { error: 'unavailable' }));
ipcMain.handle('vid:remove', (e, id) => (trusted(e) && vids ? vids.remove(String(id)) : false));
ipcMain.on('vid:cancel', (e, id) => { if (trusted(e) && vids) vids.cancel(String(id)); });

/* Screenshot / screen-record protection. */
// Applied to EVERY window (main, child, sign-in pop-ups). A single call before the window is shown is not enough on
// Windows: the display affinity can be lost when the window is shown / maximised / restored / full-screened, so it is
// re-applied on those events (debounced) and by a slow heartbeat. A fast 2 s timer caused video stutter, so none of this
// runs often: only on state changes and once a minute while the window is visible.
const protectedWins = new WeakSet();
function protect(w) {
  if (!w || protectedWins.has(w)) return;
  protectedWins.add(w);
  const apply = () => { try { if (!w.isDestroyed()) w.setContentProtection(true); } catch (_) {} };
  let t = null;
  const soon = () => { clearTimeout(t); t = setTimeout(apply, 250); };
  apply();
  ['show', 'restore', 'maximize', 'unmaximize', 'enter-full-screen', 'leave-full-screen', 'ready-to-show'].forEach((ev) => { try { w.on(ev, soon); } catch (_) {} });
  w.on('show', () => { setTimeout(apply, 800); setTimeout(apply, 3000); });
  const hb = setInterval(() => {
    try { if (w.isDestroyed()) { clearInterval(hb); return; } if (w.isVisible() && !w.isMinimized()) apply(); } catch (_) {}
  }, 60000);
  hb.unref();
  w.on('closed', () => { clearInterval(hb); clearTimeout(t); });
}
app.on('browser-window-created', (_e, w) => protect(w));

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

// A new exe build drops the old HTTP cache once, so the newest website (GitHub Pages caches pages for ~10 min) loads at the first start.
async function freshCacheOnce() {
  try {
    const f = path.join(app.getPath('userData'), 'cache-ver.txt');
    let prev = ''; try { prev = fs.readFileSync(f, 'utf8'); } catch (_) {}
    if (prev !== app.getVersion()) {
      await session.defaultSession.clearCache();
      fs.writeFileSync(f, app.getVersion());
    }
  } catch (_) {}
}

function create() {
  win = new BrowserWindow({
    width: 1280, height: 820, minWidth: 900, minHeight: 600, show: false,
    backgroundColor: '#ffffff', title: 'Rajib Tutorial', autoHideMenuBar: true,
    // custom title bar (drawn by preload.js); Windows draws only the min / max / close buttons
    titleBarStyle: 'hidden',
    titleBarOverlay: { color: '#ffffff', symbolColor: '#1f2937', height: 36 },
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      devTools: !PACKAGED,
      backgroundThrottling: false,
      webviewTag: true, // embedded YouTube / Studio / Google view (index.html → openExtView)
      preload: path.join(__dirname, 'preload.js'),
    },
  });
  protect(win);
  // Local splash paints almost instantly, so the window appears right after the click.
  win.once('ready-to-show', () => { win.maximize(); if (!startHidden) win.show(); startHidden = false; });
  // Closing the window only hides it to the tray, so update / admin-panel notifications and Focus Mode keep working.
  win.on('close', (e) => {
    if (forceQuit || !tray) return;
    e.preventDefault();
    win.hide();
    if (!hintedTray && Notification.isSupported()) {
      hintedTray = true;
      new Notification({ title: 'Rajib Tutorial', body: 'অ্যাপ background এ চলছে (system tray)। পুরোপুরি বন্ধ করতে tray আইকনে right-click → Quit।' }).show();
    }
  });
  win.on('closed', () => { win = null; });
  win.setMenuBarVisibility(false);

  const wc = win.webContents;
  // Start loading the real site as soon as the splash is up.
  let siteStarted = false;
  wc.on('did-finish-load', () => {
    if (!siteStarted) { siteStarted = true; freshCacheOnce().finally(() => { if (win) win.loadURL(SITE); }); return; }
    if (!offlineHandling && !warmedOnce && wc.getURL().startsWith(SITE_ORIGIN)) setTimeout(warmCache, 3000);
  });
  wc.on('did-finish-load', () => {
    if (pendingFocusView && wc.getURL().startsWith(SITE_ORIGIN)) { pendingFocusView = false; setTimeout(sendFocusShow, 600); }
  });
  wc.on('did-fail-load', (_e, code, _d, url, isMain) => {
    if (!isMain || code === -3 || !url.startsWith('http')) return;
    // network failed: if a saved copy of the site exists, serve it; otherwise show the offline page
    if (!offlineHandling && hasCache(SITE)) { setOfflineHandling(true); win.loadURL(SITE); return; }
    win.loadFile(path.join(__dirname, 'offline.html'));
  });
  wc.on('will-navigate', (e, url) => {
    if (url === 'app://retry') { e.preventDefault(); setOfflineHandling(false); win.loadURL(SITE); }
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



/* ---------- Tray, background mode, start with Windows ----------
   The window hides instead of closing, so the site's admin-panel notifications and the update check keep
   running while the window is closed. Focus Mode can't be quit while a session is active. */
let hintedTray = false;
const PREFS = () => path.join(app.getPath('userData'), 'prefs.json');
function readPrefs() { try { return JSON.parse(fs.readFileSync(PREFS(), 'utf8')) || {}; } catch (_) { return {}; } }
function writePrefs(p) { try { fs.writeFileSync(PREFS(), JSON.stringify(p)); } catch (_) {} }
function autostartOn() { const p = readPrefs(); return p.autostart !== false; } // default: on
function applyAutostart(on) {
  if (!PACKAGED) return; // never register the dev electron.exe
  try { app.setLoginItemSettings({ openAtLogin: !!on, args: ['--hidden'] }); } catch (_) {}
}
function showMain() {
  if (!win || win.isDestroyed()) { startHidden = false; create(); return; }
  if (win.isMinimized()) win.restore();
  win.show(); win.focus();
}
function trayMenu() {
  const active = focus.isActive();
  return Menu.buildFromTemplate([
    { label: 'Open Rajib Tutorial', click: showMain },
    { label: active ? 'Focus Mode (চলছে)' : 'Focus Mode', click: () => focus.openWindow() },
    { type: 'separator' },
    {
      label: 'Start with Windows', type: 'checkbox', checked: autostartOn() || active, enabled: !active,
      click: (item) => { const p = readPrefs(); p.autostart = item.checked; writePrefs(p); applyAutostart(item.checked); },
    },
    { type: 'separator' },
    { label: active ? 'Quit (Focus চলার সময় বন্ধ করা যাবে না)' : 'Quit', enabled: !active, click: () => { forceQuit = true; app.quit(); } },
  ]);
}
function initTray() {
  try {
    const img = nativeImage.createFromPath(path.join(__dirname, 'build', 'icon.png'));
    if (img.isEmpty()) return; // no icon → no tray → the close button keeps its normal behaviour
    tray = new Tray(img.resize({ width: 16, height: 16 }));
    tray.setToolTip('Rajib Tutorial');
    tray.setContextMenu(trayMenu());
    tray.on('click', showMain);
  } catch (_) { tray = null; }
}
// Focus Mode now opens INSIDE the app (a view of the website: sidebar + title bar stay) instead of replacing the window.
let pendingFocusView = false;
function sendFocusShow() { if (win && !win.isDestroyed()) win.webContents.send('focus:show'); }
function openFocusView() {
  showMain();
  if (win && !win.isDestroyed() && win.webContents.getURL().startsWith(SITE_ORIGIN)) sendFocusShow();
  else pendingFocusView = true; // site not loaded yet (splash / offline page): open the view as soon as it is
}
function refreshTrayMenu() { if (tray) tray.setContextMenu(trayMenu()); }

app.on('before-quit', (e) => {
  if (!forceQuit && focus.isActive()) e.preventDefault(); // a Focus session is running
});
app.on('session-end', () => { forceQuit = true; });
app.on('will-quit', () => { focus.stop(); });

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
    setTimeout(() => { forceQuit = true; autoUpdater.quitAndInstall(true, true); }, 800);
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

const EXT_PARTITION = 'persist:rtext';
// the course-video page shown by index.html (wvPlayer): youtube.com/embed/<id> or /watch?v=<id>, opened with "#rtlesson"
function isLessonPage(wc) {
  try { const u = new URL(wc.getURL()); return u.hostname === 'www.youtube.com' && /(^|[#&])rtlesson/.test(u.hash); } catch (_) { return false; }
}
// the local Focus Mode page shown in a <webview> inside the app layout (see index.html openFocus)
function isFocusGuest(wc) { try { return wc.getURL().startsWith('rtfocus://'); } catch (_) { return false; } }
// Ad / ad-tracking networks: blocked for every site opened in the embedded view (these domains serve ads only).
const AD_DOMAINS = [
  'doubleclick.net', 'googlesyndication.com', 'googleadservices.com', 'googletagservices.com', 'adservice.google.com', '2mdn.net',
  'moatads.com', 'adnxs.com', 'adsrvr.org', 'advertising.com', 'adform.net', 'rubiconproject.com', 'pubmatic.com', 'openx.net',
  'criteo.com', 'criteo.net', 'taboola.com', 'outbrain.com', 'amazon-adsystem.com', 'casalemedia.com', '33across.com',
  'smartadserver.com', 'mgid.com', 'revcontent.com', 'popads.net', 'popcash.net', 'propellerads.com', 'exoclick.com', 'exosrv.com',
  'juicyads.com', 'trafficjunky.net', 'adsterra.com', 'highperformanceformat.com', 'effectivegatecpm.com', 'onclickads.net',
  'adcash.com', 'a-ads.com', 'ad-maven.com', 'admaven.com', 'yieldmo.com', 'sharethrough.com', 'teads.tv', 'zedo.com', 'adtng.com',
  'bidswitch.net', 'contextweb.com', 'lijit.com', 'sovrn.com', 'indexww.com', 'triplelift.com', 'smaato.net', 'inmobi.com',
  'adroll.com', 'adthrive.com', 'adskeeper.com', 'adcolony.com', 'applovin.com', 'unity3d.com/ads',
].filter((d) => !d.includes('/'));
const AUTH_HOST_RE = /(^|\.)(accounts\.google\.com|appleid\.apple\.com|login\.microsoftonline\.com|login\.live\.com|www\.facebook\.com)$/i;
const AD_HOST_RE = new RegExp('(^|\\.)(' + AD_DOMAINS.map((d) => d.replace(/\./g, '\\.')).join('|') + ')$', 'i');
app.on('web-contents-created', (_e, wc) => {
  guard(wc);
  // The main window may embed external sites in a <webview>; lock those guests down.
  wc.on('will-attach-webview', (e, webPreferences, params) => {
    let ok = false;
    const isFocus = params.src === FOCUS_SRC && params.partition === FOCUS_PARTITION;
    try { ok = new URL(params.src).protocol === 'https:' && params.partition === EXT_PARTITION; } catch (_) {}
    if (!ok && !isFocus) { e.preventDefault(); return; }
    delete webPreferences.preloadURL;
    // external sites: ext-preload.js makes the page look like normal Chrome (Google sign-in); Focus page: its own small API bridge
    webPreferences.preload = path.join(__dirname, isFocus ? 'focus-preload.js' : 'ext-preload.js');
    webPreferences.nodeIntegration = false;
    webPreferences.contextIsolation = true;
    webPreferences.sandbox = true;
    webPreferences.webSecurity = true;
    webPreferences.devTools = !PACKAGED;
    // course video page ("#rtlesson", see ext-preload.js): start playing without a click on the page itself
    if (/#rtlesson/.test(String(params.src))) webPreferences.autoplayPolicy = 'no-user-gesture-required';
  });
  if (wc.getType() === 'window') {
    try {
      if (wc.session === session.fromPartition(EXT_PARTITION)) {
        wc.setWindowOpenHandler(({ url: u }) => {
          try { const p = new URL(u); if (p.protocol === 'https:' && !AD_HOST_RE.test(p.hostname)) wc.loadURL(u); } catch (_) {}
          return { action: 'deny' };
        });
      }
    } catch (_) {}
  }
  if (wc.getType() === 'webview') {
    // links that want a new window just navigate the embedded page
    wc.setWindowOpenHandler(({ url: u, disposition }) => {
      try {
        if (isLessonPage(wc) || isFocusGuest(wc)) return { action: 'deny' };                    // course video: nothing may open from it
        const parsed = new URL(u);
        if (AD_HOST_RE.test(parsed.hostname)) return { action: 'deny' };   // ad pop-up / pop-under
        if (parsed.protocol === 'https:') {
          // Sign-in flows ("Continue with Google" etc.) open a pop-up and talk to it through window.opener / postMessage.
          // Loading it inside the same tab breaks the login, so real pop-ups are kept as pop-up windows (same session).
          const isPopup = disposition === 'new-window' || AUTH_HOST_RE.test(parsed.hostname);
          if (isPopup) {
            const parent = wc.hostWebContents && !wc.hostWebContents.isDestroyed() ? BrowserWindow.fromWebContents(wc.hostWebContents) : null;
            return {
              action: 'allow',
              overrideBrowserWindowOptions: {
                width: 520, height: 720, autoHideMenuBar: true, title: 'Sign in', parent: parent || undefined,
                webPreferences: { preload: path.join(__dirname, 'ext-preload.js'), sandbox: true, contextIsolation: true, nodeIntegration: false, devTools: !PACKAGED },
              },
            };
          }
          wc.loadURL(u);
        } else if (parsed.protocol === 'mailto:' || parsed.protocol === 'tel:') shell.openExternal(u);
      } catch (_) {}
      return { action: 'deny' };
    });
    wc.on('will-navigate', (ev, u) => { if (!/^https:/i.test(u) || isLessonPage(wc)) ev.preventDefault(); });
    // Safe Browser shortcuts (F11 / Esc / Ctrl+T, W, L, R, D, Tab) typed while the page inside the <webview> has focus
    // never reach the app window — pass them on, so full screen and tabs work exactly like in Chrome.
    // right-click inside the page → Chrome-style menu drawn by index.html (Safe Browser tabs only, never the course video)
    wc.on('context-menu', (_ev, params) => {
      try {
        if (isLessonPage(wc) || isFocusGuest(wc) || !wc.hostWebContents || wc.hostWebContents.isDestroyed()) return;
        wc.hostWebContents.send('bt:ctx', JSON.stringify({ x: params.x, y: params.y, linkURL: params.linkURL || '', srcURL: params.srcURL || '', mediaType: params.mediaType || '', selectionText: (params.selectionText || '').slice(0, 300), isEditable: !!params.isEditable }));
      } catch (_) {}
    });
    wc.on('before-input-event', (_ev, input) => {
      try {
        if (input.type !== 'keyDown' || isLessonPage(wc) || isFocusGuest(wc)) return;
        const k = input.key, ctrl = !!(input.control || input.meta);
        const fwd = k === 'F11' || k === 'F5' || k === 'Escape' || (ctrl && (/^[twlrdfhp0-9=+\-_]$/i.test(k) || k === 'Tab')) || (input.alt && (k === 'ArrowLeft' || k === 'ArrowRight'));
        if (fwd && wc.hostWebContents && !wc.hostWebContents.isDestroyed()) {
          wc.hostWebContents.send('bt:key', { key: k, ctrlKey: ctrl, shiftKey: !!input.shift, altKey: !!input.alt });
        }
      } catch (_) {}
    });
  }
});

app.setAppUserModelId('com.rajibtutorial.webexe'); // needed for Windows notifications
app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  {
    // Embedded sites (YouTube, Studio, Google): own persistent session so logins are kept, and a normal
    // Chrome user-agent (Google refuses sign-in from browsers that announce "Electron").
    const ext = session.fromPartition(EXT_PARTITION);
    ext.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/' + process.versions.chrome + ' Safari/537.36');
    // Client-hint headers: send the same "Google Chrome" brands as the user-agent (no "Electron" anywhere).
    const major = String(process.versions.chrome).split('.')[0];
    const brands = '"Chromium";v="' + major + '", "Google Chrome";v="' + major + '", "Not_A Brand";v="24"';
    const fullList = '"Chromium";v="' + process.versions.chrome + '", "Google Chrome";v="' + process.versions.chrome + '", "Not_A Brand";v="24.0.0.0"';
    // YouTube ads: cancel the ad / tracking requests (only in this embedded session; ext-preload.js handles the rest)
    ext.webRequest.onBeforeRequest({ urls: [
      '*://*.doubleclick.net/*', '*://*.googlesyndication.com/*', '*://*.googleadservices.com/*', '*://adservice.google.com/*',
      '*://*.youtube.com/pagead/*', '*://*.youtube.com/api/stats/ads*', '*://*.youtube.com/ptracking*',
      '*://*.youtube.com/get_midroll_info*', '*://*.youtube.com/youtubei/v1/player/ad_break*',
      ...AD_DOMAINS.map((d) => '*://*.' + d + '/*'),
    ] }, (_d, cb) => cb({ cancel: true }));
    ext.webRequest.onBeforeSendHeaders((details, cb) => {
      const h = details.requestHeaders;
      if (details.resourceType === 'mainFrame' && /^https:\/\/www\.youtube\.com\/embed\//.test(details.url)) h['Referer'] = SITE;
      for (const k of Object.keys(h)) {
        const l = k.toLowerCase();
        if (l === 'sec-ch-ua') h[k] = brands;
        else if (l === 'sec-ch-ua-full-version-list') h[k] = fullList;
        else if (l === 'sec-ch-ua-mobile') h[k] = '?0';
        else if (l === 'sec-ch-ua-platform') h[k] = '"Windows"';
      }
      cb({ requestHeaders: h });
    });
    ext.setPermissionRequestHandler((_wc, perm, cb) =>
      cb(['media', 'fullscreen', 'clipboard-sanitized-write', 'notifications'].includes(perm)));
  }
  session.defaultSession.setPermissionRequestHandler((_wc, perm, cb) =>
    cb(['media', 'fullscreen', 'clipboard-sanitized-write', 'notifications'].includes(perm)));
  session.defaultSession.webRequest.onBeforeSendHeaders(
    { urls: ['https://www.youtube.com/embed/*', 'https://www.youtube-nocookie.com/embed/*'] },
    (d, cb) => { d.requestHeaders['Referer'] = SITE; cb({ requestHeaders: d.requestHeaders }); });
  try { session.defaultSession.preconnect({ url: SITE_ORIGIN, numSockets: 2 }); } catch (_) {}
  // pre-open connections to YouTube so a lesson starts playing sooner
  ['https://www.youtube.com', 'https://www.youtube-nocookie.com', 'https://i.ytimg.com', 'https://s.ytimg.com']
    .forEach(u => { try { session.defaultSession.preconnect({ url: u, numSockets: 2 }); } catch (_) {} });
  try { initVideos(); } catch (_) {}
  // rtfocus://app/focus.html  -> the local Focus Mode page (only this one file is ever served)
  try {
    protocol.handle('rtfocus', (request) => {
      try {
        if (new URL(request.url).pathname !== '/focus.html') return new Response('not found', { status: 404 });
        return new Response(fs.readFileSync(path.join(__dirname, 'focus.html')), { status: 200, headers: { 'content-type': 'text/html; charset=utf-8' } });
      } catch (_) { return new Response('error', { status: 500 }); }
    });
  } catch (_) {}
  create();
  initTray();
  focus.init({ getMainWin: () => win, onStart: () => applyAutostart(true), onActiveChange: refreshTrayMenu, openView: openFocusView });
  applyAutostart(autostartOn() || focus.isActive());
  setTimeout(initUpdater, 1500);
  try { globalShortcut.register('PrintScreen', () => { try { clipboard.clear(); } catch (_) {} }); } catch (_) {}
});
app.on('will-quit', () => { try { globalShortcut.unregisterAll(); } catch (_) {} });
app.on('window-all-closed', () => { if (!tray || forceQuit) app.quit(); });
