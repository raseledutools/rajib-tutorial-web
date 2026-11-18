'use strict';
const { ipcRenderer, contextBridge } = require('electron');

contextBridge.exposeInMainWorld('FocusAPI', {
  get: () => ipcRenderer.invoke('focus:get'),
  update: (patch) => ipcRenderer.invoke('focus:update', patch),
  start: (o) => ipcRenderer.invoke('focus:start', o || {}),
  stop: (o) => ipcRenderer.invoke('focus:stop', o || {}),
  pickExe: () => ipcRenderer.invoke('focus:pickExe'),
  running: () => ipcRenderer.invoke('focus:running'),
  onState: (fn) => { ipcRenderer.on('focus:state', (_e, s) => { try { fn(s); } catch (_) {} }); },
});
