package com.heracaresolutions.kiosk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;

/**
 * The kiosk: a full-screen browser locked to the Willowmere site.
 * Admin menu: tap the top-left corner of the screen 7 times quickly, then enter the PIN.
 */
public class KioskActivity extends Activity {
    private static final String TAG = "Kiosk";

    private static final int REQUEST_FILE = 41;
    private static final int CORNER_TAPS = 7;
    private static final long CORNER_TAP_WINDOW_MS = 4000;
    private static final int CORNER_SIZE_DP = 96;
    private static final long RETRY_INTERVAL_MS = 15_000;
    private static final long IDLE_CHECK_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Long> cornerTaps = new ArrayDeque<>();

    private Prefs prefs;
    private FrameLayout root;
    private WebView web;
    private View offlinePanel;
    private TextView offlineDetail;
    private ValueCallback<Uri[]> pendingFileCallback;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String failedUrl;
    private boolean mainFrameFailed;
    private long lastInteraction;
    private TextView brightnessBadge;
    private final Runnable hideBrightnessBadge = () -> { if (brightnessBadge != null) brightnessBadge.setVisibility(View.GONE); };
    private int keyboardPadding = -1;
    /** Host of the page now showing, for the printing bridge (see isHeraPage). Set on the UI thread, read from the bridge's. */
    private volatile String pageHost;

    private final Runnable retryRunnable = this::retryNow;

    private final Runnable idleRunnable = new Runnable() {
        @Override
        public void run() {
            int minutes = prefs.idleMinutes();
            if (minutes > 0 && web != null
                    && SystemClock.elapsedRealtime() - lastInteraction > minutes * 60_000L) {
                String start = prefs.startUrl();
                if (!start.equals(web.getUrl())) web.loadUrl(start);
                lastInteraction = SystemClock.elapsedRealtime();
            }
            handler.postDelayed(this, IDLE_CHECK_MS);
        }
    };

    // ------------------------------------------------------------ Lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        lastInteraction = SystemClock.elapsedRealtime();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        setUpKeyboardHandling();

        createWebView();
        createOfflinePanel();
        createBrightnessBadge();
        registerNetworkCallback();

        web.loadUrl(prefs.startUrl());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back to the kiosk always ends maintenance and re-applies the lockdown.
        prefs.endMaintenance();
        RelockReceiver.cancel(this);
        KioskPolicy.applyAll(this);
        enterLockTask();
        applyScreenOn();
        applyBrightness();
        hideSystemBars();
        applyUserAgent();

        if (web != null) {
            web.onResume();
            if (prefs.takeClearCacheRequest()) web.clearCache(true);
            if (prefs.takeReloadRequest()) loadStart();
        }
        lastInteraction = SystemClock.elapsedRealtime();
        handler.removeCallbacks(idleRunnable);
        handler.postDelayed(idleRunnable, IDLE_CHECK_MS);

        if (!prefs.hasPin()) {
            // First run: an admin PIN must exist before anything else.
            openAdmin();
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(idleRunnable);
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (networkCallback != null) {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            try {
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressLint("MissingSuperCall")
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // Back only ever moves back inside Willowmere; it never leaves the kiosk.
        if (web != null && web.canGoBack()) web.goBack();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        lastInteraction = SystemClock.elapsedRealtime();
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int zone = dp(CORNER_SIZE_DP);
            if (ev.getRawX() <= zone && ev.getRawY() <= zone) {
                onCornerTap();
            } else {
                cornerTaps.clear();
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE) return;
        KioskPolicy.restoreLockTask(this);
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            pendingFileCallback = null;
        }
    }

    // ---------------------------------------------------------- Kiosk lock

    private void enterLockTask() {
        if (KioskPolicy.isInLockTask(this)) return;
        boolean owner = KioskPolicy.isDeviceOwner(this);
        // Without device owner this is Android screen pinning, which asks for confirmation;
        // don't pin until an admin PIN exists, so there's always a way back out.
        if (!owner && !prefs.hasPin()) return;
        try {
            startLockTask();
        } catch (Exception e) {
            Log.w(TAG, "startLockTask failed", e);
        }
    }

    private void onCornerTap() {
        long now = SystemClock.elapsedRealtime();
        cornerTaps.addLast(now);
        while (!cornerTaps.isEmpty() && now - cornerTaps.peekFirst() > CORNER_TAP_WINDOW_MS) {
            cornerTaps.removeFirst();
        }
        if (cornerTaps.size() >= CORNER_TAPS) {
            cornerTaps.clear();
            PinDialog.show(this, "Kiosk admin", this::openAdmin);
        }
    }

    private void openAdmin() {
        startActivity(new Intent(this, AdminActivity.class));
    }

    // ------------------------------------------------------------- Display

    private void applyScreenOn() {
        if (prefs.keepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /** Full-screen windows don't resize for the keyboard, so pad the page above it ourselves. */
    @SuppressWarnings("deprecation")
    private void setUpKeyboardHandling() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int bottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                v.setPadding(0, 0, 0, bottom);
                return WindowInsets.CONSUMED;
            });
        } else {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                Rect r = new Rect();
                root.getWindowVisibleDisplayFrame(r);
                int screen = root.getRootView().getHeight();
                int hidden = screen - r.bottom;
                int pad = hidden > screen * 0.15 ? hidden : 0;
                if (pad != keyboardPadding) {
                    keyboardPadding = pad;
                    root.setPadding(0, 0, 0, pad);
                }
            });
        }
    }

    // ------------------------------------------------------- Brightness keys

    /*
     * The volume buttons adjust screen brightness instead of volume: on a ward
     * tablet brightness is what staff need to change (dim at night, bright by a
     * window), and the system brightness slider is out of reach in kiosk mode.
     * The level is saved and applied to this window, so it survives restarts.
     */
    private static final float BRIGHTNESS_STEP = 0.1f, BRIGHTNESS_MIN = 0.05f;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                float now = currentBrightness();
                float next = code == KeyEvent.KEYCODE_VOLUME_UP ? now + BRIGHTNESS_STEP : now - BRIGHTNESS_STEP;
                next = Math.max(BRIGHTNESS_MIN, Math.min(1f, next));
                prefs.setBrightness(next);
                applyBrightness();
                showBrightnessBadge(next);
                lastInteraction = SystemClock.elapsedRealtime();
            }
            return true;   // consumed: the volume never changes
        }
        return super.dispatchKeyEvent(event);
    }

    private float currentBrightness() {
        float saved = prefs.brightness();
        if (saved >= 0) return saved;
        try {
            int sys = android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS);
            return Math.max(BRIGHTNESS_MIN, sys / 255f);
        } catch (Exception e) {
            return 0.5f;
        }
    }

    private void applyBrightness() {
        float b = prefs.brightness();
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b >= 0 ? b : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    private void createBrightnessBadge() {
        brightnessBadge = new TextView(this);
        brightnessBadge.setTextColor(Color.WHITE);
        brightnessBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        brightnessBadge.setTypeface(Typeface.DEFAULT_BOLD);
        int pad = dp(18);
        brightnessBadge.setPadding(pad * 2, pad, pad * 2, pad);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xDD05131F);
        bg.setCornerRadius(dp(16));
        brightnessBadge.setBackground(bg);
        brightnessBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(brightnessBadge, lp);
    }

    private void showBrightnessBadge(float level) {
        if (brightnessBadge == null) return;
        brightnessBadge.setText("\u2600  Brightness " + Math.round(level * 100) + "%");
        brightnessBadge.setVisibility(View.VISIBLE);
        brightnessBadge.bringToFront();
        handler.removeCallbacks(hideBrightnessBadge);
        handler.postDelayed(hideBrightnessBadge, 1500);
    }

    // ------------------------------------------------------------- WebView

    private String baseUserAgent;

    /*
     * The user agent tells Hera two things: that it is on a kiosk tablet, and the
     * idle minutes set in the kiosk menu (HeraIdle/5), so Hera locks to the PIN
     * after the same time the kiosk uses. Re-applied on resume, so a change in
     * the menu takes effect without a restart.
     */
    private void applyUserAgent() {
        if (web == null || baseUserAgent == null) return;
        String ua = baseUserAgent + " WillowmereKiosk/" + BuildConfig.VERSION_NAME;
        int idle = prefs.idleMinutes();
        if (idle > 0) ua += " HeraIdle/" + idle;
        if (!ua.equals(web.getSettings().getUserAgentString())) web.getSettings().setUserAgentString(ua);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private void createWebView() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);           // window.open / target=_blank stay in the kiosk
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        baseUserAgent = s.getUserAgentString();
        applyUserAgent();

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, false);

        // Printing for Hera (window.HeraKiosk). Every call checks the page is Hera's own.
        web.addJavascriptInterface(new KioskBridge(this), "HeraKiosk");
        web.setWebViewClient(new KioskClient());
        web.setWebChromeClient(new KioskChrome());
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) ->
                toast("Downloads are switched off on this tablet."));

        root.addView(web, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** If the browser engine crashes or is killed for memory, start a fresh one instead of closing. */
    private void rebuildWebView() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        createWebView();
        loadStart();
    }

    private void loadStart() {
        failedUrl = null;
        if (web != null) web.loadUrl(prefs.startUrl());
    }

    private final class KioskClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (prefs.isAllowed(request.getUrl())) return false;
            if (request.isForMainFrame()) toast("That page isn't available on this tablet.");
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            mainFrameFailed = false;
            pageHost = hostOf(url);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            pageHost = hostOf(url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!mainFrameFailed) hideOffline();
            CookieManager.getInstance().flush();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            mainFrameFailed = true;
            failedUrl = request.getUrl().toString();
            showOffline(String.valueOf(error.getDescription()));
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
            sslHandler.cancel();
            mainFrameFailed = true;
            failedUrl = error.getUrl();
            showOffline("The secure connection couldn't be verified. Check the tablet's date and time, and that it's on the right Wi-Fi.");
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view == web) rebuildWebView();
            return true;
        }
    }

    private final class KioskChrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (!prefs.allowUploads()) {
                toast("File uploads are switched off on this tablet.");
                callback.onReceiveValue(null);
                return true;
            }
            if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = callback;

            Intent picker;
            try {
                picker = params.createIntent();
            } catch (Exception e) {
                pendingFileCallback = null;
                callback.onReceiveValue(null);
                return true;
            }
            // The file picker is another app, so briefly allow it inside the kiosk.
            KioskPolicy.allowAppsFor(KioskActivity.this, picker);
            try {
                startActivityForResult(picker, REQUEST_FILE);
            } catch (ActivityNotFoundException e) {
                KioskPolicy.restoreLockTask(KioskActivity.this);
                pendingFileCallback = null;
                callback.onReceiveValue(null);
                toast("No file picker available.");
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny(); // camera / microphone not used yet
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }
    }

    // ------------------------------------------------------- Offline screen

    private void createOfflinePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundColor(Color.parseColor("#F4F1EA"));
        int pad = dp(40);
        panel.setPadding(pad, pad, pad, pad);
        panel.setClickable(true);

        TextView title = new TextView(this);
        title.setText("Can't reach Willowmere");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1F3D33"));
        title.setGravity(Gravity.CENTER);

        offlineDetail = new TextView(this);
        offlineDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        offlineDetail.setTextColor(Color.parseColor("#4A5A54"));
        offlineDetail.setGravity(Gravity.CENTER);
        offlineDetail.setPadding(0, dp(16), 0, dp(24));

        Button retry = new Button(this);
        retry.setText("Try again now");
        retry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        retry.setOnClickListener(v -> retryNow());

        panel.addView(title);
        panel.addView(offlineDetail);
        panel.addView(retry, new LinearLayout.LayoutParams(dp(260), dp(64)));
        panel.setVisibility(View.GONE);
        offlinePanel = panel;
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showOffline(String detail) {
        offlineDetail.setText(detail + "\n\nCheck the Wi-Fi is working. The tablet will keep trying every 15 seconds.");
        offlinePanel.setVisibility(View.VISIBLE);
        handler.removeCallbacks(retryRunnable);
        handler.postDelayed(retryRunnable, RETRY_INTERVAL_MS);
    }

    private void hideOffline() {
        offlinePanel.setVisibility(View.GONE);
        handler.removeCallbacks(retryRunnable);
        failedUrl = null;
    }

    private void retryNow() {
        handler.removeCallbacks(retryRunnable);
        if (web == null) return;
        web.loadUrl(failedUrl != null ? failedUrl : prefs.startUrl());
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handler.post(() -> {
                    if (offlinePanel != null && offlinePanel.getVisibility() == View.VISIBLE) retryNow();
                });
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception e) {
            networkCallback = null;
        }
    }

    // ------------------------------------------------------------ Printing

    /** True when the page showing is the Hera site this kiosk was set up for (not an extra allowed site). */
    boolean isHeraPage() {
        String start = hostOf(prefs.startUrl());
        String now = pageHost;
        return start != null && start.equals(now);
    }

    /** Calls window.HeraKioskEvents.<name>(args) in the page. Args must already be JavaScript literals. */
    void sendEvent(String name, String... args) {
        runOnUiThread(() -> {
            if (web == null || !isHeraPage()) return;
            web.evaluateJavascript("window.HeraKioskEvents&&window.HeraKioskEvents." + name + "&&window.HeraKioskEvents."
                    + name + "(" + String.join(",", args) + ")", null);
        });
    }

    private static String hostOf(String url) {
        if (url == null) return null;
        String h = Uri.parse(url).getHost();
        return h == null ? null : h.toLowerCase(java.util.Locale.ROOT);
    }

    // --------------------------------------------------------------- Utils

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
