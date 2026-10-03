# SpecQR-Java

SpecQR-Java is a dependency-free Java QR Code Model 2 generator with Kanji, ECI, GS1 helpers, Structured Append, SVG and PNG output. It requires Java 17 or later and uses only `java.base` at runtime.

SpecQR のアルゴリズムと契約を Java 向けに実装したライブラリです。別言語の実行環境、ネイティブライブラリ、外部サービス、他の QR ライブラリのラッパーは使いません。数値・英数字・Kanji・UTF-8・バイナリを扱い、生成前の容量計算から画像出力まで Java だけで実行できます。

現在の版は **`0.1.0-rc.1`（初期リリース候補）**です。ソースからのビルドを利用方法とし、stable release、stable tag、Maven Central 公開はまだ提供していません。依存関係に追加するための Maven 座標はありません。

## ビルドと実行

フル JDK 17 以上を用意し、リポジトリのルートで実行します。Maven、Gradle、ネットワークアクセスは通常のビルド・自己テストに不要です。

```sh
java tools/Build.java test
java tools/VerifyPackage.java
java -jar dist/specqr-java-0.1.0-rc.1.jar --help
java -jar dist/specqr-java-0.1.0-rc.1.jar --format png --output hello.png "Hello, QR!"
java -cp dist/specqr-java-0.1.0-rc.1.jar examples/Basic.java
```

生成物は次の 2 個です。

- `dist/specqr-java-0.1.0-rc.1.jar`: 実行可能 JAR / ライブラリ
- `dist/specqr-java-0.1.0-rc.1-sources.jar`: ソース JAR

`java tools/Build.java` はビルドのみ実行します。`java tools/VerifyPackage.java` は再ビルドの再現性、JAR の構成、モジュール依存、リポジトリ外の classpath / module / CLI 利用を確認します。同じソースと同じ JDK toolchain による繰り返しビルドを byte-identical にする設計で、異なる JDK compiler major 間の実行 JAR の byte 一致は保証しません。コンパイラには `--release 17`、UTF-8、`-Xlint:all -Werror` を指定しています。公開パッケージと JPMS モジュール名は共に `io.specqr` です。通常の classpath でも利用できます。モジュールとして CLI を起動する場合は `java --module-path dist/specqr-java-0.1.0-rc.1.jar --module io.specqr/io.specqr.Main --help` を使います。ビルド用コードには JDK のコンパイラ API が必要ですが、完成したライブラリ・CLI のランタイム依存は `java.base` だけです。`java.desktop`、AWT、ImageIO は使用しません。

### 対応環境の方針

最低 Java 17。CI の検証対象は **Linux / macOS / Windows × Eclipse Temurin 17 / 21 / 25** です。この一覧は検証対象の宣言であり、全組み合わせの成功を示すものではありません。実行済みの範囲と証拠は [検証](docs/verification.md) を確認してください。

17・21・25 の LTS 区分は 2026-10-03 に [Adoptium のサポート情報](https://adoptium.net/support) と [Oracle の Java SE ロードマップ](https://www.oracle.com/java/technologies/java-se-support-roadmap.html) で確認しています。JDK 自体のサポート期間・ライセンスと、このライブラリの検証状況は別です。新しい JDK や他の VM について、未実行の環境を検証済みとは扱いません。

## 最小のライブラリ例

```java
import io.specqr.Options;
import io.specqr.QrCode;
import io.specqr.SpecQr;
import java.nio.file.Files;
import java.nio.file.Path;

class Hello {
    public static void main(String[] args) throws Exception {
        Options options = Options.builder()
                .errorCorrectionLevel("Q")
                .scale(8)
                .margin(4)
                .build();
        QrCode qr = SpecQr.generate("こんにちは、SpecQR!", options);
        Files.writeString(Path.of("hello.svg"), qr.toSvg());
        Files.write(Path.of("hello.png"), qr.toPng());
        System.out.println("Version: " + qr.version());
        System.out.println("Mask: " + qr.maskPattern());
        System.out.println(qr.diagnostics().get("warnings"));
    }
}
```

`Hello.java` として保存すると、次のコマンドで実行できます。

```sh
java -cp dist/specqr-java-0.1.0-rc.1.jar Hello.java
```

`QrCode` は immutable です。配列を返すアクセサはコピーを返し、セグメントのリストと診断のネストしたコレクションは変更できません。完成した結果を複数スレッドで共有できます。`Options.Builder` とストリーム用 `Segments.OptimizationTracker` は可変なので、共有せずに利用してください。

## 対応する機能

- QR Code Model 2、Version 1–40、ECC L / M / Q / H、mask 0–7
- numeric / alphanumeric / Kanji / UTF-8 byte / raw binary、手動・自動 mixed segmentation
- ECI、FNC1 first / second、低水準 Structured Append header
- `estimate()` / `analyzeSegments()`、単一モード容量表、同一 Version 内の ECC boosting
- 対応範囲を限定した 50 個の GS1 AI、element string、HRI、check digit、Digital Link
- text / binary / manual segment の Structured Append 分割、parity 計算、結合
- SVG、RGBA PNG、RGBA ピクセル配列、SVG / PNG data URL、CLI
- Reed–Solomon、配置、mask 採点などの低水準 API

Micro QR、rMQR、QR の読み取り、ロゴ合成、ブラウザ / GUI オブジェクトは対象外です。GS1 全仕様の検証、GS1 認証、Digital Link resolver も提供しません。用途に適した実機スキャンと印刷検証は利用側で必要です。

## 生成前の計画と手動セグメント

```java
import io.specqr.*;
import java.util.List;

Options options = Options.builder().maxVersion(10).errorCorrectionLevel("Q").build();
Plan plan = SpecQr.estimate("注文1234567890", options);
if (plan.ok()) {
    System.out.println(plan.version() + ": " + plan.remainingBits() + " bits free");
}

QrCode mixed = SpecQr.generateSegments(List.of(
        Segment.eci(26),
        Segment.numeric("1234567890"),
        Segment.bytes("こんにちは")));

int bytes = SpecQr.getCapacity(1, "M", "byte").maxBytes(); // 14
```

計画は画像・Reed–Solomon・mask を構築しません。単に入らない入力は `Plan.ok() == false` と実際の必要 bit 数を返し、生成時は `DATA_TOO_LONG` になります。ただし、不正な入力・オプションや資源上限違反は計画時にも例外になります。

Java の `String` は UTF-16 ですが、QR byte セグメントには UTF-8 を使います。孤立 surrogate は拒否し、置換文字へ黙って変換しません。Kanji は固定した WHATWG Shift_JIS 由来の **6,953 マッピング**を内蔵し、実行環境の charset provider に依存しません。ECI は後続バイトの解釈を指定するだけで、文字コード変換は行いません。

## CLI

```sh
java -jar dist/specqr-java-0.1.0-rc.1.jar --format svg --output hello.svg "Hello, QR!"
java -jar dist/specqr-java-0.1.0-rc.1.jar --format png --output hello.png --diagnostics "漢字とUTF-8"
java -jar dist/specqr-java-0.1.0-rc.1.jar --binary payload.bin --format png --output binary.png
java -jar dist/specqr-java-0.1.0-rc.1.jar --segments examples/segments.json --format matrix
java -jar dist/specqr-java-0.1.0-rc.1.jar --estimate --version 1 "Capacity check"
java -jar dist/specqr-java-0.1.0-rc.1.jar -- --text-starting-with-a-dash
```

入力は 1 個の文字列、`--binary FILE`、`--segments FILE` のいずれか 1 個です。標準入力の自動読み取りや複数文字列の自動結合はしません。

| オプション | 内容 |
| --- | --- |
| `--format` | `svg`（既定）、`png`、`matrix`、`svg-data-url`、`png-data-url` |
| `--output FILE`, `-o FILE` | 出力ファイル。省略すると標準出力 |
| `--ecc L\|M\|Q\|H` | ECC。既定 `M` |
| `--version N` | 固定 Version 1–40 |
| `--min-version N`, `--max-version N` | 自動選択範囲。既定 1–40 |
| `--mask N` | mask 0–7。省略で自動 |
| `--mode` | `auto`、`numeric`、`alphanumeric`、`byte`、`kanji` |
| `--eci N`, `--gs1`, `--fnc1-second XX` | 制御モード。組み合わせ制限あり |
| `--no-optimize`, `--boost` | 自動 mixed segmentation を無効化 / ECC boosting を有効化 |
| `--estimate` | 生成せず計画 JSON を出力 |
| `--diagnostics` | 生成時の診断 JSON を標準エラーへ出力 |
| `--scale N`, `--margin N` | module のピクセル数 / quiet zone の module 数 |
| `--foreground COLOR`, `--background COLOR` | 描画色 |
| `--dpi N` | 診断用の印刷 DPI |

成功は終了コード 0、不正入力・容量不足・ファイル入出力失敗は 2 です。`--estimate` は容量不足も JSON の `ok: false` として終了コード 0 で返します。PNG はバイナリなので、端末では `--output` を使ってください。既存の出力ファイルは上書きします。Structured Append の自動分割・結合と GS1 ヘルパーの直接操作には Java API を使います。CLI の手動 JSON では低水準 Structured Append header を指定できます。

JSON セグメントの書式は [API](docs/api.md#cli-の-json-セグメント) を参照してください。

## 描画・診断・運用上の境界

既定は黒・白、quiet zone 4 modules、scale 8 です。PNG は独自の決定的な stored-DEFLATE / RGBA 実装で生成するため、圧縮効率より依存不要・再現性を優先しています。PNG / pixels の色は hex、`black`、`white`、`transparent` に限定します。SVG は他の色表現も XML 属性としてエスケープしますが、CSS の安全性や視認性を保証するものではありません。

診断は生成・計画時の `Options` を反映します。`qr.toPng(otherOptions)` などの描画時オーバーライドでは、元の診断を更新しません。描画資源上限も実際の描画時に検証するため、計画成功だけでは画像生成成功を保証しません。

資源上限、例外、UTF-8 / ECI の注意点は [API](docs/api.md)、GS1 と Digital Link の境界は [GS1](docs/gs1.md)、分割規則は [Structured Append](docs/structured-append.md) を確認してください。

## 開発・出典・ライセンス

- [API リファレンス](docs/api.md)
- [GS1 / Digital Link](docs/gs1.md)
- [Structured Append](docs/structured-append.md)
- [検証手順と結果](docs/verification.md)
- [貢献方法](CONTRIBUTING.md) / [セキュリティ](SECURITY.md)

MIT ライセンスです。一次の契約基準は SpecQR `15ad15e5c770ea0e39072f8f88b2733018f02ffd`（`v3.0.0-rc.2`）。所有者の Python / Swift / C# / C++ 実装と Conformance Lab も参照し、固定した版は [NOTICE](NOTICE) に記録しています。

第三者の QR 実装のソース・bytecode をライブラリに取り込んでいません。独立した encoder / decoder は、別途導入する開発用検証でのみ使用し、配布 JAR の runtime 依存には含めません。
