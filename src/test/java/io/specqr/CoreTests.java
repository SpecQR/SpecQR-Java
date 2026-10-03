package io.specqr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Dependency-free core regression tests; golden hashes are from pinned SpecQR JS. */
public final class CoreTests {
  private int assertions;

  private CoreTests() {}

  /** Run with or without -ea; every check is an explicit AssertionError. */
  public static int run() {
    CoreTests suite = new CoreTests();
    suite.tables();
    suite.padding();
    suite.fieldAndReedSolomon();
    suite.allVersionsAndMasks();
    suite.penaltyRules();
    suite.immutability();
    suite.invalidInputs();
    return suite.assertions;
  }

  public static void main(String[] args) {
    System.out.println("CoreTests: " + run() + " assertions passed");
  }

  private void tables() {
    equal(Tables.size(1), 21, "version 1 side");
    equal(Tables.size(40), 177, "version 40 side");
    equal(Tables.rawCodewords(1), 26, "version 1 codewords");
    equal(Tables.rawCodewords(40), 3706, "version 40 codewords");
    equal(Tables.dataCodewords(40, "L"), 2956, "maximum data capacity");
    int[] v1 = {19, 16, 13, 9};
    for (int i = 0; i < 4; i++) {
      equal(Tables.dataCodewords(1, "LMQH".substring(i, i + 1)), v1[i], "version 1 capacity");
    }
    ints(Tables.alignmentPositions(1), new int[0], "version 1 alignment");
    ints(Tables.alignmentPositions(2), new int[] {6, 18}, "version 2 alignment");
    ints(Tables.alignmentPositions(7), new int[] {6, 22, 38}, "version 7 alignment");
    ints(
        Tables.alignmentPositions(32),
        new int[] {6, 34, 60, 86, 112, 138},
        "version 32 special alignment");
    ints(
        Tables.alignmentPositions(40),
        new int[] {6, 30, 58, 86, 114, 142, 170},
        "version 40 alignment");
    String[] modes = {"numeric", "alphanumeric", "byte", "kanji"};
    int[][] expected = {{10, 12, 12, 14}, {9, 11, 11, 13}, {8, 16, 16, 16}, {8, 10, 10, 12}};
    int[] versions = {9, 10, 26, 27};
    for (int mode = 0; mode < modes.length; mode++) {
      for (int version = 0; version < versions.length; version++) {
        equal(
            Tables.countBits(modes[mode], versions[version]),
            expected[mode][version],
            "count-bit version boundary");
      }
    }
  }

  private void padding() {
    bytes(
        Core.padDataBits(new int[0], 1, "H"),
        bytes(0, 236, 17, 236, 17, 236, 17, 236, 17),
        "empty padding");
    bytes(
        Core.padDataBits(new int[] {1, 0, 1}, 1, "H"),
        bytes(160, 236, 17, 236, 17, 236, 17, 236, 17),
        "partial byte padding");
    for (int length = 0; length <= 72; length++) {
      int[] bits = new int[length];
      Arrays.fill(bits, 1);
      byte[] actual = Core.padDataBits(bits, 1, "H");
      equal(actual.length, 9, "padded length");
      for (int index = 0; index < length; index++) {
        equal((actual[index / 8] >>> (7 - index % 8)) & 1, 1, "data bit preserved");
      }
      int terminated = length + Math.min(4, 72 - length);
      int boundary = (terminated + 7) / 8 * 8;
      for (int index = length; index < boundary; index++) {
        equal((actual[index / 8] >>> (7 - index % 8)) & 1, 0, "terminator and alignment zero");
      }
      for (int index = boundary / 8; index < actual.length; index++) {
        equal(
            actual[index] & 255,
            (index - boundary / 8) % 2 == 0 ? 236 : 17,
            "alternating pad byte");
      }
    }
    int[] maximum = new int[2956 * 8];
    Arrays.fill(maximum, 1);
    byte[] full = Core.padDataBits(maximum, 40, "L");
    equal(full.length, 2956, "maximum padding output length");
    for (byte value : full) {
      equal(value & 255, 255, "full data capacity retained");
    }
    fails("DATA_TOO_LONG", () -> Core.padDataBits(new int[73], 1, "H"));
    int[] oversized = new int[100000];
    oversized[0] = -1;
    fails("DATA_TOO_LONG", () -> Core.padDataBits(oversized, 40, "L"));
  }

  private void fieldAndReedSolomon() {
    MessageDigest fieldHash = sha256();
    // Independent polynomial convolution and long division over GF(2).
    for (int left = 0; left < 256; left++) {
      for (int right = 0; right < 256; right++) {
        int product = 0;
        for (int bit = 0; bit < 8; bit++) {
          if ((right & (1 << bit)) != 0) {
            product ^= left << bit;
          }
        }
        for (int bit = 14; bit >= 8; bit--) {
          if ((product & (1 << bit)) != 0) {
            product ^= 0x11D << (bit - 8);
          }
        }
        int actual = Core.gfMultiply(left, right);
        equal(actual, product, "GF multiplication");
        fieldHash.update((byte) actual);
      }
    }
    equal(
        hex(fieldHash),
        "003d1a609783d2740b9b3f00b0cd9e43e42c4f3eedc5ff54ec1709996d52e1e0",
        "all GF products match pinned SpecQR");
    equal(Core.gfMultiply(0x53, 0xCA), 0x8F, "GF known product");
    for (int base = 0; base < 256; base++) {
      int value = 1;
      for (int exponent = 0; exponent < 256; exponent++) {
        equal(Core.gfPow(base, exponent), value, "GF exponentiation");
        value = Core.gfMultiply(value, base);
      }
    }
    equal(Core.gfPow(1, Integer.MAX_VALUE), 1, "bounded large exponent");
    bytes(
        Core.reedSolomonDivisor(7),
        bytes(1, 127, 122, 154, 164, 11, 68, 117),
        "RS degree 7 divisor");
    for (int degree : new int[] {1, 7, 10, 18, 30, 255}) {
      byte[] data = new byte[40];
      for (int i = 0; i < data.length; i++) {
        data[i] = (byte) i;
      }
      byte[] parity = Core.reedSolomonRemainder(data, degree);
      equal(parity.length, degree, "RS parity length");
      bytes(
          Core.reedSolomonRemainder(concat(data, parity), degree),
          new byte[degree],
          "RS zero syndrome");
      bytes(Core.reedSolomonRemainder(new byte[0], degree), new byte[degree], "RS empty data");
    }
    // Pinned JS SHA-256 over generator then remainder for degrees 1..255.
    // Data[i]=(i*73+degree*19)&255, with 40 data bytes for each degree.
    MessageDigest reedSolomonHash = sha256();
    for (int degree = 1; degree <= 255; degree++) {
      byte[] data = new byte[40];
      for (int i = 0; i < data.length; i++) {
        data[i] = (byte) (i * 73 + degree * 19);
      }
      reedSolomonHash.update(Core.reedSolomonDivisor(degree));
      reedSolomonHash.update(Core.reedSolomonRemainder(data, degree));
    }
    equal(
        hex(reedSolomonHash),
        "e05728e7fe4cad32e3f51f85538d9e5183af74928c1a73e3682754a3d9ce57aa",
        "all RS degrees match pinned SpecQR");
  }

  private void allVersionsAndMasks() {
    MessageDigest codewordHash = sha256();
    MessageDigest matrixHash = sha256();
    MessageDigest penaltyHash = sha256();
    for (int version = 1; version <= 40; version++) {
      for (String level : List.of("L", "M", "Q", "H")) {
        for (int pattern = 0; pattern < 3; pattern++) {
          Tables.BlockInfo info = Tables.blockInfo(version, level);
          byte[] data = new byte[info.dataCodewords()];
          for (int i = 0; i < data.length; i++) {
            data[i] =
                (byte)
                    (pattern == 0
                        ? 0
                        : pattern == 1 ? 255 : i * 73 + version * 19 + level.charAt(0));
          }
          Core.InterleavedResult encoded = Core.interleaveCodewords(data, version, level);
          byte[] codewords = encoded.codewords();
          codewordHash.update(codewords);
          equal(encoded.totalCodewords(), info.rawCodewords(), "total block length");
          equal(encoded.dataCodewords(), info.dataCodewords(), "data block length");
          equal(
              encoded.errorCorrectionCodewordCount(),
              info.blocks() * info.eccPerBlock(),
              "parity count");
          equal(
              encoded.errorCorrectionCodewords().length,
              info.blocks() * info.eccPerBlock(),
              "parity bytes");
          bytes(
              encoded.errorCorrectionCodewords(),
              Arrays.copyOfRange(codewords, info.dataCodewords(), codewords.length),
              "interleaved parity order");
          equal(encoded.blocks().size(), info.blocks(), "block count");
          int offset = 0;
          int previousLength = 0;
          for (Core.CodewordBlock block : encoded.blocks()) {
            byte[] blockData = block.data();
            byte[] parity = block.ecc();
            bytes(
                blockData,
                Arrays.copyOfRange(data, offset, offset + blockData.length),
                "source data block order");
            check(blockData.length >= previousLength, "short blocks precede long blocks");
            if (previousLength > 0) {
              check(blockData.length - previousLength <= 1, "block lengths differ by at most one");
            }
            equal(parity.length, info.eccPerBlock(), "per-block parity length");
            bytes(
                Core.reedSolomonRemainder(concat(blockData, parity), info.eccPerBlock()),
                new byte[parity.length],
                "per-block syndrome");
            offset += blockData.length;
            previousLength = blockData.length;
          }
          equal(offset, data.length, "all input bytes partitioned");
          int bestMask = 0;
          int bestPenalty = Integer.MAX_VALUE;
          Core.MatrixResult automatic = Core.buildMatrix(codewords, version, level);
          equal(automatic.maskPenalties().size(), 8, "auto evaluates eight masks");
          for (int mask = 0; mask < 8; mask++) {
            Core.MatrixResult fixed = Core.buildMatrix(codewords, version, level, mask);
            boolean[][] matrix = fixed.matrix();
            equal(matrix.length, Tables.size(version), "matrix side");
            check(matrix[matrix.length - 8][8], "dark module is fixed");
            equal(fixed.maskPattern(), mask, "forced mask retained");
            equal(fixed.maskPenalties().size(), 1, "forced mask evaluates one candidate");
            equal(fixed.penalty(), Core.penaltyScore(matrix), "reported penalty recomputes");
            equal(automatic.maskPenalties().get(mask).maskPattern(), mask, "candidate mask order");
            equal(
                automatic.maskPenalties().get(mask).penalty(),
                fixed.penalty(),
                "auto and forced penalties match");
            hashMatrix(matrixHash, matrix);
            penaltyHash.update(
                (version
                        + ","
                        + level
                        + ","
                        + pattern
                        + ","
                        + mask
                        + ","
                        + fixed.maskPattern()
                        + ","
                        + fixed.penalty()
                        + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            if (fixed.penalty() < bestPenalty) {
              bestPenalty = fixed.penalty();
              bestMask = mask;
            }
          }
          equal(automatic.maskPattern(), bestMask, "lowest-penalty mask; lowest number wins ties");
          equal(automatic.penalty(), bestPenalty, "minimum automatic penalty");
          check(
              Arrays.deepEquals(
                  automatic.matrix(),
                  Core.buildMatrix(codewords, version, level, bestMask).matrix()),
              "automatic winning matrix");
          hashMatrix(matrixHash, automatic.matrix());
          penaltyHash.update(
              (version
                      + ","
                      + level
                      + ","
                      + pattern
                      + ",8,"
                      + automatic.maskPattern()
                      + ","
                      + automatic.penalty()
                      + "\n")
                  .getBytes(StandardCharsets.US_ASCII));
        }
      }
    }
    // Generated directly with node:crypto and the unmodified SpecQR JS baseline
    // at 15ad15e5c770ea0e39072f8f88b2733018f02ffd. Iteration order is versions
    // 1..40, levels L/M/Q/H, patterns 0..2, masks 0..7 then auto (8).
    // Data patterns are all zero, all 255, and (i*73+version*19+level.charCodeAt(0))&255.
    // Hash streams are interleaved bytes; newline-joined ASCII matrix rows with
    // no final newline per matrix; and "version,level,pattern,maskOption,selectedMask,penalty\n".
    equal(
        hex(codewordHash),
        "d0c5fcd42af2170ab1ec29705c617ffaa1c05164d5f7bc5f8f9153b4520b4fc7",
        "all 480 codeword layouts match SpecQR");
    equal(
        hex(matrixHash),
        "2c26ac59d742275cb6af5447ff0577cfab5e13b7a84fc02d2e8c505c1f179c7b",
        "all 4320 matrices match SpecQR");
    equal(
        hex(penaltyHash),
        "d6166135ca4a5c8e8c15ffd245a10890f76a5572169674ab6ca0c0947fb995e2",
        "all 4320 mask penalties match SpecQR");
  }

  private void penaltyRules() {
    penalty(new String[] {"11111", "01010", "10101", "01010", "10101"}, 23);
    penalty(new String[] {"11010", "11010", "00101", "01010", "10101"}, 3);
    String[] finder = new String[11];
    finder[0] = "10111010000";
    for (int i = 1; i < finder.length; i++) {
      finder[i] = i % 2 == 0 ? "10101010101" : "01010101010";
    }
    penalty(finder, 40);
    String[] black = new String[10];
    Arrays.fill(black, "1111111111");
    penalty(black, 503);
    penalty(new String[] {"0"}, 100);
    penalty(new String[] {"1"}, 100);
    for (int mask = 0; mask < 8; mask++) {
      for (int y = 0; y < 21; y++) {
        for (int x = 0; x < 21; x++) {
          boolean expected =
              switch (mask) {
                case 0 -> (x + y) % 2 == 0;
                case 1 -> y % 2 == 0;
                case 2 -> x % 3 == 0;
                case 3 -> (x + y) % 3 == 0;
                case 4 -> (y / 2 + x / 3) % 2 == 0;
                case 5 -> x * y % 2 + x * y % 3 == 0;
                case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
                default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
              };
          check(Core.maskCondition(mask, x, y) == expected, "mask predicate");
        }
      }
    }
  }

  private void immutability() {
    byte[] source = new byte[19];
    Core.InterleavedResult result = Core.interleaveCodewords(source, 1, "L");
    source[0] = 7;
    equal(result.blocks().get(0).data()[0], 0, "caller data mutation is isolated");
    result.codewords()[0] = 8;
    result.blocks().get(0).data()[0] = 9;
    result.blocks().get(0).ecc()[0] = 10;
    result.errorCorrectionCodewords()[0] = 11;
    equal(result.codewords()[0], 0, "interleaved output copy");
    equal(result.blocks().get(0).data()[0], 0, "block data copy");
    equal(result.blocks().get(0).ecc()[0], 0, "block parity copy");
    equal(result.errorCorrectionCodewords()[0], 0, "parity output copy");
    try {
      result.blocks().clear();
      throw new AssertionError("block list is mutable");
    } catch (UnsupportedOperationException expected) {
      assertions++;
    }
    Core.MatrixResult built = Core.buildMatrix(new byte[26], 1, "L", 0);
    boolean[][] matrix = built.matrix();
    matrix[0][0] = false;
    matrix[1] = null;
    check(built.matrix()[0][0], "matrix row copy");
    check(built.matrix()[1] != null, "matrix outer-array copy");
    try {
      built.maskPenalties().clear();
      throw new AssertionError("penalty list is mutable");
    } catch (UnsupportedOperationException expected) {
      assertions++;
    }
    byte[] divisor = Core.reedSolomonDivisor(7);
    divisor[0] = 0;
    equal(Core.reedSolomonDivisor(7)[0], 1, "divisor cache is protected");
    int[] positions = Tables.alignmentPositions(7);
    positions[0] = 0;
    equal(Tables.alignmentPositions(7)[0], 6, "alignment result is a fresh array");
  }

  private void invalidInputs() {
    for (int version : new int[] {Integer.MIN_VALUE, -1, 0, 41, Integer.MAX_VALUE}) {
      fails("INVALID_VERSION", () -> Tables.size(version));
      fails("INVALID_VERSION", () -> Tables.rawCodewords(version));
      fails("INVALID_VERSION", () -> Tables.alignmentPositions(version));
      fails("INVALID_VERSION", () -> Tables.countBits("byte", version));
      fails("INVALID_VERSION", () -> Tables.blockInfo(version, "L"));
      fails("INVALID_VERSION", () -> Core.buildMatrix(new byte[26], version, "L"));
    }
    for (String level : new String[] {null, "", "l", "X", "LL", " L"}) {
      fails("INVALID_INPUT", () -> Tables.validateLevel(level));
      fails("INVALID_INPUT", () -> Tables.dataCodewords(1, level));
      fails("INVALID_INPUT", () -> Core.padDataBits(new int[0], 1, level));
      fails("INVALID_INPUT", () -> Core.buildMatrix(new byte[26], 1, level));
    }
    for (String mode : new String[] {null, "", "BYTE", "eci", "unknown"}) {
      fails(mode == null ? "INVALID_INPUT" : "INVALID_MODE", () -> Tables.countBits(mode, 1));
    }
    fails("INVALID_INPUT", () -> Core.padDataBits(null, 1, "L"));
    for (int value : new int[] {-1, 2, Integer.MAX_VALUE}) {
      fails("INVALID_INPUT", () -> Core.padDataBits(new int[] {value}, 1, "L"));
    }
    for (int value : new int[] {-1, 256, Integer.MAX_VALUE}) {
      fails("INVALID_INPUT", () -> Core.gfMultiply(value, 1));
      fails("INVALID_INPUT", () -> Core.gfMultiply(1, value));
      fails("INVALID_INPUT", () -> Core.gfPow(value, 1));
    }
    fails("INVALID_INPUT", () -> Core.gfPow(2, -1));
    for (int degree : new int[] {-1, 0, 256, Integer.MAX_VALUE}) {
      fails("INVALID_INPUT", () -> Core.reedSolomonDivisor(degree));
      fails("INVALID_INPUT", () -> Core.reedSolomonRemainder(new byte[0], degree));
    }
    fails("INVALID_INPUT", () -> Core.reedSolomonRemainder(null, 7));
    fails("INVALID_INPUT", () -> Core.reedSolomonRemainder(new byte[3707], 7));
    fails("INVALID_INPUT", () -> Core.interleaveCodewords(null, 1, "L"));
    for (int length : new int[] {0, 18, 20, 3707}) {
      fails("INVALID_INPUT", () -> Core.interleaveCodewords(new byte[length], 1, "L"));
    }
    fails("INVALID_INPUT", () -> Core.buildMatrix(null, 1, "L"));
    for (int length : new int[] {0, 25, 27, 3707}) {
      fails("INVALID_INPUT", () -> Core.buildMatrix(new byte[length], 1, "L"));
    }
    for (int mask : new int[] {-1, 8, Integer.MAX_VALUE}) {
      fails("INVALID_INPUT", () -> Core.buildMatrix(new byte[26], 1, "L", mask));
      fails("INVALID_INPUT", () -> Core.maskCondition(mask, 0, 0));
    }
    for (int coordinate : new int[] {-1, 177, Integer.MAX_VALUE}) {
      fails("INVALID_INPUT", () -> Core.maskCondition(0, coordinate, 0));
      fails("INVALID_INPUT", () -> Core.maskCondition(0, 0, coordinate));
    }
    fails("INVALID_INPUT", () -> Core.penaltyScore(null));
    fails("INVALID_INPUT", () -> Core.penaltyScore(new boolean[0][]));
    fails("INVALID_INPUT", () -> Core.penaltyScore(new boolean[178][]));
    fails("INVALID_INPUT", () -> Core.penaltyScore(new boolean[][] {null}));
    fails("INVALID_INPUT", () -> Core.penaltyScore(new boolean[][] {{true, false}}));
  }

  private void penalty(String[] rows, int expected) {
    boolean[][] matrix = new boolean[rows.length][rows.length];
    boolean[][] transpose = new boolean[rows.length][rows.length];
    for (int y = 0; y < rows.length; y++) {
      for (int x = 0; x < rows.length; x++) {
        matrix[y][x] = rows[y].charAt(x) == '1';
        transpose[x][y] = matrix[y][x];
      }
    }
    equal(Core.penaltyScore(matrix), expected, "N1-N4 penalty fixture");
    equal(Core.penaltyScore(transpose), expected, "transposed penalty fixture");
  }

  private void check(boolean condition, String label) {
    assertions++;
    if (!condition) {
      throw new AssertionError(label);
    }
  }

  private void equal(int actual, int expected, String label) {
    check(actual == expected, label + ": expected " + expected + ", got " + actual);
  }

  private void equal(String actual, String expected, String label) {
    check(actual.equals(expected), label + ": expected " + expected + ", got " + actual);
  }

  private void bytes(byte[] actual, byte[] expected, String label) {
    check(Arrays.equals(actual, expected), label);
  }

  private void ints(int[] actual, int[] expected, String label) {
    check(Arrays.equals(actual, expected), label);
  }

  private void fails(String code, Runnable action) {
    try {
      action.run();
    } catch (SpecQrException expected) {
      equal(expected.code(), code, "failure code");
      return;
    }
    throw new AssertionError("Expected SpecQrException " + code);
  }

  private static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      result[i] = (byte) values[i];
    }
    return result;
  }

  private static byte[] concat(byte[] first, byte[] second) {
    byte[] result = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String hex(MessageDigest hash) {
    return HexFormat.of().formatHex(hash.digest());
  }

  private static void hashMatrix(MessageDigest hash, boolean[][] matrix) {
    for (int y = 0; y < matrix.length; y++) {
      if (y != 0) {
        hash.update((byte) '\n');
      }
      for (boolean value : matrix[y]) {
        hash.update((byte) (value ? '1' : '0'));
      }
    }
  }
}
