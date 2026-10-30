const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('wf', {
  getKey: () => ipcRenderer.invoke('key:get'),
  setKey: k => ipcRenderer.invoke('key:set', k),
  routes: (body, mask) => ipcRenderer.invoke('routes', body, mask)
});
