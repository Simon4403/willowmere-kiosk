package com.heracaresolutions.kiosk;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * Silent background updates.
 *
 * 1. Check: on launch, when Wi-Fi connects, and every 4 hours. A newer version is downloaded and
 *    checked straight away (AutoUpdater) with nothing shown on screen.
 * 2. Wait: the update installs only once the tablet has been on charge for 10 minutes without a
 *    break, and nobody has touched the screen for 2 minutes. Unplugging resets the 10 minutes.
 * 3. Install: silent (device owner). Willowmere reopens by itself.
 *
 * Driven by {@link #tick} from the kiosk (launch, charger plugged/unplugged, Wi-Fi back) and from an
 * alarm ({@link UpdateAlarmReceiver}) so it keeps working with the screen off. All calls on the main thread.
 */
final class UpdateScheduler {
    private static final String TAG = "UpdateScheduler";

    static final long CHECK_EVERY_MS = 4 * 60 * 60_000L;
    /** Never hit the server more often than this from background checks, even when they fail. */
    private static final long MIN_ATTEMPT_GAP_MS = 30 * 60_000L;
    /** Safety-net tick while an update waits and the tablet is unplugged (plugging in normally wakes us). */
    private static final long UNPLUGGED_TICK_MS = 15 * 60_000L;
    /** An install that hasn't restarted the app by now is treated as finished/failed. */
    private static final long INSTALL_STUCK_MS = 5 * 60_000L;

    interface Ui {
        void onUpdateInstalling(String versionName);

        void onUpdateFailed();
    }

    private static volatile long lastTouch;
    /** Typing on the on-screen keyboard doesn't reach the kiosk's touch handler, so an open keyboard counts as in use. */
    private static volatile boolean keyboardOpen;
    private static long lastAttempt;
    private static long installingSince;
    private static boolean checkWhenOnline;
    private static WeakReference<Ui> ui = new WeakReference<>(null);

    private UpdateScheduler() {
    }

    static void setUi(Ui u) {
        ui = new WeakReference<>(u);
    }

    /** Someone touched the screen or pressed a key. */
    static void noteTouch() {
        lastTouch = SystemClock.elapsedRealtime();
    }

    static void setKeyboardOpen(boolean open) {
        keyboardOpen = open;
        if (open) noteTouch();
    }

    private static long lastUse(long now) {
        return keyboardOpen ? now : lastTouch;
    }

    /** Kiosk started: check now (AutoUpdater itself skips if it checked in the last 10 minutes). */
    static void onLaunch(Context context) {
        tick(context, null, true);
    }

    /** Charger plugged in or unplugged. */
    static void onPowerChanged(Context context, boolean pluggedIn) {
        tick(context, pluggedIn, false);
    }

    /** Wi-Fi connected: run a check that was skipped for lack of network. */
    static void onNetworkAvailable(Context context) {
        if (checkWhenOnline) tick(context, null, true);
    }

    /** Android rejected the install (InstallResultReceiver). */
    static void onInstallRejected(Context context) {
        installingSince = 0;
        Prefs.get(context).clearPendingUpdate();
        UpdateInstaller.deleteDownloads(context, 0);
        schedule(context.getApplicationContext());
    }

    /** Clears the charging clock after a restart, because elapsedRealtime starts again from zero. */
    static void onBoot(Context context) {
        Prefs.get(context).setChargingSince(0);
    }

    /**
     * @param pluggedHint the charger state from a power event (the battery broadcast can lag), or null to read it
     * @param checkNow    run an update check now rather than waiting for the 4-hourly one
     */
    static void tick(Context context, Boolean pluggedHint, boolean checkNow) {
        Context app = context.getApplicationContext();
        Prefs prefs = Prefs.get(app);
        long now = SystemClock.elapsedRealtime();

        boolean plugged = pluggedHint != null ? pluggedHint : isPluggedIn(app);
        long since = ChargeWait.chargingSince(prefs.chargingSince(), plugged, now);
        if (since != prefs.chargingSince()) prefs.setChargingSince(since);

        if (installingSince > 0 && now - installingSince > INSTALL_STUCK_MS) installingSince = 0;
        boolean allowed = prefs.autoUpdate() && KioskPolicy.isDeviceOwner(app) && prefs.hasPin();

        File apk = pendingApk(app, prefs);
        if (apk != null && allowed && installingSince == 0 && !prefs.inMaintenance()
                && ChargeWait.ready(since, plugged, lastUse(now), now)) {
            install(app, prefs, apk);
            return;
        }

        boolean due = System.currentTimeMillis() - prefs.lastUpdateCheck() >= CHECK_EVERY_MS;
        boolean recentlyTried = lastAttempt > 0 && now - lastAttempt < MIN_ATTEMPT_GAP_MS;
        if (allowed && installingSince == 0 && (checkNow || (due && !recentlyTried))) {
            lastAttempt = now;
            checkWhenOnline = false;
            AutoUpdater.check(app, false, (result, detail) -> {
                Log.i(TAG, "Update check: " + result + " - " + detail);
                if (result == AutoUpdater.Result.NO_NETWORK) checkWhenOnline = true;
                schedule(app);
            });
        }
        schedule(app);
    }

    private static void install(Context app, Prefs prefs, File apk) {
        int code = prefs.pendingUpdateCode();
        String name = prefs.pendingUpdateName();
        installingSince = SystemClock.elapsedRealtime();
        Log.i(TAG, "On charge for 10 minutes: installing version " + name);
        Ui u = ui.get();
        if (u != null) u.onUpdateInstalling(name);
        AutoUpdater.install(app, apk, code, name, prefs.pendingUpdateSha(), (result, detail) -> {
            Log.i(TAG, "Install: " + result + " - " + detail);
            if (result == AutoUpdater.Result.INSTALL_FAILED) {
                installingSince = 0;
                Ui v = ui.get();
                if (v != null) v.onUpdateFailed();
                schedule(app);
            }
            // INSTALLING: Android replaces the app and restarts it.
        });
    }

    /** The downloaded APK waiting to install, or null (tidying up anything stale). */
    static File pendingApk(Context app, Prefs prefs) {
        int code = prefs.pendingUpdateCode();
        if (code <= 0) return null;
        File f = UpdateInstaller.downloadedApk(app, code);
        if (code <= BuildConfig.VERSION_CODE || !f.isFile() || prefs.pendingUpdateSha() == null) {
            prefs.clearPendingUpdate(); // already installed, or the file has gone
            UpdateInstaller.deleteDownloads(app, 0);
            return null;
        }
        return f;
    }

    /** Short status for the admin menu, or null when nothing is waiting. */
    static String pendingStatus(Context context) {
        Context app = context.getApplicationContext();
        Prefs prefs = Prefs.get(app);
        if (pendingApk(app, prefs) == null) return null;
        long since = prefs.chargingSince();
        String when;
        if (since <= 0 || !isPluggedIn(app)) {
            when = "Installs after the tablet has been on charge for 10 minutes.";
        } else {
            long mins = (SystemClock.elapsedRealtime() - since) / 60_000L;
            when = "On charge for " + mins + " min; installs at 10 min (once the screen has been untouched for 2 min).";
        }
        return "Version " + prefs.pendingUpdateName() + " is downloaded and ready. " + when;
    }

    static boolean isPluggedIn(Context app) {
        try {
            Intent battery = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Sets the next alarm. Replaces any earlier one. */
    private static void schedule(Context app) {
        Prefs prefs = Prefs.get(app);
        long now = SystemClock.elapsedRealtime();
        long delay;
        if (!prefs.autoUpdate()) {
            delay = CHECK_EVERY_MS; // keeps ticking so switching it back on needs nothing else
        } else if (pendingApk(app, prefs) != null) {
            boolean plugged = isPluggedIn(app);
            delay = plugged
                    ? ChargeWait.waitMs(prefs.chargingSince() > 0 ? prefs.chargingSince() : now, lastUse(now), now) + 5_000L
                    : UNPLUGGED_TICK_MS;
        } else {
            long sinceCheck = System.currentTimeMillis() - prefs.lastUpdateCheck();
            delay = Math.max(MIN_ATTEMPT_GAP_MS, CHECK_EVERY_MS - Math.max(0, sinceCheck));
        }
        delay = Math.max(15_000L, Math.min(CHECK_EVERY_MS, delay));

        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (am == null) return;
        Intent i = new Intent(app, UpdateAlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(app, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + delay, pi);
        } catch (Exception e) {
            Log.w(TAG, "Couldn't set update alarm", e);
        }
    }
}
