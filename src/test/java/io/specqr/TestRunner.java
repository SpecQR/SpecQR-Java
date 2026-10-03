package io.specqr;

/** Framework-free test entrypoint. Counts assertions, not invented test cases. */
public final class TestRunner {
  private TestRunner() {}

  public static void main(String[] args) throws Exception {
    int total = 0, suites = 0;
    for (String name :
        new String[] {
          "CoreTests",
          "SegmentTests",
          "ApiTests",
          "RenderTests",
          "CliTests",
          "Gs1Tests",
          "StructuredAppendTests"
        }) {
      Object n = Class.forName("io.specqr." + name).getMethod("run").invoke(null);
      if (!(n instanceof Integer))
        throw new AssertionError("Suite must return assertion count: " + name);
      total += (Integer) n;
      suites++;
      System.out.println(name + ": PASS (" + n + " assertions)");
    }
    System.out.println(
        "PASS: " + suites + " suites, " + total + " assertions; 0 failed, 0 skipped");
  }
}
