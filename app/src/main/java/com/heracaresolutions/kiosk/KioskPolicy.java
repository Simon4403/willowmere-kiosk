package com.heracaresolutions.kiosk;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.app.admin.SystemUpdatePolicy;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything that needs Device Owner rights. When the app is NOT device owner
 * (installed normally) these calls are skipped and the kiosk falls back to
 * Android screen pinning.
 */
final class KioskPolicy {
    private static final String TAG = "KioskPolicy";

    private static final String[] RESTRICTIONS = {
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_USB_FILE_TRANSFER,
    };

    private KioskPolicy() {
    }

    static DevicePolicyManager dpm(Context c) {
        return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static ComponentName admin(Context c) {
        return new ComponentName(c, KioskAdminReceiver.class);
    }

    static boolean isDeviceOwner(Context c) {
        DevicePolicyManager d = dpm(c);
        return d != null && d.isDeviceOwnerApp(c.getPackageName());
    }

    static boolean isInLockTask(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    /** Applies the full lockdown. Safe to call repeatedly. */
    static void applyAll(Context c) {
        if (!isDeviceOwner(c)) return;
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        Prefs p = Prefs.get(c);

        run("lock task packages", () -> d.setLockTaskPackages(a, new String[]{c.getPackageName()}));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // No home, recents, notifications or power menu while locked.
            run("lock task features", () -> d.setLockTaskFeatures(a, DevicePolicyManager.LOCK_TASK_FEATURE_NONE));
        }
        run("home app", () -> setKioskAsHome(c, true));
        run("keyguard", () -> d.setKeyguardDisabled(a, true));
        run("status bar", () -> d.setStatusBarDisabled(a, true));
        run("screen capture", () -> d.setScreenCaptureDisabled(a, true));
        for (String r : RESTRICTIONS) {
            run(r, () -> d.addUserRestriction(a, r));
        }
        run("usb debugging", () -> {
            if (p.blockUsbDebugging()) {
                d.addUserRestriction(a, UserManager.DISALLOW_DEBUGGING_FEATURES);
            } else {
                d.clearUserRestriction(a, UserManager.DISALLOW_DEBUGGING_FEATURES);
            }
        });
        // Android system updates install overnight (02:00-05:00) rather than mid-shift.
        run("update window", () -> d.setSystemUpdatePolicy(a, SystemUpdatePolicy.createWindowedInstallPolicy(120, 300)));
        run("stay on while charging", () -> d.setGlobalSetting(a, Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                p.keepScreenOn()
                        ? String.valueOf(BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB | BatteryManager.BATTERY_PLUGGED_WIRELESS)
                        : "0"));
    }

    /** Back to "only the kiosk may run". */
    static void restoreLockTask(Context c) {
        if (!isDeviceOwner(c)) return;
        run("restore lock task", () -> dpm(c).setLockTaskPackages(admin(c), new String[]{c.getPackageName()}));
    }

    /** Temporarily lets the app(s) that handle this intent open inside the locked kiosk (Wi-Fi settings, file picker). */
    static void allowAppsFor(Context c, Intent intent) {
        if (!isDeviceOwner(c) || intent == null) return;
        Set<String> pkgs = new LinkedHashSet<>();
        pkgs.add(c.getPackageName());
        List<ResolveInfo> matches = c.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo ri : matches) {
            if (ri.activityInfo != null) pkgs.add(ri.activityInfo.packageName);
        }
        run("allow apps", () -> dpm(c).setLockTaskPackages(admin(c), pkgs.toArray(new String[0])));
    }

    /** Pauses the kiosk so the tablet behaves normally for a while. */
    static void suspendForMaintenance(Activity a) {
        if (isDeviceOwner(a)) {
            run("release home", () -> setKioskAsHome(a, false));
            run("status bar", () -> dpm(a).setStatusBarDisabled(admin(a), false));
        }
        try {
            a.stopLockTask();
        } catch (Exception e) {
            Log.w(TAG, "stopLockTask failed", e);
        }
    }

    /** Removes all kiosk control so the tablet can be repurposed, the app uninstalled, or the tablet reset. */
    @SuppressWarnings("deprecation") // clearDeviceOwnerApp is still the only self-release route for a DPC
    static void releaseDevice(Activity a) {
        if (!isDeviceOwner(a)) {
            try {
                a.stopLockTask();
            } catch (Exception ignored) {
            }
            return;
        }
        DevicePolicyManager d = dpm(a);
        ComponentName ad = admin(a);
        for (String r : RESTRICTIONS) {
            run(r, () -> d.clearUserRestriction(ad, r));
        }
        run("usb debugging", () -> d.clearUserRestriction(ad, UserManager.DISALLOW_DEBUGGING_FEATURES));
        run("status bar", () -> d.setStatusBarDisabled(ad, false));
        run("keyguard", () -> d.setKeyguardDisabled(ad, false));
        run("screen capture", () -> d.setScreenCaptureDisabled(ad, false));
        run("update policy", () -> d.setSystemUpdatePolicy(ad, null));
        run("stay on", () -> d.setGlobalSetting(ad, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0"));
        run("home", () -> setKioskAsHome(a, false));
        try {
            a.stopLockTask();
        } catch (Exception ignored) {
        }
        run("lock task packages", () -> d.setLockTaskPackages(ad, new String[0]));
        run("device owner", () -> d.clearDeviceOwnerApp(a.getPackageName()));
    }

    /** Makes (or stops making) the kiosk the tablet's home screen. */
    static void setKioskAsHome(Context c, boolean enable) {
        ComponentName alias = homeAlias(c);
        PackageManager pm = c.getPackageManager();
        int wanted = enable ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        if (pm.getComponentEnabledSetting(alias) != wanted) {
            pm.setComponentEnabledSetting(alias, wanted, PackageManager.DONT_KILL_APP);
        }
        if (!isDeviceOwner(c)) return;
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        d.clearPackagePersistentPreferredActivities(a, c.getPackageName());
        if (enable) {
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_HOME);
            f.addCategory(Intent.CATEGORY_DEFAULT);
            d.addPersistentPreferredActivity(a, f, alias);
        }
    }

    /** The tablet's normal launcher, so maintenance mode has somewhere to go. */
    static Intent systemHomeIntent(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            // Skip ourselves and the Settings "FallbackHome" placeholder.
            if (c.getPackageName().equals(pkg) || "com.android.settings".equals(pkg)) continue;
            return new Intent(home)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }

    static void launchKiosk(Context c) {
        Intent i = new Intent(c, KioskActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "Could not launch kiosk", e);
        }
    }

    private static ComponentName homeAlias(Context c) {
        return new ComponentName(c.getPackageName(), c.getPackageName() + ".HomeAlias");
    }

    private static void run(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            // Some tablets don't support every policy; carry on with the rest.
            Log.w(TAG, "Policy step failed: " + what, t);
        }
    }
}
