package io.specqr;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic Structured Append splitting and reassembly for 2..16 symbols. Headers and public
 * indexes are one-based. Parity is the XOR of original text UTF-8 or raw bytes, including for
 * manual Kanji; it is not authentication. Text is split only between Unicode scalars. Manual
 * numeric, alphanumeric and Kanji segments are indivisible; byte segments may be split at safe
 * boundaries.
 */
public final class StructuredAppend {
  private static final Set<String> DATA_MODES = Set.of("numeric", "alphanumeric", "byte", "kanji");

  private StructuredAppend() {}

  public record Result(
      List<QrCode> symbols,
      int total,
      int parity,
      int inputLength,
      int byteLength,
      Map<String, Object> diagnostics) {
    public Result {
      if (symbols == null || symbols.size() < 2 || symbols.size() > 16 || total != symbols.size())
        throw invalid("Structured Append result requires 2..16 matching symbols");
      if (parity < 0
          || parity > 255
          || inputLength < 1
          || inputLength > Segments.MAX_PAYLOAD_UNITS
          || byteLength < 1
          || byteLength > 4L * Segments.MAX_PAYLOAD_UNITS)
        throw invalid("Invalid Structured Append result metadata");
      symbols = List.copyOf(symbols);
      diagnostics = SpecQr.freeze(diagnostics);
    }
  }

  /** Summary is bounded by segments and symbols; full explicitly expands split units. */
  public record DiagnosticOptions(String splitUnits, String symbolResults) {
    public static DiagnosticOptions defaults() {
      return new DiagnosticOptions("summary", "output");
    }

    public static DiagnosticOptions full() {
      return new DiagnosticOptions("full", "diagnostics");
    }
  }

  /** One decoded part. Binary data is defensively copied on input and access. */
  public record Part(int index, int total, int parity, Object data) {
    public Part {
      validateMetadata(index, total, parity);
      if (data instanceof String text) Segment.validateText(text);
      if (!(data instanceof String) && !(data instanceof byte[]))
        throw invalid("Part data must be text or bytes");
      if (data instanceof byte[] bytes) {
        if (bytes.length > Segments.MAX_PAYLOAD_UNITS)
          throw tooLong("Part exceeds payload resource limit");
        data = bytes.clone();
      }
    }

    @Override
    public Object data() {
      return data instanceof byte[] bytes ? bytes.clone() : data;
    }
  }

  public record PartInfo(int index, int total, int parity, String dataType, int byteLength) {}

  public record MergeResult(
      Object data, int total, int parity, List<PartInfo> parts, Map<String, Object> diagnostics) {
    public MergeResult {
      if (total < 2 || total > 16 || parity < 0 || parity > 255)
        throw invalid("Invalid Structured Append merge metadata");
      if (parts == null || parts.size() != total)
        throw invalid("Merge metadata parts must match total");
      if (data instanceof String text) Segment.validateText(text);
      if (data instanceof byte[] bytes && bytes.length > Segments.MAX_PAYLOAD_UNITS)
        throw tooLong("Merged payload exceeds the 1000000-unit resource limit");
      if (!(data instanceof String) && !(data instanceof byte[]))
        throw invalid("Merged data must be text or bytes");
      if (data instanceof byte[] bytes) data = bytes.clone();
      parts = List.copyOf(parts);
      diagnostics = SpecQr.freeze(diagnostics);
    }

    @Override
    public Object data() {
      return data instanceof byte[] bytes ? bytes.clone() : data;
    }

    public String text() {
      return data instanceof String text ? text : null;
    }

    public byte[] bytes() {
      return data instanceof byte[] bytes ? bytes.clone() : null;
    }
  }

  private static SpecQrException invalid(String message) {
    return new SpecQrException("INVALID_INPUT", message);
  }

  private static SpecQrException mode(String message) {
    return new SpecQrException("INVALID_MODE", message);
  }

  private static SpecQrException tooLong(String message) {
    return new SpecQrException("DATA_TOO_LONG", message);
  }

  private static void validateMetadata(int index, int total, int parity) {
    if (index < 1 || index > 16) throw invalid("index must be an integer from 1 to 16");
    if (total < 2 || total > 16) throw invalid("total must be an integer from 2 to 16");
    if (parity < 0 || parity > 255) throw invalid("parity must be an integer from 0 to 255");
    if (index > total) throw invalid("Structured Append index must not exceed total");
  }

  private static int xor(byte[] data) {
    int value = 0;
    for (byte b : data) value ^= b & 255;
    return value;
  }

  /** UTF-8 indexes use two integers per 64 scalars rather than one object per scalar. */
  private static final class TextIndex {
    final String text;
    final int length, byteLength, parity;
    final int[] charOffsets, byteOffsets;

    TextIndex(String text) {
      length = Segment.validateText(text);
      this.text = text;
      charOffsets = new int[length / 64 + 1];
      byteOffsets = new int[charOffsets.length];
      int bytes = 0, checksum = 0, scalar = 0;
      for (int offset = 0; offset < text.length(); ) {
        int cp = text.codePointAt(offset);
        offset += Character.charCount(cp);
        scalar++;
        if (cp < 0x80) {
          bytes++;
          checksum ^= cp;
        } else if (cp < 0x800) {
          bytes += 2;
          checksum ^= 0xC0 | (cp >>> 6);
          checksum ^= 0x80 | (cp & 63);
        } else if (cp < 0x10000) {
          bytes += 3;
          checksum ^= 0xE0 | (cp >>> 12);
          checksum ^= 0x80 | ((cp >>> 6) & 63);
          checksum ^= 0x80 | (cp & 63);
        } else {
          bytes += 4;
          checksum ^= 0xF0 | (cp >>> 18);
          checksum ^= 0x80 | ((cp >>> 12) & 63);
          checksum ^= 0x80 | ((cp >>> 6) & 63);
          checksum ^= 0x80 | (cp & 63);
        }
        if (scalar % 64 == 0) {
          charOffsets[scalar / 64] = offset;
          byteOffsets[scalar / 64] = bytes;
        }
      }
      byteLength = bytes;
      parity = checksum;
    }

    int charOffset(int scalar) {
      int checkpoint = scalar / 64, offset = charOffsets[checkpoint];
      for (int i = checkpoint * 64; i < scalar; i++)
        offset += Character.charCount(text.codePointAt(offset));
      return offset;
    }

    int byteOffset(int scalar) {
      int checkpoint = scalar / 64,
          offset = charOffsets[checkpoint],
          bytes = byteOffsets[checkpoint];
      for (int i = checkpoint * 64; i < scalar; i++) {
        int cp = text.codePointAt(offset);
        offset += Character.charCount(cp);
        bytes += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
      }
      return bytes;
    }

    int byteLength(int start, int length) {
      return byteOffset(start + length) - byteOffset(start);
    }

    String slice(int start, int length) {
      return text.substring(charOffset(start), charOffset(start + length));
    }
  }

  public static int calculateParity(String input) {
    return new TextIndex(input).parity;
  }

  public static int calculateParity(byte[] input) {
    if (input == null) throw invalid("Input must be text or bytes");
    if (input.length > Segments.MAX_PAYLOAD_UNITS)
      throw tooLong("Payload exceeds the 1000000-unit resource limit");
    return xor(input);
  }

  private static List<Segment> manual(List<Segment> segments, int unitLimit) {
    if (segments == null || segments.isEmpty())
      throw invalid("Structured Append requires at least one non-empty data segment");
    if (segments.size() > Segments.MAX_MANUAL_SEGMENTS)
      throw tooLong("Manual segments exceed the 16384-segment resource limit");
    if (segments.size() > unitLimit)
      throw tooLong(
          "Input segments cannot be split within the selected Structured Append capacity");
    List<Segment> result = new ArrayList<>(segments.size());
    long units = 0;
    for (Segment segment : segments) {
      if (segment == null) throw invalid("Manual segments must not contain null");
      if (segment.mode().equals("fnc1"))
        throw new SpecQrException(
            "INVALID_GS1",
            "Structured Append cannot be combined with manual FNC1 first position segments");
      if (!DATA_MODES.contains(segment.mode()))
        throw mode(
            "Structured Append cannot be combined with manual "
                + segment.mode()
                + " control segments");
      int count = segment.text() == null ? segment.count() : segment.characterCount();
      if (count == 0) throw invalid("Structured Append segments must include non-empty data");
      units += count;
      if (units > unitLimit)
        throw tooLong(
            "Input segments cannot be split within the selected Structured Append capacity");
      result.add(segment);
    }
    return List.copyOf(result);
  }

  public static int calculateSegmentsParity(List<Segment> segments) {
    int result = 0;
    for (Segment segment : manual(segments, Segments.MAX_PAYLOAD_UNITS))
      result ^=
          segment.text() == null ? xor(segment.logicalBytes()) : calculateParity(segment.text());
    return result;
  }

  private static Options options(Options options, int maximum, boolean manual) {
    if (options == null) throw invalid("Options must not be null");
    if (maximum < 2 || maximum > 16) throw mode("maxSymbols must be an integer from 2 to 16");
    if (options.structuredAppend() != null)
      throw mode("Structured Append generation owns its header");
    if (options.eci() != null || options.fnc1Second() != null)
      throw mode("Structured Append cannot be combined with ECI or FNC1 second position");
    if (options.gs1())
      throw new SpecQrException("INVALID_GS1", "Structured Append cannot be combined with gs1");
    if (options.boostErrorCorrection())
      throw mode("Structured Append does not support boostErrorCorrection");
    if (manual && (!options.mode().equals("auto") || !options.optimizeSegments()))
      throw mode("Manual Structured Append preserves caller segment modes");
    return options;
  }

  private static DiagnosticOptions diagnosticOptions(DiagnosticOptions options) {
    if (options == null) throw invalid("Diagnostic options must not be null");
    if (!"summary".equals(options.splitUnits()) && !"full".equals(options.splitUnits()))
      throw invalid("diagnostics.splitUnits must be summary or full");
    if (!"output".equals(options.symbolResults()) && !"diagnostics".equals(options.symbolResults()))
      throw invalid("diagnostics.symbolResults must be output or diagnostics");
    return options;
  }

  private static int capacity(Options options, int version) {
    return Tables.dataCodewords(version, options.errorCorrectionLevel()) * 8;
  }

  private static int maximumVersion(Options options) {
    return options.version() == null ? options.maxVersion() : options.version();
  }

  private static int unitBudget(Options options, int maximum) {
    return maximum * ((capacity(options, maximumVersion(options)) - 20) * 3 / 10);
  }

  private static long numericBits(int length) {
    return length / 3 * 10L + new int[] {0, 4, 7}[length % 3];
  }

  private static long payloadBits(String mode, int length, int byteLength) {
    return switch (mode) {
      case "numeric" -> numericBits(length);
      case "alphanumeric" -> length / 2 * 11L + length % 2 * 6;
      case "kanji" -> length * 13L;
      default -> byteLength * 8L;
    };
  }

  private static long segmentBits(String mode, int length, int byteLength, int version) {
    int width = Tables.countBits(mode, version), count = mode.equals("byte") ? byteLength : length;
    return count >= (1 << width)
        ? Long.MAX_VALUE / 1024
        : 4 + width + payloadBits(mode, length, byteLength);
  }

  private record Chunk(Object data, Map<String, Object> offsets) {}

  private abstract static class Source {
    int length, inputLength, byteLength, parity;

    abstract long bits(int start, int length, Options options, int version);

    abstract Chunk chunk(int start, int length);

    int largestPrefix(int start, int maximum, Options options, int version) {
      int low = 1, high = maximum, best = 0;
      while (low <= high) {
        int length = low + (high - low) / 2;
        if (bits(start, length, options, version) <= capacity(options, version)) {
          best = length;
          low = length + 1;
        } else high = length - 1;
      }
      return best;
    }
  }

  private static final class InputSource extends Source {
    final String text;
    final byte[] binary;
    final TextIndex index;

    InputSource(String value, byte[] data, Options options, int maximum) {
      int budget = unitBudget(options, maximum);
      if (value == null && data == null) throw invalid("Input must be text or bytes");
      if (value != null && value.length() > 2L * budget || data != null && data.length > budget)
        throw tooLong("Input cannot be split within the selected Structured Append capacity");
      if (value != null) {
        index = new TextIndex(value);
        text = value;
        binary = null;
        length = index.length;
        byteLength = index.byteLength;
        parity = index.parity;
        if (length > budget)
          throw tooLong("Input cannot be split within the selected Structured Append capacity");
        if (!options.mode().equals("auto"))
          Segments.create(value, maximumVersion(options), options.mode(), false, true);
      } else {
        if (!options.mode().equals("auto") && !options.mode().equals("byte"))
          throw mode("Binary input can only be encoded in byte mode");
        index = null;
        text = null;
        binary = data.clone();
        length = data.length;
        byteLength = data.length;
        parity = xor(data);
      }
      if (length == 0) throw invalid("Structured Append requires at least two non-empty symbols");
      inputLength = length;
      String mode = binary != null ? "byte" : options.mode();
      int version = maximumVersion(options);
      int width =
          mode.equals("auto")
              ? Math.min(
                  Math.min(
                      Tables.countBits("numeric", version),
                      Tables.countBits("alphanumeric", version)),
                  Math.min(Tables.countBits("byte", version), Tables.countBits("kanji", version)))
              : Tables.countBits(mode, version);
      long required =
          mode.equals("auto") ? numericBits(length) : payloadBits(mode, length, byteLength);
      if (required > maximum * Math.max(0, capacity(options, version) - 24L - width))
        throw tooLong("Input cannot be split within the selected Structured Append capacity");
    }

    @Override
    long bits(int start, int length, Options options, int version) {
      int capacity = capacity(options, version);
      if (numericBits(length) > capacity - 20) return Long.MAX_VALUE / 1024;
      if (binary != null) return 20 + segmentBits("byte", length, length, version);
      int bytes = index.byteLength(start, length);
      if (!options.mode().equals("auto"))
        return 20 + segmentBits(options.mode(), length, bytes, version);
      if (options.optimizeSegments()) {
        Segments.OptimizationTracker tracker = new Segments.OptimizationTracker(version, true);
        long bits = 0;
        int offset = index.charOffset(start);
        for (int i = 0; i < length; i++) {
          int cp = text.codePointAt(offset);
          offset += Character.charCount(cp);
          bits = tracker.append(cp);
          if (bits + 20 > capacity) break;
        }
        return bits + 20;
      }
      return 20
          + Segments.bitLength(
              Segments.create(index.slice(start, length), version, "auto", false, true), version);
    }

    @Override
    int largestPrefix(int start, int maximum, Options options, int version) {
      if (binary == null && options.mode().equals("auto") && options.optimizeSegments()) {
        Segments.OptimizationTracker tracker = new Segments.OptimizationTracker(version, true);
        int capacity = capacity(options, version) - 20, offset = index.charOffset(start);
        for (int i = 0; i < maximum; i++) {
          int cp = text.codePointAt(offset);
          offset += Character.charCount(cp);
          if (tracker.append(cp) > capacity) return i;
        }
        return maximum;
      }
      return super.largestPrefix(start, maximum, options, version);
    }

    @Override
    Chunk chunk(int start, int length) {
      Object payload =
          binary == null
              ? index.slice(start, length)
              : Arrays.copyOfRange(binary, start, start + length);
      return new Chunk(
          payload,
          SpecQr.map(
              "inputStart",
              start,
              "inputLength",
              length,
              "byteStart",
              binary == null ? index.byteOffset(start) : start,
              "byteLength",
              binary == null ? index.byteLength(start, length) : length));
    }
  }

  private static final class Descriptor {
    final Segment segment;
    final int sourceIndex, splitStart, splitCount, byteStart, byteLength;
    final TextIndex textIndex;
    final byte[] binary;

    Descriptor(Segment segment, int sourceIndex, int splitStart, int byteStart) {
      this.segment = segment;
      this.sourceIndex = sourceIndex;
      this.splitStart = splitStart;
      this.byteStart = byteStart;
      textIndex = segment.text() == null ? null : new TextIndex(segment.text());
      binary = textIndex == null ? segment.logicalBytes() : null;
      byteLength = textIndex == null ? binary.length : textIndex.byteLength;
      splitCount =
          segment.mode().equals("byte") ? textIndex == null ? binary.length : textIndex.length : 1;
    }

    int localByteOffset(int start) {
      return !segment.mode().equals("byte")
          ? 0
          : textIndex == null ? start : textIndex.byteOffset(start);
    }

    int localByteLength(int start, int length) {
      return !segment.mode().equals("byte")
          ? byteLength
          : textIndex == null ? length : textIndex.byteLength(start, length);
    }

    int parity() {
      return textIndex == null ? xor(binary) : textIndex.parity;
    }

    Segment slice(int start, int length) {
      if (!segment.mode().equals("byte")) return segment;
      return textIndex == null
          ? Segment.bytes(Arrays.copyOfRange(binary, start, start + length))
          : Segment.bytes(textIndex.slice(start, length));
    }
  }

  private static final class SegmentSource extends Source {
    final List<Segment> segments;
    final List<Descriptor> descriptors = new ArrayList<>();

    SegmentSource(List<Segment> input, Options options, int maximum) {
      segments = manual(input, Math.min(Segments.MAX_PAYLOAD_UNITS, unitBudget(options, maximum)));
      inputLength = segments.size();
      int version = maximumVersion(options);
      long bits = 0;
      for (int i = 0; i < segments.size(); i++) {
        Segment segment = segments.get(i);
        Descriptor descriptor = new Descriptor(segment, i, length, byteLength);
        descriptors.add(descriptor);
        length += descriptor.splitCount;
        byteLength += descriptor.byteLength;
        parity ^= descriptor.parity();
        bits +=
            4
                + Tables.countBits(segment.mode(), version)
                + payloadBits(segment.mode(), segment.characterCount(), descriptor.byteLength);
      }
      if (bits > maximum * Math.max(0, capacity(options, version) - 20L))
        throw tooLong(
            "Input segments cannot be split within the selected Structured Append capacity");
    }

    @Override
    long bits(int start, int length, Options options, int version) {
      int end = start + length;
      long bits = 20;
      for (Descriptor d : descriptors) {
        int finish = d.splitStart + d.splitCount;
        if (finish <= start) continue;
        if (d.splitStart >= end) break;
        int localStart = Math.max(start, d.splitStart) - d.splitStart;
        int localLength = Math.min(end, finish) - (d.splitStart + localStart);
        bits +=
            segmentBits(
                d.segment.mode(),
                d.segment.mode().equals("byte") ? localLength : d.segment.characterCount(),
                d.localByteLength(localStart, localLength),
                version);
        if (bits > capacity(options, version)) break;
      }
      return bits;
    }

    @Override
    Chunk chunk(int start, int length) {
      List<Segment> result = new ArrayList<>();
      int firstIndex = -1, lastIndex = -1, byteStart = 0, byteLength = 0, end = start + length;
      for (Descriptor d : descriptors) {
        int finish = d.splitStart + d.splitCount;
        if (finish <= start) continue;
        if (d.splitStart >= end) break;
        int localStart = Math.max(start, d.splitStart) - d.splitStart;
        int localLength = Math.min(end, finish) - (d.splitStart + localStart);
        if (firstIndex < 0) {
          firstIndex = d.sourceIndex;
          byteStart = d.byteStart + d.localByteOffset(localStart);
        }
        lastIndex = d.sourceIndex + 1;
        byteLength += d.localByteLength(localStart, localLength);
        result.add(d.slice(localStart, localLength));
      }
      return new Chunk(
          List.copyOf(result),
          SpecQr.map(
              "sourceSegmentStart",
              firstIndex,
              "sourceSegmentEnd",
              lastIndex,
              "splitUnitStart",
              start,
              "splitUnitLength",
              length,
              "byteStart",
              byteStart,
              "byteLength",
              byteLength));
    }

    List<Map<String, Object>> fullDetail() {
      List<Map<String, Object>> result = new ArrayList<>(length);
      for (Descriptor d : descriptors)
        for (int unit = 0; unit < d.splitCount; unit++)
          result.add(
              SpecQr.map(
                  "sourceSegmentIndex",
                  d.sourceIndex,
                  "mode",
                  d.segment.mode(),
                  "unitStart",
                  d.segment.mode().equals("byte") ? unit : 0,
                  "unitLength",
                  d.segment.mode().equals("byte") ? 1 : d.segment.characterCount(),
                  "byteStart",
                  d.byteStart + d.localByteOffset(unit),
                  "byteLength",
                  d.localByteLength(unit, 1)));
      return result;
    }
  }

  private record Range(int start, int length) {}

  private record Selection(int version, List<Range> ranges, String strategy) {}

  private static Selection select(Source source, Options options, int maximum) {
    int first = options.version() == null ? options.minVersion() : options.version(),
        last = maximumVersion(options);
    boolean sawTooLong = false;
    for (int version = first; version <= last; version++) {
      if (source.bits(0, source.length, options, version) <= capacity(options, version)) continue;
      List<Range> ranges = new ArrayList<>();
      int start = 0;
      while (start < source.length && ranges.size() < maximum) {
        int limit = source.length - start - (ranges.isEmpty() ? 1 : 0);
        int length = source.largestPrefix(start, limit, options, version);
        if (length == 0) break;
        ranges.add(new Range(start, length));
        start += length;
      }
      if (start == source.length && ranges.size() >= 2)
        return new Selection(
            version, List.copyOf(ranges), options.version() == null ? "auto-minimum" : "fixed");
      sawTooLong = true;
    }
    if (sawTooLong)
      throw tooLong(
          "Input cannot be split into "
              + maximum
              + " or fewer Structured Append symbols in the selected version range");
    throw invalid(
        "Input fits in one symbol in the selected version range; use generate(),"
            + " generateSegments(), or a low-level Structured Append header");
  }

  @SuppressWarnings("unchecked")
  private static Result generate(
      Source source, Options options, int maximum, DiagnosticOptions detail) {
    Selection selection = select(source, options, maximum);
    int version = selection.version(), total = selection.ranges().size();
    List<QrCode> symbols = new ArrayList<>(total);
    List<Map<String, Object>> diagnostics = new ArrayList<>(total);
    for (int i = 0; i < total; i++) {
      Range range = selection.ranges().get(i);
      Chunk chunk = source.chunk(range.start(), range.length());
      Options fixed =
          options.toBuilder()
              .version(version)
              .minVersion(version)
              .maxVersion(version)
              .structuredAppend(Segment.structuredAppend(i + 1, total, source.parity))
              .build();
      QrCode result =
          chunk.data() instanceof String text
              ? SpecQr.generate(text, fixed)
              : chunk.data() instanceof byte[] bytes
                  ? SpecQr.generate(bytes, fixed)
                  : SpecQr.generateSegments((List<Segment>) chunk.data(), fixed);
      symbols.add(result);
      long bits = Segments.bitLength(result.segments(), version);
      Map<String, Object> symbol =
          SpecQr.map(
              "index",
              i + 1,
              "total",
              total,
              "parity",
              source.parity,
              "sequenceIndex",
              i,
              "sequenceTotal",
              total - 1,
              "sequenceIndicator",
              (i << 4) | (total - 1));
      symbol.putAll(chunk.offsets());
      symbol.putAll(
          SpecQr.map(
              "version",
              version,
              "errorCorrectionLevel",
              result.errorCorrectionLevel(),
              "dataBitLength",
              bits,
              "capacityBits",
              capacity(options, version),
              "remainingBits",
              capacity(options, version) - bits,
              "maskPattern",
              result.maskPattern()));
      diagnostics.add(symbol);
    }
    List<Map<String, Object>> warnings = new ArrayList<>();
    if (total == maximum)
      warnings.add(
          SpecQr.map(
              "code",
              "STRUCTURED_APPEND_MAX_SYMBOLS_NEAR_LIMIT",
              "severity",
              "info",
              "message",
              "The generated Structured Append set uses the configured maximum number of symbols.",
              "details",
              SpecQr.map("total", total, "maxSymbols", maximum)));
    if (detail.symbolResults().equals("diagnostics"))
      warnings.add(
          SpecQr.map(
              "code",
              "STRUCTURED_APPEND_DECODER_SUPPORT_VARIES",
              "severity",
              "info",
              "message",
              "Decoder APIs vary in how they expose Structured Append set metadata.",
              "details",
              SpecQr.map("total", total)));
    String reason =
        selection.strategy().equals("fixed")
            ? "Version " + version + " was requested explicitly."
            : "Version "
                + version
                + " is the smallest version in "
                + options.minVersion()
                + ".."
                + options.maxVersion()
                + " that can split the payload into "
                + total
                + " Structured Append symbols at error correction "
                + options.errorCorrectionLevel()
                + ".";
    Map<String, Object> summary =
        SpecQr.map(
            "version",
            version,
            "errorCorrectionLevel",
            options.errorCorrectionLevel(),
            "versionSelection",
            selection.strategy(),
            "versionSelectionReason",
            reason,
            "total",
            total,
            "parity",
            source.parity,
            "byteLength",
            source.byteLength,
            "inputLength",
            source.inputLength,
            "maxSymbols",
            maximum,
            "splitStrategy",
            source instanceof SegmentSource
                ? "segment-boundary-byte-chunk"
                : "greedy-largest-fitting",
            "symbols",
            diagnostics,
            "warnings",
            warnings);
    if (source instanceof SegmentSource manual) {
      summary.putAll(
          SpecQr.map(
              "segmentCount",
              manual.segments.size(),
              "splitUnitCount",
              source.length,
              "splitUnitsDetail",
              detail.splitUnits()));
      if (detail.splitUnits().equals("full")) summary.put("splitUnits", manual.fullDetail());
    }
    return new Result(
        symbols, total, source.parity, source.inputLength, source.byteLength, summary);
  }

  public static Result generate(String input) {
    return generate(input, Options.defaults());
  }

  public static Result generate(byte[] input) {
    return generate(input, Options.defaults());
  }

  public static Result generate(String input, Options options) {
    return generate(input, options, 16);
  }

  public static Result generate(byte[] input, Options options) {
    return generate(input, options, 16);
  }

  public static Result generate(String input, Options options, int maxSymbols) {
    return generate(input, options, maxSymbols, DiagnosticOptions.defaults());
  }

  public static Result generate(byte[] input, Options options, int maxSymbols) {
    return generate(input, options, maxSymbols, DiagnosticOptions.defaults());
  }

  public static Result generate(
      String input, Options options, int maxSymbols, DiagnosticOptions detail) {
    options(options, maxSymbols, false);
    diagnosticOptions(detail);
    return generate(new InputSource(input, null, options, maxSymbols), options, maxSymbols, detail);
  }

  public static Result generate(
      byte[] input, Options options, int maxSymbols, DiagnosticOptions detail) {
    options(options, maxSymbols, false);
    diagnosticOptions(detail);
    return generate(new InputSource(null, input, options, maxSymbols), options, maxSymbols, detail);
  }

  public static Result generateSegments(List<Segment> segments) {
    return generateSegments(segments, Options.defaults());
  }

  public static Result generateSegments(List<Segment> segments, Options options) {
    return generateSegments(segments, options, 16);
  }

  public static Result generateSegments(List<Segment> segments, Options options, int maxSymbols) {
    return generateSegments(segments, options, maxSymbols, DiagnosticOptions.defaults());
  }

  public static Result generateSegments(
      List<Segment> segments, Options options, int maxSymbols, DiagnosticOptions detail) {
    options(options, maxSymbols, true);
    diagnosticOptions(detail);
    return generate(new SegmentSource(segments, options, maxSymbols), options, maxSymbols, detail);
  }

  /** Validates a complete set, sorts indexes, verifies canonical XOR and joins. */
  public static MergeResult merge(List<Part> parts) {
    if (parts == null || parts.isEmpty())
      throw invalid("Structured Append parts must be a non-empty list");
    if (parts.size() > 16) throw invalid("Structured Append cannot contain more than 16 parts");
    Part[] ordered = new Part[17];
    PartInfo[] descriptions = new PartInfo[17];
    Integer total = null, parity = null;
    String kind = null;
    int bytes = 0, actual = 0;
    long units = 0;
    for (Part part : parts) {
      if (part == null) throw invalid("Structured Append parts must not contain null");
      if (part.index() < 1 || part.index() > 16)
        throw invalid("index must be an integer from 1 to 16");
      if (part.total() < 2 || part.total() > 16)
        throw invalid("total must be an integer from 2 to 16");
      if (part.parity() < 0 || part.parity() > 255)
        throw invalid("parity must be an integer from 0 to 255");
      if (part.index() > part.total())
        throw invalid("Structured Append index must not exceed total");
      if (total != null && total != part.total()) throw invalid("Structured Append total mismatch");
      if (parity != null && parity != part.parity())
        throw invalid("Structured Append parity mismatch");
      if (ordered[part.index()] != null)
        throw invalid("Structured Append duplicate index " + part.index());
      String partKind;
      int size, checksum;
      if (part.data instanceof String text) {
        if (text.length() > 2L * Segments.MAX_PAYLOAD_UNITS)
          throw tooLong("Merged payload exceeds the 1000000-unit resource limit");
        TextIndex index = new TextIndex(text);
        units += index.length;
        partKind = "string";
        size = index.byteLength;
        checksum = index.parity;
      } else if (part.data instanceof byte[] data) {
        units += data.length;
        partKind = "binary";
        size = data.length;
        checksum = xor(data);
      } else throw invalid("Part data must be text or bytes");
      if (units > Segments.MAX_PAYLOAD_UNITS)
        throw tooLong("Merged payload exceeds the 1000000-unit resource limit");
      if (kind != null && !kind.equals(partKind))
        throw invalid("Structured Append parts must not mix string and binary data");
      total = part.total();
      parity = part.parity();
      kind = partKind;
      bytes += size;
      actual ^= checksum;
      ordered[part.index()] = part;
      descriptions[part.index()] =
          new PartInfo(part.index(), part.total(), part.parity(), kind, size);
    }
    List<String> missing = new ArrayList<>();
    for (int i = 1; i <= total; i++) if (ordered[i] == null) missing.add(Integer.toString(i));
    if (!missing.isEmpty())
      throw invalid("Structured Append parts are missing indexes: " + String.join(", ", missing));
    if (parts.size() != total) throw invalid("Structured Append part count does not match total");
    if (actual != parity)
      throw invalid(
          "Structured Append parity check failed: expected " + parity + ", got " + actual);
    Object merged;
    List<PartInfo> info = new ArrayList<>(total);
    if (kind.equals("string")) {
      StringBuilder out = new StringBuilder((int) units);
      for (int i = 1; i <= total; i++) {
        out.append((String) ordered[i].data);
        info.add(descriptions[i]);
      }
      merged = out.toString();
    } else {
      ByteArrayOutputStream out = new ByteArrayOutputStream(bytes);
      for (int i = 1; i <= total; i++) {
        byte[] data = (byte[]) ordered[i].data;
        out.write(data, 0, data.length);
        info.add(descriptions[i]);
      }
      merged = out.toByteArray();
    }
    return new MergeResult(
        merged,
        total,
        parity,
        info,
        SpecQr.map(
            "partCount",
            total,
            "total",
            total,
            "parity",
            parity,
            "dataType",
            kind,
            "byteLength",
            bytes,
            "missing",
            List.of(),
            "duplicate",
            List.of(),
            "parityCheck",
            SpecQr.map("expected", parity, "actual", actual, "matches", true)));
  }
}
