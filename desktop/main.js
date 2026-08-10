'use strict';

const { app, BrowserWindow, ipcMain, dialog, session } = require('electron');
const path = require('path');
const { Tunnel, SOCKS_PORT } = require('./tunnel');
const store = require('./store');

const tunnel = new Tunnel();
let win = null;

function send(channel, ...args) {
    if (win && !win.isDestroyed()) win.webContents.send(channel, ...args);
}

function createWindow() {
    win = new BrowserWindow({
        width: 1200,
        height: 800,
        title: 'sshurf',
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            webviewTag: true,
        },
    });
    win.removeMenu();
    win.loadFile(path.join(__dirname, 'renderer', 'index.html'));

    // The webview's dedicated session goes through the tunnel; the app UI
    // itself (default session) stays direct.
    const browserSession = session.fromPartition('persist:browser');
    browserSession.setProxy({ proxyRules: `socks5://127.0.0.1:${SOCKS_PORT}` });
}

// --- IPC ---

ipcMain.handle('config:load', () => store.load() || {});

ipcMain.handle('config:save', (_e, cfg) => {
    store.save(cfg);
    return true;
});

ipcMain.handle('key:pick', async () => {
    const r = await dialog.showOpenDialog(win, {
        title: '选择私钥文件',
        properties: ['openFile'],
    });
    return r.canceled ? null : r.filePaths[0];
});

ipcMain.handle('tunnel:connect', (_e, cfg) => {
    if (cfg) store.save(cfg);
    tunnel.start(cfg || store.load());
});

ipcMain.handle('tunnel:disconnect', () => tunnel.stop());

// --- app lifecycle ---

app.whenReady().then(() => {
    tunnel.on('status', (state, detail) => send('tunnel:status', state, detail));
    tunnel.on('log', (msg) => send('tunnel:log', msg));

    createWindow();

    // Desktop is browse-only: auto-connect on launch when config is complete.
    const cfg = store.load();
    if (cfg && cfg.host && cfg.user && (cfg.password || cfg.keyPath)) {
        tunnel.start(cfg);
    }

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

app.on('window-all-closed', () => {
    tunnel.stop();
    if (process.platform !== 'darwin') app.quit();
});
