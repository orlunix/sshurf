'use strict';

/**
 * Minimal SOCKS5 server (no-auth, CONNECT only). Each accepted connection is
 * relayed through ssh2's forwardOut (direct-tcpip channel) — the in-process
 * equivalent of `ssh -D`. Domain names are forwarded as-is and resolved on
 * the SSH server side. Same logic as the Android app's Socks5Server.
 */

const net = require('net');

class Socks5Server {
    /**
     * @param {number} port local listen port (bound to 127.0.0.1)
     * @param {() => import('ssh2').Client | null} getSsh connected ssh2 client supplier
     * @param {(msg: string) => void} log
     */
    constructor(port, getSsh, log) {
        this.port = port;
        this.getSsh = getSsh;
        this.log = log;
        this.server = null;
    }

    start() {
        this.server = net.createServer((socket) => this._handle(socket));
        this.server.listen(this.port, '127.0.0.1');
        this.log(`SOCKS5 listening on 127.0.0.1:${this.port}`);
    }

    stop() {
        if (this.server) this.server.close();
        this.server = null;
    }

    _handle(socket) {
        socket.setNoDelay(true);
        let buf = Buffer.alloc(0);
        let stage = 'greeting';
        let relayed = false;

        socket.on('error', () => socket.destroy());

        socket.on('data', (chunk) => {
            buf = Buffer.concat([buf, chunk]);
            try {
                if (stage === 'greeting') {
                    if (buf.length < 2) return;
                    if (buf[0] !== 0x05) {
                        this.log('非 SOCKS5 握手，关闭');
                        socket.end();
                        return;
                    }
                    const nmethods = buf[1];
                    if (buf.length < 2 + nmethods) return;
                    buf = buf.slice(2 + nmethods);
                    socket.write(Buffer.from([0x05, 0x00])); // no-auth
                    stage = 'request';
                }
                if (stage === 'request') {
                    if (buf.length < 5) return;
                    if (buf[0] !== 0x05 || buf[1] !== 0x01) { // CONNECT only
                        socket.end();
                        return;
                    }
                    const atyp = buf[3];
                    let host, off;
                    if (atyp === 1) {           // IPv4
                        if (buf.length < 4 + 4 + 2) return;
                        host = Array.from(buf.slice(4, 8)).join('.');
                        off = 8;
                    } else if (atyp === 3) {    // domain
                        const len = buf[4];
                        if (buf.length < 5 + len + 2) return;
                        host = buf.slice(5, 5 + len).toString('utf8');
                        off = 5 + len;
                    } else if (atyp === 4) {    // IPv6
                        if (buf.length < 4 + 16 + 2) return;
                        const b = buf.slice(4, 20);
                        host = Array.from({ length: 8 }, (_, i) => b.readUInt16BE(i * 2).toString(16)).join(':');
                        off = 20;
                    } else {
                        socket.end();
                        return;
                    }
                    const port = buf.readUInt16BE(off);
                    this._relay(socket, host, port);
                    stage = 'relayed';
                    relayed = true;
                }
            } catch (e) {
                this.log(`SOCKS 处理异常: ${e.message}`);
                socket.destroy();
            }
        });

        socket.on('close', () => { /* channel closes via stream 'close' handler */ });
        void relayed;
    }

    _relay(socket, host, port) {
        const target = `${host}:${port}`;
        const ssh = this.getSsh();
        if (!ssh) {
            this.log(`✗ ${target} 隧道未连接`);
            socket.end();
            return;
        }
        this.log(`→ ${target}`);
        ssh.forwardOut('127.0.0.1', socket.remotePort, host, port, (err, stream) => {
            if (err) {
                this.log(`✗ ${target} ${err.message}`);
                socket.end();
                return;
            }
            // SOCKS success reply, then pipe both directions
            socket.write(Buffer.from([0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]));
            socket.pipe(stream);
            stream.pipe(socket);
            this.log(`✓ ${target}`);
            stream.on('close', () => socket.end());
            socket.on('close', () => stream.close());
            stream.on('error', () => socket.destroy());
        });
    }
}

module.exports = { Socks5Server };
