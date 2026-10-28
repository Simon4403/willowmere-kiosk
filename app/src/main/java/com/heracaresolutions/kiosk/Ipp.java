package com.heracaresolutions.kiosk;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A small IPP/1.1 client (RFC 8010/8011): just what Hera needs to find out what a
 * network printer accepts, send it a job and follow the job to the end.
 *
 * Pure Java, no Android classes, so it can be tested off the tablet.
 *
 * Transport is a plain socket, not HttpURLConnection, for two reasons: printers
 * use self-signed certificates for ipps://, and the app keeps cleartext HTTP
 * switched off for the browser. Both are made safe by {@link #isLocalHost}: the
 * kiosk only ever talks IPP to addresses on the tablet's own network.
 */
final class Ipp {
    // Operations
    static final int PRINT_JOB = 0x0002;
    static final int GET_JOB_ATTRIBUTES = 0x0009;
    static final int GET_PRINTER_ATTRIBUTES = 0x000B;

    // Delimiter and value tags
    private static final int TAG_OPERATION = 0x01, TAG_JOB = 0x02, TAG_END = 0x03;
    static final int INTEGER = 0x21, BOOLEAN = 0x22, ENUM = 0x23, RESOLUTION = 0x32, RANGE = 0x33,
            BEG_COLLECTION = 0x34, END_COLLECTION = 0x37, TEXT = 0x41, NAME = 0x42, KEYWORD = 0x44,
            URI_TAG = 0x45, CHARSET = 0x47, LANGUAGE = 0x48, MIME = 0x49, MEMBER_NAME = 0x4A;

    static final int CONNECT_TIMEOUT_MS = 5000;
    static final int READ_TIMEOUT_MS = 60_000;
    private static int requestId = 1;

    private Ipp() {
    }

    /** One attribute with all its values. Collections are skipped. */
    static final class Attr {
        final String name;
        final int tag;
        final List<Object> values = new ArrayList<>();

        Attr(String name, int tag) {
            this.name = name;
            this.tag = tag;
        }

        String text() {
            return values.isEmpty() ? "" : String.valueOf(values.get(0));
        }

        int integer(int fallback) {
            return !values.isEmpty() && values.get(0) instanceof Integer ? (Integer) values.get(0) : fallback;
        }

        List<String> texts() {
            List<String> out = new ArrayList<>();
            for (Object v : values) out.add(String.valueOf(v));
            return out;
        }
    }

    /** A parsed response: status code and attributes from every group, by name. */
    static final class Response {
        int status;
        final Map<String, Attr> attrs = new LinkedHashMap<>();

        boolean ok() {
            return status >= 0 && status < 0x0100;
        }

        Attr get(String name) {
            return attrs.get(name);
        }

        String text(String name) {
            Attr a = attrs.get(name);
            return a == null ? "" : a.text();
        }

        List<String> texts(String name) {
            Attr a = attrs.get(name);
            return a == null ? new ArrayList<>() : a.texts();
        }
    }

    /** Builds a request body. */
    static final class Request {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int group = -1;

        Request(int operation) {
            out.write(0x01);
            out.write(0x01);                 // IPP/1.1: every IPP printer speaks it
            u16(operation);
            u32(nextRequestId());
        }

        Request group(int tag) {
            if (group != tag) {
                out.write(tag);
                group = tag;
            }
            return this;
        }

        Request operation() {
            return group(TAG_OPERATION);
        }

        Request job() {
            return group(TAG_JOB);
        }

        Request string(int tag, String name, String... values) {
            for (int i = 0; i < values.length; i++) {
                out.write(tag);
                bytes(i == 0 ? name : "");
                bytes(values[i]);
            }
            return this;
        }

        Request integer(int tag, String name, int value) {
            out.write(tag);
            bytes(name);
            u16(4);
            u32(value);
            return this;
        }

        Request bool(String name, boolean value) {
            out.write(BOOLEAN);
            bytes(name);
            u16(1);
            out.write(value ? 1 : 0);
            return this;
        }

        /** The three attributes every request starts with. */
        Request standard(String printerUri) {
            return operation()
                    .string(CHARSET, "attributes-charset", "utf-8")
                    .string(LANGUAGE, "attributes-natural-language", "en-gb")
                    .string(URI_TAG, "printer-uri", printerUri);
        }

        byte[] finish() {
            out.write(TAG_END);
            return out.toByteArray();
        }

        private void bytes(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            u16(b.length);
            out.write(b, 0, b.length);
        }

        private void u16(int v) {
            out.write((v >> 8) & 0xFF);
            out.write(v & 0xFF);
        }

        private void u32(int v) {
            u16((v >>> 16) & 0xFFFF);
            u16(v & 0xFFFF);
        }
    }

    private static synchronized int nextRequestId() {
        return requestId++;
    }

    // ------------------------------------------------------------ parsing

    static Response parse(byte[] body) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(body));
        Response r = new Response();
        in.readUnsignedShort();                  // version
        r.status = in.readUnsignedShort();
        in.readInt();                            // request id
        Attr last = null;
        int depth = 0;
        while (true) {
            int tag;
            try {
                tag = in.readUnsignedByte();
            } catch (EOFException e) {
                break;
            }
            if (tag == TAG_END) break;
            if (tag < 0x10) {                    // a new group
                last = null;
                continue;
            }
            int nameLen = in.readUnsignedShort();
            byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            int valueLen = in.readUnsignedShort();
            byte[] value = new byte[valueLen];
            in.readFully(value);

            // Collections (media-col and friends) aren't needed: skip their members.
            if (tag == BEG_COLLECTION) {
                depth++;
                continue;
            }
            if (tag == END_COLLECTION) {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth > 0) continue;

            String name = new String(nameBytes, StandardCharsets.UTF_8);
            Attr attr;
            if (name.isEmpty()) {
                if (last == null) continue;      // an additional value with nothing to add it to
                attr = last;
            } else {
                attr = new Attr(name, tag);
                if (!r.attrs.containsKey(name)) r.attrs.put(name, attr);
                last = attr;
            }
            attr.values.add(decode(tag, value));
        }
        return r;
    }

    private static Object decode(int tag, byte[] v) {
        switch (tag) {
            case INTEGER:
            case ENUM:
                return v.length == 4 ? ((v[0] & 0xFF) << 24) | ((v[1] & 0xFF) << 16) | ((v[2] & 0xFF) << 8) | (v[3] & 0xFF) : 0;
            case BOOLEAN:
                return v.length > 0 && v[0] != 0;
            case RESOLUTION:
                if (v.length != 9) return "";
                int x = ((v[0] & 0xFF) << 24) | ((v[1] & 0xFF) << 16) | ((v[2] & 0xFF) << 8) | (v[3] & 0xFF);
                int y = ((v[4] & 0xFF) << 24) | ((v[5] & 0xFF) << 16) | ((v[6] & 0xFF) << 8) | (v[7] & 0xFF);
                return x + "x" + y + (v[8] == 3 ? "dpi" : "dpcm");
            case RANGE:
                if (v.length != 8) return "";
                int lo = ((v[0] & 0xFF) << 24) | ((v[1] & 0xFF) << 16) | ((v[2] & 0xFF) << 8) | (v[3] & 0xFF);
                int hi = ((v[4] & 0xFF) << 24) | ((v[5] & 0xFF) << 16) | ((v[6] & 0xFF) << 8) | (v[7] & 0xFF);
                return lo + "-" + hi;
            default:
                return new String(v, StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------- transport

    /** Sends a request (and an optional document after it) and returns the parsed response. */
    static Response send(String printerUri, byte[] request, byte[] document) throws IOException {
        URI u = URI.create(printerUri);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        boolean tls = scheme.equals("ipps") || scheme.equals("https");
        if (!tls && !scheme.equals("ipp") && !scheme.equals("http")) throw new IOException("Not a printer address: " + printerUri);
        String host = u.getHost();
        if (host == null || !isLocalHost(host)) throw new IOException("Printers must be on this tablet's own network");
        int port = u.getPort() > 0 ? u.getPort() : 631;
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/ipp/print" : u.getRawPath();

        try (Socket socket = open(host, port, tls)) {
            OutputStream out = socket.getOutputStream();
            int length = request.length + (document == null ? 0 : document.length);
            String hostHeader = host.contains(":") ? "[" + host + "]" : host;
            String head = "POST " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + ":" + port + "\r\n"
                    + "Content-Type: application/ipp\r\n"
                    + "Content-Length: " + length + "\r\n"
                    + "User-Agent: Hera-Kiosk\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(request);
            if (document != null) {
                for (int off = 0; off < document.length; off += 65536) {
                    out.write(document, off, Math.min(65536, document.length - off));
                }
            }
            out.flush();
            return parse(readHttpBody(new BufferedInputStream(socket.getInputStream())));
        }
    }

    private static Socket open(String host, int port, boolean tls) throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        plain.setSoTimeout(READ_TIMEOUT_MS);
        if (!tls) return plain;
        try {
            // Printers ship self-signed certificates, so there is no chain to check.
            // Encryption still keeps printouts off the air on a shared Wi-Fi; the
            // address is restricted to the local network by isLocalHost.
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(plain, host, port, true);
            s.setSoTimeout(READ_TIMEOUT_MS);
            s.startHandshake();
            return s;
        } catch (IOException e) {
            plain.close();
            throw e;
        } catch (Exception e) {
            plain.close();
            throw new IOException("Secure connection to the printer failed", e);
        }
    }

    /** Reads an HTTP/1.1 response and returns its body (Content-Length, chunked, or to close). */
    static byte[] readHttpBody(InputStream in) throws IOException {
        while (true) {
            String status = line(in);
            if (status == null) throw new IOException("The printer closed the connection");
            String[] parts = status.split(" ", 3);
            int code = parts.length > 1 ? parseInt(parts[1]) : 0;
            int contentLength = -1;
            boolean chunked = false;
            String h;
            while ((h = line(in)) != null && !h.isEmpty()) {
                int colon = h.indexOf(':');
                if (colon < 0) continue;
                String k = h.substring(0, colon).trim().toLowerCase(Locale.ROOT), v = h.substring(colon + 1).trim();
                if (k.equals("content-length")) contentLength = parseInt(v);
                if (k.equals("transfer-encoding") && v.toLowerCase(Locale.ROOT).contains("chunked")) chunked = true;
            }
            if (code == 100) continue;                    // "100 Continue": the real answer follows
            if (code != 200) throw new IOException("The printer answered HTTP " + code + (parts.length > 2 ? " " + parts[2] : ""));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (chunked) {
                while (true) {
                    String size = line(in);
                    if (size == null) break;
                    int semi = size.indexOf(';');
                    int n = Integer.parseInt((semi >= 0 ? size.substring(0, semi) : size).trim(), 16);
                    if (n == 0) break;
                    copy(in, body, n);
                    line(in);
                }
            } else if (contentLength >= 0) {
                copy(in, body, contentLength);
            } else {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) body.write(buf, 0, r);
            }
            return body.toByteArray();
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, int n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int r = in.read(buf, 0, Math.min(buf.length, n));
            if (r < 0) throw new EOFException("The printer's answer was cut short");
            out.write(buf, 0, r);
            n -= r;
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("Header too long");
        }
        return c < 0 && sb.length() == 0 ? null : sb.toString();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ----------------------------------------------------------- safety

    /**
     * True for addresses on the tablet's own network: private IPv4 ranges,
     * link-local, loopback, IPv6 unique-local/link-local, and mDNS ".local" names. A
     * printout of care records must never be sent across the internet.
     */
    static boolean isLocalHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.endsWith(".local")) return true;
        if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            String[] p = h.split("\\.");
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            for (String part : p) if (Integer.parseInt(part) > 255) return false;
            return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 169 && b == 254) || a == 127;
        }
        if (h.contains(":")) {
            try {
                InetAddress ip = InetAddress.getByName(h);            // a literal: no lookup happens
                byte[] x = ip.getAddress();
                return x.length == 16 && ((x[0] & 0xFE) == 0xFC || ((x[0] & 0xFF) == 0xFE && (x[1] & 0xC0) == 0x80));
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------- operations

    static final String[] PRINTER_ATTRIBUTES = {
            "printer-name", "printer-info", "printer-make-and-model", "printer-location", "printer-state",
            "printer-state-reasons", "printer-state-message", "printer-is-accepting-jobs", "document-format-supported",
            "media-supported", "media-default", "print-scaling-supported", "copies-supported",
            "pwg-raster-document-resolution-supported", "pwg-raster-document-type-supported", "printer-uuid"
    };

    static Response printerAttributes(String printerUri) throws IOException {
        byte[] req = new Request(GET_PRINTER_ATTRIBUTES).standard(printerUri)
                .string(NAME, "requesting-user-name", "Hera kiosk")
                .string(KEYWORD, "requested-attributes", PRINTER_ATTRIBUTES)
                .finish();
        return send(printerUri, req, null);
    }

    static Response printJob(String printerUri, String jobName, String format, byte[] document,
                             int copies, boolean a4, boolean fitToPage) throws IOException {
        Request r = new Request(PRINT_JOB).standard(printerUri)
                .string(NAME, "requesting-user-name", "Hera kiosk")
                .string(NAME, "job-name", jobName.length() > 200 ? jobName.substring(0, 200) : jobName)
                .string(MIME, "document-format", format);
        r.job();
        if (copies > 1) r.integer(INTEGER, "copies", copies);
        if (a4) r.string(KEYWORD, "media", "iso_a4_210x297mm");
        if (fitToPage) r.string(KEYWORD, "print-scaling", "fit");
        return send(printerUri, r.finish(), document);
    }

    static Response jobAttributes(String printerUri, int jobId) throws IOException {
        byte[] req = new Request(GET_JOB_ATTRIBUTES).standard(printerUri)
                .integer(INTEGER, "job-id", jobId)
                .string(NAME, "requesting-user-name", "Hera kiosk")
                .string(KEYWORD, "requested-attributes", "job-state", "job-state-reasons", "job-state-message")
                .finish();
        return send(printerUri, req, null);
    }

    /** Status codes worth explaining in plain words. */
    static String statusText(int status) {
        switch (status) {
            case 0x0400: return "the printer refused the request";
            case 0x0401: return "the printer didn't accept the request";
            case 0x0402: return "the printer needs a sign-in, which the tablet can't give";
            case 0x0403: return "the printer won't accept jobs from this tablet";
            case 0x0406: return "the printer no longer has that job";
            case 0x040A: return "the printer can't print this kind of document";
            case 0x040B: return "the printer couldn't use a setting it was sent";
            case 0x0500: return "the printer had an internal error";
            case 0x0506: return "the printer isn't accepting jobs";
            case 0x0507: return "the printer is busy; try again in a minute";
            default: return "the printer answered with error 0x" + Integer.toHexString(status);
        }
    }

    /** "media-empty-error" -> "out of paper". Unknown reasons come back tidied, not dropped. */
    static String reasonText(List<String> reasons) {
        StringBuilder sb = new StringBuilder();
        for (String r : reasons) {
            if (r == null || r.isEmpty() || r.equals("none")) continue;
            String base = r.replaceAll("-(error|warning|report)$", "");
            String t;
            switch (base) {
                case "media-empty": t = "out of paper"; break;
                case "media-needed": t = "needs paper"; break;
                case "media-jam": t = "paper jam"; break;
                case "toner-empty": t = "out of toner"; break;
                case "toner-low": t = "toner low"; break;
                case "marker-supply-empty": t = "out of ink"; break;
                case "marker-supply-low": t = "ink low"; break;
                case "door-open": t = "a door is open"; break;
                case "cover-open": t = "the cover is open"; break;
                case "offline": t = "offline"; break;
                case "paused": t = "paused"; break;
                case "output-area-full": t = "output tray full"; break;
                case "input-tray-missing": t = "a paper tray is missing"; break;
                case "job-canceled-at-device": t = "cancelled at the printer"; break;
                case "job-canceled-by-user": t = "cancelled"; break;
                case "aborted-by-system": t = "the printer gave up on it"; break;
                case "document-format-error": t = "the printer couldn't read the document"; break;
                case "job-completed-successfully": case "job-printing": case "job-incoming": case "job-queued":
                case "processing-to-stop-point": case "job-data-insufficient": case "printer-stopped-partly":
                    continue;
                default: t = base.replace('-', ' ');
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(t);
        }
        return sb.toString();
    }
}
