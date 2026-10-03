package io.specqr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/** Dependency-free QR Code Model 2 encoder and planning facade. */
public final class SpecQr {
  public static final String VERSION = "0.1.0-rc.1";

  private SpecQr() {}

  static Map<String, Object> map(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
    return m;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> freeze(Map<String, Object> m) {
    if (m == null) throw new SpecQrException("INVALID_INPUT", "Diagnostics must not be null");
    return (Map<String, Object>) freezeValue(m, 0, new int[] {0});
  }

  private static Object freezeValue(Object v, int depth, int[] nodes) {
    if (depth > 64 || ++nodes[0] > 1_000_000)
      throw new SpecQrException("INVALID_INPUT", "Diagnostics nesting or item budget exceeded");
    if (v instanceof Map<?, ?> m) {
      if (m.size() > 1_000_000)
        throw new SpecQrException("INVALID_INPUT", "Diagnostics item budget exceeded");
      Map<String, Object> n = new LinkedHashMap<>();
      for (var e : m.entrySet()) {
        if (!(e.getKey() instanceof String))
          throw new SpecQrException("INVALID_INPUT", "Diagnostic keys must be strings");
        n.put((String) e.getKey(), freezeValue(e.getValue(), depth + 1, nodes));
      }
      return Collections.unmodifiableMap(n);
    }
    if (v instanceof List<?> a) {
      if (a.size() > 1_000_000)
        throw new SpecQrException("INVALID_INPUT", "Diagnostics item budget exceeded");
      List<Object> b = new ArrayList<>(a.size());
      for (Object x : a) b.add(freezeValue(x, depth + 1, nodes));
      return Collections.unmodifiableList(b);
    }
    if (v != null && v.getClass().isArray()) {
      if (java.lang.reflect.Array.getLength(v) > 1_000_000)
        throw new SpecQrException("INVALID_INPUT", "Diagnostics item budget exceeded");
      List<Object> b = new ArrayList<>();
      for (int i = 0; i < java.lang.reflect.Array.getLength(v); i++)
        b.add(freezeValue(java.lang.reflect.Array.get(v, i), depth + 1, nodes));
      return Collections.unmodifiableList(b);
    }
    if (v == null
        || v instanceof String
        || v instanceof Boolean
        || v instanceof Byte
        || v instanceof Short
        || v instanceof Integer
        || v instanceof Long
        || v instanceof Float
        || v instanceof Double
        || v.getClass() == java.math.BigInteger.class
        || v.getClass() == java.math.BigDecimal.class) return v;
    throw new SpecQrException("INVALID_INPUT", "Unsupported mutable diagnostics value");
  }

  private static Options opts(Options o) {
    if (o == null) throw new SpecQrException("INVALID_INPUT", "Options must not be null");
    return o;
  }

  public static QrCode generate(String text) {
    return generate(text, Options.defaults());
  }

  public static QrCode generate(byte[] data) {
    return generate(data, Options.defaults());
  }

  public static QrCode generate(String text, Options o) {
    return build(estimate(text, o), o);
  }

  public static QrCode generate(byte[] data, Options o) {
    return build(estimate(data, o), o);
  }

  public static QrCode generateSegments(List<Segment> s) {
    return generateSegments(s, Options.defaults());
  }

  public static QrCode generateSegments(List<Segment> s, Options o) {
    return build(analyzeSegments(s, o), o);
  }

  public static Plan estimate(String text) {
    return estimate(text, Options.defaults());
  }

  public static Plan estimate(byte[] data) {
    return estimate(data, Options.defaults());
  }

  public static Plan estimate(String text, Options options) {
    Options o = opts(options);
    if (text == null) throw new SpecQrException("INVALID_INPUT", "Text must not be null");
    if (text.length() > 2_000_000)
      throw new SpecQrException("DATA_TOO_LONG", "Input exceeds resource budget");
    String selectedMode = o.mode();
    Map<String, Object> gs1 = null;
    if (o.gs1()) {
      var parsed = Gs1.parseElementString(text);
      gs1 =
          map(
              "enabled",
              true,
              "elementCount",
              parsed.elements().size(),
              "ais",
              parsed.elements().stream().map(Gs1.Element::ai).toList(),
              "hasSeparators",
              text.indexOf(29) >= 0);
    }
    if ((o.gs1() || o.fnc1Second() != null) && text.indexOf('%') >= 0) {
      if (selectedMode.equals("alphanumeric"))
        throw new SpecQrException(
            "INVALID_MODE",
            "Literal percent in high-level FNC1 requires byte mode; use manual escaped segments for"
                + " low-level data");
      if (selectedMode.equals("auto")) selectedMode = "byte";
    }
    final String mode = selectedMode;
    Map<Integer, List<Segment>> cache = new LinkedHashMap<>();
    return select(
        v ->
            cache.computeIfAbsent(
                group(v),
                g ->
                    controls(
                        Segments.create(
                            text,
                            v,
                            mode,
                            o.optimizeSegments() && text.codePointCount(0, text.length()) <= 7089,
                            o.eci() == null),
                        o)),
        o,
        gs1);
  }

  public static Plan estimate(byte[] data, Options options) {
    Options o = opts(options);
    if (data == null) throw new SpecQrException("INVALID_INPUT", "Bytes must not be null");
    if (data.length > 1_000_000)
      throw new SpecQrException("DATA_TOO_LONG", "Input exceeds resource budget");
    if (o.gs1())
      throw new SpecQrException("INVALID_GS1", "High-level GS1 input requires an element string");
    byte[] snapshot = data.clone();
    Map<Integer, List<Segment>> cache = new LinkedHashMap<>();
    return select(
        v ->
            cache.computeIfAbsent(
                group(v),
                g ->
                    controls(
                        Segments.create(
                            snapshot, v, o.mode(), o.optimizeSegments(), o.eci() == null),
                        o)),
        o,
        null);
  }

  public static Plan analyzeSegments(List<Segment> s) {
    return analyzeSegments(s, Options.defaults());
  }

  public static Plan analyzeSegments(List<Segment> s, Options options) {
    Options o = opts(options);
    List<Segment> data = controls(Segments.normalize(s), o);
    return select(v -> data, o, null);
  }

  private static int group(int v) {
    return v <= 9 ? 0 : v <= 26 ? 1 : 2;
  }

  private static List<Segment> controls(List<Segment> s, Options o) {
    List<Segment> a = new ArrayList<>(s.size() + 1);
    if (o.eci() != null) a.add(Segment.eci(o.eci()));
    else if (o.gs1()) a.add(Segment.fnc1());
    else if (o.fnc1Second() != null) a.add(Segment.fnc1Second(o.fnc1Second()));
    else if (o.structuredAppend() != null) a.add(o.structuredAppend());
    a.addAll(s);
    return Segments.normalize(a);
  }

  private static Plan select(
      IntFunction<List<Segment>> factory, Options o, Map<String, Object> gs1) {
    int first = o.version() != null ? o.version() : o.minVersion(),
        last = o.version() != null ? o.version() : o.maxVersion(),
        v = first;
    List<Segment> s = List.of();
    long bits = 0;
    String level = o.errorCorrectionLevel();
    boolean ok = false;
    for (v = first; v <= last; v++) {
      s = factory.apply(v);
      bits = Segments.bitLength(s, v);
      ok = bits <= Tables.dataCodewords(v, level) * 8L;
      if (ok) {
        if (o.boostErrorCorrection()) {
          for (int i = "LMQH".indexOf(level) + 1; i < 4; i++) {
            String stronger = "LMQH".substring(i, i + 1);
            if (bits <= Tables.dataCodewords(v, stronger) * 8L) level = stronger;
          }
        }
        break;
      }
    }
    if (!ok) v = last;
    int capacity = Tables.dataCodewords(v, level) * 8;
    Map<String, Object> d = diagnostics(s, v, level, bits, o, true, ok);
    if (gs1 != null) d.put("gs1Validation", gs1);
    return new Plan(
        ok,
        ok || o.version() != null ? v : null,
        v,
        level,
        o.errorCorrectionLevel(),
        !level.equals(o.errorCorrectionLevel()),
        bits,
        capacity,
        capacity - bits,
        s,
        d);
  }

  private static QrCode build(Plan p, Options o) {
    if (!p.ok())
      throw new SpecQrException(
          "DATA_TOO_LONG",
          "Input requires "
              + p.requiredBits()
              + " bits; version "
              + p.capacityVersion()
              + "-"
              + p.errorCorrectionLevel()
              + " holds "
              + p.capacityBits());
    int v = p.capacityVersion();
    byte[] data = Core.padDataBits(Segments.bits(p.segments(), v), v, p.errorCorrectionLevel());
    var interleaved = Core.interleaveCodewords(data, v, p.errorCorrectionLevel());
    var built =
        Core.buildMatrix(interleaved.codewords(), v, p.errorCorrectionLevel(), o.maskPattern());
    Map<String, Object> d =
        diagnostics(p.segments(), v, p.errorCorrectionLevel(), p.requiredBits(), o, false, true);
    d.put("gs1Validation", p.diagnostics().get("gs1Validation"));
    d.putAll(
        map(
            "maskPattern",
            built.maskPattern(),
            "maskPenalty",
            built.penalty(),
            "maskPenalties",
            built.maskPenalties().stream()
                .map(m -> map("maskPattern", m.maskPattern(), "penalty", m.penalty()))
                .toList(),
            "maskSelectionReason",
            o.maskPattern() != null
                ? "Explicit mask requested."
                : "Lowest penalty; first mask wins ties.",
            "dataCodewords",
            data.length,
            "errorCorrectionCodewords",
            interleaved.codewords().length - data.length,
            "totalCodewords",
            interleaved.codewords().length));
    return new QrCode(
        built.matrix(),
        v,
        built.maskPattern(),
        p.errorCorrectionLevel(),
        data,
        interleaved.codewords(),
        p.segments(),
        d,
        o);
  }

  public static Capacity getCapacity(int version) {
    return getCapacity(version, "M", null, 0);
  }

  public static Capacity getCapacity(int version, String level) {
    return getCapacity(version, level, null, 0);
  }

  public static Capacity getCapacity(int version, String level, String mode) {
    return getCapacity(version, level, mode, 0);
  }

  public static Capacity getCapacity(int version, String level, String mode, long controls) {
    Tables.validateVersion(version);
    Tables.validateLevel(level);
    if (controls < 0 || controls > 9007199254740991L)
      throw new SpecQrException("INVALID_INPUT", "controlBits must be 0..2^53-1");
    int data = Tables.dataCodewords(version, level);
    Integer width = null, maximum = null;
    Long payload = null;
    if (mode != null) {
      if (!List.of("numeric", "alphanumeric", "byte", "kanji").contains(mode))
        throw new SpecQrException("INVALID_MODE", "Unknown capacity mode");
      width = Tables.countBits(mode, version);
      payload = Math.max(0, 8L * data - controls - 4 - width);
      long n =
          switch (mode) {
            case "numeric" ->
                payload / 10 * 3 + (payload % 10 >= 7 ? 2 : payload % 10 >= 4 ? 1 : 0);
            case "alphanumeric" -> payload / 11 * 2 + (payload % 11 >= 6 ? 1 : 0);
            case "byte" -> payload / 8;
            default -> payload / 13;
          };
      maximum = (int) Math.min(n, (1 << width) - 1);
    }
    return new Capacity(
        version,
        level,
        Tables.size(version),
        data,
        Tables.rawCodewords(version),
        data * 8,
        mode,
        width,
        mode == null ? null : 4,
        controls,
        payload,
        "byte".equals(mode) ? null : maximum,
        "byte".equals(mode) ? maximum : null);
  }

  private static void warning(
      List<Map<String, Object>> w,
      String code,
      String severity,
      String message,
      Map<String, Object> details) {
    w.add(map("code", code, "severity", severity, "message", message, "details", details));
  }

  private static Map<String, Object> diagnostics(
      List<Segment> s, int v, String level, long bits, Options o, boolean planning, boolean ok) {
    int capacity = Tables.dataCodewords(v, level) * 8;
    List<Segment> controls = s.stream().filter(Segment::isControl).toList();
    List<String> modes =
        s.stream().filter(x -> !x.isControl()).map(Segment::mode).distinct().toList();
    String mode = modes.size() == 1 ? modes.get(0) : modes.isEmpty() ? "byte" : "mixed";
    List<Map<String, Object>> w = new ArrayList<>();
    if (o.margin() < 4)
      warning(
          w,
          "QUIET_ZONE_TOO_SMALL",
          "warning",
          "QR readers expect at least four quiet-zone modules.",
          map("margin", o.margin()));
    int[] fg = Render.parseColor(o.foreground(), false),
        bg = Render.parseColor(o.background(), false);
    Double ratio = fg == null || bg == null ? null : Render.contrastRatio(fg, bg);
    if (ratio == null)
      warning(
          w,
          "COLOR_CONTRAST_UNKNOWN",
          "info",
          "These SVG colors cannot be checked for contrast.",
          map());
    else if (ratio < 4.5)
      warning(
          w,
          "COLOR_CONTRAST_LOW",
          "warning",
          "Color contrast is below the recommended minimum.",
          map("ratio", ratio));
    else if (ratio < 7)
      warning(
          w,
          "COLOR_CONTRAST_MODERATE",
          "info",
          "Stronger contrast is recommended.",
          map("ratio", ratio));
    if (fg != null && bg != null && (fg[3] < 255 || bg[3] < 255))
      warning(w, "COLOR_ALPHA_USED", "warning", "Transparency can reduce scan reliability.", map());
    if (capacity - bits >= 0 && capacity - bits < capacity * .05)
      warning(w, "CAPACITY_NEAR_LIMIT", "info", "Selected version is close to full.", map());
    Double mm = o.printDpi() == null ? null : o.scale() / o.printDpi() * 25.4;
    if (mm != null && mm < .25)
      warning(
          w,
          "PRINT_MODULE_TOO_SMALL",
          "warning",
          "Print modules are smaller than 0.25 mm.",
          map("moduleSizeMm", mm));
    List<Object> blocking =
        w.stream()
            .filter(x -> x.get("severity").equals("warning"))
            .map(x -> x.get("code"))
            .toList();
    if (!blocking.isEmpty())
      warning(
          w,
          "SCAN_RISK",
          "warning",
          "One or more settings may reduce scan reliability.",
          map("blockingWarnings", blocking));
    Segment
        sa =
            controls.stream()
                .filter(x -> x.mode().equals("structured-append"))
                .findFirst()
                .orElse(null),
        second =
            controls.stream().filter(x -> x.mode().equals("fnc1-second")).findFirst().orElse(null),
        eci = controls.stream().filter(x -> x.mode().equals("eci")).findFirst().orElse(null);
    boolean gs1 = controls.stream().anyMatch(x -> x.mode().equals("fnc1"));
    String fnc1 = gs1 ? "first-position" : second != null ? "second-position" : null;
    return map(
        "phase",
        planning ? "planning" : "generation",
        "renderPlanned",
        false,
        "maskEvaluated",
        !planning,
        "codewordsBuilt",
        !planning,
        "version",
        ok || o.version() != null ? v : null,
        "size",
        ok || o.version() != null ? Tables.size(v) : null,
        "errorCorrectionLevel",
        level,
        "requestedErrorCorrectionLevel",
        o.errorCorrectionLevel(),
        "boostedErrorCorrection",
        !level.equals(o.errorCorrectionLevel()),
        "versionSelection",
        o.version() != null ? "fixed" : ok ? "auto-minimum" : "auto-range",
        "versionSelectionReason",
        o.version() != null
            ? "Explicit version requested."
            : ok
                ? "Smallest fitting version in the requested range."
                : "No fitting version in the requested range.",
        "mode",
        mode,
        "controlSegments",
        controls.stream().map(x -> map("mode", x.mode(), "bitLength", x.totalBits(v))).toList(),
        "eciAssignmentNumber",
        eci == null ? null : eci.assignment(),
        "fnc1",
        fnc1,
        "gs1",
        gs1,
        "gs1Validation",
        map(
            "enabled",
            gs1,
            "elementCount",
            gs1 ? null : 0,
            "ais",
            List.of(),
            "hasSeparators",
            false),
        "fnc1Second",
        map(
            "enabled",
            second != null,
            "applicationIndicator",
            second == null ? null : second.applicationIndicator(),
            "applicationIndicatorCodeword",
            second == null ? null : second.applicationIndicatorCodeword()),
        "structuredAppend",
        map(
            "enabled",
            sa != null,
            "index",
            sa == null ? null : sa.index(),
            "total",
            sa == null ? null : sa.total(),
            "parity",
            sa == null ? null : sa.parity(),
            "sequenceIndex",
            sa == null ? null : sa.index() - 1,
            "sequenceTotal",
            sa == null ? null : sa.total() - 1,
            "sequenceIndicator",
            sa == null ? null : ((sa.index() - 1) << 4) | (sa.total() - 1)),
        "segments",
        s.stream()
            .map(
                x ->
                    map(
                        "mode",
                        x.mode(),
                        "characterCount",
                        x.characterCount(),
                        "byteCount",
                        x.byteCount(),
                        "bitLength",
                        x.totalBits(v)))
            .toList(),
        "dataBitLength",
        bits,
        "capacityBits",
        capacity,
        "remainingBits",
        capacity - bits,
        "capacityUtilization",
        (double) bits / capacity,
        "inputBytes",
        s.stream().mapToLong(x -> x.logicalBytes().length).sum(),
        "quietZone",
        map("modules", o.margin(), "recommendedModules", 4, "isSufficient", o.margin() >= 4),
        "colors",
        map(
            "ratio",
            ratio,
            "isInspectable",
            ratio != null,
            "foregroundAlpha",
            fg == null ? null : fg[3],
            "backgroundAlpha",
            bg == null ? null : bg[3],
            "isStrong",
            ratio != null && ratio >= 7,
            "isSufficient",
            ratio != null && ratio >= 4.5 && fg[3] == 255 && bg[3] == 255),
        "print",
        map(
            "dpi",
            o.printDpi(),
            "modulePixels",
            o.scale(),
            "moduleSizeMm",
            mm,
            "symbolSizeMm",
            mm == null ? null : (Tables.size(v) + o.margin() * 2L) * mm,
            "recommendedMinimumModuleSizeMm",
            .25,
            "isModuleSizeSufficient",
            mm == null ? null : mm >= .25),
        "warnings",
        w);
  }
}
