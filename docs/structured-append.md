# Structured Append

`StructuredAppend` splits text, bytes, or manual segments into a deterministic set of 2–16 QR symbols and validates decoded parts before joining them.

Structured Append は、複数の QR symbol に sequence / total / parity の header を付ける機能です。SpecQR-Java は自動分割、低水準 header、parity 計算、復号済みパートの結合を提供します。**QR を画像から読み取る decoder は含みません。** 読み取りライブラリが Structured Append metadata を公開するかは別途確認してください。

## 文字列を分割する

```java
import io.specqr.*;
import java.nio.file.Files;
import java.nio.file.Path;

Options options = Options.builder()
        .version(1)
        .errorCorrectionLevel("M")
        .build();
StructuredAppend.Result set = StructuredAppend.generate("A".repeat(100), options, 16);
for (int i = 0; i < set.symbols().size(); i++) {
    Files.write(Path.of("part-" + (i + 1) + ".png"), set.symbols().get(i).toPng());
}
System.out.println("Symbols: " + set.total() + ", parity: " + set.parity());
```

公開 overload は `generate(String)` / `generate(byte[])`、それぞれに `Options`、`Options + maxSymbols`、`Options + maxSymbols + DiagnosticOptions` を受け取る形があります。既定は `Options.defaults()`、maxSymbols `16`、`DiagnosticOptions.defaults()` です。

`Result` は次を返します。

| accessor | 内容 |
| --- | --- |
| `symbols()` | immutable な `List<QrCode>` |
| `total()` | 2–16 |
| `parity()` | 全入力から計算した 0–255 の XOR |
| `inputLength()` | text は scalar 数、binary は byte 数、manual は元 segment 数 |
| `byteLength()` | text は UTF-8、binary は元 byte、manual は logical byte 合計 |
| `diagnostics()` | immutable な分割・各 symbol の診断 |

`Result` のコンストラクタも symbol 数 2–16 と total の一致、parity、inputLength / byteLength の範囲を検証します。生成された各 `QrCode` は通常の結果と同じ描画 API、matrix、codeword、diagnostics を利用できます。総数と parity は全 symbol 共通、先頭 segment の index は 1 から total です。

## 選択・分割の規則

1. 固定 Version、または minVersion から maxVersion を小さい順に調べる
2. 各候補で 20-bit Structured Append header を含め、最大 maxSymbols 個に収まるか確認する
3. 先頭から、残りが空にならない範囲で最大の fitting prefix を切り出す
4. 2 個以上の完全な分割が得られる最小 Version を選ぶ

全 symbol は同じ Version と ECC を使います。mask は既定では symbol ごとに評価し、Options に固定 mask を指定した場合は共通です。目的は選択範囲内の最小 Version で分割することで、symbol 総面積や総数を全組み合わせで最適化するものではありません。

文字列は Unicode scalar の境界だけで分割し、surrogate pair や UTF-8 の途中では切りません。binary は byte 境界で分割します。auto / optimize は各候補 prefix の bit 費用を計算します。

**1 個に収まるデータを必ず 2 個へ分ける API ではありません。** 候補 Version で、header 付きの全入力が 1 個に入る場合はその候補をスキップします。全候補で 1 個に収まれば `INVALID_INPUT`、最大数までに分けられなければ `DATA_TOO_LONG` になります。通常の単一 QR が欲しい場合は `SpecQr.generate`、分割境界を完全に制御する場合は低水準 header を使ってください。

空入力、maxSymbols が 2–16 外、ECI、GS1 / FNC1 first、FNC1 second、事前指定の Structured Append header、ECC boosting は高水準分割で拒否します。これらは本実装の対応範囲で、QR 規格のすべての機能組み合わせを実装したものではありません。

## 手動セグメントの分割

```java
import java.util.List;

List<Segment> segments = List.of(
        Segment.numeric("12345678901234567890"),
        Segment.bytes("a".repeat(100)),
        Segment.kanji("漢字"));
StructuredAppend.Result set = StructuredAppend.generateSegments(
        segments, Options.builder().version(2).build(), 16);
```

`generateSegments(List<Segment>[, Options[, int maxSymbols[, DiagnosticOptions]]])` は caller のモードと境界を保持します。

- numeric / alphanumeric / Kanji segment は **分割しません**。1 segment が許容 Version の symbol に収まらなければ失敗します
- `Segment.bytes(String)` は scalar 境界で分割可能
- `Segment.bytes(byte[])` は byte 境界で分割可能
- 異なる元 segment を勝手に統合しない
- 空 list、空 data segment、null、control segment は拒否
- 手動分割では Options の mode は `auto`、optimizeSegments は既定 `true` が必要。実際に手動モードを再最適化する、という意味ではない

大きい numeric segment を任意位置で分けたい場合は、先に複数の `Segment.numeric(...)` を作るか、高水準 text 分割を使ってください。

## Parity の契約

```java
int textParity = StructuredAppend.calculateParity("こんにちは");
int byteParity = StructuredAppend.calculateParity(new byte[] {1, 2, 3});
int manualParity = StructuredAppend.calculateSegmentsParity(segments);
```

parity は元データの各 byte を XOR した 8-bit 値です。

- text: UTF-8
- binary: raw bytes
- manual: 各 segment の logical bytes を連結したもの
- manual Kanji: **元 text の UTF-8**。Kanji 用 Shift_JIS の 2-byte 値を使う契約ではない
- control: 高水準 parity 対象の manual 入力にはそもそも認めない

parity は順序を完全には検出せず、容易に衝突します。認証・署名・暗号化・強い integrity check ではありません。信頼境界を越えるデータには、上位プロトコルで MAC / 署名等を扱ってください。

## 低水準 header

```java
int parity = StructuredAppend.calculateParity("AB");
QrCode first = SpecQr.generateSegments(List.of(
        Segment.structuredAppend(1, 2, parity),
        Segment.bytes("A")));
QrCode second = SpecQr.generateSegments(List.of(
        Segment.structuredAppend(2, 2, parity),
        Segment.bytes("B")));
```

または `Options.builder().structuredAppend(Segment.structuredAppend(...)).build()` で 1 個の symbol に付加できます。すでに手動 header がある場合は Options でも重ねて付けないでください。

公開 index は **1-based**、total は 2–16、parity は 0–255。bitstream では index − 1 と total − 1 を各 4 bits、parity を 8 bits として 4-bit mode indicator の後に入れます。合計 20 bits です。

低水準生成は、他の symbol の存在、総データの parity、欠番、重複、一式の Version / ECC の統一を検証しません。利用側がセット全体を管理します。header は先頭かつ一意で、ECI / FNC1 first / second と併用できません。

## 復号済みパートの結合

```java
int parity = StructuredAppend.calculateParity("AB");
StructuredAppend.MergeResult merged = StructuredAppend.merge(List.of(
        new StructuredAppend.Part(2, 2, parity, "B"),
        new StructuredAppend.Part(1, 2, parity, "A")));
assert merged.text().equals("AB");
assert merged.bytes() == null;
```

`Part(int index, int total, int parity, Object data)` の data は `String` または `byte[]`。コンストラクタで index 1–16、total 2–16、index ≤ total、parity 0–255 を検証します。String は正しい UTF-16 と payload 上限、binary は byte 数の上限も検証します。binary はコンストラクタと `data()` で defensive copy します。複数パート間の一致、欠番・重複、実データの parity は `merge()` がセット全体で確認します。

`merge(List<Part>)` は:

1. 1–16 の index、2–16 の total、0–255 の parity を確認
2. total / parity の一致、同じ index の重複がないことを確認
3. 1 から total までの全 index が存在することを確認
4. 全 data が String、または全 data が byte[] であることを確認
5. canonical bytes の XOR が parity と一致することを確認
6. index 順に並べて結合

入力 list の順は問いません。欠けたパートの推測、重複の自動除去、文字列 / binary の暗黙変換はしません。文字列は正しい UTF-16 として検証します。復号側が元テキストと異なる正規化・変換をした場合には parity が一致しないことがあります。

`MergeResult` は `data()`, `total()`, `parity()`, `parts()`, `diagnostics()` に加え、型別 accessor の `text()` / `bytes()` を持ちます。対象でない型の accessor は null を返し、binary accessor はコピーを返します。`parts()` は `(index, total, parity, dataType, byteLength)` の `PartInfo` list です。`MergeResult` を直接構築する場合も、total 2–16、parity 0–255、parts 数と total の一致、data の型・正しい UTF-16・payload 上限をコピー前に検証します。ただし、このコンストラクタを呼ぶだけでパート単位の完全性や parity が再計算されるわけではありません。完全な結合検証には `merge()` を使ってください。

## 診断と上限

```java
StructuredAppend.DiagnosticOptions summary =
        StructuredAppend.DiagnosticOptions.defaults();
StructuredAppend.DiagnosticOptions full =
        StructuredAppend.DiagnosticOptions.full();
```

`DiagnosticOptions(String splitUnits, String symbolResults)` は次を受け付けます。

- splitUnits: `summary`（既定）または `full`
- symbolResults: `output`（既定）または `diagnostics`

`full()` は `("full", "diagnostics")` です。manual の full は分割単位ごとの metadata を展開するため大きくなります。summary はセグメント数と symbol 数を基準にした bounded 診断です。現在の Java API はどちらの場合も `Result.symbols()` に完成した `QrCode` を返します。symbolResults=`diagnostics` は decoder support の警告を追加する設定であり、matrix を省略する planning-only モードではありません。

集合診断には `version`, `errorCorrectionLevel`, `versionSelection`, `versionSelectionReason`, `total`, `parity`, `byteLength`, `inputLength`, `maxSymbols`, `splitStrategy`, `symbols`, `warnings` があります。manual では `segmentCount`, `splitUnitCount`, `splitUnitsDetail`、full なら `splitUnits` を追加します。各 symbol の metadata には input / byte offset、bit 数、capacity、remainingBits、mask、sequenceIndex / sequenceTotal / sequenceIndicator などを含みます。

高水準 inputStart / inputLength は text では scalar、binary では byte を数え、byteStart / byteLength は UTF-8 または raw byte を数えます。manual には元 segment と分割 unit の対応を記録します。すべて 0-based の offset で、header の 1-based index とは別です。

最大数ちょうどを使うと `STRUCTURED_APPEND_MAX_SYMBOLS_NEAR_LIMIT`、診断 detail によって `STRUCTURED_APPEND_DECODER_SUPPORT_VARIES` を返します。decoder support は全 reader に保証できる機能ではありません。

通常の 100 万 payload units / 16,384 manual segments より先に、Version / ECC / maxSymbols に応じた物理容量の事前上限を適用します。極端に長いデータを全 scalar のオブジェクトへ展開せず、過大入力を早期拒否します。merge は最大 16 parts、合計 100 万 scalars または bytes に制限します。描画予算は各 symbol の描画時に別途適用します。

資源上限、Unicode、制御併用、immutable 結果の共通規則は [API](api.md) も確認してください。
