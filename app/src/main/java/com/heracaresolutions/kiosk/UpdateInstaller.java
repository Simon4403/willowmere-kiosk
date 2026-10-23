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

/** Downloads a new kiosk APK and installs it. As device owner this needs no taps on the tablet. */
final class UpdateInstaller {
    interface Listener {
        void onStatus(String message);
    }

    private UpdateInstaller() {
    }

    static void install(Context context, String url, Listener listener) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            HttpURLConnection conn = null;
            PackageInstaller.Session session = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20_000);
                conn.setReadTimeout(60_000);
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

                long total = 0;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = session.openWrite("kiosk.apk", 0, -1)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                    session.fsync(out);
                }
                listener.onStatus("Downloaded " + (total / 1024) + " KB. Installing…");

                Intent result = new Intent(app, InstallResultReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pi = PendingIntent.getBroadcast(app, sessionId, result, flags);
                session.commit(pi.getIntentSender());
                session.close();
                session = null;
            } catch (Exception e) {
                if (session != null) {
                    try {
                        session.abandon();
                    } catch (Exception ignored) {
                    }
                }
                listener.onStatus("Update failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "kiosk-update").start();
    }
}
