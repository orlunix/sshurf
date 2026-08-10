'use strict';

/* global sshurf */

const wv = document.getElementById('wv');
const dot = document.getElementById('dot');
const stateEl = document.getElementById('state');
const urlEl = document.getElementById('url');
const settings = document.getElementById('settings');
const sidePanel = document.getElementById('sidepanel');
const logEl = document.getElementById('log');
const connBtn = document.getElementById('connbtn');

const FAV_SHOW = 5;   // favorites shown before "More"
const HIST_SHOW = 10; // history entries shown before "More"

let tunnelState = 'disconnected';
let profiles = [];
let enabledId = '';
let editingId = null;
let favExpanded = false;
let histExpanded = false;
let importMode = false;   // proflist shows ~/.ssh/config entries instead
let importEntries = [];
let newDraft = null;      // prefill for the add form (used by import)

// ---------- navigation ----------

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

// Keep the address bar in sync with whatever the webview ends up on.
wv.addEventListener('did-navigate', (e) => {
    if (e.url !== 'about:blank') urlEl.value = e.url;
    recordHistory(wv.getTitle() || e.url, e.url);
});
wv.addEventListener('did-navigate-in-page', (e) => {
    if (e.isMainFrame && e.url !== 'about:blank') urlEl.value = e.url;
});

// ---------- favorites + history (localStorage) ----------

const BM_KEY = 'sshurf.bookmarks';
const HIST_KEY = 'sshurf.history';
const loadBm = () => JSON.parse(localStorage.getItem(BM_KEY) || '[]');
const saveBm = (list) => localStorage.setItem(BM_KEY, JSON.stringify(list));
const loadHist = () => JSON.parse(localStorage.getItem(HIST_KEY) || '[]');
const saveHist = (list) => localStorage.setItem(HIST_KEY, JSON.stringify(list.slice(0, 20)));
const isFav = (url) => loadBm().some((b) => b.url === url);

function toggleFav(title, url) {
    let list = loadBm();
    if (isFav(url)) {
        list = list.filter((b) => b.url !== url);
    } else {
        list.push({ name: title || url, url });
    }
    saveBm(list);
    renderSidePanel();
}

function recordHistory(title, url) {
    if (!url || url === 'about:blank') return;
    const list = loadHist().filter((e) => e.url !== url);
    list.unshift({ title: title || url, url });
    saveHist(list);
}

wv.addEventListener('page-title-updated', (e) => {
    const list = loadHist();
    if (list.length && list[0].url === wv.getURL()) {
        list[0].title = e.title;
        saveHist(list);
    }
});

function row(entry, { fav }) {
    const r = document.createElement('div');
    r.className = fav ? 'frow' : 'hrow';
    const t = document.createElement('div');
    t.className = 't';
    t.textContent = fav ? entry.name : entry.title;
    t.title = entry.url;
    t.addEventListener('click', () => {
        wv.src = entry.url;
        sidePanel.classList.remove('open');
    });
    r.appendChild(t);
    if (fav) {
        const del = document.createElement('button');
        del.textContent = '×';
        del.title = 'Remove favorite';
        del.addEventListener('click', () => toggleFav(entry.name, entry.url));
        r.appendChild(del);
    } else {
        const star = document.createElement('button');
        const faved = isFav(entry.url);
        star.className = 'star' + (faved ? ' on' : '');
        star.textContent = faved ? '★' : '☆';
        star.title = faved ? 'Remove favorite' : 'Add to favorites';
        star.addEventListener('click', () => toggleFav(entry.title, entry.url));
        const del = document.createElement('button');
        del.textContent = '×';
        del.title = 'Delete entry';
        del.addEventListener('click', () => {
            saveHist(loadHist().filter((h) => h.url !== entry.url));
            renderSidePanel();
        });
        r.append(star, del);
    }
    return r;
}

function renderSidePanel() {
    const favs = loadBm();
    const favBox = document.getElementById('favlist');
    favBox.innerHTML = '';
    (favExpanded ? favs : favs.slice(0, FAV_SHOW))
        .forEach((b) => favBox.appendChild(row(b, { fav: true })));
    if (!favs.length) favBox.innerHTML = '<div class="small">(star entries from History below)</div>';
    const favMore = document.getElementById('favmore');
    if (favs.length > FAV_SHOW) {
        favMore.style.display = 'block';
        favMore.textContent = favExpanded ? 'Collapse' : `More (${favs.length} total)…`;
        favMore.onclick = () => { favExpanded = !favExpanded; renderSidePanel(); };
    } else {
        favMore.style.display = 'none';
    }

    const hist = loadHist();
    const histBox = document.getElementById('histlist');
    histBox.innerHTML = '';
    (histExpanded ? hist : hist.slice(0, HIST_SHOW))
        .forEach((e) => histBox.appendChild(row(e, { fav: false })));
    if (!hist.length) histBox.innerHTML = '<div class="small">(empty)</div>';
    const histMore = document.getElementById('histmore');
    if (hist.length > HIST_SHOW) {
        histMore.style.display = 'block';
        histMore.textContent = histExpanded ? 'Collapse' : `More (${hist.length} total)…`;
        histMore.onclick = () => { histExpanded = !histExpanded; renderSidePanel(); };
    } else {
        histMore.style.display = 'none';
    }
}

document.getElementById('favhist').addEventListener('click', () => {
    renderSidePanel();
    sidePanel.classList.toggle('open');
    settings.classList.remove('open');
});

// ---------- SSH profiles ----------

async function refreshProfiles() {
    const data = await sshurf.listProfiles();
    profiles = data.profiles;
    enabledId = data.enabledId;
    renderProfiles();
}

function renderProfiles() {
    const box = document.getElementById('proflist');
    box.innerHTML = '';

    if (importMode) {
        if (!importEntries.length) {
            box.innerHTML = '<div class="small">(no importable hosts found)</div>';
        }
        importEntries.forEach((e) => {
            const rowEl = document.createElement('div');
            rowEl.className = 'prow';
            const meta = document.createElement('div');
            meta.className = 'meta';
            meta.innerHTML = '<div class="name"></div><div class="sum"></div>';
            meta.querySelector('.name').textContent = e.name;
            meta.querySelector('.sum').textContent =
                `${e.user}@${e.host}${e.port && e.port !== 22 ? ':' + e.port : ''}`
                + (e.keyPath ? ' · key ' + e.keyPath.split(/[\\/]/).pop() : '');
            rowEl.appendChild(meta);
            rowEl.addEventListener('click', () => {
                newDraft = {
                    id: '', name: e.name, host: e.host, port: e.port || 22,
                    user: e.user, auth: e.keyPath ? 'key' : 'password',
                    keyPath: e.keyPath || '', password: '', passphrase: '',
                };
                importMode = false;
                editingId = 'new';
                renderProfiles();
            });
            box.appendChild(rowEl);
        });
        const back = document.createElement('button');
        back.textContent = '← Back';
        back.addEventListener('click', () => { importMode = false; renderProfiles(); });
        box.appendChild(back);
        return;
    }

    profiles.forEach((p) => {
        if (editingId === p.id) {
            box.appendChild(profileForm(p));
            return;
        }
        const rowEl = document.createElement('div');
        rowEl.className = 'prow' + (p.id === enabledId ? ' enabled' : '');
        const radio = document.createElement('span');
        radio.textContent = p.id === enabledId ? '◉' : '○';
        const meta = document.createElement('div');
        meta.className = 'meta';
        meta.innerHTML = '<div class="name"></div><div class="sum"></div>';
        meta.querySelector('.name').textContent = p.name;
        meta.querySelector('.sum').textContent =
            `${p.user}@${p.host}${p.port === 22 ? '' : ':' + p.port}${p.auth === 'key' ? ' · key' : ''}`;
        const edit = document.createElement('button');
        edit.textContent = 'Edit';
        edit.addEventListener('click', (e) => { e.stopPropagation(); editingId = p.id; renderProfiles(); });
        rowEl.append(radio, meta, edit);
        rowEl.addEventListener('click', async () => {
            await sshurf.enableProfile(p.id);
            enabledId = p.id;
            renderProfiles();
        });
        box.appendChild(rowEl);
    });
    if (editingId === 'new') box.appendChild(profileForm(null));
}

function profileForm(p) {
    const isNew = !p;
    const draft = p || Object.assign(
        { id: '', name: '', host: '', port: 22, user: '', auth: 'key', keyPath: '', password: '', passphrase: '' },
        newDraft);
    newDraft = null;
    const form = document.createElement('div');
    form.className = 'pform';
    form.innerHTML = `
        <input type="text" data-f="name" placeholder="Name (e.g. work)">
        <input type="text" data-f="host" placeholder="Host">
        <input type="number" data-f="port" placeholder="Port (default 22)">
        <input type="text" data-f="user" placeholder="Username">
        <div class="row">
          <label><input type="radio" name="auth" data-f="auth-key" style="width:auto"> Private key</label>
          <label><input type="radio" name="auth" data-f="auth-pass" style="width:auto"> Password</label>
        </div>
        <div class="row"><button type="button" data-f="pick">Choose key file…</button></div>
        <div class="small" data-f="keypath">(none)</div>
        <input type="password" data-f="passphrase" placeholder="Key passphrase (optional)">
        <input type="password" data-f="password" placeholder="Password (for password auth)">
        <div class="row">
          <button type="button" data-f="save">Save</button>
          ${isNew ? '' : '<button type="button" data-f="del">Delete</button>'}
          <button type="button" data-f="cancel">Cancel</button>
        </div>`;
    const q = (sel) => form.querySelector(`[data-f="${sel}"]`);
    q('name').value = draft.name;
    q('host').value = draft.host;
    q('port').value = draft.port || '';
    q('user').value = draft.user;
    q('passphrase').value = draft.passphrase || '';
    q('password').value = draft.password || '';
    q(draft.auth === 'key' ? 'auth-key' : 'auth-pass').checked = true;
    if (draft.keyPath) q('keypath').textContent = draft.keyPath;

    q('pick').addEventListener('click', async () => {
        const path = await sshurf.pickKey();
        if (path) {
            q('keypath').textContent = path;
            q('keypath').dataset.path = path;
            q('auth-key').checked = true;
        }
    });
    q('save').addEventListener('click', async () => {
        const out = {
            id: draft.id,
            name: q('name').value.trim() || q('host').value.trim(),
            host: q('host').value.trim(),
            port: parseInt(q('port').value, 10) || 22,
            user: q('user').value.trim(),
            auth: q('auth-key').checked ? 'key' : 'password',
            keyPath: q('keypath').dataset.path || draft.keyPath || '',
            passphrase: q('passphrase').value,
            password: q('password').value,
        };
        if (!out.host || !out.user) { alert('Host and username are required'); return; }
        await sshurf.saveProfile(out);
        editingId = null;
        refreshProfiles();
    });
    if (!isNew) {
        q('del').addEventListener('click', async () => {
            if (confirm(`Delete "${draft.name}"?`)) {
                await sshurf.deleteProfile(draft.id);
                editingId = null;
                refreshProfiles();
            }
        });
    }
    q('cancel').addEventListener('click', () => { editingId = null; renderProfiles(); });
    return form;
}

document.getElementById('addprof').addEventListener('click', () => {
    importMode = false;
    editingId = editingId === 'new' ? null : 'new';
    renderProfiles();
});

document.getElementById('importbtn').addEventListener('click', async () => {
    const r = await sshurf.listSshConfig();
    if (r.error) {
        alert(r.error);
        return;
    }
    importEntries = r.entries;
    importMode = true;
    editingId = null;
    renderProfiles();
});

document.getElementById('gear').addEventListener('click', () => {
    settings.classList.toggle('open');
    sidePanel.classList.remove('open');
    if (settings.classList.contains('open')) {
        importMode = false;
        refreshProfiles();
    }
});

// ---------- tunnel ----------

connBtn.addEventListener('click', async () => {
    if (tunnelState === 'disconnected') {
        if (!profiles.length) { alert('Add a server first'); return; }
        await sshurf.connect();
    } else {
        await sshurf.disconnect();
    }
});

sshurf.onStatus((state, detail) => {
    tunnelState = state;
    dot.className = state === 'connected' ? 'on' : state === 'connecting' ? 'ing' : '';
    stateEl.textContent = state === 'connected' ? 'Connected'
        : state === 'connecting' ? (detail || 'Connecting…') : 'Disconnected';
    connBtn.textContent = state === 'disconnected' ? 'Connect' : 'Disconnect';
});

sshurf.onLog((msg) => {
    const line = document.createElement('div');
    line.className = msg.includes('✗') ? 'fail' : msg.includes('✓') ? 'ok' : '';
    line.textContent = new Date().toLocaleTimeString('en-GB') + '  ' + msg;
    logEl.appendChild(line);
    logEl.scrollTop = logEl.scrollHeight;
});

// ---------- init ----------

(async () => {
    const data = await sshurf.listProfiles();
    profiles = data.profiles;
    enabledId = data.enabledId;
    if (!profiles.length) settings.classList.add('open'); // first run: guide to config
})();
