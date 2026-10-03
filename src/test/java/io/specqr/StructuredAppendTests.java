package io.specqr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Structured Append split, parity, merge, resource and golden-matrix tests. */
public final class StructuredAppendTests {
  private int assertions;

  private StructuredAppendTests() {}

  public static int run() {
    StructuredAppendTests tests = new StructuredAppendTests();
    tests.parity();
    tests.goldenFixtures();
    tests.unicodeAndManual();
    tests.versionAndOptions();
    tests.merge();
    tests.boundsAndOwnership();
    return tests.assertions;
  }

  public static void main(String[] args) {
    System.out.println("StructuredAppendTests: " + run() + " assertions passed");
  }

  private void check(boolean condition, String label) {
    assertions++;
    if (!condition) throw new AssertionError(label);
  }

  private void equal(Object actual, Object expected, String label) {
    check(Objects.equals(actual, expected), label + ": expected " + expected + ", got " + actual);
  }

  private void rejects(String code, Runnable action) {
    assertions++;
    try {
      action.run();
    } catch (SpecQrException error) {
      if (!error.code().equals(code))
        throw new AssertionError("Expected " + code + ", got " + error.code(), error);
      return;
    }
    throw new AssertionError("Expected " + code);
  }

  private void immutable(Runnable action) {
    assertions++;
    try {
      action.run();
    } catch (UnsupportedOperationException expected) {
      return;
    }
    throw new AssertionError("Collection was mutable");
  }

  private static Options v1(String mode) {
    return Options.builder().version(1).errorCorrectionLevel("L").maskPattern(0).mode(mode).build();
  }

  private static int xor(byte[] data) {
    int value = 0;
    for (byte b : data) value ^= b & 255;
    return value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> symbols(StructuredAppend.Result result) {
    return (List<Map<String, Object>>) result.diagnostics().get("symbols");
  }

  private static String hash(QrCode symbol) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      boolean[][] matrix = symbol.matrix();
      for (int y = 0; y < matrix.length; y++) {
        if (y > 0) digest.update((byte) '\n');
        for (boolean module : matrix[y]) digest.update((byte) (module ? '1' : '0'));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private void parity() {
    equal(StructuredAppend.calculateParity(""), 0, "empty text parity");
    equal(StructuredAppend.calculateParity(new byte[0]), 0, "empty binary parity");
    for (String text : List.of("ABC", "漢字", "é😀", "\u0000\uffff", "A".repeat(137)))
      equal(
          StructuredAppend.calculateParity(text),
          xor(text.getBytes(StandardCharsets.UTF_8)),
          "UTF-8 parity " + text);
    List<Segment> values =
        List.of(Segment.numeric("0123"), Segment.kanji("漢字"), Segment.bytes(new byte[] {0, 1, -1}));
    equal(
        StructuredAppend.calculateSegmentsParity(values),
        StructuredAppend.calculateParity("0123漢字") ^ 254,
        "canonical manual parity");
    rejects("INVALID_INPUT", () -> StructuredAppend.calculateParity("\ud800"));
    rejects("INVALID_INPUT", () -> StructuredAppend.calculateParity((String) null));
    rejects("INVALID_INPUT", () -> StructuredAppend.calculateSegmentsParity(List.of()));
    rejects(
        "INVALID_GS1",
        () ->
            StructuredAppend.calculateSegmentsParity(List.of(Segment.fnc1(), Segment.bytes("A"))));
    rejects(
        "INVALID_MODE",
        () ->
            StructuredAppend.calculateSegmentsParity(List.of(Segment.eci(26), Segment.bytes("A"))));
  }

  private void goldenFixtures() {
    StructuredAppend.Result high = StructuredAppend.generate("A".repeat(31), v1("alphanumeric"));
    equal(high.total(), 2, "high-level fixture total");
    equal(high.parity(), 65, "high-level parity");
    equal(high.inputLength(), 31, "scalar input length");
    equal(high.byteLength(), 31, "byte length");
    equal(symbols(high).get(0).get("inputLength"), 21, "greedy first chunk");
    equal(symbols(high).get(1).get("inputStart"), 21, "second start");
    equal(symbols(high).get(0).get("dataBitLength"), 149L, "first bits");
    equal(
        hash(high.symbols().get(0)),
        "fd73348ee286d6a4e25704e88d34f1342523ac2850eef066a1c2c607fdac8420",
        "high-level fixture matrix 1");
    equal(
        hash(high.symbols().get(1)),
        "ec9e0086883e50d28f136164ed0f61d45ff722b16d5c8266d4aa21ae4813b648",
        "high-level fixture matrix 2");
    List<Segment> values =
        List.of(
            Segment.alphanumeric("ABCDEFGHIJKLMNOPQRSTU"),
            Segment.numeric("12345678901234567890"),
            Segment.bytes(new byte[] {0, 1, 2, -1}));
    StructuredAppend.Result manual =
        StructuredAppend.generateSegments(
            values, v1("auto"), 16, StructuredAppend.DiagnosticOptions.full());
    equal(manual.total(), 2, "manual fixture total");
    equal(manual.parity(), 189, "manual fixture parity");
    equal(manual.inputLength(), 3, "segment input length");
    equal(manual.byteLength(), 45, "manual logical bytes");
    equal(symbols(manual).get(1).get("sourceSegmentStart"), 1, "manual segment start");
    equal(symbols(manual).get(1).get("splitUnitLength"), 5, "manual split units");
    equal(
        hash(manual.symbols().get(0)),
        "3ff5fa1a26a012e5f49615295a502813223aa5a958908ba1c518a26992ba96c8",
        "manual fixture matrix 1");
    equal(
        hash(manual.symbols().get(1)),
        "64e4a16efd806eccb0e3f34111690e725ec26c5f113a25baa233e5bb364fa531",
        "manual fixture matrix 2");
    byte[] bytes = new byte[31];
    for (int i = 0; i < 30; i++) bytes[i] = (byte) i;
    bytes[30] = -1;
    StructuredAppend.Result binary =
        StructuredAppend.generateSegments(
            List.of(Segment.bytes(bytes)), v1("auto").toBuilder().maskPattern(1).build());
    equal(binary.total(), 3, "binary chunk fixture total");
    equal(binary.parity(), 254, "binary parity");
    String[] binaryHashes = {
      "f6dfcc71e6e0d29c6a90087eecbab29ee5c7dee1701a3bb752f4f6d012d6d5d7",
      "102db6b47b49e297c2349009c756c6a3db4100e9833e12ac9f39c59df0fa25ad",
      "90137d43a9c423bd5e6d669bc725df5f0d6e7361f1572faebe1a944269889166"
    };
    for (int i = 0; i < 3; i++)
      equal(hash(binary.symbols().get(i)), binaryHashes[i], "binary fixture matrix " + i);
    StructuredAppend.Result kanji =
        StructuredAppend.generateSegments(
            List.of(
                Segment.alphanumeric("ABCDEFGHIJKLMNOPQRSTU"),
                Segment.kanji("漢字"),
                Segment.numeric("12345678901234567890")),
            v1("auto").toBuilder().maskPattern(2).build());
    equal(kanji.parity(), 102, "Kanji fixture original-UTF8 parity");
    equal(kanji.byteLength(), 47, "Kanji byte count");
    equal(
        hash(kanji.symbols().get(0)),
        "4a15156b33a6774fc8b20203a84dc6c1beef52a42e2089f8cba3c004f71b032a",
        "Kanji fixture matrix 1");
    equal(
        hash(kanji.symbols().get(1)),
        "82795c08d044218a34794d9c144baa6038f4f6ace2609e1fc91035357500a3ae",
        "Kanji fixture matrix 2");
  }

  private void unicodeAndManual() {
    for (String text :
        List.of("😀".repeat(17), "é漢😀abc".repeat(12), "1234567890".repeat(17), "漢字".repeat(20))) {
      for (boolean optimize : new boolean[] {false, true}) {
        StructuredAppend.Result result =
            StructuredAppend.generate(
                text, v1("auto").toBuilder().optimizeSegments(optimize).build());
        StringBuilder merged = new StringBuilder();
        int offset = 0, bytes = 0;
        for (int i = 0; i < result.total(); i++) {
          QrCode symbol = result.symbols().get(i);
          Segment header = symbol.segments().get(0);
          equal(header.index(), i + 1, "one-based index");
          equal(header.total(), result.total(), "header total");
          equal(header.parity(), result.parity(), "header parity");
          equal(symbols(result).get(i).get("inputStart"), offset, "scalar offset");
          equal(symbols(result).get(i).get("byteStart"), bytes, "UTF8 offset");
          for (Segment segment : symbol.segments().subList(1, symbol.segments().size())) {
            merged.append(segment.text());
            offset += segment.characterCount();
            bytes += segment.text().getBytes(StandardCharsets.UTF_8).length;
          }
          check(Segments.bitLength(symbol.segments(), symbol.version()) <= 152, "symbol fits");
        }
        equal(merged.toString(), text, "lossless scalar split");
        equal(result.inputLength(), text.codePointCount(0, text.length()), "scalar length");
        equal(result.byteLength(), text.getBytes(StandardCharsets.UTF_8).length, "UTF8 length");
      }
    }
    String unicode = "😀éA漢".repeat(10);
    StructuredAppend.Result manual =
        StructuredAppend.generateSegments(List.of(Segment.bytes(unicode)), v1("auto"));
    StringBuilder merged = new StringBuilder();
    for (QrCode symbol : manual.symbols())
      for (Segment segment : symbol.segments().subList(1, symbol.segments().size())) {
        equal(segment.mode(), "byte", "manual byte mode preserved");
        merged.append(segment.text());
      }
    equal(merged.toString(), unicode, "manual Unicode boundary");
    check(!manual.diagnostics().containsKey("splitUnits"), "default omits expanded units");
    StructuredAppend.Result expanded =
        StructuredAppend.generateSegments(
            List.of(Segment.bytes(unicode)),
            v1("auto"),
            16,
            StructuredAppend.DiagnosticOptions.full());
    equal(
        ((List<?>) expanded.diagnostics().get("splitUnits")).size(),
        40,
        "full detail scalar units");
    rejects(
        "DATA_TOO_LONG",
        () ->
            StructuredAppend.generateSegments(
                List.of(Segment.numeric("1".repeat(100))), v1("auto")));
  }

  private void versionAndOptions() {
    StructuredAppend.Result auto =
        StructuredAppend.generate(
            "A".repeat(70),
            Options.builder().errorCorrectionLevel("L").maxVersion(5).maskPattern(0).build(),
            2);
    equal(auto.symbols().get(0).version(), 2, "minimum version supporting two");
    equal(auto.total(), 2, "max symbols respected");
    equal(auto.diagnostics().get("versionSelection"), "auto-minimum", "version strategy");
    rejects("INVALID_INPUT", () -> StructuredAppend.generate("A", v1("auto")));
    rejects("INVALID_INPUT", () -> StructuredAppend.generate("", v1("auto")));
    rejects("DATA_TOO_LONG", () -> StructuredAppend.generate("A".repeat(300), v1("auto"), 2));
    rejects("INVALID_MODE", () -> StructuredAppend.generate("A".repeat(31), v1("auto"), 1));
    rejects("INVALID_MODE", () -> StructuredAppend.generate("A".repeat(31), v1("auto"), 17));
    rejects(
        "INVALID_MODE",
        () -> StructuredAppend.generate("A".repeat(31), v1("auto").toBuilder().eci(0).build()));
    rejects(
        "INVALID_MODE",
        () ->
            StructuredAppend.generate(
                "A".repeat(31), v1("auto").toBuilder().fnc1Second("00").build()));
    rejects(
        "INVALID_MODE",
        () ->
            StructuredAppend.generate(
                "A".repeat(31), v1("auto").toBuilder().boostErrorCorrection(true).build()));
    rejects(
        "INVALID_MODE",
        () ->
            StructuredAppend.generate(
                "A".repeat(31),
                v1("auto").toBuilder()
                    .structuredAppend(Segment.structuredAppend(1, 2, 0))
                    .build()));
    rejects(
        "INVALID_GS1",
        () -> StructuredAppend.generate("A".repeat(31), v1("auto").toBuilder().gs1(true).build()));
    rejects("INVALID_MODE", () -> StructuredAppend.generate(new byte[40], v1("numeric")));
    rejects(
        "INVALID_MODE",
        () ->
            StructuredAppend.generateSegments(List.of(Segment.bytes("A".repeat(31))), v1("byte")));
    rejects(
        "INVALID_INPUT",
        () ->
            StructuredAppend.generate(
                "A".repeat(31),
                v1("auto"),
                16,
                new StructuredAppend.DiagnosticOptions("bad", "output")));
    rejects(
        "INVALID_INPUT",
        () -> StructuredAppend.generateSegments(List.of(Segment.bytes("")), v1("auto")));
    rejects(
        "INVALID_MODE",
        () ->
            StructuredAppend.generateSegments(
                List.of(Segment.eci(26), Segment.bytes("A".repeat(31))), v1("auto")));
  }

  private void merge() {
    String text = "first😀second";
    int parity = StructuredAppend.calculateParity(text);
    StructuredAppend.MergeResult result =
        StructuredAppend.merge(
            List.of(
                new StructuredAppend.Part(2, 2, parity, "second"),
                new StructuredAppend.Part(1, 2, parity, "first😀")));
    equal(result.text(), text, "out of order merge");
    equal(result.parts().get(0).index(), 1, "sorted metadata");
    equal(
        result.diagnostics().get("byteLength"),
        text.getBytes(StandardCharsets.UTF_8).length,
        "merge UTF8 metrics");
    List<StructuredAppend.Part> binary =
        List.of(
            new StructuredAppend.Part(2, 2, 255, new byte[] {2, -1}),
            new StructuredAppend.Part(1, 2, 255, new byte[] {0, 1, 3}));
    check(
        Arrays.equals(StructuredAppend.merge(binary).bytes(), new byte[] {0, 1, 3, 2, -1}),
        "binary merge");
    rejects("INVALID_INPUT", () -> StructuredAppend.merge(List.of()));
    rejects(
        "INVALID_INPUT",
        () -> StructuredAppend.merge(List.of(new StructuredAppend.Part(1, 2, parity, "first😀"))));
    rejects(
        "INVALID_INPUT",
        () ->
            StructuredAppend.merge(
                List.of(
                    new StructuredAppend.Part(1, 2, 0, "A"),
                    new StructuredAppend.Part(1, 2, 0, "B"))));
    rejects(
        "INVALID_INPUT",
        () ->
            StructuredAppend.merge(
                List.of(
                    new StructuredAppend.Part(1, 2, 0, "A"),
                    new StructuredAppend.Part(2, 3, 0, "A"))));
    rejects(
        "INVALID_INPUT",
        () ->
            StructuredAppend.merge(
                List.of(
                    new StructuredAppend.Part(1, 2, 0, "A"),
                    new StructuredAppend.Part(2, 2, 1, "A"))));
    rejects(
        "INVALID_INPUT",
        () ->
            StructuredAppend.merge(
                List.of(
                    new StructuredAppend.Part(1, 2, 0, "A"),
                    new StructuredAppend.Part(2, 2, 0, new byte[] {65}))));
    rejects(
        "INVALID_INPUT",
        () ->
            StructuredAppend.merge(
                List.of(
                    new StructuredAppend.Part(1, 2, 1, "A"),
                    new StructuredAppend.Part(2, 2, 1, "A"))));
    for (int[] invalid : new int[][] {{0, 2, 0}, {1, 1, 0}, {1, 2, 256}, {3, 2, 0}})
      rejects(
          "INVALID_INPUT",
          () -> new StructuredAppend.Part(invalid[0], invalid[1], invalid[2], "A"));
    rejects(
        "INVALID_INPUT",
        () ->
            StructuredAppend.merge(
                List.of(
                    new StructuredAppend.Part(1, 2, 0, "\ud800"),
                    new StructuredAppend.Part(2, 2, 0, ""))));
  }

  @SuppressWarnings("unchecked")
  private void boundsAndOwnership() {
    rejects("DATA_TOO_LONG", () -> StructuredAppend.generate("1".repeat(1_000_001), v1("auto")));
    rejects("DATA_TOO_LONG", () -> StructuredAppend.calculateParity(new byte[1_000_001]));
    rejects("DATA_TOO_LONG", () -> new StructuredAppend.Part(1, 2, 0, new byte[1_000_001]));
    rejects("DATA_TOO_LONG", () -> new StructuredAppend.Part(1, 2, 0, "A".repeat(1_000_001)));
    rejects(
        "DATA_TOO_LONG",
        () ->
            new StructuredAppend.MergeResult(
                new byte[1_000_001],
                2,
                0,
                List.of(
                    new StructuredAppend.PartInfo(1, 2, 0, "binary", 1),
                    new StructuredAppend.PartInfo(2, 2, 0, "binary", 1)),
                Map.of()));
    rejects(
        "DATA_TOO_LONG",
        () ->
            StructuredAppend.calculateSegmentsParity(
                Collections.nCopies(16_385, Segment.bytes("A"))));
    rejects(
        "DATA_TOO_LONG",
        () ->
            StructuredAppend.merge(
                List.of(
                    new StructuredAppend.Part(1, 2, 0, "A".repeat(500_001)),
                    new StructuredAppend.Part(2, 2, 0, "A".repeat(500_001)))));
    StructuredAppend.Result result = StructuredAppend.generate("A".repeat(31), v1("auto"));
    immutable(() -> result.symbols().clear());
    immutable(() -> result.diagnostics().clear());
    immutable(() -> symbols(result).get(0).clear());
    byte[] original = {1};
    StructuredAppend.Part part = new StructuredAppend.Part(1, 2, 3, original);
    original[0] = 9;
    equal(((byte[]) part.data())[0], (byte) 1, "part input snapshot");
    byte[] obtained = (byte[]) part.data();
    obtained[0] = 9;
    equal(((byte[]) part.data())[0], (byte) 1, "part accessor snapshot");
    StructuredAppend.MergeResult merged =
        StructuredAppend.merge(List.of(part, new StructuredAppend.Part(2, 2, 3, new byte[] {2})));
    byte[] data = merged.bytes();
    data[0] = 9;
    equal(merged.bytes()[0], (byte) 1, "merge accessor snapshot");
    immutable(() -> merged.parts().clear());
    immutable(() -> ((Map<String, Object>) merged.diagnostics().get("parityCheck")).clear());
  }
}
