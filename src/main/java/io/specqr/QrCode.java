package io.specqr;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Immutable QR symbol; every array accessor returns a defensive copy. */
public final class QrCode {
  private final boolean[][] matrix;
  private final int version, mask;
  private final String level;
  private final byte[] data, words;
  private final List<Segment> segments;
  private final Map<String, Object> diagnostics;
  private final Options options;

  QrCode(
      boolean[][] m,
      int v,
      int mask,
      String e,
      byte[] d,
      byte[] w,
      List<Segment> s,
      Map<String, Object> diag,
      Options o) {
    matrix = Render.copyMatrix(m);
    version = v;
    this.mask = mask;
    level = e;
    data = d.clone();
    words = w.clone();
    segments = List.copyOf(s);
    diagnostics = SpecQr.freeze(diag);
    options = o;
  }

  public boolean[][] matrix() {
    return Render.copyMatrix(matrix);
  }

  public int version() {
    return version;
  }

  public int size() {
    return matrix.length;
  }

  public int maskPattern() {
    return mask;
  }

  public String errorCorrectionLevel() {
    return level;
  }

  public boolean module(int x, int y) {
    if (x < 0 || y < 0 || x >= size() || y >= size())
      throw new SpecQrException("INVALID_INPUT", "Module coordinates out of bounds");
    return matrix[y][x];
  }

  public byte[] dataCodewords() {
    return data.clone();
  }

  public byte[] codewords() {
    return words.clone();
  }

  public byte[] errorCorrectionCodewords() {
    return Arrays.copyOfRange(words, data.length, words.length);
  }

  public List<Segment> segments() {
    return segments;
  }

  public Map<String, Object> diagnostics() {
    return diagnostics;
  }

  public Options options() {
    return options;
  }

  public String toSvg() {
    return Render.toSvg(matrix, options);
  }

  public String toSvg(Options o) {
    return Render.toSvg(matrix, o);
  }

  public byte[] toPng() {
    return Render.toPng(matrix, options);
  }

  public byte[] toPng(Options o) {
    return Render.toPng(matrix, o);
  }

  public Render.Pixels toPixels() {
    return Render.toPixels(matrix, options);
  }

  public Render.Pixels toPixels(Options o) {
    return Render.toPixels(matrix, o);
  }

  public String toSvgDataUrl() {
    return Render.toSvgDataUrl(matrix, options);
  }

  public String toSvgDataUrl(Options o) {
    return Render.toSvgDataUrl(matrix, o);
  }

  public String toPngDataUrl() {
    return Render.toPngDataUrl(matrix, options);
  }

  public String toPngDataUrl(Options o) {
    return Render.toPngDataUrl(matrix, o);
  }
}
