'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('sshurf', {
    loadConfig: () => ipcRenderer.invoke('config:load'),
    saveConfig: (cfg) => ipcRenderer.invoke('config:save', cfg),
    pickKey: () => ipcRenderer.invoke('key:pick'),
    connect: (cfg) => ipcRenderer.invoke('tunnel:connect', cfg),
    disconnect: () => ipcRenderer.invoke('tunnel:disconnect'),
    onStatus: (fn) => ipcRenderer.on('tunnel:status', (_e, state, detail) => fn(state, detail)),
    onLog: (fn) => ipcRenderer.on('tunnel:log', (_e, msg) => fn(msg)),
});
