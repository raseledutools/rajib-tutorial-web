'use strict';
const { ipcRenderer } = require('electron');

function build() {
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
window.addEventListener('DOMContentLoaded', build);
window.addEventListener('load', build);
