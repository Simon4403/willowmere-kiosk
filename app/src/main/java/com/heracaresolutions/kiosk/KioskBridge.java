package com.heracaresolutions.kiosk;

import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * window.HeraKiosk: what the Hera page can ask of the tablet. Printing only.
 *
 * Every call checks that the page asking is the Hera site the kiosk was set up
 * with; any other page (an allowed extra site, a sign-in page) gets nothing.
 * Answers that take time come back as calls to window.HeraKioskEvents.
 */
final class KioskBridge {
    static final int API_VERSION = 1;
    private static final int MAX_PDF_BYTES = 20 * 1024 * 1024;

    private final KioskActivity activity;
    private final Prefs prefs;

    KioskBridge(KioskActivity activity) {
        this.activity = activity;
        this.prefs = Prefs.get(activity);
    }

    @JavascriptInterface
    public int apiVersion() {
        return activity.isHeraPage() ? API_VERSION : 0;
    }

    /** The printer this tablet prints to, as JSON, or "{}". */
    @JavascriptInterface
    public String printerInfo() {
        if (!activity.isHeraPage()) return "{}";
        JSONObject p = prefs.printer();
        return p == null ? "{}" : p.toString();
    }

    /** Looks for printers on the Wi-Fi. Results: HeraKioskEvents.printers(json), possibly several times, the last with done: true. */
    @JavascriptInterface
    public void discover() {
        if (!activity.isHeraPage()) return;
        activity.runOnUiThread(() -> PrinterService.discover(activity, this::sendPrinters));
    }

    /** Checks a printer address typed by hand. Result: HeraKioskEvents.printers(json). */
    @JavascriptInterface
    public void probe(String address) {
        if (!activity.isHeraPage()) return;
        PrinterService.probe(address, this::sendPrinters);
    }

    /** Makes a printer (as returned by discover/probe) this tablet's printer. Returns {"ok", "printer"} or {"ok": false, "error"}. */
    @JavascriptInterface
    public String choosePrinter(String json) {
        JSONObject out = new JSONObject();
        try {
            if (!activity.isHeraPage()) return out.put("ok", false).put("error", "Not allowed").toString();
            JSONObject p = new JSONObject(json);
            String uri = p.optString("uri");
            String host = java.net.URI.create(uri).getHost();
            if (!(uri.startsWith("ipp://") || uri.startsWith("ipps://")) || !Ipp.isLocalHost(host)) {
                return out.put("ok", false).put("error", "That isn't a printer on this tablet's network.").toString();
            }
            JSONObject keep = new JSONObject();
            for (String k : new String[]{"name", "uri", "id", "makeModel", "location", "address", "format", "secure"}) {
                if (p.has(k)) keep.put(k, p.get(k));
            }
            prefs.setPrinter(keep);
            return out.put("ok", true).put("printer", keep).toString();
        } catch (Exception e) {
            try {
                return out.put("ok", false).put("error", "That printer couldn't be used.").toString();
            } catch (JSONException ignored) {
                return "{\"ok\":false}";
            }
        }
    }

    /** The chosen printer's state. Result: HeraKioskEvents.printerState(json). */
    @JavascriptInterface
    public void checkPrinter() {
        if (!activity.isHeraPage()) return;
        JSONObject p = prefs.printer();
        if (p == null) return;
        new Thread(() -> {
            JSONObject s = PrinterService.state(p.optString("uri"));
            activity.sendEvent("printerState", JSONObject.quote(s.toString()));
        }, "printer-state").start();
    }

    /** Prints a PDF on the chosen printer. Progress: HeraKioskEvents.printStatus(jobId, status, detail). */
    @JavascriptInterface
    public void print(String jobId, String title, String pdfBase64, int copies) {
        if (!activity.isHeraPage()) return;
        byte[] pdf = decode(pdfBase64);
        JSONObject p = prefs.printer();
        if (pdf == null) {
            status(jobId, "Failed", "The tablet didn't receive the document.");
            return;
        }
        if (p == null) {
            status(jobId, "Failed", "No printer chosen on this tablet.");
            return;
        }
        PrinterService.print(activity, p, pdf, title == null ? "Hera" : title, Math.max(1, Math.min(10, copies)),
                (s, d) -> status(jobId, s, d));
    }

    /** Prints a PDF using Android's own print screen. */
    @JavascriptInterface
    public void printWithDialog(String jobId, String title, String pdfBase64) {
        if (!activity.isHeraPage()) return;
        byte[] pdf = decode(pdfBase64);
        if (pdf == null) {
            status(jobId, "Failed", "The tablet didn't receive the document.");
            return;
        }
        activity.runOnUiThread(() -> PrinterService.printWithDialog(activity, pdf, title == null ? "Hera" : title,
                (s, d) -> status(jobId, s, d)));
    }

    // ---------------------------------------------------------------------

    private byte[] decode(String b64) {
        if (b64 == null || b64.length() > MAX_PDF_BYTES * 4 / 3 + 8) return null;
        try {
            byte[] pdf = Base64.decode(b64, Base64.DEFAULT);
            return pdf.length > 4 && pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F' ? pdf : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void status(String jobId, String status, String detail) {
        activity.sendEvent("printStatus", JSONObject.quote(jobId == null ? "" : jobId), JSONObject.quote(status),
                JSONObject.quote(detail == null ? "" : detail));
    }

    private void sendPrinters(List<JSONObject> printers, boolean done, String error) {
        JSONObject o = new JSONObject();
        try {
            o.put("printers", new JSONArray(printers));
            o.put("done", done);
            if (error != null) o.put("error", error);
        } catch (JSONException ignored) {
        }
        activity.sendEvent("printers", JSONObject.quote(o.toString()));
    }
}
