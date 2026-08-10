'use strict';

/**
 * Config persistence: JSON file in userData. Secrets (password, key
 * passphrase) are encrypted with Electron safeStorage (OS keychain: DPAPI
 * on Windows, Keychain on macOS). Key itself is referenced by file path.
 */

const fs = require('fs');
const path = require('path');
const { app, safeStorage } = require('electron');

const FILE = () => path.join(app.getPath('userData'), 'config.json');

function load() {
    try {
        const raw = JSON.parse(fs.readFileSync(FILE(), 'utf8'));
        if (raw.passwordEnc) {
            raw.password = safeStorage.decryptString(Buffer.from(raw.passwordEnc, 'base64'));
            delete raw.passwordEnc;
        }
        if (raw.passphraseEnc) {
            raw.passphrase = safeStorage.decryptString(Buffer.from(raw.passphraseEnc, 'base64'));
            delete raw.passphraseEnc;
        }
        return raw;
    } catch (e) {
        return null;
    }
}

function save(cfg) {
    const out = { ...cfg };
    if (cfg.password) {
        out.passwordEnc = safeStorage.encryptString(cfg.password).toString('base64');
        delete out.password;
    }
    if (cfg.passphrase) {
        out.passphraseEnc = safeStorage.encryptString(cfg.passphrase).toString('base64');
        delete out.passphrase;
    }
    fs.writeFileSync(FILE(), JSON.stringify(out, null, 2), { mode: 0o600 });
}

module.exports = { load, save };
