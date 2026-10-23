package com.heracaresolutions.kiosk;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;

/** Device admin entry point. Becomes Device Owner via QR provisioning or `adb shell dpm set-device-owner`. */
public class KioskAdminReceiver extends DeviceAdminReceiver {
    @SuppressWarnings("deprecation")
    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        PersistableBundle extras = intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE);
        Prefs.get(context).applyProvisioningExtras(extras);
        KioskPolicy.applyAll(context);
        KioskPolicy.launchKiosk(context);
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        // Also covers the adb route, where no provisioning broadcast is sent.
        KioskPolicy.applyAll(context);
    }
}
