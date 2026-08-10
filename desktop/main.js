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
        title: 'sshurf desktop',
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

ipcMain.handle('profiles:list', () => store.load());
ipcMain.handle('profiles:save', (_e, p) => store.saveProfile(p));
ipcMain.handle('profiles:delete', (_e, id) => store.deleteProfile(id));
ipcMain.handle('profiles:enable', (_e, id) => store.setEnabled(id));

ipcMain.handle('key:pick', async () => {
    const r = await dialog.showOpenDialog(win, {
        title: '选择私钥文件',
        properties: ['openFile'],
    });
    return r.canceled ? null : r.filePaths[0];
});

ipcMain.handle('tunnel:connect', () => {
    const p = store.enabledProfile();
    if (p) tunnel.start(p);
});

ipcMain.handle('tunnel:disconnect', () => tunnel.stop());

// --- app lifecycle ---

app.whenReady().then(() => {
    tunnel.on('status', (state, detail) => send('tunnel:status', state, detail));
    tunnel.on('log', (msg) => send('tunnel:log', msg));

    createWindow();

    // Desktop is browse-only: auto-connect on launch when a profile exists.
    const p = store.enabledProfile();
    if (p && p.host && p.user) {
        tunnel.start(p);
    }

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
});

app.on('window-all-closed', () => {
    tunnel.stop();
    if (process.platform !== 'darwin') app.quit();
});
