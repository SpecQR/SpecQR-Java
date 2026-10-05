package io.specqr;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Behavioral contracts audited after the TypeScript regression fixes. */
public final class CrossPortRegressionTests {
  private static int assertions;

  private CrossPortRegressionTests() {}

  private static void check(boolean value, String label) {
    assertions++;
    if (!value) throw new AssertionError(label);
  }

  private static void error(String code, Runnable action) {
    assertions++;
    try {
      action.run();
    } catch (SpecQrException e) {
      if (code.equals(e.code())) return;
      throw new AssertionError("Expected " + code + ", got " + e.code(), e);
    }
    throw new AssertionError("Expected " + code);
  }

  public static void main(String[] args) {
    System.out.println("CrossPortRegressionTests: PASS (" + run() + " assertions)");
  }

  public static int run() {
    assertions = 0;
    percentPayloads();
    digitalLinks();
    printGeometry();
    eccAndBounds();
    return assertions;
  }

  private static void percentPayloads() {
    for (String text :
        List.of(
            "10ABC%DEF",
            "10ABC%%DEF", "10%ABC", "10ABC%", "10ABC%\u001d21DEF%", "10" + "A".repeat(19) + "%")) {
      for (boolean optimize : new boolean[] {false, true}) {
        Options options =
            Options.builder().gs1(true).optimizeSegments(optimize).maskPattern(3).build();
        QrCode actual = SpecQr.generate(text, options);
        Plan plan = SpecQr.estimate(text, options);
        QrCode explicit = SpecQr.generate(text, options.toBuilder().mode("byte").build());
        check(plan.ok() && plan.version() == actual.version(), "GS1 planning agrees");
        check(actual.segments().get(1).mode().equals("byte"), "safe byte fallback");
        check(
            Arrays.equals(
                actual.segments().get(1).logicalBytes(), text.getBytes(StandardCharsets.UTF_8)),
            "literal payload preserved");
        check(Arrays.equals(actual.codewords(), explicit.codewords()), "explicit byte equivalent");
        error(
            "INVALID_MODE",
            () -> SpecQr.generate(text, options.toBuilder().mode("alphanumeric").build()));
        error(
            "INVALID_MODE",
            () -> SpecQr.estimate(text, options.toBuilder().mode("alphanumeric").build()));
      }
    }
    for (String indicator : List.of("37", "A")) {
      for (String text : List.of("%", "%%", "ABC%DEF%%", "AA%\u001dBB%%", "漢字%")) {
        Options options = Options.builder().fnc1Second(indicator).build();
        QrCode actual = SpecQr.generate(text, options);
        check(
            Arrays.equals(
                actual.segments().get(1).logicalBytes(), text.getBytes(StandardCharsets.UTF_8)),
            "second position literal payload");
        error(
            "INVALID_MODE",
            () -> SpecQr.generate(text, options.toBuilder().mode("alphanumeric").build()));
      }
    }
    for (String manual : List.of("ABC%DEF", "ABC%%DEF")) {
      List<Segment> segments = List.of(Segment.fnc1(), Segment.alphanumeric(manual));
      QrCode q = SpecQr.generateSegments(segments);
      check(
          Arrays.equals(
              q.segments().get(1).logicalBytes(), manual.getBytes(StandardCharsets.UTF_8)),
          "manual escaping unchanged");
      check(SpecQr.analyzeSegments(segments).ok(), "manual planning accepted");
    }
    check(
        SpecQr.generate("ABC%DEF").segments().get(0).mode().equals("alphanumeric"),
        "non-FNC1 alpha unchanged");
    Options fixed = Options.builder().gs1(true).version(1).errorCorrectionLevel("L").build();
    String fits = "10" + "A".repeat(14) + "%", over = "10" + "A".repeat(15) + "%";
    check(SpecQr.estimate(fits, fixed).ok(), "17-byte FNC1 version1 L fits");
    check(!SpecQr.estimate(over, fixed).ok(), "18-byte FNC1 version1 L overflows safely");
    error("DATA_TOO_LONG", () -> SpecQr.generate(over, fixed));
    check(
        SpecQr.generate(over, fixed.toBuilder().version(null).build()).version() > 1,
        "auto grows rather than corrupts");
    for (int version : new int[] {9, 10, 26, 27}) {
      Plan plan = SpecQr.estimate("A%", Options.builder().fnc1Second("A").version(version).build());
      check(plan.dataBitLength() == (version < 10 ? 40 : 48), "byte count width boundary");
    }
  }

  private static void digitalLinks() {
    String root = "https://example.com/01/04912345678904";
    Gs1.Element primary = new Gs1.Element("01", "04912345678904");
    for (String dot : List.of(".", "..", "%2e", "%2E%2e", ".%2E", "%2e.")) {
      String value = dot.toLowerCase().replace("%2e", ".");
      String raw = root + "/10/" + dot;
      List<Gs1.Element> want = List.of(primary, new Gs1.Element("10", value));
      check(
          Gs1.parseDigitalLink(raw).elements().equals(want),
          "dot path retained without normalization loss");
      check(Gs1.validateDigitalLink(raw).ok(), "lossless dot path accepted");
      check(
          Gs1.normalizeDigitalLink(raw).equals(root + "?10=" + value),
          "dot path moved safely to query");
      check(
          Gs1.createDigitalLink(want, "https://example.com").equals(root + "?10=" + value),
          "builder dot moved safely to query");
    }
    String erased = "https://example.com/01/./../01/04912345678904";
    error("INVALID_GS1", () -> Gs1.parseDigitalLink(erased));
    error("INVALID_GS1", () -> Gs1.normalizeDigitalLink(erased));
    check(!Gs1.validateDigitalLink(erased).ok(), "invalid first primary cannot disappear");
    String query = root + "?10=..&21=.&utm=a&utm=b";
    check(
        Gs1.normalizeDigitalLink(query).equals(query),
        "query dots and repeated unknown query preserved");
    check(
        Gs1.parseDigitalLink(query)
            .elements()
            .equals(List.of(primary, new Gs1.Element("10", ".."), new Gs1.Element("21", "."))),
        "query payload intact");
    check(
        Gs1.createDigitalLink(List.of(primary, new Gs1.Element("10", "%2e")), "https://example.com")
            .equals(root + "/10/%252e"),
        "literal percent encoding not dot");
    check(
        Gs1.createDigitalLink(List.of(primary), "https://example.com/a/../b")
            .equals("https://example.com/b/01/04912345678904"),
        "safe base normalization retained");
  }

  private static void printGeometry() {
    // These APIs always expose diagnostics and validate immutable options for any version.
    // Keep their existing conservative version-40 geometry guard, including for fixed version 1.
    for (double dpi :
        new double[] {
          Double.MIN_VALUE, 1e-305, 1e-304, 0, -1, Double.NaN, Double.POSITIVE_INFINITY
        }) {
      for (int version : new int[] {1, 40}) {
        error("INVALID_INPUT", () -> Options.builder().version(version).printDpi(dpi).build());
      }
    }
    for (int version : new int[] {1, 40}) {
      Options options = Options.builder().version(version).printDpi(1e-303).build();
      Map<?, ?> print = (Map<?, ?>) SpecQr.generate("1", options).diagnostics().get("print");
      check(
          Double.isFinite((Double) print.get("moduleSizeMm"))
              && Double.isFinite((Double) print.get("symbolSizeMm")),
          "accepted tiny DPI finite");
    }
    Options normal = Options.builder().version(1).printDpi(300.0).maskPattern(0).build();
    QrCode q = SpecQr.generate("1", normal);
    Map<?, ?> print = (Map<?, ?>) q.diagnostics().get("print");
    check(
        Math.abs((Double) print.get("moduleSizeMm") - 8.0 / 300 * 25.4) < 1e-12,
        "normal print geometry unchanged");
    check(
        Arrays.equals(
            q.codewords(),
            SpecQr.generate("1", normal.toBuilder().printDpi(null).build()).codewords()),
        "valid DPI does not affect QR data");
  }

  private static void eccAndBounds() {
    for (String invalid :
        new String[] {
          "constructor", "toString", "valueOf", "__proto__", "hasOwnProperty", "", "m", null
        }) {
      error("INVALID_INPUT", () -> Options.builder().errorCorrectionLevel(invalid).build());
      error("INVALID_INPUT", () -> SpecQr.getCapacity(1, invalid, "byte"));
    }
    for (String valid : List.of("L", "M", "Q", "H"))
      check(
          SpecQr.generate("A", Options.builder().errorCorrectionLevel(valid).build())
              .errorCorrectionLevel()
              .equals(valid),
          "valid ECC retained");
    Options second = Options.builder().fnc1Second("37").build();
    check(!SpecQr.estimate("%".repeat(100_000), second).ok(), "large percent estimate bounded");
    error("DATA_TOO_LONG", () -> SpecQr.generate("%".repeat(100_000), second));
    error("DATA_TOO_LONG", () -> SpecQr.estimate("%".repeat(1_000_001), second));
  }
}
