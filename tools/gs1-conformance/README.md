# GS1 differential audit

This development-only audit uses Python 3's standard library, a JDK 17+ and Node.

公開した Java ライブラリには、Python、Node、JavaScript、第三者 runtime の依存はありません。
Java probe は、実際の公開 GS1 実装を呼び出します。

`java`、`javac`、`node` を `PATH` に置き、repository のルートから実行します。

```sh
python3 tools/gs1-conformance/verify_gs1.py --baseline /path/to/SpecQR
```

baseline は、変更を加えていない SpecQR JavaScript の次の checkout である必要があります。
`15ad15e5c770ea0e39072f8f88b2733018f02ffd`。tool を上書き指定するには `--java`、`--javac`、
`--node`、または `JAVA`、`JAVAC`、`NODE` を使います。`--report` は JSON report の保存先を
選択します（既定は `build/gs1-conformance.json`）。source は一時ディレクトリへ Java 17 の
bytecode としてコンパイルし、すべての compiler warnings を有効にして、警告をエラーとして
扱います。すべての子 process に timeout を設け、JVM の処理は active processors を 2 個に
制限します。追跡対象の baseline の source/package files は、実行前後で固定した HEAD と
一致している必要があります。Java source、tests、helper tools は実行前後に hash を計算し、
実行中に変更された場合は監査を失敗にします。テスト専用の JVM property が、実際の GS1 処理の
後で catalog 出力を破損させます。同じ comparator が、その負の対照を値の不一致として検出する
必要があります。参照実装や障害注入の分岐を runtime にコピーすることはありません。

決定的な corpus は、全 50 AI の metadata、各 AI の有効値・不正値、host・credentials・stem・
path・query 値・作成用の値に含まれる全 ASCII code、IPv4 の数値表記、IPv6、port、
special-scheme URL 構文、percent escape、全 UTF-8 byte 値と継続 byte の境界、seed を固定した
不正な URL/byte-string の sample、IDNA の差、literal/encoded の dot-only GS1 値を対象にします。
各 URI で解析・検証・正規化を実行します。

report は、正確な request と結果、corpus hash、Java と Node の version、baseline commit、
次の 3 種類を区別した差分件数を保持します。

- Acceptance: 一方の実装が受理し、もう一方が拒否する
- Value: 両方が成功するが、論理要素、未知 query の値・順序、AI metadata、正規化・作成した
  URI のいずれかが異なる
- Diagnostic: 受理・拒否は一致するが、安定した error/warning fields が異なる。比較対象は
  `code`、`reason`、`ai`、`value`、`key`、`offset`、`elementIndex`、`expected`、`count` で、
  missing と null は正規化して比較する。人間向けの `message` 本文は意図的に比較しない

通常の比較または catalog に差があれば、失敗の終了コードを返します。次の 2 区分は想定した差として、
分けて明示的に報告します。

1. Java の `java.net.IDN` は IDNA2003 で、browser URL は UTS #46 を使います。例えば、
   `faß.de` は Java で `fass.de` になり、final sigma は変換され、一部の joiners は削除され、
   新しい Unicode 文字は拒否されることがあります。ASCII ACE (`xn--`) label は
   `IDN.toUnicode(..., ALLOW_UNASSIGNED)` を経由した round-trip が必要です。不正 ACE を
   拒否しますが、`xn--fa-hia.de` のような有効な IDNA2008 deviation label も拒否します。
   emoji などの新しい有効な ACE scalar は、この flag により、raw Unicode の host 形式が
   拒否される場合でも通ることがあります。通常の ASCII non-ACE host は、この IDNA の境界を
   回避できます。adapter は `java.net.URI` のより厳格な raw-character grammar を使わず、
   host の解決やネットワークアクセスも行いません。
2. dot-only GS1 値は、意図的に欠損させずに保持します。Java parser は literal または
   percent-encoded の `.` / `..` 値を保持し、作成・正規化時には query へ置きます。
   JS baseline の browser URL 正規化では、それらが失われるか、結果の path が拒否される
   場合があります。report は、受理・拒否の差と値の差の両方を数えます。

この corpus は検証した挙動を示すものであり、WHATWG URL または GS1 への完全準拠を
主張するものではありません。
