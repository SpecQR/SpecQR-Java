package io.specqr;

/** Validated input or capacity failure with a stable machine-readable code. */
public final class SpecQrException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;
  private final String code;

  public SpecQrException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
