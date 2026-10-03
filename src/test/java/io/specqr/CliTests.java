package io.specqr;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class CliTests {
  private CliTests() {}

  private static int assertions;

  private static void check(boolean b, String m) {
    assertions++;
    if (!b) throw new AssertionError(m);
  }

  private static void bad(Runnable r) {
    assertions++;
    try {
      r.run();
      throw new AssertionError("Expected malformed rejection");
    } catch (SpecQrException expected) {
    }
  }

  private record Call(int exit, byte[] out, String err) {}

  private static Call call(String... args) throws Exception {
    PrintStream out = System.out, err = System.err;
    ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
    try (var op = new PrintStream(o, true, StandardCharsets.UTF_8);
        var ep = new PrintStream(e, true, StandardCharsets.UTF_8)) {
      System.setOut(op);
      System.setErr(ep);
      return new Call(Main.run(args), o.toByteArray(), e.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(out);
      System.setErr(err);
    }
  }

  public static int run() throws Exception {
    assertions = 0;
    Object value =
        Json.parse(
            "{\"text\":\"\\u6f22\\ud83d\\ude00\",\"numbers\":[0,-1,1.2,1e3],\"yes\":true,\"no\":false,\"nil\":null}");
    check(Json.parse(Json.write(value)).equals(value), "JSON roundtrip");
    for (String text :
        List.of(
            "\"\\u００４１\"",
            "\"\\uFFＦＦ\"",
            "01",
            "-01",
            "1.",
            "1e",
            "+1",
            "1e999",
            "[1,]",
            "{\"a\":1,\"a\":2}",
            "\"\\q\"",
            "\"\n\"",
            "truefalse",
            "[",
            "{",
            "",
            "\u00a0null")) bad(() -> Json.parse(text));
    bad(() -> Json.parse("[".repeat(66) + "]".repeat(66)));
    bad(() -> Json.parse(" ".repeat(4_000_001)));
    check(
        Main.parseSegments(Json.parse("[{\"mode\":\"byte\",\"bytes\":[0,255]}]")).get(0).binary()[1]
            == (byte) 255,
        "manual binary JSON");
    for (String text :
        List.of(
            "{}",
            "[{\"mode\":\"byte\",\"bytes\":[256]}]",
            "[{\"mode\":\"byte\",\"text\":\"a\",\"bytes\":[1]}]",
            "[{\"mode\":\"eci\",\"assignmentNumber\":26.0}]",
            "[{\"mode\":\"byte\",\"extra\":1,\"text\":\"a\"}]"))
      bad(() -> Main.parseSegments(Json.parse(text)));
    Call help = call("--help");
    check(
        help.exit == 0 && new String(help.out, StandardCharsets.UTF_8).contains("Usage:"), "help");
    Call matrix = call("--format", "matrix", "--diagnostics", "Hello");
    check(
        matrix.exit == 0
            && Json.parse(new String(matrix.out, StandardCharsets.UTF_8)) instanceof List<?>,
        "matrix output");
    check(Json.parse(matrix.err) instanceof Map<?, ?>, "diagnostic stderr");
    Call png = call("--format", "png", "Hello");
    check(png.exit == 0 && png.out[0] == (byte) 137, "binary stdout");
    check(
        call("--estimate", "--version", "1", "x".repeat(500)).exit == 0,
        "overflow estimate succeeds");
    check(call("--version", "1", "x".repeat(500)).exit == 2, "overflow generation fails");
    for (String[] args :
        new String[][] {
          {},
          {"--wat"},
          {"--scale"},
          {"--scale", "no", "x"},
          {"--dpi", "NaN", "x"},
          {"one", "two"},
          {"--format", "wat", "x"},
          {"--margin", "2147483647", "--scale", "2147483647", "x"}
        }) check(call(args).exit == 2, "CLI rejects malformed");
    check(call("--", "--literal").exit == 0, "dash input");
    Path dir = Files.createTempDirectory("specqr-cli-");
    try {
      Path binary = dir.resolve("input.bin"),
          manual = dir.resolve("segments.json"),
          out = dir.resolve("out.svg");
      Files.write(binary, new byte[] {0, (byte) 255});
      check(
          call("--binary", binary.toString(), "--output", out.toString()).exit == 0
              && Files.readString(out).startsWith("<svg"),
          "binary input/file output");
      Files.writeString(manual, "[{\"mode\":\"numeric\",\"text\":\"12345\"}]");
      check(call("--segments", manual.toString()).exit == 0, "manual segments CLI");
      check(call("text", "--binary", binary.toString()).exit == 2, "ambiguous source");
      Files.write(manual, new byte[] {(byte) 0xff});
      check(call("--segments", manual.toString()).exit == 2, "malformed UTF8 file");
      Files.writeString(manual, "[{\"mode\":\"byte\",\"text\":\"\\u００４１\"}]");
      check(call("--segments", manual.toString()).exit == 2, "nonASCII JSON hex");
    } finally {
      try (var paths = Files.walk(dir)) {
        for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
    return assertions;
  }
}
