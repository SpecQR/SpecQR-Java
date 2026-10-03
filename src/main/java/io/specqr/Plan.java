package io.specqr;

import java.util.List;
import java.util.Map;

/** Encoding plan without matrix, Reed-Solomon or mask work. */
public record Plan(
    boolean ok,
    Integer version,
    int capacityVersion,
    String errorCorrectionLevel,
    String requestedErrorCorrectionLevel,
    boolean boostedErrorCorrection,
    long requiredBits,
    int capacityBits,
    long remainingBits,
    List<Segment> segments,
    Map<String, Object> diagnostics) {
  public Plan {
    segments = List.copyOf(segments);
    diagnostics = SpecQr.freeze(diagnostics);
  }

  public long dataBitLength() {
    return requiredBits;
  }

  public Integer selectedVersion() {
    return version;
  }

  public long overflowBits() {
    return Math.max(0, -remainingBits);
  }

  public double capacityUtilization() {
    return (double) requiredBits / capacityBits;
  }
}
