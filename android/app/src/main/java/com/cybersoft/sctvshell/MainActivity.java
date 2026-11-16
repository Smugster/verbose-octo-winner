package com.cybersoft.sctvshell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.cybersoft.sctvshell.core.MiniJson;
import com.cybersoft.sctvshell.core.UpdateEngine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The thin shell (E7-14): a full-screen web view that plays the player stored on the device, or the one inside the APK.
 * Player updates come from the update host (shell-config.json), signed and checked by UpdateEngine; a new build switches
 * in at a safe moment (the player's window.sctvSafeToSwitch), must report ready (SctvShell.ready) within the probation
 * time, and is rolled back otherwise.
 */
public class MainActivity extends Activity {
    private static final String TAG = "SctvShell", FLOOR = "file:///android_asset/floor/index.html";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private WebView web; private TextView statusView; private UpdateEngine engine; private String playerConfig = "null";
    private long checkMs = 15 * 60_000L, probationMs = 60_000L; private String switchPolicy = "between-meals"; private boolean showStatus = true;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String updateUrl = "";
        try (InputStream in = getAssets().open("shell-config.json")) {
            Map<?, ?> c = (Map<?, ?>) MiniJson.parse(new String(readAll(in), StandardCharsets.UTF_8));
            updateUrl = String.valueOf(c.get("updateUrl"));
            if (c.get("checkMinutes") != null) checkMs = (long) (((Double) c.get("checkMinutes")) * 60_000);
            if (c.get("probationSeconds") != null) probationMs = (long) (((Double) c.get("probationSeconds")) * 1000);
            if (c.get("switchPolicy") != null) switchPolicy = String.valueOf(c.get("switchPolicy"));
            if (c.get("showStatus") != null) showStatus = Boolean.TRUE.equals(c.get("showStatus"));
            if (c.get("player") != null) playerConfig = MiniJson.stringify(c.get("player"));   // screen settings stay in the APK, never on the public host
        } catch (Exception e) { Log.e(TAG, "shell-config.json unreadable", e); }
        @SuppressLint("HardwareIds") String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        try { engine = new UpdateEngine(new File(getFilesDir(), "shell"), updateUrl, ShellKeys.PUBLIC_KEY, deviceId == null ? "unknown" : deviceId, this::fetch); }
        catch (Exception e) { Log.e(TAG, "update engine failed to start; playing the built-in player only", e); }
        if (engine != null) engine.onAppStart(System.currentTimeMillis(), probationMs);

        FrameLayout root = new FrameLayout(this); web = new WebView(this); root.addView(web);
        statusView = new TextView(this); statusView.setTextColor(Color.WHITE); statusView.setTextSize(11); statusView.setBackgroundColor(0x99000000); statusView.setPadding(10, 4, 10, 4);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, 12, 8); root.addView(statusView, lp); statusView.setVisibility(showStatus ? View.VISIBLE : View.GONE);
        setContentView(root); immersive();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(true); s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setBackgroundColor(Color.BLACK); web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {   // the page crashed: start again rather than leave a dead screen
                Log.e(TAG, "web view render process gone; restarting"); recreate(); return true; }
        });
        web.addJavascriptInterface(new ShellBridge(), "SctvShell");
        web.addJavascriptInterface(new NativeHttp(), "SctvNative");
        loadPlayer();
        if (engine != null && engine.onProbation()) watchProbation();
        ui.postDelayed(checkLoop, 5_000);
    }

    /** The stored build, or the player inside the APK. */
    private void loadPlayer() {
        File f = engine == null ? null : engine.entryFile();
        web.loadUrl(f != null ? Uri.fromFile(f).toString() : FLOOR);
        showStatus();
    }

    private final Runnable checkLoop = new Runnable() { @Override public void run() {
        if (engine != null) bg.execute(() -> { engine.check(System.currentTimeMillis()); ui.post(() -> { showStatus(); trySwitch(); }); });
        ui.postDelayed(this, checkMs);
    } };
    /** While a build waits, ask the player every minute whether now is a safe moment. */
    private final Runnable switchLoop = new Runnable() { @Override public void run() { trySwitch(); } };
    private void trySwitch() {
        ui.removeCallbacks(switchLoop);
        if (engine == null || !engine.hasPending()) return;
        if ("now".equals(switchPolicy)) { doSwitch(); return; }
        web.evaluateJavascript("(function(){try{return window.sctvSafeToSwitch?!!window.sctvSafeToSwitch():true}catch(e){return true}})()", v -> {
            if ("true".equals(v)) doSwitch(); else ui.postDelayed(switchLoop, 60_000); });
    }
    private void doSwitch() {
        engine.switchToPending(System.currentTimeMillis(), probationMs);
        loadPlayer(); watchProbation();
    }
    private void watchProbation() {
        ui.postDelayed(() -> {
            if (engine != null && engine.probationExpired(System.currentTimeMillis())) {
                engine.rollback("it didn't report ready within " + (probationMs / 1000) + " s"); loadPlayer(); }
            else if (engine != null && engine.onProbation()) watchProbation();
        }, probationMs + 1_000);
    }
    private void showStatus() {
        if (!showStatus || engine == null) return;
        UpdateEngine.Build b = engine.current();
        statusView.setText("Shell " + UpdateEngine.SHELL_VERSION + " \u00b7 " + (b == null ? "built-in player" : "build " + b.build) + " \u00b7 " + engine.status());
    }

    /** Fetches from the update host; manifest files skip any cache, so a new release is seen at the next check. */
    private byte[] fetch(String url) throws java.io.IOException {
        String u = url.endsWith("manifest.json") || url.endsWith("manifest.sig") ? url + "?t=" + System.currentTimeMillis() : url;
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15_000); c.setReadTimeout(30_000); c.setUseCaches(false); c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode(); if (code != 200) throw new java.io.IOException("HTTP " + code);
        try (InputStream in = c.getInputStream()) { return readAll(in); }
    }
    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); return o.toByteArray();
    }

    /** What the player can tell the shell. */
    class ShellBridge {
        @JavascriptInterface public void ready(String build) {
            try { int b = Integer.parseInt(build.trim()); if (engine != null) engine.ready(b, System.currentTimeMillis()); } catch (NumberFormatException ignored) {}
            ui.post(MainActivity.this::showStatus); }
        @JavascriptInterface public String info() { return engine == null ? "{}" : engine.infoJson(); }
        @JavascriptInterface public void log(String msg) { Log.i(TAG, "player: " + msg); }
        @JavascriptInterface public String config() { return playerConfig; }
    }

    /** Production menu calls from native code (no browser cross-origin block); the same interface as the PoC app. */
    class NativeHttp {
        @JavascriptInterface public String httpGet(String url) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("Accept", "application/json"); c.setConnectTimeout(15_000); c.setReadTimeout(20_000);
                int code = c.getResponseCode(); InputStream st = code >= 200 && code <= 299 ? c.getInputStream() : c.getErrorStream();
                String body = st == null ? "" : new String(readAll(st), StandardCharsets.UTF_8);
                return code >= 200 && code <= 299 ? body : "ERR:" + code + " " + body;
            } catch (Exception e) { return "ERR:" + (e.getMessage() == null ? "network error" : e.getMessage()); }
        }
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive(); }
    @SuppressWarnings("deprecation") @Override public void onBackPressed() { /* a signage screen: Back does nothing */ }
    @Override protected void onDestroy() { ui.removeCallbacksAndMessages(null); bg.shutdownNow(); if (web != null) web.destroy(); super.onDestroy(); }
}
