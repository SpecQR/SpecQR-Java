package io.specqr;

/** Exact single-segment capacity; byte counts are not UTF-8 character counts. */
public record Capacity(
    int version,
    String errorCorrectionLevel,
    int size,
    int dataCodewords,
    int totalCodewords,
    int capacityBits,
    String mode,
    Integer characterCountBits,
    Integer modeBits,
    long controlBits,
    Long payloadBits,
    Integer maxCharacters,
    Integer maxBytes) {
  public Integer maximum() {
    return mode != null && mode.equals("byte") ? maxBytes : maxCharacters;
  }
}
