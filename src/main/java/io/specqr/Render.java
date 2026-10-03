package io.specqr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.Adler32;
import java.util.zip.CRC32;

/** Portable SVG and deterministic RGBA PNG renderer using java.base only. */
public final class Render {
  private Render() {}

  public static final int RASTER_PIXEL_BUDGET = 4 * 1024 * 1024,
      SVG_CHARACTER_BUDGET = 8 * 1024 * 1024;
  public static final int DATA_URL_CHARACTER_BUDGET = 32 * 1024 * 1024;

  public static final class Pixels {
    private final int width, height;
    private final byte[] pixels;

    private Pixels(int w, int h, byte[] p) {
      width = w;
      height = h;
      pixels = p;
    }

    public int width() {
      return width;
    }

    public int height() {
      return height;
    }

    public byte[] pixels() {
      return pixels.clone();
    }
  }

  static boolean[][] copyMatrix(boolean[][] m) {
    if (m == null || m.length < 1 || m.length > 177) throw bad("Matrix dimension must be 1..177");
    boolean[][] a = new boolean[m.length][];
    for (int y = 0; y < m.length; y++) {
      if (m[y] == null || m[y].length != m.length) throw bad("Matrix must be square");
      a[y] = m[y].clone();
    }
    return a;
  }

  private static SpecQrException bad(String s) {
    return new SpecQrException("INVALID_INPUT", s);
  }

  private static long dimension(boolean[][] m, Options o, boolean raster) {
    long modules = m.length + 2L * o.margin();
    if (modules > 9007199254740991L / o.scale())
      throw bad("Render geometry exceeds resource budget");
    long d = modules * o.scale();
    if (d > 9007199254740991L || raster && (d > 2048 || d * d > RASTER_PIXEL_BUDGET))
      throw bad("Render geometry exceeds resource budget");
    return d;
  }

  public static int[] parseColor(String s) {
    return parseColor(s, true);
  }

  static int[] parseColor(String s, boolean strict) {
    if (s == null) throw new SpecQrException("INVALID_COLOR", "Color must not be null");
    String v = s.trim().toLowerCase(Locale.ROOT);
    if (v.equals("black")) return new int[] {0, 0, 0, 255};
    if (v.equals("white")) return new int[] {255, 255, 255, 255};
    if (v.equals("transparent")) return new int[] {0, 0, 0, 0};
    if (v.matches("#[0-9a-f]{3,4}|#[0-9a-f]{6}(?:[0-9a-f]{2})?")) {
      String h = v.substring(1);
      int[] c = {0, 0, 0, 255};
      int n = h.length();
      for (int i = 0; i < (n <= 4 ? n : n / 2); i++)
        c[i] =
            n <= 4
                ? Integer.parseInt(h.substring(i, i + 1), 16) * 17
                : Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
      return c;
    }
    if (strict)
      throw new SpecQrException(
          "INVALID_COLOR", "Raster color must be hex, black, white or transparent");
    return null;
  }

  public static double contrastRatio(int[] a, int[] b) {
    return (Math.max(luminance(a), luminance(b)) + .05)
        / (Math.min(luminance(a), luminance(b)) + .05);
  }

  private static double luminance(int[] c) {
    if (c == null || c.length != 4) throw bad("Color must have four components");
    if (c[3] < 0 || c[3] > 255) throw bad("Color component out of range");
    double sum = 0;
    double[] w = {.2126, .7152, .0722};
    for (int i = 0; i < 3; i++) {
      if (c[i] < 0 || c[i] > 255) throw bad("Color component out of range");
      double v = c[i] / 255.0;
      sum += w[i] * (v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4));
    }
    return sum;
  }

  public static Pixels toPixels(boolean[][] matrix) {
    return toPixels(matrix, Options.defaults());
  }

  public static Pixels toPixels(boolean[][] matrix, Options options) {
    Options o = required(options);
    boolean[][] m = copyMatrix(matrix);
    int n = (int) dimension(m, o, true);
    int[] fg = parseColor(o.foreground()), bg = parseColor(o.background());
    byte[] p = new byte[n * n * 4];
    int offset = 0;
    for (int y = 0; y < n; y++) {
      int my = y / o.scale() - o.margin();
      for (int x = 0; x < n; x++) {
        int mx = x / o.scale() - o.margin();
        int[] c = my >= 0 && my < m.length && mx >= 0 && mx < m.length && m[my][mx] ? fg : bg;
        for (int z : c) p[offset++] = (byte) z;
      }
    }
    return new Pixels(n, n, p);
  }

  private static Options required(Options o) {
    if (o == null) throw bad("Options must not be null");
    return o;
  }

  private static String xml(String s) {
    for (int i = 0; i < s.length(); ) {
      int c = s.codePointAt(i);
      if (!(c == 9
          || c == 10
          || c == 13
          || c >= 32 && c <= 0xD7FF
          || c >= 0xE000 && c <= 0xFFFD
          || c >= 0x10000 && c <= 0x10FFFF))
        throw new SpecQrException("INVALID_COLOR", "SVG color contains invalid XML character");
      i += Character.charCount(c);
    }
    return s.replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  public static String toSvg(boolean[][] matrix) {
    return toSvg(matrix, Options.defaults());
  }

  public static String toSvg(boolean[][] matrix, Options options) {
    Options o = required(options);
    boolean[][] m = copyMatrix(matrix);
    long n = dimension(m, o, false);
    int digits = Long.toString(n).length(), sd = Integer.toString(o.scale()).length(), dark = 0;
    for (boolean[] row : m) for (boolean b : row) if (b) dark++;
    long budget =
        512L
            + 6L * (o.foreground().length() + o.background().length())
            + 4L * digits
            + (long) dark * (7 + 2 * digits + 3 * sd);
    if (budget > SVG_CHARACTER_BUDGET) throw bad("SVG exceeds output budget");
    String fg = xml(o.foreground()), bg = xml(o.background());
    StringBuilder s = new StringBuilder((int) budget);
    s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
        .append(n)
        .append("\" height=\"")
        .append(n)
        .append("\" viewBox=\"0 0 ")
        .append(n)
        .append(' ')
        .append(n)
        .append("\" role=\"img\"><rect width=\"100%\" height=\"100%\" fill=\"")
        .append(bg)
        .append("\"/><path fill=\"")
        .append(fg)
        .append("\" d=\"");
    for (int y = 0; y < m.length; y++)
      for (int x = 0; x < m.length; x++)
        if (m[y][x])
          s.append('M')
              .append((x + (long) o.margin()) * o.scale())
              .append(',')
              .append((y + (long) o.margin()) * o.scale())
              .append('h')
              .append(o.scale())
              .append('v')
              .append(o.scale())
              .append("h-")
              .append(o.scale())
              .append('z');
    return s.append("\"/></svg>").toString();
  }

  private static void be(ByteArrayOutputStream b, long v) {
    for (int i = 3; i >= 0; i--) b.write((int) (v >>> (8 * i)) & 255);
  }

  private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
    byte[] t = type.getBytes(StandardCharsets.US_ASCII);
    be(out, data.length);
    out.writeBytes(t);
    out.writeBytes(data);
    CRC32 c = new CRC32();
    c.update(t);
    c.update(data);
    be(out, c.getValue());
  }

  public static byte[] toPng(boolean[][] matrix) {
    return toPng(matrix, Options.defaults());
  }

  public static byte[] toPng(boolean[][] matrix, Options o) {
    Pixels p = toPixels(matrix, o);
    int stride = p.width * 4;
    byte[] raw = new byte[(stride + 1) * p.height];
    for (int y = 0; y < p.height; y++)
      System.arraycopy(p.pixels, y * stride, raw, y * (stride + 1) + 1, stride);
    ByteArrayOutputStream z = new ByteArrayOutputStream(raw.length + raw.length / 65535 * 5 + 16);
    z.write(0x78);
    z.write(1);
    for (int i = 0; i < raw.length; i += 65535) {
      int k = Math.min(65535, raw.length - i);
      z.write(i + k == raw.length ? 1 : 0);
      z.write(k & 255);
      z.write(k >>> 8);
      z.write((k ^ 65535) & 255);
      z.write((k ^ 65535) >>> 8);
      z.write(raw, i, k);
    }
    Adler32 a = new Adler32();
    a.update(raw);
    be(z, a.getValue());
    ByteArrayOutputStream h = new ByteArrayOutputStream(13);
    be(h, p.width);
    be(h, p.height);
    h.writeBytes(new byte[] {8, 6, 0, 0, 0});
    ByteArrayOutputStream out = new ByteArrayOutputStream(z.size() + 57);
    out.writeBytes(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    chunk(out, "IHDR", h.toByteArray());
    chunk(out, "IDAT", z.toByteArray());
    chunk(out, "IEND", new byte[0]);
    return out.toByteArray();
  }

  public static String toPngDataUrl(boolean[][] m, Options o) {
    return "data:image/png;base64," + Base64.getEncoder().encodeToString(toPng(m, o));
  }

  public static String toPngDataUrl(boolean[][] m) {
    return toPngDataUrl(m, Options.defaults());
  }

  public static String toSvgDataUrl(boolean[][] m, Options o) {
    byte[] b = toSvg(m, o).getBytes(StandardCharsets.UTF_8);
    if (b.length * 3L + 31 > DATA_URL_CHARACTER_BUDGET)
      throw bad("SVG data URL exceeds output budget");
    StringBuilder s = new StringBuilder("data:image/svg+xml;charset=utf-8,");
    String hex = "0123456789ABCDEF";
    for (byte value : b) {
      int c = value & 255;
      if (c >= 65 && c <= 90
          || c >= 97 && c <= 122
          || c >= 48 && c <= 57
          || "~()*!.'-_".indexOf(c) >= 0) s.append((char) c);
      else s.append('%').append(hex.charAt(c >>> 4)).append(hex.charAt(c & 15));
    }
    return s.toString();
  }

  public static String toSvgDataUrl(boolean[][] m) {
    return toSvgDataUrl(m, Options.defaults());
  }
}
