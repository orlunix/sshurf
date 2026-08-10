'use strict';

/**
 * SSH tunnel manager: owns the ssh2 client (auto-reconnect) and the local
 * SOCKS5 server that the browser window points at.
 */

const { EventEmitter } = require('events');
const fs = require('fs');
const { Client } = require('ssh2');
const { Socks5Server } = require('./socks5');

const RECONNECT_DELAY_MS = 5000;
const SOCKS_PORT = 10808;

class Tunnel extends EventEmitter {
    constructor() {
        super();
        this.enabled = false;
        this.ssh = null;
        this.cfg = null;
        this.socks = new Socks5Server(SOCKS_PORT, () => this.ssh, (m) => this._log(m));
    }

    /** @param cfg {host, port, user, auth:'key'|'password', keyPath, passphrase, password} */
    start(cfg) {
        this.cfg = cfg;
        if (this.enabled) return;
        this.enabled = true;
        this.socks.start();
        this._loop();
    }

    stop() {
        this.enabled = false;
        this.socks.stop();
        if (this.ssh) {
            const s = this.ssh;
            this.ssh = null;
            s.end();
        }
        this._status('disconnected');
    }

    async _loop() {
        while (this.enabled) {
            try {
                this._status('connecting', '正在连接 SSH…');
                await this._connectAndWait(); // returns when an established connection drops
            } catch (e) {
                if (this.enabled) this._log(`连接失败：${e.message}`);
            }
            if (this.enabled) {
                this._status('connecting', `${RECONNECT_DELAY_MS / 1000}s 后重连…`);
                await new Promise((r) => setTimeout(r, RECONNECT_DELAY_MS));
            }
        }
        this._status('disconnected');
    }

    _connectAndWait() {
        return new Promise((resolve, reject) => {
            const conn = new Client();
            let ready = false;

            const opts = {
                host: this.cfg.host,
                port: this.cfg.port || 22,
                username: this.cfg.user,
                readyTimeout: 15000,
                keepaliveInterval: 30000,
                keepaliveCountMax: 3,
                // TODO: TOFU host key verification (same as Android M2 plan)
                hostVerifier: () => true,
            };
            if (this.cfg.auth === 'key' && this.cfg.keyPath) {
                opts.privateKey = fs.readFileSync(this.cfg.keyPath);
                if (this.cfg.passphrase) opts.passphrase = this.cfg.passphrase;
            } else {
                opts.password = this.cfg.password;
            }

            conn.on('ready', () => {
                ready = true;
                this.ssh = conn;
                this._status('connected', 'SSH 已连接');
            });
            conn.on('error', (e) => {
                this._log(`SSH 错误: ${e.message}`);
                if (!ready) reject(e);
                // post-ready errors are followed by 'close', which resolves
            });
            conn.on('close', () => {
                if (this.ssh === conn) this.ssh = null;
                if (ready) {
                    if (this.enabled) this._log('SSH 连接断开');
                    resolve();
                } else {
                    reject(new Error('连接被关闭'));
                }
            });
            conn.connect(opts);
        });
    }

    _status(state, detail) {
        this.emit('status', state, detail);
        if (detail) this._log(detail);
    }

    _log(msg) {
        this.emit('log', msg);
    }
}

module.exports = { Tunnel, SOCKS_PORT };
