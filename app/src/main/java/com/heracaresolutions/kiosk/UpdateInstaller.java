package com.heracaresolutions.kiosk;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/** Downloads a new kiosk APK and installs it. As device owner this needs no taps on the tablet. */
final class UpdateInstaller {
    static final String EXTRA_VERSION_CODE = "com.heracaresolutions.kiosk.UPDATE_VERSION_CODE";

    interface Listener {
        void onStatus(String message);

        /** The APK is handed to Android to install; the app will be restarted. */
        default void onCommitted() {
        }

        /**
         * Download or checks failed; nothing was installed.
         *
         * @param countAsFailure true when retrying soon won't help (bad file, checksum mismatch),
         *                       false for things that may be temporary (network, server).
         */
        default void onFailed(String message, boolean countAsFailure) {
            onStatus(message);
        }
    }

    private UpdateInstaller() {
    }

    /** Manual update from the admin menu: no checksum to compare against. */
    static void install(Context context, String url, Listener listener) {
        install(context, url, null, 0, listener);
    }

    /**
     * @param expectedSha256 lowercase hex SHA-256 of the APK, or null to skip the check
     * @param versionCode    the version being installed (for failure tracking), or 0
     */
    static void install(Context context, String url, String expectedSha256, int versionCode, Listener listener) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            HttpURLConnection conn = null;
            PackageInstaller.Session session = null;
            boolean networkProblem = true;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20_000);
                conn.setReadTimeout(60_000);
                conn.setUseCaches(false);
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) throw new IOException("server replied " + code);

                PackageInstaller installer = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }
                int sessionId = installer.createSession(params);
                session = installer.openSession(sessionId);

                // Hash while streaming into the install session; nothing is committed until it matches.
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                long total = 0;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = session.openWrite("kiosk.apk", 0, -1)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        sha.update(buf, 0, n);
                        total += n;
                    }
                    session.fsync(out);
                }
                networkProblem = false;

                if (expectedSha256 != null) {
                    String actual = hex(sha.digest());
                    if (!actual.equals(expectedSha256.toLowerCase(Locale.ROOT))) {
                        throw new IOException("downloaded file doesn't match its checksum");
                    }
                }
                listener.onStatus("Downloaded " + (total / 1024) + " KB. Installing…");

                Intent result = new Intent(app, InstallResultReceiver.class)
                        .putExtra(EXTRA_VERSION_CODE, versionCode);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pi = PendingIntent.getBroadcast(app, sessionId, result, flags);
                session.commit(pi.getIntentSender());
                session.close();
                session = null;
                listener.onCommitted();
            } catch (Exception e) {
                if (session != null) {
                    try {
                        session.abandon();
                    } catch (Exception ignored) {
                    }
                }
                listener.onFailed("Update failed: " + e.getMessage(), !networkProblem);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "kiosk-update").start();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
