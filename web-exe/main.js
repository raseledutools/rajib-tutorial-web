'use strict';
const { app, BrowserWindow, Menu, Tray, nativeImage, session, shell, clipboard, globalShortcut, ipcMain, Notification, protocol, net, safeStorage, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { Readable } = require('stream');
const vidstore = require('./vidstore');
const focus = require('./focus');
const pdfreader = require('./pdfreader');

const SITE = 'https://raseledutools.github.io/rajib-tutorial-web/';
const SITE_ORIGIN = new URL(SITE).origin;
const PACKAGED = app.isPackaged;
let win = null;
let tray = null;
let bgIcon = false; // the tray icon image loaded → the app is allowed to keep running in the background (with or without a visible tray icon)
let forceQuit = false; // true only for a real quit (tray → Quit, installing an update, Windows shutdown)
let startHidden = process.argv.includes('--hidden'); // started by Windows at login: stay in the tray

// rtvid://  — plays saved lesson videos from disk (with Range support so seeking works)
protocol.registerSchemesAsPrivileged([
  { scheme: 'rtvid', privileges: { standard: true, secure: true, stream: true, supportFetchAPI: true, bypassCSP: true } },
  { scheme: 'rtpdf', privileges: { standard: true, secure: true, stream: true, supportFetchAPI: true, corsEnabled: true } },   // PDF Reader (pdf.js viewer + the opened PDF), see pdfreader.js
  { scheme: 'rtfocus', privileges: { standard: true, secure: true } },   // Focus Mode page, shown inside the app layout (<webview> in index.html)
]);
const FOCUS_SRC = 'rtfocus://app/focus.html';
const FOCUS_PARTITION = 'rtfocus';

// Black-screen safety: 'ignore-gpu-blocklist' and 'enable-zero-copy' forced the GPU path on machines whose driver Chromium
// had deliberately blocked -> pages turned black / flickered. They are gone. If the GPU process still crashes twice
// (see below), or the user turns on "compatibility mode", the app starts with hardware acceleration off.
let GPU_OFF = false;
try { GPU_OFF = !!(JSON.parse(fs.readFileSync(path.join(app.getPath('userData'), 'prefs.json'), 'utf8')) || {}).gpuOff; } catch (_) {}
if (GPU_OFF) app.disableHardwareAcceleration();
else app.commandLine.appendSwitch('enable-gpu-rasterization');
// keep the renderer from being throttled
app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.commandLine.appendSwitch('disable-features', 'CalculateNativeWinOcclusion,DirectCompositionVideoOverlays');
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

let gpuCrashes = 0;
app.on('child-process-gone', (_e, d) => {
  try {
    if (!d || d.type !== 'GPU' || d.reason === 'clean-exit' || GPU_OFF) return;
    if (++gpuCrashes < 2) return;
    const p = readPrefs(); p.gpuOff = true; writePrefs(p);   // next start: no hardware acceleration
    forceQuit = true; app.relaunch(); app.exit(0);
  } catch (_) {}
});

if (!app.requestSingleInstanceLock()) app.quit();
// PDF Reader context (pdfreader.js): Windows starts us with a .pdf path when the user opens a PDF from Explorer
const pdfCtx = {
  ipcMain, dialog, shell, session, protocol,
  getWin: () => win, trusted: (e) => trusted(e), showMain: () => showMain(),
  ready: () => { try { return !!win && !win.isDestroyed() && win.webContents.getURL().startsWith(SITE_ORIGIN); } catch (_) { return false; } },
};
app.on('second-instance', (_e, argv) => {
  const f = pdfreader.pdfFromArgv(argv);
  if (f) pdfreader.openFile(pdfCtx, f); else showMain();
});


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
  const apply = () => { try { if (!w.isDestroyed()) w.setContentProtection(!!pref('screenProtect', true)); } catch (_) {} };
  let t = null;
  const soon = () => { clearTimeout(t); t = setTimeout(apply, 300); };
  apply();
  // only on real window-state changes. No timer / heartbeat: re-calling SetWindowDisplayAffinity while the window is
  // idle made the custom title bar flicker every now and then (esp. when a page / webview was opened).
  ['show', 'restore', 'maximize', 'unmaximize', 'enter-full-screen', 'leave-full-screen'].forEach((ev) => { try { w.on(ev, soon); } catch (_) {} });
  w.on('closed', () => { clearTimeout(t); });
}
app.on('browser-window-created', (_e, w) => protect(w));

function guard(wc) {
  wc.on('before-input-event', (e, input) => {
    const k = (input.key || '').toLowerCase();
    const ctrl = input.control || input.meta;
    if (k === 'printscreen') { if (!pref('screenProtect', true)) return; e.preventDefault(); try { clipboard.clear(); } catch (_) {} return; }
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
    if (ctrl && ['p', 's', 'u'].includes(k) && !(isPdfGuest(wc) && k !== 'u')) e.preventDefault();   // PDF reader: print / save allowed
    if (PACKAGED && (k === 'f12' || (ctrl && input.shift && ['i', 'j', 'c'].includes(k)))) e.preventDefault();
  });
  wc.on('context-menu', (e) => e.preventDefault());
}

/* Open a URL in a child window (inside the app, not the system browser).
   Used for YouTube, Google, YouTube Studio and any external https link. */
function openChild(url, title, opts) {
  opts = opts || {};
  // If a child with this URL already exists, just focus it (not for "New window" / incognito)
  if (!opts.forceNew) for (const w of BrowserWindow.getAllWindows()) {
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
      partition: opts.partition || EXT_PARTITION,   // same session as the Safe Browser tabs: logins are kept + Chrome user-agent (incognito: its own temporary session)
      preload: path.join(__dirname, 'ext-preload.js'),
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
        var INC = ${opts.incognito ? 'true' : 'false'};
        if (INC) bar.style.background = 'rgba(38,28,66,.96)';
        var title = document.createElement('span');
        title.style.cssText = 'flex:1;color:#ddd;font-size:13px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;text-align:center;';
        title.textContent = (INC ? '🕶 Incognito — ' : '') + (document.title || location.hostname);
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
  if (pref('alwaysOnTop', false)) { try { win.setAlwaysOnTop(true); } catch (_) {} }
  // Local splash paints almost instantly, so the window appears right after the click.
  win.once('ready-to-show', () => { win.maximize(); if (!startHidden) win.show(); startHidden = false; });
  // Closing the window only hides it to the tray, so update / admin-panel notifications and Focus Mode keep working.
  win.on('close', (e) => {
    if (forceQuit || !bgIcon) return;
    if (!pref('closeToTray', true) && !focus.isActive()) { forceQuit = true; return; } // setting: close = quit
    e.preventDefault();
    win.hide();
    safety.clean().catch(() => {});   // gone to the tray: drop caches (logins stay)
    stopAllMedia(); // window is hidden → course / YouTube videos must not keep playing in the background
  });
  win.on('show', unmuteAll);
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
    if (wc.getURL().startsWith(SITE_ORIGIN)) setTimeout(() => pdfreader.flush(pdfCtx), 900);   // a PDF opened from Explorer while the site was loading
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
function pref(k, d) { const p = readPrefs(); return p[k] === undefined ? d : p[k]; } // settings panel (sidebar): notifications, screenProtect, autostart, closeToTray
function autostartOn() { const p = readPrefs(); return p.autostart !== false; } // default: on
function applyAutostart(on) {
  if (!PACKAGED) return; // never register the dev electron.exe
  try { app.setLoginItemSettings({ openAtLogin: !!on, args: ['--hidden'] }); } catch (_) {}
}
/* Pause every <video>/<audio> in the main page, its iframes (YouTube embeds) and the Safe Browser webviews, and mute them.
   The page itself keeps running (backgroundThrottling:false) so notifications / update checks still work. */
const PAUSE_JS = "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}});" +
  "var yt=document.querySelector('.html5-video-player');if(yt&&yt.pauseVideo)yt.pauseVideo();}catch(e){}})();";
function stopAllMedia() {
  try {
    if (!win || win.isDestroyed()) return;
    const host = win.webContents;
    const all = [host].concat(require('electron').webContents.getAllWebContents().filter((w) => {
      try { return w.hostWebContents === host; } catch (_) { return false; }
    }));
    all.forEach((w) => {
      try {
        if (w.isDestroyed()) return;
        w.setAudioMuted(true);
        const frames = (w.mainFrame && w.mainFrame.framesInSubtree) || [];
        frames.forEach((f) => { try { f.executeJavaScript(PAUSE_JS).catch(() => {}); } catch (_) {} });
        if (!frames.length) w.executeJavaScript(PAUSE_JS).catch(() => {});
      } catch (_) {}
    });
  } catch (_) {}
}
function unmuteAll() {
  try {
    if (!win || win.isDestroyed()) return;
    const host = win.webContents;
    [host].concat(require('electron').webContents.getAllWebContents().filter((w) => { try { return w.hostWebContents === host; } catch (_) { return false; } }))
      .forEach((w) => { try { if (!w.isDestroyed()) w.setAudioMuted(false); } catch (_) {} });
  } catch (_) {}
}
function showMain() {
  if (!win || win.isDestroyed()) { startHidden = false; create(); return; }
  if (win.isMinimized()) win.restore();
  unmuteAll();
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
    if (img.isEmpty()) return; // no icon → no background mode → the close button keeps its normal behaviour
    bgIcon = true;
    if (!pref('trayIcon', true)) return; // hidden background mode (like Telegram): no tray icon, process keeps running, notifications keep coming
    tray = new Tray(img.resize({ width: 16, height: 16 }));
    tray.setToolTip('Rajib Tutorial');
    tray.setContextMenu(trayMenu());
    tray.on('click', showMain);
  } catch (_) { tray = null; }
}
// Settings toggle: show / hide the tray icon live. Hidden = the app still runs in the background; open it again by starting the shortcut.
function applyTrayIcon(on) {
  if (!bgIcon) return;
  if (on && !tray) initTray();
  else if (!on && tray) { try { tray.destroy(); } catch (_) {} tray = null; }
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

let cacheCleanedOnQuit = false;
app.on('before-quit', (e) => {
  if (!forceQuit && focus.isActive()) { e.preventDefault(); return; } // a Focus session is running
  if (cacheCleanedOnQuit) return;
  // real quit: wipe caches first (max 2.5 s), then quit for real. Cookies / logins are kept.
  e.preventDefault(); cacheCleanedOnQuit = true;
  Promise.race([safety.clean(), new Promise((r) => setTimeout(r, 2500))]).catch(() => {}).finally(() => app.quit());
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
    if (first && Notification.isSupported() && pref('notifications', true)) {
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
    // NOT silent: the one-click installer shows its own "Installing…" progress window while it closes the old version and
    // installs the new one (a silent install showed nothing for ~40 s). It relaunches the new version when it is done.
    setTimeout(() => { forceQuit = true; autoUpdater.quitAndInstall(false, true); }, 150);
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
  if (!Notification.isSupported() || !pref('notifications', true)) return;
  const n = new Notification({
    title: String(title || 'Rajib Tutorial'),
    body: String(body || ''),
    icon: path.join(__dirname, 'build', 'icon.png'),
    silent: !pref('notifySound', true),
  });
  n.on('click', () => {
    if (win) { if (win.isMinimized()) win.restore(); win.show(); win.focus(); }
  });
  n.show();
});

// IPC: sidebar Settings panel (index.html). Only the main window may call these.
function settingsState() {
  return { notifications: !!pref('notifications', true), screenProtect: !!pref('screenProtect', true), autostart: autostartOn(),
           closeToTray: !!pref('closeToTray', true), trayIcon: !!pref('trayIcon', true), notifySound: !!pref('notifySound', true), alwaysOnTop: !!pref('alwaysOnTop', false),
           gpuOff: !!pref('gpuOff', false), adultBlock: !!pref('adultBlock', true), version: app.getVersion(), packaged: PACKAGED };
}
ipcMain.handle('settings:get', (e) => (trusted(e) ? settingsState() : null));
ipcMain.handle('settings:set', (e, key, val) => {
  if (!trusted(e) || !['notifications', 'screenProtect', 'autostart', 'closeToTray', 'trayIcon', 'notifySound', 'alwaysOnTop', 'gpuOff', 'adultBlock'].includes(key)) return null;
  val = !!val;
  if (key === 'autostart' && !val && focus.isActive()) return settingsState(); // Focus session running: keep Start-with-Windows on
  const p = readPrefs(); p[key] = val; writePrefs(p);
  if (key === 'screenProtect') BrowserWindow.getAllWindows().forEach((w) => { try { if (!w.isDestroyed()) w.setContentProtection(val); } catch (_) {} });
  if (key === 'autostart') applyAutostart(val);
  if (key === 'trayIcon') applyTrayIcon(val);
  if (key === 'alwaysOnTop' && win && !win.isDestroyed()) { try { win.setAlwaysOnTop(val); } catch (_) {} }
  if (key === 'adultBlock') adultBlockOn = val;
  if (key === 'gpuOff') { setTimeout(() => { forceQuit = true; app.relaunch(); app.exit(0); }, 400); return settingsState(); }
  refreshTrayMenu();
  return settingsState();
});
ipcMain.handle('settings:testNotify', (e) => {
  if (!trusted(e) || !Notification.isSupported()) return false;
  const on = !!pref('notifications', true);
  if (!on) return 'off';
  new Notification({ title: 'Rajib Tutorial', body: 'Test notification — সব ঠিক আছে ✓', icon: path.join(__dirname, 'build', 'icon.png'), silent: !pref('notifySound', true) }).show();
  return true;
});
ipcMain.handle('settings:clearCache', async (e) => {
  if (!trusted(e)) return false;
  try { await session.defaultSession.clearCache(); await session.fromPartition(EXT_PARTITION).clearCache(); } catch (_) { return false; }
  return true;
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
// Optional modules must never stop the app from starting (a failure here once disabled the window AND the updater):
// every one of them falls back to a harmless stub.
const stubErr = async () => ({ ok: false, error: 'This feature could not start.' });
let extMgr;
try { extMgr = require('./extensions')({ partition: EXT_PARTITION, getMainWin: () => win }); }
catch (e) { console.error('extensions failed', e); extMgr = { init: async () => {}, list: () => [], addFolder: stubErr, addFile: stubErr, addStore: stubErr, remove: stubErr, toggle: stubErr, open: stubErr }; }
let adultBlockOn = true;   // Settings → Adult Block (kept in memory: checked on every request)
let safety;
try {
  safety = require('./safety')({
    getSessions: () => ({ def: session.defaultSession, ext: session.fromPartition(EXT_PARTITION) }),
    isAdultBlockOn: () => adultBlockOn,
  });
} catch (e) { console.error('safety failed', e); safety = { adultCheck: () => null, persistCookies() {}, clean: async () => {}, isAdultHost: () => false }; }
ipcMain.handle('ext:list', (e) => (trusted(e) ? extMgr.list() : []));
ipcMain.handle('ext:addFolder', (e) => (trusted(e) ? extMgr.addFolder() : null));
ipcMain.handle('ext:addFile', (e) => (trusted(e) ? extMgr.addFile() : null));
ipcMain.handle('ext:addStore', (e, s) => (trusted(e) ? extMgr.addStore(String(s || '')) : null));
ipcMain.handle('ext:remove', (e, k) => (trusted(e) ? extMgr.remove(String(k)) : null));
ipcMain.handle('ext:toggle', (e, k, on) => (trusted(e) ? extMgr.toggle(String(k), !!on) : null));
ipcMain.handle('ext:open', (e, k) => (trusted(e) ? extMgr.open(String(k)) : null));
// Chrome-style menu: New window / New Incognito window (temporary session: nothing is saved, closed = gone)
const safeHttps = (u, d) => { try { const x = new URL(String(u || d)); return x.protocol === 'https:' ? x.toString() : d; } catch (_) { return d; } };
ipcMain.handle('win:new', (e, u) => { if (!trusted(e)) return null; openChild(safeHttps(u, 'https://www.google.com/'), 'Safe Browser', { forceNew: true }); return { ok: true }; });
ipcMain.handle('incog:open', (e, u) => {
  if (!trusted(e)) return null;
  const part = 'rtincog-' + Date.now().toString(36);   // no "persist:" prefix => memory only
  const ses = session.fromPartition(part);
  setupBrowserSession(ses, false);
  webTools.hook(ses);
  openChild(safeHttps(u, 'https://www.google.com/'), 'Incognito', { forceNew: true, partition: part, incognito: true });
  return { ok: true };
});
let webTools = { hook() {} };
try { webTools = require('./webtools').register({ ipcMain, app, session, shell, dialog, webContents: require('electron').webContents, getWin: () => win, partition: EXT_PARTITION, trusted }); }
catch (e) { console.error('webtools failed', e); }
// the course-video page shown by index.html (wvPlayer): youtube.com/embed/<id> or /watch?v=<id>, opened with "#rtlesson"
function isLessonPage(wc) {
  try { const u = new URL(wc.getURL()); return u.hostname === 'www.youtube.com' && /(^|[#&])rtlesson/.test(u.hash); } catch (_) { return false; }
}
// the local Focus Mode page shown in a <webview> inside the app layout (see index.html openFocus)
function isFocusGuest(wc) { try { return wc.getURL().startsWith('rtfocus://'); } catch (_) { return false; } }
// the PDF Reader page (pdf.js viewer) shown in a <webview> inside the app layout
function isPdfGuest(wc) { try { return wc.getURL().startsWith('rtpdf://'); } catch (_) { return false; } }
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
    const isPdf = String(params.src).split('?')[0] === pdfreader.PDF_SRC && params.partition === pdfreader.PDF_PARTITION;
    try { ok = new URL(params.src).protocol === 'https:' && params.partition === EXT_PARTITION; } catch (_) {}
    if (!ok && !isFocus && !isPdf) { e.preventDefault(); return; }
    delete webPreferences.preloadURL;
    // external sites: ext-preload.js makes the page look like normal Chrome (Google sign-in); Focus page: its own small API bridge
    if (isPdf) delete webPreferences.preload;   // the PDF viewer needs no bridge at all
    else webPreferences.preload = path.join(__dirname, isFocus ? 'focus-preload.js' : 'ext-preload.js');
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
    // black-screen guards: white base colour, force a repaint after loads, and reload a page whose renderer crashed
    try { wc.setBackgroundColor('#ffffff'); } catch (_) {}
    const repaint = () => { try { if (!wc.isDestroyed()) wc.invalidate(); } catch (_) {} };
    wc.on('did-finish-load', repaint); wc.on('did-navigate', repaint); wc.on('did-stop-loading', repaint);
    wc.on('render-process-gone', (_ev, d) => {
      if (!d || d.reason === 'clean-exit' || d.reason === 'killed') return;
      setTimeout(() => { try { if (!wc.isDestroyed()) wc.reload(); } catch (_) {} }, 400);
    });
    // links that want a new window just navigate the embedded page
    wc.setWindowOpenHandler(({ url: u, disposition, features }) => {
      try {
        if (isLessonPage(wc) || isFocusGuest(wc) || isPdfGuest(wc)) return { action: 'deny' };                    // course video: nothing may open from it
        const parsed = new URL(u || 'about:blank');
        if (AD_HOST_RE.test(parsed.hostname)) return { action: 'deny' };   // ad pop-up / pop-under
        // Sign-in flows ("Continue with Google" etc.) open a pop-up and talk to it through window.opener / postMessage.
        // Loading it inside the same tab breaks the login, so real pop-ups are kept as pop-up windows.
        const feat = String(features || '');
        const wantsPopup = disposition === 'new-window' || /(^|,)\s*(width|height|popup)\b/i.test(feat) || AUTH_HOST_RE.test(parsed.hostname);
        const isBlank = parsed.protocol === 'about:';   // window.open('') first, navigated afterwards (very common in OAuth libraries)
        if ((parsed.protocol === 'https:' && wantsPopup) || (isBlank && wantsPopup)) {
          const parent = wc.hostWebContents && !wc.hostWebContents.isDestroyed() ? BrowserWindow.fromWebContents(wc.hostWebContents) : null;
          return {
            action: 'allow',
            overrideBrowserWindowOptions: {
              width: 520, height: 720, autoHideMenuBar: true, title: 'Sign in', parent: parent || undefined,
              backgroundColor: '#ffffff',
              webPreferences: {
                partition: EXT_PARTITION,   // SAME session as the tab: shared cookies + Chrome user-agent (Google refuses an Electron browser)
                preload: path.join(__dirname, 'ext-preload.js'), sandbox: true, contextIsolation: true, nodeIntegration: false, devTools: !PACKAGED,
              },
            },
          };
        }
        if (parsed.protocol === 'https:') wc.loadURL(u);
        else if (parsed.protocol === 'mailto:' || parsed.protocol === 'tel:') shell.openExternal(u);
      } catch (_) {}
      return { action: 'deny' };
    });
    wc.on('will-navigate', (ev, u) => { if (!/^https:/i.test(u) || isLessonPage(wc)) ev.preventDefault(); });
    // Safe Browser shortcuts (F11 / Esc / Ctrl+T, W, L, R, D, Tab) typed while the page inside the <webview> has focus
    // never reach the app window — pass them on, so full screen and tabs work exactly like in Chrome.
    // right-click inside the page → Chrome-style menu drawn by index.html (Safe Browser tabs only, never the course video)
    wc.on('context-menu', (_ev, params) => {
      try {
        if (isLessonPage(wc) || isFocusGuest(wc) || isPdfGuest(wc) || !wc.hostWebContents || wc.hostWebContents.isDestroyed()) return;
        wc.hostWebContents.send('bt:ctx', JSON.stringify({ x: params.x, y: params.y, linkURL: params.linkURL || '', srcURL: params.srcURL || '', mediaType: params.mediaType || '', selectionText: (params.selectionText || '').slice(0, 300), isEditable: !!params.isEditable }));
      } catch (_) {}
    });
    wc.on('before-input-event', (_ev, input) => {
      try {
        if (input.type !== 'keyDown' || isLessonPage(wc) || isFocusGuest(wc) || isPdfGuest(wc)) return;
        const k = input.key, ctrl = !!(input.control || input.meta);
        const fwd = k === 'F11' || k === 'F5' || k === 'Escape' || (ctrl && (/^[twlrdfhpnjs0-9=+\-_]$/i.test(k) || k === 'Tab' || (input.shift && k === 'Delete'))) || (input.alt && (k === 'ArrowLeft' || k === 'ArrowRight'));
        if (fwd && wc.hostWebContents && !wc.hostWebContents.isDestroyed()) {
          wc.hostWebContents.send('bt:key', { key: k, ctrlKey: ctrl, shiftKey: !!input.shift, altKey: !!input.alt });
        }
      } catch (_) {}
    });
  }
});

app.setAppUserModelId('com.rajibtutorial.webexe'); // needed for Windows notifications
function setupBrowserSession(ext, persistent) {
    // Reported Chrome version never below MIN_CHROME_MAJOR (Electron's own Chrome gets old -> "out of date browser")
    const MIN_CHROME_MAJOR = 146;
    const realMajor = parseInt(String(process.versions.chrome).split('.')[0], 10);
    const spoofMajor = Math.max(realMajor, MIN_CHROME_MAJOR);
    const spoofFull = spoofMajor + '.0.0.0';
    ext.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/' + spoofFull + ' Safari/537.36');
    // Client-hint headers: send the same "Google Chrome" brands as the user-agent (no "Electron" anywhere).
    const major = String(spoofMajor);
    const brands = '"Chromium";v="' + major + '", "Google Chrome";v="' + major + '", "Not_A Brand";v="24"';
    const fullList = '"Chromium";v="' + spoofFull + '", "Google Chrome";v="' + spoofFull + '", "Not_A Brand";v="24.0.0.0"';
    // YouTube ads: cancel the ad / tracking requests (only in this embedded session; ext-preload.js handles the rest)
    // ONE listener (Electron keeps only one per event): adult block / SafeSearch first, then the ad network block
    const YT_AD_PATH = /^\/(pagead\/|api\/stats\/ads|ptracking|get_midroll_info|youtubei\/v1\/player\/ad_break)/;
    ext.webRequest.onBeforeRequest((d, cb) => {
      const a = safety.adultCheck(d);
      if (a) return cb(a);
      try {
        const u = new URL(d.url);
        if (AD_HOST_RE.test(u.hostname) || (/(^|\.)youtube\.com$/.test(u.hostname) && YT_AD_PATH.test(u.pathname))) return cb({ cancel: true });
      } catch (_) {}
      cb({});
    });
    if (persistent) safety.persistCookies(ext);   // stay signed in after the app is closed
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

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  setupBrowserSession(session.fromPartition(EXT_PARTITION), true);
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
  // rtfocus://app/focus.html  -> the local Focus Mode page (only this one file is ever served).
  // The Focus <webview> uses its own partition ("rtfocus"), and custom protocols are per session: the handler has to be
  // registered on THAT session (before, only the default session had it, so the Focus page stayed blank).
  const focusHandler = (request) => {
    try {
      if (new URL(request.url).pathname !== '/focus.html') return new Response('not found', { status: 404 });
      return new Response(fs.readFileSync(path.join(__dirname, 'focus.html')), { status: 200, headers: { 'content-type': 'text/html; charset=utf-8' } });
    } catch (_) { return new Response('error', { status: 500 }); }
  };
  try { session.fromPartition(FOCUS_PARTITION).protocol.handle('rtfocus', focusHandler); } catch (_) {}
  try { protocol.handle('rtfocus', focusHandler); } catch (_) {}
  adultBlockOn = !!pref('adultBlock', true);
  safety.persistCookies(session.defaultSession);
  extMgr.init().catch(() => {});
  try { pdfreader.init(pdfCtx); } catch (e) { console.error('pdfreader failed', e); }
  create();
  initTray();
  { const f = pdfreader.pdfFromArgv(process.argv); if (f) { startHidden = false; pdfreader.openFile(pdfCtx, f); } }   // started by double-clicking a .pdf
  focus.init({ getMainWin: () => win, onStart: () => applyAutostart(true), onActiveChange: refreshTrayMenu, openView: openFocusView });
  applyAutostart(autostartOn() || focus.isActive());
  setTimeout(initUpdater, 1500);
  try { globalShortcut.register('PrintScreen', () => { try { clipboard.clear(); } catch (_) {} }); } catch (_) {}
});
app.on('will-quit', () => { try { globalShortcut.unregisterAll(); } catch (_) {} });
app.on('window-all-closed', () => { if (!bgIcon || forceQuit) app.quit(); });
