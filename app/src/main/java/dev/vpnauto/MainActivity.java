package dev.vpnauto;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * M1.5 flow:
 *   1) "读取验证码" -> ask MediaProjection consent -> ScreenCaptureService
 *      opens Authenticator, grabs one frame, OCRs the 6-digit code.
 *   2) "连接 GlobalProtect" -> launches the GlobalProtect app.
 * Everything is logged to Logcat (tag VpnAuto) and the on-screen log view.
 */
public class MainActivity extends Activity {

    private static final String GP_PACKAGE = "com.paloaltonetworks.globalprotect";
    private static final int REQ_PROJECTION = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int MAX_LOG_LINES = 200;

    private TextView statusText;
    private TextView codeText;
    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        codeText = findViewById(R.id.codeText);
        logText = findViewById(R.id.logText);
        Button captureButton = findViewById(R.id.captureButton);
        Button connectButton = findViewById(R.id.connectButton);

        captureButton.setOnClickListener(v -> requestScreenCapture());
        connectButton.setOnClickListener(v -> launchGlobalProtect());

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }

        LogBus.log("M1.5 启动，GlobalProtect 已安装: " + isInstalled(GP_PACKAGE));
        handleCodes(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        LogBus.setListener(this::appendLog);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LogBus.setListener(null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleCodes(intent);
    }

    private void requestScreenCapture() {
        LogBus.log("请求屏幕录制授权 ...");
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) {
            return;
        }
        if (resultCode == RESULT_OK && data != null) {
            LogBus.log("屏幕录制授权通过，启动抓屏服务");
            Intent svc = new Intent(this, ScreenCaptureService.class);
            svc.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
            svc.putExtra(ScreenCaptureService.EXTRA_DATA, data);
            startForegroundService(svc);
            statusText.setText("状态：正在读取 Authenticator ...");
        } else {
            LogBus.log("用户取消了屏幕录制授权");
            statusText.setText("状态：已取消");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            LogBus.log("通知权限: " + granted);
        }
    }

    private void handleCodes(Intent intent) {
        ArrayList<String> codes =
                intent.getStringArrayListExtra(ScreenCaptureService.EXTRA_CODES);
        if (codes == null) {
            return;
        }
        if (codes.isEmpty()) {
            codeText.setText("------");
            statusText.setText("状态：未识别到验证码");
            LogBus.log("未识别到验证码。若画面为黑屏，说明 Authenticator 禁止截屏"
                    + "（FLAG_SECURE），此路线不可行，需改手动输入");
            return;
        }
        String first = codes.get(0);
        codeText.setText(first);
        statusText.setText("状态：已捕获验证码");
        LogBus.log("采用验证码: " + first
                + (codes.size() > 1 ? "（另有候选: " + codes.subList(1, codes.size()) + "）" : ""));
        // Also put it on the clipboard for easy pasting into the login page.
        ClipboardManager cm =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("totp", first));
        LogBus.log("验证码已写入剪贴板，便于粘贴");
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void launchGlobalProtect() {
        LogBus.log("点击: 连接 GlobalProtect");
        Intent intent = getPackageManager().getLaunchIntentForPackage(GP_PACKAGE);
        if (intent == null) {
            LogBus.log("错误: 未找到 GlobalProtect，请确认已安装");
            statusText.setText("状态：GlobalProtect 未安装");
            return;
        }
        LogBus.log("拉起 GlobalProtect ...");
        startActivity(intent);
        statusText.setText("状态：已拉起 GlobalProtect，请手动点 Connect");
    }

    private void appendLog(String line) {
        String current = logText.getText().toString();
        String updated = current.isEmpty() ? line : current + "\n" + line;
        String[] lines = updated.split("\n");
        if (lines.length > MAX_LOG_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - MAX_LOG_LINES; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
            updated = sb.toString().trim();
        }
        logText.setText(updated);
    }
}
