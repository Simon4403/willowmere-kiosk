package com.heracaresolutions.kiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PersistableBundle;
import android.text.TextUtils;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** All kiosk settings, stored privately on the tablet. */
final class Prefs {
    private static final String FILE = "kiosk_settings";

    private static final String START_URL = "start_url";
    private static final String ALLOWED_HOSTS = "allowed_hosts";
    private static final String UPDATE_URL = "update_url";
    private static final String PIN_HASH = "pin_hash";
    private static final String PIN_SALT = "pin_salt";
    private static final String KEEP_SCREEN_ON = "keep_screen_on";
    private static final String ALLOW_UPLOADS = "allow_uploads";
    private static final String BLOCK_USB_DEBUG = "block_usb_debug";
    private static final String IDLE_MINUTES = "idle_minutes";
    private static final String BRIGHTNESS = "brightness";
    private static final String MAINTENANCE_UNTIL = "maintenance_until";
    private static final String FAILED_ATTEMPTS = "failed_attempts";
    private static final String LOCKOUT_UNTIL = "lockout_until";
    private static final String RELOAD_PENDING = "reload_pending";
    private static final String CLEAR_CACHE_PENDING = "clear_cache_pending";

    static final int MIN_PIN_LENGTH = 6;
    static final int MAX_PIN_LENGTH = 12;
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_MS = 5 * 60_000L;
    private static final int PBKDF2_ITERATIONS = 50_000;

    private final SharedPreferences sp;

    private Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static Prefs get(Context context) {
        return new Prefs(context);
    }

    // ---------------------------------------------------------------- URLs

    String startUrl() {
        String v = sp.getString(START_URL, null);
        return TextUtils.isEmpty(v) ? BuildConfig.DEFAULT_START_URL : v;
    }

    void setStartUrl(String url) {
        sp.edit().putString(START_URL, url.trim()).apply();
    }

    String allowedHostsRaw() {
        return sp.getString(ALLOWED_HOSTS, "");
    }

    void setAllowedHosts(String raw) {
        sp.edit().putString(ALLOWED_HOSTS, raw == null ? "" : raw.trim()).apply();
    }

    String updateUrl() {
        String v = sp.getString(UPDATE_URL, null);
        return TextUtils.isEmpty(v) ? BuildConfig.DEFAULT_UPDATE_URL : v;
    }

    void setUpdateUrl(String url) {
        sp.edit().putString(UPDATE_URL, url == null ? "" : url.trim()).apply();
    }

    /** The start page's site is always allowed; extra sites come from the admin menu. */
    List<String> allowedHosts() {
        List<String> out = new ArrayList<>();
        String startHost = Uri.parse(startUrl()).getHost();
        if (startHost != null) out.add(startHost.toLowerCase(Locale.ROOT));

        for (String part : allowedHostsRaw().split("[,;\\s]+")) {
            String h = part.trim().toLowerCase(Locale.ROOT);
            if (h.isEmpty()) continue;
            if (h.contains("://")) {
                String parsed = Uri.parse(h).getHost();
                if (parsed == null) continue;
                h = parsed;
            }
            int slash = h.indexOf('/');
            if (slash >= 0) h = h.substring(0, slash);
            if (h.startsWith("*.")) h = h.substring(2);
            if (!h.isEmpty() && !out.contains(h)) out.add(h);
        }
        return out;
    }

    /** True if the kiosk may navigate to this address (the site itself or one of its sub-domains). */
    boolean isAllowed(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "about":
            case "data":
            case "blob":
            case "javascript":
                return true;
            case "https":
            case "http":
                break;
            default:
                return false; // tel:, mailto:, intent:, market: etc. would leave the kiosk
        }
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String allowed : allowedHosts()) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------- Options

    boolean keepScreenOn() {
        return sp.getBoolean(KEEP_SCREEN_ON, true);
    }

    void setKeepScreenOn(boolean v) {
        sp.edit().putBoolean(KEEP_SCREEN_ON, v).apply();
    }

    boolean allowUploads() {
        return sp.getBoolean(ALLOW_UPLOADS, true);
    }

    void setAllowUploads(boolean v) {
        sp.edit().putBoolean(ALLOW_UPLOADS, v).apply();
    }

    boolean blockUsbDebugging() {
        return sp.getBoolean(BLOCK_USB_DEBUG, true);
    }

    void setBlockUsbDebugging(boolean v) {
        sp.edit().putBoolean(BLOCK_USB_DEBUG, v).apply();
    }

    /**
     * Idle minutes: the kiosk returns to the start page, and Hera locks to the
     * staff member's PIN, after this long without a touch. 0 = the kiosk never
     * returns to the start page and Hera uses its own default (5 minutes).
     */
    int idleMinutes() {
        return sp.getInt(IDLE_MINUTES, 5);
    }

    void setIdleMinutes(int minutes) {
        sp.edit().putInt(IDLE_MINUTES, Math.max(0, minutes)).apply();
    }

    /** Screen brightness set with the volume buttons, 0.05 to 1. Negative = the tablet's own setting. */
    float brightness() {
        return sp.getFloat(BRIGHTNESS, -1f);
    }

    void setBrightness(float v) {
        sp.edit().putFloat(BRIGHTNESS, v).apply();
    }

    // ----------------------------------------------------------------- PIN

    boolean hasPin() {
        return sp.contains(PIN_HASH);
    }

    static boolean isValidPin(String pin) {
        return pin != null && pin.matches("\\d{" + MIN_PIN_LENGTH + "," + MAX_PIN_LENGTH + "}");
    }

    void setPin(String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        sp.edit()
                .putString(PIN_SALT, b64(salt))
                .putString(PIN_HASH, b64(hash(pin, salt)))
                .putInt(FAILED_ATTEMPTS, 0)
                .putLong(LOCKOUT_UNTIL, 0)
                .apply();
    }

    boolean checkPin(String pin) {
        if (pin == null || pin.isEmpty() || !hasPin()) return false;
        byte[] salt = Base64.decode(sp.getString(PIN_SALT, ""), Base64.NO_WRAP);
        byte[] expected = Base64.decode(sp.getString(PIN_HASH, ""), Base64.NO_WRAP);
        return MessageDigest.isEqual(expected, hash(pin, salt));
    }

    long lockoutRemainingMs() {
        long left = sp.getLong(LOCKOUT_UNTIL, 0) - System.currentTimeMillis();
        if (left > LOCKOUT_MS) left = LOCKOUT_MS; // guard against the clock being wound back
        return Math.max(0, left);
    }

    void recordFailure() {
        int n = sp.getInt(FAILED_ATTEMPTS, 0) + 1;
        SharedPreferences.Editor e = sp.edit();
        if (n >= MAX_ATTEMPTS) {
            e.putInt(FAILED_ATTEMPTS, 0).putLong(LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_MS);
        } else {
            e.putInt(FAILED_ATTEMPTS, n);
        }
        e.apply();
    }

    int attemptsLeft() {
        return MAX_ATTEMPTS - sp.getInt(FAILED_ATTEMPTS, 0);
    }

    void clearFailures() {
        sp.edit().putInt(FAILED_ATTEMPTS, 0).putLong(LOCKOUT_UNTIL, 0).apply();
    }

    private static byte[] hash(String pin, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PIN hashing unavailable", e);
        }
    }

    // ---------------------------------------------------------- Maintenance

    void startMaintenance(long durationMs) {
        sp.edit().putLong(MAINTENANCE_UNTIL, System.currentTimeMillis() + durationMs).commit();
    }

    long maintenanceUntil() {
        return sp.getLong(MAINTENANCE_UNTIL, 0);
    }

    boolean inMaintenance() {
        return maintenanceUntil() > System.currentTimeMillis();
    }

    void endMaintenance() {
        if (sp.getLong(MAINTENANCE_UNTIL, 0) != 0) sp.edit().putLong(MAINTENANCE_UNTIL, 0).apply();
    }

    // -------------------------------------------- Messages to the kiosk page

    void requestReload(boolean clearCache) {
        SharedPreferences.Editor e = sp.edit().putBoolean(RELOAD_PENDING, true);
        if (clearCache) e.putBoolean(CLEAR_CACHE_PENDING, true);
        e.apply();
    }

    boolean takeReloadRequest() {
        boolean v = sp.getBoolean(RELOAD_PENDING, false);
        if (v) sp.edit().putBoolean(RELOAD_PENDING, false).apply();
        return v;
    }

    boolean takeClearCacheRequest() {
        boolean v = sp.getBoolean(CLEAR_CACHE_PENDING, false);
        if (v) sp.edit().putBoolean(CLEAR_CACHE_PENDING, false).apply();
        return v;
    }

    // ------------------------------------------------------- Provisioning

    /** Settings carried in the provisioning QR code ("admin extras bundle"). */
    void applyProvisioningExtras(PersistableBundle extras) {
        if (extras == null) return;
        SharedPreferences.Editor e = sp.edit();
        String start = extras.getString("start_url");
        if (!TextUtils.isEmpty(start)) e.putString(START_URL, start.trim());
        String hosts = extras.getString("allowed_hosts");
        if (hosts != null) e.putString(ALLOWED_HOSTS, hosts.trim());
        String update = extras.getString("update_url");
        if (!TextUtils.isEmpty(update)) e.putString(UPDATE_URL, update.trim());
        e.commit();
    }

    /** Accepts "willowmere.example.com" or a full link; returns a clean https URL or null. */
    static String normaliseUrl(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (u.isEmpty()) return null;
        if (!u.contains("://")) u = "https://" + u;
        Uri parsed = Uri.parse(u);
        if (!"https".equalsIgnoreCase(parsed.getScheme()) || TextUtils.isEmpty(parsed.getHost())) return null;
        return u;
    }

    private static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
