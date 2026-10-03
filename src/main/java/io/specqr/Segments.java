package io.specqr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic segment construction, normalization, and bit materialization. */
public final class Segments {
  /** Maximum text scalars or binary bytes in a payload or manual sequence. */
  public static final int MAX_PAYLOAD_UNITS = 1_000_000;

  /** Maximum number of manually supplied segments. */
  public static final int MAX_MANUAL_SEGMENTS = 16_384;

  /** Maximum number of Unicode scalars admitted by the single-symbol optimizer. */
  public static final int MAX_SINGLE_SYMBOL_CHARACTERS = 7_089;

  /** Maximum data bits in one QR symbol (version 40, level L). */
  public static final int MAX_SINGLE_SYMBOL_DATA_BITS = 23_648;

  private static final String[] DATA_MODES = {"numeric", "alphanumeric", "kanji", "byte"};

  private Segments() {}

  /**
   * Constructs immutable data segments. Auto mode selects exact minimum-bit segmentation, breaking
   * ties by fewer segments and then SpecQR's stable numeric/alphanumeric/Kanji/byte traversal
   * order.
   *
   * <p>When {@code allowKanji} is false, automatic selection excludes Kanji; explicitly requested
   * Kanji remains supported. This is useful with ECI.
   */
  public static List<Segment> create(
      String text, int version, String mode, boolean optimize, boolean allowKanji) {
    validateVersion(version);
    validateMode(mode);
    int scalars = Segment.validateText(text);
    if (!mode.equals("auto")) return List.of(Segment.fromText(mode, text));
    if (optimize) {
      if (scalars > MAX_SINGLE_SYMBOL_CHARACTERS) {
        throw Segment.error(
            "DATA_TOO_LONG", "Text exceeds maximum single-symbol character capacity");
      }
      return optimize(text, scalars, version, allowKanji);
    }

    boolean numeric = !text.isEmpty();
    boolean alphanumeric = !text.isEmpty();
    boolean kanji = !text.isEmpty() && allowKanji;
    for (int offset = 0; offset < text.length(); ) {
      int cp = text.codePointAt(offset);
      numeric &= Segment.isDigit(cp);
      alphanumeric &= Segment.alphanumericValue(cp) >= 0;
      if (kanji) kanji = Kanji.canEncode(cp);
      offset += Character.charCount(cp);
    }
    String selected =
        numeric ? "numeric" : alphanumeric ? "alphanumeric" : kanji ? "kanji" : "byte";
    return List.of(Segment.fromText(selected, text));
  }

  /** Constructs one immutable binary byte segment; mode must be auto or byte. */
  public static List<Segment> create(
      byte[] data, int version, String mode, boolean optimize, boolean allowKanji) {
    validateVersion(version);
    validateMode(mode);
    if (!mode.equals("auto") && !mode.equals("byte")) {
      throw Segment.error("INVALID_MODE", "Binary input can only be encoded in byte mode");
    }
    return List.of(Segment.bytes(data));
  }

  /**
   * Owns an immutable snapshot and validates resource bounds and manual control placement. Manual
   * boundaries and repeated ECI transitions are preserved. FNC1, FNC1 second, and Structured Append
   * must be first, unique, and cannot be combined with each other or ECI in the SpecQR contract.
   */
  public static List<Segment> normalize(List<Segment> segments) {
    if (segments == null) throw Segment.error("INVALID_INPUT", "Manual segments must be a list");
    if (segments.size() > MAX_MANUAL_SEGMENTS) {
      throw Segment.error(
          "DATA_TOO_LONG", "Manual segments exceed the 16384-segment resource limit");
    }
    List<Segment> copy = new ArrayList<>(segments.size());
    long units = 0;
    int fnc1 = 0;
    int fnc1Second = 0;
    int structuredAppend = 0;
    int eci = 0;
    for (Segment segment : segments) {
      if (segment == null)
        throw Segment.error("INVALID_INPUT", "Manual segments must not contain null");
      if (copy.size() >= MAX_MANUAL_SEGMENTS) {
        throw Segment.error(
            "DATA_TOO_LONG", "Manual segments exceed the 16384-segment resource limit");
      }
      units += segment.payloadUnits();
      if (units > MAX_PAYLOAD_UNITS) {
        throw Segment.error(
            "DATA_TOO_LONG", "Manual payload exceeds the 1000000-unit resource limit");
      }
      switch (segment.mode()) {
        case "fnc1" -> {
          if (++fnc1 > 1)
            throw Segment.error(
                "INVALID_GS1", "Manual segments can include at most one FNC1 segment");
          if (!copy.isEmpty())
            throw Segment.error("INVALID_GS1", "Manual FNC1 segment must be first");
        }
        case "fnc1-second" -> {
          if (++fnc1Second > 1)
            throw Segment.error(
                "INVALID_MODE", "Manual segments can include at most one FNC1 second segment");
          if (!copy.isEmpty())
            throw Segment.error("INVALID_MODE", "Manual FNC1 second segment must be first");
        }
        case "structured-append" -> {
          if (++structuredAppend > 1)
            throw Segment.error(
                "INVALID_MODE",
                "Manual segments can include at most one Structured Append segment");
          if (!copy.isEmpty())
            throw Segment.error("INVALID_MODE", "Manual Structured Append segment must be first");
        }
        case "eci" -> eci++;
        default -> {}
      }
      copy.add(segment);
    }
    int controlsPresent =
        (fnc1 > 0 ? 1 : 0)
            + (fnc1Second > 0 ? 1 : 0)
            + (structuredAppend > 0 ? 1 : 0)
            + (eci > 0 ? 1 : 0);
    if (controlsPresent > 1) {
      throw Segment.error(
          fnc1 > 0 ? "INVALID_GS1" : "INVALID_MODE",
          "FNC1, FNC1 second, Structured Append, and ECI cannot be combined in this"
              + " implementation");
    }
    return List.copyOf(copy);
  }

  /** Returns the arithmetic unpadded length, even for payloads too large to encode. */
  public static long bitLength(List<Segment> segments, int version) {
    validateVersion(version);
    long result = 0;
    for (Segment segment : normalize(segments)) result += segment.totalBits(version);
    return result;
  }

  /** Returns freshly allocated unpadded bits, after all allocation and count checks. */
  public static int[] bits(List<Segment> segments, int version) {
    validateVersion(version);
    List<Segment> normalized = normalize(segments);
    long length = 0;
    for (Segment segment : normalized) {
      long segmentLength = segment.totalBits(version);
      segment.validateMaterialization(version, segmentLength);
      length += segmentLength;
    }
    if (length > MAX_SINGLE_SYMBOL_DATA_BITS) {
      throw Segment.error(
          "DATA_TOO_LONG", "Segments exceed the maximum single-symbol bit capacity");
    }
    int[] result = new int[(int) length];
    int offset = 0;
    for (Segment segment : normalized) offset = segment.appendBits(result, offset, version);
    if (offset != result.length)
      throw new AssertionError("Segment bit length disagrees with encoding");
    return result;
  }

  /**
   * Constant-memory exact bit-cost tracker for prefixes, including inputs that span multiple
   * symbols. Each append accepts one valid Unicode scalar.
   */
  public static final class OptimizationTracker {
    private final int version;
    private final boolean allowKanji;
    private LinkedHashMap<Integer, State> states = initialStates();
    private int characters;

    public OptimizationTracker(int version, boolean allowKanji) {
      validateVersion(version);
      this.version = version;
      this.allowKanji = allowKanji;
    }

    /** Adds one Unicode code point and returns the exact minimum prefix bit cost. */
    public long append(int codePoint) {
      if (!Character.isValidCodePoint(codePoint) || codePoint >= 0xD800 && codePoint <= 0xDFFF) {
        throw Segment.error("INVALID_INPUT", "Append requires exactly one Unicode scalar");
      }
      if (characters >= MAX_PAYLOAD_UNITS) {
        throw Segment.error("DATA_TOO_LONG", "Payload exceeds the 1000000-unit resource limit");
      }
      states = advance(states, codePoint, version, allowKanji);
      characters++;
      return best(states).getValue().cost;
    }

    /** Adds a string containing exactly one Unicode scalar. */
    public long append(String character) {
      if (Segment.validateText(character) != 1) {
        throw Segment.error("INVALID_INPUT", "Append requires exactly one Unicode scalar");
      }
      return append(character.codePointAt(0));
    }
  }

  private record State(int cost, int segmentCount, int mode, int mod, int previous) {}

  private static LinkedHashMap<Integer, State> initialStates() {
    LinkedHashMap<Integer, State> states = new LinkedHashMap<>();
    states.put(-1, new State(0, 0, -1, 0, -1));
    return states;
  }

  private static List<Segment> optimize(String text, int scalars, int version, boolean allowKanji) {
    if (scalars == 0) return List.of(Segment.bytes(""));
    int[] characters = text.codePoints().toArray();
    List<LinkedHashMap<Integer, State>> layers = new ArrayList<>(scalars + 1);
    layers.add(initialStates());
    for (int cp : characters)
      layers.add(advance(layers.get(layers.size() - 1), cp, version, allowKanji));
    int key = best(layers.get(scalars)).getKey();
    int[] assignments = new int[scalars];
    for (int i = scalars; i > 0; i--) {
      State state = layers.get(i).get(key);
      assignments[i - 1] = state.mode;
      key = state.previous;
    }
    List<Segment> result = new ArrayList<>();
    int startCharacter = 0;
    int startOffset = 0;
    int offset = Character.charCount(characters[0]);
    for (int i = 1; i <= scalars; i++) {
      if (i == scalars || assignments[i] != assignments[startCharacter]) {
        result.add(
            Segment.fromText(
                DATA_MODES[assignments[startCharacter]], text.substring(startOffset, offset)));
        startCharacter = i;
        startOffset = offset;
      }
      if (i < scalars) offset += Character.charCount(characters[i]);
    }
    return List.copyOf(result);
  }

  private static LinkedHashMap<Integer, State> advance(
      LinkedHashMap<Integer, State> states, int cp, int version, boolean allowKanji) {
    boolean[] eligible = {
      Segment.isDigit(cp),
      Segment.alphanumericValue(cp) >= 0,
      allowKanji && Kanji.canEncode(cp),
      true
    };
    int byteBits = (cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4) * 8;
    LinkedHashMap<Integer, State> next = new LinkedHashMap<>();
    for (Map.Entry<Integer, State> entry : states.entrySet()) {
      State state = entry.getValue();
      for (int mode = 0; mode < DATA_MODES.length; mode++) {
        if (!eligible[mode]) continue;
        boolean same = state.mode == mode;
        int mod = same ? state.mod : 0;
        int payload =
            switch (mode) {
              case 0 -> mod == 0 ? 4 : 3;
              case 1 -> mod == 0 ? 6 : 5;
              case 2 -> 13;
              default -> byteBits;
            };
        int nextMod = mode == 0 ? (mod + 1) % 3 : mode == 1 ? (mod + 1) % 2 : 0;
        int nextKey = mode * 3 + nextMod;
        State candidate =
            new State(
                state.cost + payload + (same ? 0 : 4 + Tables.countBits(DATA_MODES[mode], version)),
                state.segmentCount + (same ? 0 : 1),
                mode,
                nextMod,
                entry.getKey());
        State current = next.get(nextKey);
        // LinkedHashMap replacement retains first insertion order, just like JS Map.set.
        if (current == null || better(candidate, current)) next.put(nextKey, candidate);
      }
    }
    return next;
  }

  private static Map.Entry<Integer, State> best(LinkedHashMap<Integer, State> states) {
    Map.Entry<Integer, State> result = null;
    for (Map.Entry<Integer, State> entry : states.entrySet()) {
      if (result == null || better(entry.getValue(), result.getValue())) result = entry;
    }
    if (result == null) throw new AssertionError("No byte-mode optimization state");
    return result;
  }

  private static boolean better(State candidate, State current) {
    return candidate.cost < current.cost
        || candidate.cost == current.cost && candidate.segmentCount < current.segmentCount;
  }

  static void validateVersion(int version) {
    if (version < 1 || version > 40)
      throw Segment.error("INVALID_VERSION", "Version must be from 1 to 40");
  }

  private static void validateMode(String mode) {
    if (mode == null
        || !(mode.equals("auto")
            || mode.equals("numeric")
            || mode.equals("alphanumeric")
            || mode.equals("kanji")
            || mode.equals("byte"))) {
      throw Segment.error(
          "INVALID_MODE", "Mode must be auto, numeric, alphanumeric, byte, or kanji");
    }
  }
}
