package dev.sshbrowser;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.Session;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Minimal SOCKS5 server: no-auth, CONNECT only. Each accepted connection is
 * piped through an SSH direct-tcpip channel — the in-app equivalent of `ssh -D`.
 * Domain names are forwarded as-is and resolved on the SSH server side.
 */
public final class Socks5Server implements Runnable {

    private static final int CONNECT_TIMEOUT_MS = 15000;

    private final int port;
    private final Supplier<Session> sessionSupplier;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private volatile boolean running;
    private ServerSocket serverSocket;

    public Socks5Server(int port, Supplier<Session> sessionSupplier) {
        this.port = port;
        this.sessionSupplier = sessionSupplier;
    }

    public void start() {
        running = true;
        Thread t = new Thread(this, "socks5-server");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
    }

    @Override
    public void run() {
        try (ServerSocket ss = new ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"))) {
            serverSocket = ss;
            Bus.log("SOCKS5 listening on 127.0.0.1:" + port);
            while (running) {
                Socket client = ss.accept();
                pool.execute(() -> handle(client));
            }
        } catch (IOException e) {
            if (running) Bus.log("SOCKS5 server error: " + e.getMessage());
        }
    }

    private void handle(Socket client) {
        ChannelDirectTCPIP channel = null;
        String target = null;
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // --- greeting: VER=5, NMETHODS, METHODS ---
            int ver = in.read();
            if (ver != 5) {
                Bus.log("收到非 SOCKS5 握手（首字节 0x" + Integer.toHexString(ver)
                        + "），关闭。若大量出现说明代理被当成了 HTTP 代理");
                return;
            }
            int nmethods = in.read();
            if (nmethods <= 0) return;
            skipFully(in, nmethods);
            out.write(new byte[]{5, 0}); // no-auth accepted
            out.flush();

            // --- request: VER CMD RSV ATYP DST.ADDR DST.PORT ---
            if (in.read() != 5) return;
            int cmd = in.read();
            if (in.read() != 0) return; // RSV
            if (cmd != 1) { // CONNECT only
                Bus.log("不支持的 SOCKS 命令: " + cmd);
                sendReply(out, 7); // command not supported
                return;
            }
            int atyp = in.read();
            String host;
            switch (atyp) {
                case 1: { // IPv4
                    host = InetAddress.getByAddress(readFully(in, 4)).getHostAddress();
                    break;
                }
                case 3: { // domain
                    int len = in.read();
                    if (len <= 0) return;
                    host = new String(readFully(in, len), StandardCharsets.UTF_8);
                    break;
                }
                case 4: { // IPv6
                    host = InetAddress.getByAddress(readFully(in, 16)).getHostAddress();
                    break;
                }
                default:
                    sendReply(out, 8); // address type not supported
                    return;
            }
            int dstPort = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
            target = host + ":" + dstPort;
            Bus.log("→ " + target);

            Session session = sessionSupplier.get();
            if (session == null || !session.isConnected()) {
                Bus.log("✗ " + target + " 隧道未连接");
                sendReply(out, 5); // connection refused (tunnel down)
                return;
            }

            channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
            channel.setHost(host);
            channel.setPort(dstPort);
            channel.setInputStream(in);
            channel.setOutputStream(out);
            channel.setOrgIPAddress("127.0.0.1");
            channel.setOrgPort(client.getPort());
            // mwiede-jsch with streams set: connect() is ASYNC — it spawns the pump
            // thread and returns before the channel open is confirmed. Must poll.
            channel.connect(CONNECT_TIMEOUT_MS);
            long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
            while (!channel.isConnected()) {
                if (channel.isClosed()) {
                    throw new IOException("channel open rejected by server");
                }
                if (System.currentTimeMillis() > deadline) {
                    channel.disconnect();
                    throw new IOException("channel open timed out");
                }
                Thread.sleep(50);
            }
            sendReply(out, 0); // success; JSch now pumps both directions
            Bus.log("✓ " + target);

            // Keep this thread (and the client socket) alive until the channel ends.
            while (channel.isConnected()) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Bus.log("✗ " + (target != null ? target + " " : "") + e.getMessage());
        } finally {
            if (channel != null) channel.disconnect();
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Reply with an IPv4 0.0.0.0:0 bind address; enough for CONNECT responses. */
    private static void sendReply(OutputStream out, int rep) throws IOException {
        out.write(new byte[]{5, (byte) rep, 0, 1, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
        return buf;
    }

    private static void skipFully(InputStream in, int len) throws IOException {
        readFully(in, len);
    }
}
