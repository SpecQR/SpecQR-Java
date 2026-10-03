# Contributing

Contributions should preserve SpecQR's deterministic contracts and Java-only runtime. Please include focused tests and document deliberate compatibility differences.

## 開発環境

フル JDK 17 以上を使い、リポジトリのルートで実行してください。

```sh
java tools/Build.java test
java tools/VerifyPackage.java
```

通常のビルドと自己テストは外部依存なしです。Maven / Gradle は不要です。`build/classes`、`build/tests` と `dist` に生成物を作り、対象のビルドディレクトリは再作成されます。コンパイルは Java 17 bytecode、UTF-8、警告をエラーとして扱います。`VerifyPackage` は 2 回ビルドして同一 toolchain 内での byte 再現性を確認し、JAR の manifest / license、java.base-only 依存、切り離した consumer の動作も検証します。JDK compiler major が異なる場合の runtime JAR hash 一致は要求しません。

CI の対象は Linux / macOS / Windows と Temurin 17 / 21 / 25 の組み合わせです。ローカルで成功した環境と、CI が実際に完了した環境を区別してください。JDK 17 以外で開発しても、Java 17 にない API を runtime ソースへ追加しないでください。

## 変更の進め方

1. 不具合や変更の意図を、再現する最小入力・期待値・実際の結果で説明する
2. `src/test/java/io/specqr` に回帰テストを加える
3. Java 実装を修正し、通常の自己テストを実行する
4. bitstream / matrix / charset / GS1 / Structured Append の変更では、該当する独立検証も実行する
5. 公開 API、資源上限、例外、対応範囲を変えた場合は文書を更新する

独立検証の依存・コマンド・基準は [docs/verification.md](docs/verification.md) にまとめます。比較 oracle の出力を変更後の実装に合わせて無条件に更新しないでください。差がある場合は、どの契約を変えるのか、互換性に何が起きるのかを先に説明してください。

## 守る契約

- runtime は `java.base` のみ。GUI、画像、ネイティブ、他言語、外部 QR 実装に依存しない
- QR Model 2、Version 1–40 の範囲を明示し、未対応機能を成功扱いしない
- 数値・英数字・Kanji・UTF-8・binary の境界と手動セグメント境界を保つ
- UTF-16 の孤立 surrogate を QR 入力で拒否する。Kanji 表をプラットフォーム charset で置き換えない
- ECI を文字コード変換として扱わない。FNC1 の percent 規則を破壊しない
- mask と最適化の tie-break を決定的に保つ
- 入力サイズ・配列確保・再帰深度・画像サイズの上限を確保前に検証する
- immutable 結果、配列の defensive copy、ネストした診断の読み取り専用性を維持する
- 標準エラーの診断と標準出力の画像・JSON を混在させない
- GS1 全仕様や URL 安全性を保証するような説明を追加しない

配列は Java の符号付き `byte` でも、codeword としては `value & 0xff` で扱います。matrix は `[row][column]`、`module(x, y)` は `(column, row)` です。Structured Append の公開 index は 1-based、raw header の nibble は 0-based です。

## レビューしてほしい情報

PR には、目的、関連する最小入力、テストコマンド、実際に確認した OS / JDK、既知の制約、互換性への影響を含めてください。性能改善は測定条件とサイズを添えてください。速度だけでなく、大きな入力でのメモリ使用や上限到達時の挙動も確認します。

未実行の CI、未導入の decoder、未確認の環境を「成功」「対応確認済み」と記録しないでください。秘密の入力、認証情報、利用者の実データ、個人環境の絶対パスを公開テストやレポートに含めないでください。

## 出典とリリース

MIT の所有者作成 SpecQR を基準とし、固定した参照 commit は [NOTICE](NOTICE) にあります。他の QR ライブラリの実装ソースをコピーして runtime に追加しないでください。外部 decoder / encoder を検証に追加する場合は、バージョンを固定し、ライセンスと用途を明示して開発用に分離します。

この版は `0.1.0-rc.1` です。バージョンを更新する場合は `SpecQr.VERSION`、`tools/Build.java`、CLI の案内、文書、関連テスト・配布名を一貫させてください。タグ作成、stable release、Maven Central 公開は個別のリリース判断です。ビルド成功だけでそれらを実施したことにはなりません。

セキュリティ報告は [SECURITY.md](SECURITY.md) に従ってください。
