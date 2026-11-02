package com.heracaresolutions.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Result of an update install. On a successful self-update the app restarts via BootReceiver. */
public class InstallResultReceiver extends BroadcastReceiver {
    @SuppressWarnings("deprecation")
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Only happens when the app isn't device owner: Android wants a confirmation tap.
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                KioskPolicy.allowAppsFor(context, confirm);
                try {
                    context.startActivity(confirm);
                } catch (Exception ignored) {
                }
            }
            return;
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            int versionCode = intent.getIntExtra(UpdateInstaller.EXTRA_VERSION_CODE, 0);
            // Don't keep retrying a version Android rejected (see AutoUpdater's back-off).
            if (versionCode > 0) Prefs.get(context).recordUpdateFailure(versionCode);
            Toast.makeText(context, "Update failed: " + message, Toast.LENGTH_LONG).show();
            // Tell the kiosk so it removes the "Updating…" screen straight away.
            Intent back = new Intent(context, KioskActivity.class)
                    .setAction(KioskActivity.ACTION_UPDATE_FAILED)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                context.startActivity(back);
            } catch (Exception ignored) {
            }
        }
    }
}
