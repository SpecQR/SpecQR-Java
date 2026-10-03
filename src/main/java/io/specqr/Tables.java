package io.specqr;

/**
 * QR Code Model 2 capacities and block parameters for versions 1 through 40. Tables and formulas
 * ported from SpecQR at 15ad15e5c770ea0e39072f8f88b2733018f02ffd (MIT).
 */
public final class Tables {
  private Tables() {}

  private static final int[][] ECC_PER_BLOCK = {
    {
      -1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30,
      30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30
    },
    {
      -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28,
      28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28
    },
    {
      -1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30,
      30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30
    },
    {
      -1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24,
      30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30
    }
  };
  private static final int[][] BLOCKS = {
    {
      -1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13,
      14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25
    },
    {
      -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23,
      25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49
    },
    {
      -1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29,
      34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68
    },
    {
      -1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35,
      37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81
    }
  };

  /** Immutable block parameters; total parity length is blocks * eccPerBlock. */
  public record BlockInfo(int blocks, int eccPerBlock, int rawCodewords, int dataCodewords) {}

  /** Reject versions outside the Model 2 range before capacity calculations. */
  public static void validateVersion(int version) {
    if (version < 1 || version > 40) {
      throw new SpecQrException("INVALID_VERSION", "QR version must be an integer from 1 to 40");
    }
  }

  /** Accept exactly the uppercase error-correction levels L, M, Q, and H. */
  public static void validateLevel(String level) {
    levelIndex(level);
  }

  private static int levelIndex(String level) {
    if (level == null) {
      throw invalid("Error correction level must be one of L, M, Q, H");
    }
    return switch (level) {
      case "L" -> 0;
      case "M" -> 1;
      case "Q" -> 2;
      case "H" -> 3;
      default -> throw invalid("Error correction level must be one of L, M, Q, H");
    };
  }

  static int formatBits(String level) {
    return switch (levelIndex(level)) {
      case 0 -> 1;
      case 1 -> 0;
      case 2 -> 3;
      default -> 2;
    };
  }

  /** Width and height, in modules, excluding the quiet zone. */
  public static int size(int version) {
    validateVersion(version);
    return version * 4 + 17;
  }

  /** Total data and error-correction codewords, excluding remainder bits. */
  public static int rawCodewords(int version) {
    validateVersion(version);
    int bits = (16 * version + 128) * version + 64;
    if (version >= 2) {
      int count = version / 7 + 2;
      bits -= (25 * count - 10) * count - 55;
      if (version >= 7) {
        bits -= 36;
      }
    }
    return bits / 8;
  }

  /** Data capacity in bytes, including segment headers and padding. */
  public static int dataCodewords(int version, String level) {
    return blockInfo(version, level).dataCodewords();
  }

  public static BlockInfo blockInfo(int version, String level) {
    validateVersion(version);
    int index = levelIndex(level);
    int blocks = BLOCKS[index][version];
    int ecc = ECC_PER_BLOCK[index][version];
    int raw = rawCodewords(version);
    return new BlockInfo(blocks, ecc, raw, raw - blocks * ecc);
  }

  /** A fresh sorted array of alignment-pattern center coordinates. */
  public static int[] alignmentPositions(int version) {
    validateVersion(version);
    if (version == 1) {
      return new int[0];
    }
    int count = version / 7 + 2;
    int denominator = count * 2 - 2;
    int step = version == 32 ? 26 : (version * 4 + 4 + denominator - 1) / denominator * 2;
    int[] positions = new int[count];
    positions[0] = 6;
    for (int index = count - 1, position = size(version) - 7;
        index >= 1;
        index--, position -= step) {
      positions[index] = position;
    }
    return positions;
  }

  /** Width of the character-count field for a data mode and version. */
  public static int countBits(String mode, int version) {
    validateVersion(version);
    if (mode == null) {
      throw invalid("Mode must be numeric, alphanumeric, byte, or kanji");
    }
    return switch (mode) {
      case "numeric" -> version <= 9 ? 10 : version <= 26 ? 12 : 14;
      case "alphanumeric" -> version <= 9 ? 9 : version <= 26 ? 11 : 13;
      case "byte" -> version <= 9 ? 8 : 16;
      case "kanji" -> version <= 9 ? 8 : version <= 26 ? 10 : 12;
      default ->
          throw new SpecQrException(
              "INVALID_MODE", "Mode must be numeric, alphanumeric, byte, or kanji");
    };
  }

  private static SpecQrException invalid(String message) {
    return new SpecQrException("INVALID_INPUT", message);
  }
}
