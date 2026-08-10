package dev.sshbrowser;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.util.concurrent.Executor;

/**
 * Fullscreen web page. The only control is the floating button in the
 * bottom-right corner: tap = back to Index. All traffic goes through the
 * local SOCKS5 proxy backed by the SSH tunnel.
 */
public final class BrowserActivity extends Activity {

    public static final String EXTRA_URL = "url";
    private static final String ERROR_PAGE = "file:///android_asset/error.html";

    private WebView webView;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enterFullscreen();
        setContentView(R.layout.activity_browser);

        progress = findViewById(R.id.progress);
        webView = findViewById(R.id.webview);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadUrl(ERROR_PAGE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && !url.startsWith("file://")) {
                    String title = view.getTitle();
                    RecentStore.add(BrowserActivity.this,
                            title == null || title.isEmpty() ? url : title, url);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        applySocksProxy();

        setupFab();

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (savedInstanceState == null && url != null) {
            webView.loadUrl(url);
        }
    }

    // --- draggable FAB: tap = back to Index, drag = reposition (persisted) ---

    private void setupFab() {
        View fab = findViewById(R.id.fab);
        android.content.SharedPreferences sp = getSharedPreferences("ui", MODE_PRIVATE);

        // restore saved position (fractions of the parent size)
        if (sp.contains("fab_x")) {
            fab.post(() -> {
                View parent = (View) fab.getParent();
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) fab.getLayoutParams();
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                lp.leftMargin = clamp((int) (sp.getFloat("fab_x", 0) * parent.getWidth()),
                        0, parent.getWidth() - fab.getWidth());
                lp.topMargin = clamp((int) (sp.getFloat("fab_y", 0) * parent.getHeight()),
                        0, parent.getHeight() - fab.getHeight());
                lp.rightMargin = 0;
                lp.bottomMargin = 0;
                fab.setLayoutParams(lp);
            });
        }

        final int slop = (int) (12 * getResources().getDisplayMetrics().density);
        fab.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY, startX, startY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                View parent = (View) v.getParent();
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = v.getX();
                        startY = v.getY();
                        dragging = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downRawX;
                        float dy = e.getRawY() - downRawY;
                        if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            dragging = true;
                            // switch to TOP|START so x/y map directly to margins
                            android.widget.FrameLayout.LayoutParams lp =
                                    (android.widget.FrameLayout.LayoutParams) v.getLayoutParams();
                            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                            lp.leftMargin = (int) startX;
                            lp.topMargin = (int) startY;
                            lp.rightMargin = 0;
                            lp.bottomMargin = 0;
                            v.setLayoutParams(lp);
                        }
                        if (dragging) {
                            android.widget.FrameLayout.LayoutParams lp =
                                    (android.widget.FrameLayout.LayoutParams) v.getLayoutParams();
                            lp.leftMargin = clamp((int) (startX + dx), 0, parent.getWidth() - v.getWidth());
                            lp.topMargin = clamp((int) (startY + dy), 0, parent.getHeight() - v.getHeight());
                            v.setLayoutParams(lp);
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (dragging) {
                            sp.edit()
                                    .putFloat("fab_x", v.getX() / parent.getWidth())
                                    .putFloat("fab_y", v.getY() / parent.getHeight())
                                    .apply();
                        } else {
                            finish(); // tap = back to Index
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void enterFullscreen() {
        // No LAYOUT_* flags: the window must stay resizable so the soft
        // keyboard can shrink the WebView (adjustResize) instead of covering it.
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    private void applySocksProxy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Bus.log("当前 WebView 不支持代理设置（PROXY_OVERRIDE），请更新 Android System WebView");
            return;
        }
        ProxyConfig config = new ProxyConfig.Builder()
                .addProxyRule("socks5://127.0.0.1:" + SshTunnelService.SOCKS_PORT)
                .build();
        Executor executor = Runnable::run;
        ProxyController.getInstance().setProxyOverride(config, executor,
                () -> Bus.log("WebView 代理已生效: socks5://127.0.0.1:" + SshTunnelService.SOCKS_PORT));
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
