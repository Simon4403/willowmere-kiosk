package com.heracaresolutions.kiosk;
import java.io.*;
/** Writes raster files plus the raw pixels they should decode to. */
public class PwgGen {
  public static void main(String[] a) throws Exception {
    for (boolean color : new boolean[]{false, true}) {
      int w = 301, h = 517, bpp = color ? 3 : 1, pages = 2;   // odd sizes: runs/literals cross 128-pixel limits
      PwgRaster r = new PwgRaster(w, h, 300, color, pages);
      ByteArrayOutputStream expect = new ByteArrayOutputStream();
      java.util.Random rnd = new java.util.Random(42);
      for (int p = 0; p < pages; p++) {
        r.beginPage();
        int given = p == 0 ? h : 300;                          // page 2: fewer rows given -> white padding
        byte[] rows = new byte[w * bpp * given];
        for (int y = 0; y < given; y++) for (int x = 0; x < w; x++) for (int k = 0; k < bpp; k++) {
          int v;
          if (y < 40) v = 255;                                  // blank band (row repeats > 255 rows? no: 40)
          else if (y < 340 && y >= 40) v = (y % 3 == 0) ? 255 : (x < 200 ? 0 : 255); // repeated rows, long runs
          else v = rnd.nextInt(4) == 0 ? rnd.nextInt(256) : (x / 7) * 3 + k;          // literals mixed with runs
          rows[(y * w + x) * bpp + k] = (byte) v;
        }
        // feed in bands of 64 rows, like the tablet does
        for (int y0 = 0; y0 < given; y0 += 64) {
          int n = Math.min(64, given - y0);
          byte[] band = java.util.Arrays.copyOfRange(rows, y0 * w * bpp, (y0 + n) * w * bpp);
          r.addRows(band, n);
        }
        r.endPage();
        expect.write(rows);
        byte[] white = new byte[w * bpp * (h - given)]; java.util.Arrays.fill(white, (byte) 255); expect.write(white);
      }
      String n = color ? "rgb" : "gray";
      try (FileOutputStream f = new FileOutputStream(a[0] + "/" + n + ".pwg")) { f.write(r.toByteArray()); }
      try (FileOutputStream f = new FileOutputStream(a[0] + "/" + n + ".raw")) { f.write(expect.toByteArray()); }
    }
  }
}
