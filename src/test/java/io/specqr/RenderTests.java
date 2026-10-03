package io.specqr;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.Inflater;

public final class RenderTests {
  private RenderTests() {}

  private static int assertions;

  private static void check(boolean b, String m) {
    assertions++;
    if (!b) throw new AssertionError(m);
  }

  private static void bad(Runnable r) {
    assertions++;
    try {
      r.run();
      throw new AssertionError("Expected render failure");
    } catch (SpecQrException expected) {
    }
  }

  public static int run() throws Exception {
    assertions = 0;
    boolean[][] m = {{true, false}, {false, true}};
    Options o =
        Options.builder().margin(1).scale(2).foreground("#1234").background("#abcdef80").build();
    Render.Pixels p = Render.toPixels(m, o);
    check(p.width() == 8 && p.height() == 8 && p.pixels().length == 256, "pixel geometry");
    check(
        Arrays.equals(
            Arrays.copyOfRange(p.pixels(), 0, 4),
            new byte[] {(byte) 0xab, (byte) 0xcd, (byte) 0xef, (byte) 0x80}),
        "background RGBA");
    int i = (2 * 8 + 2) * 4;
    check(
        Arrays.equals(
            Arrays.copyOfRange(p.pixels(), i, i + 4), new byte[] {0x11, 0x22, 0x33, 0x44}),
        "foreground RGBA");
    byte[] owned = p.pixels();
    owned[0] = 0;
    check(p.pixels()[0] != (byte) 0, "pixel ownership");
    check(
        Render.contrastRatio(Render.parseColor("black"), Render.parseColor("white")) == 21,
        "contrast");
    check(Arrays.equals(Render.parseColor(" #AbC "), new int[] {170, 187, 204, 255}), "hex color");
    bad(() -> Render.parseColor("red"));
    bad(() -> Render.contrastRatio(new int[] {0, 0, 0, -1}, new int[] {0, 0, 0, 255}));
    bad(() -> Render.toPixels(new boolean[0][]));
    bad(() -> Render.toPixels(new boolean[][] {{true}, {false}}));
    bad(() -> Render.toPixels(null));
    bad(() -> Render.toPixels(m, Options.builder().scale(Integer.MAX_VALUE).build()));
    bad(
        () ->
            Render.toSvg(
                m, Options.builder().margin(Integer.MAX_VALUE).scale(Integer.MAX_VALUE).build()));
    byte[] png = Render.toPng(m, o);
    check(
        Arrays.equals(Arrays.copyOf(png, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}),
        "PNG signature");
    ByteBuffer b = ByteBuffer.wrap(png);
    b.position(8);
    ByteArrayOutputStream idat = new ByteArrayOutputStream();
    boolean header = false, end = false;
    while (b.hasRemaining()) {
      int n = b.getInt();
      byte[] type = new byte[4];
      b.get(type);
      byte[] d = new byte[n];
      b.get(d);
      long actual = Integer.toUnsignedLong(b.getInt());
      CRC32 c = new CRC32();
      c.update(type);
      c.update(d);
      check(c.getValue() == actual, "PNG CRC");
      String t = new String(type, StandardCharsets.US_ASCII);
      if (t.equals("IHDR")) {
        ByteBuffer h = ByteBuffer.wrap(d);
        check(h.getInt() == 8 && h.getInt() == 8 && h.get() == 8 && h.get() == 6, "PNG header");
        header = true;
      } else if (t.equals("IDAT")) idat.writeBytes(d);
      else if (t.equals("IEND")) {
        check(n == 0, "IEND");
        end = true;
      }
    }
    check(header && end, "PNG chunks");
    Inflater inflater = new Inflater();
    inflater.setInput(idat.toByteArray());
    byte[] raw = new byte[8 * 33];
    check(inflater.inflate(raw) == raw.length && inflater.finished(), "DEFLATE decode");
    inflater.end();
    for (int y = 0; y < 8; y++) {
      check(raw[y * 33] == 0, "PNG filter");
      check(
          Arrays.equals(
              Arrays.copyOfRange(raw, y * 33 + 1, y * 33 + 33),
              Arrays.copyOfRange(p.pixels(), y * 32, y * 32 + 32)),
          "PNG pixels");
    }
    check(Arrays.equals(png, Render.toPng(m, o)), "PNG reproducibility");
    check(
        Arrays.equals(Base64.getDecoder().decode(Render.toPngDataUrl(m, o).substring(22)), png),
        "PNG data URL");
    String svg = Render.toSvg(m, o);
    check(
        svg.contains("width=\"8\"") && svg.contains("M2,2h2v2h-2z") && svg.contains("M4,4h2v2h-2z"),
        "SVG geometry");
    String css = "red\"/><script>&'";
    String escaped = Render.toSvg(m, Options.builder().foreground(css).build());
    check(
        escaped.contains("&quot;")
            && escaped.contains("&lt;")
            && escaped.contains("&amp;")
            && !escaped.contains("<script>"),
        "SVG XML escaping");
    bad(() -> Render.toSvg(m, Options.builder().foreground("\u0000").build()));
    bad(() -> Render.toSvg(m, Options.builder().foreground("\ud800").build()));
    check(
        Render.toSvgDataUrl(m, o).startsWith("data:image/svg+xml;charset=utf-8,%3Csvg"),
        "SVG data URL");
    QrCode q = SpecQr.generate("Renderer parity");
    check(q.toSvg().equals(Render.toSvg(q.matrix(), q.options())), "result SVG delegate");
    check(Arrays.equals(q.toPng(), Render.toPng(q.matrix(), q.options())), "result PNG delegate");
    check(q.toPixels().width() == (q.size() + 8) * 8, "default geometry");
    check(
        q.toPixels(Options.builder().scale(3).build()).width() == (q.size() + 8) * 3,
        "render override");
    return assertions;
  }
}
