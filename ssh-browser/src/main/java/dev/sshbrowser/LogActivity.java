package dev.sshbrowser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Full-screen log view: colored by outcome (✓ green, ✗ red), replays the
 * bounded history, supports copy-all (for bug reports) and clear.
 */
public final class LogActivity extends Activity implements Bus.Listener {

    private TextView tvLog;
    private final StringBuilder plain = new StringBuilder();
    private final SpannableStringBuilder colored = new SpannableStringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        tvLog = findViewById(R.id.tv_log);

        findViewById(R.id.btn_copy).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("sshurf log", plain.toString()));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            Bus.clear();
            plain.setLength(0);
            colored.clear();
            tvLog.setText("");
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        plain.setLength(0);
        colored.clear();
        tvLog.setText("");
        Bus.setListener(this); // replays history into onLog
    }

    @Override
    protected void onPause() {
        Bus.setListener(null);
        super.onPause();
    }

    @Override
    public void onState(Bus.State state, String detail) {
        // Log page shows lines only; state is visible on Index.
    }

    @Override
    public void onLog(String line) {
        plain.append(line).append('\n');
        int start = colored.length();
        colored.append(line).append("\n");
        int color = line.contains("✗")
                ? Color.parseColor("#E53935")
                : line.contains("✓") ? Color.parseColor("#43A047") : Color.parseColor("#616161");
        colored.setSpan(new ForegroundColorSpan(color), start, colored.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvLog.setText(colored);
    }
}
