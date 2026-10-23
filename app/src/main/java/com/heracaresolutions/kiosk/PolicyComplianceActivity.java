package com.heracaresolutions.kiosk;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.os.Bundle;
import android.os.PersistableBundle;

/** Called at the end of Android 12+ provisioning: save the QR settings and apply the lockdown. */
public class PolicyComplianceActivity extends Activity {
    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PersistableBundle extras = getIntent().getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE);
        Prefs.get(this).applyProvisioningExtras(extras);
        KioskPolicy.applyAll(this);
        setResult(RESULT_OK);
        finish();
    }
}
