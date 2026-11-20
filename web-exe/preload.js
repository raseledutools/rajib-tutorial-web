'use strict';
const { ipcRenderer, contextBridge } = require('electron');

// Custom title bar (white, drag area, app icon + title). Native min/max/close buttons come from titleBarOverlay in main.js.
function buildTitleBar() {
  if (!document.body || document.getElementById('__rt_titlebar')) return;
  const isFile = location.protocol === 'file:';
  const bar = document.createElement('div');
  bar.id = '__rt_titlebar';
  bar.style.cssText = 'position:fixed;top:0;left:0;right:0;height:36px;z-index:2147483646;display:flex;align-items:center;gap:8px;padding:0 150px 0 12px;background:#fff;border-bottom:1px solid #E3E6EA;font:600 13px "Segoe UI",system-ui,sans-serif;color:#1f2937;-webkit-app-region:drag;user-select:none;transform:translateZ(0);will-change:transform';
  bar.innerHTML = '<svg width="18" height="18" viewBox="0 0 100 100" fill="none" stroke="#00959c" stroke-width="9" stroke-linecap="round"><path d="M50 14v72M14 50h72M24 24l52 52M76 24L24 76"/></svg><span>Rajib Tutorial</span>';
  document.body.appendChild(bar);
  // Local pages (focus / offline) have no layout that knows about the bar: push their content down.
  if (isFile && !location.pathname.endsWith('splash.html')) document.documentElement.style.paddingTop = '36px';
}
window.addEventListener('DOMContentLoaded', buildTitleBar);
window.addEventListener('load', buildTitleBar);

// No flicker: the white bar is painted by CSS from the very first frame of every page (before <body> exists), and the real
// bar is (re)added the moment <body> appears or if a page ever removes it. Before this, the bar only appeared at
// DOMContentLoaded, so every page change showed a blank strip + the bar popping in.
function injectBarStyle() {
  const root = document.documentElement;
  if (!root || document.getElementById('__rt_tbstyle')) return;
  const st = document.createElement('style');
  st.id = '__rt_tbstyle';
  st.textContent = 'html::before{content:"";position:fixed;top:0;left:0;right:0;height:36px;background:#fff;border-bottom:1px solid #E3E6EA;z-index:2147483645;pointer-events:none}'
    + 'html.bt-fs::before{display:none}';
  root.appendChild(st);
}
injectBarStyle();
try {
  const early = new MutationObserver(() => { injectBarStyle(); if (document.body) buildTitleBar(); });
  early.observe(document, { childList: true, subtree: true });
  window.addEventListener('DOMContentLoaded', () => {
    early.disconnect();
    buildTitleBar();
    const keep = new MutationObserver(() => { if (document.body && !document.getElementById('__rt_titlebar')) buildTitleBar(); if (!document.getElementById('__rt_tbstyle')) injectBarStyle(); });
    keep.observe(document.documentElement, { childList: true });
    if (document.body) keep.observe(document.body, { childList: true });
  });
} catch (_) {}

// Update UI: a banner at the top when an update exists + a fixed check button (bottom-right)
let lastUpdate = { status: 'idle' };
function renderUpdateBtn() {
  if (location.protocol === 'file:') return; // splash / offline page
  const st = lastUpdate.status, v = lastUpdate.version ? ' v' + lastUpdate.version : '';

  // top banner — only when an update exists / is being installed
  let b = document.getElementById('__rt_update');
  if (!['available', 'downloading', 'installing'].includes(st)) { if (b) b.remove(); }
  else {
    if (!b) {
      b = document.createElement('button');
      b.id = '__rt_update';
      b.style.cssText = 'position:fixed;top:8px;left:50%;transform:translateX(-50%);z-index:2147483647;border:0;border-radius:999px;padding:8px 18px;background:linear-gradient(135deg,#16a34a,#22c55e);color:#fff;font:600 13px system-ui,sans-serif;cursor:pointer;box-shadow:0 4px 14px rgba(0,0,0,.35)';
      b.onclick = () => { if (lastUpdate.status === 'available') ipcRenderer.send('update:install'); };
      (document.body || document.documentElement).appendChild(b);
    }
    if (st === 'available') { b.textContent = '🔄 নতুন আপডেট' + v + ' — ক্লিক করে আপডেট করুন'; b.style.cursor = 'pointer'; }
    else if (st === 'downloading') { b.textContent = '⬇ ডাউনলোড হচ্ছে… ' + (lastUpdate.percent || 0) + '%'; b.style.cursor = 'default'; }
    else { b.textContent = '✅ ইনস্টল হচ্ছে, অ্যাপ রিস্টার্ট হবে…'; b.style.cursor = 'default'; }
  }

  // fixed check button — always visible
  let c = document.getElementById('__rt_updcheck');
  if (!c) {
    c = document.createElement('button');
    c.id = '__rt_updcheck';
    c.style.cssText = 'position:fixed;right:14px;bottom:14px;z-index:2147483646;border:0;border-radius:999px;padding:8px 14px;background:rgba(30,30,40,.88);color:#fff;font:600 12.5px system-ui,sans-serif;cursor:pointer;box-shadow:0 4px 16px rgba(0,0,0,.3);backdrop-filter:blur(6px)';
    c.onmouseenter = () => (c.style.background = 'rgba(60,60,80,.95)');
    c.onmouseleave = () => (c.style.background = 'rgba(30,30,40,.88)');
    c.onclick = () => {
      const s = lastUpdate.status;
      if (s === 'available') ipcRenderer.send('update:install');
      else if (s === 'idle' || s === 'uptodate' || s === 'error') ipcRenderer.send('update:check');
    };
    (document.body || document.documentElement).appendChild(c);
  }
  const cur = lastUpdate.current ? ' v' + lastUpdate.current : '';
  const label = {
    idle: '⟳ আপডেট চেক' + cur,
    checking: '⟳ চেক হচ্ছে…',
    uptodate: '✅ আপনি latest version-এ আছেন' + cur,
    error: '❌ চেক করা যায়নি — আবার চাপুন',
    available: '🔄 আপডেট' + v,
    downloading: '⬇ ' + (lastUpdate.percent || 0) + '%',
    installing: '✅ ইনস্টল হচ্ছে…',
  }[st] || '⟳ আপডেট চেক';
  c.textContent = label;
  c.style.cursor = ['checking', 'downloading', 'installing'].includes(st) ? 'default' : 'pointer';
}
ipcRenderer.on('update:state', (_e, st) => { lastUpdate = st || { status: 'idle' }; renderUpdateBtn(); });
window.addEventListener('DOMContentLoaded', () => { ipcRenderer.send('update:get'); renderUpdateBtn(); });
window.addEventListener('load', renderUpdateBtn);


// Focus Mode: opened from the website's sidebar item (no floating button any more)
let focusOn = false;
const focusListeners = [];
const focusShowListeners = [];
ipcRenderer.on('focus:show', () => focusShowListeners.forEach((fn) => { try { fn(); } catch (_) {} }));
function setFocusOn(on) { focusOn = !!on; focusListeners.forEach((fn) => { try { fn(focusOn); } catch (_) {} }); }
ipcRenderer.on('focus:badge', (_e, on) => setFocusOn(on));
window.addEventListener('DOMContentLoaded', () => { ipcRenderer.invoke('focus:isActive').then(setFocusOn).catch(() => {}); });

// Keys forwarded by main.js from the page inside the Safe Browser <webview> (F11, Esc, Ctrl+T …) → handled by index.html like normal key presses
ipcRenderer.on('bt:key', (_e, k) => {
  try {
    document.dispatchEvent(new KeyboardEvent('keydown', { key: String((k && k.key) || ''), ctrlKey: !!(k && k.ctrlKey), shiftKey: !!(k && k.shiftKey), altKey: !!(k && k.altKey), bubbles: true, cancelable: true }));
  } catch (_) {}
});

ipcRenderer.on('bt:ctx', (_e, json) => {
  try { document.dispatchEvent(new CustomEvent('bt-ctx', { detail: String(json || '') })); } catch (_) {}
});

// Expose a safe bridge so the website can open URLs inside the Electron app
// (used by openInAppBrowser for YouTube, Google, YouTube Studio etc.)
contextBridge.exposeInMainWorld('ElectronShell', {
  openUrl: (url) => ipcRenderer.send('shell:openUrl', url),
  notify: (title, body) => ipcRenderer.send('notify:show', String(title || ''), String(body || '')),
  isElectron: true,
  settings: {
    get: () => ipcRenderer.invoke('settings:get'),
    set: (k, v) => ipcRenderer.invoke('settings:set', String(k), !!v),
    clearCache: () => ipcRenderer.invoke('settings:clearCache'),
    testNotify: () => ipcRenderer.invoke('settings:testNotify'),
    checkUpdate: () => ipcRenderer.send('update:check'),
  },
  focus: {
    open: () => ipcRenderer.send('focus:open'),
    isOn: () => focusOn,
    onChange: (fn) => { focusListeners.push(fn); },
    embed: true,                                   // Focus page opens inside the app layout (index.html openFocus)
    onShow: (fn) => { focusShowListeners.push(fn); }, // tray → Focus Mode asks the website to show its Focus view
  },
  ext: {   // Safe Browser extensions (extensions.js)
    list: () => ipcRenderer.invoke('ext:list'),
    addFolder: () => ipcRenderer.invoke('ext:addFolder'),
    addFile: () => ipcRenderer.invoke('ext:addFile'),
    addStore: (s) => ipcRenderer.invoke('ext:addStore', String(s || '')),
    remove: (k) => ipcRenderer.invoke('ext:remove', String(k)),
    toggle: (k, on) => ipcRenderer.invoke('ext:toggle', String(k), !!on),
    open: (k) => ipcRenderer.invoke('ext:open', String(k)),
  },
  embed: true, // this build can show YouTube / Studio / Google inside the app layout (<webview>)
  // offline lesson videos (saved to disk by the main process)
  vid: {
    list: () => ipcRenderer.invoke('vid:list'),
    active: () => ipcRenderer.invoke('vid:active'),
    has: (id, url) => ipcRenderer.invoke('vid:has', String(id), String(url || '')),
    start: (o) => ipcRenderer.invoke('vid:start', { id: String(o.id), url: String(o.url), title: String(o.title || ''), course: String(o.course || ''), expireDays: Number(o.expireDays) || 7 }),
    remove: (id) => ipcRenderer.invoke('vid:remove', String(id)),
    cancel: (id) => ipcRenderer.send('vid:cancel', String(id)),
    src: (id) => 'rtvid://v/' + encodeURIComponent(String(id)),
    onEvent: (fn) => { ipcRenderer.on('vid:event', (_e, ev) => { try { fn(ev); } catch (_) {} }); },
  },
});
