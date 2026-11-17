'use strict';
const { ipcRenderer, contextBridge } = require('electron');

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

// Update button (top center) — visible only when a newer release exists
let lastUpdate = { status: 'idle' };
function renderUpdateBtn() {
  let b = document.getElementById('__rt_update');
  if (lastUpdate.status === 'idle') { if (b) b.remove(); return; }
  if (!b) {
    b = document.createElement('button');
    b.id = '__rt_update';
    b.style.cssText = 'position:fixed;top:8px;left:50%;transform:translateX(-50%);z-index:2147483647;border:0;border-radius:999px;padding:8px 18px;background:linear-gradient(135deg,#16a34a,#22c55e);color:#fff;font:600 13px system-ui,sans-serif;cursor:pointer;box-shadow:0 4px 14px rgba(0,0,0,.35)';
    b.onclick = () => { if (lastUpdate.status === 'available') ipcRenderer.send('update:install'); };
    (document.body || document.documentElement).appendChild(b);
  }
  const v = lastUpdate.version ? ' v' + lastUpdate.version : '';
  if (lastUpdate.status === 'available') { b.textContent = '🔄 নতুন আপডেট' + v + ' — ক্লিক করে আপডেট করুন'; b.style.cursor = 'pointer'; }
  else if (lastUpdate.status === 'downloading') { b.textContent = '⬇ ডাউনলোড হচ্ছে… ' + (lastUpdate.percent || 0) + '%'; b.style.cursor = 'default'; }
  else if (lastUpdate.status === 'installing') { b.textContent = '✅ ইনস্টল হচ্ছে, অ্যাপ রিস্টার্ট হবে…'; b.style.cursor = 'default'; }
}
ipcRenderer.on('update:state', (_e, st) => { lastUpdate = st || { status: 'idle' }; renderUpdateBtn(); });
window.addEventListener('DOMContentLoaded', () => { ipcRenderer.send('update:get'); });
window.addEventListener('load', renderUpdateBtn);

// Expose a safe bridge so the website can open URLs inside the Electron app
// (used by openInAppBrowser for YouTube, Google, YouTube Studio etc.)
contextBridge.exposeInMainWorld('ElectronShell', {
  openUrl: (url) => ipcRenderer.send('shell:openUrl', url),
  notify: (title, body) => ipcRenderer.send('notify:show', String(title || ''), String(body || '')),
  isElectron: true,
});
