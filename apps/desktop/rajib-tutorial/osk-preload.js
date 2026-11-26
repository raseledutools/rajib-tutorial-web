'use strict';
// Bridge for the on-screen keyboard window (osk.html) — nothing else is exposed.
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('oskApi', {
  key: (k) => ipcRenderer.send('osk:key', String(k).slice(0, 8)),
  hide: () => ipcRenderer.send('osk:hide'),
  flip: () => ipcRenderer.send('osk:flip'),
  toggle: () => ipcRenderer.send('osk:toggle'),
});
