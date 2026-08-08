package dev.sshbrowser;

import android.app.Activity;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.util.concurrent.Executor;

/**
 * Minimal browser: a WebView whose traffic goes through the local SOCKS5
 * proxy backed by the SSH tunnel. The override is process-wide, which is
 * fine here — the whole app is this browser.
 */
public final class BrowserActivity extends Activity {

    private WebView webView;
    private EditText addressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        addressBar = findViewById(R.id.et_url);
        webView = findViewById(R.id.webview);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());

        applySocksProxy();

        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                load(addressBar.getText().toString().trim());
                return true;
            }
            return false;
        });
        findViewById(R.id.btn_go).setOnClickListener(v -> load(addressBar.getText().toString().trim()));

        if (Bus.getState() != Bus.State.CONNECTED) {
            toast("隧道未连接，网页将无法打开");
        }
        if (savedInstanceState == null) load("https://ifconfig.me");
    }

    private void applySocksProxy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            toast("当前 WebView 不支持代理设置，请更新 Android System WebView");
            return;
        }
        ProxyConfig config = new ProxyConfig.Builder()
                .addProxyRule("socks5://127.0.0.1:" + SshTunnelService.SOCKS_PORT)
                .build();
        Executor executor = Runnable::run;
        ProxyController.getInstance().setProxyOverride(config, executor,
                () -> Bus.log("WebView 代理已生效: socks5://127.0.0.1:" + SshTunnelService.SOCKS_PORT));
    }

    private void load(String url) {
        if (url.isEmpty()) return;
        if (!url.contains("://")) url = "https://" + url;
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
