'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  info: () => ipcRenderer.invoke('app:info'),
  openFiles: (opts) => ipcRenderer.invoke('file:open', opts),
  saveFile: (opts) => ipcRenderer.invoke('file:save', opts),
  saveMany: (opts) => ipcRenderer.invoke('file:saveMany', opts),
  openExternalWindow: (url) => ipcRenderer.invoke('win:open', url),
  focus: {
    status: () => ipcRenderer.invoke('focus:status'),
    setPin: (pin, oldPin) => ipcRenderer.invoke('focus:setPin', { pin, oldPin }),
    start: (cfg) => ipcRenderer.invoke('focus:start', cfg),
    stop: (pin) => ipcRenderer.invoke('focus:stop', { pin }),
  },
});
