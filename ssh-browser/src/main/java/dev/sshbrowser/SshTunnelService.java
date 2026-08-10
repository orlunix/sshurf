package dev.sshbrowser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.nio.charset.StandardCharsets;

/**
 * Foreground service owning the SSH session (with auto-reconnect) and the
 * local SOCKS5 server that WebView traffic is pointed at.
 *
 * M1 known limitation: host key checking is off (StrictHostKeyChecking=no),
 * fingerprint is logged on connect. TOFU verification is planned for M2.
 */
public final class SshTunnelService extends Service {

    public static final String ACTION_START = "dev.sshbrowser.START";
    public static final String ACTION_STOP = "dev.sshbrowser.STOP";
    public static final int SOCKS_PORT = 10808;

    private static final String CHANNEL_ID = "tunnel";
    private static final int NOTIFICATION_ID = 1;
    private static final int RECONNECT_DELAY_MS = 5000;

    private volatile boolean enabled;
    private volatile Session session;
    private Socks5Server socksServer;
    private Thread worker;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "SSH 隧道",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (enabled) return START_STICKY;
        enabled = true;
        startForeground(NOTIFICATION_ID, buildNotification("SSH 隧道运行中"));
        if (socksServer == null) {
            socksServer = new Socks5Server(SOCKS_PORT, () -> session);
            socksServer.start();
        }
        worker = new Thread(this::runLoop, "ssh-loop");
        worker.setDaemon(true);
        worker.start();
        return START_STICKY;
    }

    private void runLoop() {
        while (enabled) {
            try {
                Bus.postState(Bus.State.CONNECTING, "正在连接 SSH…");
                connectOnce();
                Bus.postState(Bus.State.CONNECTED, "SSH 已连接");
                while (enabled && session != null && session.isConnected()) {
                    Thread.sleep(2000);
                }
                if (enabled) Bus.log("SSH 连接断开");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (enabled) Bus.postState(Bus.State.CONNECTING, "连接失败：" + e.getMessage());
            } finally {
                disconnectSession();
            }
            if (enabled) {
                Bus.log(RECONNECT_DELAY_MS / 1000 + "s 后重连…");
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Bus.postState(Bus.State.DISCONNECTED, null);
    }

    private void connectOnce() throws Exception {
        Profiles.Profile p = Profiles.enabled(this);
        if (p == null || p.host.isEmpty() || p.user.isEmpty()) {
            throw new IllegalStateException("没有可用的 SSH 配置，请先在设置里添加");
        }
        if (!p.hasKey() && p.password.isEmpty()) {
            throw new IllegalStateException("「" + p.name + "」未设置认证方式：请导入/生成私钥或填密码");
        }

        JSch jsch = new JSch();
        if (p.hasKey()) {
            byte[] pass = p.keyPass.isEmpty() ? null : p.keyPass.getBytes(StandardCharsets.UTF_8);
            jsch.addIdentity("device-key", p.privKey.getBytes(StandardCharsets.US_ASCII), null, pass);
        }
        Session s = jsch.getSession(p.user, p.host, p.port);
        if (!p.password.isEmpty()) s.setPassword(p.password);
        // TODO(M2): TOFU host key verification; M1 accepts any and logs the fingerprint.
        s.setConfig("StrictHostKeyChecking", "no");
        s.setServerAliveInterval(30000);
        s.setServerAliveCountMax(3);
        s.connect(15000);

        try {
            String fp = s.getHostKey().getFingerPrint(jsch);
            Bus.log(p.name + " 指纹: " + fp);
        } catch (Exception ignored) {
        }
        session = s;
    }

    private void disconnectSession() {
        Session s = session;
        session = null;
        if (s != null) s.disconnect();
    }

    private void shutdown() {
        enabled = false;
        if (worker != null) worker.interrupt();
        disconnectSession();
        if (socksServer != null) {
            socksServer.stop();
            socksServer = null;
        }
        stopForeground(true);
        stopSelf();
        Bus.postState(Bus.State.DISCONNECTED, "已断开");
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SSH Browser")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
