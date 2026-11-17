package com.heracaresolutions.kiosk;

import android.content.Context;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;

/** Off-device tests for silent updates: the charging wait (ChargeWait) and download-then-verify (UpdateInstaller). */
public final class UpdateHarness {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
    }

    static final long MIN = 60_000L;

    public static void main(String[] args) throws Exception {
        chargeWait();
        download();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void chargeWait() {
        System.out.println("Charging wait");
        long t0 = 1_000_000L;
        check("unplugged clears the clock", ChargeWait.chargingSince(t0, false, t0 + MIN) == 0);
        check("plugging in starts the clock now", ChargeWait.chargingSince(0, true, t0) == t0);
        check("staying plugged keeps the start", ChargeWait.chargingSince(t0, true, t0 + 5 * MIN) == t0);
        check("a start in the future (after restart) resets to now", ChargeWait.chargingSince(t0 + MIN, true, t0) == t0);

        check("9m59s on charge: not yet", !ChargeWait.ready(t0, true, 0, t0 + 10 * MIN - 1000));
        check("10 min on charge, untouched: install", ChargeWait.ready(t0, true, 0, t0 + 10 * MIN));
        check("10 min on charge but unplugged now: no", !ChargeWait.ready(t0, false, 0, t0 + 11 * MIN));
        check("never plugged: no", !ChargeWait.ready(0, true, 0, t0 + 60 * MIN));
        check("touched 1 min ago: wait", !ChargeWait.ready(t0, true, t0 + 11 * MIN, t0 + 12 * MIN));
        check("touched 2 min ago: install", ChargeWait.ready(t0, true, t0 + 10 * MIN, t0 + 12 * MIN));

        // Unplug at 6 min, replug at 7: needs a fresh 10 minutes (until 17).
        long since = ChargeWait.chargingSince(t0, false, t0 + 6 * MIN);
        since = ChargeWait.chargingSince(since, true, t0 + 7 * MIN);
        check("unplug/replug restarts the 10 minutes", since == t0 + 7 * MIN
                && !ChargeWait.ready(since, true, 0, t0 + 16 * MIN) && ChargeWait.ready(since, true, 0, t0 + 17 * MIN));

        check("wait: 10 min left when just plugged in", ChargeWait.waitMs(t0, 0, t0) == 10 * MIN);
        check("wait: quiet time wins when longer", ChargeWait.waitMs(t0, t0 + 9 * MIN + 30_000, t0 + 10 * MIN) == 90_000);
        check("wait: 0 when ready", ChargeWait.waitMs(t0, 0, t0 + 20 * MIN) == 0);
    }

    static void download() throws Exception {
        System.out.println("Download and verify");
        File dir = Files.createTempDirectory("kiosk-files").toFile();
        Context.FILES_DIR = dir;
        Context ctx = new Context();

        byte[] apk = new byte[300_000];
        new java.util.Random(7).nextBytes(apk);
        String sha = hex(MessageDigest.getInstance("SHA-256").digest(apk));
        AtomicInteger hits = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/app.apk", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, apk.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(apk); }
        });
        server.createContext("/missing.apk", ex -> { ex.sendResponseHeaders(404, -1); ex.close(); });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            // an old leftover that should be tidied away
            new File(dir, "updates").mkdirs();
            Files.write(new File(dir, "updates/kiosk-5.apk").toPath(), new byte[]{1, 2, 3});

            File f = UpdateInstaller.download(ctx, base + "/app.apk", sha, 9);
            check("saved under the version's name", f.equals(UpdateInstaller.downloadedApk(ctx, 9)) && f.isFile());
            check("saved bytes match", java.util.Arrays.equals(Files.readAllBytes(f.toPath()), apk));
            check("no .part left behind", !new File(dir, "updates/kiosk-9.apk.part").exists());
            check("older downloads removed", !new File(dir, "updates/kiosk-5.apk").exists());

            int before = hits.get();
            UpdateInstaller.download(ctx, base + "/app.apk", sha, 9);
            check("already downloaded: not fetched again", hits.get() == before);

            String wrong = sha.substring(0, 63) + (sha.charAt(63) == '0' ? '1' : '0');
            try {
                UpdateInstaller.download(ctx, base + "/app.apk", wrong, 10);
                check("bad checksum rejected", false);
            } catch (UpdateInstaller.DownloadException e) {
                check("bad checksum rejected as permanent", e.permanent);
                check("bad file not kept", !UpdateInstaller.downloadedApk(ctx, 10).exists()
                        && !new File(dir, "updates/kiosk-10.apk.part").exists());
            }
            try {
                UpdateInstaller.download(ctx, base + "/missing.apk", sha, 11);
                check("server error reported", false);
            } catch (UpdateInstaller.DownloadException e) {
                check("server error is temporary (retry later)", !e.permanent);
            }
            check("good download still there after failures", UpdateInstaller.downloadedApk(ctx, 9).isFile());
            check("sha256Of matches", sha.equals(UpdateInstaller.sha256Of(UpdateInstaller.downloadedApk(ctx, 9))));

            UpdateInstaller.deleteDownloads(ctx, 0);
            File[] left = new File(dir, "updates").listFiles();
            check("deleteDownloads(0) clears everything", left != null && left.length == 0);
        } finally {
            server.stop(0);
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
