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

const FAV_SHOW = 5;   // favorites shown before "更多"
const HIST_SHOW = 10; // history entries shown before "更多"

let tunnelState = 'disconnected';
let profiles = [];
let enabledId = '';
let editingId = null;
let favExpanded = false;
let histExpanded = false;

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
        del.title = '取消收藏';
        del.addEventListener('click', () => toggleFav(entry.name, entry.url));
        r.appendChild(del);
    } else {
        const star = document.createElement('button');
        const faved = isFav(entry.url);
        star.className = 'star' + (faved ? ' on' : '');
        star.textContent = faved ? '★' : '☆';
        star.title = faved ? '取消收藏' : '收藏';
        star.addEventListener('click', () => toggleFav(entry.title, entry.url));
        const del = document.createElement('button');
        del.textContent = '×';
        del.title = '删除记录';
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
    if (!favs.length) favBox.innerHTML = '<div class="small">（从历史记录点 ☆ 收藏）</div>';
    const favMore = document.getElementById('favmore');
    if (favs.length > FAV_SHOW) {
        favMore.style.display = 'block';
        favMore.textContent = favExpanded ? '收起' : `更多（共 ${favs.length} 条）…`;
        favMore.onclick = () => { favExpanded = !favExpanded; renderSidePanel(); };
    } else {
        favMore.style.display = 'none';
    }

    const hist = loadHist();
    const histBox = document.getElementById('histlist');
    histBox.innerHTML = '';
    (histExpanded ? hist : hist.slice(0, HIST_SHOW))
        .forEach((e) => histBox.appendChild(row(e, { fav: false })));
    if (!hist.length) histBox.innerHTML = '<div class="small">（暂无记录）</div>';
    const histMore = document.getElementById('histmore');
    if (hist.length > HIST_SHOW) {
        histMore.style.display = 'block';
        histMore.textContent = histExpanded ? '收起' : `更多（共 ${hist.length} 条）…`;
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
            `${p.user}@${p.host}${p.port === 22 ? '' : ':' + p.port}${p.auth === 'key' ? ' · 密钥' : ''}`;
        const edit = document.createElement('button');
        edit.textContent = '编辑';
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
    p = p || { id: '', name: '', host: '', port: 22, user: '', auth: 'key', keyPath: '', password: '', passphrase: '' };
    const form = document.createElement('div');
    form.className = 'pform';
    form.innerHTML = `
        <input type="text" data-f="name" placeholder="名称（如：公司服务器）">
        <input type="text" data-f="host" placeholder="主机">
        <input type="number" data-f="port" placeholder="端口（默认 22）">
        <input type="text" data-f="user" placeholder="用户名">
        <div class="row">
          <label><input type="radio" name="auth" data-f="auth-key" style="width:auto"> 私钥</label>
          <label><input type="radio" name="auth" data-f="auth-pass" style="width:auto"> 密码</label>
        </div>
        <div class="row"><button type="button" data-f="pick">选择私钥文件</button></div>
        <div class="small" data-f="keypath">（未选择）</div>
        <input type="password" data-f="passphrase" placeholder="私钥口令（可选）">
        <input type="password" data-f="password" placeholder="密码（选密码认证时填）">
        <div class="row">
          <button type="button" data-f="save">保存</button>
          ${isNew ? '' : '<button type="button" data-f="del">删除</button>'}
          <button type="button" data-f="cancel">收起</button>
        </div>`;
    const q = (sel) => form.querySelector(`[data-f="${sel}"]`);
    q('name').value = p.name;
    q('host').value = p.host;
    q('port').value = p.port || '';
    q('user').value = p.user;
    q('passphrase').value = p.passphrase || '';
    q('password').value = p.password || '';
    q(p.auth === 'key' ? 'auth-key' : 'auth-pass').checked = true;
    if (p.keyPath) q('keypath').textContent = p.keyPath;

    q('pick').addEventListener('click', async () => {
        const path = await sshurf.pickKey();
        if (path) {
            q('keypath').textContent = path;
            q('keypath').dataset.path = path;
            q('auth-key').checked = true;
        }
    });
    q('save').addEventListener('click', async () => {
        const draft = {
            id: p.id,
            name: q('name').value.trim() || q('host').value.trim(),
            host: q('host').value.trim(),
            port: parseInt(q('port').value, 10) || 22,
            user: q('user').value.trim(),
            auth: q('auth-key').checked ? 'key' : 'password',
            keyPath: q('keypath').dataset.path || p.keyPath || '',
            passphrase: q('passphrase').value,
            password: q('password').value,
        };
        if (!draft.host || !draft.user) { alert('主机和用户名必填'); return; }
        await sshurf.saveProfile(draft);
        editingId = null;
        refreshProfiles();
    });
    if (!isNew) {
        q('del').addEventListener('click', async () => {
            if (confirm(`删除「${p.name}」？`)) {
                await sshurf.deleteProfile(p.id);
                editingId = null;
                refreshProfiles();
            }
        });
    }
    q('cancel').addEventListener('click', () => { editingId = null; renderProfiles(); });
    return form;
}

document.getElementById('addprof').addEventListener('click', () => {
    editingId = editingId === 'new' ? null : 'new';
    renderProfiles();
});

document.getElementById('gear').addEventListener('click', () => {
    settings.classList.toggle('open');
    sidePanel.classList.remove('open');
    if (settings.classList.contains('open')) refreshProfiles();
});

// ---------- tunnel ----------

connBtn.addEventListener('click', async () => {
    if (tunnelState === 'disconnected') {
        if (!profiles.length) { alert('请先添加服务器'); return; }
        await sshurf.connect();
    } else {
        await sshurf.disconnect();
    }
});

sshurf.onStatus((state, detail) => {
    tunnelState = state;
    dot.className = state === 'connected' ? 'on' : state === 'connecting' ? 'ing' : '';
    stateEl.textContent = state === 'connected' ? '已连接'
        : state === 'connecting' ? (detail || '连接中…') : '未连接';
    connBtn.textContent = state === 'disconnected' ? '连接' : '断开';
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
