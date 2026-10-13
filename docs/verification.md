# 検証と対応範囲

検証は実際の Java process とビルドした JAR を対象に行います。開発専用の参照 encoder と decoder は runtime の依存に含めません。

この文書は初期公開候補 `0.1.0-rc.1` の検証範囲を記録します。assertion 数、比較した matrix 数、decoder の読み取り回数、CI job 数は別の単位です。規格認証や全 scanner・全プリンターでの動作保証を意味しません。

## 基準

- JavaScript: [SpecQR](https://github.com/SpecQR/SpecQR/tree/15ad15e5c770ea0e39072f8f88b2733018f02ffd)、`15ad15e5c770ea0e39072f8f88b2733018f02ffd`
- Python: `1deb5cf83ed7a93eeb44138e5980a963a00cdffb`
- Swift: `0ef9613fe8f1ecd687da76ce797b4ef896afa477`
- C#: `057c4b3f25e52c4786a8f94c744ff884eedcecfa`
- C++: `e91cd8fe4434fd6ef10d1126bb2fd17b0b19321f`
- Conformance Lab: `72ad78c979327e3e261526ea3c0a164efa4ab390`

公開直前に基準 revision を照合しています。主たる入出力契約と golden vectors は JavaScript の上記 commit に固定します。他言語は補助資料です。Java の実行時にこれらを呼び出すことはありません。

## 再実行

```sh
java tools/Build.java test
java tools/VerifyPackage.java
```

通常のビルドと自己テストは JDK のみで実行でき、ネットワークやサードパーティーのテストフレームワークを必要としません。外部 oracle / decoder の準備とコマンドは [検証ツール](../tools/conformance/README.md) を参照してください。

## 自己テスト

2026-10-03 の固定ソースで、7 suites / **263,790 assertions** がすべて成功しました。失敗 0、skip 0 です。

| Suite | Assertions |
| --- | ---: |
| CoreTests | 224,436 |
| SegmentTests | 28,768 |
| ApiTests | 2,240 |
| RenderTests | 48 |
| CliTests | 46 |
| Gs1Tests | 7,792 |
| StructuredAppendTests | 460 |

Linux の Eclipse Temurin JDK 17.0.20.1+1 および 25.0.4.1+1 でビルド・全 suite・配布物検査を実行しました。OpenJDK 21.0.12.1 の runtime でも、同じ suite を `--limit-modules java.base` で成功確認しています。これは Windows / macOS の実行証拠とは区別します。

主な固定比較と性質:

- 全 40 Version × 4 ECC × 3 種の data codeword 配列に対し、480 block layout、4,320 個の matrix と mask penalty を固定した JavaScript の SHA-256 と照合
- GF(256) の 65,536 積を JavaScript および独立した多項式演算と照合、Reed–Solomon 255 degree の divisor / remainder を照合
- 3,054 の optimizer mode / 境界 / bit length 比較、2,213 の header / payload bit 比較、6,953 の Kanji mapping 全体を照合
- 全 Version / ECC / mode の capacity 境界、Unicode surrogate、手動 control の順序、空入力、混合 / raw binary、immutable snapshot、同時実行
- PNG chunk、CRC、DEFLATE 解凍、filter、RGBA 全画素、SVG XML escaping、data URL、CLI、巨大入力と geometry overflow の preflight
- GS1 と Structured Append の形式、parity、順序、重複 / 不足、スカラー境界、資源上限

## 独立 conformance

固定した source と上記の JDK 17 JAR のそれぞれに対して、conformance / jsQR / ZXing-C++ / ZXing Java の **4 lanes を再実行し、計 8 reports が成功**しました。以下は 1 対象あたりの件数で、source と JAR を足して水増ししていません。

- 公開 API matrix 3,028、内部 raw matrix 4,320、Structured Append 22 sets / 112 member matrices。合計 7,460 matrices。SA の member data / ECC codewords も正確に比較
- 上記公開 API のうち 2,400 matrices は独立した Nayuki とも一致。全 1,280 Version / ECC / mask tuple を含む
- capacity 640、境界 estimate 1,920、GF 積 65,536、RS degree 255、typed malformed rejection 18、同時実行の追加 replay 256
- 候補出力の破損など 6 negative controls と、untyped exception を検出する 3 controls が成功

| Decoder | Matrix 読み取り | PNG 読み取り | 全 RGBA pixel 照合 |
| --- | ---: | ---: | ---: |
| jsQR 1.4.0 | 232 | 232 | 13,855,872 |
| ZXing-C++ 3.1.1 | 706 | 750 | 97,805,184 |
| ZXing Java 3.5.4 | 446 | 446 | 10,008,270 |

全 decoder の matrix / PNG lane が Version 1–40 を通過し、blank image / matrix の負の対照を拒否しました。jsQR の FNC1 / SA 非対応を成功として扱いません。ZXing-C++ は FNC1 second の全 152 indicators を 2 経路で検査（304 symbols）、Java は damaged-symbol 32 件で訂正結果を確認しています。両 ZXing は SA header 135 件と high-level 5 sets / 44 symbols の独立再構築を検査しました。

機械可読の結果・fingerprint の概要は [verification-summary.json](verification-summary.json) にあります。

検証工具は候補を実際の Java 子プロセスで実行します。Java の executable、classpath、実行クラスの hash、challenge nonce を確認し、参照側の結果を候補として流用しません。source / class / JAR と関連する test tool の fingerprint が実行中に変わった場合は成功にしません。

Nayuki、jsQR、ZXing-C++、ZXing Java は開発専用で固定 version / hash を使います。matrix / codeword / metadata の意図的破損、および blank image を負の対照として使用します。

### ZXing Java の scale 8 に関する記録

Java decoder の通常 PNG detection において、特定の既定 scale 8 画像と、それと全画素同一の対照画像の両方が `NotFound` になりました。これは成功件数に入れません。matrix 自体、RGBA 全画素、および別 decoder の読み取りで独立に検証します。この元の検出失敗を、画像を変更した成功結果で置き換えることはしません。詳細は機械可読 report の diagnostic lane に残します。

## GS1 / Digital Link の境界

GS1 は 50 AI の限定された catalog です。全 GS1 AI、日付の暦上の妥当性、登録主体や取引の意味まで保証しません。通常の文字列を FNC1 と組み合わせる際の literal `%` は byte fallback で保持し、明示的な unsafe alphanumeric は拒否します。

Digital Link の受け付け可否、正規化した値、診断情報の差を別々に数えます。`.`, `..` の GS1 値は破壊的な URL 正規化を避けて保存し、作成・正規化時には query に出力します。この安全修正は、元の JavaScript が dot path を消してしまう挙動には追従しません。Unicode host は Java の IDNA2003 によるため、ブラウザーの UTS #46 と異なる場合があります。詳細な例と制限は [GS1](gs1.md) を参照してください。

公開した [GS1 differential audit](../tools/gs1-conformance/README.md) の固定 corpus は **5,610 cases / 15,690 operations** です。通常の URL / 作成操作 14,839 と catalog 操作 701 はすべて一致しました。残りは別レーンに分けています。

- IDNA2003: 90 operations のうち 66 一致、acceptance の差 21、正規化した host 値の差 3
- 意図的な dot 値保持: 60 operations のうち 20 一致、acceptance の差 12、値の差 28
- diagnostic-only の差: 0。比較対象は code / reason / ai / value / key / offset / elementIndex / expected / count。missing と null を同一視し、人間向け message 本文は比較しません

合計は 15,626 一致 + 64 の明示した差 = 15,690 operations です。期待外の差は 0 です。Java probe が実際の GS1 処理をした後で結果を壊す負の対照を検出し、実行前後の source / test / tool hash も一致しました。基準 JavaScript の追跡対象も変更されていないことを検査しています。

## 配布物と再現性

`VerifyPackage.java` は同じ source / JDK toolchain で 2 回作成し、runtime JAR と sources JAR の SHA-256 がそれぞれ一致することを確認します。JAR entry の順序・時刻は固定し、manifest、MIT / NOTICE、classfile、開発用 class 混入も確認します。JDK major が異なると compiler の classfile 出力が変わるため、異なる compiler 間の runtime JAR byte 一致は約束しません。

JDK 17 の検証済み runtime JAR SHA-256 は `5b8e7a816d1d38d681aac0dfdf4c3d199b3676e63f4cc1df351b8707f8075d1c`、sources JAR は `2baaa17f7c20486f69f821724a19fe3cea07e57af7ecdc3c74830aa90ead6eeb` です。

さらに、元の checkout とは別の一時ディレクトリーに JAR だけをコピーし、consumer をコンパイルして実行します。`jdeps --print-module-deps` は `java.base` のみ、classpath / named-module / CLI は `--limit-modules java.base` で実行します。依存 runtime、native library、外部 encoder の呼び出しを必要としません。

## 独立した最終レビュー

実装担当とは別のレビューで、別途作成した **5,823 assertions** を新規コンパイルした source と正規 JAR のそれぞれで実行しました。named-module consumer、`jdeps`、JAR の 57 entries、Java 17 classfile version、sources JAR の 17 source files、隔離した 2 回の再ビルドを照合しています。修正した geometry overflow、診断 snapshot、JSON の非 ASCII hex escape を回帰テストに残しました。

## CI

[GitHub Actions](https://github.com/SpecQR/SpecQR-Java/actions) の portable matrix は Windows / macOS / Linux × Temurin 17 / 21 / 25 の 9 jobs、独立 conformance / decoder job、および結果を要求する aggregate job です。

公開前の環境 matrix 成功を先取りして宣言しません。公開 commit に対する実際の terminal 結果と、公開 URL から fresh clone した consumer の検証を完了条件とします。
