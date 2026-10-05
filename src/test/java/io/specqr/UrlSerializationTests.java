package io.specqr;

import java.util.List;

/** Independently fixed TypeScript URL serialization outcomes. */
public final class UrlSerializationTests {
  private UrlSerializationTests() {}
  public static int run() {
    var elements = List.of(new Gs1.Element("01", "04912345678904"),
        new Gs1.Element("10", "ABC123"), new Gs1.Element("17", "251231"));
    String root = "https://example.com/01/04912345678904";
    String[] expected = {root + "/10/ABC123?17=251231#", root + "?10=ABC123&17=251231#",
        root + "?10=ABC123&17=251231#", root + "/10/ABC123?17=251231#"};
    int checks = 0;
    for (int i = 0; i < 4; i++) {
      List<String> paths = switch (i) { case 0 -> null; case 1 -> List.of();
        case 2 -> List.of("21"); default -> List.of("01", "10"); };
      var options = new Gs1.DigitalLinkOptions("https://example.com#", null, paths,
          "preserve", false, "specqr-deterministic");
      String actual = Gs1.createDigitalLink(elements, options);
      if (!actual.equals(expected[i])) throw new AssertionError("empty fragment: " + actual);
      checks++;
      if (!Gs1.normalizeDigitalLink(actual).equals(root + "/10/ABC123?17=251231"))
        throw new AssertionError("normalization must clear fragment");
      checks++;
    }
    if (!Gs1.normalizeDigitalLink(root + "?x=%00").equals(root + "?x=%00"))
      throw new AssertionError("decoded query NUL must remain supported");
    return checks + 1;
  }
}
