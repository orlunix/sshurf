package dev.sshbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Home page. Top bar: hamburger menu (Settings / Logs / Recents), app name,
 * tunnel status (tap = toggle). Below: one-off address/search input, then the
 * bookmark cards ("web apps"). Tapping a card auto-connects the tunnel if
 * needed, then opens the page fullscreen.
 */
public final class IndexActivity extends Activity implements Bus.Listener {

    public static final String EXTRA_OPEN_URL = "open_url";
    private static final int CONNECT_TIMEOUT_MS = 25000;

    private TextView tvDot, tvState;
    private ListView lvBookmarks;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private List<BookmarkStore.Bookmark> bookmarks;
    private String pendingUrl;
    private final Runnable pendingTimeout = () -> {
        if (pendingUrl != null) {
            pendingUrl = null;
            toast("连接超时，请检查网络和配置");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_index);

        tvDot = findViewById(R.id.tv_dot);
        tvState = findViewById(R.id.tv_state);
        lvBookmarks = findViewById(R.id.lv_bookmarks);

        findViewById(R.id.btn_menu).setOnClickListener(this::showMainMenu);
        findViewById(R.id.status_bar).setOnClickListener(v -> toggleTunnel());
        findViewById(R.id.btn_add).setOnClickListener(v -> showBookmarkDialog(-1));

        EditText etUrl = findViewById(R.id.et_url);
        etUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openUrl(v.getText().toString().trim());
                v.setText("");
                return true;
            }
            return false;
        });

        // First run without any profile: land on Config for guided setup.
        if (Profiles.list(this).isEmpty()) {
            startActivity(new Intent(this, ConfigActivity.class));
        }

        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        handleOpenExtra(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleOpenExtra(intent);
    }

    private void handleOpenExtra(Intent intent) {
        String url = intent == null ? null : intent.getStringExtra(EXTRA_OPEN_URL);
        if (url != null) {
            intent.removeExtra(EXTRA_OPEN_URL);
            openUrl(url);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Bus.setListener(this);
        refreshStatus();
        refreshBookmarks();
    }

    @Override
    protected void onPause() {
        Bus.setListener(null);
        super.onPause();
    }

    // --- hamburger menu ---

    private void showMainMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "最近访问");
        menu.getMenu().add(0, 2, 1, "设置");
        menu.getMenu().add(0, 3, 2, "日志");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    startActivity(new Intent(this, RecentActivity.class));
                    return true;
                case 2:
                    startActivity(new Intent(this, ConfigActivity.class));
                    return true;
                case 3:
                    startActivity(new Intent(this, LogActivity.class));
                    return true;
            }
            return false;
        });
        menu.show();
    }

    // --- tunnel ---

    private void toggleTunnel() {
        if (Bus.getState() == Bus.State.DISCONNECTED) {
            startService(new Intent(this, SshTunnelService.class).setAction(SshTunnelService.ACTION_START));
        } else {
            startService(new Intent(this, SshTunnelService.class).setAction(SshTunnelService.ACTION_STOP));
        }
    }

    private void openUrl(String input) {
        if (input.isEmpty()) return;
        String url = toUrl(input);
        if (Bus.getState() == Bus.State.CONNECTED) {
            browse(url);
        } else {
            pendingUrl = url;
            handler.removeCallbacks(pendingTimeout);
            handler.postDelayed(pendingTimeout, CONNECT_TIMEOUT_MS);
            if (Bus.getState() == Bus.State.DISCONNECTED) toggleTunnel();
            toast("正在连接隧道…");
        }
    }

    private void browse(String url) {
        startActivity(new Intent(this, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_URL, url));
    }

    private static String toUrl(String input) {
        if (input.contains("://")) return input;
        if (input.contains(".") && !input.contains(" ")) return "https://" + input;
        try {
            return "https://www.baidu.com/s?wd=" + java.net.URLEncoder.encode(input, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "https://www.baidu.com/s?wd=" + input;
        }
    }

    // --- bookmark cards ---

    private void refreshBookmarks() {
        bookmarks = BookmarkStore.list(this);
        ArrayAdapter<BookmarkStore.Bookmark> adapter = new ArrayAdapter<BookmarkStore.Bookmark>(
                this, R.layout.item_bookmark, bookmarks) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.item_bookmark, parent, false);
                BookmarkStore.Bookmark b = getItem(position);
                TextView initial = view.findViewById(R.id.tv_initial);
                initial.setText(b.name.substring(0, 1));
                initial.setBackground(circle(colorFor(b.name)));
                ((TextView) view.findViewById(R.id.tv_name)).setText(b.name);
                ((TextView) view.findViewById(R.id.tv_url)).setText(b.url);
                view.findViewById(R.id.tv_more)
                        .setOnClickListener(v -> showBookmarkMenu(v, position));
                return view;
            }
        };
        lvBookmarks.setAdapter(adapter);
        lvBookmarks.setOnItemClickListener((parent, view, position, id) ->
                openUrl(bookmarks.get(position).url));
    }

    private void showBookmarkMenu(View anchor, int position) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "打开");
        menu.getMenu().add(0, 2, 1, "编辑");
        menu.getMenu().add(0, 3, 2, "删除");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    openUrl(bookmarks.get(position).url);
                    return true;
                case 2:
                    showBookmarkDialog(position);
                    return true;
                case 3:
                    confirmDeleteBookmark(position);
                    return true;
            }
            return false;
        });
        menu.show();
    }

    /** @param position bookmark index to edit, or -1 to add a new one */
    private void showBookmarkDialog(int position) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        fields.setPadding(pad, pad / 2, pad, 0);
        EditText etName = new EditText(this);
        etName.setHint("名称（如：Wiki）");
        EditText etUrl = new EditText(this);
        etUrl.setHint("网址（如：wiki.internal 或 https://…）");
        fields.addView(etName);
        fields.addView(etUrl);
        if (position >= 0) {
            etName.setText(bookmarks.get(position).name);
            etUrl.setText(bookmarks.get(position).url);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(fields);
        new AlertDialog.Builder(this)
                .setTitle(position >= 0 ? "编辑网络应用" : "添加网络应用")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String url = etUrl.getText().toString().trim();
                    if (name.isEmpty() || url.isEmpty()) {
                        toast("名称和网址都要填");
                        return;
                    }
                    if (position >= 0) {
                        BookmarkStore.updateAt(this, position, name, toUrl(url));
                    } else {
                        BookmarkStore.add(this, name, toUrl(url));
                    }
                    refreshBookmarks();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteBookmark(int position) {
        new AlertDialog.Builder(this)
                .setMessage("删除「" + bookmarks.get(position).name + "」？")
                .setPositiveButton("删除", (d, w) -> {
                    BookmarkStore.removeAt(this, position);
                    refreshBookmarks();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static int colorFor(String name) {
        int[] palette = {0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
                0xFF42A5F5, 0xFFFFA726, 0xFF8D6E63, 0xFF66BB6A};
        return palette[Math.abs(name.hashCode()) % palette.length];
    }

    // --- status ---

    private void refreshStatus() {
        onState(Bus.getState(), null);
    }

    @Override
    public void onState(Bus.State state, String detail) {
        switch (state) {
            case CONNECTED:
                tvDot.setTextColor(0xFF4CAF50);
                tvState.setText("已连接");
                if (pendingUrl != null) {
                    String url = pendingUrl;
                    pendingUrl = null;
                    handler.removeCallbacks(pendingTimeout);
                    browse(url);
                }
                break;
            case CONNECTING:
                tvDot.setTextColor(0xFFFFC107);
                tvState.setText("连接中…");
                break;
            default:
                tvDot.setTextColor(0xFF9E9E9E);
                tvState.setText("未连接");
        }
    }

    @Override
    public void onLog(String line) {
        // Index page doesn't show logs; LogActivity does.
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
