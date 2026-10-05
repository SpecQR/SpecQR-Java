package io.specqr;

import java.io.ByteArrayOutputStream;
import java.net.IDN;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded helpers for SpecQR's 50-AI GS1 catalog, not a complete GS1 validator. The HTTP URL
 * adapter is local and never resolves hosts or makes network calls. It follows special-URL syntax
 * rather than imposing java.net.URI's stricter character grammar. Unicode host labels use Java's
 * IDNA2003 implementation, not browsers' UTS #46; newer Unicode and deviation characters can
 * differ. ASCII ACE (xn--) labels must round-trip through IDNA2003 with unassigned code points
 * allowed, so valid IDNA2008 deviation labels may be rejected. Dot-only GS1 values are retained
 * when parsing and emitted in the query when creating/normalizing, avoiding destructive browser
 * dot-segment normalization.
 */
public final class Gs1 {
  public static final String FNC1_SEPARATOR = "\u001d";
  public static final String GS1_FNC1_SEPARATOR = FNC1_SEPARATOR;
  public static final int MAX_INPUT_CHARACTERS = 1_000_000;
  public static final int MAX_ELEMENTS = 16_384;
  private static final Set<String> PRIMARY = Set.of("00", "01", "414");
  private static final Pattern INVALID_PERCENT = Pattern.compile("%(?![0-9a-fA-F]{2})");
  private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):");
  private static final List<AiInfo> CATALOG = catalog();
  private static final Map<String, AiInfo> AIS;

  static {
    Map<String, AiInfo> entries = new LinkedHashMap<>();
    for (AiInfo info : CATALOG) entries.put(info.ai(), info);
    AIS = Map.copyOf(entries);
  }

  private Gs1() {}

  public record Element(String ai, String value) {}

  public record AiLength(String type, Integer exact, Integer min, Integer max) {
    public boolean isVariable() {
      return "variable".equals(type);
    }
  }

  public record AiInfo(
      String ai,
      String label,
      AiLength length,
      String valueKind,
      String checkDigitRule,
      String digitalLinkRole,
      String separator,
      List<String> digitalLinkPathForPrimary) {
    public AiInfo {
      if (digitalLinkPathForPrimary != null)
        digitalLinkPathForPrimary = List.copyOf(digitalLinkPathForPrimary);
    }
  }

  public record ElementStringParseResult(List<Element> elements, boolean hasSeparators) {
    public ElementStringParseResult {
      elements = List.copyOf(elements);
    }
  }

  public record ValidationIssue(
      String code,
      String message,
      String reason,
      String ai,
      String value,
      String key,
      Integer offset,
      Integer elementIndex,
      Object expected,
      Integer count) {
    public ValidationIssue {
      expected = SpecQr.freeze(SpecQr.map("expected", expected)).get("expected");
    }
  }

  public record ValidationOptions(
      String context, boolean collectAllErrors, boolean allowUnsupportedAi) {
    public static ValidationOptions defaults() {
      return new ValidationOptions("element-string", true, false);
    }
  }

  public record ValidationResult(
      boolean ok,
      List<Element> elements,
      Boolean hasSeparators,
      List<ValidationIssue> errors,
      List<ValidationIssue> warnings) {
    public ValidationResult {
      if (elements != null) elements = List.copyOf(elements);
      errors = List.copyOf(errors);
      warnings = List.copyOf(warnings);
    }
  }

  public record UnknownQuery(String key, String value) {}

  public record DigitalLinkParseResult(
      List<Element> elements,
      Element primary,
      List<Element> pathElements,
      List<Element> queryElements,
      List<UnknownQuery> unknownQuery) {
    public DigitalLinkParseResult {
      elements = List.copyOf(elements);
      pathElements = List.copyOf(pathElements);
      queryElements = List.copyOf(queryElements);
      unknownQuery = List.copyOf(unknownQuery);
    }
  }

  public record DigitalLinkValidationResult(
      boolean ok,
      DigitalLinkParseResult result,
      List<ValidationIssue> errors,
      List<ValidationIssue> warnings) {
    public DigitalLinkValidationResult {
      errors = List.copyOf(errors);
      warnings = List.copyOf(warnings);
    }
  }

  /** Null primaryAi auto-detects for parsing and defaults to 01 for creation. */
  public record DigitalLinkOptions(
      String baseUrl,
      String primaryAi,
      List<String> pathAis,
      String unknownQuery,
      boolean normalize,
      String mode) {
    public DigitalLinkOptions {
      if (pathAis != null) {
        if (pathAis.size() > MAX_ELEMENTS)
          throw failure("GS1 Digital Link pathAis exceeds element limit");
        pathAis = java.util.Collections.unmodifiableList(new ArrayList<>(pathAis));
      }
    }

    public static DigitalLinkOptions defaults() {
      return new DigitalLinkOptions(null, null, null, "preserve", false, "specqr-deterministic");
    }

    public static DigitalLinkOptions forBaseUrl(String url) {
      return new DigitalLinkOptions(url, null, null, "preserve", false, "specqr-deterministic");
    }

    public DigitalLinkOptions withPrimaryAi(String ai) {
      return new DigitalLinkOptions(baseUrl, ai, pathAis, unknownQuery, normalize, mode);
    }

    public DigitalLinkOptions withPathAis(List<String> ais) {
      return new DigitalLinkOptions(baseUrl, primaryAi, ais, unknownQuery, normalize, mode);
    }

    public DigitalLinkOptions withUnknownQuery(String policy) {
      return new DigitalLinkOptions(baseUrl, primaryAi, pathAis, policy, normalize, mode);
    }
  }

  private static SpecQrException failure(String message) {
    return new SpecQrException("INVALID_GS1", message);
  }

  private static String text(String value, String label) {
    if (value == null) throw failure(label + " must be a string");
    if (value.length() > MAX_INPUT_CHARACTERS)
      throw failure(label + " must contain at most " + MAX_INPUT_CHARACTERS + " characters");
    return value;
  }

  private static boolean digits(String value) {
    if (value == null || value.isEmpty()) return false;
    for (int i = 0; i < value.length(); i++)
      if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
    return true;
  }

  private static boolean isAi(String value) {
    return value != null && value.length() >= 2 && value.length() <= 4 && digits(value);
  }

  private static List<Object> bounded(Iterable<?> values, String label) {
    if (values == null) throw failure(label + " must be an iterable of elements");
    List<Object> result = new ArrayList<>();
    long work = 0;
    for (Object value : values) {
      if (result.size() == MAX_ELEMENTS)
        throw failure(label + " must contain at most " + MAX_ELEMENTS + " elements");
      if (value instanceof Element e) work += stringLength(e.ai()) + stringLength(e.value());
      else if (value instanceof Map<?, ?> e)
        work += stringLength(e.get("ai")) + stringLength(e.get("value"));
      else work += stringLength(value);
      if (work > MAX_INPUT_CHARACTERS)
        throw failure(
            label
                + " aggregate text exceeds the character work budget ("
                + MAX_INPUT_CHARACTERS
                + ")");
      result.add(value);
    }
    return result;
  }

  private static int stringLength(Object value) {
    return value instanceof String s ? s.length() : 0;
  }

  private static void add(
      List<AiInfo> entries,
      String ai,
      String label,
      int size,
      boolean variable,
      String kind,
      String check,
      String role) {
    entries.add(
        new AiInfo(
            ai,
            label,
            variable
                ? new AiLength("variable", null, 1, size)
                : new AiLength("fixed", size, null, null),
            kind,
            check,
            role,
            variable ? "required-when-followed" : "none",
            "key-qualifier".equals(role) ? List.of("01") : null));
  }

  private static List<AiInfo> catalog() {
    List<AiInfo> entries = new ArrayList<>();
    add(
        entries,
        "00",
        "Serial shipping container code",
        18,
        false,
        "numeric",
        "sscc",
        "primary-key");
    add(entries, "01", "Global trade item number", 14, false, "numeric", "gtin", "primary-key");
    add(entries, "02", "Contained trade item GTIN", 14, false, "numeric", "gtin", "data-attribute");
    add(entries, "10", "Batch or lot number", 20, true, "text", "none", "key-qualifier");
    String[][] dates = {
      {"11", "Production date"},
      {"12", "Due date"},
      {"13", "Packaging date"},
      {"15", "Best before date"},
      {"16", "Sell by date"},
      {"17", "Expiration date"}
    };
    for (String[] e : dates)
      add(entries, e[0], e[1], 6, false, "numeric", "none", "data-attribute");
    add(entries, "20", "Internal product variant", 2, false, "numeric", "none", "data-attribute");
    add(entries, "21", "Serial number", 20, true, "text", "none", "key-qualifier");
    add(entries, "22", "Consumer product variant", 20, true, "text", "none", "key-qualifier");
    add(entries, "30", "Variable count", 8, true, "numeric", "none", "data-attribute");
    add(
        entries,
        "37",
        "Count of contained trade items",
        8,
        true,
        "numeric",
        "none",
        "data-attribute");
    String[][] others = {
      {"240", "Additional product identification"},
      {"241", "Customer part number"},
      {"400", "Customer purchase order number"}
    };
    for (String[] e : others) add(entries, e[0], e[1], 30, true, "text", "none", "data-attribute");
    String[] gln = {
      "Ship to global location number",
      "Bill to global location number",
      "Purchased from global location number",
      "Ship for global location number",
      "Identification of a physical location",
      "Global location number of the invoicing party"
    };
    for (int i = 0; i < gln.length; i++)
      add(
          entries,
          Integer.toString(410 + i),
          gln[i],
          13,
          false,
          "numeric",
          "none",
          i == 4 ? "primary-key" : "data-attribute");
    add(entries, "420", "Ship to postal code", 20, true, "text", "none", "data-attribute");
    String[][] countries = {
      {"422", "Country of origin"},
      {"424", "Country of processing"},
      {"425", "Country of disassembly"},
      {"426", "Country covering full process chain"}
    };
    for (String[] e : countries)
      add(entries, e[0], e[1], 3, false, "numeric", "none", "data-attribute");
    for (int start : new int[] {3100, 3200})
      for (int i = 0; i < 6; i++)
        add(
            entries,
            Integer.toString(start + i),
            start == 3100 ? "Net weight in kilograms" : "Net weight in pounds",
            6,
            false,
            "numeric",
            "none",
            "data-attribute");
    for (int i = 91; i < 100; i++)
      add(
          entries,
          Integer.toString(i),
          "Company internal information",
          90,
          true,
          "text",
          "none",
          "data-attribute");
    return List.copyOf(entries);
  }

  public static List<AiInfo> getSupportedAis() {
    return CATALOG;
  }

  public static AiInfo getAiInfo(String ai) {
    return ai == null ? null : AIS.get(ai);
  }

  public static List<AiInfo> getSupportedGs1Ais() {
    return getSupportedAis();
  }

  public static AiInfo getGs1AiInfo(String ai) {
    return getAiInfo(ai);
  }

  private static String numeric(String value, String label) {
    text(value, label);
    if (!digits(value)) throw failure(label + " must contain digits only");
    return value;
  }

  public static String calculateCheckDigit(String body) {
    numeric(body, "GS1 check digit input");
    int total = 0, weight = 3;
    for (int i = body.length() - 1; i >= 0; i--, weight = 4 - weight)
      total = (total + (body.charAt(i) - '0') * weight) % 10;
    return Integer.toString((10 - total) % 10);
  }

  public static boolean validateCheckDigit(String value) {
    numeric(value, "GS1 check digit value");
    if (value.length() < 2)
      throw failure("GS1 check digit value must include body digits and one check digit");
    return calculateCheckDigit(value.substring(0, value.length() - 1)).charAt(0)
        == value.charAt(value.length() - 1);
  }

  public static String calculateGtinCheckDigit(String body) {
    numeric(body, "GTIN body");
    if (!Set.of(7, 11, 12, 13).contains(body.length()))
      throw failure("GTIN body must be 7, 11, 12, or 13 digits");
    return calculateCheckDigit(body);
  }

  public static String appendGtinCheckDigit(String body) {
    return body + calculateGtinCheckDigit(body);
  }

  public static boolean validateGtinCheckDigit(String value) {
    numeric(value, "GTIN");
    if (!Set.of(8, 12, 13, 14).contains(value.length()))
      throw failure("GTIN must be 8, 12, 13, or 14 digits");
    return validateCheckDigit(value);
  }

  public static String calculateSsccCheckDigit(String body) {
    numeric(body, "SSCC body");
    if (body.length() != 17) throw failure("SSCC body must be exactly 17 digits");
    return calculateCheckDigit(body);
  }

  public static String appendSsccCheckDigit(String body) {
    return body + calculateSsccCheckDigit(body);
  }

  public static boolean validateSsccCheckDigit(String value) {
    numeric(value, "SSCC");
    if (value.length() != 18) throw failure("SSCC must be exactly 18 digits");
    return validateCheckDigit(value);
  }

  public static String calculateGs1CheckDigit(String body) {
    return calculateCheckDigit(body);
  }

  public static boolean validateGs1CheckDigit(String value) {
    return validateCheckDigit(value);
  }

  private static Element element(Object raw, int index) {
    Object ai, value;
    if (raw instanceof Element e) {
      ai = e.ai();
      value = e.value();
    } else if (raw instanceof Map<?, ?> e) {
      ai = e.get("ai");
      value = e.get("value");
    } else throw failure("GS1 element " + index + " must be an object");
    if (!(ai instanceof String a)) throw failure("GS1 element " + index + " AI must be a string");
    if (!(value instanceof String v))
      throw failure("GS1 element " + index + " value must be a string to preserve leading zeroes");
    text(a, "GS1 AI");
    text(v, "GS1 value");
    if (!isAi(a))
      throw failure(
          "GS1 element "
              + index
              + " has invalid AI \""
              + a.substring(0, Math.min(a.length(), 100))
              + "\"; expected 2 to 4 digits");
    AiInfo info = AIS.get(a);
    if (info == null)
      throw failure("Unsupported GS1 AI " + a + ". Add explicit support before using it.");
    String prefix = "GS1 AI " + a + " value ";
    if (v.isEmpty()) throw failure(prefix + "must not be empty");
    if (v.contains(FNC1_SEPARATOR)) throw failure(prefix + "must not contain the FNC1 separator");
    if (v.indexOf('(') >= 0 || v.indexOf(')') >= 0)
      throw failure(prefix + "must be raw data without human-readable parentheses");
    for (int i = 0; i < v.length(); i++)
      if (v.charAt(i) < ' ' || v.charAt(i) > '~')
        throw failure(prefix + "must use printable ASCII characters");
    if (info.valueKind().equals("numeric") && !digits(v))
      throw failure(prefix + "must contain digits only");
    if (info.length().isVariable()) {
      if (v.length() > info.length().max())
        throw failure(prefix + "must be at most " + info.length().max() + " characters");
    } else if (v.length() != info.length().exact())
      throw failure(prefix + "must be exactly " + info.length().exact() + " characters");
    if (info.checkDigitRule().equals("gtin") && !validateGtinCheckDigit(v))
      throw failure(prefix + "has an invalid GTIN check digit");
    if (info.checkDigitRule().equals("sscc") && !validateSsccCheckDigit(v))
      throw failure(prefix + "has an invalid SSCC check digit");
    return new Element(a, v);
  }

  public static List<Element> fromHumanReadable(String input) {
    text(input, "GS1 human-readable input");
    if (input.isEmpty()) throw failure("GS1 human-readable input must not be empty");
    List<Element> result = new ArrayList<>();
    for (int position = 0; position < input.length(); ) {
      if (result.size() == MAX_ELEMENTS) throw failure("GS1 elements exceed element limit");
      if (input.charAt(position) != '(')
        throw failure(
            "GS1 human-readable input must contain an AI in parentheses at offset " + position);
      int close = input.indexOf(')', position + 1);
      if (close < 0)
        throw failure(
            "GS1 AI starting at offset " + position + " is missing a closing parenthesis");
      int end = input.indexOf('(', close + 1);
      if (end < 0) end = input.length();
      result.add(
          element(
              new Element(input.substring(position + 1, close), input.substring(close + 1, end)),
              result.size()));
      position = end;
    }
    return List.copyOf(result);
  }

  public static List<Element> parseHumanReadable(String input) {
    return fromHumanReadable(input);
  }

  public static String toElementString(Iterable<?> elements) {
    List<Object> values = bounded(elements, "GS1 elements");
    if (values.isEmpty()) throw failure("GS1 elements must not be empty");
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      Element e = element(values.get(i), i);
      out.append(e.ai()).append(e.value());
      if (AIS.get(e.ai()).length().isVariable() && i + 1 < values.size())
        out.append(FNC1_SEPARATOR);
      if (out.length() > MAX_INPUT_CHARACTERS)
        throw failure("GS1 element string output exceeds character limit");
    }
    return out.toString();
  }

  public static String createElementString(Iterable<?> elements) {
    return toElementString(elements);
  }

  public static String toHumanReadable(Iterable<?> elements) {
    List<Object> values = bounded(elements, "GS1 elements");
    if (values.isEmpty()) throw failure("GS1 elements must not be empty");
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      Element e = element(values.get(i), i);
      out.append('(').append(e.ai()).append(')').append(e.value());
      if (out.length() > MAX_INPUT_CHARACTERS)
        throw failure("GS1 human-readable output exceeds character limit");
    }
    return out.toString();
  }

  public static String toHumanReadable(String input) {
    return toHumanReadable(parseElementString(input).elements());
  }

  private static AiInfo readAi(String input, int offset) {
    for (int size : new int[] {4, 3, 2})
      if (offset + size <= input.length()) {
        AiInfo found = AIS.get(input.substring(offset, offset + size));
        if (found != null) return found;
      }
    return null;
  }

  public static ElementStringParseResult parseElementString(String input) {
    text(input, "GS1 element string input");
    if (input.isEmpty()) throw failure("GS1 element string input must not be empty");
    if (input.indexOf('(') >= 0 || input.indexOf(')') >= 0)
      throw failure(
          "GS1 element string input must be raw data without human-readable parentheses; use"
              + " fromHumanReadable() and toElementString() first");
    List<Element> result = new ArrayList<>();
    for (int position = 0; position < input.length(); ) {
      if (result.size() == MAX_ELEMENTS) throw failure("GS1 elements exceed element limit");
      if (input.charAt(position) == '\u001d')
        throw failure("GS1 element string has an unexpected FNC1 separator at offset " + position);
      AiInfo info = readAi(input, position);
      if (info == null) throw failure("Unsupported GS1 AI at offset " + position);
      int start = position + info.ai().length();
      int end =
          info.length().isVariable()
              ? input.indexOf('\u001d', start)
              : Math.min(input.length(), start + info.length().exact());
      if (end < 0) end = input.length();
      if (info.length().isVariable() && end == input.length()) {
        for (int offset = Math.max(start + 1, end - 22); offset < end; offset++) {
          AiInfo suffix = readAi(input, offset);
          if (suffix != null
              && !suffix.length().isVariable()
              && offset + suffix.ai().length() + suffix.length().exact() == end)
            throw failure(
                "GS1 variable-length element at offset "
                    + start
                    + " is missing an FNC1 separator before offset "
                    + offset);
        }
      }
      result.add(element(new Element(info.ai(), input.substring(start, end)), result.size()));
      position = end;
      if (position < input.length()
          && input.charAt(position) == '\u001d'
          && info.length().isVariable()) {
        if (++position == input.length())
          throw failure("GS1 element string must not end with an FNC1 separator");
      }
    }
    return new ElementStringParseResult(result, input.contains(FNC1_SEPARATOR));
  }

  private static ValidationIssue simpleIssue(
      String code, String message, String reason, Object expected) {
    return new ValidationIssue(code, message, reason, null, null, null, null, null, expected, null);
  }

  private static ValidationIssue optionsIssue(ValidationOptions options) {
    if (options.context() != null
        && !Set.of("element-string", "digital-link").contains(options.context()))
      return simpleIssue(
          "GS1_INVALID_INPUT",
          "GS1 validation context must be \"element-string\" or \"digital-link\"",
          "invalid-options",
          "element-string or digital-link");
    if (options.allowUnsupportedAi())
      return simpleIssue(
          "GS1_INVALID_INPUT",
          "GS1 validation allowUnsupportedAi must be false",
          "invalid-options",
          false);
    return null;
  }

  private static ValidationResult invalid(ValidationIssue issue) {
    return new ValidationResult(false, null, null, List.of(issue), List.of());
  }

  public static ValidationResult validateElements(Iterable<?> elements) {
    return validateElements(elements, ValidationOptions.defaults());
  }

  public static ValidationResult validateElements(Iterable<?> elements, ValidationOptions options) {
    if (options == null) options = ValidationOptions.defaults();
    ValidationIssue bad = optionsIssue(options);
    if (bad != null) return invalid(bad);
    List<Object> values;
    try {
      values = bounded(elements, "GS1 elements");
      if (values.isEmpty()) throw failure("GS1 elements must not be empty");
    } catch (SpecQrException error) {
      return invalid(issue(error, null, null, null, false));
    }
    List<Element> normalized = new ArrayList<>();
    List<ValidationIssue> errors = new ArrayList<>();
    for (int i = 0; i < values.size(); i++) {
      try {
        normalized.add(element(values.get(i), i));
      } catch (SpecQrException error) {
        errors.add(issue(error, values.get(i), i, null, false));
        if (!options.collectAllErrors()) break;
      }
    }
    if (!errors.isEmpty()) return new ValidationResult(false, null, null, errors, List.of());
    if ("digital-link".equals(options.context())
        && normalized.stream().noneMatch(e -> PRIMARY.contains(e.ai())))
      return invalid(
          simpleIssue(
              "GS1_INVALID_DIGITAL_LINK_PLACEMENT",
              "GS1 Digital Link elements must include a primary AI 00, 01, or 414",
              "invalid-digital-link-placement",
              "primary AI 00, 01, or 414"));
    return new ValidationResult(true, normalized, null, List.of(), List.of());
  }

  public static ValidationResult validateElementString(String input) {
    return validateElementString(input, ValidationOptions.defaults());
  }

  public static ValidationResult validateElementString(String input, ValidationOptions options) {
    if (options == null) options = ValidationOptions.defaults();
    ValidationIssue bad = optionsIssue(options);
    if (bad != null) return invalid(bad);
    try {
      ElementStringParseResult parsed = parseElementString(input);
      return new ValidationResult(
          true, parsed.elements(), parsed.hasSeparators(), List.of(), List.of());
    } catch (SpecQrException error) {
      return invalid(issue(error, null, null, input, false));
    }
  }

  private static String match(String pattern, String value) {
    Matcher matcher = Pattern.compile(pattern).matcher(value);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static ValidationIssue issue(
      SpecQrException error, Object raw, Integer index, String input, boolean digital) {
    String message = error.getMessage(), code = "GS1_INVALID_INPUT", reason = "invalid-input";
    Object expected = null;
    if (digital
        && (message.contains("absolute http or https URL")
            || message.contains("must use http or https"))) {
      code = "GS1_DIGITAL_LINK_INVALID_URI";
      reason = "invalid-uri";
      expected = "absolute http or https URL";
    } else if (digital && message.contains("must not include a fragment")) {
      code = "GS1_DIGITAL_LINK_FRAGMENT_NOT_ALLOWED";
      reason = "fragment-not-allowed";
      expected = "URI without fragment";
    } else if (digital && message.contains("valid percent-encoding")) {
      code = "GS1_INVALID_PERCENT_ENCODING";
      reason = "invalid-percent-encoding";
      expected = "percent escapes must use two hexadecimal digits";
    } else if (digital
        && message.contains("query parameter")
        && message.contains("is not a GS1 AI")) {
      code = "GS1_DIGITAL_LINK_UNKNOWN_QUERY";
      reason = "unknown-query";
      expected = "GS1 AI query parameter or unknownQuery: \"preserve\"";
    } else if (digital
        && (message.contains("primaryAi must be one")
            || message.contains("unknownQuery must be"))) {
      reason = "invalid-options";
      expected = message.contains("primaryAi") ? "00, 01, or 414" : "preserve or reject";
    } else if (digital && (message.contains("path must") || message.contains("path segment"))) {
      reason = "malformed-path";
      expected = "Digital Link path containing primary AI and AI/value pairs";
    } else if (message.contains("Unsupported GS1 AI")) {
      code = "GS1_UNSUPPORTED_AI";
      reason = "unsupported-ai";
      expected = "supported GS1 AI";
    } else if (Pattern.compile("exactly \\d+ characters|at most \\d+ characters")
        .matcher(message)
        .find()) {
      code = "GS1_INVALID_LENGTH";
      reason = "invalid-length";
      expected = match("(exactly \\d+ characters|at most \\d+ characters)", message);
    } else if (message.contains("digits only") || message.contains("printable ASCII")) {
      code = "GS1_INVALID_CHARSET";
      reason = "invalid-charset";
      expected = message.contains("digits only") ? "digits only" : "printable ASCII";
    } else if (message.contains("missing an FNC1 separator")) {
      code = "GS1_MISSING_SEPARATOR";
      reason = "missing-separator";
      expected = "FNC1 separator before the next GS1 element";
    } else if (message.contains("unexpected FNC1 separator")
        || message.contains("must not end with an FNC1 separator")
        || message.contains("must not contain the FNC1 separator")) {
      code = "GS1_UNEXPECTED_SEPARATOR";
      reason = "unexpected-separator";
      expected = "separator only after a non-final variable-length GS1 element";
    } else if (message.contains("invalid GTIN check digit")
        || message.contains("invalid SSCC check digit")) {
      code = "GS1_INVALID_CHECK_DIGIT";
      reason = "invalid-check-digit";
      expected = message.contains("SSCC") ? "valid SSCC check digit" : "valid GTIN check digit";
    } else if (message.contains("cannot be placed in the Digital Link path")) {
      code = "GS1_INVALID_DIGITAL_LINK_PLACEMENT";
      reason = "invalid-digital-link-placement";
    } else if (message.contains("duplicate AI")) {
      code = "GS1_DUPLICATE_AI";
      reason = "duplicate-ai";
      expected = "unique GS1 AI within the Digital Link URI";
    }
    String ai = match("(?:GS1 AI |duplicate AI )([0-9]{2,4})", message);
    String offsetText = match("offset ([0-9]+)", message);
    Integer offset = offsetText == null ? null : Integer.valueOf(offsetText);
    if (ai == null && input != null && offset != null && offset <= input.length()) {
      ai = match("^([0-9]{2,4})", input.substring(offset));
      if (ai == null)
        for (int size = 2; size <= 4; size++)
          if (offset >= size && AIS.containsKey(input.substring(offset - size, offset))) {
            ai = input.substring(offset - size, offset);
            break;
          }
    }
    String explicitIndex = match("GS1 element ([0-9]+)", message);
    if (explicitIndex != null) index = Integer.valueOf(explicitIndex);
    Object rawValue =
        raw instanceof Element e ? e.value() : raw instanceof Map<?, ?> e ? e.get("value") : null;
    String value = rawValue instanceof String s ? s : null;
    String key =
        code.equals("GS1_DIGITAL_LINK_UNKNOWN_QUERY")
            ? match("(?s)query parameter \"(.*)\" is not a GS1 AI", message)
            : null;
    return new ValidationIssue(
        code, message, reason, ai, value, key, offset, index, expected, null);
  }

  private record Url(String scheme, String authority, String path, String query, String fragment) {
    String serialize() {
      return text(
          scheme
              + "://"
              + authority
              + path
              + (query == null ? "" : "?" + query)
              + (fragment == null ? "" : "#" + fragment),
          "GS1 Digital Link output");
    }
  }

  private static SpecQrException invalidUri() {
    return failure("GS1 Digital Link URI must be an absolute http or https URL");
  }

  private static String usv(String input) {
    StringBuilder out = new StringBuilder(input.length());
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (Character.isHighSurrogate(c)
          && i + 1 < input.length()
          && Character.isLowSurrogate(input.charAt(i + 1))) out.append(c).append(input.charAt(++i));
      else out.append(Character.isSurrogate(c) ? '\ufffd' : c);
    }
    return out.toString();
  }

  private static void escape(StringBuilder out, int value) {
    final String hex = "0123456789ABCDEF";
    out.append('%').append(hex.charAt(value >>> 4)).append(hex.charAt(value & 15));
  }

  private static String encode(String value, int mode) {
    StringBuilder out = new StringBuilder();
    for (byte b : usv(value).getBytes(StandardCharsets.UTF_8)) {
      int c = b & 255;
      boolean asciiLetterDigit =
          c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
      boolean keep =
          mode == 0
              ? asciiLetterDigit || "~!*'()-._".indexOf(c) >= 0
              : mode == 1
                  ? asciiLetterDigit || "*-._".indexOf(c) >= 0
                  : c > 32 && c < 127 && (mode == 2 ? "\"#<>?`{}^" : "\"#'<>").indexOf(c) < 0;
      if (keep) out.append((char) c);
      else if (mode == 1 && c == 32) out.append('+');
      else escape(out, c);
    }
    return out.toString();
  }

  private static int asciiHex(char value) {
    return value >= '0' && value <= '9'
        ? value - '0'
        : value >= 'a' && value <= 'f'
            ? value - 'a' + 10
            : value >= 'A' && value <= 'F' ? value - 'A' + 10 : -1;
  }

  private static byte[] percentBytes(String value, boolean form) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
    for (int i = 0; i < value.length(); ) {
      char c = value.charAt(i);
      if (c == '%'
          && i + 2 < value.length()
          && asciiHex(value.charAt(i + 1)) >= 0
          && asciiHex(value.charAt(i + 2)) >= 0) {
        out.write(asciiHex(value.charAt(i + 1)) * 16 + asciiHex(value.charAt(i + 2)));
        i += 3;
      } else if (form && c == '+') {
        out.write(' ');
        i++;
      } else {
        int point = value.codePointAt(i);
        String part = new String(Character.toChars(point));
        byte[] bytes = usv(part).getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        i += Character.charCount(point);
      }
    }
    return out.toByteArray();
  }

  private static String strictDecode(String value, String label) {
    if (INVALID_PERCENT.matcher(value).find())
      throw failure("GS1 Digital Link path " + label + " must be valid percent-encoding");
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(percentBytes(value, false)))
          .toString();
    } catch (CharacterCodingException error) {
      throw failure("GS1 Digital Link path " + label + " must be valid percent-encoding");
    }
  }

  // WHATWG's UTF-8 decoder rejects invalid scalar prefixes byte by byte.
  private static String formDecode(String value) {
    byte[] bytes = percentBytes(value, true);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < bytes.length; ) {
      int lead = bytes[i] & 255;
      if (lead < 128) {
        out.append((char) lead);
        i++;
        continue;
      }
      int need =
          lead >= 0xC2 && lead <= 0xDF
              ? 1
              : lead >= 0xE0 && lead <= 0xEF ? 2 : lead >= 0xF0 && lead <= 0xF4 ? 3 : 0;
      if (need == 0) {
        out.append('\ufffd');
        i++;
        continue;
      }
      int point = lead & (0x7F >> need);
      int used = 1;
      boolean bad = false;
      while (used <= need) {
        if (i + used == bytes.length) {
          bad = true;
          break;
        }
        int c = bytes[i + used] & 255;
        int low = used == 1 && lead == 0xE0 ? 0xA0 : used == 1 && lead == 0xF0 ? 0x90 : 0x80;
        int high = used == 1 && lead == 0xED ? 0x9F : used == 1 && lead == 0xF4 ? 0x8F : 0xBF;
        if (c < low || c > high) {
          bad = true;
          break;
        }
        point = (point << 6) | (c & 63);
        used++;
      }
      if (bad) out.append('\ufffd');
      else out.appendCodePoint(point);
      i += used;
    }
    return out.toString();
  }

  private static String credentials(String input) {
    StringBuilder out = new StringBuilder();
    boolean colon = false;
    for (byte b : usv(input).getBytes(StandardCharsets.UTF_8)) {
      int c = b & 255;
      if (c == ':' && !colon) {
        out.append(':');
        colon = true;
      } else if (c <= 32 || c > 126 || "\"#<>?`{}/:;=@[\\]^|".indexOf(c) >= 0) escape(out, c);
      else out.append((char) c);
    }
    if (!out.isEmpty() && out.charAt(out.length() - 1) == ':') out.setLength(out.length() - 1);
    return out.toString();
  }

  private static String host(String raw) {
    if (raw.isEmpty()) throw invalidUri();
    if (raw.charAt(0) == '[') {
      if (!raw.endsWith("]")) throw invalidUri();
      return ipv6(raw.substring(1, raw.length() - 1));
    }
    String value;
    try {
      value = strictDecode(raw, "host");
    } catch (SpecQrException e) {
      throw invalidUri();
    }
    value = value.replace('\u3002', '.').replace('\uff0e', '.').replace('\uff61', '.');
    String[] labels = value.split("\\.", -1);
    for (int i = 0; i < labels.length; i++) {
      boolean nonAscii = false;
      for (int j = 0; j < labels[i].length(); j++) nonAscii |= labels[i].charAt(j) > 127;
      if (nonAscii)
        try {
          labels[i] = IDN.toASCII(labels[i]);
        } catch (IllegalArgumentException error) {
          throw invalidUri();
        }
      else if (labels[i].regionMatches(true, 0, "xn--", 0, 4)) {
        // toASCII leaves ACE input unchecked. toUnicode validates its
        // round trip; ALLOW_UNASSIGNED admits newer encoded scalars.
        // IDNA2008 deviation labels still fail this IDNA2003 check.
        String decoded = IDN.toUnicode(labels[i], IDN.ALLOW_UNASSIGNED);
        if (decoded.equalsIgnoreCase(labels[i])) throw invalidUri();
      }
    }
    value = String.join(".", labels).toLowerCase(Locale.ROOT);
    if (value.isEmpty()) throw invalidUri();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c <= 32 || c == 127 || "#/:<>?@[\\]^|%".indexOf(c) >= 0) throw invalidUri();
    }
    String withoutDot = value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    String[] pieces = withoutDot.split("\\.", -1);
    String last = pieces[pieces.length - 1];
    if (!(digits(last) || last.matches("0x[0-9a-f]*"))) return value;
    if (pieces.length > 4) throw invalidUri();
    long address = 0;
    for (int i = 0; i < pieces.length; i++) {
      String piece = pieces[i];
      if (piece.isEmpty()) throw invalidUri();
      int radix =
          piece.startsWith("0x") ? 16 : piece.length() > 1 && piece.charAt(0) == '0' ? 8 : 10;
      String body = piece.substring(radix == 16 ? 2 : radix == 8 ? 1 : 0);
      long number = 0;
      for (int j = 0; j < body.length(); j++) {
        int digit = Character.digit(body.charAt(j), radix);
        if (digit < 0) throw invalidUri();
        number = number * radix + digit;
        if (number > 0xFFFFFFFFL) throw invalidUri();
      }
      if (i + 1 < pieces.length) {
        if (number > 255) throw invalidUri();
        address += number << (8 * (3 - i));
      } else {
        if (number >= (1L << (8 * (5 - pieces.length)))) throw invalidUri();
        address += number;
      }
    }
    return ((address >>> 24) & 255)
        + "."
        + ((address >>> 16) & 255)
        + "."
        + ((address >>> 8) & 255)
        + "."
        + (address & 255);
  }

  private static String ipv6(String value) {
    if (value.isEmpty() || value.indexOf('%') >= 0 || !value.matches("[0-9a-fA-F:.]+"))
      throw invalidUri();
    if (value.indexOf('.') >= 0) {
      int colon = value.lastIndexOf(':');
      if (colon < 0) throw invalidUri();
      String[] v4 = value.substring(colon + 1).split("\\.", -1);
      if (v4.length != 4) throw invalidUri();
      int[] b = new int[4];
      for (int i = 0; i < 4; i++) {
        if (!digits(v4[i]) || v4[i].length() > 3 || v4[i].length() > 1 && v4[i].charAt(0) == '0')
          throw invalidUri();
        b[i] = Integer.parseInt(v4[i]);
        if (b[i] > 255) throw invalidUri();
      }
      value =
          value.substring(0, colon + 1)
              + Integer.toHexString(b[0] * 256 + b[1])
              + ":"
              + Integer.toHexString(b[2] * 256 + b[3]);
    }
    int compression = value.indexOf("::");
    if (compression >= 0 && value.indexOf("::", compression + 2) >= 0) throw invalidUri();
    String left = compression < 0 ? value : value.substring(0, compression);
    String right = compression < 0 ? "" : value.substring(compression + 2);
    String[] first = left.isEmpty() ? new String[0] : left.split(":", -1);
    String[] last = right.isEmpty() ? new String[0] : right.split(":", -1);
    if (compression < 0 ? first.length != 8 : first.length + last.length >= 8) throw invalidUri();
    int[] groups = new int[8];
    for (int i = 0; i < first.length + last.length; i++) {
      String group = i < first.length ? first[i] : last[i - first.length];
      if (group.isEmpty() || group.length() > 4) throw invalidUri();
      groups[i < first.length ? i : 8 - last.length + i - first.length] =
          Integer.parseInt(group, 16);
    }
    int bestStart = -1, bestLength = 1;
    for (int i = 0; i < 8; ) {
      if (groups[i] != 0) {
        i++;
        continue;
      }
      int end = i;
      while (end < 8 && groups[end] == 0) end++;
      if (end - i > bestLength) {
        bestStart = i;
        bestLength = end - i;
      }
      i = end;
    }
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < 8; ) {
      if (i == bestStart) {
        out.append("::");
        i += bestLength;
      } else {
        if (i > 0 && i != bestStart + bestLength) out.append(':');
        out.append(Integer.toHexString(groups[i++]));
      }
    }
    return out.append(']').toString();
  }

  private static String normalizePath(String path, boolean base) {
    if (path.chars().filter(c -> c == '/').count() > MAX_ELEMENTS)
      throw failure("GS1 Digital Link path component count exceeds limit");
    String[] parts = path.split("/", -1);
    List<String> out = new ArrayList<>();
    boolean primarySeen = false;
    for (int i = 0; i < parts.length; i++) {
      String part = parts[i];
      if (!base && PRIMARY.contains(part)) primarySeen = true;
      String dot = part.replaceAll("(?i)%2e", ".");
      if (!primarySeen && (dot.equals(".") || dot.equals(".."))) {
        if (dot.equals("..") && out.size() > 1) out.remove(out.size() - 1);
        if (i + 1 == parts.length) out.add("");
      } else out.add(part);
    }
    String normalized = String.join("/", out);
    return encode(normalized.startsWith("/") ? normalized : "/" + normalized, 2);
  }

  private static Url url(String input, boolean base) {
    text(input, "GS1 Digital Link URI");
    int start = 0, end = input.length();
    while (start < end && input.charAt(start) <= 32) start++;
    while (end > start && input.charAt(end - 1) <= 32) end--;
    String clean =
        usv(input.substring(start, end)).replace("\t", "").replace("\r", "").replace("\n", "");
    Matcher match = SCHEME.matcher(clean);
    if (!match.find()) throw invalidUri();
    String scheme = match.group(1).toLowerCase(Locale.ROOT),
        rest = clean.substring(match.end()),
        fragment = null,
        query = null;
    int hash = rest.indexOf('#');
    if (hash >= 0) {
      fragment = rest.substring(hash + 1);
      rest = rest.substring(0, hash);
    }
    int question = rest.indexOf('?');
    if (question >= 0) {
      query = encode(rest.substring(question + 1), 3);
      rest = rest.substring(0, question);
    }
    if (!Set.of("http", "https", "ftp", "ws", "wss").contains(scheme))
      return new Url(scheme, "", rest, query, fragment);
    rest = rest.replace('\\', '/');
    int slashStart = 0;
    while (slashStart < rest.length() && rest.charAt(slashStart) == '/') slashStart++;
    rest = rest.substring(slashStart);
    int slash = rest.indexOf('/');
    String authority = slash < 0 ? rest : rest.substring(0, slash),
        path = slash < 0 ? "/" : rest.substring(slash);
    if (authority.isEmpty()) throw invalidUri();
    String user = "";
    int at = authority.lastIndexOf('@');
    if (at >= 0) {
      user = credentials(authority.substring(0, at));
      if (!user.isEmpty()) user += "@";
      authority = authority.substring(at + 1);
    }
    String host, port = "";
    if (authority.startsWith("[")) {
      int bracket = authority.indexOf(']');
      if (bracket < 0) throw invalidUri();
      host = authority.substring(0, bracket + 1);
      String suffix = authority.substring(bracket + 1);
      if (!suffix.isEmpty()) {
        if (!suffix.startsWith(":")) throw invalidUri();
        port = suffix.substring(1);
      }
    } else {
      int colon = authority.indexOf(':');
      host = colon < 0 ? authority : authority.substring(0, colon);
      if (colon >= 0) port = authority.substring(colon + 1);
    }
    if (!port.isEmpty()) {
      if (!digits(port)) throw invalidUri();
      int number = 0;
      for (int i = 0; i < port.length(); i++) {
        number = number * 10 + port.charAt(i) - '0';
        if (number > 65535) throw invalidUri();
      }
      boolean standard =
          (scheme.equals("http") || scheme.equals("ws")) && number == 80
              || (scheme.equals("https") || scheme.equals("wss")) && number == 443
              || scheme.equals("ftp") && number == 21;
      port = standard ? "" : ":" + number;
    }
    return new Url(scheme, user + host(host) + port, normalizePath(path, base), query, fragment);
  }

  private static void checkUri(Url url, boolean base) {
    if (!url.scheme().equals("http") && !url.scheme().equals("https"))
      throw failure("GS1 Digital Link URI must use http or https");
    if (base) {
      if (url.query() != null && !url.query().isEmpty()
          || url.fragment() != null && !url.fragment().isEmpty())
        throw failure("GS1 Digital Link baseUrl must not include query or fragment components");
    } else if (url.fragment() != null && !url.fragment().isEmpty())
      throw failure("GS1 Digital Link URI must not include a fragment");
  }

  private static String primary(String ai) {
    if (!PRIMARY.contains(ai))
      throw failure("GS1 Digital Link primaryAi must be one of 00, 01, or 414");
    return ai;
  }

  private static String policy(String value) {
    if (value == null) return "preserve";
    if (!value.equals("preserve") && !value.equals("reject"))
      throw failure("GS1 Digital Link unknownQuery must be \"preserve\" or \"reject\"");
    return value;
  }

  private static boolean eligible(String ai, String primary) {
    return primary.equals("01") && Set.of("10", "21", "22").contains(ai);
  }

  private static void placement(String ai, String primary) {
    if (!AIS.containsKey(ai))
      throw failure("Unsupported GS1 AI " + ai + ". Add explicit support before using it.");
    if (!eligible(ai, primary))
      throw failure(
          "GS1 AI "
              + ai
              + " cannot be placed in the Digital Link path after primary AI "
              + primary);
  }

  private static void unique(String ai, Set<String> seen) {
    if (!seen.add(ai)) throw failure("GS1 Digital Link input must not contain duplicate AI " + ai);
  }

  public static String createDigitalLink(Iterable<?> elements, String baseUrl) {
    return createDigitalLink(elements, DigitalLinkOptions.forBaseUrl(baseUrl));
  }

  public static String createDigitalLink(ElementStringParseResult result, String baseUrl) {
    if (result == null) throw failure("GS1 elements must not be null");
    return createDigitalLink(result.elements(), baseUrl);
  }

  public static String createDigitalLink(Iterable<?> elements, DigitalLinkOptions options) {
    if (options == null) options = DigitalLinkOptions.defaults();
    List<Object> values = bounded(elements, "GS1 elements");
    if (options.baseUrl() == null || options.baseUrl().isEmpty())
      throw failure("GS1 Digital Link baseUrl is required");
    Url url = url(options.baseUrl(), true);
    checkUri(url, true);
    String primaryAi = primary(options.primaryAi() == null ? "01" : options.primaryAi());
    Set<String> paths = null;
    if (options.pathAis() != null) {
      paths = new HashSet<>();
      for (Object raw : bounded(options.pathAis(), "GS1 Digital Link pathAis")) {
        if (!(raw instanceof String ai) || !isAi(ai))
          throw failure("GS1 Digital Link pathAis entries must be 2 to 4 digit AI strings");
        if (!ai.equals(primaryAi)) {
          placement(ai, primaryAi);
          paths.add(ai);
        }
      }
    }
    if (values.isEmpty()) throw failure("GS1 Digital Link input elements must not be empty");
    List<Element> normalized = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Element primary = null;
    for (int i = 0; i < values.size(); i++) {
      Element e = element(values.get(i), i);
      unique(e.ai(), seen);
      normalized.add(e);
      if (e.ai().equals(primaryAi)) primary = e;
    }
    if (primary == null)
      throw failure("GS1 Digital Link input must include primary AI " + primaryAi);
    List<Element> path = new ArrayList<>(List.of(primary)), query = new ArrayList<>();
    for (Element e : normalized) {
      if (e == primary) continue;
      boolean inPath = paths == null ? eligible(e.ai(), primaryAi) : paths.contains(e.ai());
      (inPath && !e.value().equals(".") && !e.value().equals("..") ? path : query).add(e);
    }
    StringBuilder pathname = new StringBuilder(url.path().replaceFirst("/+$", ""));
    for (Element e : path)
      pathname.append('/').append(encode(e.ai(), 0)).append('/').append(encode(e.value(), 0));
    query.sort(Comparator.comparing(Element::ai).thenComparing(Element::value));
    StringBuilder search = new StringBuilder();
    for (Element e : query) {
      if (!search.isEmpty()) search.append('&');
      search.append(encode(e.ai(), 1)).append('=').append(encode(e.value(), 1));
    }
    return new Url(
            url.scheme(),
            url.authority(),
            pathname.toString(),
            search.isEmpty() ? null : search.toString(),
            url.fragment())
        .serialize();
  }

  private static List<String> pathParts(String path) {
    String value = path.replaceFirst("^/+", "").replaceFirst("/+$", "");
    if (value.isEmpty())
      throw failure("GS1 Digital Link path must include primary AI 00, 01, or 414");
    List<String> parts = List.of(value.split("/", -1));
    if (parts.contains("")) throw failure("GS1 Digital Link path must not contain empty segments");
    return parts;
  }

  private static int firstAi(List<String> parts, String selected) {
    for (int i = 0; i < parts.size(); i++)
      if (selected == null ? PRIMARY.contains(parts.get(i)) : selected.equals(parts.get(i)))
        return i;
    throw failure("GS1 Digital Link path must include primary AI 00, 01, or 414");
  }

  private static DigitalLinkParseResult parseLink(Url url, DigitalLinkOptions options) {
    checkUri(url, false);
    String primaryAi = options.primaryAi();
    if (primaryAi != null) primary(primaryAi);
    String unknownPolicy = policy(options.unknownQuery());
    List<String> parts = pathParts(url.path());
    int start = firstAi(parts, primaryAi);
    if ((parts.size() - start) % 2 != 0)
      throw failure("GS1 Digital Link path must contain AI/value pairs");
    List<Element> path = new ArrayList<>(), query = new ArrayList<>();
    List<UnknownQuery> unknown = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = start; i < parts.size(); i += 2) {
      String ai = parts.get(i);
      if (!isAi(ai))
        throw failure("GS1 Digital Link path segment " + (i + 1) + " must be a GS1 AI");
      Element e =
          element(
              new Element(ai, strictDecode(parts.get(i + 1), "value for AI " + ai)), path.size());
      if (!path.isEmpty()) placement(ai, path.get(0).ai());
      unique(ai, seen);
      path.add(e);
    }
    if (url.query() != null && !url.query().isEmpty()) {
      String raw = url.query();
      if (raw.chars().filter(c -> c == '&').count() + 1 > MAX_ELEMENTS)
        throw failure("GS1 Digital Link query component count exceeds limit");
      for (String pair : raw.split("&", -1)) {
        if (pair.isEmpty()) continue;
        int equal = pair.indexOf('=');
        String key = formDecode(equal < 0 ? pair : pair.substring(0, equal)),
            value = formDecode(equal < 0 ? "" : pair.substring(equal + 1));
        if (isAi(key)) {
          Element e = element(new Element(key, value), path.size() + query.size());
          unique(key, seen);
          query.add(e);
        } else if (unknownPolicy.equals("preserve")) unknown.add(new UnknownQuery(key, value));
        else throw failure("GS1 Digital Link query parameter \"" + key + "\" is not a GS1 AI");
      }
    }
    List<Element> elements = new ArrayList<>(path);
    elements.addAll(query);
    return new DigitalLinkParseResult(elements, path.get(0), path, query, unknown);
  }

  public static DigitalLinkParseResult parseDigitalLink(String uri) {
    return parseDigitalLink(uri, DigitalLinkOptions.defaults());
  }

  public static DigitalLinkParseResult parseDigitalLink(String uri, DigitalLinkOptions options) {
    return parseLink(url(uri, false), options == null ? DigitalLinkOptions.defaults() : options);
  }

  public static DigitalLinkValidationResult validateDigitalLink(String uri) {
    return validateDigitalLink(uri, DigitalLinkOptions.defaults());
  }

  public static DigitalLinkValidationResult validateDigitalLink(
      String uri, DigitalLinkOptions options) {
    if (options == null) options = DigitalLinkOptions.defaults();
    if (options.normalize())
      return new DigitalLinkValidationResult(
          false,
          null,
          List.of(
              simpleIssue(
                  "GS1_INVALID_INPUT",
                  "GS1 Digital Link validation normalize is not implemented yet",
                  "unsupported-option",
                  false)),
          List.of());
    try {
      Url url = url(uri, false);
      if (INVALID_PERCENT.matcher(url.path()).find()
          || url.query() != null && INVALID_PERCENT.matcher(url.query()).find())
        throw failure("GS1 Digital Link URI must use valid percent-encoding");
      DigitalLinkParseResult result = parseLink(url, options);
      List<ValidationIssue> warnings = new ArrayList<>();
      if (url.scheme().equals("http"))
        warnings.add(
            simpleIssue(
                "GS1_DIGITAL_LINK_HTTP",
                "GS1 Digital Link URI uses http. Use https when transport security is required.",
                "http-uri",
                null));
      if (!result.unknownQuery().isEmpty())
        warnings.add(
            new ValidationIssue(
                "GS1_DIGITAL_LINK_UNKNOWN_QUERY_PRESERVED",
                "GS1 Digital Link URI contains non-GS1 query parameters preserved in unknownQuery.",
                "unknown-query-preserved",
                null,
                null,
                null,
                null,
                null,
                null,
                result.unknownQuery().size()));
      return new DigitalLinkValidationResult(true, result, List.of(), warnings);
    } catch (SpecQrException error) {
      return new DigitalLinkValidationResult(
          false, null, List.of(issue(error, null, null, null, true)), List.of());
    }
  }

  public static String normalizeDigitalLink(String uri) {
    return normalizeDigitalLink(uri, DigitalLinkOptions.defaults());
  }

  public static String normalizeDigitalLink(String uri, DigitalLinkOptions options) {
    if (options == null) options = DigitalLinkOptions.defaults();
    if (options.mode() != null && !options.mode().equals("specqr-deterministic"))
      throw failure("GS1 Digital Link normalization mode must be \"specqr-deterministic\"");
    Url url = url(uri, false);
    checkUri(url, false);
    if (INVALID_PERCENT.matcher(url.path()).find()
        || url.query() != null && INVALID_PERCENT.matcher(url.query()).find())
      throw failure("GS1 Digital Link URI must use valid percent-encoding");
    DigitalLinkParseResult parsed = parseLink(url, options);
    List<String> parts = pathParts(url.path());
    int start = firstAi(parts, options.primaryAi());
    String stem =
        new Url(
                url.scheme(),
                url.authority(),
                "/" + String.join("/", parts.subList(0, start)),
                null,
                null)
            .serialize();
    String normalized =
        createDigitalLink(
            parsed.elements(),
            DigitalLinkOptions.forBaseUrl(stem).withPrimaryAi(parsed.primary().ai()));
    StringBuilder out = new StringBuilder(normalized);
    boolean hasQuery = normalized.indexOf('?') >= 0;
    for (UnknownQuery q : parsed.unknownQuery()) {
      out.append(hasQuery ? '&' : '?');
      hasQuery = true;
      out.append(encode(q.key(), 1)).append('=').append(encode(q.value(), 1));
    }
    return text(out.toString(), "GS1 Digital Link output");
  }
}
