'use strict';

/**
 * Profile persistence: JSON file in userData. Multiple SSH profiles, one
 * enabled. Secrets (password, key passphrase) are encrypted with Electron
 * safeStorage (OS keychain: DPAPI on Windows, Keychain on macOS).
 * Migrates the v0.1 flat single-server config on first load.
 */

const fs = require('fs');
const path = require('path');
const { app, safeStorage } = require('electron');

const FILE = () => path.join(app.getPath('userData'), 'config.json');

function enc(s) {
    return s ? safeStorage.encryptString(s).toString('base64') : '';
}

function dec(s) {
    try {
        return s ? safeStorage.decryptString(Buffer.from(s, 'base64')) : '';
    } catch (e) {
        return '';
    }
}

function toStored(p) {
    return {
        id: p.id, name: p.name, host: p.host, port: p.port, user: p.user,
        auth: p.auth, keyPath: p.keyPath || '',
        passwordEnc: enc(p.password), passphraseEnc: enc(p.passphrase),
    };
}

function fromStored(p) {
    return {
        id: p.id, name: p.name, host: p.host, port: p.port, user: p.user,
        auth: p.auth, keyPath: p.keyPath || '',
        password: dec(p.passwordEnc), passphrase: dec(p.passphraseEnc),
    };
}

function readRaw() {
    try {
        return JSON.parse(fs.readFileSync(FILE(), 'utf8'));
    } catch (e) {
        return null;
    }
}

function writeRaw(data) {
    fs.writeFileSync(FILE(), JSON.stringify(data, null, 2), { mode: 0o600 });
}

function migrate(raw) {
    if (raw && !raw.profiles && raw.host) {
        // v0.1 flat config -> first profile
        return {
            enabledId: 'default',
            profiles: [toStored({
                id: 'default', name: '默认服务器', host: raw.host, port: raw.port || 22,
                user: raw.user, auth: raw.auth || 'key', keyPath: raw.keyPath || '',
                password: dec(raw.passwordEnc), passphrase: dec(raw.passphraseEnc),
            })],
        };
    }
    return raw;
}

function load() {
    const raw = migrate(readRaw());
    if (!raw || !raw.profiles) return { profiles: [], enabledId: '' };
    return {
        enabledId: raw.enabledId || '',
        profiles: raw.profiles.map(fromStored),
    };
}

function persist(data) {
    writeRaw({ enabledId: data.enabledId, profiles: data.profiles.map(toStored) });
}

function saveProfile(p) {
    const data = load();
    const i = data.profiles.findIndex((x) => x.id === p.id);
    if (i >= 0) data.profiles[i] = p;
    else {
        p.id = p.id || Math.random().toString(36).slice(2, 10);
        data.profiles.push(p);
    }
    if (!data.enabledId) data.enabledId = p.id;
    persist(data);
    return p.id;
}

function deleteProfile(id) {
    const data = load();
    data.profiles = data.profiles.filter((p) => p.id !== id);
    if (data.enabledId === id) data.enabledId = data.profiles.length ? data.profiles[0].id : '';
    persist(data);
}

function setEnabled(id) {
    const data = load();
    data.enabledId = id;
    persist(data);
}

function enabledProfile() {
    const data = load();
    return data.profiles.find((p) => p.id === data.enabledId) || data.profiles[0] || null;
}

module.exports = { load, saveProfile, deleteProfile, setEnabled, enabledProfile };
