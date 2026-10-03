package io.specqr;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small bounded JSON codec for the CLI; deliberately not a general public API. */
final class Json {
  private Json() {}

  static String write(Object value) {
    StringBuilder b = new StringBuilder();
    append(b, value);
    return b.toString();
  }

  private static void append(StringBuilder b, Object v) {
    if (v == null) {
      b.append("null");
      return;
    }
    if (v instanceof String s) {
      quote(b, s);
      return;
    }
    if (v instanceof Boolean) {
      b.append(v);
      return;
    }
    if (v instanceof Number n) {
      if (n instanceof Double && !Double.isFinite(n.doubleValue())
          || n instanceof Float && !Float.isFinite(n.floatValue()))
        throw bad("Non-finite JSON number");
      b.append(n);
      return;
    }
    if (v instanceof Map<?, ?> m) {
      b.append('{');
      boolean first = true;
      for (var e : m.entrySet()) {
        if (!(e.getKey() instanceof String)) throw bad("JSON keys must be strings");
        if (!first) b.append(',');
        first = false;
        quote(b, (String) e.getKey());
        b.append(':');
        append(b, e.getValue());
      }
      b.append('}');
      return;
    }
    if (v instanceof Iterable<?> a) {
      b.append('[');
      boolean first = true;
      for (Object x : a) {
        if (!first) b.append(',');
        first = false;
        append(b, x);
      }
      b.append(']');
      return;
    }
    if (v.getClass().isArray()) {
      b.append('[');
      for (int i = 0; i < Array.getLength(v); i++) {
        if (i > 0) b.append(',');
        Object x = Array.get(v, i);
        append(b, x instanceof Byte ? ((Byte) x) & 255 : x);
      }
      b.append(']');
      return;
    }
    throw bad("Unsupported JSON value");
  }

  private static void quote(StringBuilder b, String s) {
    b.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\b' -> b.append("\\b");
        case '\f' -> b.append("\\f");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 32 || Character.isSurrogate(c)) {
            b.append("\\u");
            String h = Integer.toHexString(c);
            b.append("0".repeat(4 - h.length())).append(h);
          } else b.append(c);
        }
      }
    }
    b.append('"');
  }

  static Object parse(String text) {
    if (text == null || text.length() > 4_000_000) throw bad("JSON input exceeds 4 MB");
    Parser p = new Parser(text);
    Object v = p.value(0);
    p.space();
    if (p.i != text.length()) throw bad("Trailing JSON input");
    return v;
  }

  private static SpecQrException bad(String m) {
    return new SpecQrException("INVALID_INPUT", m);
  }

  private static final class Parser {
    final String s;
    int i, nodes;

    Parser(String s) {
      this.s = s;
    }

    void space() {
      while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }

    Object value(int depth) {
      space();
      if (depth > 64 || ++nodes > 100_000) throw bad("JSON nesting or item budget exceeded");
      if (i == s.length()) throw bad("Incomplete JSON");
      char c = s.charAt(i);
      if (c == '"') return string();
      if (c == '[') {
        i++;
        List<Object> a = new ArrayList<>();
        space();
        if (take(']')) return a;
        do {
          a.add(value(depth + 1));
          space();
          if (take(']')) return a;
        } while (take(','));
        throw bad("Expected comma or bracket");
      }
      if (c == '{') {
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        space();
        if (take('}')) return m;
        do {
          space();
          if (i == s.length() || s.charAt(i) != '"') throw bad("Expected JSON key");
          String k = string();
          space();
          if (!take(':')) throw bad("Expected colon");
          if (m.containsKey(k)) throw bad("Duplicate JSON key");
          m.put(k, value(depth + 1));
          space();
          if (take('}')) return m;
        } while (take(','));
        throw bad("Expected comma or brace");
      }
      for (String t : List.of("true", "false", "null"))
        if (s.startsWith(t, i)) {
          i += t.length();
          return t.equals("null") ? null : t.equals("true");
        }
      int start = i;
      if (c == '-') i++;
      if (i == s.length()) throw bad("Invalid number");
      if (s.charAt(i) == '0') i++;
      else {
        int begin = i;
        while (i < s.length() && digit(s.charAt(i))) i++;
        if (i == begin) throw bad("Invalid JSON value");
      }
      boolean decimal = false;
      if (take('.')) {
        decimal = true;
        int begin = i;
        while (i < s.length() && digit(s.charAt(i))) i++;
        if (i == begin) throw bad("Invalid decimal");
      }
      if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
        decimal = true;
        i++;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        int begin = i;
        while (i < s.length() && digit(s.charAt(i))) i++;
        if (i == begin) throw bad("Invalid exponent");
      }
      try {
        String number = s.substring(start, i);
        if (decimal) {
          double d = Double.parseDouble(number);
          if (!Double.isFinite(d)) throw bad("Non-finite number");
          return d;
        }
        return Long.parseLong(number);
      } catch (NumberFormatException e) {
        throw bad("JSON number out of range");
      }
    }

    boolean take(char c) {
      if (i < s.length() && s.charAt(i) == c) {
        i++;
        return true;
      }
      return false;
    }

    boolean digit(char c) {
      return c >= '0' && c <= '9';
    }

    String string() {
      i++;
      StringBuilder b = new StringBuilder();
      while (i < s.length()) {
        char c = s.charAt(i++);
        if (c == '"') return b.toString();
        if (c < 32) throw bad("Control character in JSON string");
        if (c == '\\') {
          if (i == s.length()) throw bad("Incomplete escape");
          char e = s.charAt(i++);
          switch (e) {
            case '"', '\\', '/' -> b.append(e);
            case 'b' -> b.append('\b');
            case 'f' -> b.append('\f');
            case 'n' -> b.append('\n');
            case 'r' -> b.append('\r');
            case 't' -> b.append('\t');
            case 'u' -> {
              if (i + 4 > s.length()) throw bad("Incomplete Unicode escape");
              int n = 0;
              for (int j = 0; j < 4; j++) {
                char h = s.charAt(i++);
                int d =
                    h >= '0' && h <= '9'
                        ? h - '0'
                        : h >= 'A' && h <= 'F'
                            ? h - 'A' + 10
                            : h >= 'a' && h <= 'f' ? h - 'a' + 10 : -1;
                if (d < 0) throw bad("Invalid Unicode escape");
                n = n * 16 + d;
              }
              b.append((char) n);
            }
            default -> throw bad("Unknown JSON escape");
          }
        } else b.append(c);
      }
      throw bad("Unterminated string");
    }
  }
}
