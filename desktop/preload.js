'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  info: () => ipcRenderer.invoke('app:info'),
  openFiles: (opts) => ipcRenderer.invoke('file:open', opts),
  saveFile: (opts) => ipcRenderer.invoke('file:save', opts),
  saveMany: (opts) => ipcRenderer.invoke('file:saveMany', opts),
  openExternalWindow: (url) => ipcRenderer.invoke('win:open', url),
  fs: {
    list: () => ipcRenderer.invoke('fs:list'),
    get: (id) => ipcRenderer.invoke('fs:get', id),
    memberSession: (mobile, patch) => ipcRenderer.invoke('fs:memberSession', { mobile, patch }),
    chat: (limit) => ipcRenderer.invoke('fs:chat', limit),
    post: (m) => ipcRenderer.invoke('fs:post', m),
    push: () => ipcRenderer.invoke('fs:push'),
  },
  pay: (d) => ipcRenderer.invoke('pay:create', d),
  focus: {
    status: () => ipcRenderer.invoke('focus:status'),
    setPin: (pin, oldPin) => ipcRenderer.invoke('focus:setPin', { pin, oldPin }),
    start: (cfg) => ipcRenderer.invoke('focus:start', cfg),
    stop: (pin) => ipcRenderer.invoke('focus:stop', { pin }),
  },
});
