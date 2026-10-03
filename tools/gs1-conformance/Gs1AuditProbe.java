package io.specqr;

import java.io.*;
import java.util.*;

public final class Gs1AuditProbe {
  private Gs1AuditProbe() {}

  static Object diagnostic(Gs1.ValidationIssue issue) {
    return SpecQr.map(
        "code",
        issue.code(),
        "reason",
        issue.reason(),
        "ai",
        issue.ai(),
        "value",
        issue.value(),
        "key",
        issue.key(),
        "offset",
        issue.offset(),
        "elementIndex",
        issue.elementIndex(),
        "expected",
        issue.expected(),
        "count",
        issue.count());
  }

  static Object elements(List<Gs1.Element> input) {
    return input.stream().map(e -> List.of(e.ai(), e.value())).toList();
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    BufferedReader in =
        new BufferedReader(
            new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
    String line;
    while ((line = in.readLine()) != null) {
      Map<String, Object> out = new LinkedHashMap<>();
      try {
        Map<String, Object> request = (Map<String, Object>) Json.parse(line);
        String command = (String) request.get("command");
        if (command.equals("catalog")) {
          List<Object> catalog = new ArrayList<>();
          for (Gs1.AiInfo info : Gs1.getSupportedAis()) {
            Map<String, Object> length =
                info.length().isVariable()
                    ? SpecQr.map(
                        "type", "variable", "min", info.length().min(), "max", info.length().max())
                    : SpecQr.map("type", "fixed", "exact", info.length().exact());
            Map<String, Object> entry =
                SpecQr.map(
                    "ai",
                    info.ai(),
                    "label",
                    info.label(),
                    "length",
                    length,
                    "valueKind",
                    info.valueKind(),
                    "checkDigitRule",
                    info.checkDigitRule(),
                    "digitalLinkRole",
                    info.digitalLinkRole(),
                    "separator",
                    info.separator());
            if (info.digitalLinkPathForPrimary() != null)
              entry.put("digitalLinkPathForPrimary", info.digitalLinkPathForPrimary());
            catalog.add(entry);
          }
          out.put("catalog", SpecQr.map("ok", true, "value", catalog));
        } else if (command.equals("url")) {
          String uri = (String) request.get("input");
          Map<String, Object> opts =
              request.containsKey("options")
                  ? (Map<String, Object>) request.get("options")
                  : Map.of();
          Gs1.DigitalLinkOptions options =
              new Gs1.DigitalLinkOptions(
                  null,
                  (String) opts.get("primaryAi"),
                  null,
                  (String) opts.getOrDefault("unknownQuery", "preserve"),
                  Boolean.TRUE.equals(opts.get("normalize")),
                  "specqr-deterministic");
          try {
            var r = Gs1.parseDigitalLink(uri, options);
            out.put(
                "parse",
                SpecQr.map(
                    "ok",
                    true,
                    "elements",
                    elements(r.elements()),
                    "path",
                    elements(r.pathElements()),
                    "query",
                    elements(r.queryElements()),
                    "unknown",
                    r.unknownQuery().stream().map(q -> List.of(q.key(), q.value())).toList()));
          } catch (SpecQrException e) {
            out.put("parse", SpecQr.map("ok", false, "code", e.code()));
          }
          try {
            out.put(
                "normalize",
                SpecQr.map("ok", true, "value", Gs1.normalizeDigitalLink(uri, options)));
          } catch (SpecQrException e) {
            out.put("normalize", SpecQr.map("ok", false, "code", e.code()));
          }
          var v = Gs1.validateDigitalLink(uri, options);
          out.put(
              "validate",
              SpecQr.map(
                  "ok",
                  v.ok(),
                  "errors",
                  v.errors().stream().map(Gs1AuditProbe::diagnostic).toList(),
                  "warnings",
                  v.warnings().stream().map(Gs1AuditProbe::diagnostic).toList()));
        } else if (command.equals("create")) {
          var values = (List<?>) request.get("elements");
          String base = (String) request.get("baseUrl");
          try {
            out.put("create", SpecQr.map("ok", true, "value", Gs1.createDigitalLink(values, base)));
          } catch (SpecQrException e) {
            out.put("create", SpecQr.map("ok", false, "code", e.code()));
          }
        } else if (command.equals("elements")) {
          var values = (List<?>) request.get("elements");
          var v = Gs1.validateElements(values);
          out.put(
              "validate",
              SpecQr.map(
                  "ok",
                  v.ok(),
                  "errors",
                  v.errors().stream().map(Gs1AuditProbe::diagnostic).toList()));
          try {
            out.put("string", SpecQr.map("ok", true, "value", Gs1.toElementString(values)));
          } catch (SpecQrException e) {
            out.put("string", SpecQr.map("ok", false, "code", e.code()));
          }
        }
      } catch (Throwable e) {
        out.put("uncaught", e.toString());
      }
      if (Boolean.getBoolean("specqr.gs1.audit.corrupt") && out.containsKey("catalog"))
        out.put("catalog", SpecQr.map("ok", true, "value", List.of("deliberate negative control")));
      System.out.println(Json.write(out));
    }
  }
}
