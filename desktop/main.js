'use strict';

const { app, BrowserWindow, ipcMain, dialog, session } = require('electron');
const fs = require('fs');
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

// Parse ~/.ssh/config (OpenSSH client config) into importable profiles.
ipcMain.handle('sshconfig:list', () => {
    const os = require('os');
    const cfgPath = path.join(os.homedir(), '.ssh', 'config');
    let text;
    try {
        text = fs.readFileSync(cfgPath, 'utf8');
    } catch (e) {
        return { error: `Not found: ${cfgPath}`, entries: [] };
    }
    const home = os.homedir();
    const expand = (v) => v.replace(/^["']|["']$/g, '')
        .replace(/^~/, home).replace(/%d/g, home);
    const entries = [];
    let cur = null;
    for (const rawLine of text.split(/\r?\n/)) {
        const line = rawLine.trim();
        if (!line || line.startsWith('#')) continue;
        const m = line.match(/^(\S+)\s+(.+)$/);
        if (!m) continue;
        const key = m[1].toLowerCase();
        const val = m[2].trim();
        if (key === 'host') {
            if (cur) entries.push(cur);
            const names = val.split(/\s+/)
                .filter((n) => !n.includes('*') && !n.includes('?') && !n.startsWith('!'));
            cur = names.length ? { name: names[0] } : null;
        } else if (cur) {
            if (key === 'hostname') cur.host = expand(val);
            else if (key === 'port') cur.port = parseInt(val, 10) || 22;
            else if (key === 'user') cur.user = val;
            else if (key === 'identityfile') cur.keyPath = expand(val);
        }
    }
    if (cur) entries.push(cur);
    return { entries: entries.filter((e) => e.host && e.user) };
});

ipcMain.handle('key:pick', async () => {
    const r = await dialog.showOpenDialog(win, {
        title: 'Choose private key file',
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
