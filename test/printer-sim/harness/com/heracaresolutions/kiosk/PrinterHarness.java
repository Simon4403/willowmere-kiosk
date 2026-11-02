package com.heracaresolutions.kiosk;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Virtual test of the kiosk's "add printer" and printing code, run on a JVM
 * against simulated network printers (farm.py). The kiosk classes are the real
 * ones; only Android framework pieces are stand-ins (see ../shims).
 */
public class PrinterHarness {
    static int pass, fail;
    static final List<String> failures = new ArrayList<>();
    static File jobsDir;
    static byte[] pdf;

    static void check(boolean ok, String what) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + what);
        } else {
            fail++;
            failures.add(what);
            System.out.println("  FAIL  " + what);
        }
    }

    static void section(String s) {
        System.out.println("\n== " + s);
    }

    // ------------------------------------------------------------ helpers

    static final class FoundLog {
        final List<List<JSONObject>> calls = new CopyOnWriteArrayList<>();
        volatile String error;
        volatile int doneCount;
        final CountDownLatch done = new CountDownLatch(1);
        volatile long doneAtMs;
        final long start = System.currentTimeMillis();

        PrinterService.Found cb() {
            return (list, isDone, err) -> {
                calls.add(new ArrayList<>(list));
                if (isDone) {
                    doneCount++;
                    error = err;
                    doneAtMs = System.currentTimeMillis() - start;
                    done.countDown();
                }
            };
        }

        List<JSONObject> last() {
            return calls.isEmpty() ? new ArrayList<>() : calls.get(calls.size() - 1);
        }

        boolean await(long s) throws InterruptedException {
            return done.await(s, TimeUnit.SECONDS);
        }
    }

    static final class StatusLog {
        final List<String> s = new CopyOnWriteArrayList<>();
        final CountDownLatch end = new CountDownLatch(1);

        PrinterService.Status cb() {
            return (status, detail) -> {
                s.add(status + " | " + detail);
                System.out.println("        status: " + status + " | " + detail);
                if (!status.equals("Sent to printer")) end.countDown();
            };
        }

        boolean await(long sec) throws InterruptedException {
            return end.await(sec, TimeUnit.SECONDS);
        }

        String lastStatus() {
            return s.isEmpty() ? "" : s.get(s.size() - 1).split(" \\| ")[0];
        }

        String all() {
            return String.join(" / ", s);
        }
    }

    static List<JSONObject> jobsFor(String printer) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        File[] files = jobsDir.listFiles((d, n) -> n.startsWith(printer + "-") && n.endsWith(".json"));
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) out.add(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        return out;
    }

    static byte[] docFor(String printer, int jobId) throws Exception {
        return Files.readAllBytes(new File(jobsDir, printer + "-" + jobId + ".doc").toPath());
    }

    static NsdServiceInfo svc(String name, String type, int port, String rp, String uuid, String... hosts) throws Exception {
        NsdServiceInfo i = new NsdServiceInfo();
        i.setServiceName(name);
        i.setServiceType(type);
        i.setPort(port);
        List<InetAddress> addrs = new ArrayList<>();
        for (String h : hosts) addrs.add(InetAddress.getByName(h));
        i.setHostAddresses(addrs);
        if (rp != null) i.setAttribute("rp", rp);
        if (uuid != null) i.setAttribute("UUID", uuid);
        i.setAttribute("ty", "Simulated " + name);
        return i;
    }

    static NsdServiceInfo found(String name, String type) {
        NsdServiceInfo i = new NsdServiceInfo();
        i.setServiceName(name);
        i.setServiceType(type);
        return i;
    }

    static JSONObject byName(List<JSONObject> list, String name) {
        for (JSONObject p : list) if (name.equals(p.optString("name"))) return p;
        return null;
    }

    // --------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        jobsDir = new File(args[0]);
        pdf = Files.readAllBytes(new File(args[1]).toPath());
        String only = args.length > 2 ? args[2] : "all";
        android.util.Log.quiet = true;
        Context ctx = new Context();

        if (only.equals("all") || only.equals("parse")) testParse();
        if (only.equals("all") || only.equals("local")) testLocal();
        if (only.equals("all") || only.equals("describe")) testDescribe();
        if (only.equals("all") || only.equals("probe")) testProbe();
        if (only.equals("all") || only.equals("discover")) testDiscover(ctx);
        if (only.equals("all") || only.equals("print")) testPrint(ctx);
        if (only.equals("all") || only.equals("moved")) testMoved(ctx);
        if (only.equals("all") || only.equals("bridge")) testBridge();

        System.out.println("\n==== " + pass + " passed, " + fail + " failed");
        for (String f : failures) System.out.println("  - " + f);
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------- tests

    /** Hand-built printer answer: a collection, additional values, resolution, range, booleans. */
    static void testParse() throws Exception {
        section("IPP parsing (hand-built response)");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(new byte[]{1, 1, 0, 0, 0, 0, 0, 7});
        b.write(0x04); // printer group
        attr(b, 0x49, "document-format-supported", "application/pdf".getBytes());
        attr(b, 0x49, "", "image/pwg-raster".getBytes());
        attr(b, 0x34, "media-col-default", new byte[0]);              // collection start
        attr(b, 0x4A, "", "media-size".getBytes());
        attr(b, 0x34, "", new byte[0]);                               // nested collection
        attr(b, 0x4A, "", "x-dimension".getBytes());
        attr(b, 0x21, "", new byte[]{0, 0, 0x52, 0x08});
        attr(b, 0x37, "", new byte[0]);
        attr(b, 0x37, "", new byte[0]);                               // collection end
        attr(b, 0x32, "pwg-raster-document-resolution-supported", new byte[]{0, 0, 1, 0x2C, 0, 0, 1, 0x2C, 3});
        attr(b, 0x33, "copies-supported", new byte[]{0, 0, 0, 1, 0, 0, 0, 99});
        attr(b, 0x22, "printer-is-accepting-jobs", new byte[]{0});
        attr(b, 0x23, "printer-state", new byte[]{0, 0, 0, 5});
        b.write(0x03);
        Ipp.Response r = Ipp.parse(b.toByteArray());
        check(r.ok(), "status 0 is ok");
        check(r.texts("document-format-supported").equals(Arrays.asList("application/pdf", "image/pwg-raster")),
                "additional values join their attribute: " + r.texts("document-format-supported"));
        check(r.get("media-size") == null && r.get("x-dimension") == null, "collection members (incl. nested) skipped");
        check("300x300dpi".equals(r.text("pwg-raster-document-resolution-supported")), "resolution decoded after a collection: " + r.text("pwg-raster-document-resolution-supported"));
        check("1-99".equals(r.text("copies-supported")), "range decoded: " + r.text("copies-supported"));
        check("false".equals(r.text("printer-is-accepting-jobs")), "boolean false read as \"false\"");
        check(r.get("printer-state").integer(0) == 5, "enum read as integer");
    }

    static void attr(ByteArrayOutputStream b, int tag, String name, byte[] value) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        b.write(tag);
        b.write(n.length >> 8);
        b.write(n.length);
        b.write(n, 0, n.length);
        b.write(value.length >> 8);
        b.write(value.length);
        b.write(value, 0, value.length);
    }

    static void testLocal() {
        section("Only printers on the tablet's own network (isLocalHost)");
        String[] yes = {"192.168.1.40", "10.0.0.5", "172.16.0.9", "172.31.255.1", "169.254.3.4", "127.0.0.1",
                "printer.local", "BRN123.LOCAL", "fe80::1", "[fd00::25]", "fc00::1"};
        String[] no = {"8.8.8.8", "172.32.0.1", "11.0.0.1", "192.169.1.1", "192.168.1.256", "printer.example.com",
                "printer.local.evil.com", "192.168.1.40.nip.io", "2001:db8::1", "::1", "", null};
        for (String h : yes) check(Ipp.isLocalHost(h), "allowed: " + h);
        for (String h : no) check(!Ipp.isLocalHost(h), "refused: " + h);
    }

    static void testDescribe() throws Exception {
        section("Asking printers what they are (describe)");
        JSONObject office = PrinterService.describe("ipp://127.0.0.1:8631/ipp/print", "Office (mDNS name)", null);
        check("Office PDF".equals(office.optString("name")), "name from printer-info: " + office.optString("name"));
        check("application/pdf".equals(office.optString("format")) && office.optBoolean("supported"), "PDF printer: format pdf, usable");
        check("urn:uuid:a1".equals(office.optString("id")), "id from printer-uuid: " + office.optString("id"));
        check("127.0.0.1".equals(office.optString("address")), "address recorded");
        check(!office.optBoolean("secure"), "plain ipp marked not secure");

        JSONObject pwg = PrinterService.describe("ipp://127.0.0.1:8632/ipp/print", null, null);
        check("image/pwg-raster".equals(pwg.optString("format")) && pwg.optBoolean("supported"), "PWG-only printer usable via raster");

        JSONObject ps = PrinterService.describe("ipp://127.0.0.1:8637/ipp/print", null, null);
        check(!ps.optBoolean("supported") && ps.optString("format").isEmpty(), "PostScript-only printer marked unusable");

        JSONObject tls = PrinterService.describe("ipps://127.0.0.1:8636/ipp/print", null, null);
        check(tls.optBoolean("supported") && tls.optBoolean("secure"), "ipps with self-signed certificate works, marked secure");

        JSONObject st = PrinterService.state("ipp://127.0.0.1:8633/ipp/print");
        check(st.optBoolean("reachable") && "stopped".equals(st.optString("state")) && !st.optBoolean("accepting"),
                "state(): out-of-paper printer reported stopped, not accepting: " + st);
        JSONObject gone = PrinterService.state("ipp://127.0.0.5:631/ipp/print");
        check(!gone.optBoolean("reachable"), "state(): switched-off printer reported unreachable");
        boolean refused = false;
        try {
            PrinterService.describe("ipp://8.8.8.8:631/ipp/print", null, null);
        } catch (java.io.IOException e) {
            refused = e.getMessage().contains("own network");
        }
        check(refused, "describe() refuses an internet address before connecting");
    }

    static void testProbe() throws Exception {
        section("Add a printer by IP address (probe, port 631)");
        Object[][] cases = {
                // address, expect name (null = expect error), error must contain, max seconds staff wait
                {"127.0.0.3", "By IP secure", null, 10},
                {"127.0.0.4", "By IP odd path", null, 15},
                {"ipp://127.0.0.2:631/ipp/print", "By IP plain", null, 15},
                {"127.0.0.5", null, "Nothing answered", 10},
                {"127.0.0.7", null, "not as a printer", 15},
                {"8.8.8.8", null, "on this network", 2},
                {"   ", null, "on this network", 2},
                {"127.0.0.6", null, "Nothing answered", 30},
        };
        for (Object[] c : cases) {
            FoundLog log = new FoundLog();
            PrinterService.probe((String) c[0], log.cb());
            boolean finished = log.await((Integer) c[3] + 75);
            double secs = log.doneAtMs / 1000.0;
            List<JSONObject> list = log.last();
            if (c[1] != null) {
                JSONObject p = list.isEmpty() ? null : list.get(0);
                check(finished && p != null && c[1].equals(p.optString("name")) && p.optBoolean("supported"),
                        "probe " + c[0] + " -> " + (p == null ? "nothing (" + log.error + ")" : p.optString("name") + " " + p.optString("uri")) + String.format(" in %.1fs", secs));
            } else {
                check(finished && list.isEmpty() && log.error != null && log.error.contains((String) c[2]),
                        "probe '" + c[0] + "' -> error \"" + log.error + "\"" + String.format(" in %.1fs", secs));
            }
            check(secs <= (Integer) c[3], String.format("probe '%s' answered within %ds (took %.1fs)", c[0], c[3], secs));
            check(log.doneCount == 1, "probe '" + c[0] + "' reported done exactly once (" + log.doneCount + ")");
        }
    }

    static void testDiscover(Context ctx) throws Exception {
        section("Find printers on this Wi-Fi (mDNS discovery, fake network)");
        NsdManager nsd = new NsdManager();
        Context.SERVICES.put(Context.NSD_SERVICE, nsd);
        Object[][] net = {
                // found name/type, resolved
                {found("Office PDF", "._ipp._tcp"), svc("Office PDF", "._ipp._tcp", 8631, "ipp/print", "a1", "127.0.0.1")},
                {found("Secure jam", "._ipps._tcp"), svc("Secure jam", "._ipps._tcp", 8636, "ipp/print", "f6", "127.0.0.1")},
                {found("Old PostScript", "._ipp._tcp"), svc("Old PostScript", "._ipp._tcp", 8637, "ipp/print", "g7", "127.0.0.1")},
                // Android 14+ often lists the IPv6 link-local address first.
                {found("Ward PWG", "._ipp._tcp"), svc("Ward PWG", "._ipp._tcp", 8632, "ipp/print", "b2", "fe80::1%1", "127.0.0.1")},
                {found("Internet printer", "._ipp._tcp"), svc("Internet printer", "._ipp._tcp", 631, "ipp/print", "zz", "8.8.8.8")},
                {found("Flaky", "._ipp._tcp"), null},
        };
        for (Object[] n : net) nsd.network.add(new NsdManager.Advert((NsdServiceInfo) n[0], (NsdServiceInfo) n[1]));

        FoundLog log = new FoundLog();
        new android.os.Handler().post(() -> PrinterService.discover(ctx, log.cb()));
        check(log.await(30), "discovery finished");
        List<JSONObject> all = log.last();
        System.out.println("        final list: " + all.size() + " printers");
        for (JSONObject p : all) System.out.println("          - " + p.optString("name") + " " + p.optString("uri") + " supported=" + p.optBoolean("supported") + (p.has("problem") ? " problem=" + p.optString("problem") : ""));
        check(log.doneCount == 1, "done reported once (" + log.doneCount + ")");
        check(log.doneAtMs >= 7000 && log.doneAtMs < 15000, String.format("search runs about 8 s (%.1fs)", log.doneAtMs / 1000.0));
        check(nsd.discoveriesStarted.get() == 2 && nsd.discoveriesStopped.get() == 2, "both _ipp and _ipps searched, both stopped");
        check(nsd.alreadyActiveFailures.get() == 0, "resolves run one at a time (no 'already active' failures: " + nsd.alreadyActiveFailures.get() + ")");
        JSONObject office = byName(all, "Office PDF");
        check(office != null && office.optBoolean("supported"), "Office PDF found and usable");
        JSONObject sec = byName(all, "Secure jam");
        check(sec != null && sec.optBoolean("supported") && sec.optString("uri").startsWith("ipps://"), "ipps printer found over TLS");
        JSONObject ps = byName(all, "Old PostScript");
        check(ps != null && !ps.optBoolean("supported"), "PostScript printer listed as can't-use");
        check(byName(all, "Internet printer") == null, "printer resolving to an internet address is dropped");
        JSONObject ward = byName(all, "Ward PWG");
        check(ward != null && ward.optBoolean("supported"),
                "printer whose first address is IPv6 link-local still usable: " + (ward == null ? "missing" : ward.optString("uri") + " supported=" + ward.optBoolean("supported")));
        check(all.size() == 4, "4 printers listed (dropped internet, failed resolve): " + all.size());

        // Address choice on its own, including Android 13 and older (one address only).
        NsdServiceInfo v6first = svc("x", "._ipp._tcp", 631, null, null, "fe80::1%1", "fd00::9", "192.168.1.40");
        check("192.168.1.40".equals(PrinterService.bestAddress(v6first).getHostAddress()), "bestAddress: IPv4 preferred over IPv6");
        NsdServiceInfo v6only = svc("x", "._ipp._tcp", 631, null, null, "fe80::1%1", "fd00::9");
        check(PrinterService.bestAddress(v6only).getHostAddress().startsWith("fd00"), "bestAddress: routable IPv6 preferred over link-local");
        check(Ipp.sameId("urn:uuid:6D4C-AA") .equals(Ipp.sameId("6d4c-aa")), "sameId: urn:uuid prefix and case ignored");
    }

    static void testPrint(Context ctx) throws Exception {
        section("Printing (real IPP jobs to the simulated printers)");
        // 1. Normal PDF printer, 2 copies.
        StatusLog s = new StatusLog();
        JSONObject office = PrinterService.describe("ipp://127.0.0.1:8631/ipp/print", null, null);
        PrinterService.print(ctx, office, pdf, "Medication round 08:00", 2, s.cb());
        check(s.await(30) && s.lastStatus().equals("Printed"), "office: printed -> " + s.all());
        check(s.s.get(0).startsWith("Sent to printer | Job "), "office: 'Sent to printer' with job number first");
        List<JSONObject> jobs = jobsFor("office");
        JSONObject j = jobs.isEmpty() ? new JSONObject() : jobs.get(jobs.size() - 1);
        check(jobs.size() == 1, "office: exactly one job sent for 2 copies (printer does copies): " + jobs.size());
        check(Arrays.equals(docFor("office", j.optInt("job_id")), pdf), "office: PDF arrived byte-for-byte (" + j.optInt("doc_bytes") + " bytes)");
        check("application/pdf".equals(j.optJSONArray("document-format") == null ? null : j.optJSONArray("document-format").optString(0)), "office: document-format application/pdf");
        check("[2]".equals(String.valueOf(j.opt("copies"))), "office: copies=2 in job attributes: " + j.opt("copies"));
        check("[\"iso_a4_210x297mm\"]".equals(String.valueOf(j.opt("media"))), "office: media A4");
        check("[\"fit\"]".equals(String.valueOf(j.opt("print-scaling"))), "office: print-scaling fit");
        check("[\"Medication round 08:00\"]".equals(String.valueOf(j.opt("job-name"))), "office: job name passed through");

        // 2. Printer without copies support: 3 separate jobs.
        s = new StatusLog();
        PrinterService.print(ctx, PrinterService.describe("ipp://127.0.0.1:8635/ipp/print", null, null), pdf, "Care plan", 3, s.cb());
        check(s.await(30) && s.lastStatus().equals("Printed"), "no-copies printer: printed -> " + s.lastStatus());
        jobs = jobsFor("nocopies");
        check(jobs.size() == 3, "no-copies printer: sent 3 jobs for 3 copies: " + jobs.size());
        check(jobs.stream().allMatch(x -> x.isNull("copies") && x.isNull("print-scaling")), "no-copies printer: no unsupported attributes sent");

        // 3-8. Problem printers.
        Object[][] bad = {
                {"ipp://127.0.0.1:8633/ipp/print", "Failed", "out of paper", 15},
                {"ipp://127.0.0.1:8634/ipp/print", "Failed", "couldn't read the document", 20},
                {"ipps://127.0.0.1:8636/ipp/print", "Printed", "", 25},
                {"ipp://127.0.0.1:8638/ipp/print", "Printed", "Finished", 15},
                {"ipp://127.0.0.1:8639/ipp/print", "Failed", "busy", 15},
                {"ipp://127.0.0.1:8640/ipp/print", "Cancelled", "cancelled at the printer", 20},
                {"ipp://127.0.0.1:8637/ipp/print", "Failed", "can't take PDF", 15},
        };
        for (Object[] b : bad) {
            s = new StatusLog();
            JSONObject p = new JSONObject().put("name", "P").put("uri", b[0]).put("id", "x");
            PrinterService.print(ctx, p, pdf, "T", 1, s.cb());
            boolean ended = s.await((Integer) b[3]);
            String last = s.s.isEmpty() ? "" : s.s.get(s.s.size() - 1);
            check(ended && s.lastStatus().equals(b[1]) && last.contains((String) b[2]), b[0] + " -> " + last);
        }
        // the jam printer should explain the stop once, then print
        long jamNotes = s.s.size();
        StatusLog jam = new StatusLog();
        PrinterService.print(ctx, new JSONObject().put("name", "Jam").put("uri", "ipps://127.0.0.1:8636/ipp/print"), pdf, "T", 1, jam.cb());
        jam.await(25);
        long stops = jam.s.stream().filter(x -> x.contains("has stopped: paper jam")).count();
        check(stops == 1 && jam.lastStatus().equals("Printed"), "paper jam reported once, then printed (" + stops + " notices)");
    }

    static void testMoved(Context ctx) throws Exception {
        section("Printer changed IP address (DHCP): found again by its id");
        NsdManager nsd = new NsdManager();
        Context.SERVICES.put(Context.NSD_SERVICE, nsd);
        // Real printers advertise the bare UUID in mDNS TXT; IPP printer-uuid is "urn:uuid:...".
        nsd.network.add(new NsdManager.Advert(found("Office PDF", "._ipp._tcp"),
                svc("Office PDF", "._ipp._tcp", 8631, "ipp/print", "A1", "127.0.0.1")));

        // (a) chosen from the Wi-Fi list (id came from mDNS TXT)
        StatusLog s = new StatusLog();
        JSONObject fromList = new JSONObject().put("name", "Office PDF").put("uri", "ipp://127.0.0.5:631/ipp/print").put("id", "A1");
        PrinterService.print(ctx, fromList, pdf, "Moved (list)", 1, s.cb());
        check(s.await(40) && s.lastStatus().equals("Printed"), "added from Wi-Fi list, then moved: printed -> " + s.all());

        // (b) added by IP address (id came from printer-uuid)
        s = new StatusLog();
        JSONObject byIp = new JSONObject().put("name", "Office PDF").put("uri", "ipp://127.0.0.5:631/ipp/print").put("id", "urn:uuid:a1");
        PrinterService.print(ctx, byIp, pdf, "Moved (by IP)", 1, s.cb());
        check(s.await(40) && s.lastStatus().equals("Printed"), "added by IP, then moved: printed -> " + s.all());
        JSONObject saved = Prefs.get(ctx).printer();
        check(saved != null && saved.optString("uri").contains("8631"), "new address saved: " + (saved == null ? null : saved.optString("uri")));
    }

    /** window.HeraKiosk: printer powers only for Hera's own page; events stop if the page changes. */
    static void testBridge() throws Exception {
        section("Hera page bridge (window.HeraKiosk) permission checks");
        KioskActivity page = new KioskActivity();
        KioskBridge bridge = new KioskBridge(page);
        Prefs prefs = Prefs.get(page);
        prefs.setPrinter(null);

        check(bridge.apiVersion() == KioskBridge.API_VERSION, "Hera page sees the API");
        JSONObject r = new JSONObject(bridge.choosePrinter(new JSONObject().put("name", "Evil").put("uri", "ipp://8.8.8.8/ipp/print").toString()));
        check(!r.optBoolean("ok"), "choosePrinter refuses an internet printer: " + r.optString("error"));
        r = new JSONObject(bridge.choosePrinter(new JSONObject().put("name", "Web").put("uri", "http://192.168.1.5/print").toString()));
        check(!r.optBoolean("ok"), "choosePrinter refuses a non-IPP link");
        r = new JSONObject(bridge.choosePrinter("not json"));
        check(!r.optBoolean("ok"), "choosePrinter survives junk input");
        r = new JSONObject(bridge.choosePrinter(new JSONObject().put("name", "Office PDF").put("uri", "ipp://127.0.0.1:8631/ipp/print")
                .put("id", "urn:uuid:a1").put("format", "application/pdf").put("password", "secret").toString()));
        check(r.optBoolean("ok") && !r.getJSONObject("printer").has("password"), "choosePrinter saves a local printer, keeps only known fields");
        check(new JSONObject(bridge.printerInfo()).optString("uri").contains("8631"), "printerInfo returns the saved printer");

        page.events.clear();
        bridge.probe("127.0.0.3");
        Thread.sleep(4000);
        check(page.events.stream().anyMatch(e -> e.startsWith("printers(") && e.contains("By IP secure")), "probe result delivered to Hera as printers(...) event");

        String b64 = java.util.Base64.getMimeEncoder().encodeToString(pdf);  // with line breaks, as browsers may send
        page.events.clear();
        bridge.print("job-1", "From Hera", b64, 1);
        Thread.sleep(9000);
        check(page.events.stream().anyMatch(e -> e.startsWith("printStatus(\"job-1\",\"Printed\"")), "print via bridge -> printStatus Printed event: " + page.events);

        page.events.clear();
        bridge.print("job-2", "Bad", java.util.Base64.getEncoder().encodeToString("<html>".getBytes()), 1);
        Thread.sleep(500);
        check(page.events.stream().anyMatch(e -> e.contains("\"job-2\",\"Failed\"") && e.contains("didn't receive")), "non-PDF data refused");
        page.events.clear();
        bridge.print("job-3", "Huge", "A".repeat(28 * 1024 * 1024), 1);
        Thread.sleep(500);
        check(page.events.stream().anyMatch(e -> e.contains("\"job-3\",\"Failed\"")), "over-size document refused");

        // Page changes to another allowed site while a job is running: no events leak to it.
        page.events.clear();
        page.blockedEvents.clear();
        bridge.print("job-4", "Leave", b64, 1);
        Thread.sleep(300);
        page.pageHost = "login.microsoftonline.com";
        int deliveredBefore = page.events.size();       // "Sent to printer" may legitimately arrive before navigating
        Thread.sleep(9000);
        check(page.events.size() == deliveredBefore && page.blockedEvents.stream().anyMatch(e -> e.contains("Printed")),
                "after navigating away, later job events withheld (delivered before: " + deliveredBefore + ", withheld: " + page.blockedEvents + ")");

        // Now on the other site: every call refused.
        int before = jobsFor("office").size();
        check(bridge.apiVersion() == 0, "other site: apiVersion 0");
        check("{}".equals(bridge.printerInfo()), "other site: printerInfo empty");
        check(new JSONObject(bridge.choosePrinter(new JSONObject().put("uri", "ipp://127.0.0.1:8632/ipp/print").toString())).optString("error").equals("Not allowed"),
                "other site: choosePrinter refused");
        bridge.print("job-5", "Sneaky", b64, 1);
        bridge.discover();
        bridge.probe("127.0.0.2");
        bridge.checkPrinter();
        Thread.sleep(3000);
        check(jobsFor("office").size() == before, "other site: print() sent nothing to the printer");
        check(new JSONObject(bridge.printerInfo()).length() == 0 && prefs.printer().optString("uri").contains("8631"), "other site: saved printer unchanged");
        page.pageHost = page.heraHost;
    }
}
