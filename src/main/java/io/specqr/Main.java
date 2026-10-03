package io.specqr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Command-line entry point: java -jar specqr-java-0.1.0-rc.1.jar --help. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    int code = run(args);
    if (code != 0) System.exit(code);
  }

  static int run(String[] args) {
    try {
      Options.Builder b = Options.builder();
      String text = null, binary = null, segments = null, output = null, format = "svg";
      boolean diagnostics = false, estimate = false;
      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        switch (arg) {
          case "--help", "-h" -> {
            System.out.println(
                "SpecQR Java "
                    + SpecQr.VERSION
                    + "\n"
                    + "Usage: java -jar specqr-java-0.1.0-rc.1.jar [options] TEXT\n"
                    + "Sources: TEXT | --binary FILE | --segments FILE\n"
                    + "Options: --format svg|png|matrix|svg-data-url|png-data-url --output FILE\n"
                    + "         --ecc L|M|Q|H --version N --min-version N --max-version N --mask"
                    + " N\n"
                    + "         --mode auto|numeric|alphanumeric|byte|kanji --eci N --gs1\n"
                    + "         --fnc1-second XX --no-optimize --boost --estimate --diagnostics\n"
                    + "         --scale N --margin N --foreground COLOR --background COLOR --dpi"
                    + " N\n"
                    + "Use -- before text beginning with a dash.");
            return 0;
          }
          case "--binary" -> binary = value(args, ++i, arg);
          case "--segments" -> segments = value(args, ++i, arg);
          case "--output", "-o" -> output = value(args, ++i, arg);
          case "--format" -> format = value(args, ++i, arg);
          case "--ecc" -> b.errorCorrectionLevel(value(args, ++i, arg));
          case "--version" -> b.version(integer(value(args, ++i, arg)));
          case "--min-version" -> b.minVersion(integer(value(args, ++i, arg)));
          case "--max-version" -> b.maxVersion(integer(value(args, ++i, arg)));
          case "--mask" -> b.maskPattern(integer(value(args, ++i, arg)));
          case "--mode" -> b.mode(value(args, ++i, arg));
          case "--eci" -> b.eci(integer(value(args, ++i, arg)));
          case "--gs1" -> b.gs1(true);
          case "--fnc1-second" -> b.fnc1Second(value(args, ++i, arg));
          case "--no-optimize" -> b.optimizeSegments(false);
          case "--boost" -> b.boostErrorCorrection(true);
          case "--scale" -> b.scale(integer(value(args, ++i, arg)));
          case "--margin" -> b.margin(integer(value(args, ++i, arg)));
          case "--foreground" -> b.foreground(value(args, ++i, arg));
          case "--background" -> b.background(value(args, ++i, arg));
          case "--dpi" -> b.printDpi(Double.valueOf(value(args, ++i, arg)));
          case "--diagnostics" -> diagnostics = true;
          case "--estimate" -> estimate = true;
          case "--" -> {
            if (i + 2 != args.length || text != null)
              throw bad("Expected one text argument after --");
            text = args[++i];
          }
          default -> {
            if (arg.startsWith("-")) throw bad("Unknown option: " + arg);
            if (text != null) throw bad("Only one text argument is accepted");
            text = arg;
          }
        }
      }
      if ((text != null ? 1 : 0) + (binary != null ? 1 : 0) + (segments != null ? 1 : 0) != 1)
        throw bad("Provide exactly one text, --binary or --segments source");
      if (!Set.of("svg", "png", "matrix", "svg-data-url", "png-data-url").contains(format))
        throw new SpecQrException("INVALID_OUTPUT", "Unknown output format");
      Options o = b.build();
      byte[] bytes = binary == null ? null : readBounded(binary, 1_000_000);
      List<Segment> manual =
          segments == null
              ? null
              : parseSegments(Json.parse(decode(readBounded(segments, 4_000_000))));
      if (estimate) {
        Plan p =
            manual != null
                ? SpecQr.analyzeSegments(manual, o)
                : bytes != null ? SpecQr.estimate(bytes, o) : SpecQr.estimate(text, o);
        write(
            output,
            Json.write(
                    SpecQr.map(
                        "ok",
                        p.ok(),
                        "version",
                        p.version(),
                        "capacityVersion",
                        p.capacityVersion(),
                        "requiredBits",
                        p.requiredBits(),
                        "capacityBits",
                        p.capacityBits(),
                        "overflowBits",
                        p.overflowBits(),
                        "diagnostics",
                        p.diagnostics()))
                .getBytes(StandardCharsets.UTF_8),
            true);
        return 0;
      }
      QrCode q =
          manual != null
              ? SpecQr.generateSegments(manual, o)
              : bytes != null ? SpecQr.generate(bytes, o) : SpecQr.generate(text, o);
      byte[] result =
          switch (format) {
            case "png" -> q.toPng();
            case "svg" -> q.toSvg().getBytes(StandardCharsets.UTF_8);
            case "matrix" -> Json.write(q.matrix()).getBytes(StandardCharsets.UTF_8);
            case "svg-data-url" -> q.toSvgDataUrl().getBytes(StandardCharsets.UTF_8);
            case "png-data-url" -> q.toPngDataUrl().getBytes(StandardCharsets.UTF_8);
            default -> throw new AssertionError();
          };
      write(output, result, !format.equals("png"));
      if (diagnostics) System.err.println(Json.write(q.diagnostics()));
      return 0;
    } catch (IllegalArgumentException | IOException e) {
      System.err.println(
          "specqr: " + (e instanceof SpecQrException s ? s.code() + ": " : "") + e.getMessage());
      return 2;
    }
  }

  private static String decode(byte[] b) {
    try {
      return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString();
    } catch (java.nio.charset.CharacterCodingException e) {
      throw bad("JSON file must be valid UTF-8");
    }
  }

  private static byte[] readBounded(String path, int max) throws IOException {
    try (var in = Files.newInputStream(Path.of(path))) {
      byte[] b = in.readNBytes(max + 1);
      if (b.length > max) throw bad("Input file exceeds resource budget");
      return b;
    }
  }

  private static void write(String path, byte[] b, boolean newline) throws IOException {
    if (path == null) {
      System.out.write(b);
      if (newline) System.out.write('\n');
      System.out.flush();
    } else Files.write(Path.of(path), b);
  }

  private static String value(String[] a, int i, String arg) {
    if (i >= a.length) throw bad("Missing value for " + arg);
    return a[i];
  }

  private static int integer(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      throw bad("Expected a 32-bit integer: " + s);
    }
  }

  private static SpecQrException bad(String s) {
    return new SpecQrException("INVALID_INPUT", s);
  }

  static int number(Object v) {
    if (!(v instanceof Number n)
        || v instanceof Double
        || v instanceof Float
        || n.longValue() < Integer.MIN_VALUE
        || n.longValue() > Integer.MAX_VALUE) throw bad("Expected integer");
    return ((Number) v).intValue();
  }

  static String string(Object v) {
    if (!(v instanceof String s)) throw bad("Expected string");
    return (String) v;
  }

  static List<Segment> parseSegments(Object value) {
    if (!(value instanceof List<?> a) || a.size() > 16384)
      throw bad("Segments must be a bounded JSON array");
    List<Segment> s = new ArrayList<>();
    for (Object item : (List<?>) value) {
      if (!(item instanceof Map<?, ?> m) || !(m.get("mode") instanceof String mode))
        throw bad("Segment must be an object with a mode");
      Set<String> allowed =
          switch (mode) {
            case "numeric", "alphanumeric", "byte", "kanji" -> Set.of("mode", "text", "bytes");
            case "eci" -> Set.of("mode", "assignmentNumber");
            case "fnc1" -> Set.of("mode");
            case "fnc1-second" -> Set.of("mode", "applicationIndicator");
            case "structured-append" -> Set.of("mode", "index", "total", "parity");
            default -> throw bad("Unknown segment mode");
          };
      for (Object k : m.keySet()) if (!allowed.contains(k)) throw bad("Unknown segment field");
      if (Set.of("numeric", "alphanumeric", "byte", "kanji").contains(mode)
          && m.containsKey("text") == m.containsKey("bytes"))
        throw bad("Data segment requires exactly one text or bytes field");
      Segment x =
          switch (mode) {
            case "numeric" -> Segment.numeric(string(m.get("text")));
            case "alphanumeric" -> Segment.alphanumeric(string(m.get("text")));
            case "kanji" -> Segment.kanji(string(m.get("text")));
            case "byte" -> {
              if (m.containsKey("text")) yield Segment.bytes(string(m.get("text")));
              if (!(m.get("bytes") instanceof List<?> values) || values.size() > 1_000_000)
                throw bad("bytes must be an integer array");
              byte[] data = new byte[values.size()];
              for (int i = 0; i < data.length; i++) {
                int n = number(values.get(i));
                if (n < 0 || n > 255) throw bad("Byte out of range");
                data[i] = (byte) n;
              }
              yield Segment.bytes(data);
            }
            case "eci" -> Segment.eci(number(m.get("assignmentNumber")));
            case "fnc1" -> Segment.fnc1();
            case "fnc1-second" -> Segment.fnc1Second(string(m.get("applicationIndicator")));
            case "structured-append" ->
                Segment.structuredAppend(
                    number(m.get("index")), number(m.get("total")), number(m.get("parity")));
            default -> throw new AssertionError();
          };
      s.add(x);
    }
    return Segments.normalize(s);
  }
}
