package com.heracaresolutions.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Wakes UpdateScheduler for its 4-hourly check and to install a waiting update once on charge. */
public class UpdateAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        UpdateScheduler.tick(context, null, false);
    }
}
