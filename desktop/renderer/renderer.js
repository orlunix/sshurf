'use strict';

/* global sshurf */

const wv = document.getElementById('wv');
const dot = document.getElementById('dot');
const stateEl = document.getElementById('state');
const urlEl = document.getElementById('url');
const toggleBtn = document.getElementById('toggle');
const settings = document.getElementById('settings');
const logEl = document.getElementById('log');

let connected = false;
let keyPath = '';

// --- navigation ---

function toUrl(input) {
    if (!input) return null;
    if (input.includes('://')) return input;
    if (input.includes('.') && !input.includes(' ')) return 'https://' + input;
    return 'https://www.baidu.com/s?wd=' + encodeURIComponent(input);
}

function go() {
    const url = toUrl(urlEl.value.trim());
    if (url) wv.src = url;
}

urlEl.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
document.getElementById('go').addEventListener('click', go);

// --- settings panel ---

document.getElementById('gear').addEventListener('click', () => {
    settings.classList.toggle('open');
});

document.getElementById('s-pickkey').addEventListener('click', async () => {
    const p = await sshurf.pickKey();
    if (p) {
        keyPath = p;
        document.getElementById('keypath').textContent = p;
        document.getElementById('s-auth-key').checked = true;
    }
});

function readForm() {
    return {
        host: document.getElementById('s-host').value.trim(),
        port: parseInt(document.getElementById('s-port').value, 10) || 22,
        user: document.getElementById('s-user').value.trim(),
        auth: document.getElementById('s-auth-key').checked ? 'key' : 'password',
        keyPath,
        passphrase: document.getElementById('s-passphrase').value,
        password: document.getElementById('s-password').value,
    };
}

document.getElementById('s-save').addEventListener('click', async () => {
    const cfg = readForm();
    await sshurf.saveConfig(cfg);
    await sshurf.connect(cfg);
});

toggleBtn.addEventListener('click', async () => {
    if (connected || dot.classList.contains('ing')) {
        await sshurf.disconnect();
    } else {
        await sshurf.connect(null); // use saved config
    }
});

// --- status & log ---

sshurf.onStatus((state, detail) => {
    connected = state === 'connected';
    dot.className = state === 'connected' ? 'on' : state === 'connecting' ? 'ing' : '';
    stateEl.textContent = state === 'connected' ? '已连接'
        : state === 'connecting' ? (detail || '连接中…') : '未连接';
    toggleBtn.textContent = state === 'disconnected' ? '连接' : '断开';
});

sshurf.onLog((msg) => {
    const line = document.createElement('div');
    line.className = msg.includes('✗') ? 'fail' : msg.includes('✓') ? 'ok' : '';
    line.textContent = new Date().toLocaleTimeString('en-GB') + '  ' + msg;
    logEl.appendChild(line);
    logEl.scrollTop = logEl.scrollHeight;
});

// --- init ---

(async () => {
    const cfg = await sshurf.loadConfig();
    if (cfg.host) document.getElementById('s-host').value = cfg.host;
    if (cfg.port) document.getElementById('s-port').value = cfg.port;
    if (cfg.user) document.getElementById('s-user').value = cfg.user;
    if (cfg.keyPath) {
        keyPath = cfg.keyPath;
        document.getElementById('keypath').textContent = cfg.keyPath;
    }
    if (cfg.auth === 'password') document.getElementById('s-auth-pass').checked = true;
    if (cfg.password) document.getElementById('s-password').value = cfg.password;
    if (cfg.passphrase) document.getElementById('s-passphrase').value = cfg.passphrase;
    if (!cfg.host) settings.classList.add('open'); // first run: guide to config
})();
