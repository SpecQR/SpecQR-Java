package io.specqr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dependency-free Model 2 padding, Reed-Solomon coding, placement, and masking. The matrix and
 * mask-selection behavior follows SpecQR at 15ad15e5c770ea0e39072f8f88b2733018f02ffd (MIT). Matrix
 * indexing is [row][column].
 */
public final class Core {
  private static final int MAX_CODEWORDS = 3706;
  private static final int[][] DIVISORS = new int[256][];

  private Core() {}

  /** A data block and its Reed-Solomon parity, both returned as defensive copies. */
  public static final class CodewordBlock {
    private final byte[] data;
    private final byte[] ecc;

    private CodewordBlock(byte[] data, byte[] ecc) {
      this.data = data.clone();
      this.ecc = ecc.clone();
    }

    public byte[] data() {
      return data.clone();
    }

    public byte[] ecc() {
      return ecc.clone();
    }
  }

  /** Interleaved symbol bytes and immutable per-block diagnostic information. */
  public static final class InterleavedResult {
    private final byte[] codewords;
    private final List<CodewordBlock> blocks;
    private final int dataCodewords;

    private InterleavedResult(byte[] codewords, List<CodewordBlock> blocks, int dataCodewords) {
      this.codewords = codewords.clone();
      this.blocks = List.copyOf(blocks);
      this.dataCodewords = dataCodewords;
    }

    public byte[] codewords() {
      return codewords.clone();
    }

    /** Parity bytes in their final interleaved order. */
    public byte[] errorCorrectionCodewords() {
      return Arrays.copyOfRange(codewords, dataCodewords, codewords.length);
    }

    public List<CodewordBlock> blocks() {
      return blocks;
    }

    public int dataCodewords() {
      return dataCodewords;
    }

    public int errorCorrectionCodewordCount() {
      return codewords.length - dataCodewords;
    }

    public int totalCodewords() {
      return codewords.length;
    }
  }

  /** Penalty assigned to one evaluated mask. */
  public record MaskPenalty(int maskPattern, int penalty) {}

  /** A completed matrix, selected mask, and penalties in candidate-mask order. */
  public static final class MatrixResult {
    private final boolean[][] matrix;
    private final int maskPattern;
    private final int penalty;
    private final List<MaskPenalty> maskPenalties;

    private MatrixResult(
        boolean[][] matrix, int maskPattern, int penalty, List<MaskPenalty> penalties) {
      this.matrix = copyMatrix(matrix);
      this.maskPattern = maskPattern;
      this.penalty = penalty;
      this.maskPenalties = List.copyOf(penalties);
    }

    public boolean[][] matrix() {
      return copyMatrix(matrix);
    }

    public int maskPattern() {
      return maskPattern;
    }

    public int penalty() {
      return penalty;
    }

    public List<MaskPenalty> maskPenalties() {
      return maskPenalties;
    }
  }

  /**
   * Append the terminator, zero alignment bits, and alternating EC/11 pad bytes. The input is
   * checked against the chosen symbol before allocation or traversal.
   */
  public static byte[] padDataBits(int[] bits, int version, String level) {
    int capacity = Tables.dataCodewords(version, level);
    if (bits == null) {
      throw invalid("Bits must not be null");
    }
    if (bits.length > capacity * 8) {
      throw new SpecQrException(
          "DATA_TOO_LONG",
          "Input requires "
              + bits.length
              + " bits, but version "
              + version
              + "-"
              + level
              + " has "
              + capacity * 8
              + " data bits");
    }
    byte[] data = new byte[capacity];
    for (int index = 0; index < bits.length; index++) {
      int value = bits[index];
      if (value != 0 && value != 1) {
        throw invalid("Bits must contain only 0 or 1");
      }
      data[index / 8] |= (byte) (value << (7 - index % 8));
    }
    int terminatedBits = bits.length + Math.min(4, capacity * 8 - bits.length);
    int paddedBytes = (terminatedBits + 7) / 8;
    for (int index = paddedBytes; index < capacity; index++) {
      data[index] = (byte) ((index - paddedBytes) % 2 == 0 ? 0xEC : 0x11);
    }
    return data;
  }

  /** Multiply byte-valued integers in GF(256), reduction polynomial 0x11D. */
  public static int gfMultiply(int left, int right) {
    range(left, 0, 255, "Left operand");
    range(right, 0, 255, "Right operand");
    return multiply(left, right);
  }

  private static int multiply(int left, int right) {
    int result = 0;
    while (right != 0) {
      if ((right & 1) != 0) {
        result ^= left;
      }
      right >>>= 1;
      left <<= 1;
      if ((left & 0x100) != 0) {
        left ^= 0x11D;
      }
    }
    return result;
  }

  /** Raise a GF(256) byte to a nonnegative exponent in bounded logarithmic time. */
  public static int gfPow(int base, int exponent) {
    range(base, 0, 255, "Base");
    if (exponent < 0) {
      throw invalid("Exponent must be nonnegative");
    }
    int result = 1;
    while (exponent != 0) {
      if ((exponent & 1) != 0) {
        result = multiply(result, base);
      }
      base = multiply(base, base);
      exponent >>>= 1;
    }
    return result;
  }

  /** Generator coefficients in descending-power order, including the monic 1. */
  public static byte[] reedSolomonDivisor(int degree) {
    range(degree, 1, 255, "Reed-Solomon degree");
    int[] divisor = divisor(degree);
    byte[] result = new byte[divisor.length];
    for (int index = 0; index < divisor.length; index++) {
      result[index] = (byte) divisor[index];
    }
    return result;
  }

  private static synchronized int[] divisor(int degree) {
    if (DIVISORS[degree] == null) {
      int[] coefficients = new int[degree + 1];
      coefficients[0] = 1;
      int root = 1;
      for (int factor = 0; factor < degree; factor++) {
        for (int index = factor + 1; index > 0; index--) {
          coefficients[index] ^= multiply(coefficients[index - 1], root);
        }
        root = multiply(root, 2);
      }
      DIVISORS[degree] = coefficients;
    }
    return DIVISORS[degree];
  }

  /** Compute parity for at most one symbol's codewords, with degree 1 through 255. */
  public static byte[] reedSolomonRemainder(byte[] data, int degree) {
    validateBytes(data, "Data");
    range(degree, 1, 255, "Reed-Solomon degree");
    return remainder(data, divisor(degree));
  }

  private static byte[] remainder(byte[] data, int[] divisor) {
    int degree = divisor.length - 1;
    byte[] result = new byte[degree];
    for (byte value : data) {
      int factor = (value ^ result[0]) & 0xFF;
      for (int index = 0; index < degree - 1; index++) {
        result[index] = (byte) (result[index + 1] ^ multiply(divisor[index + 1], factor));
      }
      result[degree - 1] = (byte) multiply(divisor[degree], factor);
    }
    return result;
  }

  /** Split padded data into QR blocks, compute parity, and interleave both portions. */
  public static InterleavedResult interleaveCodewords(byte[] data, int version, String level) {
    Tables.BlockInfo info = Tables.blockInfo(version, level);
    validateBytes(data, "Data codewords");
    if (data.length != info.dataCodewords()) {
      throw invalid("Expected " + info.dataCodewords() + " data codewords; got " + data.length);
    }
    int shortCount = info.blocks() - info.rawCodewords() % info.blocks();
    int shortDataLength = info.rawCodewords() / info.blocks() - info.eccPerBlock();
    int[] generator = divisor(info.eccPerBlock());
    List<CodewordBlock> blocks = new ArrayList<>(info.blocks());
    int offset = 0;
    for (int index = 0; index < info.blocks(); index++) {
      int length = shortDataLength + (index < shortCount ? 0 : 1);
      byte[] blockData = Arrays.copyOfRange(data, offset, offset + length);
      blocks.add(new CodewordBlock(blockData, remainder(blockData, generator)));
      offset += length;
    }
    byte[] result = new byte[info.rawCodewords()];
    int output = 0;
    for (int column = 0; column <= shortDataLength; column++) {
      for (CodewordBlock block : blocks) {
        if (column < block.data.length) {
          result[output++] = block.data[column];
        }
      }
    }
    for (int column = 0; column < info.eccPerBlock(); column++) {
      for (CodewordBlock block : blocks) {
        result[output++] = block.ecc[column];
      }
    }
    if (offset != data.length || output != result.length) {
      throw new IllegalStateException("Inconsistent QR block interleaving length");
    }
    return new InterleavedResult(result, blocks, info.dataCodewords());
  }

  /** The QR data-mask predicate at a module coordinate between 0 and 176. */
  public static boolean maskCondition(int mask, int x, int y) {
    range(mask, 0, 7, "Mask pattern");
    range(x, 0, 176, "Column");
    range(y, 0, 176, "Row");
    return maskAt(mask, x, y);
  }

  private static boolean maskAt(int mask, int x, int y) {
    return switch (mask) {
      case 0 -> (x + y) % 2 == 0;
      case 1 -> y % 2 == 0;
      case 2 -> x % 3 == 0;
      case 3 -> (x + y) % 3 == 0;
      case 4 -> (y / 2 + x / 3) % 2 == 0;
      case 5 -> x * y % 2 + x * y % 3 == 0;
      case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
      default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
    };
  }

  /** Exact SpecQR N1–N4 score of a square matrix from 1 through 177 modules. */
  public static int penaltyScore(boolean[][] matrix) {
    if (matrix == null || matrix.length < 1 || matrix.length > 177) {
      throw invalid("Matrix must be square with 1 to 177 rows");
    }
    for (boolean[] row : matrix) {
      if (row == null || row.length != matrix.length) {
        throw invalid("Matrix rows must have the same length as the matrix");
      }
    }
    return score(matrix);
  }

  private static int score(boolean[][] matrix) {
    int side = matrix.length;
    int penalty = 0;
    int dark = 0;
    for (int line = 0; line < side; line++) {
      penalty += linePenalty(matrix, line, true);
      penalty += linePenalty(matrix, line, false);
      for (int x = 0; x < side; x++) {
        if (matrix[line][x]) {
          dark++;
        }
        if (line + 1 < side
            && x + 1 < side
            && matrix[line][x] == matrix[line][x + 1]
            && matrix[line][x] == matrix[line + 1][x]
            && matrix[line][x] == matrix[line + 1][x + 1]) {
          penalty += 3;
        }
      }
    }
    int total = side * side;
    return penalty + Math.abs(dark * 20 - total * 10) / total * 10;
  }

  private static int linePenalty(boolean[][] matrix, int line, boolean horizontal) {
    int penalty = 0;
    boolean runColor = false;
    int runLength = 0;
    int window = 0;
    for (int index = 0; index < matrix.length; index++) {
      boolean value = horizontal ? matrix[line][index] : matrix[index][line];
      if (index == 0 || value != runColor) {
        if (runLength >= 5) {
          penalty += runLength - 2;
        }
        runColor = value;
        runLength = 1;
      } else {
        runLength++;
      }
      window = ((window << 1) | (value ? 1 : 0)) & 0x7FF;
      if (index >= 10 && (window == 0b10111010000 || window == 0b00001011101)) {
        penalty += 40;
      }
    }
    return penalty + (runLength >= 5 ? runLength - 2 : 0);
  }

  /**
   * Place complete interleaved codewords and apply a selected mask, or pass null to evaluate all
   * eight masks. A tie chooses the lowest mask number.
   */
  public static MatrixResult buildMatrix(
      byte[] codewords, int version, String level, Integer mask) {
    int side = Tables.size(version);
    Tables.validateLevel(level);
    if (mask != null) {
      range(mask, 0, 7, "Mask pattern");
    }
    validateBytes(codewords, "Interleaved codewords");
    int expected = Tables.rawCodewords(version);
    if (codewords.length != expected) {
      throw invalid("Expected " + expected + " interleaved codewords; got " + codewords.length);
    }
    Grid base = new Grid(side);
    base.drawFunctions(version, level);
    base.drawCodewords(codewords);
    Grid best = null;
    int bestMask = 0;
    int bestPenalty = Integer.MAX_VALUE;
    List<MaskPenalty> penalties = new ArrayList<>(mask == null ? 8 : 1);
    int firstMask = mask == null ? 0 : mask;
    int lastMask = mask == null ? 7 : mask;
    for (int candidateMask = firstMask; candidateMask <= lastMask; candidateMask++) {
      Grid candidate = base.masked(level, candidateMask);
      int penalty = score(candidate.modules);
      penalties.add(new MaskPenalty(candidateMask, penalty));
      if (penalty < bestPenalty) {
        best = candidate;
        bestMask = candidateMask;
        bestPenalty = penalty;
      }
    }
    if (best == null) {
      throw new IllegalStateException("No QR mask candidate was evaluated");
    }
    return new MatrixResult(best.modules, bestMask, bestPenalty, penalties);
  }

  /** Equivalent to buildMatrix(codewords, version, level, null). */
  public static MatrixResult buildMatrix(byte[] codewords, int version, String level) {
    return buildMatrix(codewords, version, level, null);
  }

  private static final class Grid {
    private final int side;
    private final boolean[][] modules;
    private final boolean[][] functions;

    private Grid(int side) {
      this.side = side;
      this.modules = new boolean[side][side];
      this.functions = new boolean[side][side];
    }

    private Grid(Grid base) {
      this.side = base.side;
      this.modules = copyMatrix(base.modules);
      this.functions = base.functions;
    }

    private void function(int x, int y, boolean dark) {
      if (x >= 0 && y >= 0 && x < side && y < side) {
        modules[y][x] = dark;
        functions[y][x] = true;
      }
    }

    private void finder(int left, int top) {
      for (int dy = -1; dy <= 7; dy++) {
        for (int dx = -1; dx <= 7; dx++) {
          boolean inside = dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6;
          boolean dark =
              inside
                  && (dx == 0
                      || dx == 6
                      || dy == 0
                      || dy == 6
                      || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4));
          function(left + dx, top + dy, dark);
        }
      }
    }

    private void drawFormat(String level, int mask) {
      int data = (Tables.formatBits(level) << 3) | mask;
      int remainder = data;
      for (int index = 0; index < 10; index++) {
        remainder = (remainder << 1) ^ (((remainder >>> 9) & 1) * 0x537);
      }
      int bits = ((data << 10) | remainder) ^ 0x5412;
      for (int index = 0; index < 6; index++) {
        function(8, index, bit(bits, index));
      }
      function(8, 7, bit(bits, 6));
      function(8, 8, bit(bits, 7));
      function(7, 8, bit(bits, 8));
      for (int index = 9; index < 15; index++) {
        function(14 - index, 8, bit(bits, index));
      }
      for (int index = 0; index < 8; index++) {
        function(side - 1 - index, 8, bit(bits, index));
      }
      for (int index = 8; index < 15; index++) {
        function(8, side - 15 + index, bit(bits, index));
      }
    }

    private void drawFunctions(int version, String level) {
      finder(0, 0);
      finder(side - 7, 0);
      finder(0, side - 7);
      for (int index = 8; index < side - 8; index++) {
        function(index, 6, index % 2 == 0);
        function(6, index, index % 2 == 0);
      }
      int[] positions = Tables.alignmentPositions(version);
      int last = positions.length - 1;
      for (int yi = 0; yi < positions.length; yi++) {
        for (int xi = 0; xi < positions.length; xi++) {
          if ((xi == 0 && yi == 0) || (xi == last && yi == 0) || (xi == 0 && yi == last)) {
            continue;
          }
          for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
              function(
                  positions[xi] + dx,
                  positions[yi] + dy,
                  Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
          }
        }
      }
      drawFormat(level, 0);
      function(8, side - 8, true);
      if (version >= 7) {
        int remainder = version;
        for (int index = 0; index < 12; index++) {
          remainder = (remainder << 1) ^ (((remainder >>> 11) & 1) * 0x1F25);
        }
        int bits = (version << 12) | remainder;
        for (int index = 0; index < 18; index++) {
          int a = side - 11 + index % 3;
          int b = index / 3;
          function(a, b, bit(bits, index));
          function(b, a, bit(bits, index));
        }
      }
    }

    private void drawCodewords(byte[] codewords) {
      int bitIndex = 0;
      for (int right = side - 1; right >= 1; right -= 2) {
        if (right == 6) {
          right = 5;
        }
        for (int vertical = 0; vertical < side; vertical++) {
          int y = ((right + 1) & 2) == 0 ? side - 1 - vertical : vertical;
          for (int x = right; x >= right - 1; x--) {
            if (!functions[y][x]) {
              if (bitIndex < codewords.length * 8) {
                modules[y][x] = bit(codewords[bitIndex / 8] & 0xFF, 7 - bitIndex % 8);
              }
              bitIndex++;
            }
          }
        }
      }
      int remaining = bitIndex - codewords.length * 8;
      if (remaining < 0 || remaining > 7) {
        throw new IllegalStateException("Inconsistent QR data-module count");
      }
    }

    private Grid masked(String level, int mask) {
      Grid candidate = new Grid(this);
      for (int y = 0; y < side; y++) {
        for (int x = 0; x < side; x++) {
          if (!functions[y][x] && maskAt(mask, x, y)) {
            candidate.modules[y][x] = !candidate.modules[y][x];
          }
        }
      }
      candidate.drawFormat(level, mask);
      return candidate;
    }
  }

  private static boolean bit(int value, int index) {
    return ((value >>> index) & 1) != 0;
  }

  private static boolean[][] copyMatrix(boolean[][] matrix) {
    boolean[][] result = new boolean[matrix.length][];
    for (int index = 0; index < matrix.length; index++) {
      result[index] = matrix[index].clone();
    }
    return result;
  }

  private static void validateBytes(byte[] bytes, String label) {
    if (bytes == null) {
      throw invalid(label + " must not be null");
    }
    if (bytes.length > MAX_CODEWORDS) {
      throw invalid(label + " exceeds the maximum QR codeword count");
    }
  }

  private static void range(int value, int lower, int upper, String label) {
    if (value < lower || value > upper) {
      throw invalid(label + " must be an integer from " + lower + " to " + upper);
    }
  }

  private static SpecQrException invalid(String message) {
    return new SpecQrException("INVALID_INPUT", message);
  }
}
