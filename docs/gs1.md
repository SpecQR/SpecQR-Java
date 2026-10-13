# GS1 / Digital Link

`Gs1` は、範囲を限定した 50 個の AI の catalog、element string のヘルパー、check digit、ローカルでの Digital Link 解析を提供します。完全な GS1 validator、resolver、認証サービスではありません。

SpecQR-Java は、固定した SpecQR のカタログに含まれる **50 個の具体的な AI** を扱います。GS1 全仕様、すべての業種・用途規則、GS1 Digital Link の圧縮・resolver・canonicalization は実装しません。このライブラリによる生成・検証は GS1 認証を意味しません。ヘルパーは DNS 解決やネットワーク通信を行いません。

## 基本例

```java
import io.specqr.*;
import java.util.List;

List<Gs1.Element> elements = Gs1.fromHumanReadable(
        "(01)04912345678904(10)100%(17)251231");
String raw = Gs1.toElementString(elements);
assert raw.equals("010491234567890410100%\u001d17251231");
assert Gs1.parseElementString(raw).elements().equals(elements);

QrCode qr = SpecQr.generate(raw, Options.builder().gs1(true).build());
String uri = Gs1.createDigitalLink(elements, "https://example.com/products");
assert uri.equals("https://example.com/products/01/04912345678904/10/100%25?17=251231");
assert Gs1.normalizeDigitalLink(uri).equals(uri);
```

raw GS1 QR は `gs1(true)`、Digital Link QR は通常の URL として `SpecQr.generate(uri)` に渡します。両者は同じ入力表現ではありません。HRI の括弧付き文字列をそのまま `gs1(true)` に渡すと拒否します。

`Gs1.Element` は `record Element(String ai, String value)` です。要素を受け取るメソッドは、`Element` または文字列の `ai` / `value` を持つ `Map` の `Iterable<?>` を受け付けます。先頭ゼロを維持するため、数値型の value は受け付けません。`Element` を構築しただけでは値は検証されず、ヘルパーを通す時に検証します。結果のリストは変更不可です。

## 対応 AI

| AI | 許可する値 |
| --- | --- |
| 00 | 18 桁、SSCC check digit |
| 01, 02 | 14 桁、GTIN check digit |
| 10, 21, 22 | 印字可能 ASCII、1–20 文字 |
| 11, 12, 13, 15, 16, 17 | 6 桁の数字 |
| 20 | 2 桁の数字 |
| 30, 37 | 1–8 桁の数字 |
| 240, 241, 400 | 印字可能 ASCII、1–30 文字 |
| 410–415 | 13 桁の数字 |
| 420 | 印字可能 ASCII、1–20 文字 |
| 422, 424, 425, 426 | 3 桁の数字 |
| 3100–3105, 3200–3205 | 6 桁の数字 |
| 91–99 | 印字可能 ASCII、1–90 文字 |

すべての値で括弧と ASCII GS (`U+001D`) を禁止します。日付は 6 桁という形を検証するだけで、実在する暦日かは判断しません。GLN の check digit、実在する国コード、用途別の組み合わせ規則も検証しません。AI 90、3106、3206 など表にない AI は未対応です。

`Gs1.getSupportedAis()` は固定カタログ順の immutable list、`getAiInfo(String ai)` は 1 件の metadata または未対応時に `null` を返します。`getSupportedGs1Ais()` / `getGs1AiInfo()` は同じ動作の別名です。

`AiInfo` の accessor は `ai`、`label`、`length`、`valueKind`、`checkDigitRule`、`digitalLinkRole`、`separator`、`digitalLinkPathForPrimary`。`length()` は `AiLength(type, exact, min, max)` で、`isVariable()` を使えます。適用しない値は `null`、値の種類は `numeric` / `text`、check rule は `gtin` / `sscc` / `none` です。

## HRI / element string

| メソッド | 動作 |
| --- | --- |
| `fromHumanReadable(String)` | `(AI)value` の HRI を検証して `List<Element>` にする |
| `parseHumanReadable(String)` | 上記の別名 |
| `toElementString(Iterable<?>)` | 非最終の可変長要素の後に ASCII GS を挿入 |
| `createElementString(Iterable<?>)` | 上記の別名 |
| `parseElementString(String)` | raw を検証して `ElementStringParseResult` を返す |
| `toHumanReadable(Iterable<?>)` | 検証した要素を `(AI)value` にする |
| `toHumanReadable(String)` | raw を解析して HRI にする |

separator の定数は `Gs1.FNC1_SEPARATOR`、別名 `GS1_FNC1_SEPARATOR` で、値は `"\u001d"` です。QR の FNC1 first-position **制御セグメント**と、payload 内の **ASCII GS 文字**は区別してください。

`ElementStringParseResult` は `elements()` と `hasSeparators()` を持ちます。固定長要素は separator 不要です。先頭・固定長要素の直後・末尾などの不正な位置にある separator を拒否します。要素文字列は空にできません。

### raw 解析の曖昧性

raw パーサは、最後の可変長値の末尾が「対応する固定長 AI + その固定長の値」に見える場合、separator が欠けている可能性があるとして拒否する保守的なヒューリスティックを持ちます。候補末尾の値の意味までは調べません。例えば `10ABC17251231` と `10ABC17XXXXXX` はどちらも拒否します。

このため、任意の合法な構造化要素について、`toElementString` → `parseElementString` が必ず同じ値に戻るとは限りません。構造化された要素を持っている場合はそれを保持してください。曖昧な raw 文字列から、意図した境界を推測して補完する機能ではありません。

## 検証結果

```java
Gs1.ValidationOptions options = new Gs1.ValidationOptions(
        "element-string", true, false);
Gs1.ValidationResult checked = Gs1.validateElements(elements, options);
if (!checked.ok()) {
    for (Gs1.ValidationIssue issue : checked.errors()) {
        System.out.println(issue.code() + ": " + issue.message());
    }
}
```

`validateElements(Iterable<?>[, ValidationOptions])` と `validateElementString(String[, ValidationOptions])` は `ValidationResult` を返します。既定は `ValidationOptions.defaults()` = context `"element-string"`、collectAllErrors `true`、allowUnsupportedAi `false`。

- `context`: `element-string` または `digital-link`。null は既定相当
- `collectAllErrors`: 要素リスト検証で複数エラーを収集するか
- `allowUnsupportedAi`: `true` は未対応オプションとして拒否。未知 AI を許可する escape hatch ではない

`ValidationResult` は `ok`、`elements`、`hasSeparators`、`errors`、`warnings`。失敗時の elements / hasSeparators は `null` です。要素リストには raw separator がないため、成功でも hasSeparators は `null` です。

要素検証の `context="digital-link"` は、primary AI 00 / 01 / 414 のいずれかが存在することを確認します。URI 内の重複や path placement まで確認するわけではありません。それらには `validateDigitalLink` を使ってください。raw 検証の context / collectAllErrors は解析自体を変えず、パーサは最初のエラーで停止します。

`ValidationIssue` は `code`, `message`, `reason`, `ai`, `value`, `key`, `offset`, `elementIndex`, `expected`, `count` を持ち、多くは任意の nullable field です。`offset` は Java の **UTF-16 code unit** の位置、elementIndex は 0-based です。機械的な分岐には code / reason を使い、英語 message 全文や他言語版との完全一致に依存しないでください。

代表的な issue code は `GS1_INVALID_INPUT`、`GS1_UNSUPPORTED_AI`、`GS1_INVALID_LENGTH`、`GS1_INVALID_CHARSET`、`GS1_INVALID_CHECK_DIGIT`、`GS1_MISSING_SEPARATOR`、`GS1_UNEXPECTED_SEPARATOR`、`GS1_INVALID_DIGITAL_LINK_PLACEMENT`、`GS1_DUPLICATE_AI`、`GS1_DIGITAL_LINK_INVALID_URI`、`GS1_DIGITAL_LINK_FRAGMENT_NOT_ALLOWED`、`GS1_INVALID_PERCENT_ENCODING`、`GS1_DIGITAL_LINK_UNKNOWN_QUERY` です。

通常の parse / create helper は `SpecQrException`（`code() == "INVALID_GS1"`）を投げ、validator はそれをエラー結果に変換します。利用側の iterator、メモリ確保などに由来する無関係な例外は捕捉する契約ではありません。

## Check digit

- `calculateCheckDigit(String body)`: modulo-10 の check digit を 1 文字の String として返す
- `validateCheckDigit(String value)`: 本体と check digit が必要。形が正しく check digit のみ不一致なら `false`
- `calculateGs1CheckDigit` / `validateGs1CheckDigit`: 上記の別名
- `calculateGtinCheckDigit`, `appendGtinCheckDigit`, `validateGtinCheckDigit`: GTIN の本体 7 / 11 / 12 / 13 桁、完成値 8 / 12 / 13 / 14 桁
- `calculateSsccCheckDigit`, `appendSsccCheckDigit`, `validateSsccCheckDigit`: SSCC の本体 17 桁、完成値 18 桁

数値形式や桁数が不正な場合は `false` ではなく例外です。これらは check digit を扱い、発番された番号か、対象物が実在するかは判定しません。

## FNC1 と literal percent

高水準 `SpecQr.generate(raw, Options.builder().gs1(true).build())` は raw element string を検証して FNC1 first を先頭に追加します。binary を高水準 GS1 として受け付けません。

GS1 / FNC1 second のテキストに literal `%` がある場合:

- `auto`: テキスト全体を byte mode に切り替え、literal percent を維持
- 明示 `alphanumeric`: 曖昧な符号化を避けるため `INVALID_MODE`
- 明示 `byte`: literal percent を維持

低水準 `generateSegments` は payload をそのまま扱い、HRI / element string の意味検証や percent の自動エスケープを行いません。FNC1 が有効な QR の alphanumeric では `%` が separator、`%%` が literal `%` です。エスケープは呼び出し側で管理します。

```java
QrCode manual = SpecQr.generateSegments(List.of(
        Segment.fnc1(),
        Segment.alphanumeric("010491234567890410100%%")));
```

手動 FNC1 の存在は、そのデータ全体が GS1 の業務規則を満たすことを保証しません。FNC1 first / second を ECI、Structured Append、お互いと組み合わせることは本実装ではできません。

## Digital Link の作成・解析

```java
Gs1.DigitalLinkOptions options = Gs1.DigitalLinkOptions
        .forBaseUrl("https://example.com/items")
        .withPrimaryAi("01")
        .withPathAis(List.of("10", "21"));
String uri = Gs1.createDigitalLink(elements, options);
Gs1.DigitalLinkParseResult parsed = Gs1.parseDigitalLink(uri);
Gs1.DigitalLinkValidationResult checked = Gs1.validateDigitalLink(uri,
        Gs1.DigitalLinkOptions.defaults().withUnknownQuery("reject"));
```

公開メソッド:

- `createDigitalLink(Iterable<?> elements, String baseUrl)`
- `createDigitalLink(ElementStringParseResult result, String baseUrl)`
- `createDigitalLink(Iterable<?> elements, DigitalLinkOptions options)`
- `parseDigitalLink(String uri[, DigitalLinkOptions])`
- `validateDigitalLink(String uri[, DigitalLinkOptions])`
- `normalizeDigitalLink(String uri[, DigitalLinkOptions])`

`DigitalLinkOptions` は `(String baseUrl, String primaryAi, List<String> pathAis, String unknownQuery, boolean normalize, String mode)` の record です。既定は `(null, null, null, "preserve", false, "specqr-deterministic")`。便利な factory は `defaults()` / `forBaseUrl(url)`、コピー操作は `withPrimaryAi(ai)` / `withPathAis(list)` / `withUnknownQuery(policy)` です。

作成には baseUrl が必須です。primaryAi が null なら作成では `01`、解析では path の 00 / 01 / 414 から自動検出します。primary は path に置きます。primary 01 の qualifier は 10 / 21 / 22、primary 00 / 414 には後続の path qualifier がありません。

- pathAis が null: 配置可能な qualifier を入力順に path へ
- 空 list: primary 以外をすべて query へ
- 部分集合: 指定した配置可能な qualifier だけ path へ
- その他の要素: AI、次いで value 順の query へ
- 例外として、値が `.` / `..` だけの qualifier は常に query へ

GS1 AI の重複は path と query を通じて拒否します。未知の 2–4 桁の数値 query key は「unknown query」ではなく、未対応 AI として拒否します。

`DigitalLinkParseResult` は `elements`, `primary`, `pathElements`, `queryElements`, `unknownQuery` を持ちます。elements は path → query の順なので、入力要素列から URI を作って再解析すると順序が変わることがあります。unknownQuery は `UnknownQuery(key, value)` の list です。

`unknownQuery="preserve"` では AI 以外の未知 query pair の順序と重複を保持し、`"reject"` は拒否します。query の `+` は空白、percent-encoded NUL は String 内の NUL として復号します。`DigitalLinkValidationResult` は `ok`, `result`, `errors`, `warnings`。失敗時の result は null です。HTTP には `GS1_DIGITAL_LINK_HTTP`、未知 query を保持した場合は `GS1_DIGITAL_LINK_UNKNOWN_QUERY_PRESERVED` の warning が付きます。

## 正規化と dot-only value の保持

`normalizeDigitalLink` の mode は `specqr-deterministic` だけです。これはこの実装の決定的な表現であり、GS1 公式 canonical URI という意味ではありません。配置可能な qualifier を path に置き、GS1 query を並べ、未知 query は相対順序を維持して末尾へ追加します。正規化は pathAis で配置を固定する機能ではありません。

validator の `normalize=true` は未対応としてエラーを返します。正規化を行いたい場合は `normalizeDigitalLink` を明示的に呼んでください。

ブラウザの URL 正規化は path の `.` / `..` を削除し、データを失う可能性があります。本実装は **primary AI 以後の dot segment を消さずに解析し、作成・正規化時には dot-only qualifier を query へ移動**します。percent-encoded の dot も復号後の値を維持します。

```java
String dot = "https://example.com/01/04912345678904/10/..";
assert Gs1.parseDigitalLink(dot).elements().get(1).value().equals("..");
assert Gs1.normalizeDigitalLink(dot)
        .equals("https://example.com/01/04912345678904?10=..");
```

`pathAis` で明示的に path を希望しても、dot-only 値は query に配置します。文字列値 `%2e` は dot とは異なるデータであり、`%252e` としてエスケープされます。primary より前の通常 prefix / 明示 baseUrl に含まれる dot segment は通常の path として正規化します。

この処理は、データを失わないための意図的な WHATWG / 基準実装との差分です。未加工の dot path URI をいったんブラウザの `URL` に通すと、このヘルパーに到着する前に情報が失われ得ます。受け取った元文字列を直接渡し、配布する URL は作成 / 正規化結果を使ってください。

## URL adapter の範囲と Java 固有の差分

`java.net.URI` をパーサとして使わず、ローカルの HTTP(S) adapter を実装しています。普通の HTTP(S) 入力と多くの special-URL 正規化を扱いますが、ブラウザ WHATWG URL への完全準拠は保証しません。

- scheme / host を小文字化し、既定ポートを除く
- 前後の C0 制御文字・空白、URL 中の tab / CR / LF を除く
- HTTP(S) の slash 補完、authority / path の backslash 変換。query 内の backslash はデータとして保持
- userinfo をエスケープする。認証や通信は行わない
- 短縮形・16 進・8 進・単一整数の IPv4 を dotted decimal に正規化
- 角括弧付き IPv6 と IPv4-mapped 形式を扱い、最長のゼロ列を圧縮。zone identifier は拒否
- 非空 fragment は拒否。空 `#` は入力として受け付け、作成時は保持し、正規化結果からは除く
- baseUrl の非空 query は拒否。空 `?` は受け付け、作成した query で置き換える
- path 値は `%HH` 構文と UTF-8 を厳密に復号
- query は寛容な form decoding。正しくない UTF-8 を置換する。低水準 parse では未知 query 内の不正 percent 構文を保持する場合がある
- validate / normalize は未知 query も含めて不正 `%HH` 構文を拒否。ただし、有効な escape から復号した UTF-8 が置換されたという理由だけでは拒否しない
- URL 入力の孤立 surrogate は URL adapter の scalar-value 処理で U+FFFD に置換。QR テキスト入力の厳格な UTF-16 検証とは別契約

Unicode host は Java の `IDN.toASCII` による **IDNA2003 / Unicode 3.2** です。raw Unicode の変換では `ALLOW_UNASSIGNED` を有効にしません。一方、ASCII の ACE（`xn--`）label は `IDN.toUnicode(..., IDN.ALLOW_UNASSIGNED)` の round-trip 検証を行います。不正な ACE を拒否しますが、ブラウザで有効な IDNA2008 deviation label も拒否する場合があります。新しい Unicode scalar を表す有効な ACE は、この flag により raw Unicode 形式が拒否される場合でも通ることがあります。通常の ASCII non-ACE label は browser 風の禁止文字検査を行い、完全な DNS 名検証は行いません。この基本方針は [Java 17 IDN の仕様](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/IDN.html) に基づきます。

実装と Node WHATWG URL で確認した差分例:

| 入力 host | Java adapter | Node WHATWG |
| --- | --- | --- |
| `faß.de` | `fass.de` | `xn--fa-hia.de` |
| `ς.gr` | `xn--4xa.gr` | `xn--3xa.gr` |
| `a` + U+200C + `b.com` | `ab.com` | 拒否 |
| `a` + U+200D + `b.com` | `ab.com` | 拒否 |
| `😀.com` | 拒否 | `xn--e28h.com` |
| `xn--fa-hia.de`（ACE の ß） | 拒否 | `xn--fa-hia.de` |
| `xn--3xa.gr`（ACE の final sigma） | 拒否 | `xn--3xa.gr` |
| `xn--e28h.test`（ACE の emoji） | `xn--e28h.test` | `xn--e28h.test` |

`xn--a`、`xn--abc-`、`xn--a-ecp` のような不正 ACE は拒否します。`é.com`、`例え.テスト` のような一般的な名前は punycode になります。ASCII 表記ならすべて IDNA 制限を回避できるわけではありません。Java とブラウザで同じ host になる保証が必要なアプリケーションでは、承認した ASCII non-ACE hostname を使うか、実際に使う ACE hostname を両側で個別に検証してください。到達性、DNS の正当性、同形文字攻撃、URL の信頼性をこのヘルパーで判定しないでください。URL 差分検証の手順と区分は [GS1 differential audit](../tools/gs1-conformance/README.md) を参照してください。

## 資源上限

入力文字列は最大 1,000,000 UTF-16 code units、要素数は 16,384。要素 iterable の AI / value 合計テキスト量にも 1,000,000 の work budget を適用します。path / query component と出力にも上限があります。無限 iterator を最後まで走査せず上限で停止しますが、iterator 自体が長時間停止することまでは防げません。公開定数は `MAX_INPUT_CHARACTERS` と `MAX_ELEMENTS` です。

GS1 helper が受理した入力でも QR 容量や render 上限は別です。用途ごとの仕様確認と、実際に利用する reader / printer での検証を行ってください。

## URL serialization compatibility (2026-10-05)

base URL の空 fragment `#` は作成時に保持します。正規化は従来どおり空 fragment を除去し、非空 fragment は拒否します。 限定した URL 出力互換性の拡張であり、通常の QR 符号化・公開 API・runtime dependency は変更しません。既存の dot 値・NUL・IDNA・診断方針を保持します。[固定 corpus と再現手順](../tools/url-serialization/README.md) を参照してください。
