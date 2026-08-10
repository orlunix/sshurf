'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('sshurf', {
    listProfiles: () => ipcRenderer.invoke('profiles:list'),
    saveProfile: (p) => ipcRenderer.invoke('profiles:save', p),
    deleteProfile: (id) => ipcRenderer.invoke('profiles:delete', id),
    enableProfile: (id) => ipcRenderer.invoke('profiles:enable', id),
    pickKey: () => ipcRenderer.invoke('key:pick'),
    connect: () => ipcRenderer.invoke('tunnel:connect'),
    disconnect: () => ipcRenderer.invoke('tunnel:disconnect'),
    onStatus: (fn) => ipcRenderer.on('tunnel:status', (_e, state, detail) => fn(state, detail)),
    onLog: (fn) => ipcRenderer.on('tunnel:log', (_e, msg) => fn(msg)),
});
