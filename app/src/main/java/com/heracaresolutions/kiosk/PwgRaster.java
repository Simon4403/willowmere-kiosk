package com.heracaresolutions.kiosk;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * PWG Raster (PWG 5102.4) writer: the page-image format every IPP Everywhere
 * printer has to accept. Used when a printer doesn't take PDF.
 *
 * Pure Java so it can be tested off the tablet. Pages arrive a band of rows at a
 * time, already rendered to 8-bit grey or 8-bit RGB, so a whole A4 page at 300 dpi
 * never has to sit in memory.
 */
final class PwgRaster {
    static final int CSPACE_SGRAY = 18, CSPACE_SRGB = 19;
    private static final int HEADER_BYTES = 1796;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
    private final int width, height, dpi, bytesPerPixel, colorSpace, totalPages;
    private int rowsWritten;
    private byte[] pending;            // the previous row, held back to count repeats
    private int pendingRepeats;

    /**
     * @param width       pixels across (for A4 portrait at 300 dpi: 2480)
     * @param height      pixels down (3508)
     * @param dpi         resolution sent to the printer
     * @param color       true for sRGB 8-bit, false for 8-bit grey
     * @param totalPages  number of pages in the job
     */
    PwgRaster(int width, int height, int dpi, boolean color, int totalPages) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.bytesPerPixel = color ? 3 : 1;
        this.colorSpace = color ? CSPACE_SRGB : CSPACE_SGRAY;
        this.totalPages = totalPages;
        out.write('R');
        out.write('a');
        out.write('S');
        out.write('2');
    }

    int bytesPerLine() {
        return width * bytesPerPixel;
    }

    /** Starts a page: writes its header. A4 portrait. */
    void beginPage() {
        rowsWritten = 0;
        pending = null;
        pendingRepeats = 0;
        byte[] h = new byte[HEADER_BYTES];
        putString(h, 0, "PwgRaster");                 // MediaClass
        putInt(h, 276, dpi);                           // HWResolution
        putInt(h, 280, dpi);
        putInt(h, 352, 595);                           // PageSize, points (A4)
        putInt(h, 356, 842);
        putInt(h, 372, width);                         // cupsWidth
        putInt(h, 376, height);                        // cupsHeight
        putInt(h, 384, 8);                             // BitsPerColor
        putInt(h, 388, 8 * bytesPerPixel);             // BitsPerPixel
        putInt(h, 392, bytesPerLine());                // BytesPerLine
        putInt(h, 396, 0);                             // ColorOrder: chunky
        putInt(h, 400, colorSpace);                    // ColorSpace
        putInt(h, 420, bytesPerPixel);                 // NumColors
        putInt(h, 452, totalPages);                    // TotalPageCount
        putInt(h, 456, 1);                             // CrossFeedTransform
        putInt(h, 460, 1);                             // FeedTransform
        putInt(h, 480, 0xFFFFFF);                      // AlternatePrimary
        putString(h, 1732, "iso_a4_210x297mm");        // PageSizeName
        out.write(h, 0, h.length);
    }

    /** Adds rows (each bytesPerLine long) to the current page, top to bottom. */
    void addRows(byte[] data, int rows) {
        int bpl = bytesPerLine();
        for (int r = 0; r < rows && rowsWritten < height; r++, rowsWritten++) {
            byte[] row = Arrays.copyOfRange(data, r * bpl, (r + 1) * bpl);
            if (pending != null && pendingRepeats < 255 && Arrays.equals(pending, row)) {
                pendingRepeats++;
                continue;
            }
            flushPending();
            pending = row;
            pendingRepeats = 0;
        }
    }

    /** Ends the page, padding with white if fewer rows than the height were given. */
    void endPage() {
        if (rowsWritten < height) {
            byte[] white = new byte[bytesPerLine() * Math.min(256, height - rowsWritten)];
            Arrays.fill(white, (byte) 0xFF);
            while (rowsWritten < height) addRows(white, Math.min(256, height - rowsWritten));
        }
        flushPending();
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }

    private void flushPending() {
        if (pending == null) return;
        out.write(pendingRepeats);                     // line repeat count: extra copies of this row
        encodeRow(pending);
        pending = null;
        pendingRepeats = 0;
    }

    /*
     * Each row is a series of packets. A control byte 0..127 means "the next pixel,
     * repeated (n + 1) times"; 129..255 means "(257 - n) different pixels follow".
     */
    private void encodeRow(byte[] row) {
        int bpp = bytesPerPixel, n = row.length / bpp, i = 0;
        while (i < n) {
            int run = 1;
            while (i + run < n && run < 128 && samePixel(row, i, i + run)) run++;
            if (run > 1) {
                out.write(run - 1);
                out.write(row, i * bpp, bpp);
                i += run;
                continue;
            }
            int start = i, lit = 0;
            while (i < n && lit < 128) {
                if (i + 1 < n && samePixel(row, i, i + 1)) break;     // a run starts here
                i++;
                lit++;
            }
            if (lit <= 1) {                             // one lone pixel: a run of one ("repeat 0 more times")
                i = start + 1;
                out.write(0);
                out.write(row, start * bpp, bpp);
                continue;
            }
            out.write(257 - lit);                       // 2..128 literal pixels: 255..129
            out.write(row, start * bpp, lit * bpp);
        }
    }

    private boolean samePixel(byte[] row, int a, int b) {
        for (int k = 0; k < bytesPerPixel; k++) if (row[a * bytesPerPixel + k] != row[b * bytesPerPixel + k]) return false;
        return true;
    }

    private static void putInt(byte[] h, int off, int v) {
        h[off] = (byte) (v >>> 24);
        h[off + 1] = (byte) (v >>> 16);
        h[off + 2] = (byte) (v >>> 8);
        h[off + 3] = (byte) v;
    }

    private static void putString(byte[] h, int off, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, Math.min(b.length, 63));
    }
}
