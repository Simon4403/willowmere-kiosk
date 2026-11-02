package com.heracaresolutions.kiosk;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Automatic updates. On launch the kiosk reads version.json, which every build
 * publishes next to the APK:
 *
 *   {"versionCode": 12, "versionName": "1.0.12",
 *    "apkUrl": "https://…/v1.0.12/willowmere-kiosk.apk", "sha256": "…64 hex…"}
 *
 * If versionCode is newer than this app, the APK is downloaded, checked against
 * sha256, and installed silently (device owner only). Android also refuses any
 * APK not signed with the same key, so a tampered file can't be installed.
 * After install Android restarts the app and BootReceiver brings the kiosk back.
 */
final class AutoUpdater {
    private static final String TAG = "AutoUpdater";

    /** Don't re-check more often than this (the activity can be recreated, e.g. after a crash). */
    private static final long MIN_CHECK_INTERVAL_MS = 10 * 60_000L;
    /** After a failed install of a version, leave that version alone for this long. */
    static final long FAILURE_BACKOFF_MS = 6 * 60 * 60_000L;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024;

    enum Result {
        /** Turned off, not device owner, no update link, checked recently, or backing off. */
        SKIPPED,
        /** No network yet: worth trying again when Wi-Fi connects. */
        NO_NETWORK,
        /** Couldn't read version.json (server error, bad file). Tried again on the next launch. */
        CHECK_FAILED,
        UP_TO_DATE,
        /** A newer version is downloading and installing; the app will restart. */
        INSTALLING,
        /** Download, checksum or install failed. */
        INSTALL_FAILED
    }

    interface Listener {
        /** Called on the main thread before the download starts. */
        default void onInstallStarting(String versionName) {
        }

        /** Called once on the main thread with the outcome (INSTALLING means Android is installing it now). */
        void onResult(Result result, String detail);
    }

    private AutoUpdater() {
    }

    /**
     * Checks for and installs an update.
     *
     * @param manual true from the admin menu's "Check for updates now": ignores the
     *               on/off setting, the 10-minute limit and the failure back-off.
     */
    static void check(Context context, boolean manual, Listener listener) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        Prefs prefs = Prefs.get(app);

        if (!manual && !prefs.autoUpdate()) {
            main.post(() -> listener.onResult(Result.SKIPPED, "Automatic updates are off"));
            return;
        }
        if (!KioskPolicy.isDeviceOwner(app)) {
            // Without device owner Android would ask staff to confirm the install.
            main.post(() -> listener.onResult(Result.SKIPPED,
                    "Automatic updates need full protection (QR set-up)"));
            return;
        }
        String manifestUrl = manifestUrlFor(prefs.updateUrl());
        if (manifestUrl == null) {
            main.post(() -> listener.onResult(Result.SKIPPED, "No update link set"));
            return;
        }
        if (!manual && System.currentTimeMillis() - prefs.lastUpdateCheck() < MIN_CHECK_INTERVAL_MS) {
            main.post(() -> listener.onResult(Result.SKIPPED, "Checked recently"));
            return;
        }
        if (!hasNetwork(app)) {
            main.post(() -> listener.onResult(Result.NO_NETWORK, "No network"));
            return;
        }

        new Thread(() -> {
            Manifest m;
            try {
                m = Manifest.parse(fetch(manifestUrl));
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
                main.post(() -> listener.onResult(Result.CHECK_FAILED, "Couldn't check for updates: " + e.getMessage()));
                return;
            }
            prefs.setLastUpdateCheck(System.currentTimeMillis());

            if (m.versionCode <= BuildConfig.VERSION_CODE) {
                main.post(() -> listener.onResult(Result.UP_TO_DATE,
                        "Up to date (version " + BuildConfig.VERSION_NAME + ")"));
                return;
            }
            if (!manual && prefs.updateRecentlyFailed(m.versionCode, FAILURE_BACKOFF_MS)) {
                main.post(() -> listener.onResult(Result.SKIPPED,
                        "Version " + m.versionName + " failed to install recently; will retry later"));
                return;
            }

            main.post(() -> listener.onInstallStarting(m.versionName));
            UpdateInstaller.install(app, m.apkUrl, m.sha256, m.versionCode, new UpdateInstaller.Listener() {
                @Override
                public void onStatus(String message) {
                    Log.i(TAG, message);
                }

                @Override
                public void onCommitted() {
                    main.post(() -> listener.onResult(Result.INSTALLING, "Installing version " + m.versionName));
                }

                @Override
                public void onFailed(String message, boolean countAsFailure) {
                    if (countAsFailure) prefs.recordUpdateFailure(m.versionCode);
                    main.post(() -> listener.onResult(Result.INSTALL_FAILED, message));
                }
            });
        }, "kiosk-update-check").start();
    }

    /** version.json sits next to the APK: …/download/willowmere-kiosk.apk → …/download/version.json */
    static String manifestUrlFor(String apkUrl) {
        if (apkUrl == null) return null;
        String base = apkUrl.trim();
        int q = base.indexOf('?');
        if (q >= 0) base = base.substring(0, q);
        int hash = base.indexOf('#');
        if (hash >= 0) base = base.substring(0, hash);
        if (!base.startsWith("https://")) return null;
        int slash = base.lastIndexOf('/');
        if (slash <= "https://".length()) return null;
        return base.substring(0, slash + 1) + "version.json";
    }

    private static boolean hasNetwork(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) return true; // can't tell: just try
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("server replied " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_MANIFEST_BYTES) throw new IOException("version.json too large");
                }
                return out.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }

    /** The contents of version.json, validated. */
    static final class Manifest {
        final int versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;

        private Manifest(int versionCode, String versionName, String apkUrl, String sha256) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
        }

        static Manifest parse(String json) throws IOException {
            try {
                JSONObject o = new JSONObject(json);
                int code = o.getInt("versionCode");
                String apk = o.getString("apkUrl").trim();
                String sha = o.getString("sha256").trim().toLowerCase(java.util.Locale.ROOT);
                String name = o.optString("versionName", String.valueOf(code));
                if (code <= 0) throw new IOException("bad versionCode");
                if (!apk.startsWith("https://")) throw new IOException("apkUrl must be https");
                if (!sha.matches("[0-9a-f]{64}")) throw new IOException("bad sha256");
                return new Manifest(code, name, apk, sha);
            } catch (org.json.JSONException e) {
                throw new IOException("unreadable version.json", e);
            }
        }
    }
}
