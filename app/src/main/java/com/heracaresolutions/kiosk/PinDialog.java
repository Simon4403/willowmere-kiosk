package com.heracaresolutions.kiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

/** Asks for the admin PIN. 5 wrong tries locks the prompt for 5 minutes. */
final class PinDialog {
    interface OnUnlocked {
        void onUnlocked();
    }

    private static final long AUTO_DISMISS_MS = 45_000;

    private PinDialog() {
    }

    static void show(Activity activity, String title, OnUnlocked onUnlocked) {
        Prefs prefs = Prefs.get(activity);
        long locked = prefs.lockoutRemainingMs();
        if (locked > 0) {
            long minutes = (locked + 59_999) / 60_000;
            Toast.makeText(activity, "Too many wrong PINs. Try again in " + minutes + " min.", Toast.LENGTH_LONG).show();
            return;
        }

        EditText pin = new EditText(activity);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        NoEchoMask.apply(pin);                         // no flash of the last digit
        pin.setHint("Admin PIN");
        pin.setSingleLine(true);

        FrameLayout wrap = new FrameLayout(activity);
        int pad = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(pin);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("Unlock", null)
                .setNegativeButton("Cancel", null)
                .create();

        Handler handler = new Handler(Looper.getMainLooper());
        Runnable timeout = dialog::dismiss;

        dialog.setOnShowListener(d -> {
            pin.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (prefs.checkPin(pin.getText().toString())) {
                    prefs.clearFailures();
                    dialog.dismiss();
                    onUnlocked.onUnlocked();
                    return;
                }
                prefs.recordFailure();
                pin.setText("");
                if (prefs.lockoutRemainingMs() > 0) {
                    dialog.dismiss();
                    Toast.makeText(activity, "Too many wrong PINs. Locked for 5 minutes.", Toast.LENGTH_LONG).show();
                } else {
                    pin.setError("Wrong PIN. " + prefs.attemptsLeft() + " tries left.");
                }
            });
        });
        dialog.setOnDismissListener(d -> handler.removeCallbacks(timeout));
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
        handler.postDelayed(timeout, AUTO_DISMISS_MS);
    }
}
