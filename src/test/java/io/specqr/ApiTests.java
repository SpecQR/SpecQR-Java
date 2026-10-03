package io.specqr;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public final class ApiTests {
  private ApiTests() {}

  private static int assertions;

  private static void check(boolean b, String m) {
    assertions++;
    if (!b) throw new AssertionError(m);
  }

  private static void error(String code, Runnable r) {
    assertions++;
    try {
      r.run();
      throw new AssertionError("Expected " + code);
    } catch (SpecQrException e) {
      if (!e.code().equals(code))
        throw new AssertionError("Expected " + code + " got " + e.code(), e);
    }
  }

  private static void immutable(Runnable r) {
    assertions++;
    try {
      r.run();
      throw new AssertionError("Expected immutable");
    } catch (UnsupportedOperationException expected) {
    }
  }

  @SuppressWarnings("unchecked")
  public static int run() throws Exception {
    assertions = 0;
    byte[] diagBytes = {1};
    Plan constructed =
        new Plan(true, 1, 1, "M", "M", false, 0, 128, 128, List.of(), Map.of("bytes", diagBytes));
    diagBytes[0] = 2;
    check(
        ((List<?>) constructed.diagnostics().get("bytes")).get(0).equals((byte) 1),
        "constructor diagnostic deep freeze");
    immutable(() -> ((List<Object>) constructed.diagnostics().get("bytes")).clear());
    java.util.Map<String, Object> cyclic = new java.util.HashMap<>();
    cyclic.put("self", cyclic);
    error(
        "INVALID_INPUT",
        () -> new Plan(true, 1, 1, "M", "M", false, 0, 128, 128, List.of(), cyclic));
    java.util.List<Object> huge =
        new java.util.AbstractList<>() {
          public int size() {
            return Integer.MAX_VALUE;
          }

          public Object get(int i) {
            throw new AssertionError("Traversed before preflight");
          }
        };
    error(
        "INVALID_INPUT",
        () -> new Plan(true, 1, 1, "M", "M", false, 0, 128, 128, List.of(), Map.of("huge", huge)));
    Options o = Options.defaults();
    check(
        o.errorCorrectionLevel().equals("M")
            && o.optimizeSegments()
            && o.margin() == 4
            && o.scale() == 8,
        "defaults");
    Options.Builder b = o.toBuilder();
    b.version(2);
    check(o.version() == null && b.build().version() == 2, "builder ownership");
    for (int v : new int[] {0, 41})
      error("INVALID_VERSION", () -> Options.builder().version(v).build());
    error("INVALID_VERSION", () -> Options.builder().minVersion(5).maxVersion(4).build());
    error("INVALID_INPUT", () -> Options.builder().errorCorrectionLevel("m").build());
    error("INVALID_MODE", () -> Options.builder().mode("unsupported").build());
    error("INVALID_ECI", () -> Options.builder().eci(1_000_000).build());
    error("INVALID_MODE", () -> Options.builder().eci(26).gs1(true).build());
    error("INVALID_INPUT", () -> Options.builder().maskPattern(8).build());
    error("INVALID_INPUT", () -> Options.builder().scale(0).build());
    error("INVALID_INPUT", () -> Options.builder().margin(-1).build());
    for (double d : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0, -1, Double.MIN_VALUE})
      error("INVALID_INPUT", () -> Options.builder().printDpi(d).build());
    QrCode q = SpecQr.generate("HELLO WORLD");
    check(q.version() == 1 && q.size() == 21, "basic symbol");
    check(q.errorCorrectionLevel().equals("M"), "ecc");
    check(
        q.dataCodewords().length == 16
            && q.errorCorrectionCodewords().length == 10
            && q.codewords().length == 26,
        "data lengths");
    boolean original = q.module(0, 0);
    boolean[][] m = q.matrix();
    m[0][0] = !m[0][0];
    check(q.module(0, 0) == original, "matrix snapshot");
    byte[] d = q.dataCodewords();
    d[0] ^= 1;
    check(!Arrays.equals(d, q.dataCodewords()), "data snapshot");
    byte[] w = q.codewords();
    w[0] ^= 1;
    check(!Arrays.equals(w, q.codewords()), "codewords snapshot");
    immutable(() -> q.segments().clear());
    immutable(() -> q.diagnostics().clear());
    immutable(() -> ((Map<String, Object>) q.diagnostics().get("quietZone")).clear());
    immutable(() -> ((List<Object>) q.diagnostics().get("warnings")).clear());
    error("INVALID_INPUT", () -> q.module(-1, 0));
    error("INVALID_INPUT", () -> SpecQr.generate((String) null));
    error("INVALID_INPUT", () -> SpecQr.generate((byte[]) null));
    error("INVALID_INPUT", () -> SpecQr.generate("a", null));
    error("INVALID_INPUT", () -> SpecQr.generate("\ud800"));
    error("INVALID_INPUT", () -> SpecQr.generate("\udc00"));
    check(SpecQr.generate("😀").version() == 1, "supplementary text");
    for (int v = 1; v <= 40; v++)
      for (String e : List.of("L", "M", "Q", "H"))
        for (String mode : List.of("numeric", "alphanumeric", "byte", "kanji")) {
          Capacity c = SpecQr.getCapacity(v, e, mode);
          check(
              c.capacityBits() == Tables.dataCodewords(v, e) * 8 && c.size() == 17 + 4 * v,
              "capacity metadata");
          String unit =
              switch (mode) {
                case "numeric" -> "1";
                case "alphanumeric" -> "A";
                case "kanji" -> "漢";
                default -> "a";
              };
          Options fixed = Options.builder().version(v).errorCorrectionLevel(e).mode(mode).build();
          Plan fit = SpecQr.estimate(unit.repeat(c.maximum()), fixed);
          Plan overflow = SpecQr.estimate(unit.repeat(c.maximum() + 1), fixed);
          check(fit.ok(), "at capacity");
          check(!overflow.ok() && overflow.overflowBits() > 0, "over capacity");
        }
    Plan over = SpecQr.estimate("x".repeat(3000), Options.builder().maxVersion(1).build());
    check(!over.ok() && over.version() == null && over.capacityVersion() == 1, "auto overflow");
    check(over.requiredBits() > over.capacityBits() && over.remainingBits() < 0, "overflow bits");
    check(
        ((List<Map<String, Object>>) over.diagnostics().get("warnings"))
            .stream().noneMatch(x -> x.get("code").equals("CAPACITY_NEAR_LIMIT")),
        "no near-limit warning for overflow");
    error(
        "DATA_TOO_LONG",
        () -> SpecQr.generate("x".repeat(3000), Options.builder().maxVersion(1).build()));
    Plan fixedOver = SpecQr.estimate("x".repeat(3000), Options.builder().version(1).build());
    check(fixedOver.version() == 1, "fixed overflow version");
    Plan big = SpecQr.estimate("1".repeat(100_000));
    check(!big.ok() && big.requiredBits() > 300_000, "bounded giant plan");
    error("DATA_TOO_LONG", () -> SpecQr.generate("x".repeat(1_000_001)));
    error("DATA_TOO_LONG", () -> SpecQr.generate(new byte[1_000_001]));
    QrCode boosted =
        SpecQr.generate(
            "A", Options.builder().errorCorrectionLevel("L").boostErrorCorrection(true).build());
    check(boosted.version() == 1 && boosted.errorCorrectionLevel().equals("H"), "boost");
    QrCode manual = SpecQr.generateSegments(List.of(Segment.numeric("123"), Segment.bytes("abc")));
    check(manual.segments().size() == 2, "manual boundaries");
    check(manual.diagnostics().get("mode").equals("mixed"), "mixed diagnostic");
    QrCode eci = SpecQr.generate("漢字", Options.builder().eci(26).build());
    check(
        eci.segments().get(0).mode().equals("eci")
            && eci.segments().stream().noneMatch(s -> s.mode().equals("kanji")),
        "ECI disables automatic Kanji");
    for (String value : List.of("10ABC%DEF", "10ABC%%DEF")) {
      QrCode gs = SpecQr.generate(value, Options.builder().gs1(true).build());
      check(
          gs.segments().size() == 2 && gs.segments().get(1).mode().equals("byte"),
          "GS1 percent byte fallback");
      check(
          Arrays.equals(
              gs.segments().get(1).logicalBytes(),
              value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          "GS1 literal percent");
      error(
          "INVALID_MODE",
          () -> SpecQr.generate(value, Options.builder().gs1(true).mode("alphanumeric").build()));
    }
    QrCode second = SpecQr.generate("A%B", Options.builder().fnc1Second("A").build());
    check(second.segments().get(1).mode().equals("byte"), "FNC1 second percent");
    error(
        "INVALID_GS1", () -> SpecQr.generate(new byte[] {1}, Options.builder().gs1(true).build()));
    error(
        "INVALID_GS1",
        () ->
            SpecQr.generateSegments(
                List.of(Segment.fnc1(), Segment.bytes("a")), Options.builder().gs1(true).build()));
    QrCode styled =
        SpecQr.generate(
            "X",
            Options.builder()
                .margin(0)
                .foreground("#777")
                .background("#888")
                .printDpi(300.0)
                .scale(1)
                .build());
    List<Map<String, Object>> warnings =
        (List<Map<String, Object>>) styled.diagnostics().get("warnings");
    check(warnings.stream().anyMatch(x -> x.get("code").equals("SCAN_RISK")), "scan warnings");
    check(
        SpecQr.estimate("A").diagnostics().get("maskEvaluated").equals(false),
        "planning does not build mask");
    error("INVALID_MODE", () -> SpecQr.getCapacity(1, "M", "auto"));
    error("INVALID_INPUT", () -> SpecQr.getCapacity(1, "M", "byte", -1));
    byte[] payload = {0, 1, 2};
    Plan snapshot = SpecQr.estimate(payload);
    payload[0] = 9;
    check(snapshot.segments().get(0).binary()[0] == 0, "binary plan snapshot");
    var executor = Executors.newFixedThreadPool(8);
    try {
      List<Callable<Boolean>> jobs = new java.util.ArrayList<>();
      for (int i = 0; i < 256; i++) {
        final int k = i;
        jobs.add(
            () -> {
              String text = "Concurrent漢字😀" + k;
              QrCode a = SpecQr.generate(text), c = SpecQr.generate(text);
              return Arrays.deepEquals(a.matrix(), c.matrix())
                  && Arrays.equals(a.toPng(), c.toPng());
            });
      }
      for (var f : executor.invokeAll(jobs)) check(f.get(), "concurrency replay");
    } finally {
      executor.shutdownNow();
    }
    return assertions;
  }
}
