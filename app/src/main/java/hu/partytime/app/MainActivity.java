package hu.partytime.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import hu.partytime.core.Json;

public class MainActivity extends Activity {
    private static final String START = "file:///android_asset/start.html";

    private WebView web;
    private Discovery discovery;
    private SharedPreferences prefs;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.parseColor("#120f1f"));
        getWindow().setNavigationBarColor(Color.parseColor("#120f1f"));

        prefs = getSharedPreferences("partytime", Context.MODE_PRIVATE);
        discovery = new Discovery(this);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#120f1f"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "PartyApp");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !request.getUrl().toString().startsWith("file:")) {
                    loadStart("Nem sikerült elérni a játékot. Ugyanazon a wifin vagytok?");
                }
            }
        });
        setContentView(web);
        loadStart(null);
    }

    private void loadStart(String msg) {
        web.loadUrl(START + (msg != null ? "#" + Uri.encode(msg) : ""));
    }

    private boolean onStartPage() {
        String u = web.getUrl();
        return u == null || u.startsWith("file:");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!onStartPage()) {
            loadStart(null);
        } else if (HostManager.isRunning()) {
            moveTaskToBack(true);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        discovery.stop();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Methods callable from JavaScript as window.PartyApp.* (run on a background thread). */
    public final class Bridge {
        @JavascriptInterface
        public String getState() {
            return Json.write(Json.map(
                    "hostRunning", HostManager.isRunning(),
                    "hostUrl", HostManager.localUrl(),
                    "joinUrl", HostManager.joinUrl(),
                    "hasSave", HostManager.hasSave(MainActivity.this),
                    "name", prefs.getString("name", ""),
                    "ip", NetUtil.localIp(),
                    "version", BuildConfig.VERSION_NAME));
        }

        @JavascriptInterface
        public void setName(String name) {
            prefs.edit().putString("name", name == null ? "" : name.trim()).apply();
        }

        @JavascriptInterface
        public String startHost(String name, boolean resume) {
            try {
                String url = HostManager.start(MainActivity.this, name, resume);
                GameService.start(MainActivity.this);
                runOnUiThread(() -> {
                    if (Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                    }
                });
                return Json.write(Json.map("ok", true, "url", url));
            } catch (Exception e) {
                return Json.write(Json.map("ok", false, "error", String.valueOf(e.getMessage())));
            }
        }

        @JavascriptInterface
        public void stopHost() {
            HostManager.stop();
            GameService.stop(MainActivity.this);
        }

        @JavascriptInterface
        public void startDiscovery() { discovery.start(); }

        @JavascriptInterface
        public void stopDiscovery() { discovery.stop(); }

        @JavascriptInterface
        public String getHosts() { return discovery.json(); }

        /** Checks a manually typed address ("192.168.1.23" or "192.168.1.23:8765"). */
        @JavascriptInterface
        public String probe(String address) {
            try {
                String a = address.trim().replaceFirst("^https?://", "").replaceAll("/.*$", "");
                if (a.isEmpty()) throw new IllegalArgumentException();
                if (!a.contains(":")) a = a + ":" + NetUtil.HTTP_PORT;
                String base = "http://" + a + "/";
                HttpURLConnection c = (HttpURLConnection) new URL(base + "api/ping").openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(3000);
                InputStream in = c.getInputStream();
                String body = new String(HostManager.readAll(in), StandardCharsets.UTF_8);
                c.disconnect();
                Map<String, Object> m = Json.parseObject(body);
                if (!"PartyTime".equals(Json.str(m, "app", ""))) throw new IllegalStateException();
                return Json.write(Json.map("ok", true, "url", base, "name", Json.str(m, "host", "")));
            } catch (Exception e) {
                return Json.write(Json.map("ok", false));
            }
        }

        @JavascriptInterface
        public void open(String url) {
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
            runOnUiThread(() -> {
                discovery.stop();
                web.loadUrl(url);
            });
        }

        @JavascriptInterface
        public void goHome() {
            runOnUiThread(() -> loadStart(null));
        }

        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public void vibrate(long ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                long d = Math.max(10, Math.min(ms, 2000));
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(d, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(d);
            } catch (Exception ignored) {}
        }
    }
}
