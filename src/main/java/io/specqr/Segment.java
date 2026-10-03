package io.specqr;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable, validated QR data or control segment.
 *
 * <p>Text byte segments use strict UTF-8; an ECI segment labels subsequent data but does not
 * transcode it. Supply binary bytes when using another encoding. Text must contain correctly paired
 * UTF-16 surrogates. Textual logical bytes always use UTF-8, including Kanji, for Structured Append
 * parity.
 *
 * <p>Manual FNC1 alphanumeric payloads are preserved as supplied: {@code %} denotes a separator and
 * {@code %%} a literal percent to FNC1 readers.
 */
public final class Segment {
  static final String ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

  private final String mode;
  private final String text;
  private final byte[] logical;
  private final boolean binary;
  private final int characters;
  private final Integer assignment;
  private final String applicationIndicator;
  private final Integer index;
  private final Integer total;
  private final Integer parity;

  private Segment(
      String mode,
      String text,
      byte[] logical,
      boolean binary,
      int characters,
      Integer assignment,
      String applicationIndicator,
      Integer index,
      Integer total,
      Integer parity) {
    this.mode = mode;
    this.text = text;
    this.logical = logical;
    this.binary = binary;
    this.characters = characters;
    this.assignment = assignment;
    this.applicationIndicator = applicationIndicator;
    this.index = index;
    this.total = total;
    this.parity = parity;
  }

  /** Creates a numeric segment containing only ASCII decimal digits. */
  public static Segment numeric(String text) {
    return fromText("numeric", text);
  }

  /** Creates a QR alphanumeric segment, without changing case or escaping. */
  public static Segment alphanumeric(String text) {
    return fromText("alphanumeric", text);
  }

  /** Creates a byte segment containing the strict UTF-8 encoding of text. */
  public static Segment bytes(String text) {
    return fromText("byte", text);
  }

  /** Creates a byte segment with an owned copy of the supplied bytes. */
  public static Segment bytes(byte[] bytes) {
    if (bytes == null) throw error("INVALID_INPUT", "Byte segment requires binary data");
    if (bytes.length > Segments.MAX_PAYLOAD_UNITS) {
      throw error("DATA_TOO_LONG", "Payload exceeds the 1000000-unit resource limit");
    }
    return new Segment("byte", null, bytes.clone(), true, 0, null, null, null, null, null);
  }

  /** Creates a Kanji segment using the pinned WHATWG Shift_JIS first mapping. */
  public static Segment kanji(String text) {
    return fromText("kanji", text);
  }

  /** Creates an ECI designator, with an assignment number from 0 to 999999. */
  public static Segment eci(int assignment) {
    if (assignment < 0 || assignment > 999_999) {
      throw error("INVALID_ECI", "ECI assignment number must be from 0 to 999999");
    }
    return control("eci", assignment, null, null, null, null);
  }

  /** Creates an FNC1 first-position control segment. */
  public static Segment fnc1() {
    return control("fnc1", null, null, null, null, null);
  }

  /** Creates FNC1 second position; the indicator is two ASCII digits or one Latin letter. */
  public static Segment fnc1Second(String applicationIndicator) {
    if (applicationIndicator == null
        || !(applicationIndicator.length() == 2
                && isDigit(applicationIndicator.charAt(0))
                && isDigit(applicationIndicator.charAt(1))
            || applicationIndicator.length() == 1
                && isLatinLetter(applicationIndicator.charAt(0)))) {
      throw error(
          "INVALID_MODE",
          "FNC1 second application indicator must be two ASCII digits or one Latin letter");
    }
    return control("fnc1-second", null, applicationIndicator, null, null, null);
  }

  /** Creates Structured Append with one-based index, total 2..16, and parity 0..255. */
  public static Segment structuredAppend(int index, int total, int parity) {
    if (index < 1
        || index > 16
        || total < 2
        || total > 16
        || index > total
        || parity < 0
        || parity > 255) {
      throw error(
          "INVALID_MODE",
          "Structured Append requires 1 <= index <= total, 2 <= total <= 16, and parity 0..255");
    }
    return control("structured-append", null, null, index, total, parity);
  }

  static Segment fromText(String mode, String text) {
    int characters = validateText(text);
    for (int offset = 0; offset < text.length(); ) {
      int cp = text.codePointAt(offset);
      if (mode.equals("numeric") && !isDigit(cp)) {
        throw error("INVALID_MODE", "Numeric mode can only encode decimal digits 0-9");
      }
      if (mode.equals("alphanumeric") && alphanumericValue(cp) < 0) {
        throw error("INVALID_MODE", "Alphanumeric mode can only encode: " + ALPHANUMERIC);
      }
      if (mode.equals("kanji") && !Kanji.canEncode(cp)) {
        throw error(
            "INVALID_MODE",
            "Kanji mode cannot encode U+"
                + Integer.toHexString(cp).toUpperCase(java.util.Locale.ROOT));
      }
      offset += Character.charCount(cp);
    }
    return new Segment(
        mode,
        text,
        text.getBytes(StandardCharsets.UTF_8),
        false,
        characters,
        null,
        null,
        null,
        null,
        null);
  }

  private static Segment control(
      String mode,
      Integer assignment,
      String applicationIndicator,
      Integer index,
      Integer total,
      Integer parity) {
    return new Segment(
        mode, null, new byte[0], false, 0, assignment, applicationIndicator, index, total, parity);
  }

  /** The lower-case QR mode identifier, such as {@code byte} or {@code fnc1-second}. */
  public String mode() {
    return mode;
  }

  /** Source text, or null for binary data and controls. */
  public String text() {
    return text;
  }

  /** A defensive copy of the binary payload, or null for textual data and controls. */
  public byte[] binary() {
    return binary ? logical.clone() : null;
  }

  /** Canonical UTF-8 text bytes or binary payload bytes; empty for controls. */
  public byte[] logicalBytes() {
    return logical.clone();
  }

  /** ECI assignment number, or null for other modes. */
  public Integer assignment() {
    return assignment;
  }

  /** FNC1 second-position application indicator, or null for other modes. */
  public String applicationIndicator() {
    return applicationIndicator;
  }

  /** One-based Structured Append sequence index, or null for other modes. */
  public Integer index() {
    return index;
  }

  /** Structured Append sequence total, or null for other modes. */
  public Integer total() {
    return total;
  }

  /** Structured Append parity byte as an unsigned integer, or null for other modes. */
  public Integer parity() {
    return parity;
  }

  /** Whether the segment is a control designator rather than payload data. */
  public boolean isControl() {
    return mode.equals("eci")
        || mode.equals("fnc1")
        || mode.equals("fnc1-second")
        || mode.equals("structured-append");
  }

  /** Count written in the QR header: encoded bytes for byte mode, scalars otherwise. */
  public int count() {
    return mode.equals("byte") ? logical.length : characters;
  }

  /** Source Unicode scalar count; zero for binary data and controls. */
  public int characterCount() {
    return characters;
  }

  /** Diagnostic byte count: two bytes per Kanji scalar, canonical bytes otherwise. */
  public int byteCount() {
    return mode.equals("kanji") ? characters * 2 : logical.length;
  }

  /** Encoded FNC1 second-position indicator byte, or null for other modes. */
  public Integer applicationIndicatorCodeword() {
    if (applicationIndicator == null) return null;
    return applicationIndicator.length() == 2
        ? (applicationIndicator.charAt(0) - '0') * 10 + applicationIndicator.charAt(1) - '0'
        : applicationIndicator.charAt(0) + 100;
  }

  /** Payload/designator bit length, excluding mode and character-count headers. */
  public int dataBitLength() {
    int count = count();
    return switch (mode) {
      case "numeric" -> count / 3 * 10 + (count % 3 == 0 ? 0 : count % 3 == 1 ? 4 : 7);
      case "alphanumeric" -> count / 2 * 11 + count % 2 * 6;
      case "byte" -> count * 8;
      case "kanji" -> count * 13;
      case "eci" -> assignment < 128 ? 8 : assignment < 16384 ? 16 : 24;
      case "fnc1" -> 0;
      case "fnc1-second" -> 8;
      case "structured-append" -> 16;
      default -> throw new AssertionError("Unrecognized validated segment mode");
    };
  }

  /**
   * Arithmetic unpadded bit length. Oversized payloads remain inspectable; {@link #bits(int)}
   * checks count-field and single-symbol capacity limits.
   */
  public long totalBits(int version) {
    Segments.validateVersion(version);
    return 4L + (isControl() ? 0 : Tables.countBits(mode, version)) + dataBitLength();
  }

  /** Returns fresh header and payload bits, without terminator or pad codewords. */
  public int[] bits(int version) {
    long length = totalBits(version);
    validateMaterialization(version, length);
    int[] result = new int[(int) length];
    appendBits(result, 0, version);
    return result;
  }

  void validateMaterialization(int version, long length) {
    if (!isControl() && count() >= (1 << Tables.countBits(mode, version))) {
      throw error("DATA_TOO_LONG", "Segment count exceeds the count field for version " + version);
    }
    if (length > Segments.MAX_SINGLE_SYMBOL_DATA_BITS) {
      throw error("DATA_TOO_LONG", "Segment exceeds the maximum single-symbol bit capacity");
    }
  }

  int appendBits(int[] bits, int offset, int version) {
    int indicator =
        switch (mode) {
          case "numeric" -> 1;
          case "alphanumeric" -> 2;
          case "structured-append" -> 3;
          case "byte" -> 4;
          case "fnc1" -> 5;
          case "eci" -> 7;
          case "kanji" -> 8;
          case "fnc1-second" -> 9;
          default -> throw new AssertionError("Unrecognized validated segment mode");
        };
    offset = append(bits, offset, indicator, 4);
    if (!isControl()) offset = append(bits, offset, count(), Tables.countBits(mode, version));
    switch (mode) {
      case "eci" -> {
        if (assignment < 128) offset = append(bits, offset, assignment, 8);
        else if (assignment < 16384) offset = append(bits, offset, 0x8000 | assignment, 16);
        else offset = append(bits, offset, 0xC00000 | assignment, 24);
      }
      case "fnc1-second" -> offset = append(bits, offset, applicationIndicatorCodeword(), 8);
      case "structured-append" -> {
        offset = append(bits, offset, index - 1, 4);
        offset = append(bits, offset, total - 1, 4);
        offset = append(bits, offset, parity, 8);
      }
      case "numeric" -> {
        for (int i = 0; i < text.length(); i += 3) {
          int end = Math.min(i + 3, text.length());
          int value = 0;
          for (int j = i; j < end; j++) value = value * 10 + text.charAt(j) - '0';
          offset = append(bits, offset, value, end - i == 3 ? 10 : end - i == 2 ? 7 : 4);
        }
      }
      case "alphanumeric" -> {
        int i = 0;
        for (; i + 1 < text.length(); i += 2) {
          offset =
              append(
                  bits,
                  offset,
                  alphanumericValue(text.charAt(i)) * 45 + alphanumericValue(text.charAt(i + 1)),
                  11);
        }
        if (i < text.length()) offset = append(bits, offset, alphanumericValue(text.charAt(i)), 6);
      }
      case "byte" -> {
        for (byte value : logical) offset = append(bits, offset, value & 0xFF, 8);
      }
      case "kanji" -> {
        for (int i = 0; i < text.length(); i++)
          offset = append(bits, offset, Kanji.value(text.charAt(i)), 13);
      }
      case "fnc1" -> {}
      default -> throw new AssertionError("Unrecognized validated segment mode");
    }
    return offset;
  }

  static int append(int[] bits, int offset, int value, int width) {
    for (int shift = width - 1; shift >= 0; shift--) bits[offset++] = (value >>> shift) & 1;
    return offset;
  }

  int payloadUnits() {
    return binary ? logical.length : characters;
  }

  static int validateText(String text) {
    if (text == null) throw error("INVALID_INPUT", "QR text must be a string");
    if (text.length() > 2L * Segments.MAX_PAYLOAD_UNITS) {
      throw error("DATA_TOO_LONG", "Payload exceeds the 1000000-unit resource limit");
    }
    int count = 0;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (Character.isHighSurrogate(ch)) {
        if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(++i))) {
          throw error("INVALID_INPUT", "QR text must contain correctly paired UTF-16 surrogates");
        }
      } else if (Character.isLowSurrogate(ch)) {
        throw error("INVALID_INPUT", "QR text must contain correctly paired UTF-16 surrogates");
      }
      if (++count > Segments.MAX_PAYLOAD_UNITS) {
        throw error("DATA_TOO_LONG", "Payload exceeds the 1000000-unit resource limit");
      }
    }
    return count;
  }

  static int alphanumericValue(int cp) {
    return cp < 128 && cp >= 0 ? ALPHANUMERIC.indexOf(cp) : -1;
  }

  static boolean isDigit(int cp) {
    return cp >= '0' && cp <= '9';
  }

  private static boolean isLatinLetter(int cp) {
    return cp >= 'A' && cp <= 'Z' || cp >= 'a' && cp <= 'z';
  }

  static SpecQrException error(String code, String message) {
    return new SpecQrException(code, message);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof Segment that)) return false;
    return mode.equals(that.mode)
        && Objects.equals(text, that.text)
        && binary == that.binary
        && Arrays.equals(logical, that.logical)
        && Objects.equals(assignment, that.assignment)
        && Objects.equals(applicationIndicator, that.applicationIndicator)
        && Objects.equals(index, that.index)
        && Objects.equals(total, that.total)
        && Objects.equals(parity, that.parity);
  }

  @Override
  public int hashCode() {
    return 31
            * Objects.hash(
                mode, text, binary, assignment, applicationIndicator, index, total, parity)
        + Arrays.hashCode(logical);
  }

  @Override
  public String toString() {
    return "Segment[mode=" + mode + ", count=" + count() + "]";
  }
}
