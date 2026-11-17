package com.heracaresolutions.kiosk;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Downloads a new kiosk APK and installs it. As device owner this needs no taps on the tablet.
 *
 * Automatic updates are two steps: {@link #download} saves and checks the APK in the app's
 * private storage straight away, and {@link #installDownloaded} installs it later, once the
 * tablet has been on charge for a while (see UpdateScheduler).
 */
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
                int sessionId = installer.createSession(sessionParams(app));
                session = installer.openSession(sessionId);

                // Hash while streaming into the install session; nothing is committed until it matches.
                MessageDigest sha = sha256();
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

                commit(app, session, sessionId, versionCode);
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

    // ------------------------------------------------- Download now, install later

    private static File dir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "updates");
    }

    /** Where a downloaded, checked APK for this version lives. */
    static File downloadedApk(Context context, int versionCode) {
        return new File(dir(context), "kiosk-" + versionCode + ".apk");
    }

    /** Removes downloaded APKs other than {@code keepVersionCode} (0 removes them all). */
    static void deleteDownloads(Context context, int keepVersionCode) {
        File[] files = dir(context).listFiles();
        if (files == null) return;
        String keep = keepVersionCode > 0 ? downloadedApk(context, keepVersionCode).getName() : null;
        for (File f : files) {
            if (!f.getName().equals(keep)) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    /**
     * Downloads the APK to private storage and checks it against its SHA-256. Blocking: call off the
     * main thread. Nothing is installed. On success the file is at {@link #downloadedApk}.
     *
     * @throws IOException on any failure; {@code DownloadException.permanent} says whether retrying soon is pointless
     */
    static File download(Context context, String url, String expectedSha256, int versionCode) throws IOException {
        File d = dir(context);
        if (!d.isDirectory() && !d.mkdirs()) throw new DownloadException("can't create update folder", false);
        File target = downloadedApk(context, versionCode);
        if (target.isFile() && expectedSha256.equalsIgnoreCase(sha256Of(target))) return target; // already have it
        File part = new File(d, target.getName() + ".part");
        HttpURLConnection conn = null;
        String actual;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(60_000);
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new DownloadException("server replied " + code, false);
            MessageDigest sha = sha256();
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                }
                ((FileOutputStream) out).getFD().sync();
            }
            actual = hex(sha.digest());
        } catch (DownloadException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new DownloadException(e.getMessage(), false);
        } finally {
            if (conn != null) conn.disconnect();
        }
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new DownloadException("downloaded file doesn't match its checksum", true);
        }
        if (!part.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new DownloadException("couldn't save the update", false);
        }
        deleteDownloads(context, versionCode);
        return target;
    }

    static final class DownloadException extends IOException {
        final boolean permanent;

        DownloadException(String message, boolean permanent) {
            super(message);
            this.permanent = permanent;
        }
    }

    /**
     * Installs an APK saved by {@link #download}, checking its SHA-256 again as it is copied
     * into the install session (so a damaged file is never committed).
     */
    static void installDownloaded(Context context, File apk, String expectedSha256, int versionCode, Listener listener) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                PackageInstaller installer = app.getPackageManager().getPackageInstaller();
                int sessionId = installer.createSession(sessionParams(app));
                session = installer.openSession(sessionId);
                MessageDigest sha = sha256();
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("kiosk.apk", 0, apk.length())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        sha.update(buf, 0, n);
                    }
                    session.fsync(out);
                }
                if (!hex(sha.digest()).equalsIgnoreCase(expectedSha256)) {
                    throw new IOException("saved update doesn't match its checksum");
                }
                commit(app, session, sessionId, versionCode);
                session = null;
                listener.onCommitted();
            } catch (Exception e) {
                if (session != null) {
                    try {
                        session.abandon();
                    } catch (Exception ignored) {
                    }
                }
                listener.onFailed("Update failed: " + e.getMessage(), true);
            }
        }, "kiosk-update-install").start();
    }

    private static PackageInstaller.SessionParams sessionParams(Context app) {
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        return params;
    }

    private static void commit(Context app, PackageInstaller.Session session, int sessionId, int versionCode) {
        Intent result = new Intent(app, InstallResultReceiver.class)
                .putExtra(EXTRA_VERSION_CODE, versionCode);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(app, sessionId, result, flags);
        session.commit(pi.getIntentSender());
        session.close();
    }

    static String sha256Of(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest sha = sha256();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) sha.update(buf, 0, n);
            return hex(sha.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
