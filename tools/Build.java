import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;

/** JDK-only reproducible build: java tools/Build.java [test]. */
class Build {
  static final Path ROOT = Path.of("").toAbsolutePath();
  static final String VERSION = "0.1.0-rc.1";

  public static void main(String[] args) throws Exception {
    if (!Files.isDirectory(ROOT.resolve("src/main/java")))
      throw new IllegalStateException("Run from the repository root");
    Path classes = ROOT.resolve("build/classes"),
        tests = ROOT.resolve("build/tests"),
        dist = ROOT.resolve("dist");
    clear(classes);
    Files.createDirectories(classes);
    Files.createDirectories(dist);
    compile(ROOT.resolve("src/main/java"), classes, null);
    jar(classes, dist.resolve("specqr-java-" + VERSION + ".jar"), true);
    jar(
        ROOT.resolve("src/main/java"),
        dist.resolve("specqr-java-" + VERSION + "-sources.jar"),
        false);
    System.out.println("Built dependency-free JARs (Java 17 bytecode)");
    if (args.length > 0 && args[0].equals("test")) {
      clear(tests);
      Files.createDirectories(tests);
      compile(ROOT.resolve("src/test/java"), tests, classes.toString());
      String java =
          Path.of(
                  System.getProperty("java.home"),
                  "bin",
                  System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
              .toString();
      int code =
          new ProcessBuilder(
                  java, "-ea", "-cp", classes + File.pathSeparator + tests, "io.specqr.TestRunner")
              .inheritIO()
              .start()
              .waitFor();
      if (code != 0) throw new IllegalStateException("Tests failed with exit " + code);
    }
  }

  static void compile(Path source, Path out, String cp) throws Exception {
    List<String> a =
        new ArrayList<>(
            List.of(
                "--release",
                "17",
                "-encoding",
                "UTF-8",
                "-Xlint:all",
                "-Werror",
                "-d",
                out.toString()));
    if (cp != null) a.addAll(List.of("-cp", cp));
    try (var paths = Files.walk(source)) {
      paths.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> a.add(p.toString()));
    }
    var compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IllegalStateException("A full JDK 17+ is required");
    if (compiler.run(null, System.out, System.err, a.toArray(String[]::new)) != 0)
      throw new IllegalStateException("Compilation failed");
  }

  static void clear(Path dir) throws IOException {
    if (Files.exists(dir))
      try (var paths = Files.walk(dir)) {
        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
  }

  static void entry(ZipOutputStream z, String name, byte[] bytes) throws IOException {
    ZipEntry e = new ZipEntry(name);
    e.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0));
    e.setMethod(ZipEntry.STORED);
    e.setSize(bytes.length);
    CRC32 c = new CRC32();
    c.update(bytes);
    e.setCrc(c.getValue());
    z.putNextEntry(e);
    z.write(bytes);
    z.closeEntry();
  }

  static void jar(Path src, Path target, boolean main) throws IOException {
    try (var z = new ZipOutputStream(Files.newOutputStream(target))) {
      if (main)
        entry(
            z,
            "META-INF/MANIFEST.MF",
            ("Manifest-Version: 1.0\r\nMain-Class: io.specqr.Main\r\nImplementation-Version: "
                    + VERSION
                    + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
      entry(z, "META-INF/LICENSE", Files.readAllBytes(ROOT.resolve("LICENSE")));
      entry(z, "META-INF/NOTICE", Files.readAllBytes(ROOT.resolve("NOTICE")));
      try (var paths = Files.walk(src)) {
        for (Path p : paths.filter(Files::isRegularFile).sorted().toList())
          entry(
              z,
              src.relativize(p).toString().replace(File.separatorChar, '/'),
              Files.readAllBytes(p));
      }
    }
  }
}
