package io.specqr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Catalog, parser, validation and browser-compatible URL regressions. */
public final class Gs1Tests {
  private int assertions;
  private static final String GTIN = "04912345678904";
  private static final String ROOT = "https://example.com/01/" + GTIN;

  private Gs1Tests() {}

  public static int run() {
    Gs1Tests tests = new Gs1Tests();
    tests.catalogAndDigits();
    tests.elements();
    tests.validation();
    tests.digitalLinks();
    tests.urlSemantics();
    tests.punctuationAndMalformedProperties();
    tests.boundsAndOwnership();
    return tests.assertions;
  }

  public static void main(String[] args) {
    System.out.println("Gs1Tests: " + run() + " assertions passed");
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

  private void catalogAndDigits() {
    equal(Gs1.getSupportedAis().size(), 50, "exact catalog size");
    equal(Gs1.getAiInfo("250"), null, "unsupported AI absent");
    equal(Gs1.getAiInfo(null), null, "null introspection");
    equal(Gs1.getAiInfo("10").digitalLinkPathForPrimary(), List.of("01"), "qualifier placement");
    equal(Gs1.getAiInfo("01").length().exact(), 14, "GTIN length");
    equal(Gs1.getAiInfo("99").length().max(), 90, "private data bound");
    equal(Gs1.calculateGtinCheckDigit("0491234567890"), "4", "GTIN digit");
    equal(Gs1.appendGtinCheckDigit("0491234567890"), GTIN, "GTIN append");
    check(Gs1.validateGtinCheckDigit(GTIN), "GTIN accepted");
    check(!Gs1.validateGtinCheckDigit("04912345678905"), "incorrect GTIN digit");
    for (int length : new int[] {7, 11, 12, 13})
      check(
          Gs1.validateGtinCheckDigit(Gs1.appendGtinCheckDigit("0".repeat(length))),
          "GTIN leading zero length " + length);
    String sscc = Gs1.appendSsccCheckDigit("12345678901234567");
    check(Gs1.validateSsccCheckDigit(sscc), "SSCC roundtrip");
    equal(sscc.length(), 18, "SSCC length");
    rejects("INVALID_GS1", () -> Gs1.calculateCheckDigit(""));
    rejects("INVALID_GS1", () -> Gs1.validateCheckDigit("1"));
    rejects("INVALID_GS1", () -> Gs1.calculateCheckDigit("１２"));
    rejects("INVALID_GS1", () -> Gs1.calculateGtinCheckDigit("123"));
    rejects("INVALID_GS1", () -> Gs1.validateSsccCheckDigit("123"));
    for (Gs1.AiInfo info : Gs1.getSupportedAis()) {
      String value =
          info.valueKind().equals("text")
              ? "A"
              : info.length().isVariable() ? "1" : "0".repeat(info.length().exact());
      Gs1.Element element = new Gs1.Element(info.ai(), value);
      String encoded = Gs1.toElementString(List.of(element));
      equal(
          Gs1.parseElementString(encoded).elements(),
          List.of(element),
          "catalog roundtrip " + info.ai());
      equal(
          Gs1.fromHumanReadable("(" + info.ai() + ")" + value),
          List.of(element),
          "human roundtrip " + info.ai());
      int limit = info.length().isVariable() ? info.length().max() : info.length().exact();
      for (String bad : List.of("", "0".repeat(limit + 1), "é", "A\u001dB", "A(B)"))
        check(
            !Gs1.validateElements(List.of(new Gs1.Element(info.ai(), bad))).ok(),
            "invalid catalog value " + info.ai());
    }
  }

  private void elements() {
    List<Gs1.Element> values =
        List.of(
            new Gs1.Element("01", GTIN),
            new Gs1.Element("10", "LOT-42"),
            new Gs1.Element("17", "271031"),
            new Gs1.Element("21", "SERIAL"));
    String raw = "01" + GTIN + "10LOT-42\u001d1727103121SERIAL";
    equal(Gs1.toElementString(values), raw, "FNC1 placement");
    equal(Gs1.parseElementString(raw).elements(), values, "raw roundtrip");
    check(Gs1.parseElementString(raw).hasSeparators(), "separator flag");
    equal(
        Gs1.toHumanReadable(raw),
        "(01)" + GTIN + "(10)LOT-42(17)271031(21)SERIAL",
        "human rendering");
    equal(Gs1.fromHumanReadable(Gs1.toHumanReadable(values)), values, "human roundtrip");
    rejects("INVALID_GS1", () -> Gs1.toElementString(List.of()));
    rejects("INVALID_GS1", () -> Gs1.parseElementString(""));
    rejects("INVALID_GS1", () -> Gs1.fromHumanReadable("01" + GTIN));
    rejects("INVALID_GS1", () -> Gs1.fromHumanReadable("(01"));
    rejects("INVALID_GS1", () -> Gs1.parseElementString("(01)" + GTIN));
    rejects("INVALID_GS1", () -> Gs1.parseElementString("\u001d01" + GTIN));
    rejects("INVALID_GS1", () -> Gs1.parseElementString("10ABC\u001d"));
    rejects("INVALID_GS1", () -> Gs1.parseElementString("17271031\u001d10ABC"));
    rejects("INVALID_GS1", () -> Gs1.parseElementString("10ABC17271031"));
    rejects("INVALID_GS1", () -> Gs1.parseElementString("250ABC"));
    rejects("INVALID_GS1", () -> Gs1.toElementString(List.of(new Gs1.Element("10", "A(B)"))));
    rejects("INVALID_GS1", () -> Gs1.toElementString(List.of(new Gs1.Element("10", "é"))));
    rejects("INVALID_GS1", () -> Gs1.toElementString(List.of(new Gs1.Element("10", "\u001d"))));
    equal(
        Gs1.toElementString(List.of(Map.of("ai", "17", "value", "271031"))),
        "17271031",
        "map element adapter");
  }

  private void validation() {
    Gs1.ValidationResult invalid =
        Gs1.validateElements(
            List.of(
                new Gs1.Element("17", "A"),
                new Gs1.Element("10", "A".repeat(21)),
                new Gs1.Element("250", "A")));
    check(!invalid.ok(), "invalid result");
    equal(invalid.errors().size(), 3, "collect errors");
    equal(invalid.errors().get(0).code(), "GS1_INVALID_CHARSET", "charset code");
    equal(invalid.errors().get(1).code(), "GS1_INVALID_LENGTH", "length code");
    equal(invalid.errors().get(2).code(), "GS1_UNSUPPORTED_AI", "unsupported code");
    equal(invalid.errors().get(1).elementIndex(), 1, "element index");
    equal(invalid.errors().get(1).ai(), "10", "AI issue metadata");
    equal(invalid.errors().get(1).expected(), "at most 20 characters", "expected metadata");
    equal(
        Gs1.validateElements(
                List.of(new Gs1.Element("17", "A"), new Gs1.Element("17", "B")),
                new Gs1.ValidationOptions("element-string", false, false))
            .errors()
            .size(),
        1,
        "first error option");
    equal(
        Gs1.validateElements(
                List.of(new Gs1.Element("10", "ABC")),
                new Gs1.ValidationOptions("digital-link", true, false))
            .errors()
            .get(0)
            .code(),
        "GS1_INVALID_DIGITAL_LINK_PLACEMENT",
        "primary required");
    check(
        Gs1.validateElementString("10ABC", new Gs1.ValidationOptions("digital-link", true, false))
            .ok(),
        "element-string validator context parity");
    check(
        !Gs1.validateElements(List.of(), new Gs1.ValidationOptions("bad", true, false)).ok(),
        "invalid context");
    check(
        !Gs1.validateElements(List.of(), new Gs1.ValidationOptions("element-string", true, true))
            .ok(),
        "unsupported opt-in rejected");
    equal(
        Gs1.validateElementString("10ABC17271031").errors().get(0).code(),
        "GS1_MISSING_SEPARATOR",
        "missing separator code");
    equal(Gs1.validateElementString("\u001d10ABC").errors().get(0).offset(), 0, "separator offset");
    equal(
        Gs1.validateElementString("0104912345678905").errors().get(0).code(),
        "GS1_INVALID_CHECK_DIGIT",
        "check digit issue");
    check(Gs1.validateElementString(null).errors().size() == 1, "null validation does not throw");
    equal(
        Gs1.validateElements(List.of(new Gs1.Element("91", "A".repeat(91))))
            .errors()
            .get(0)
            .value(),
        "A".repeat(91),
        "bounded invalid value retained");
  }

  private void digitalLinks() {
    List<Gs1.Element> values =
        List.of(
            new Gs1.Element("17", "271031"),
            new Gs1.Element("01", GTIN),
            new Gs1.Element("10", "A/B ?#%"),
            new Gs1.Element("21", "S 1"));
    String link = Gs1.createDigitalLink(values, "https://example.com/stem/");
    equal(
        link,
        "https://example.com/stem/01/" + GTIN + "/10/A%2FB%20%3F%23%25/21/S%201?17=271031",
        "deterministic creation");
    Gs1.DigitalLinkParseResult parsed = Gs1.parseDigitalLink(link + "&x=one+two&x=three");
    equal(parsed.primary(), new Gs1.Element("01", GTIN), "primary");
    equal(parsed.pathElements().size(), 3, "path elements");
    equal(parsed.queryElements().size(), 1, "query elements");
    equal(
        parsed.unknownQuery(),
        List.of(new Gs1.UnknownQuery("x", "one two"), new Gs1.UnknownQuery("x", "three")),
        "unknown ordering");
    String queryOnly =
        Gs1.createDigitalLink(
            values,
            Gs1.DigitalLinkOptions.forBaseUrl("https://example.com").withPathAis(List.of()));
    equal(queryOnly, ROOT + "?10=A%2FB+%3F%23%25&17=271031&21=S+1", "force qualifiers to query");
    equal(
        Gs1.normalizeDigitalLink(queryOnly), link.replace("/stem", ""), "normalization placement");
    equal(Gs1.normalizeDigitalLink(Gs1.normalizeDigitalLink(link)), link, "idempotent");
    check(
        Gs1.validateDigitalLink("http://example.com/01/" + GTIN + "?x=1").warnings().size() == 2,
        "HTTP and unknown warnings");
    equal(
        Gs1.validateDigitalLink(
                ROOT + "?x=1", Gs1.DigitalLinkOptions.defaults().withUnknownQuery("reject"))
            .errors()
            .get(0)
            .code(),
        "GS1_DIGITAL_LINK_UNKNOWN_QUERY",
        "unknown reject");
    equal(
        Gs1.validateDigitalLink(
                ROOT + "?line%0Akey=1",
                Gs1.DigitalLinkOptions.defaults().withUnknownQuery("reject"))
            .errors()
            .get(0)
            .key(),
        "line\nkey",
        "unknown query key metadata");
    equal(
        Gs1.validateDigitalLink(ROOT, Gs1.DigitalLinkOptions.defaults().withPrimaryAi("10"))
            .errors()
            .get(0)
            .expected(),
        "00, 01, or 414",
        "primary option expectation");
    equal(
        Gs1.validateDigitalLink(ROOT, Gs1.DigitalLinkOptions.defaults().withUnknownQuery("invalid"))
            .errors()
            .get(0)
            .expected(),
        "preserve or reject",
        "query policy expectation");
    equal(
        Gs1.validateDigitalLink(ROOT + "?17=271031&17=271031").errors().get(0).code(),
        "GS1_DUPLICATE_AI",
        "duplicate query AI");
    equal(
        Gs1.validateDigitalLink(ROOT + "/17/271031").errors().get(0).code(),
        "GS1_INVALID_DIGITAL_LINK_PLACEMENT",
        "invalid path AI");
    equal(
        Gs1.validateDigitalLink(ROOT + "#fragment").errors().get(0).code(),
        "GS1_DIGITAL_LINK_FRAGMENT_NOT_ALLOWED",
        "fragment prohibited");
    equal(
        Gs1.validateDigitalLink("ftp://example.com/01/" + GTIN).errors().get(0).code(),
        "GS1_DIGITAL_LINK_INVALID_URI",
        "scheme prohibited");
    check(Gs1.parseDigitalLink(ROOT + "#").elements().size() == 1, "empty fragment accepted");
    rejects("INVALID_GS1", () -> Gs1.createDigitalLink(values, "https://example.com?x=1"));
    rejects(
        "INVALID_GS1",
        () ->
            Gs1.createDigitalLink(
                values,
                Gs1.DigitalLinkOptions.forBaseUrl("https://example.com")
                    .withPathAis(List.of("17"))));
    for (String dot : List.of(".", "..")) {
      equal(
          Gs1.parseDigitalLink(ROOT + "/10/" + dot).pathElements().get(1).value(),
          dot,
          "literal dot preserved");
      equal(
          Gs1.parseDigitalLink(ROOT + "/10/" + dot.replace(".", "%2e"))
              .pathElements()
              .get(1)
              .value(),
          dot,
          "escaped dot preserved");
      equal(
          Gs1.normalizeDigitalLink(ROOT + "/10/" + dot),
          ROOT + "?10=" + dot,
          "dot normalized safely");
      equal(
          Gs1.createDigitalLink(
              List.of(new Gs1.Element("01", GTIN), new Gs1.Element("10", dot)),
              "https://example.com"),
          ROOT + "?10=" + dot,
          "dot create safely");
    }
    String gln = "1234567890123";
    equal(
        Gs1.createDigitalLink(
            List.of(new Gs1.Element("414", gln), new Gs1.Element("10", "ABC")),
            Gs1.DigitalLinkOptions.forBaseUrl("https://example.com").withPrimaryAi("414")),
        "https://example.com/414/" + gln + "?10=ABC",
        "non-GTIN primary");
  }

  private void urlSemantics() {
    // Golden ordinary cases audited against Node URL / pinned JS implementation.
    String suffix = "/01/" + GTIN;
    String[][] cases = {
      {"HTTPS://EXAMPLE.COM:443/stem", "https://example.com/stem"},
      {"https:example.com", "https://example.com"},
      {"https:\\example.com", "https://example.com"},
      {"http://0x7f000001", "http://127.0.0.1"},
      {"http://0177.1", "http://127.0.0.1"},
      {"https://user:p:a@example.com", "https://user:p%3Aa@example.com"},
      {"https://[::ffff:192.0.2.128]", "https://[::ffff:c000:280]"},
      {"https://[2001:0db8:0:0:0:0:0:1]", "https://[2001:db8::1]"},
      {"https://例え.テスト", "https://xn--r8jz45g.xn--zckzah"},
      {"https://foo_bar.test", "https://foo_bar.test"},
      {"https://foo..test", "https://foo..test"},
      {"https://a%2eb", "https://a.b"},
      {"https://x.test/a/../b", "https://x.test/b"},
      {"https://x.test:000443", "https://x.test"},
      {"https://xn--bcher-kva.de", "https://xn--bcher-kva.de"},
      {"https://xn--e28h.test", "https://xn--e28h.test"},
      {"https://x.test/a^b", "https://x.test/a%5Eb"}
    };
    for (String[] c : cases)
      equal(Gs1.normalizeDigitalLink(c[0] + suffix), c[1] + suffix, "WHATWG URL " + c[0]);
    for (String base :
        List.of(
            "https://09",
            "https://1.2.3.256",
            "https://x:65536",
            "https://x:abc",
            "https://[::%25zone]",
            "https://[1:2]",
            "https://[:::]",
            "https://a b",
            "https://%ff",
            "https://xn--a",
            "https://xn--",
            "https://xn--abc-",
            "https://xn--a-ecp.test"))
      rejects("INVALID_GS1", () -> Gs1.parseDigitalLink(base + suffix));
    rejects(
        "INVALID_GS1",
        () ->
            Gs1.parseDigitalLink(
                "https://xn--fa-hia.de" + suffix)); // IDNA2003 deviation-label boundary.
    equal(
        Gs1.normalizeDigitalLink("https://faß.de" + suffix),
        "https://fass.de" + suffix,
        "documented IDNA2003 mapping");
    String[][] queries = {
      {"%ED%A0%80", "���"},
      {"%E2%82", "�"},
      {"%E0%80%80", "���"},
      {"%F0%9F%98%80", "😀"},
      {"%zz", "%zz"},
      {"%FF", "�"},
      {"a%00b", "a\u0000b"},
      {"%E2%82%41", "�A"},
      {"%ＦＦ", "%ＦＦ"},
      {"%４１", "%４１"},
      {"%١١", "%١١"}
    };
    for (String[] c : queries)
      equal(
          Gs1.parseDigitalLink(ROOT + "?x=" + c[0]).unknownQuery().get(0).value(),
          c[1],
          "forgiving query UTF-8 " + c[0]);
    check(
        !Gs1.validateDigitalLink(ROOT + "?x=%zz").ok(),
        "validation rejects malformed query escapes");
    rejects("INVALID_GS1", () -> Gs1.normalizeDigitalLink(ROOT + "?x=%zz"));
    rejects("INVALID_GS1", () -> Gs1.parseDigitalLink(ROOT + "/10/%ED%A0%80"));
    rejects("INVALID_GS1", () -> Gs1.parseDigitalLink(ROOT + "/10/%zz"));
    rejects("INVALID_GS1", () -> Gs1.parseDigitalLink("https://example.com/%30%31/" + GTIN));
    rejects("INVALID_GS1", () -> Gs1.parseDigitalLink(ROOT + "//10/ABC"));
  }

  private void punctuationAndMalformedProperties() {
    for (int code = 32; code <= 126; code++) {
      if (code == '(' || code == ')') continue;
      String value = "A" + (char) code + "B";
      List<Gs1.Element> elements =
          List.of(new Gs1.Element("01", GTIN), new Gs1.Element("10", value));
      for (boolean query : new boolean[] {false, true}) {
        Gs1.DigitalLinkOptions options = Gs1.DigitalLinkOptions.forBaseUrl("https://example.com");
        if (query) options = options.withPathAis(List.of());
        String uri = Gs1.createDigitalLink(elements, options);
        equal(
            Gs1.parseDigitalLink(uri).elements(),
            elements,
            "printable punctuation roundtrip " + code);
        check(Gs1.validateDigitalLink(uri).ok(), "printable punctuation validates " + code);
      }
    }
    java.util.Random random = new java.util.Random(701);
    String alphabet =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            + " -_~/+%?#&=.:;@[]{}\\\u0000\u001f\u007f";
    String[] positions = {"/10/", "?10=", "?x=", "/10/A?x="};
    for (int sample = 0; sample < 2500; sample++) {
      StringBuilder suffix = new StringBuilder();
      int length = 1 + random.nextInt(27);
      for (int i = 0; i < length; i++)
        suffix.append(alphabet.charAt(random.nextInt(alphabet.length())));
      String uri = ROOT + positions[random.nextInt(positions.length)] + suffix;
      Gs1.DigitalLinkValidationResult validation = Gs1.validateDigitalLink(uri);
      check(
          validation.ok() || !validation.errors().isEmpty(),
          "bounded malformed URI has structured result");
      if (!validation.ok()) continue;
      Gs1.DigitalLinkParseResult before = Gs1.parseDigitalLink(uri);
      String normalized = Gs1.normalizeDigitalLink(uri);
      Gs1.DigitalLinkParseResult after = Gs1.parseDigitalLink(normalized);
      Map<String, String> left = new LinkedHashMap<>(), right = new LinkedHashMap<>();
      for (Gs1.Element e : before.elements()) left.put(e.ai(), e.value());
      for (Gs1.Element e : after.elements()) right.put(e.ai(), e.value());
      equal(right, left, "normalization preserves logical AI data");
      equal(
          after.unknownQuery(),
          before.unknownQuery(),
          "normalization preserves unknown query order");
      equal(Gs1.normalizeDigitalLink(normalized), normalized, "fuzz normalization idempotence");
    }
  }

  @SuppressWarnings("unchecked")
  private void boundsAndOwnership() {
    rejects("INVALID_GS1", () -> Gs1.parseElementString("A".repeat(Gs1.MAX_INPUT_CHARACTERS + 1)));
    rejects("INVALID_GS1", () -> Gs1.validateCheckDigit("1".repeat(Gs1.MAX_INPUT_CHARACTERS + 1)));
    Iterable<Gs1.Element> infinite =
        () ->
            new Iterator<>() {
              @Override
              public boolean hasNext() {
                return true;
              }

              @Override
              public Gs1.Element next() {
                return new Gs1.Element("17", "271031");
              }
            };
    rejects("INVALID_GS1", () -> Gs1.toElementString(infinite));
    rejects(
        "INVALID_GS1",
        () ->
            Gs1.toElementString(
                Collections.nCopies(11_000, new Gs1.Element("91", "A".repeat(90)))));
    rejects(
        "INVALID_GS1", () -> Gs1.parseDigitalLink(ROOT + "?" + "x=1&".repeat(Gs1.MAX_ELEMENTS)));
    immutable(() -> Gs1.getSupportedAis().clear());
    immutable(() -> Gs1.getAiInfo("10").digitalLinkPathForPrimary().clear());
    immutable(() -> Gs1.parseElementString("17271031").elements().clear());
    immutable(() -> Gs1.parseDigitalLink(ROOT + "?x=1").unknownQuery().clear());
    List<String> expected = new ArrayList<>(List.of("a"));
    Gs1.ValidationIssue issue =
        new Gs1.ValidationIssue(
            "code", "message", null, null, null, null, null, null, expected, null);
    expected.clear();
    equal(issue.expected(), List.of("a"), "issue owns nested collection");
    immutable(() -> ((List<Object>) issue.expected()).clear());
    List<String> path = new ArrayList<>(List.of("10"));
    Gs1.DigitalLinkOptions options =
        Gs1.DigitalLinkOptions.forBaseUrl("https://example.com").withPathAis(path);
    path.clear();
    equal(options.pathAis(), List.of("10"), "option owns list");
  }
}
