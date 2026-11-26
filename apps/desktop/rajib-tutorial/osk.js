'use strict';
// On-screen keyboard for Wine / Winlator (Windows exe running on an Android phone).
// Winlator never opens the Android keyboard by itself when a Windows app focuses a text box, so the app brings its own:
//  - every page (main window, <webview>s, iframes) reports "a text field got / lost focus" through a console marker,
//  - on a text field the keyboard slides up (bottom of the screen, or the top when the field is in the lower half),
//  - keys are typed into the page with webContents.insertText / sendInputEvent.
// It only switches on when Wine is detected (or --osk / prefs.osk = 'on'); on a normal Windows PC nothing changes.
const path = require('path');
const { execFile } = require('child_process');
const { webContents } = require('electron');

const MARK = '__RT_OSK__:';
const KB_HTML = path.join(__dirname, 'osk.html');
const PRELOAD = path.join(__dirname, 'osk-preload.js');

// Injected into every frame: reports focus on editable fields (fraction = where the field is on screen, 0 top … 1 bottom).
const INJECT = `(function(){
  if (window.__rtOsk) return; window.__rtOsk = 1;
  var P = ${JSON.stringify(MARK)};
  var OK = ['text','search','email','url','tel','password','number',''];
  function active(){ var a = document.activeElement; while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement; return a; }
  function editable(el){
    if (!el || el.nodeType !== 1) return false;
    if (el.isContentEditable) return true;
    var t = el.tagName;
    if (t === 'TEXTAREA') return !el.readOnly && !el.disabled;
    if (t === 'INPUT') return OK.indexOf(String(el.type || 'text').toLowerCase()) > -1 && !el.readOnly && !el.disabled;
    return false;
  }
  function show(){
    var a = active(); if (!editable(a)) return;
    var f = 0.3;
    try { var r = a.getBoundingClientRect(); f = Math.max(0, Math.min(1, (r.top + r.height / 2) / Math.max(1, window.innerHeight))); } catch (e) {}
    console.log(P + 'show:' + f.toFixed(2));
  }
  document.addEventListener('focusin', function(){ setTimeout(show, 30); }, true);
  document.addEventListener('pointerup', function(){ setTimeout(show, 60); }, true);
  document.addEventListener('focusout', function(){
    setTimeout(function(){
      var a = active();
      if (a && a.tagName === 'IFRAME') return;           // focus moved into a frame: that frame reports itself
      if (!editable(a) && document.hasFocus()) console.log(P + 'hide');
    }, 250);
  }, true);
  setTimeout(show, 100);
})();`;

let st = null;   // set by init()
const S = { enabled: false, kb: null, fab: null, own: new Set(), target: null, atTop: false, shown: false, timer: null };

function detectWine() {
  return new Promise((resolve) => {
    if (process.platform !== 'win32') return resolve(false);
    const e = process.env;
    if (e.WINEPREFIX || e.WINELOADER || e.WINEDLLPATH || e.WINESERVER || e.WINEUSERNAME) return resolve(true);
    try {
      // Wine always has HKCU\Software\Wine; a real Windows PC does not.
      execFile('reg', ['query', 'HKCU\\Software\\Wine'], { timeout: 4000, windowsHide: true }, (err) => resolve(!err));
    } catch (_) { resolve(false); }
  });
}

function mainOk() {
  try { const w = st.getWin(); return !!w && !w.isDestroyed() && w.isVisible() && !w.isMinimized(); } catch (_) { return false; }
}
function area() {
  try {
    const w = st.getWin();
    const d = w && !w.isDestroyed() ? st.screen.getDisplayMatching(w.getBounds()) : st.screen.getPrimaryDisplay();
    return d.workArea;
  } catch (_) { return st.screen.getPrimaryDisplay().workArea; }
}

function mkWin(opts) {
  const w = new st.BrowserWindow(Object.assign({
    show: false, frame: false, resizable: false, movable: false, minimizable: false, maximizable: false, fullscreenable: false,
    skipTaskbar: true, focusable: false, alwaysOnTop: true, hasShadow: false, backgroundColor: '#111827',
    webPreferences: { contextIsolation: true, sandbox: true, nodeIntegration: false, devTools: false, preload: PRELOAD, backgroundThrottling: false },
  }, opts));
  S.own.add(w.webContents.id);
  try { w.setAlwaysOnTop(true, 'screen-saver'); } catch (_) {}
  try { w.setMenuBarVisibility(false); } catch (_) {}
  w.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  w.webContents.on('will-navigate', (e) => e.preventDefault());
  return w;
}

function placeKb() {
  if (!S.kb || S.kb.isDestroyed()) return;
  const wa = area();
  const h = Math.round(Math.min(360, Math.max(230, wa.height * 0.46)));
  S.kb.setBounds({ x: wa.x, y: S.atTop ? wa.y : wa.y + wa.height - h, width: wa.width, height: h });
}
function placeFab() {
  if (!S.fab || S.fab.isDestroyed()) return;
  const wa = area();
  S.fab.setBounds({ x: wa.x + wa.width - 76, y: wa.y + wa.height - 64, width: 64, height: 52 });
}

function showKb(atTop) {
  if (!S.enabled || !S.kb || S.kb.isDestroyed()) return;
  if (typeof atTop === 'boolean') S.atTop = atTop;
  placeKb();
  S.kb.showInactive();
  S.shown = true;
  if (S.fab && !S.fab.isDestroyed()) S.fab.hide();
}
function hideKb() {
  S.shown = false;
  try { if (S.kb && !S.kb.isDestroyed()) S.kb.hide(); } catch (_) {}
  syncFab();
}
function syncFab() {
  if (!S.enabled || !S.fab || S.fab.isDestroyed()) return;
  if (!S.shown && mainOk()) { placeFab(); S.fab.showInactive(); } else S.fab.hide();
}

function attach(wc) {
  try {
    const t = wc.getType();
    if (t !== 'window' && t !== 'webview' && t !== 'browserView') return;
    const inject = () => {
      if (!S.enabled || S.own.has(wc.id) || wc.isDestroyed()) return;
      try { wc.executeJavaScript(INJECT, true).catch(() => {}); } catch (_) {}
    };
    wc.on('dom-ready', inject);
    wc.on('did-frame-finish-load', (_e, isMain, pid, rid) => {          // iframes (embedded players, login frames …)
      if (isMain || !S.enabled || S.own.has(wc.id)) return;
      try {
        const f = st.webFrameMain.fromId(pid, rid);
        if (f) f.executeJavaScript(INJECT, true).catch(() => {});
      } catch (_) {}
    });
    wc.on('console-message', (e, ...a) => {
      if (!S.enabled || S.own.has(wc.id)) return;
      const msg = typeof (e && e.message) === 'string' ? e.message : String(a[1] || '');
      if (msg.indexOf(MARK) !== 0) return;
      const cmd = msg.slice(MARK.length);
      if (cmd.indexOf('show:') === 0) {
        S.target = wc;
        const f = parseFloat(cmd.slice(5));
        // field in the lower half → keyboard goes to the top; once it is open it stays put while typing (⇅ flips it by hand)
        showKb(!S.shown && Number.isFinite(f) ? f > 0.55 : undefined);
      } else if (cmd === 'hide' && S.target === wc) hideKb();
    });
    wc.on('destroyed', () => { if (S.target === wc) S.target = null; });
  } catch (_) {}
}

const NAMED = {
  '{BS}': 'Backspace', '{DEL}': 'Delete', '{TAB}': 'Tab', '{LEFT}': 'Left', '{RIGHT}': 'Right', '{UP}': 'Up', '{DOWN}': 'Down',
  '{HOME}': 'Home', '{END}': 'End',
};
function typeKey(k) {
  let wc = S.target;
  if (!wc || wc.isDestroyed()) { try { wc = st.getWin().webContents; } catch (_) { return; } }
  try { wc.focus(); } catch (_) {}
  if (k === '{ENTER}') {
    wc.sendInputEvent({ type: 'keyDown', keyCode: 'Enter' });
    wc.sendInputEvent({ type: 'char', keyCode: '\r' });
    wc.sendInputEvent({ type: 'keyUp', keyCode: 'Enter' });
  } else if (NAMED[k]) {
    wc.sendInputEvent({ type: 'keyDown', keyCode: NAMED[k] });
    wc.sendInputEvent({ type: 'keyUp', keyCode: NAMED[k] });
  } else if (k && k[0] !== '{') {
    wc.insertText(k);
  }
}

function create() {
  S.kb = mkWin({ width: 1000, height: 300 });
  S.kb.loadFile(KB_HTML);
  S.fab = mkWin({ width: 64, height: 52, backgroundColor: '#1f2937' });
  S.fab.loadFile(KB_HTML, { hash: 'fab' });
  S.timer = setInterval(() => {
    if (!mainOk()) { if (S.shown) hideKb(); else if (S.fab && !S.fab.isDestroyed()) S.fab.hide(); }
    else if (!S.shown) syncFab();
  }, 1500);
}

// Wine detection is async: pages that finished loading before it said "yes" must get the script too (all frames).
function injectAll() {
  try {
    webContents.getAllWebContents().forEach((wc) => {
      try {
        const t = wc.getType();
        if (S.own.has(wc.id) || wc.isDestroyed() || (t !== 'window' && t !== 'webview' && t !== 'browserView')) return;
        const frames = (wc.mainFrame && wc.mainFrame.framesInSubtree) || [];
        frames.forEach((f) => { try { f.executeJavaScript(INJECT, true).catch(() => {}); } catch (_) {} });
      } catch (_) {}
    });
  } catch (_) {}
}

function setEnabled(on) {
  if (on === S.enabled) return;
  S.enabled = on;
  if (on) {
    if (!S.kb) create();
    injectAll();
    syncFab();
  } else {
    hideKb();
    try { if (S.fab && !S.fab.isDestroyed()) S.fab.hide(); } catch (_) {}
  }
}

// deps: { BrowserWindow, ipcMain, screen, globalShortcut, webFrameMain, getWin, readPrefs }
function init(deps) {
  st = deps;
  const own = (e) => S.own.has(e.sender.id);
  st.ipcMain.on('osk:key', (e, k) => { if (own(e)) typeKey(String(k)); });
  st.ipcMain.on('osk:hide', (e) => { if (own(e)) hideKb(); });
  st.ipcMain.on('osk:flip', (e) => { if (own(e)) { S.atTop = !S.atTop; placeKb(); } });
  st.ipcMain.on('osk:toggle', (e) => { if (own(e)) { if (S.shown) hideKb(); else showKb(); } });
  try { st.globalShortcut.register('CommandOrControl+Alt+K', () => { if (!S.enabled) setEnabled(true); if (S.shown) hideKb(); else showKb(); }); } catch (_) {}

  let pref = 'auto';
  try { pref = String((st.readPrefs() || {}).osk || 'auto'); } catch (_) {}
  if (process.argv.includes('--no-osk') || pref === 'off') return;
  if (process.argv.includes('--osk') || pref === 'on') { setEnabled(true); return; }
  detectWine().then((wine) => { if (wine) setEnabled(true); });
}

module.exports = { init, attach, _INJECT: INJECT };
