package com.heracaresolutions.kiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * PIN-protected admin menu. Also doubles as the first-run set-up screen when no PIN exists yet.
 * Closes itself after 3 minutes without a touch so it's never left open for staff.
 */
public class AdminActivity extends Activity {
    private static final long AUTO_CLOSE_MS = 3 * 60_000L;
    private static final long SYSTEM_SCREEN_LIMIT_MS = 5 * 60_000L;
    private static final long MAINTENANCE_MS = 30 * 60_000L;

    private static final String INK = "#1F3D33";
    private static final String MUTED = "#4A5A54";
    private static final String PAPER = "#F4F1EA";
    private static final String DANGER = "#B3261E";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoClose = this::finish;
    /** If Wi-Fi/Android settings are left open, snap back to the kiosk. */
    private final Runnable returnToKiosk = () -> KioskPolicy.launchKiosk(this);

    private Prefs prefs;
    private boolean setupMode;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        setupMode = !prefs.hasPin();
        if (setupMode) {
            buildSetup();
        } else {
            buildAdmin();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        KioskPolicy.restoreLockTask(this);
        handler.removeCallbacks(returnToKiosk);
        armAutoClose();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(autoClose);
        super.onPause();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        armAutoClose();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (setupMode) {
            toast("Set an admin PIN first.");
            return;
        }
        super.onBackPressed();
    }

    private void armAutoClose() {
        handler.removeCallbacks(autoClose);
        if (!setupMode) handler.postDelayed(autoClose, AUTO_CLOSE_MS);
    }

    // ---------------------------------------------------------- First run

    private void buildSetup() {
        buildShell("Set up Willowmere Kiosk",
                "Choose an admin PIN of " + Prefs.MIN_PIN_LENGTH + " to " + Prefs.MAX_PIN_LENGTH
                        + " digits. Keep it to yourself: it's the only way out of the kiosk.\n\n"
                        + "To open the admin menu later, tap the top-left corner of the screen 7 times quickly.");

        EditText pin = field("", "New PIN", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        EditText confirm = field("", "Confirm PIN", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        EditText url = field(prefs.startUrl(), "https://…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        content.addView(label("Admin PIN"));
        content.addView(pin);
        content.addView(confirm);
        content.addView(label("Willowmere address"));
        content.addView(url);
        content.addView(status());

        content.addView(button("Save and start kiosk", false, v -> {
            String p1 = pin.getText().toString();
            String p2 = confirm.getText().toString();
            if (!Prefs.isValidPin(p1)) {
                pin.setError("Use " + Prefs.MIN_PIN_LENGTH + "–" + Prefs.MAX_PIN_LENGTH + " digits");
                return;
            }
            if (!p1.equals(p2)) {
                confirm.setError("PINs don't match");
                return;
            }
            String start = Prefs.normaliseUrl(url.getText().toString());
            if (start == null) {
                url.setError("Enter an https:// address");
                return;
            }
            prefs.setPin(p1);
            prefs.setStartUrl(start);
            prefs.requestReload(false);
            toast("Saved. Kiosk is now locked.");
            finish();
        }));
    }

    // ---------------------------------------------------------- Admin menu

    private void buildAdmin() {
        buildShell("Kiosk admin", null);
        content.addView(status());

        boolean owner = KioskPolicy.isDeviceOwner(this);

        // --- Kiosk page settings
        content.addView(section("Kiosk page"));
        content.addView(label("Willowmere address"));
        EditText url = field(prefs.startUrl(), "https://…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        content.addView(url);

        content.addView(label("Other sites the kiosk may open (comma separated)"));
        EditText hosts = field(prefs.allowedHostsRaw(), "e.g. login.microsoftonline.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        content.addView(hosts);
        content.addView(hint("The Willowmere site and its sub-domains are always allowed. Anything else is blocked."));

        content.addView(label("Idle minutes: Hera locks to the staff PIN and the kiosk returns to the start page after this long untouched (0 = Hera's default of 5)"));
        EditText idle = field(String.valueOf(prefs.idleMinutes()), "0", InputType.TYPE_CLASS_NUMBER);
        content.addView(idle);
        content.addView(hint("Leave at 0 unless Willowmere saves work automatically: returning to the start page discards anything unsaved."));

        Switch screenOn = toggle("Keep the screen on", prefs.keepScreenOn());
        Switch uploads = toggle("Allow file uploads (photos, documents)", prefs.allowUploads());
        Switch usb = toggle("Block USB debugging", prefs.blockUsbDebugging());
        usb.setEnabled(owner);
        content.addView(screenOn);
        content.addView(uploads);
        content.addView(usb);

        content.addView(button("Save kiosk settings", false, v -> {
            String start = Prefs.normaliseUrl(url.getText().toString());
            if (start == null) {
                url.setError("Enter an https:// address");
                return;
            }
            int minutes;
            try {
                minutes = Integer.parseInt(idle.getText().toString().trim());
            } catch (NumberFormatException e) {
                minutes = 0;
            }
            prefs.setStartUrl(start);
            prefs.setAllowedHosts(hosts.getText().toString());
            prefs.setIdleMinutes(minutes);
            prefs.setKeepScreenOn(screenOn.isChecked());
            prefs.setAllowUploads(uploads.isChecked());
            prefs.setBlockUsbDebugging(usb.isChecked());
            prefs.requestReload(false);
            KioskPolicy.applyAll(this);
            toast("Saved.");
        }));

        // --- Tablet
        content.addView(section("Tablet"));
        content.addView(button("Wi-Fi settings", false, v -> openSystemScreen(Settings.ACTION_WIFI_SETTINGS)));
        content.addView(button("All Android settings", false, v -> openSystemScreen(Settings.ACTION_SETTINGS)));
        content.addView(hint("Press Back to return here. Settings close automatically after 5 minutes."));
        content.addView(button("Reload Willowmere", false, v -> {
            prefs.requestReload(false);
            finish();
        }));
        content.addView(button("Clear browsing data (signs everyone out)", false, v -> confirm(
                "Clear browsing data?",
                "Removes saved logins, cookies and cached pages on this tablet.",
                "Clear", () -> {
                    WebStorage.getInstance().deleteAllData();
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                    prefs.requestReload(true);
                    toast("Browsing data cleared.");
                    finish();
                })));
        content.addView(button("Change admin PIN", false, v -> changePin()));
        content.addView(button("Install kiosk app update", false, v -> promptUpdate()));

        // --- Leaving the kiosk
        content.addView(section("Leave kiosk"));
        content.addView(button("Exit kiosk for 30 minutes", false, v -> confirm(
                "Exit kiosk for 30 minutes?",
                "The tablet works normally until the kiosk relocks itself in 30 minutes, the tablet restarts, or you open Willowmere Kiosk again.",
                "Exit", this::enterMaintenance)));
        content.addView(button("Remove kiosk control from this tablet", true, v -> confirmRelease()));
        content.addView(hint("Use this before giving the tablet a different job, uninstalling the app, or factory resetting it."));

        content.addView(button("Close admin menu", false, v -> finish()));
    }

    private void openSystemScreen(String action) {
        Intent intent = new Intent(action);
        KioskPolicy.allowAppsFor(this, intent);
        try {
            startActivity(intent);
            handler.postDelayed(returnToKiosk, SYSTEM_SCREEN_LIMIT_MS);
        } catch (ActivityNotFoundException e) {
            KioskPolicy.restoreLockTask(this);
            toast("Not available on this tablet.");
        }
    }

    private void enterMaintenance() {
        prefs.startMaintenance(MAINTENANCE_MS);
        RelockReceiver.schedule(this, prefs.maintenanceUntil());
        KioskPolicy.suspendForMaintenance(this);
        toast("Kiosk paused for 30 minutes.");
        Intent home = KioskPolicy.systemHomeIntent(this);
        try {
            startActivity(home != null ? home : new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
        finish();
    }

    private void confirmRelease() {
        EditText typed = field("", "Type REMOVE", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Remove kiosk control?")
                .setMessage("This turns off every kiosk protection on this tablet. To lock it again you'll need to factory reset and scan the set-up QR code.\n\nType REMOVE to confirm.")
                .setView(padded(typed))
                .setPositiveButton("Remove", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!"REMOVE".equals(typed.getText().toString().trim())) {
                typed.setError("Type REMOVE");
                return;
            }
            dialog.dismiss();
            prefs.startMaintenance(365L * 24 * 60 * 60_000L);
            KioskPolicy.releaseDevice(this);
            toast("Kiosk control removed.");
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
            finishAffinity();
        }));
        dialog.show();
    }

    private void changePin() {
        EditText p1 = field("", "New PIN", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        EditText p2 = field("", "Confirm new PIN", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(p1);
        box.addView(p2);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Change admin PIN")
                .setView(padded(box))
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = p1.getText().toString();
            if (!Prefs.isValidPin(a)) {
                p1.setError("Use " + Prefs.MIN_PIN_LENGTH + "–" + Prefs.MAX_PIN_LENGTH + " digits");
                return;
            }
            if (!a.equals(p2.getText().toString())) {
                p2.setError("PINs don't match");
                return;
            }
            prefs.setPin(a);
            dialog.dismiss();
            toast("PIN changed.");
        }));
        dialog.show();
    }

    private void promptUpdate() {
        EditText url = field(prefs.updateUrl(), "https://…/willowmere-kiosk.apk", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle("Install kiosk app update")
                .setMessage("Downloads the app from this link and installs it. The kiosk restarts by itself when it's done.")
                .setView(padded(url))
                .setPositiveButton("Download and install", (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (!u.startsWith("https://")) {
                        toast("Use an https:// link.");
                        return;
                    }
                    prefs.setUpdateUrl(u);
                    toast("Downloading update…");
                    UpdateInstaller.install(this, u, message -> runOnUiThread(() -> toast(message)));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------ UI bits

    private void buildShell(String title, String subtitle) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor(PAPER));

        FrameLayout frame = new FrameLayout(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        content.setPadding(pad, pad, pad, dp(48));

        int width = Math.min(getResources().getDisplayMetrics().widthPixels, dp(720));
        frame.addView(content, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        scroll.addView(frame);
        setContentView(scroll);

        TextView t = text(title, 28, true, INK);
        content.addView(t);
        if (subtitle != null) {
            TextView s = text(subtitle, 16, false, MUTED);
            s.setPadding(0, dp(8), 0, dp(8));
            content.addView(s);
        }
    }

    private TextView status() {
        boolean owner = KioskPolicy.isDeviceOwner(this);
        String protection = owner
                ? "Protection: full (device owner)"
                : "Protection: basic (screen pinning). Set the tablet up with the QR code for full lockdown.";
        TextView v = text("Version " + BuildConfig.VERSION_NAME
                + "  ·  Android " + Build.VERSION.RELEASE
                + "  ·  " + Build.MANUFACTURER + " " + Build.MODEL
                + "\n" + protection, 14, false, owner ? MUTED : DANGER);
        v.setPadding(0, dp(8), 0, dp(8));
        return v;
    }

    private TextView section(String title) {
        TextView v = text(title, 20, true, INK);
        v.setPadding(0, dp(28), 0, dp(4));
        return v;
    }

    private TextView label(String s) {
        TextView v = text(s, 15, true, INK);
        v.setPadding(0, dp(14), 0, dp(2));
        return v;
    }

    private TextView hint(String s) {
        TextView v = text(s, 13, false, MUTED);
        v.setPadding(0, dp(2), 0, dp(4));
        return v;
    }

    private TextView text(String s, int sp, boolean bold, String color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(Color.parseColor(color));
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private EditText field(String value, String hint, int inputType) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        return e;
    }

    private Switch toggle(String label, boolean checked) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setChecked(checked);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setTextColor(Color.parseColor(INK));
        s.setPadding(0, dp(10), 0, dp(10));
        return s;
    }

    private Button button(String label, boolean danger, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        if (danger) {
            b.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(DANGER)));
            b.setTextColor(Color.WHITE);
        }
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View padded(View v) {
        FrameLayout wrap = new FrameLayout(this);
        int pad = dp(24);
        wrap.setPadding(pad, dp(8), pad, 0);
        wrap.addView(v);
        return wrap;
    }

    private void confirm(String title, String message, String ok, Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(ok, (d, w) -> action.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
