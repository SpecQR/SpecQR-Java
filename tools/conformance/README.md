# Development-only conformance tools

Every candidate QR operation in these tools runs the actual Java implementation
in a separate Java 17+ JVM.

Python は比較処理を管理し、Node は独立した参照実装と decoder を実行します。
どちらも候補の symbol 生成には関与しません。製品のライブラリには、
`java.base` 以外の runtime 依存はありません。

Java 17+ (`java` と同じディレクトリの `javac`)、Python 3.11+、Node 18+ を
インストールし、repository のルートから実行します。

```sh
npm ci --prefix tools/conformance --ignore-scripts
git clone https://github.com/SpecQR/SpecQR.git ../SpecQR-baseline
git -C ../SpecQR-baseline checkout 15ad15e5c770ea0e39072f8f88b2733018f02ffd
python3 tools/conformance/verify_conformance.py --baseline ../SpecQR-baseline
python3 tools/conformance/verify_jsqr.py
python3 tools/conformance/verify_decoders.py --decoder cpp --dependency-dir .tools/zxing-cpp
python3 tools/conformance/verify_decoders.py --decoder java --dependency-dir .tools/zxing-java
```

既定の候補は、この checkout です。`--candidate PATH` で別の source checkout を選択します。
`--candidate-java /absolute/jdk/bin/java` は候補の JVM を選択します。bridge は同じ
ディレクトリの `javac` と `--release 17` を使って source とテスト用 adapter をコンパイルし、
一時領域に置いたそれらのコンパイル済み class だけを起動します。
`SPECQR_JAVA` は既定の JVM を設定します。ZXing Java の別オプション `--java` は、
decoder の JVM を選択するもので、候補の runtime とは独立しています。

ビルドした成果物を使う consumer を検証するには、`--jar /absolute/path/specqr-java.jar` を
各コマンドへ追加します。指定した JAR に対して adapter だけをコンパイルし、adapter の
class とその JAR だけで構成した classpath を使います。成果物の代わりに source checkout を
読み込むことはありません。JAR のビルドと検査は別の確認です。`--jar` を渡すだけでは、
packaging の監査にはなりません。

decoder wheels は、選択した Python/OS に対応している必要があります。`--no-install` は、
hash 検証済みの decoder files だけを使い、それらがなければ失敗します。Nayuki/jsQR は、
別途用意した package directory からも解決でき、その場合は
`SPECQR_DEV_NODE_MODULES=/absolute/package/directory` を指定します。decoder libraries、
binaries、node_modules、生成した reports、fixtures を製品の依存に含めることはありません。
report を checkout の外へ保存する場合は `--output PATH` を使います。

## Exact scope and controls

- 固定した JavaScript を実行した比較: 公開 API の 3,028 matrices を検査します。
  version 1–40 × ECC L/M/Q/H × mask 0–7 のすべて、Numeric、Alphanumeric、Byte、Kanji、
  mixed の手動セグメント、自動最適化、自動 mask、ECI 境界、FNC1 first/second、GS1、
  ECC boost、決定的な Unicode fuzz、無作為化した binary の 160 ケースを含みます。
  比較対象は、pad 済み data codewords のすべて、interleaved data/ECC codewords のすべて、
  version、ECC、mask、全 matrix module の SHA-256 です。
- 独立した Nayuki 1.8.0 との比較: version/ECC/mask の全 1,280 組合せを含む
  2,400 matrices を検査します。version と mask を固定し、独立実装間の
  auto-mask/segmentation/ECC-boost の差を無効にします。segment 境界は保持します。
- 内部比較: 4,320 raw matrices (three data patterns × 40 versions ×
  four ECC levels × automatic and eight explicit masks)、全 8 mask scores、
  全 65,536 GF products、次数 1–255 の RS generator/remainder を検査します。
- 公開 API の計画: 全 version、ECC、data mode にわたり、640 個の正確な容量と、
  容量上・容量未満・容量超過の 1,920 estimates を検査します。Structured Append は、
  各 member の正確な data/ECC codewords を含めて、22 sets と 112 matrices を比較します。
  同一 process を共有する 8 threads で再実行し、さらに 256 matrices を比較します。
  不正入力には Java の `SpecQrException` と認識可能な安定した error code を要求し、
  通常の Java 例外を型付きの失敗として認めません。
- 候補に対する 6 つの負の対照は、それぞれ実際の JVM を起動して Java API に到達した後で、
  matrix hash、data byte、interleaved codeword のいずれかを改変するか、終了・応答欠落・
  エラーを強制します。追加の 3 対照で、型付けされていない例外の漏出が拒否されることを
  確認します。候補と decoder の応答件数は厳密に確認し、切捨てを成功とは扱いません。

report は、source の fingerprint、候補の JVM/version/PID/nonce、class の読込み元、
classpath、コンパイル済み class または JAR の fingerprint、参照 commit/source の
fingerprint、参照 corpus の hash、テスト用 tool の fingerprint を記録します。
source、binary、テスト用 tool が変更された場合、conformance の結果は無効です。
`--suite public` または `--suite internal` は、範囲を限定した結果であることを明示します。

## Three independent decoders

- jsQR 1.4.0: matrix からの 232 回と実 PNG からの 232 回の検出、全 version、Numeric、
  Alphanumeric、Byte、Kanji、binary bytes、ECI header の 64 件の検査を行います。
  jsQR は FNC1 と Structured Append に未対応で、それらは ZXing lanes だけで検証します。
- ZXing-C++ 3.1.1: matrix からの 706 回と実 PNG からの 750 回の検出、全 version、
  合法な Structured Append header の 135 件、manual と high-level の API による
  全 152 FNC1-second indicators (304 symbols)、ECI bytes/text/metadata、完全な
  high-level の 5 sets (44 symbols)、独立した set の再構成を検査します。
- ZXing Java 3.5.4: matrix からの 446 回と実 PNG からの 446 回の検出、全 version、
  Structured Append header の 135 件と sequence/parity metadata、両経路で再構成した
  完全な high-level の 5 sets、破損した symbol の 32 テストを検査します。
  各破損テストでは、3 codewords が独立に訂正されることを要求します。

検出前に、実際の PNG pixel と quiet-zone pixel をすべて検証します。Java の厳密な
PNG 検出は scale 3 を使い、`PURE_BARCODE` を使わず、検出失敗時の matrix fallback も
行いません。既知の default-scale-8 の PNG 検出器の挙動は、同じ画素を持つ独立した PNG と
並べて、別の診断として保持します。失敗を明示し、厳密な成功数には加えません。
C++ は既定の scale 8 を検査します。すべての decoder に、実際の全白画像の負の対照を
拒否することを要求します。

これらの合成した有限のテストは、ISO/GS1 の認証、camera や印刷の試験、任意の入力や破損に
対する保証ではありません。decode の失敗、version の欠落、依存の欠落、source の変更、
テストの省略を、黙って成功に数えることはありません。

## Provenance and pinned development dependencies

MIT ライセンスの SpecQR-Python の conformance tools を、次の commit を基に調整しました。
`1deb5cf83ed7a93eeb44138e5980a963a00cdffb`。元の oracle、Structured Append corpus、
ECI control、decoder workflow は SpecQR-CPP の
`e91cd8fe4434fd6ef10d1126bb2fd17b0b19321f` に由来します。Java decoder と固定した
decoder requirements は SpecQR-CSharp の
`057c4b3f25e52c4786a8f94c744ff884eedcecfa` に由来します。Java decoder は元の
SpecQR-Swift の出典表記を保持します。JavaScript baseline:
`15ad15e5c770ea0e39072f8f88b2733018f02ffd`.

npm lock は package の integrity hash を含みます。tool は ZXing Java JAR の SHA-256 と
ZXing-C++ の公式 PyPI wheel の hash を検査します。第三者の encoder や decoder の
source、binary、dependency、import を Java runtime に含めることはありません。
