# Development-only conformance tools

Every candidate QR operation in these tools runs the actual Java implementation
in a separate Java 17+ JVM. Python orchestrates comparisons; Node runs independent
references and decoders. Neither is involved in generating candidate symbols.
The production library has no runtime dependencies outside `java.base`.

From the repository root, with Java 17+ (`java` and adjacent `javac`), Python 3.11+
and Node 18+ installed:

```sh
npm ci --prefix tools/conformance --ignore-scripts
git clone https://github.com/SpecQR/SpecQR.git ../SpecQR-baseline
git -C ../SpecQR-baseline checkout 15ad15e5c770ea0e39072f8f88b2733018f02ffd
python3 tools/conformance/verify_conformance.py --baseline ../SpecQR-baseline
python3 tools/conformance/verify_jsqr.py
python3 tools/conformance/verify_decoders.py --decoder cpp --dependency-dir .tools/zxing-cpp
python3 tools/conformance/verify_decoders.py --decoder java --dependency-dir .tools/zxing-java
```

The default candidate is this checkout. `--candidate PATH` selects another source
checkout. `--candidate-java /absolute/jdk/bin/java` selects the candidate JVM;
the bridge compiles sources and the test adapter with the adjacent `javac`,
`--release 17`, then launches only those compiled classes in temporary storage.
`SPECQR_JAVA` sets the default JVM. ZXing Java's separate `--java` option selects
its decoder JVM and is independent of the candidate runtime.

For a built-artifact consumer check, add `--jar /absolute/path/specqr-java.jar`
to each command. This compiles only the adapter against that exact JAR and uses
a classpath consisting solely of the adapter classes and the JAR. It does not
load the source checkout in place of the artifact. Building and inspecting the
JAR are separate checks; merely passing `--jar` is not a packaging audit.

Decoder wheels must support the selected Python/OS. `--no-install` uses only
already hash-verified decoder files and fails when they are missing. Nayuki/jsQR
can be resolved from a separate prepared package directory with
`SPECQR_DEV_NODE_MODULES=/absolute/package/directory`. Decoder libraries, binaries,
node_modules, generated reports, and fixtures are never production dependencies.
Use `--output PATH` to store reports outside the checkout when desired.

## Exact scope and controls

- Live pinned-JavaScript comparisons: 3,028 public matrices, including every
  version 1–40 × ECC L/M/Q/H × mask 0–7, Numeric, Alphanumeric, Byte, Kanji,
  mixed manual segments, automatic optimization, automatic masks, ECI boundaries,
  FNC1 first/second, GS1, ECC boost, deterministic Unicode fuzz, and 160 randomized
  binary cases. Comparisons include all padded data codewords, all interleaved
  data/ECC codewords, version, ECC, mask, and a SHA-256 of every matrix module.
- Independent Nayuki 1.8.0 comparisons: 2,400 matrices, including all 1,280
  version/ECC/mask combinations. Fixed versions and masks disable independent
  auto-mask/segmentation/ECC-boost differences. Segment boundaries are preserved.
- Internal comparisons: 4,320 raw matrices (three data patterns × 40 versions ×
  four ECC levels × automatic and eight explicit masks), all eight mask scores,
  all 65,536 GF products, and RS generator/remainder degrees 1–255.
- Public planning: 640 exact capacities and 1,920 at/below/above capacity
  estimates across all versions, ECCs, and data modes. Structured Append compares
  22 sets and 112 matrices, including exact member data/ECC codewords. Eight-thread shared-process replay compares another
  256 matrices. Malformed inputs require Java `SpecQrException` and a recognized
  stable error code; ordinary Java exceptions are not accepted as typed failures.
- Six candidate negative controls each launch a real JVM and reach the Java API
  before corrupting a matrix hash, a data byte, or an interleaved codeword, or
  forcing an exit, missing response, or error. Three additional controls ensure
  untyped exception leaks are rejected. Candidate and decoder response counts
  are strict; truncation never becomes a pass.

The reports record source fingerprints, candidate JVM/version/PID/nonce,
class-loading origin, classpath, compiled class or JAR fingerprints, reference
commit/source fingerprints, reference corpus hashes, and test-tool fingerprints.
Changed sources, binaries or test tools invalidate the conformance result.
`--suite public` or `--suite internal` is explicitly a scoped result.

## Three independent decoders

- jsQR 1.4.0: 232 matrix and 232 real-PNG detections, all versions, Numeric,
  Alphanumeric, Byte, Kanji, binary bytes, and 64 ECI-header checks. FNC1 and
  Structured Append are unsupported by jsQR and are only claimed in ZXing lanes.
- ZXing-C++ 3.1.1: 706 matrix and 750 real-PNG detections, all versions,
  135 legal Structured Append headers, all 152 FNC1-second indicators via manual
  and high-level APIs (304 symbols), ECI bytes/text/metadata, five complete
  high-level sets (44 symbols), and independent set reconstruction.
- ZXing Java 3.5.4: 446 matrix and 446 real-PNG detections, all versions,
  135 Structured Append headers and sequence/parity metadata, five complete
  high-level sets reconstructed through both routes, and 32 damaged-symbol tests
  that each require three independently corrected codewords.

Every real PNG pixel and quiet-zone pixel is verified before detection. Java
strict PNG detection uses scale 3 without `PURE_BARCODE`, with no matrix fallback
when detection fails. The known default-scale-8 PNG detector behavior is retained
as a separate diagnostic alongside an independent identical-pixel PNG; failures
remain explicit and never increment strict success counts. C++ tests default
scale 8. Every decoder must reject an actual all-white negative control.

These finite synthetic tests are not ISO/GS1 certification, camera/print testing,
or a guarantee for arbitrary inputs or damage. No failed decode, absent version,
missing dependency, changed source, or omitted test is silently counted as a pass.

## Provenance and pinned development dependencies

Adapted from MIT-licensed SpecQR-Python conformance tools at
`1deb5cf83ed7a93eeb44138e5980a963a00cdffb`. The upstream oracle, Structured Append
corpus, ECI control and decoder workflow derive from SpecQR-CPP
`e91cd8fe4434fd6ef10d1126bb2fd17b0b19321f`; the Java decoder and pinned decoder
requirements derive from SpecQR-CSharp
`057c4b3f25e52c4786a8f94c744ff884eedcecfa`. The Java decoder preserves the original
SpecQR-Swift attribution. JavaScript baseline:
`15ad15e5c770ea0e39072f8f88b2733018f02ffd`.

The npm lock contains package integrity hashes. ZXing Java JAR SHA-256 and
ZXing-C++ official PyPI wheel hashes are checked by the tools. No third-party
encoder or decoder source, binary, dependency or import enters the Java runtime.
