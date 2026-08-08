package dev.sshbrowser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity implements Bus.Listener {

    private static final int REQ_PICK_KEY = 42;
    private static final int MAX_KEY_BYTES = 256 * 1024;

    private EditText etHost, etPort, etUser, etPassword, etKeyPassphrase;
    private TextView tvStatus, tvLog, tvPubkey;
    private Button btnConnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etHost = findViewById(R.id.et_host);
        etPort = findViewById(R.id.et_port);
        etUser = findViewById(R.id.et_user);
        etPassword = findViewById(R.id.et_password);
        etKeyPassphrase = findViewById(R.id.et_key_passphrase);
        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);
        tvPubkey = findViewById(R.id.tv_pubkey);
        btnConnect = findViewById(R.id.btn_connect);

        SshConfig cfg = SshConfig.load(this);
        etHost.setText(cfg.host);
        etPort.setText(String.valueOf(cfg.port));
        etUser.setText(cfg.user);
        etPassword.setText(cfg.password);
        etKeyPassphrase.setText(cfg.keyPassphrase);
        refreshPubkey(cfg.publicKey);

        findViewById(R.id.btn_save).setOnClickListener(v -> saveConfig());
        findViewById(R.id.btn_genkey).setOnClickListener(v -> generateKey());
        findViewById(R.id.btn_import_key).setOnClickListener(v -> openKeyPicker());
        findViewById(R.id.btn_copy_key).setOnClickListener(v -> copyPubkey());
        btnConnect.setOnClickListener(v -> toggleTunnel());
        findViewById(R.id.btn_open_browser).setOnClickListener(v ->
                startActivity(new Intent(this, BrowserActivity.class)));

        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tvLog.setText(""); // history is replayed in full on attach
        Bus.setListener(this);
    }

    @Override
    protected void onPause() {
        Bus.setListener(null);
        super.onPause();
    }

    private void saveConfig() {
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            toast("端口无效");
            return;
        }
        SshConfig.saveConnection(this,
                etHost.getText().toString().trim(), port,
                etUser.getText().toString().trim(),
                etPassword.getText().toString());
        SshConfig.saveKeyPassphrase(this, etKeyPassphrase.getText().toString());
        toast("已保存");
    }

    private void generateKey() {
        toast("正在生成 RSA-4096 密钥，约需几秒…");
        new Thread(() -> {
            try {
                String pub = KeyManager.generate(this);
                runOnUiThread(() -> {
                    etKeyPassphrase.setText("");
                    refreshPubkey(pub);
                    toast("密钥已生成，公钥见下方");
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("生成失败：" + e.getMessage()));
            }
        }, "keygen").start();
    }

    // --- private key import via system file picker ---

    private void openKeyPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        startActivityForResult(Intent.createChooser(i, "选择私钥文件"), REQ_PICK_KEY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_KEY || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            String pem = readText(uri);
            importKey(pem);
        } catch (Exception e) {
            toast("读取文件失败：" + e.getMessage());
        }
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("无法打开文件");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_KEY_BYTES) throw new IllegalStateException("文件过大，不是私钥");
            }
            return new String(buf.toByteArray(), StandardCharsets.US_ASCII);
        }
    }

    private void importKey(String pem) {
        if (pem.contains("PuTTY-User-Key-File")) {
            toast("不支持 PuTTY .ppk 格式，请先转成 OpenSSH/PEM");
            return;
        }
        if (!pem.contains("-----BEGIN") || !pem.contains("PRIVATE KEY-----")) {
            toast("不是有效的 PEM/OpenSSH 私钥");
            return;
        }
        String passphrase = etKeyPassphrase.getText().toString();
        new Thread(() -> {
            try {
                JSch jsch = new JSch();
                KeyPair kp = KeyPair.load(jsch,
                        pem.getBytes(StandardCharsets.US_ASCII), null);
                if (kp.isEncrypted()) {
                    if (passphrase.isEmpty()) {
                        runOnUiThread(() -> toast("私钥有口令保护，请先填「私钥口令」再导入"));
                        return;
                    }
                    if (!kp.decrypt(passphrase)) {
                        runOnUiThread(() -> toast("私钥口令错误"));
                        return;
                    }
                }
                ByteArrayOutputStream pub = new ByteArrayOutputStream();
                kp.writePublicKey(pub, "imported@ssh-browser");
                String publicLine = new String(pub.toByteArray(), StandardCharsets.US_ASCII).trim();
                kp.dispose();
                SshConfig.saveKeyPair(this, pem, publicLine, passphrase);
                runOnUiThread(() -> {
                    refreshPubkey(publicLine);
                    toast("私钥已导入并加密存储");
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("导入失败：" + e.getMessage()));
            }
        }, "key-import").start();
    }

    private void refreshPubkey(String pub) {
        tvPubkey.setText(pub == null || pub.isEmpty()
                ? "（无密钥；可生成、导入，或用密码登录）" : pub);
    }

    private void copyPubkey() {
        SshConfig cfg = SshConfig.load(this);
        if (cfg.publicKey.isEmpty()) {
            toast("还没有公钥");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("ssh public key", cfg.publicKey));
        toast("公钥已复制，请加入服务器 ~/.ssh/authorized_keys");
    }

    private void toggleTunnel() {
        if (Bus.getState() == Bus.State.DISCONNECTED) {
            saveConfig();
            Intent i = new Intent(this, SshTunnelService.class).setAction(SshTunnelService.ACTION_START);
            ContextCompat.startForegroundService(this, i);
        } else {
            startService(new Intent(this, SshTunnelService.class).setAction(SshTunnelService.ACTION_STOP));
        }
    }

    @Override
    public void onState(Bus.State state, String detail) {
        switch (state) {
            case CONNECTED:
                tvStatus.setText("状态：已连接（代理 127.0.0.1:" + SshTunnelService.SOCKS_PORT + "）");
                btnConnect.setText("断开隧道");
                break;
            case CONNECTING:
                tvStatus.setText("状态：连接中 / 重连中");
                btnConnect.setText("断开隧道");
                break;
            default:
                tvStatus.setText("状态：未连接");
                btnConnect.setText("连接隧道");
        }
    }

    @Override
    public void onLog(String line) {
        tvLog.append(line + "\n");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
