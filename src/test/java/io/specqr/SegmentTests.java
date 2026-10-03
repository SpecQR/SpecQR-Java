package io.specqr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** Dependency-free manual segments, Unicode, optimizer, and resource-limit tests. */
public final class SegmentTests {
  private SegmentTests() {}

  private static int assertions;

  public static int run() {
    assertions = 0;
    basicSegments();
    controls();
    defensiveOwnership();
    strictUnicodeAndModes();
    limits();
    exhaustiveKanjiTable();
    exactJavaScriptGoldenCorpus();
    independentMinimumCosts();
    return assertions;
  }

  public static void main(String[] args) {
    System.out.println("SegmentTests passed: " + run() + " assertions");
  }

  private static void basicSegments() {
    Segment numeric = Segment.numeric("01234567");
    equal(
        "00010000001000000000110001010110011000011",
        bitString(numeric.bits(1)),
        "numeric reference bits");
    equal(8, numeric.count(), "numeric count");
    equal(27, numeric.dataBitLength(), "numeric payload bits");
    equal(41L, numeric.totalBits(1), "numeric total bits");
    equal(39L, Segment.alphanumeric("ABCD").totalBits(27), "alphanumeric version 27 bits");
    equal(22, Segment.alphanumeric("ABCD").dataBitLength(), "alphanumeric payload");
    equal(6, Segment.alphanumeric("A").dataBitLength(), "single alphanumeric payload");
    Segment unicode = Segment.bytes("é😀");
    equal(2, unicode.characterCount(), "Unicode scalar count");
    equal(6, unicode.count(), "UTF-8 byte-mode header count");
    equal("c3a9f09f9880", HexFormat.of().formatHex(unicode.logicalBytes()), "canonical UTF-8");
    Segment kanji = Segment.kanji("漢字");
    equal(2, kanji.characterCount(), "Kanji scalar count");
    equal(4, kanji.byteCount(), "Kanji diagnostic bytes");
    equal(6, kanji.logicalBytes().length, "Kanji logical UTF-8 parity bytes");
    equal(26, kanji.dataBitLength(), "Kanji payload bits");
    equal("byte", Segments.create("", 1, "auto", true, true).get(0).mode(), "empty auto mode");
    equal(
        "numeric",
        Segments.create("123", 1, "auto", false, true).get(0).mode(),
        "unoptimized numeric");
    equal(
        "alphanumeric",
        Segments.create("A B", 1, "auto", false, true).get(0).mode(),
        "unoptimized alphanumeric");
    equal(
        "kanji", Segments.create("漢字", 1, "auto", false, true).get(0).mode(), "unoptimized Kanji");
    equal(
        "byte",
        Segments.create("漢字", 1, "auto", false, false).get(0).mode(),
        "ECI-compatible byte selection");
    equal(
        "byte",
        Segments.create("漢字", 1, "auto", true, false).get(0).mode(),
        "ECI-compatible optimized selection");
    equal(
        "kanji",
        Segments.create("漢字", 1, "kanji", true, false).get(0).mode(),
        "explicit Kanji with ECI");
    equal(
        List.of(
            Segment.alphanumeric("ABCD"),
            Segment.numeric("12345678901234567890"),
            Segment.bytes("abcd")),
        Segments.create("ABCD12345678901234567890abcd", 1, "auto", true, true),
        "mixed segmentation");
    equal(0L, Segments.bitLength(List.of(), 1), "empty manual list bits");
    equal(0, Segments.bits(List.of(), 1).length, "empty manual materialization");
  }

  private static void controls() {
    equal("0101", bitString(Segment.fnc1().bits(1)), "FNC1 first bits");
    equal("100100001011", bitString(Segment.fnc1Second("11").bits(1)), "FNC1 numeric indicator");
    equal("100110100101", bitString(Segment.fnc1Second("A").bits(1)), "FNC1 alphabetic indicator");
    equal(222, Segment.fnc1Second("z").applicationIndicatorCodeword(), "FNC1 lowercase indicator");
    equal(
        "00110000000110100101",
        bitString(Segment.structuredAppend(1, 2, 0xA5).bits(1)),
        "Structured Append bits");
    equal(20L, Segment.structuredAppend(16, 16, 255).totalBits(40), "Structured Append length");
    equal("011100000000", bitString(Segment.eci(0).bits(1)), "ECI 0 bits");
    equal("011101111111", bitString(Segment.eci(127).bits(1)), "ECI 127 bits");
    equal("01111000000010000000", bitString(Segment.eci(128).bits(1)), "ECI 128 bits");
    equal("01111011111111111111", bitString(Segment.eci(16383).bits(1)), "ECI 16383 bits");
    equal("0111110000000100000000000000", bitString(Segment.eci(16384).bits(1)), "ECI 16384 bits");
    equal(28, Segment.eci(999999).bits(1).length, "ECI maximum length");
    equal(
        4,
        Segments.normalize(
                List.of(
                    Segment.eci(26),
                    Segment.bytes("é"),
                    Segment.eci(3),
                    Segment.bytes(new byte[] {-1})))
            .size(),
        "ECI transitions preserved");
    equal(
        "A%B%%C",
        Segments.normalize(List.of(Segment.fnc1(), Segment.alphanumeric("A%B%%C"))).get(1).text(),
        "manual FNC1 percent escaping preserved");
    fails("INVALID_ECI", () -> Segment.eci(-1));
    fails("INVALID_ECI", () -> Segment.eci(1_000_000));
    for (String indicator : new String[] {"", "1", "123", "AB", "é", "１２", "١٢", "A\n"}) {
      fails("INVALID_MODE", () -> Segment.fnc1Second(indicator));
    }
    fails("INVALID_MODE", () -> Segment.fnc1Second(null));
    fails("INVALID_MODE", () -> Segment.structuredAppend(0, 2, 0));
    fails("INVALID_MODE", () -> Segment.structuredAppend(1, 1, 0));
    fails("INVALID_MODE", () -> Segment.structuredAppend(3, 2, 0));
    fails("INVALID_MODE", () -> Segment.structuredAppend(1, 17, 0));
    fails("INVALID_MODE", () -> Segment.structuredAppend(1, 2, 256));
    fails("INVALID_GS1", () -> Segments.normalize(List.of(Segment.fnc1(), Segment.fnc1())));
    fails("INVALID_GS1", () -> Segments.normalize(List.of(Segment.bytes("x"), Segment.fnc1())));
    fails(
        "INVALID_MODE",
        () -> Segments.normalize(List.of(Segment.bytes("x"), Segment.fnc1Second("A"))));
    fails(
        "INVALID_MODE",
        () -> Segments.normalize(List.of(Segment.bytes("x"), Segment.structuredAppend(1, 2, 0))));
    fails("INVALID_GS1", () -> Segments.normalize(List.of(Segment.fnc1(), Segment.eci(26))));
    fails(
        "INVALID_MODE",
        () -> Segments.normalize(List.of(Segment.fnc1Second("A"), Segment.eci(26))));
    fails(
        "INVALID_MODE",
        () -> Segments.normalize(List.of(Segment.structuredAppend(1, 2, 0), Segment.eci(26))));
  }

  private static void defensiveOwnership() {
    byte[] input = {0, 1, -1};
    Segment segment = Segment.bytes(input);
    input[0] = 99;
    segment.binary()[1] = 99;
    segment.logicalBytes()[2] = 99;
    equal("0001ff", HexFormat.of().formatHex(segment.logicalBytes()), "binary payload ownership");
    equal(0, segment.characterCount(), "binary character count");
    equal(null, segment.text(), "binary has no text");
    equal(null, Segment.bytes("x").binary(), "text has no binary source");
    equal(null, Segment.fnc1().binary(), "control has no binary source");
    equal(0, Segment.fnc1().logicalBytes().length, "control logical bytes");
    equal(null, segment.assignment(), "unrelated ECI accessor");
    equal(null, segment.index(), "unrelated Structured Append accessor");
    equal(Segment.bytes(new byte[] {0, 1, -1}), segment, "segment value equality");
    equal(
        Segment.bytes(new byte[] {0, 1, -1}).hashCode(), segment.hashCode(), "segment value hash");
    int[] bits = segment.bits(1);
    bits[0] = 1;
    equal(0, segment.bits(1)[0], "fresh bit arrays");
    ArrayList<Segment> mutable = new ArrayList<>(List.of(segment));
    List<Segment> normalized = Segments.normalize(mutable);
    mutable.clear();
    equal(1, normalized.size(), "list snapshot ownership");
    try {
      normalized.clear();
      throw new AssertionError("Mutable normalized list");
    } catch (UnsupportedOperationException expected) {
    }
    try {
      Segments.create("1", 1, "auto", true, true).clear();
      throw new AssertionError("Mutable created list");
    } catch (UnsupportedOperationException expected) {
    }
  }

  private static void strictUnicodeAndModes() {
    for (String text :
        new String[] {"\uD800", "\uDC00", "\uD800x", "x\uDC00", "\uDC00\uD800", "\uD800\uD800"}) {
      fails("INVALID_INPUT", () -> Segment.bytes(text));
      fails("INVALID_INPUT", () -> Segments.create(text, 1, "auto", true, true));
    }
    equal(1, Segment.bytes("\uDBFF\uDFFF").characterCount(), "highest Unicode scalar");
    fails("INVALID_INPUT", () -> Segment.bytes((String) null));
    fails("INVALID_INPUT", () -> Segment.bytes((byte[]) null));
    fails("INVALID_INPUT", () -> Segments.normalize(null));
    fails("INVALID_INPUT", () -> Segments.normalize(Arrays.asList((Segment) null)));
    fails("INVALID_MODE", () -> Segment.numeric("１２"));
    fails("INVALID_MODE", () -> Segment.numeric("1\n"));
    fails("INVALID_MODE", () -> Segment.alphanumeric("a"));
    fails("INVALID_MODE", () -> Segment.kanji("😀"));
    fails("INVALID_MODE", () -> Segment.kanji("A"));
    fails("INVALID_MODE", () -> Segments.create("x", 1, "unknown", true, true));
    fails("INVALID_MODE", () -> Segments.create("x", 1, null, true, true));
    fails("INVALID_MODE", () -> Segments.create(new byte[] {1}, 1, "numeric", true, true));
    fails("INVALID_VERSION", () -> Segment.fnc1().bits(0));
    fails("INVALID_VERSION", () -> Segment.eci(26).totalBits(41));
    fails("INVALID_VERSION", () -> Segments.bitLength(List.of(), 0));
    fails("INVALID_VERSION", () -> Segments.bits(List.of(), 41));
    fails("INVALID_VERSION", () -> Segments.create("", 0, "auto", true, true));
    Segments.OptimizationTracker tracker = new Segments.OptimizationTracker(1, true);
    fails("INVALID_INPUT", () -> tracker.append(""));
    fails("INVALID_INPUT", () -> tracker.append("ab"));
    fails("INVALID_INPUT", () -> tracker.append(0xD800));
    fails("INVALID_INPUT", () -> tracker.append(0x110000));
    fails("INVALID_INPUT", () -> tracker.append(-1));
  }

  private static void limits() {
    Segment oversizedCount = Segment.bytes(new byte[256]);
    equal(2060L, oversizedCount.totalBits(1), "oversized count arithmetic length");
    equal(
        2060L,
        Segments.bitLength(List.of(oversizedCount), 1),
        "oversized sequence arithmetic length");
    fails("DATA_TOO_LONG", () -> oversizedCount.bits(1));
    equal(2068, oversizedCount.bits(10).length, "count width grows at version 10");
    equal(23648, Segment.numeric("0".repeat(7089)).bits(40).length, "maximum single-symbol bits");
    fails("DATA_TOO_LONG", () -> Segment.numeric("0".repeat(7090)).bits(40));
    fails(
        "DATA_TOO_LONG",
        () ->
            Segments.bits(
                List.of(Segment.bytes(new byte[2000]), Segment.bytes(new byte[1000])), 40));
    fails("DATA_TOO_LONG", () -> Segments.create("0".repeat(7090), 40, "auto", true, true));
    equal(
        1,
        Segments.create("0".repeat(7089), 40, "auto", true, true).size(),
        "optimizer bound accepted");
    equal(
        7090,
        Segments.create("0".repeat(7090), 40, "auto", false, true).get(0).count(),
        "unoptimized larger planning input");
    fails("DATA_TOO_LONG", () -> Segment.bytes(new byte[1_000_001]));
    fails("DATA_TOO_LONG", () -> Segment.bytes("x".repeat(1_000_001)));
    Segment million = Segment.bytes(new byte[1_000_000]);
    equal(8_000_020L, million.totalBits(40), "bounded large arithmetic");
    fails("DATA_TOO_LONG", () -> Segments.normalize(List.of(million, Segment.bytes("x"))));
    List<Segment> many = new ArrayList<>();
    for (int i = 0; i < 16384; i++) many.add(Segment.bytes(""));
    equal(16384, Segments.normalize(many).size(), "manual segment bound accepted");
    many.add(Segment.bytes(""));
    fails("DATA_TOO_LONG", () -> Segments.normalize(many));
  }

  private static void exhaustiveKanjiTable() {
    MessageDigest digest = sha256();
    int count = 0;
    for (int cp = 0; cp <= 0xFFFF; cp++) {
      int code = Kanji.code(cp);
      if (code == 0) continue;
      count++;
      digest.update(new byte[] {(byte) (cp >>> 8), (byte) cp, (byte) (code >>> 8), (byte) code});
      int value = Kanji.value(cp);
      if (value < 0 || value >= 8192) throw new AssertionError("Kanji value outside 13 bits");
      Segment segment = Segment.kanji(String.valueOf((char) cp));
      equal(13, segment.dataBitLength(), "Kanji payload width");
      equal(25, segment.bits(1).length, "Kanji segment width");
    }
    equal(6953, count, "complete WHATWG repertoire size");
    // SHA-256 of (Unicode scalar, first Shift_JIS pair), big-endian and sorted by scalar.
    // Generated directly from pinned SpecQR JS TextDecoder ranges, not Java Charset.
    equal(
        "be0a434df7babc88848f0930ceb3ea9424c6715d89ed4b1b19bcb416f9892fa8",
        HexFormat.of().formatHex(digest.digest()),
        "exhaustive WHATWG first-mapping parity");
    equal(0x8ABF, Kanji.code('漢'), "Kanji reference mapping");
    equal(0x8160, Kanji.code('～'), "WHATWG fullwidth tilde");
    equal(0, Kanji.code('〜'), "JIS wave dash must not silently alias");
    equal(0, Kanji.code('−'), "JIS minus must not silently alias");
    equal(0x817C, Kanji.code('－'), "WHATWG fullwidth minus");
    equal(0x8754, Kanji.code('Ⅰ'), "first duplicate mapping");
  }

  private static void exactJavaScriptGoldenCorpus() {
    List<String> corpus =
        new ArrayList<>(
            List.of(
                "",
                "01234567",
                "ABCD12345678901234567890abcd",
                "漢字ABC12345😀",
                "A1",
                "123a456",
                "0000000000a0000000000",
                "A".repeat(30) + "1".repeat(30),
                "漢字".repeat(30) + "a" + "漢字".repeat(30)));
    String[] alphabet = {
      "0", "1", "2", "9", "A", "B", "Z", " ", "$", "%", "*", "+", "-", ".", "/", ":", "a", "z", "é",
      "漢", "字", "茗", "～", "①", "Ⅰ", "😀", "𐐀", "\0", "\n"
    };
    XorShift random = new XorShift(0x05eeda11);
    for (int i = 0; i < 500; i++) {
      int length = random.next(80);
      StringBuilder text = new StringBuilder();
      for (int count = 0; count < length; ) {
        String character = alphabet[random.next(alphabet.length)];
        int run = Math.min(random.next(12) + 1, length - count);
        text.append(character.repeat(run));
        count += run;
      }
      corpus.add(text.toString());
    }
    MessageDigest descriptors = sha256();
    MessageDigest encoded = sha256();
    int encodes = 0;
    for (String text : corpus) {
      for (int version : new int[] {1, 10, 27}) {
        for (boolean allowKanji : new boolean[] {true, false}) {
          List<Segment> segments = Segments.create(text, version, "auto", true, allowKanji);
          long length = Segments.bitLength(segments, version);
          StringBuilder description =
              new StringBuilder(version + "/" + allowKanji + "/" + length + "/");
          for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (i > 0) description.append('|');
            description
                .append(segment.mode())
                .append(':')
                .append(
                    Base64.getEncoder()
                        .encodeToString(segment.text().getBytes(StandardCharsets.UTF_8)));
          }
          update(descriptors, description.append('\n').toString());
          if (length <= Tables.dataCodewords(version, "L") * 8L) {
            update(encoded, bitString(Segments.bits(segments, version)) + "\n");
            encodes++;
          }
          if (!text.isEmpty()) {
            Segments.OptimizationTracker tracker =
                new Segments.OptimizationTracker(version, allowKanji);
            long tracked = 0;
            for (int cp : text.codePoints().toArray()) tracked = tracker.append(cp);
            equal(length, tracked, "constant-memory tracker parity");
          }
        }
      }
    }
    // Golden digests generated by SpecQR JS commit 15ad15e5c770ea0e39072f8f88b2733018f02ffd.
    // 509 inputs, 3 version groups, Kanji enabled/disabled: 3054 exact segment comparisons.
    equal(
        "c1d012f6ffd7372930326e494edafb75ee3be1864f40ccf5c29b4bd9d5d74741",
        HexFormat.of().formatHex(descriptors.digest()),
        "exact JS optimizer modes/boundaries/costs");
    equal(2213, encodes, "JS corpus materialization coverage");
    equal(
        "12cf4b93c8fecbca4bb75545cd013de1d1dcd36bfb12aa1032d02203dada61f2",
        HexFormat.of().formatHex(encoded.digest()),
        "exact JS segment header/payload bits");
  }

  private static void independentMinimumCosts() {
    String[] alphabet = {"1", "A", "a", "漢", "😀"};
    for (int length = 1, combinations = 5; length <= 5; length++, combinations *= 5) {
      for (int input = 0; input < combinations; input++) {
        StringBuilder text = new StringBuilder();
        int value = input;
        for (int i = 0; i < length; i++, value /= alphabet.length)
          text.append(alphabet[value % alphabet.length]);
        for (int version : new int[] {1, 10, 27}) {
          String source = text.toString();
          equal(
              bruteMinimum(source, version),
              Segments.bitLength(Segments.create(source, version, "auto", true, true), version),
              "independent exhaustive minimum cost");
        }
      }
    }
  }

  private static long bruteMinimum(String text, int version) {
    int[] cps = text.codePoints().toArray();
    long[] cost = new long[cps.length + 1];
    Arrays.fill(cost, Long.MAX_VALUE / 4);
    cost[0] = 0;
    for (int start = 0; start < cps.length; start++) {
      for (int end = start + 1; end <= cps.length; end++) {
        String piece = new String(cps, start, end - start);
        for (String mode : new String[] {"numeric", "alphanumeric", "kanji", "byte"}) {
          try {
            cost[end] =
                Math.min(cost[end], cost[start] + Segment.fromText(mode, piece).totalBits(version));
          } catch (SpecQrException unsupported) {
            if (!unsupported.code().equals("INVALID_MODE")) throw unsupported;
          }
        }
      }
    }
    return cost[cps.length];
  }

  private static final class XorShift {
    private int state;

    XorShift(int seed) {
      state = seed;
    }

    int next(int bound) {
      state ^= state << 13;
      state ^= state >>> 17;
      state ^= state << 5;
      return Integer.remainderUnsigned(state, bound);
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void update(MessageDigest digest, String text) {
    digest.update(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String bitString(int[] bits) {
    StringBuilder result = new StringBuilder(bits.length);
    for (int bit : bits) {
      if (bit != 0 && bit != 1) throw new AssertionError("Non-binary bit value");
      result.append(bit);
    }
    return result.toString();
  }

  private static void equal(Object expected, Object actual, String label) {
    assertions++;
    if (!java.util.Objects.equals(expected, actual))
      throw new AssertionError(label + ": expected " + expected + ", got " + actual);
  }

  private static void fails(String code, Runnable action) {
    try {
      action.run();
    } catch (SpecQrException error) {
      equal(code, error.code(), "stable error code");
      return;
    }
    throw new AssertionError("Expected " + code);
  }
}
