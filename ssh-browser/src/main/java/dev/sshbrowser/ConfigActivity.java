package dev.sshbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Settings: a list of SSH server profiles (tap = enable, long-press = edit).
 * Auth is configured per profile in the edit dialog: password, OR private key
 * (import/generate/copy-pubkey are small utilities in that dialog).
 */
public final class ConfigActivity extends Activity {

    private static final int REQ_PICK_KEY = 42;
    private static final int MAX_KEY_BYTES = 256 * 1024;

    private ListView lvProfiles;
    private List<Profiles.Profile> profiles;

    // State of the profile edit dialog currently open (null = no dialog).
    private Profiles.Profile editing;
    private TextView dlgKeyStatus;
    private EditText dlgKeyPass;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        lvProfiles = findViewById(R.id.lv_profiles);
        findViewById(R.id.btn_add_profile).setOnClickListener(v -> showProfileDialog(null));
        findViewById(R.id.btn_view_log).setOnClickListener(v ->
                startActivity(new Intent(this, LogActivity.class)));

        lvProfiles.setOnItemClickListener((parent, view, position, id) -> {
            Profiles.setEnabled(this, profiles.get(position).id);
            refreshProfiles();
            toast("已启用「" + profiles.get(position).name + "」");
        });
        lvProfiles.setOnItemLongClickListener((parent, view, position, id) -> {
            showProfileDialog(profiles.get(position));
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProfiles();
    }

    // --- profile list ---

    private void refreshProfiles() {
        profiles = Profiles.list(this);
        Profiles.Profile enabled = Profiles.enabled(this);
        String enabledId = enabled == null ? "" : enabled.id;
        ArrayAdapter<Profiles.Profile> adapter = new ArrayAdapter<Profiles.Profile>(
                this, R.layout.item_profile, profiles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.item_profile, parent, false);
                Profiles.Profile p = getItem(position);
                ((TextView) view.findViewById(R.id.tv_radio))
                        .setText(p.id.equals(enabledId) ? "◉" : "○");
                ((TextView) view.findViewById(R.id.tv_name)).setText(p.name);
                ((TextView) view.findViewById(R.id.tv_summary))
                        .setText(p.summary() + (p.hasKey() ? " · 密钥" : ""));
                return view;
            }
        };
        lvProfiles.setAdapter(adapter);
    }

    // --- profile edit dialog (auth = password OR private key) ---

    private void showProfileDialog(Profiles.Profile existing) {
        // Work on a copy so 取消 discards changes.
        editing = new Profiles.Profile();
        if (existing != null) {
            editing.id = existing.id;
            editing.name = existing.name;
            editing.host = existing.host;
            editing.port = existing.port;
            editing.user = existing.user;
            editing.password = existing.password;
            editing.privKey = existing.privKey;
            editing.keyPass = existing.keyPass;
            editing.pubKey = existing.pubKey;
        }

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        fields.setPadding(pad, pad / 2, pad, 0);

        EditText etName = field(fields, "名称（如：公司服务器）", editing.name);
        EditText etHost = field(fields, "主机", editing.host);
        EditText etPort = field(fields, "端口（默认 22）",
                existing == null ? "" : String.valueOf(editing.port));
        etPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText etUser = field(fields, "用户名", editing.user);
        EditText etPass = field(fields, "密码（用密码认证就填这里）", editing.password);
        etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        TextView divider = new TextView(this);
        divider.setText("—— 或用私钥认证（二选一，私钥优先）——");
        divider.setTextSize(12);
        divider.setPadding(0, pad / 2, 0, pad / 4);
        fields.addView(divider);

        dlgKeyStatus = new TextView(this);
        dlgKeyStatus.setTextSize(12);
        fields.addView(dlgKeyStatus);

        LinearLayout keyButtons = new LinearLayout(this);
        keyButtons.setOrientation(LinearLayout.HORIZONTAL);
        addSmallButton(keyButtons, "导入", v -> openKeyPicker());
        addSmallButton(keyButtons, "生成", v -> generateKeyInto());
        addSmallButton(keyButtons, "复制公钥", v -> copyPubkey());
        fields.addView(keyButtons);

        dlgKeyPass = field(fields, "私钥口令（私钥无密码则留空）", editing.keyPass);
        dlgKeyPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        updateKeyStatus();

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(fields);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "添加服务器" : "编辑服务器")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> saveEditing(
                        etName, etHost, etPort, etUser, etPass))
                .setNegativeButton("取消", (d, w) -> editing = null);
        if (existing != null) {
            builder.setNeutralButton("删除", (d, w) -> {
                Profiles.delete(this, existing.id);
                editing = null;
                refreshProfiles();
            });
        }
        builder.setOnDismissListener(d -> editing = null);
        builder.show();
    }

    private void saveEditing(EditText etName, EditText etHost, EditText etPort,
                             EditText etUser, EditText etPass) {
        Profiles.Profile p = editing;
        p.name = etName.getText().toString().trim();
        p.host = etHost.getText().toString().trim();
        try {
            p.port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            p.port = 22;
        }
        p.user = etUser.getText().toString().trim();
        p.password = etPass.getText().toString();
        p.keyPass = dlgKeyPass.getText().toString();
        if (p.name.isEmpty()) p.name = p.host;
        if (p.host.isEmpty() || p.user.isEmpty()) {
            toast("主机和用户名必填");
            return;
        }
        Profiles.save(this, p);
        editing = null;
        refreshProfiles();
    }

    private void updateKeyStatus() {
        if (dlgKeyStatus != null) {
            dlgKeyStatus.setText(editing != null && editing.hasKey()
                    ? "私钥：已设置 ✓（密码框留空即可）" : "私钥：未设置");
        }
    }

    private EditText field(LinearLayout parent, String hint, String text) {
        EditText et = new EditText(this);
        et.setHint(hint);
        if (text != null) et.setText(text);
        parent.addView(et);
        return et;
    }

    private void addSmallButton(LinearLayout parent, String label, View.OnClickListener action) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(label);
        b.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        b.setLayoutParams(lp);
        b.setOnClickListener(action);
        parent.addView(b);
    }

    // --- key utilities (operate on the profile being edited) ---

    private void generateKeyInto() {
        toast("正在生成 RSA-4096 密钥，约需几秒…");
        new Thread(() -> {
            try {
                KeyManager.Pair pair = KeyManager.generate();
                runOnUiThread(() -> {
                    if (editing == null) return;
                    editing.privKey = pair.privPem;
                    editing.pubKey = pair.pubLine;
                    editing.keyPass = "";
                    if (dlgKeyPass != null) dlgKeyPass.setText("");
                    updateKeyStatus();
                    toast("密钥已生成，记得复制公钥装到服务器");
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("生成失败：" + e.getMessage()));
            }
        }, "keygen").start();
    }

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
            importKey(readText(uri));
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
        String passphrase = dlgKeyPass == null ? "" : dlgKeyPass.getText().toString();
        new Thread(() -> {
            try {
                JSch jsch = new JSch();
                KeyPair kp = KeyPair.load(jsch, pem.getBytes(StandardCharsets.US_ASCII), null);
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
                kp.writePublicKey(pub, "imported@sshurf");
                String publicLine = new String(pub.toByteArray(), StandardCharsets.US_ASCII).trim();
                kp.dispose();
                runOnUiThread(() -> {
                    if (editing == null) return;
                    editing.privKey = pem;
                    editing.pubKey = publicLine;
                    editing.keyPass = passphrase;
                    updateKeyStatus();
                    toast("私钥已导入（保存配置后生效）");
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("导入失败：" + e.getMessage()));
            }
        }, "key-import").start();
    }

    private void copyPubkey() {
        if (editing == null || editing.pubKey.isEmpty()) {
            toast("还没有公钥（先导入或生成）");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("ssh public key", editing.pubKey));
        toast("公钥已复制，请加入服务器 ~/.ssh/authorized_keys");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
