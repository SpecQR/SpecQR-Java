import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import javax.tools.ToolProvider;

/** Reproducibility, module dependency, JAR, CLI and detached consumer audit. */
class VerifyPackage {
  static final Path ROOT = Path.of("").toAbsolutePath();
  static final String NAME = "specqr-java-0.1.0-rc.1.jar";

  static String tool(String name) {
    return Path.of(
            System.getProperty("java.home"),
            "bin",
            name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""))
        .toString();
  }

  static String run(Path cwd, String... args) throws Exception {
    Process p = new ProcessBuilder(args).directory(cwd.toFile()).redirectErrorStream(true).start();
    String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int code = p.waitFor();
    if (code != 0)
      throw new AssertionError(
          "Command failed with exit " + code + ": " + String.join(" ", args) + "\n" + result);
    return result;
  }

  static String hash(Path p) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
  }

  static void require(boolean b, String m) {
    if (!b) throw new AssertionError(m);
  }

  public static void main(String[] args) throws Exception {
    run(ROOT, tool("java"), "tools/Build.java");
    Path jar = ROOT.resolve("dist/" + NAME),
        sources = ROOT.resolve("dist/specqr-java-0.1.0-rc.1-sources.jar");
    String first = hash(jar), sourceHash = hash(sources);
    run(ROOT, tool("java"), "tools/Build.java");
    require(
        first.equals(hash(jar)) && sourceHash.equals(hash(sources)),
        "Repeated build is not byte reproducible");
    try (JarFile j = new JarFile(jar.toFile())) {
      require(
          j.getManifest().getMainAttributes().getValue("Main-Class").equals("io.specqr.Main"),
          "Manifest entry");
      require(j.getEntry("module-info.class") != null, "Named module");
      require(
          j.getEntry("META-INF/LICENSE") != null && j.getEntry("META-INF/NOTICE") != null,
          "License attribution");
      var e = j.entries();
      while (e.hasMoreElements()) {
        String name = e.nextElement().getName();
        require(
            !name.contains("Test") && !name.contains("Conformance") && !name.endsWith(".java"),
            "Development artifact in runtime JAR");
        require(
            name.startsWith("io/specqr/")
                || name.equals("module-info.class")
                || name.startsWith("META-INF/"),
            "Unowned class in runtime JAR");
      }
    }
    String deps = run(ROOT, tool("jdeps"), "--print-module-deps", jar.toString()).trim();
    require(deps.equals("java.base"), "Runtime dependencies differ: " + deps);
    Path temp = Files.createTempDirectory("specqr-consumer-");
    try {
      Path isolated = temp.resolve(NAME);
      Files.copy(jar, isolated);
      Path source = temp.resolve("Consumer.java");
      Files.writeString(
          source,
          """
import io.specqr.*;
import java.util.*;
public class Consumer {
  public static void main(String[] args) {
    QrCode q=SpecQr.generate("Independent consumer 漢字 😀",Options.builder().errorCorrectionLevel("Q").build());
    if(q.size()!=17+4*q.version()||q.dataCodewords().length+q.errorCorrectionCodewords().length!=q.codewords().length)throw new AssertionError("Core metadata");
    if(q.toPng()[0]!=(byte)137||!q.toSvg().startsWith("<svg")||!q.toPngDataUrl().startsWith("data:image/png;base64,"))throw new AssertionError("Rendering");
    if(!SpecQr.estimate("123456789").ok())throw new AssertionError("Plan");
    String gs=Gs1.toElementString(List.of(new Gs1.Element("01","09501101530003"),new Gs1.Element("10","LOT%")));
    if(!SpecQr.generate(gs,Options.builder().gs1(true).build()).segments().get(1).mode().equals("byte"))throw new AssertionError("GS1 literal percent");
    var sa=StructuredAppend.generate("A".repeat(120),Options.builder().version(1).build());
    if(sa.total()<2||sa.symbols().size()!=sa.total())throw new AssertionError("Structured Append");
    byte[] input={(byte)255,0,13};if(SpecQr.generate(input).segments().get(0).binary()[0]!=(byte)255)throw new AssertionError("Binary");
    System.out.println("Detached JAR consumer PASS");
  }
}
""",
          StandardCharsets.UTF_8);
      int result =
          ToolProvider.getSystemJavaCompiler()
              .run(
                  null,
                  System.out,
                  System.err,
                  "--release",
                  "17",
                  "-encoding",
                  "UTF-8",
                  "-Xlint:all",
                  "-Werror",
                  "-cp",
                  isolated.toString(),
                  "-d",
                  temp.toString(),
                  source.toString());
      require(result == 0, "Consumer compilation");
      require(
          run(
                  temp,
                  tool("java"),
                  "--limit-modules",
                  "java.base",
                  "-cp",
                  isolated + File.pathSeparator + temp,
                  "Consumer")
              .contains("Detached JAR consumer PASS"),
          "Consumer execution");
      require(
          run(
                  temp,
                  tool("java"),
                  "--limit-modules",
                  "java.base",
                  "--module-path",
                  isolated.toString(),
                  "--module",
                  "io.specqr/io.specqr.Main",
                  "--help")
              .contains("Usage:"),
          "Module CLI");
      String cli =
          run(
              temp,
              tool("java"),
              "--limit-modules",
              "java.base",
              "-jar",
              isolated.toString(),
              "--format",
              "matrix",
              "consumer");
      require(cli.startsWith("[[true"), "JAR CLI");
      run(
          temp,
          tool("java"),
          "--limit-modules",
          "java.base",
          "-jar",
          isolated.toString(),
          "--format",
          "png",
          "--output",
          "consumer.png",
          "consumer");
      require(Files.readAllBytes(temp.resolve("consumer.png"))[0] == (byte) 137, "PNG consumer");
    } finally {
      try (var files = Files.walk(temp)) {
        for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
    System.out.println(
        "PASS: reproducible runtime/source JARs, java.base only, detached classpath/module/CLI"
            + " consumers");
    System.out.println("Runtime SHA-256: " + first);
    System.out.println("Sources SHA-256: " + sourceHash);
  }
}
