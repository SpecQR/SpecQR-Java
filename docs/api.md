# Java API リファレンス

The public Java API lives in `io.specqr`. Generated results are immutable; mutable arrays are copied at ownership boundaries.

この文書は `0.1.0-rc.1` の Java API を説明します。他言語版とアルゴリズム・主要な契約を共有しますが、メソッド名、型、例外、診断の任意フィールドが完全に同一とは限りません。特に Java の配列、UTF-16、型付き `Options`、GS1 URL adapter の境界を確認してください。

## 一覧

| 型 | 役割 |
| --- | --- |
| `SpecQr` | 生成、計画、単一モード容量 |
| `Options` / `Options.Builder` | immutable オプション / 可変 builder |
| `QrCode` | 完成した QR、codeword、診断、描画 |
| `Plan` / `Capacity` | 生成前の計画 / 1 セグメントの容量 |
| `Segment` / `Segments` | データ・制御セグメント、最適化、bit 列 |
| `Render` / `Render.Pixels` | SVG、PNG、RGBA、data URL |
| `Gs1` | 範囲限定の GS1 ヘルパー。[詳細](gs1.md) |
| `StructuredAppend` | 分割・parity・結合。[詳細](structured-append.md) |
| `Tables` / `Core` | Model 2 の表、Reed–Solomon、配置、mask |
| `SpecQrException` | `IllegalArgumentException` のサブクラス |
| `Main` | CLI の entry point |

`Json` と `Kanji` はパッケージ内の実装で、公開 API ではありません。JSON codec を一般用途のパーサとして提供しません。

## `SpecQr`: 生成と計画

次の各メソッドに、`Options` を省略して既定値を使う overload があります。

```java
QrCode SpecQr.generate(String text, Options options)
QrCode SpecQr.generate(byte[] data, Options options)
QrCode SpecQr.generateSegments(List<Segment> segments, Options options)
Plan SpecQr.estimate(String text, Options options)
Plan SpecQr.estimate(byte[] data, Options options)
Plan SpecQr.analyzeSegments(List<Segment> segments, Options options)
```

- `generate` は必要な最小 Version を選択し、padding、Reed–Solomon、配置、mask 評価まで実行します
- `estimate` / `analyzeSegments` は容量と segment bit 数を計算し、matrix・codeword・画像を作りません
- `generateSegments` / `analyzeSegments` は、手動のモードと境界を保ちます。自動の結合や再分割をしません
- `byte[]` は 0–255 の raw byte として扱います。UTF-8 検証や文字列化はしません。`auto` / `byte` 以外の指定は拒否します
- `Options` を取る overload に `null` は渡せません。省略するか `Options.defaults()` を使います
- `String` と `byte[]` の overload があるため、単なる `generate(null)` は Java コンパイラ上も曖昧です
- `SpecQr.VERSION` は `"0.1.0-rc.1"` です

### 自動モードと決定性

`mode("auto")` と `optimizeSegments(true)` では、numeric / alphanumeric / Kanji / byte の bit 費用を比較し、混合セグメントを選びます。Version 1–9、10–26、27–40 の文字数フィールド幅に応じて計画します。

同じ bit 数なら少ないセグメントを優先し、それでも同じなら numeric → alphanumeric → Kanji → byte の安定した探索順を使います。mask の自動選択は 0–7 を評価し、最小 penalty、同点なら小さい番号です。手動 mask の場合はその候補だけを評価します。

最適化を無効にした `auto` は、入力全体が入る単一モードを numeric → alphanumeric → Kanji → byte の順で選びます。空文字列は byte セグメントになります。高水準の `estimate` は 7,089 scalars を超える場合に巨大な単一シンボル最適化を行わず、単一モードの算術計画へ切り替えます。単一 QR の最大文字容量を超えていても、資源上限内なら実際の bit 数を調べられます。

## `Options`

```java
Options options = Options.builder()
        .errorCorrectionLevel("M")
        .minVersion(1).maxVersion(10)
        .boostErrorCorrection(true)
        .foreground("#112233").background("#ffffff")
        .scale(8).margin(4).printDpi(300.0)
        .build();
Options smaller = options.toBuilder().scale(4).build();
```

| builder メソッド / getter | 既定値 | 値・動作 |
| --- | --- | --- |
| `errorCorrectionLevel(String)` | `"M"` | 大文字 `L`, `M`, `Q`, `H` |
| `mode(String)` | `"auto"` | `auto`, `numeric`, `alphanumeric`, `byte`, `kanji` |
| `version(Integer)` | `null` | 固定 Version 1–40。`null` で自動に戻す |
| `minVersion(int)`, `maxVersion(int)` | 1, 40 | 自動選択範囲。最小 ≤ 最大 |
| `maskPattern(Integer)` | `null` | 0–7、`null` なら自動 |
| `optimizeSegments(boolean)` | `true` | auto の mixed segmentation |
| `boostErrorCorrection(boolean)` | `false` | 選んだ Version 内で ECC を可能な範囲まで強化 |
| `eci(Integer)` | `null` | 0–999999 の ECI assignment |
| `gs1(boolean)` | `false` | element string を検証して FNC1 first を付与 |
| `fnc1Second(String)` | `null` | ASCII 2 桁または英字 1 文字 |
| `structuredAppend(Segment)` | `null` | `Segment.structuredAppend(...)` の低水準 header |
| `margin(int)` | 4 | quiet zone、0 以上の module 数 |
| `scale(int)` | 8 | module ごとの pixel 数、1 以上 |
| `foreground(String)` | `"#000000"` | 前景色 |
| `background(String)` | `"#ffffff"` | 背景色 |
| `printDpi(Double)` | `null` | 印刷診断用。正の有限値 |

getter は引数なしで同じ名前です。`Options.defaults()` は既定値の immutable オブジェクト、`toBuilder()` はそれを引き継ぐ新しい builder です。

固定 `version` は自動選択の `minVersion` / `maxVersion` より優先されます。ただし、範囲そのものの不正値は builder で拒否します。固定 Version を範囲内に含める必要はありません。

ECI・GS1・FNC1 second・Structured Append header は、SpecQR の契約として相互に組み合わせできません。手動セグメントに制御セグメントがある場合も、オプションを付加した結果全体にこの制約を適用します。QR 規格で可能なすべての組み合わせを実装している、という意味ではありません。

ECC boosting はまず要求した ECC で最小 Version を選び、同じ Version のまま L → M → Q → H の順で強化します。より大きい Version へ移動して強化する機能ではありません。Structured Append の高水準分割では boosting は未対応です。

色は builder 時点ではサイズを制限した文字列として保持し、描画形式に応じた検証は renderer が行います。`printDpi` は PNG の DPI メタデータを書き込む設定ではありません。

## `Plan`

`Plan` は record です。単純な容量超過は例外でなく `ok() == false` になります。

| アクセサ | 内容 |
| --- | --- |
| `ok()` | 選択条件で収まるか |
| `version()`, `selectedVersion()` | 成功時の Version。自動範囲で失敗すると `null`。固定 Version の失敗では指定値 |
| `capacityVersion()` | 容量の比較対象。失敗時は固定 Version または範囲の最後 |
| `errorCorrectionLevel()` | 実際に選んだ ECC |
| `requestedErrorCorrectionLevel()` | 要求した ECC |
| `boostedErrorCorrection()` | boosting が行われたか |
| `requiredBits()`, `dataBitLength()` | header / control を含む未 padding の算術 bit 数 |
| `capacityBits()` | 比較対象の data capacity |
| `remainingBits()` | capacity − required。超過時は負 |
| `overflowBits()` | `max(0, -remainingBits)` |
| `capacityUtilization()` | required / capacity。超過時は 1 より大きくなる |
| `segments()` | immutable な計画済みセグメント |
| `diagnostics()` | immutable な診断 map |

```java
Plan tooLarge = SpecQr.estimate("A".repeat(1000),
        Options.builder().version(1).build());
assert !tooLarge.ok();
assert tooLarge.version() == 1;
assert tooLarge.requiredBits() > tooLarge.capacityBits();
```

文字数フィールドが収まらないデータも `Segment.totalBits()` / `Segments.bitLength()` では算術長を得られます。実際の bit 配列を構築する `bits()` は count field と単一シンボル bit 上限を別途検証します。計画は任意の入力を必ず受理するものではなく、不正モード、孤立 surrogate、資源上限違反などは例外です。

## `Capacity`

```java
Capacity c = SpecQr.getCapacity(1, "M", "byte", 0L);
assert c.maxBytes() == 14;
```

overload は `(version)`、`(version, level)`、`(version, level, mode)`、`(version, level, mode, controlBits)` です。省略時は ECC `M`、mode `null`、controlBits `0`。

record のアクセサは `version`、`errorCorrectionLevel`、`size`、`dataCodewords`、`totalCodewords`、`capacityBits`、`mode`、`characterCountBits`、`modeBits`、`controlBits`、`payloadBits`、`maxCharacters`、`maxBytes`。`maximum()` は byte mode なら `maxBytes`、その他なら `maxCharacters` を返します。

mode は `numeric` / `alphanumeric` / `byte` / `kanji` または `null`。`auto` は容量表のモードではありません。`null` では mode 固有の結果も `null` です。controlBits は 0–2^53−1 の算術予約量です。過大に予約すると payload は 0 に切り詰めます。この API は 1 data segment の最大容量を計算し、mixed segmentation や「空のセグメントでも必ず入る」ことを保証しません。byte の最大値は UTF-8 の文字数ではなくバイト数です。

## `Segment`

### factory

| factory | データ・制約 |
| --- | --- |
| `numeric(String)` | ASCII `0`–`9` |
| `alphanumeric(String)` | `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:` のみ |
| `bytes(String)` | 正しく対になった UTF-16 を UTF-8 に変換 |
| `bytes(byte[])` | 任意バイト。入力配列をコピー |
| `kanji(String)` | 内蔵の固定マッピングに含まれる文字 |
| `eci(int)` | assignment 0–999999 |
| `fnc1()` | FNC1 first-position |
| `fnc1Second(String)` | ASCII 2 桁、または `A`–`Z` / `a`–`z` の 1 文字 |
| `structuredAppend(int index, int total, int parity)` | 1 ≤ index ≤ total、2 ≤ total ≤ 16、parity 0–255 |

ASCII 以外の数字を numeric として扱わず、小文字を alphanumeric 用に大文字化しません。通常の単一シンボル API では空のデータセグメントを作れますが、Structured Append の高水準分割は空入力・空セグメントを拒否します。

### Unicode / Kanji / ECI

テキスト入力は Unicode scalar として検証します。孤立した high / low surrogate は `INVALID_INPUT`、補助平面の正しい surrogate pair は 1 scalar です。byte は UTF-8、Kanji は固定版 WHATWG Shift_JIS の最初の対応を用いた 6,953 文字の表で符号化します。OS や JDK の `Shift_JIS` charset に依存せず、全 Unicode 文字を Kanji として扱うものではありません。

ECI はバイトの解釈を示し、変換を実行しません。`eci(26)` は UTF-8 のラベルとして利用できます。別エンコーディングを指定するときは、呼び出し側で作った `byte[]` を渡してください。ECI を Options で指定した auto は Kanji の自動選択を抑えますが、明示的な Kanji セグメントを禁止しません。ECI を付けない UTF-8 の解釈は読み取り側の実装にも依存します。

```java
QrCode qr = SpecQr.generateSegments(List.of(
        Segment.eci(26),
        Segment.bytes("日本語"),
        Segment.eci(3),
        Segment.bytes(new byte[] {(byte) 0xe9})));
```

ECI は繰り返し指定でき、手動境界と遷移を保持します。FNC1 / FNC1 second / Structured Append は先頭・一意で、互いにおよび ECI と併用できません。

### アクセサと bit 列

- `mode()`: 小文字の mode 名
- `text()`: 元テキスト。binary / control では `null`
- `binary()`: raw binary のコピー。text / control では `null`
- `logicalBytes()`: text なら UTF-8、binary なら元バイト、control なら空配列。Kanji でもここは UTF-8
- `count()`: QR header に書く数。byte はバイト数、それ以外の data は scalar 数
- `characterCount()`: text の scalar 数。binary / control は 0
- `byteCount()`: 診断用。Kanji は 2 × scalar 数、それ以外は logical byte 数
- `assignment()`, `applicationIndicator()`, `applicationIndicatorCodeword()`, `index()`, `total()`, `parity()`: 対応する control の情報。それ以外は `null`
- `isControl()`: control か
- `dataBitLength()`: mode / count header を除いた payload / designator の長さ
- `totalBits(version)`: mode / count header 込みの算術長、`long`
- `bits(version)`: fresh な `int[]`。各要素 0 または 1、MSB-first。terminator と padding は含まない

FNC1 second の codeword は 2 桁なら数値 0–99、英字なら ASCII 値 + 100 です。ECI designator の payload 長は assignment により 8 / 16 / 24 bits、FNC1 first は 0、FNC1 second は 8、Structured Append は 16。各々に 4-bit mode indicator が加わります。

`Segment` は値に基づく `equals` / `hashCode` を実装し、配列内容も比較します。

## `Segments`

```java
List<Segment> automatic = Segments.create("ABC123日本語", 4, "auto", true, true);
List<Segment> checked = Segments.normalize(automatic);
long needed = Segments.bitLength(checked, 4);
int[] bits = Segments.bits(checked, 4);
```

`create(input, version, mode, optimize, allowKanji)` は `String` / `byte[]` の overload を持ちます。返すのは data segment のみで、Options の control を付与する facade とは別です。`allowKanji=false` は自動候補だけを制限し、明示 Kanji は引き続き検証・符号化します。`byte[]` では optimize / allowKanji は結果を変えません。

直接の `create` で auto + optimize を指定した場合、7,089 scalars を超える text は拒否します。高水準 `estimate` のような自動 fallback はしません。

`normalize` は immutable snapshot を返し、null、手動数、総 payload、制御順序・併用を検証します。手動のモード境界を結合せず、ECI の反復も保持します。`bitLength` は算術長を返し、`bits` は最大 23,648 data bits と count field を検査してから確保します。

`new Segments.OptimizationTracker(version, allowKanji)` は接頭辞の最小 bit 費用を一定サイズの状態で追跡する可変ヘルパーです。`append(int codePoint)` または scalar 1 個の `append(String)` は、その scalar を追加した接頭辞全体の費用を `long` で返します。列を復元する API や reset はありません。孤立 surrogate、複数 scalar の文字列、100 万 scalar 超は拒否します。複数スレッドで共有しないでください。

## `QrCode`

| アクセサ | 内容 |
| --- | --- |
| `version()` | 1–40 |
| `size()` | quiet zone を除く幅 = 17 + 4 × version |
| `maskPattern()` | 選んだ mask 0–7 |
| `errorCorrectionLevel()` | 選んだ ECC |
| `matrix()` | `boolean[row][column]` の深いコピー。`true` は暗 module |
| `module(x, y)` | `(column, row)` の module。範囲外は例外 |
| `dataCodewords()` | padding 済み・block interleave 前の data codeword コピー |
| `codewords()` | data / ECC を各々 interleave した最終 codeword 列のコピー |
| `errorCorrectionCodewords()` | 最終 ECC 部分のコピー |
| `segments()` | 制御を含む immutable list |
| `diagnostics()` | 再帰的に immutable な診断 map |
| `options()` | 生成に使った immutable Options |

描画メソッドは `toSvg()`、`toPng()`、`toPixels()`、`toSvgDataUrl()`、`toPngDataUrl()`。それぞれ `Options` を取る overload があります。

```java
QrCode qr = SpecQr.generate("https://example.com/");
Options renderOptions = qr.options().toBuilder().scale(4).build();
byte[] png = qr.toPng(renderOptions);
```

描画用 Options の ECC / Version / mask / mode を変えても、既存 matrix は再符号化されません。描画には margin、scale、foreground、background を使います。元の `options()` / `diagnostics()` は変更しません。

## `Render`

`Render.toSvg(matrix[, options])`、`toPng`、`toPixels`、`toSvgDataUrl`、`toPngDataUrl` は、独立した `boolean[][]` も描画できます。入力は 1–177 の正方形である必要がありますが、それが正しい QR かは検証しません。行の `null`、非正方形、Options の `null` を拒否します。

`Render.Pixels` は `width()`、`height()`、`pixels()` を持ち、`pixels()` は row-major / 各 pixel RGBA 4 byte のコピーです。各 channel は `byte & 0xff` として読みます。alpha は非乗算の 8-bit 値です。

描画後の一辺は `(matrix.length + 2 * margin) * scale`。raster は一辺 2,048 以下かつ 4,194,304 pixels 以下です。PNG は 8-bit RGBA、filter 0、stored DEFLATE を含む zlib stream、CRC / Adler32 を使います。`java.desktop` と ImageIO は不要ですが、PNG は高圧縮ではありません。

### 色と data URL

`Render.parseColor(String)` は `int[4]` の RGBA を返し、次だけを認めます。前後空白と大小文字は正規化します。

- `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`
- `black`, `white`, `transparent`

PNG / pixels もこの規則に従います。`red`、`rgb(...)`、`hsl(...)` などは raster では `INVALID_COLOR` です。SVG は任意の bounded 文字列を fill 属性へ XML エスケープして渡し、不正な XML 文字を拒否します。CSS 値の正当性や安全性をすべて検証するものではありません。SVG に他の CSS 表現を使うと contrast を評価できない場合があります。

`Render.contrastRatio(int[] a, int[] b)` は各 4 要素の RGB 相対輝度から比を算出します。alpha の合成を行う関数ではありません。診断は透明性を別途警告します。

PNG data URL は Base64、SVG data URL は UTF-8 の percent-encoding です。SVG の大きさは予算式で制限し、最大 8,388,608 characters、SVG data URL は最大 33,554,432 characters の保守的な予算判定を行います。構成によっては実出力が上限に達する前に拒否します。

## 診断

診断は `Map<String, Object>` で、ネストした list / map も変更不可です。必要な値は `Number`、`String`、`Boolean`、list / map として扱い、nullable フィールドを考慮してください。公開結果へ格納される診断は、64 段の深さ・100 万ノードまでの freeze budget で保護します。

主要なグループ:

- 計画 / 生成: `phase`, `renderPlanned`, `maskEvaluated`, `codewordsBuilt`
- 選択: `version`, `size`, `versionSelection`, `versionSelectionReason`
- ECC: `errorCorrectionLevel`, `requestedErrorCorrectionLevel`, `boostedErrorCorrection`
- mode / control: `mode`, `controlSegments`, `eciAssignmentNumber`, `fnc1`, `gs1`, `gs1Validation`, `fnc1Second`, `structuredAppend`
- data: `segments`, `dataBitLength`, `capacityBits`, `remainingBits`, `capacityUtilization`, `inputBytes`
- 描画の目安: `quietZone`, `colors`, `print`, `warnings`
- 生成後のみ: `maskPattern`, `maskPenalty`, `maskPenalties`, `maskSelectionReason`, `dataCodewords`, `errorCorrectionCodewords`, `totalCodewords`

`inputBytes` は control を除く logical byte 数なので、Kanji でも UTF-8 の長さです。一方、segment の `byteCount` は Kanji の 2-byte 表現を数えます。

`warnings` の要素は `code`, `severity`, `message`, `details` を持ちます。主なコードは `QUIET_ZONE_TOO_SMALL`、`COLOR_CONTRAST_UNKNOWN`、`COLOR_CONTRAST_LOW`、`COLOR_CONTRAST_MODERATE`、`COLOR_ALPHA_USED`、`CAPACITY_NEAR_LIMIT`、`PRINT_MODULE_TOO_SMALL`、`SCAN_RISK` です。quiet zone 4 modules、contrast 4.5 / 7、印刷 module 0.25 mm などは診断の目安であり、読取保証や業界規格の認証値ではありません。

**診断は計画・生成時の Options についての情報です。** 描画時オーバーライド後の見た目を記述するものではなく、`renderPlanned` も `false` です。画像は呼び出した renderer で初めて作られ、資源上限や形式ごとの色検証はそこで実行されます。

## 低水準 `Tables` / `Core`

一般の生成には `SpecQr` を使います。低水準 API は教育、差分検証、診断、制御した bitstream の構築向けです。引数を検証しますが、任意の codeword が意味のある QR payload を持つかは判断しません。

### `Tables`

- `validateVersion(int)`, `validateLevel(String)`
- `size(version)`: quiet zone を含まない一辺
- `rawCodewords(version)`: data + ECC の総数。remainder bits を含まない
- `dataCodewords(version, level)`: header / padding を含む data 側容量
- `blockInfo(version, level)`: `BlockInfo(blocks, eccPerBlock, rawCodewords, dataCodewords)`
- `alignmentPositions(version)`: 新しい整列済み `int[]`
- `countBits(mode, version)`: data mode の count field 幅

| mode | V1–9 | V10–26 | V27–40 |
| --- | ---: | ---: | ---: |
| numeric | 10 | 12 | 14 |
| alphanumeric | 9 | 11 | 13 |
| byte | 8 | 16 | 16 |
| kanji | 8 | 10 | 12 |

### `Core`

- `padDataBits(int[] bits, int version, String level)` → `byte[]`: 0/1 を検証し、最大 4-bit terminator、byte alignment、交互 `0xEC` / `0x11` pad を付与
- `gfMultiply(int left, int right)` → `int`: 両引数 0–255、GF(256)、既約多項式 `0x11D`
- `gfPow(int base, int exponent)` → `int`: base 0–255、非負 exponent
- `reedSolomonDivisor(int degree)` → `byte[]`: degree 1–255、最高次の 1 を含む降べき順係数
- `reedSolomonRemainder(byte[] data, int degree)` → `byte[]`: 最大 3,706 codewords、degree 1–255
- `interleaveCodewords(byte[] data, int version, String level)` → `InterleavedResult`: その Version / ECC に正確に一致する padded data 長が必要
- `maskCondition(int mask, int x, int y)` → `boolean`: mask 0–7、x / y 0–176
- `penaltyScore(boolean[][] matrix)` → `int`: 1–177 正方形の SpecQR N1–N4 penalty
- `buildMatrix(byte[] codewords, int version, String level[, Integer mask])` → `MatrixResult`: `rawCodewords` に正確に一致する interleaved 列が必要。mask 省略 / `null` は全候補評価

`InterleavedResult` は `codewords()`、`errorCorrectionCodewords()`、`blocks()`、`dataCodewords()`、`errorCorrectionCodewordCount()`、`totalCodewords()`。`CodewordBlock` は `data()` / `ecc()`。`MatrixResult` は `matrix()`、`maskPattern()`、`penalty()`、`maskPenalties()` を返し、`MaskPenalty` は `(maskPattern, penalty)` の record です。配列はすべてコピー、リストは変更不可です。

## 例外と資源上限

`SpecQrException` は `IllegalArgumentException` のサブクラスで、`code()` に machine-readable code を持ちます。英語の例外 message の全文一致には依存しないでください。

| code | 主な原因 |
| --- | --- |
| `INVALID_INPUT` | null、形状、範囲、孤立 surrogate、資源制限の一部 |
| `INVALID_VERSION` | Version 範囲、最小 / 最大の矛盾 |
| `INVALID_MODE` | 非対応 mode、mode とデータの不一致、制御併用 |
| `INVALID_ECI` | assignment の範囲 |
| `INVALID_GS1` | element string、GS1、GS1 と非対応制御の併用 |
| `INVALID_COLOR` | raster 色、SVG の不正 XML 文字 |
| `INVALID_OUTPUT` | CLI の出力形式 |
| `DATA_TOO_LONG` | QR 容量、count field、payload / segment 上限 |

GS1 validator は、これとは別に詳細な `GS1_*` issue を結果に返します。[GS1](gs1.md) を参照してください。呼び出し元の iterator が投げた例外、I/O、VM のメモリ不足などをすべて `SpecQrException` に変換する契約ではありません。

| 対象 | 上限・補足 |
| --- | --- |
| 一般の payload | text は 1,000,000 Unicode scalars、binary は 1,000,000 bytes |
| UTF-16 の早期サイズ検査 | text 長 2,000,000 code units |
| 手動セグメント | 16,384 個、合計 payload 1,000,000 units |
| 単一シンボル自動最適化 | 7,089 scalars |
| bit 配列の materialization | 23,648 bits と各 count field |
| QR matrix | 21–177 modules。一般 renderer / penalty は 1–177 正方形を受付 |
| raster | 一辺 2,048、総 4,194,304 pixels |
| GS1 | 1,000,000 UTF-16 code units の入力 / aggregate text、16,384 要素・component の範囲 |
| CLI binary file | 1,000,000 bytes |
| CLI JSON file | 4,000,000 bytes、正しい UTF-8 |

Structured Append は選んだ Version / ECC / maxSymbols に基づく、これより小さい事前上限も設けます。上限内でも同時生成数や入力内容によってメモリ・CPU を消費します。未信頼な大量リクエストにはアプリケーション側の追加制限が必要です。

## CLI の JSON セグメント

`--segments FILE` は UTF-8 の JSON array を受け付けます。

```json
[
  {"mode": "eci", "assignmentNumber": 26},
  {"mode": "numeric", "text": "1234567890"},
  {"mode": "byte", "text": "こんにちは"}
]
```

data mode は `numeric` / `alphanumeric` / `kanji` で `text`、`byte` で `text` または `bytes` のどちらか 1 個を指定します。`bytes` は 0–255 の整数 array です。

```json
[
  {"mode": "structured-append", "index": 1, "total": 2, "parity": 3},
  {"mode": "byte", "bytes": [0, 1, 2, 255]}
]
```

その他の control は `{"mode":"fnc1"}`、`{"mode":"fnc1-second","applicationIndicator":"01"}` です。未知フィールド、未知 mode、`text` と `bytes` の併記、非整数 byte、範囲外値を拒否します。JSON parser は重複 key、64 段を超えるネスト、100,000 を超える値ノードも拒否します。手動 control の先頭・組み合わせ制約は Java API と共通です。

低水準 Structured Append header の parity / total は呼び出し側が一貫させます。この JSON を使って 1 シンボルを生成しただけで、完全なセットの検証や残りのシンボルの作成は行いません。

## Java 向けの設計と互換性

- 公開 API は static facade、immutable class / record、明示的な builder を使用します。他言語の汎用 option object をそのまま受け付けません
- `String.length()` は UTF-16 code unit 数です。payload scalar 数、UTF-8 byte 数、Kanji の診断 byte 数とは区別してください
- `byte[]` は符号付きですが、符号化する値は 8-bit のビットパターンです
- `matrix()` は毎回深いコピーです。結果を繰り返し参照するだけなら `module(x, y)` を使えます
- 完成した生成結果・セグメント・Options は並行 read / render に利用できます。builder / OptimizationTracker と、呼び出し中の外部入力配列の同時変更は共有しないでください
- URL の Unicode host 処理は Java の IDNA2003。ブラウザの WHATWG URL / UTS #46 と完全一致しません
- 描画や diagnostics の便利 API 名まで各言語版に一致することは目指していません。明示した差分と固定した source baseline を確認してください
