'use strict';
const { app, BrowserWindow, Menu, Tray, nativeImage, session, shell, clipboard, globalShortcut, ipcMain, Notification, protocol, net } = require('electron');
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
]);

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
function initVideos() {
  vids = vidstore.create(path.join(app.getPath('userData'), 'offline-videos'), (ev) => {
    if (win && !win.isDestroyed()) win.webContents.send('vid:event', ev);
  });
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
function protect(w) {
  // Re-calling setContentProtection (the old 2s timer + focus/maximize hooks) forces DWM to recompose
  // the window, which showed up as video stutter / endless buffering. The affinity sticks, so set it once
  // and again only after the window is shown.
  let on = false;
  const apply = () => { if (on) return; try { w.setContentProtection(true); on = true; } catch (_) {} };
  apply();
  w.once('show', apply);
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
    if (!siteStarted) { siteStarted = true; win.loadURL(SITE); return; }
    if (!offlineHandling && !warmedOnce && wc.getURL().startsWith(SITE_ORIGIN)) setTimeout(warmCache, 3000);
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

app.on('web-contents-created', (_e, wc) => guard(wc));

app.setAppUserModelId('com.rajibtutorial.webexe'); // needed for Windows notifications
app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
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
  create();
  initTray();
  focus.init({ getMainWin: () => win, onStart: () => applyAutostart(true), onActiveChange: refreshTrayMenu });
  applyAutostart(autostartOn() || focus.isActive());
  setTimeout(initUpdater, 1500);
  try { globalShortcut.register('PrintScreen', () => { try { clipboard.clear(); } catch (_) {} }); } catch (_) {}
});
app.on('will-quit', () => { try { globalShortcut.unregisterAll(); } catch (_) {} });
app.on('window-all-closed', () => { if (!tray || forceQuit) app.quit(); });
