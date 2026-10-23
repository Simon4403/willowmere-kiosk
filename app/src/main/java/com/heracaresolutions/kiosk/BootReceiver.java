package com.heracaresolutions.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart or an app update, end any maintenance window and bring the kiosk back. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!KioskPolicy.isDeviceOwner(context)) return; // not a managed kiosk tablet
        Prefs.get(context).endMaintenance();
        KioskPolicy.applyAll(context);
        KioskPolicy.launchKiosk(context);
    }
}
