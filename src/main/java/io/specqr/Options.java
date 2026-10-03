package io.specqr;

/** Immutable encoding and default rendering options. Use {@link #builder()}. */
public final class Options {
  private final String level, mode, second, foreground, background;
  private final Integer version, mask, eci;
  private final int min, max, margin, scale;
  private final boolean optimize, boost, gs1;
  private final Segment append;
  private final Double dpi;

  private Options(Builder b) {
    Tables.validateLevel(b.level);
    Tables.validateVersion(b.min);
    Tables.validateVersion(b.max);
    if (b.min > b.max) fail("INVALID_VERSION", "minVersion must not exceed maxVersion");
    if (b.version != null) Tables.validateVersion(b.version);
    if (b.mask != null && (b.mask < 0 || b.mask > 7))
      fail("INVALID_INPUT", "maskPattern must be 0..7");
    if (b.mode == null
        || !java.util.Set.of("auto", "numeric", "alphanumeric", "byte", "kanji").contains(b.mode))
      fail("INVALID_MODE", "Unknown mode");
    if (b.eci != null && (b.eci < 0 || b.eci > 999999))
      fail("INVALID_ECI", "ECI must be 0..999999");
    if (b.second != null) Segment.fnc1Second(b.second);
    if (b.append != null && !b.append.mode().equals("structured-append"))
      fail("INVALID_MODE", "structuredAppend requires a Structured Append segment");
    if ((b.eci != null ? 1 : 0)
            + (b.gs1 ? 1 : 0)
            + (b.second != null ? 1 : 0)
            + (b.append != null ? 1 : 0)
        > 1) fail("INVALID_MODE", "ECI, GS1, FNC1 second and Structured Append cannot be combined");
    if (b.margin < 0 || b.scale < 1)
      fail("INVALID_INPUT", "margin must be nonnegative and scale positive");
    if (b.foreground == null
        || b.background == null
        || b.foreground.length() > Render.SVG_CHARACTER_BUDGET / 12
        || b.background.length() > Render.SVG_CHARACTER_BUDGET / 12)
      fail("INVALID_COLOR", "Colors must be bounded non-null strings");
    if (b.dpi != null
        && (!Double.isFinite(b.dpi)
            || b.dpi <= 0
            || !Double.isFinite((177L + 2L * b.margin) * (b.scale / b.dpi * 25.4))))
      fail("INVALID_INPUT", "printDpi must be finite and positive with finite print geometry");
    level = b.level;
    mode = b.mode;
    second = b.second;
    foreground = b.foreground;
    background = b.background;
    version = b.version;
    mask = b.mask;
    eci = b.eci;
    min = b.min;
    max = b.max;
    margin = b.margin;
    scale = b.scale;
    optimize = b.optimize;
    boost = b.boost;
    gs1 = b.gs1;
    append = b.append;
    dpi = b.dpi;
  }

  private static void fail(String code, String message) {
    throw new SpecQrException(code, message);
  }

  public static Options defaults() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  public String errorCorrectionLevel() {
    return level;
  }

  public String mode() {
    return mode;
  }

  public Integer version() {
    return version;
  }

  public int minVersion() {
    return min;
  }

  public int maxVersion() {
    return max;
  }

  public Integer maskPattern() {
    return mask;
  }

  public boolean optimizeSegments() {
    return optimize;
  }

  public boolean boostErrorCorrection() {
    return boost;
  }

  public Integer eci() {
    return eci;
  }

  public boolean gs1() {
    return gs1;
  }

  public String fnc1Second() {
    return second;
  }

  public Segment structuredAppend() {
    return append;
  }

  public int margin() {
    return margin;
  }

  public int scale() {
    return scale;
  }

  public String foreground() {
    return foreground;
  }

  public String background() {
    return background;
  }

  public Double printDpi() {
    return dpi;
  }

  public static final class Builder {
    private String level = "M",
        mode = "auto",
        second,
        foreground = "#000000",
        background = "#ffffff";
    private Integer version, mask, eci;
    private int min = 1, max = 40, margin = 4, scale = 8;
    private boolean optimize = true, boost, gs1;
    private Segment append;
    private Double dpi;

    private Builder() {}

    private Builder(Options o) {
      level = o.level;
      mode = o.mode;
      second = o.second;
      foreground = o.foreground;
      background = o.background;
      version = o.version;
      mask = o.mask;
      eci = o.eci;
      min = o.min;
      max = o.max;
      margin = o.margin;
      scale = o.scale;
      optimize = o.optimize;
      boost = o.boost;
      gs1 = o.gs1;
      append = o.append;
      dpi = o.dpi;
    }

    public Builder errorCorrectionLevel(String v) {
      level = v;
      return this;
    }

    public Builder mode(String v) {
      mode = v;
      return this;
    }

    public Builder version(Integer v) {
      version = v;
      return this;
    }

    public Builder minVersion(int v) {
      min = v;
      return this;
    }

    public Builder maxVersion(int v) {
      max = v;
      return this;
    }

    public Builder maskPattern(Integer v) {
      mask = v;
      return this;
    }

    public Builder optimizeSegments(boolean v) {
      optimize = v;
      return this;
    }

    public Builder boostErrorCorrection(boolean v) {
      boost = v;
      return this;
    }

    public Builder eci(Integer v) {
      eci = v;
      return this;
    }

    public Builder gs1(boolean v) {
      gs1 = v;
      return this;
    }

    public Builder fnc1Second(String v) {
      second = v;
      return this;
    }

    public Builder structuredAppend(Segment v) {
      append = v;
      return this;
    }

    public Builder margin(int v) {
      margin = v;
      return this;
    }

    public Builder scale(int v) {
      scale = v;
      return this;
    }

    public Builder foreground(String v) {
      foreground = v;
      return this;
    }

    public Builder background(String v) {
      background = v;
      return this;
    }

    public Builder printDpi(Double v) {
      dpi = v;
      return this;
    }

    public Options build() {
      return new Options(this);
    }
  }
}
