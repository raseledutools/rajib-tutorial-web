'use strict';
const { ipcRenderer, contextBridge } = require('electron');

// Custom title bar (white, drag area, app icon + title). Native min/max/close buttons come from titleBarOverlay in main.js.
function buildTitleBar() {
  if (!document.body || document.getElementById('__rt_titlebar')) return;
  const isFile = location.protocol === 'file:';
  const bar = document.createElement('div');
  bar.id = '__rt_titlebar';
  bar.style.cssText = 'position:fixed;top:0;left:0;right:0;height:36px;z-index:2147483646;display:flex;align-items:center;gap:8px;padding:0 150px 0 12px;background:#fff;border-bottom:1px solid #E3E6EA;font:600 13px "Segoe UI",system-ui,sans-serif;color:#1f2937;-webkit-app-region:drag;user-select:none';
  bar.innerHTML = '<svg width="18" height="18" viewBox="0 0 100 100" fill="none" stroke="#00959c" stroke-width="9" stroke-linecap="round"><path d="M50 14v72M14 50h72M24 24l52 52M76 24L24 76"/></svg><span>Rajib Tutorial</span>';
  document.body.appendChild(bar);
  // Local pages (focus / offline) have no layout that knows about the bar: push their content down.
  if (isFile && !location.pathname.endsWith('splash.html')) document.documentElement.style.paddingTop = '36px';
}
window.addEventListener('DOMContentLoaded', buildTitleBar);
window.addEventListener('load', buildTitleBar);

// Nav bar (back / forward / reload)
function buildNavBar() {
  if (location.protocol === 'file:' && location.pathname.endsWith('splash.html')) return;
  if (document.getElementById('__rt_nav')) return;
  const bar = document.createElement('div');
  bar.id = '__rt_nav';
  bar.style.cssText = 'position:fixed;left:50%;bottom:14px;transform:translateX(-50%);z-index:2147483647;display:flex;gap:6px;padding:6px 8px;background:rgba(30,30,40,.88);border-radius:999px;box-shadow:0 4px 16px rgba(0,0,0,.3);backdrop-filter:blur(6px);font-family:system-ui,sans-serif';
  const mk = (txt, title, act) => {
    const b = document.createElement('button');
    b.textContent = txt; b.title = title;
    b.style.cssText = 'width:34px;height:34px;border:0;border-radius:50%;background:rgba(255,255,255,.14);color:#fff;font-size:17px;cursor:pointer';
    b.onmouseenter = () => (b.style.background = 'rgba(255,255,255,.3)');
    b.onmouseleave = () => (b.style.background = 'rgba(255,255,255,.14)');
    b.onclick = () => ipcRenderer.send('nav', act);
    return b;
  };
  bar.append(mk('←', 'Back', 'back'), mk('→', 'Forward', 'forward'), mk('⟳', 'Refresh', 'reload'));
  (document.body || document.documentElement).appendChild(bar);
}
window.addEventListener('DOMContentLoaded', buildNavBar);
window.addEventListener('load', buildNavBar);

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


// Focus Mode: floating button (above the update-check button) opens the Focus window
let focusOn = false;
function renderFocusBtn() {
  if (location.protocol === 'file:') return;
  let f = document.getElementById('__rt_focus');
  if (!f) {
    f = document.createElement('button');
    f.id = '__rt_focus';
    f.style.cssText = 'position:fixed;right:14px;bottom:58px;z-index:2147483646;border:0;border-radius:999px;padding:8px 14px;color:#fff;font:600 12.5px system-ui,sans-serif;cursor:pointer;box-shadow:0 4px 16px rgba(0,0,0,.3);backdrop-filter:blur(6px)';
    f.onclick = () => ipcRenderer.send('focus:open');
    (document.body || document.documentElement).appendChild(f);
  }
  f.textContent = focusOn ? '🎯 Focus চলছে' : '🎯 Focus Mode';
  f.style.background = focusOn ? 'rgba(22,163,74,.92)' : 'rgba(12,168,176,.92)';
}
ipcRenderer.on('focus:badge', (_e, on) => { focusOn = !!on; renderFocusBtn(); });
window.addEventListener('DOMContentLoaded', renderFocusBtn);
window.addEventListener('load', renderFocusBtn);

// Expose a safe bridge so the website can open URLs inside the Electron app
// (used by openInAppBrowser for YouTube, Google, YouTube Studio etc.)
contextBridge.exposeInMainWorld('ElectronShell', {
  openUrl: (url) => ipcRenderer.send('shell:openUrl', url),
  notify: (title, body) => ipcRenderer.send('notify:show', String(title || ''), String(body || '')),
  isElectron: true,
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

// Focus Mode API — only on the local focus.html page (never exposed to the website)
if (location.protocol === 'file:' && location.pathname.endsWith('focus.html')) {
  contextBridge.exposeInMainWorld('FocusAPI', {
    get: () => ipcRenderer.invoke('focus:get'),
    update: (patch) => ipcRenderer.invoke('focus:update', patch),
    start: (o) => ipcRenderer.invoke('focus:start', o || {}),
    stop: (o) => ipcRenderer.invoke('focus:stop', o || {}),
    pickExe: () => ipcRenderer.invoke('focus:pickExe'),
    running: () => ipcRenderer.invoke('focus:running'),
    onState: (fn) => { ipcRenderer.on('focus:state', (_e, st) => { try { fn(st); } catch (_) {} }); },
  });
}
