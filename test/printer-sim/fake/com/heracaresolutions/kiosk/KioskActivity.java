package com.heracaresolutions.kiosk;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for the kiosk screen, used only to drive KioskBridge. Mirrors the
 * real isHeraPage()/sendEvent() rules: events reach the page only while the page
 * showing is the Hera site (the real code re-checks on delivery, as here).
 */
public class KioskActivity extends android.content.Context {
    public volatile String pageHost = "hera.example.local";
    public final String heraHost = "hera.example.local";
    public final List<String> events = new CopyOnWriteArrayList<>();
    public final List<String> blockedEvents = new CopyOnWriteArrayList<>();

    boolean isHeraPage() {
        return heraHost.equals(pageHost);
    }

    void sendEvent(String name, String... args) {
        String call = name + "(" + String.join(",", args) + ")";
        if (isHeraPage()) events.add(call);
        else blockedEvents.add(call);
    }

    public void runOnUiThread(Runnable r) {
        new android.os.Handler().post(r);
    }
}
