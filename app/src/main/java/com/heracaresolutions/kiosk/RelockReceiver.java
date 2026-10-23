package com.heracaresolutions.kiosk;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Ends "Exit kiosk for 30 minutes" automatically. */
public class RelockReceiver extends BroadcastReceiver {

    static void schedule(Context context, long atMillis) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am != null) am.set(AlarmManager.RTC_WAKEUP, atMillis, pending(context));
    }

    static void cancel(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(pending(context));
    }

    private static PendingIntent pending(Context context) {
        return PendingIntent.getBroadcast(context, 7, new Intent(context, RelockReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Prefs prefs = Prefs.get(context);
        if (prefs.inMaintenance()) {
            schedule(context, prefs.maintenanceUntil());
            return;
        }
        KioskPolicy.applyAll(context);
        KioskPolicy.launchKiosk(context);
    }
}
