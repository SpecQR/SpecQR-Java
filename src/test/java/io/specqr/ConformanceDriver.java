package io.specqr;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Development-only JSON-lines bridge to the actual Java implementation. No expected values or
 * reference encoder code are present in this class.
 */
public final class ConformanceDriver {
  private ConformanceDriver() {}

  private static Map<String, Object> map(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  private static int number(Object value) {
    return Main.number(value);
  }

  private static String string(Object value) {
    return Main.string(value);
  }

  private static boolean bool(Object value) {
    if (!(value instanceof Boolean b))
      throw new SpecQrException("INVALID_INPUT", "Expected boolean");
    return b;
  }

  private static byte[] bytes(Object value) {
    if (!(value instanceof List<?> list))
      throw new SpecQrException("INVALID_INPUT", "Expected byte array");
    byte[] output = new byte[list.size()];
    for (int i = 0; i < output.length; i++) {
      int n = number(list.get(i));
      if (n < 0 || n > 255) throw new SpecQrException("INVALID_INPUT", "Byte out of range");
      output[i] = (byte) n;
    }
    return output;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?>))
      throw new SpecQrException("INVALID_INPUT", "Expected object");
    return (Map<String, Object>) value;
  }

  private static Options options(Map<String, Object> opts) {
    Options.Builder b = Options.builder();
    for (var entry : opts.entrySet()) {
      Object v = entry.getValue();
      switch (entry.getKey()) {
        case "version" -> b.version("auto".equals(v) ? null : number(v));
        case "minVersion" -> b.minVersion(number(v));
        case "maxVersion" -> b.maxVersion(number(v));
        case "maskPattern" -> b.maskPattern("auto".equals(v) ? null : number(v));
        case "errorCorrectionLevel" -> b.errorCorrectionLevel(string(v));
        case "mode" -> b.mode(string(v));
        case "optimizeSegments" -> b.optimizeSegments(bool(v));
        case "boostErrorCorrection" -> b.boostErrorCorrection(bool(v));
        case "eci" -> b.eci(Boolean.FALSE.equals(v) ? null : number(v));
        case "gs1" -> b.gs1(bool(v));
        case "fnc1Second" -> b.fnc1Second(Boolean.FALSE.equals(v) ? null : string(v));
        case "structuredAppend" -> {
          Map<String, Object> a = object(v);
          b.structuredAppend(
              Segment.structuredAppend(
                  number(a.get("index")), number(a.get("total")), number(a.get("parity"))));
        }
        case "scale" -> b.scale(number(v));
        case "margin" -> b.margin(number(v));
        case "foreground" -> b.foreground(string(v));
        case "background" -> b.background(string(v));
        case "printDpi" -> b.printDpi(((Number) v).doubleValue());
        case "maxSymbols", "diagnostics" -> {}
        case "output" -> {
          if (!"matrix".equals(v))
            throw new IllegalArgumentException("Differential output must be matrix");
        }
        default -> throw new IllegalArgumentException("Unknown test option: " + entry.getKey());
      }
    }
    return b.build();
  }

  private static String hex(byte[] value) {
    return HexFormat.of().formatHex(value);
  }

  private static String sha(byte[] value) throws Exception {
    return hex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static List<String> rows(boolean[][] matrix) {
    List<String> rows = new ArrayList<>();
    for (boolean[] row : matrix) {
      if (row.length != matrix.length) throw new AssertionError("Matrix is not square");
      StringBuilder text = new StringBuilder(row.length);
      for (boolean module : row) text.append(module ? '1' : '0');
      rows.add(text.toString());
    }
    return rows;
  }

  private static String matrixHash(List<String> rows) throws Exception {
    return sha(String.join("", rows).getBytes(StandardCharsets.US_ASCII));
  }

  private static Map<String, Object> symbol(QrCode qr, Map<String, Object> request)
      throws Exception {
    List<String> matrix = rows(qr.matrix());
    Map<String, Object> out =
        map(
            "version",
            qr.version(),
            "ecc",
            qr.errorCorrectionLevel(),
            "mask",
            qr.maskPattern(),
            "matrixHash",
            matrixHash(matrix),
            "data",
            hex(qr.dataCodewords()),
            "codewords",
            hex(qr.codewords()));
    if (Boolean.TRUE.equals(request.get("includeMatrix")) || request.containsKey("pngScale"))
      out.put("matrix", matrix);
    if (request.containsKey("pngScale"))
      out.put(
          "png",
          hex(
              qr.toPng(
                  Options.builder().scale(number(request.get("pngScale"))).margin(4).build())));
    return out;
  }

  private static Object payload(Map<String, Object> request) throws Exception {
    if (request.containsKey("rawText"))
      return StandardCharsets.UTF_8
          .newDecoder()
          .decode(ByteBuffer.wrap(bytes(request.get("rawText"))))
          .toString();
    return request.containsKey("bytes")
        ? bytes(request.get("bytes"))
        : string(request.getOrDefault("text", ""));
  }

  private static Map<String, Object> identity(Map<String, Object> request) throws Exception {
    Path origin = Path.of(SpecQr.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Map<String, Object> hashes = new LinkedHashMap<>();
    if (Files.isDirectory(origin)) {
      try (var paths = Files.walk(origin)) {
        for (Path p : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList())
          hashes.put(origin.relativize(p).toString(), sha(Files.readAllBytes(p)));
      }
    } else hashes.put(origin.getFileName().toString(), sha(Files.readAllBytes(origin)));
    return map(
        "java",
        System.getProperty("java.runtime.version"),
        "vm",
        System.getProperty("java.vm.name"),
        "executable",
        ProcessHandle.current().info().command().orElse("unknown"),
        "module",
        origin.toString(),
        "pid",
        ProcessHandle.current().pid(),
        "nonce",
        request.get("nonce"),
        "packageVersion",
        SpecQr.VERSION,
        "packageFilesSha256",
        hashes,
        "candidateClass",
        "io.specqr.SpecQr",
        "classPath",
        System.getProperty("java.class.path"));
  }

  private static Map<String, Object> dispatch(Map<String, Object> request) throws Exception {
    String command = string(request.getOrDefault("command", "generate"));
    if (command.equals("identity")) return identity(request);
    if (command.equals("concurrency")) {
      List<?> tasks = (List<?>) request.get("requests");
      if (tasks.size() > 512) throw new IllegalArgumentException("Unbounded concurrency corpus");
      var pool = Executors.newFixedThreadPool(8);
      try {
        var futures = new ArrayList<java.util.concurrent.Future<Map<String, Object>>>();
        for (Object task : tasks) {
          Map<String, Object> r = object(task);
          if ("concurrency".equals(r.get("command")))
            throw new IllegalArgumentException("Nested concurrency");
          futures.add(pool.submit(() -> dispatch(r)));
        }
        List<Object> results = new ArrayList<>();
        for (var f : futures) results.add(f.get());
        return map("results", results);
      } finally {
        pool.shutdownNow();
      }
    }
    if (command.equals("raw")) {
      int version = number(request.get("version")),
          seed = number(request.get("seed")),
          mask = number(request.get("mask"));
      String level = string(request.get("ecc"));
      int ordinal = "LMQH".indexOf(level);
      byte[] data = new byte[Tables.dataCodewords(version, level)];
      for (int i = 0; i < data.length; i++)
        data[i] =
            (byte)
                (seed == 0
                    ? 0
                    : seed == 1
                        ? 255
                        : ((i * 149 + version * 43 + ordinal * 89 + seed * 67) ^ (i >> (seed + 1)))
                            & 255);
      var interleaved = Core.interleaveCodewords(data, version, level);
      var matrix =
          Core.buildMatrix(interleaved.codewords(), version, level, mask < 0 ? null : mask);
      return map(
          "data",
          hex(data),
          "codewords",
          hex(interleaved.codewords()),
          "matrixHash",
          matrixHash(rows(matrix.matrix())),
          "mask",
          matrix.maskPattern(),
          "penalty",
          matrix.penalty(),
          "penalties",
          matrix.maskPenalties().stream().map(Core.MaskPenalty::penalty).toList());
    }
    if (command.equals("gf")) {
      byte[] products = new byte[65536];
      for (int a = 0; a < 256; a++)
        for (int b = 0; b < 256; b++) products[a * 256 + b] = (byte) Core.gfMultiply(a, b);
      return map("bytes", hex(products));
    }
    if (command.equals("rs")) {
      int degree = number(request.get("degree"));
      byte[] data = new byte[300];
      for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 61 + degree);
      return map(
          "generator",
          hex(Core.reedSolomonDivisor(degree)),
          "remainder",
          hex(Core.reedSolomonRemainder(data, degree)));
    }
    Map<String, Object> rawOptions = object(request.getOrDefault("options", Map.of()));
    Options opts = options(rawOptions);
    if (command.equals("capacity")) {
      Capacity c = SpecQr.getCapacity(opts.version(), opts.errorCorrectionLevel(), opts.mode());
      return map(
          "maximum",
          c.maximum(),
          "dataCodewords",
          c.dataCodewords(),
          "capacityBits",
          c.capacityBits(),
          "countBits",
          c.characterCountBits());
    }
    Object input = payload(request);
    if (command.equals("estimate")) {
      Plan p =
          input instanceof byte[] data
              ? SpecQr.estimate(data, opts)
              : SpecQr.estimate((String) input, opts);
      return map(
          "fits",
          p.ok(),
          "version",
          p.version(),
          "requiredBits",
          p.requiredBits(),
          "capacityBits",
          p.capacityBits());
    }
    if (command.equals("structured-append"))
      return structuredAppend(request, rawOptions, opts, input);
    QrCode qr =
        request.containsKey("segments")
            ? SpecQr.generateSegments(Main.parseSegments(request.get("segments")), opts)
            : input instanceof byte[] data
                ? SpecQr.generate(data, opts)
                : SpecQr.generate((String) input, opts);
    return symbol(qr, request);
  }

  private static Map<String, Object> structuredAppend(
      Map<String, Object> request, Map<String, Object> rawOptions, Options opts, Object input)
      throws Exception {
    int max = rawOptions.containsKey("maxSymbols") ? number(rawOptions.get("maxSymbols")) : 16;
    StructuredAppend.Result result =
        request.containsKey("segments")
            ? StructuredAppend.generateSegments(
                Main.parseSegments(request.get("segments")), opts, max)
            : input instanceof byte[] data
                ? StructuredAppend.generate(data, opts, max)
                : StructuredAppend.generate((String) input, opts, max);
    List<Map<String, Object>> symbols = new ArrayList<>();
    for (QrCode qr : result.symbols()) symbols.add(symbol(qr, request));
    return map(
        "total",
        result.total(),
        "parity",
        result.parity(),
        "inputLength",
        result.inputLength(),
        "byteLength",
        result.byteLength(),
        "symbols",
        symbols,
        "matrixHashes",
        symbols.stream().map(s -> s.get("matrixHash")).toList(),
        "versions",
        symbols.stream().map(s -> s.get("version")).toList(),
        "masks",
        symbols.stream().map(s -> s.get("mask")).toList());
  }

  public static void main(String[] args) throws Exception {
    try (var reader =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
      for (String line; (line = reader.readLine()) != null; ) {
        Map<String, Object> request = object(Json.parse(line)), out;
        try {
          out = dispatch(request);
        } catch (Exception | AssertionError error) {
          out =
              map(
                  "error",
                  error.getClass().getSimpleName(),
                  "message",
                  error.getMessage(),
                  "isSpecQRError",
                  error instanceof SpecQrException,
                  "code",
                  error instanceof SpecQrException s ? s.code() : null);
        }
        String fault =
            "identity".equals(request.get("command")) ? null : System.getenv("SPECQR_TEST_FAULT");
        if ("exit".equals(fault)) System.exit(73);
        if ("drop".equals(fault)) continue;
        if ("error".equals(fault))
          out = map("error", "InjectedFailure", "message", "test-only negative control");
        if (fault != null && fault.startsWith("leak-"))
          out =
              map(
                  "error",
                  fault.substring(5),
                  "message",
                  "test-only untyped exception leak control",
                  "isSpecQRError",
                  false,
                  "code",
                  null);
        if (List.of("matrixHash", "data", "codewords").contains(fault == null ? "" : fault)
            && out.containsKey(fault)) {
          String value = string(out.get(fault));
          int i = "codewords".equals(fault) ? value.length() - 1 : 0;
          out.put(
              fault,
              value.substring(0, i)
                  + (value.charAt(i) == '1' ? '0' : '1')
                  + value.substring(i + 1));
        }
        System.out.println(Json.write(out));
        System.out.flush();
      }
    }
  }
}
