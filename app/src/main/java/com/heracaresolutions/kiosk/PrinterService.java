package com.heracaresolutions.kiosk;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintJob;
import android.print.PrintManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Network printing for the kiosk.
 *
 *  - Finding printers: Bonjour/mDNS ("_ipp._tcp", "_ipps._tcp"), the way phones
 *    and laptops find them, or by IP address.
 *  - Printing: IPP straight to the chosen printer. PDF if the printer takes it,
 *    otherwise PWG Raster (every IPP Everywhere / Mopria printer takes that),
 *    rendered on the tablet at A4. The job is followed until the printer says it
 *    has printed, failed (and why), or was cancelled.
 *  - If neither works, Android's own print screen, which uses whatever print
 *    service the tablet has (Mopria, the maker's plug-in).
 *
 * Only printers on the tablet's own network are ever used (see Ipp.isLocalHost).
 */
final class PrinterService {
    private static final String TAG = "Printer";
    private static final long DISCOVERY_MS = 8_000;
    private static final long FOLLOW_MS = 3 * 60_000;
    private static final String PRINT_SPOOLER = "com.android.printspooler";

    interface Status {
        /** status: "Sent to printer", "Printed", "Failed", "Cancelled". */
        void update(String status, String detail);
    }

    interface Found {
        /** Called on the main thread with printers as they're found; done once at the end. */
        void found(List<JSONObject> printers, boolean done, String error);
    }

    private static final ExecutorService JOBS = Executors.newSingleThreadExecutor();
    private static final ExecutorService PROBES = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PrinterService() {
    }

    // ----------------------------------------------------------- describe

    /** Asks a printer what it is and what it takes. Returns its JSON description. Network: call off the main thread. */
    static JSONObject describe(String uri, String fallbackName, String id) throws IOException {
        Ipp.Response r = Ipp.printerAttributes(uri);
        if (!r.ok()) throw new IOException(Ipp.statusText(r.status));
        JSONObject p = new JSONObject();
        try {
            String name = firstNonEmpty(r.text("printer-info"), fallbackName, r.text("printer-name"), r.text("printer-make-and-model"), "Printer");
            p.put("name", name.replace('_', ' '));
            p.put("makeModel", r.text("printer-make-and-model"));
            p.put("location", r.text("printer-location"));
            p.put("uri", uri);
            p.put("address", java.net.URI.create(uri).getHost());
            p.put("secure", uri.startsWith("ipps:"));
            p.put("id", firstNonEmpty(id, r.text("printer-uuid"), uri));
            String format = chooseFormat(r);
            p.put("format", format == null ? "" : format);
            p.put("supported", format != null);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return p;
    }

    /** "application/pdf" if the printer takes PDF, "image/pwg-raster" if it takes PWG Raster we can make, else null. */
    private static String chooseFormat(Ipp.Response r) {
        List<String> formats = r.texts("document-format-supported");
        if (formats.contains("application/pdf")) return "application/pdf";
        if (formats.contains("image/pwg-raster")) {
            List<String> types = r.texts("pwg-raster-document-type-supported");
            if (types.isEmpty() || types.contains("sgray_8") || types.contains("srgb_8")) return "image/pwg-raster";
        }
        return null;
    }

    /** The printer's current state, for the print screen: { reachable, state, reasons, accepting }. */
    static JSONObject state(String uri) {
        JSONObject s = new JSONObject();
        try {
            try {
                Ipp.Response r = Ipp.printerAttributes(uri);
                int st = r.get("printer-state") == null ? 3 : r.get("printer-state").integer(3);
                s.put("reachable", true);
                s.put("state", st == 5 ? "stopped" : st == 4 ? "processing" : "idle");
                s.put("reasons", new JSONArray(r.texts("printer-state-reasons")));
                s.put("accepting", !"false".equals(r.text("printer-is-accepting-jobs")));
            } catch (IOException e) {
                s.put("reachable", false);
            }
        } catch (JSONException ignored) {
        }
        return s;
    }

    // ----------------------------------------------------------- discovery

    /** Looks for printers on the Wi-Fi for a few seconds. */
    static void discover(Context context, Found callback) {
        NsdManager nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            MAIN.post(() -> callback.found(new java.util.ArrayList<>(), true, "This tablet can't search for printers. Add the printer by its IP address."));
            return;
        }
        Discovery d = new Discovery(nsd, callback);
        d.start();
    }

    private static final class Discovery {
        private final NsdManager nsd;
        private final Found callback;
        private final Map<String, JSONObject> byId = new LinkedHashMap<>();
        private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
        private final java.util.List<NsdManager.DiscoveryListener> listeners = new java.util.ArrayList<>();
        private boolean resolving, stopped;
        private int probing;

        Discovery(NsdManager nsd, Found callback) {
            this.nsd = nsd;
            this.callback = callback;
        }

        void start() {
            for (String type : new String[]{"_ipp._tcp.", "_ipps._tcp."}) {
                NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
                    @Override
                    public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                        Log.w(TAG, "discovery failed " + serviceType + " " + errorCode);
                    }

                    @Override
                    public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                    }

                    @Override
                    public void onDiscoveryStarted(String serviceType) {
                    }

                    @Override
                    public void onDiscoveryStopped(String serviceType) {
                    }

                    @Override
                    public void onServiceFound(NsdServiceInfo info) {
                        MAIN.post(() -> {
                            if (stopped) return;
                            toResolve.add(info);
                            resolveNext();
                        });
                    }

                    @Override
                    public void onServiceLost(NsdServiceInfo info) {
                    }
                };
                try {
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l);
                    listeners.add(l);
                } catch (Exception e) {
                    Log.w(TAG, "discoverServices", e);
                }
            }
            MAIN.postDelayed(this::stop, DISCOVERY_MS);
        }

        private void stop() {
            stopped = true;
            for (NsdManager.DiscoveryListener l : listeners) {
                try {
                    nsd.stopServiceDiscovery(l);
                } catch (Exception ignored) {
                }
            }
            listeners.clear();
            maybeDone();
        }

        private void maybeDone() {
            if (stopped && !resolving && probing == 0) {
                callback.found(new java.util.ArrayList<>(byId.values()), true, null);
            }
        }

        // NsdManager resolves one service at a time on older Android versions.
        @SuppressWarnings("deprecation")
        private void resolveNext() {
            if (resolving || toResolve.isEmpty()) {
                maybeDone();
                return;
            }
            resolving = true;
            NsdServiceInfo next = toResolve.poll();
            try {
                nsd.resolveService(next, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        MAIN.post(() -> {
                            resolving = false;
                            resolveNext();
                        });
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        MAIN.post(() -> {
                            resolving = false;
                            onResolved(info);
                            resolveNext();
                        });
                    }
                });
            } catch (Exception e) {
                resolving = false;
                resolveNext();
            }
        }

        @SuppressWarnings("deprecation")
        private void onResolved(NsdServiceInfo info) {
            InetAddress host = info.getHost();
            if (host == null) return;
            String ip = host.getHostAddress();
            if (ip == null) return;
            int pct = ip.indexOf('%');
            if (pct >= 0) ip = ip.substring(0, pct);
            if (!Ipp.isLocalHost(ip)) return;
            boolean secure = info.getServiceType() != null && info.getServiceType().contains("ipps");
            Map<String, byte[]> txt = info.getAttributes();
            String rp = txt(txt, "rp");
            if (rp.isEmpty()) rp = "ipp/print";
            String hostPart = ip.contains(":") ? "[" + ip + "]" : ip;
            String uri = (secure ? "ipps" : "ipp") + "://" + hostPart + ":" + info.getPort() + "/" + rp;
            String id = firstNonEmpty(txt(txt, "UUID"), ip);
            JSONObject existing = byId.get(id);
            if (existing != null && (existing.optBoolean("secure") || !secure)) return;   // already have it (ipps preferred)
            String name = info.getServiceName();
            probing++;
            final String finalIp = ip;
            PROBES.execute(() -> {
                JSONObject p;
                try {
                    p = describe(uri, name, id);
                } catch (IOException e) {
                    p = new JSONObject();
                    try {
                        p.put("name", name).put("uri", uri).put("address", finalIp).put("id", id)
                                .put("makeModel", txt(txt, "ty")).put("location", txt(txt, "note"))
                                .put("supported", false).put("problem", "didn't answer");
                    } catch (JSONException ignored) {
                    }
                }
                JSONObject found = p;
                MAIN.post(() -> {
                    probing--;
                    JSONObject before = byId.get(id);
                    if (before == null || !before.optBoolean("supported") || found.optBoolean("supported")) byId.put(id, found);
                    java.util.List<JSONObject> one = new java.util.ArrayList<>();
                    one.add(byId.get(id));
                    if (!stopped) callback.found(one, false, null);
                    maybeDone();
                });
            });
        }
    }

    /** Checks an IP address (or host) typed by hand. */
    static void probe(String address, Found callback) {
        String a = address == null ? "" : address.trim();
        if (a.contains("://")) {
            try {
                a = java.net.URI.create(a).getHost();
            } catch (Exception e) {
                a = "";
            }
        }
        if (a == null || a.isEmpty() || !Ipp.isLocalHost(a)) {
            MAIN.post(() -> callback.found(new java.util.ArrayList<>(), true,
                    "Enter the printer's address on this network, for example 192.168.1.40."));
            return;
        }
        final String host = a.contains(":") && !a.startsWith("[") ? "[" + a + "]" : a;
        PROBES.execute(() -> {
            String[] paths = {"ipp/print", "ipp", "ipp/printer", "printers/ipp"};
            for (String scheme : new String[]{"ipps", "ipp"}) {
                for (String path : paths) {
                    String uri = scheme + "://" + host + ":631/" + path;
                    try {
                        JSONObject p = describe(uri, null, null);
                        java.util.List<JSONObject> list = new java.util.ArrayList<>();
                        list.add(p);
                        MAIN.post(() -> callback.found(list, true, null));
                        return;
                    } catch (IOException e) {
                        if (isUnreachable(e)) {
                            if (scheme.equals("ipp")) {
                                MAIN.post(() -> callback.found(new java.util.ArrayList<>(), true,
                                        "Nothing answered at " + host + ". Check the address, and that the printer is on and connected to this Wi-Fi."));
                                return;
                            }
                            break;      // no TLS on this printer: try plain IPP
                        }
                    }
                }
            }
            MAIN.post(() -> callback.found(new java.util.ArrayList<>(), true,
                    "Something answered at " + host + ", but not as a printer the tablet can use. Check IPP printing is turned on in the printer's settings."));
        });
    }

    private static boolean isUnreachable(IOException e) {
        return e instanceof ConnectException || e instanceof NoRouteToHostException || e instanceof SocketTimeoutException
                || e instanceof java.net.UnknownHostException;
    }

    // --------------------------------------------------------------- print

    /** Sends a PDF to the printer and follows the job. Status updates arrive on the main thread. */
    static void print(Context context, JSONObject printer, byte[] pdf, String title, int copies, Status status) {
        Context app = context.getApplicationContext();
        JOBS.execute(() -> {
            String name = printer.optString("name", "the printer");
            String uri = printer.optString("uri");
            try {
                Ipp.Response attrs;
                try {
                    attrs = Ipp.printerAttributes(uri);
                } catch (IOException e) {
                    // A printer's address can change (DHCP). Look for it again by its id and retry once.
                    JSONObject moved = isUnreachable(e) ? findAgain(app, printer) : null;
                    if (moved == null) {
                        post(status, "Failed", "Can't reach " + name + ". Check it's switched on and connected to the same Wi-Fi as this tablet.");
                        return;
                    }
                    Prefs.get(app).setPrinter(moved);
                    uri = moved.optString("uri");
                    attrs = Ipp.printerAttributes(uri);
                }
                if (!attrs.ok()) {
                    post(status, "Failed", name + ": " + Ipp.statusText(attrs.status));
                    return;
                }
                if ("false".equals(attrs.text("printer-is-accepting-jobs"))) {
                    String why = Ipp.reasonText(attrs.texts("printer-state-reasons"));
                    post(status, "Failed", name + " isn't accepting jobs" + (why.isEmpty() ? "" : ": " + why));
                    return;
                }
                String format = chooseFormat(attrs);
                if (format == null) {
                    post(status, "Failed", name + " can't take PDF or IPP Everywhere jobs. Use the tablet's print screen instead.");
                    return;
                }
                byte[] doc = format.equals("application/pdf") ? pdf : rasterise(app, pdf, attrs);
                List<String> media = attrs.texts("media-supported");
                boolean a4 = media.contains("iso_a4_210x297mm");
                boolean fit = attrs.texts("print-scaling-supported").contains("fit");
                int maxCopies = 1;
                String range = attrs.text("copies-supported");
                int dash = range.indexOf('-');
                if (dash > 0) {
                    try {
                        maxCopies = Integer.parseInt(range.substring(dash + 1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                int sends = copies <= maxCopies ? 1 : copies;      // printer can't do copies: send it that many times
                int perJob = copies <= maxCopies ? copies : 1;
                int lastJob = -1;
                for (int i = 0; i < sends; i++) {
                    Ipp.Response r = Ipp.printJob(uri, title, format, doc, perJob, a4, fit);
                    if (!r.ok()) {
                        post(status, "Failed", name + ": " + Ipp.statusText(r.status));
                        return;
                    }
                    lastJob = r.get("job-id") == null ? -1 : r.get("job-id").integer(-1);
                }
                String warn = Ipp.reasonText(attrs.texts("printer-state-reasons"));
                post(status, "Sent to printer", "Job " + lastJob + " on " + name + (warn.isEmpty() ? "" : " (printer reports: " + warn + ")"));
                if (lastJob > 0) follow(uri, lastJob, name, status);
            } catch (OutOfMemoryError e) {
                post(status, "Failed", "The tablet ran out of memory preparing the pages. Use the tablet's print screen instead.");
            } catch (IOException e) {
                post(status, "Failed", name + ": " + (e.getMessage() == null ? "the connection failed" : e.getMessage()));
            } catch (Exception e) {
                Log.w(TAG, "print", e);
                post(status, "Failed", "Printing failed on the tablet: " + e.getClass().getSimpleName());
            }
        });
    }

    /** Polls the job until the printer finishes, gives up, or three minutes pass. */
    private static void follow(String uri, int jobId, String name, Status status) {
        long end = System.currentTimeMillis() + FOLLOW_MS;
        String stoppedSaid = null;
        while (System.currentTimeMillis() < end) {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                return;
            }
            try {
                Ipp.Response r = Ipp.jobAttributes(uri, jobId);
                if (r.status == 0x0406) {          // the printer finished and forgot the job
                    post(status, "Printed", "Finished on " + name);
                    return;
                }
                if (!r.ok()) continue;
                int state = r.get("job-state") == null ? 0 : r.get("job-state").integer(0);
                String why = Ipp.reasonText(r.texts("job-state-reasons"));
                String msg = r.text("job-state-message");
                switch (state) {
                    case 9:
                        post(status, "Printed", "Printed on " + name);
                        return;
                    case 7:
                        post(status, "Cancelled", why.isEmpty() ? "Cancelled at the printer" : why);
                        return;
                    case 8:
                        post(status, "Failed", name + ": " + firstNonEmpty(why, msg, "the printer gave up on the job"));
                        return;
                    case 6: {                         // processing-stopped: tell them once why
                        String w = firstNonEmpty(why, msg, "stopped");
                        if (!w.equals(stoppedSaid)) {
                            stoppedSaid = w;
                            post(status, "Sent to printer", name + " has stopped: " + w + ". It will carry on once that's sorted.");
                        }
                        break;
                    }
                    default:
                        break;
                }
            } catch (IOException ignored) {
                // A busy printer may not answer every poll.
            }
        }
        post(status, "Sent to printer", "Still with " + name + " after 3 minutes. Check the printer.");
    }

    /** Looks for the same printer (by id) at a new address. Blocking; call off the main thread. */
    private static JSONObject findAgain(Context context, JSONObject printer) {
        String id = printer.optString("id");
        if (id.isEmpty()) return null;
        final JSONObject[] hit = {null};
        final Object lock = new Object();
        final boolean[] done = {false};
        MAIN.post(() -> discover(context, (list, finished, error) -> {
            for (JSONObject p : list) {
                if (id.equals(p.optString("id")) && p.optBoolean("supported")) hit[0] = p;
            }
            if (finished || hit[0] != null) {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        }));
        synchronized (lock) {
            long end = System.currentTimeMillis() + DISCOVERY_MS + 4000;
            while (!done[0] && System.currentTimeMillis() < end) {
                try {
                    lock.wait(500);
                } catch (InterruptedException e) {
                    return null;
                }
            }
        }
        return hit[0];
    }

    /*
     * Renders the PDF's pages to PWG Raster at A4 portrait. Landscape pages are
     * turned a quarter so they fill the sheet. Done in bands of rows so a 300 dpi
     * page never has to sit in memory whole.
     */
    private static byte[] rasterise(Context context, byte[] pdf, Ipp.Response attrs) throws IOException {
        int dpi = chooseDpi(attrs.texts("pwg-raster-document-resolution-supported"));
        List<String> types = attrs.texts("pwg-raster-document-type-supported");
        boolean color = !types.isEmpty() && !types.contains("sgray_8");
        int width = Math.round(210f / 25.4f * dpi), height = Math.round(297f / 25.4f * dpi);
        File tmp = File.createTempFile("hera-print", ".pdf", context.getCacheDir());
        try {
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(pdf);
            }
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(fd)) {
                int pages = renderer.getPageCount();
                PwgRaster out = new PwgRaster(width, height, dpi, color, pages);
                int band = 256;
                Bitmap bmp = Bitmap.createBitmap(width, band, Bitmap.Config.ARGB_8888);
                int[] px = new int[width * band];
                byte[] rows = new byte[out.bytesPerLine() * band];
                for (int i = 0; i < pages; i++) {
                    try (PdfRenderer.Page page = renderer.openPage(i)) {
                        out.beginPage();
                        float pw = page.getWidth(), ph = page.getHeight();      // points
                        boolean landscape = pw > ph;
                        float scale = landscape ? Math.min(width / ph, height / pw) : Math.min(width / pw, height / ph);
                        for (int y0 = 0; y0 < height; y0 += band) {
                            int n = Math.min(band, height - y0);
                            bmp.eraseColor(Color.WHITE);
                            Matrix m = new Matrix();
                            if (landscape) {
                                m.setRotate(90);
                                m.postScale(scale, scale);
                                m.postTranslate(ph * scale, 0);
                            } else {
                                m.setScale(scale, scale);
                            }
                            m.postTranslate(0, -y0);
                            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                            bmp.getPixels(px, 0, width, 0, 0, width, n);
                            int o = 0;
                            for (int k = 0; k < width * n; k++) {
                                int c = px[k];
                                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                                if (color) {
                                    rows[o++] = (byte) r;
                                    rows[o++] = (byte) g;
                                    rows[o++] = (byte) b;
                                } else {
                                    rows[o++] = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
                                }
                            }
                            out.addRows(rows, n);
                        }
                        out.endPage();
                    }
                }
                bmp.recycle();
                return out.toByteArray();
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** 300 dpi if offered, else the lowest at or above 200, else whatever is first. */
    private static int chooseDpi(List<String> resolutions) {
        int best = -1;
        for (String r : resolutions) {
            if (!r.endsWith("dpi")) continue;
            int x;
            try {
                x = Integer.parseInt(r.substring(0, r.indexOf('x')));
            } catch (Exception e) {
                continue;
            }
            if (x == 300) return 300;
            if (x >= 200 && (best < 0 || x < best)) best = x;
        }
        if (best > 0) return best;
        return resolutions.isEmpty() ? 300 : Math.max(150, parseLeading(resolutions.get(0)));
    }

    private static int parseLeading(String r) {
        try {
            return Integer.parseInt(r.substring(0, r.indexOf('x')));
        } catch (Exception e) {
            return 300;
        }
    }

    // ------------------------------------------------- Android print screen

    /**
     * Android's own print screen, as a fallback. It runs in the print spooler,
     * so that app is let into the locked kiosk while it's open.
     */
    static void printWithDialog(Activity activity, byte[] pdf, String title, Status status) {
        File tmp;
        try {
            tmp = File.createTempFile("hera-print", ".pdf", activity.getCacheDir());
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(pdf);
            }
        } catch (IOException e) {
            post(status, "Failed", "The tablet couldn't prepare the document.");
            return;
        }
        PrintManager pm = (PrintManager) activity.getSystemService(Context.PRINT_SERVICE);
        if (pm == null) {
            post(status, "Failed", "This tablet has no print service.");
            return;
        }
        KioskPolicy.allowPackages(activity, PRINT_SPOOLER);
        PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build();
        PrintJob job;
        try {
            job = pm.print(title, new FileAdapter(tmp, title), attrs);
        } catch (Exception e) {
            KioskPolicy.restoreLockTask(activity);
            post(status, "Failed", "Android's print screen wouldn't open.");
            return;
        }
        Context app = activity.getApplicationContext();
        long end = System.currentTimeMillis() + 10 * 60_000L;
        final boolean[] queued = {false};
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (job.isCompleted()) {
                    status.update("Printed", "Printed from Android's print screen");
                } else if (job.isFailed()) {
                    status.update("Failed", "Android's print service couldn't print it");
                } else if (job.isCancelled()) {
                    status.update("Cancelled", "Cancelled on Android's print screen");
                } else {
                    if (!queued[0] && (job.isQueued() || job.isStarted())) {
                        queued[0] = true;
                        status.update("Sent to printer", "Sent from Android's print screen");
                    }
                    if (System.currentTimeMillis() < end) {
                        MAIN.postDelayed(this, 2000);
                        return;
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                KioskPolicy.restoreLockTask(app);
            }
        };
        MAIN.postDelayed(poll, 2000);
    }

    /** Hands an existing PDF file to Android's print framework. */
    private static final class FileAdapter extends PrintDocumentAdapter {
        private final File file;
        private final String name;

        FileAdapter(File file, String name) {
            this.file = file;
            this.name = name;
        }

        @Override
        public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes, CancellationSignal cancel,
                             LayoutResultCallback callback, Bundle extras) {
            if (cancel.isCanceled()) {
                callback.onLayoutCancelled();
                return;
            }
            callback.onLayoutFinished(new PrintDocumentInfo.Builder(safeName(name) + ".pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), !newAttributes.equals(oldAttributes));
        }

        @Override
        public void onWrite(PageRange[] pages, ParcelFileDescriptor destination, CancellationSignal cancel, WriteResultCallback callback) {
            try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) {
                    if (cancel.isCanceled()) {
                        callback.onWriteCancelled();
                        return;
                    }
                    out.write(buf, 0, r);
                }
                callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (IOException e) {
                callback.onWriteFailed(e.getMessage());
            }
        }
    }

    // ----------------------------------------------------------- test page

    /** A one-page A4 landscape PDF to check a printer from the admin menu. */
    static byte[] testPage(String printerName) {
        PdfDocument doc = new PdfDocument();
        PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(842, 595, 1).create());
        android.graphics.Canvas c = page.getCanvas();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(15, 46, 84));
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(28);
        c.drawText("Hera kiosk: printer test page", 40, 70, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setColor(Color.rgb(60, 70, 80));
        p.setTextSize(14);
        String when = new SimpleDateFormat("d MMM yyyy HH:mm", Locale.UK).format(new Date());
        c.drawText("Printer: " + printerName, 40, 110, p);
        c.drawText("Tablet: " + Build.MANUFACTURER + " " + Build.MODEL + ", kiosk app " + BuildConfig.VERSION_NAME, 40, 132, p);
        c.drawText("Printed: " + when, 40, 154, p);
        c.drawText("If this page is the right way round and the box below touches nothing at the edges, printing is set up.", 40, 190, p);
        Paint box = new Paint();
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(2);
        box.setColor(Color.rgb(15, 46, 84));
        c.drawRect(30, 30, 812, 565, box);
        doc.finishPage(page);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            doc.writeTo(out);
        } catch (IOException ignored) {
        }
        doc.close();
        return out.toByteArray();
    }

    // --------------------------------------------------------------- utils

    private static void post(Status status, String s, String detail) {
        MAIN.post(() -> status.update(s, detail));
    }

    private static String txt(Map<String, byte[]> txt, String key) {
        if (txt == null) return "";
        for (Map.Entry<String, byte[]> e : txt.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key) && e.getValue() != null) return new String(e.getValue(), StandardCharsets.UTF_8);
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    private static String safeName(String s) {
        String n = s == null ? "Hera" : s.replaceAll("[^A-Za-z0-9 ._-]+", "").trim();
        return n.isEmpty() ? "Hera" : (n.length() > 80 ? n.substring(0, 80) : n);
    }
}
