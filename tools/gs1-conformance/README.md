# GS1 differential audit

This development-only audit uses Python 3's standard library, a JDK 17+ and Node.
The published Java library has no Python, Node, JavaScript, or third-party runtime
dependency. The Java probe invokes the actual public GS1 implementation.

From the repository root, with `java`, `javac` and `node` on `PATH`:

```sh
python3 tools/gs1-conformance/verify_gs1.py --baseline /path/to/SpecQR
```

The baseline must be the unmodified SpecQR JavaScript checkout at
`15ad15e5c770ea0e39072f8f88b2733018f02ffd`. Override tools with `--java`, `--javac`,
`--node` or `JAVA`, `JAVAC`, `NODE`. `--report` selects the JSON report location
(default `build/gs1-conformance.json`). Sources are compiled into a temporary
directory with Java 17 bytecode, all compiler warnings enabled, and warnings as
errors. Every subprocess has a timeout; JVM processing is limited to two active
processors. The tracked baseline source/package files must match the pinned HEAD
before and after execution. Java source, tests, and helper tools are hashed before
and after execution, and a concurrent change fails the audit. A test-only JVM
property corrupts catalog output after actual GS1 execution; the same comparator
must detect that negative control as a value mismatch. No reference implementation
or fault-injection branch is copied into the runtime.

The deterministic corpus covers all 50 AI metadata entries; valid and invalid
values for every AI; every ASCII code in hosts, credentials, stems, paths, query
values and creator values; IPv4 numeric forms; IPv6; ports; special-scheme URL
syntax; percent escapes; all UTF-8 byte values and continuation boundaries;
seeded malformed URL/byte-string samples; IDNA deviations; and literal/encoded
dot-only GS1 values. Each URI exercises parsing, validation, and normalization.

The report retains the exact requests and results, corpus hash, Java and Node
versions, baseline commit, and three distinct difference counts:

- Acceptance: one implementation accepts and the other rejects
- Value: both succeed but logical elements, unknown query values/order, AI
  metadata, or normalized/created URI differ
- Diagnostic: acceptance agrees but stable error/warning fields differ. Compared
  fields are `code`, `reason`, `ai`, `value`, `key`, `offset`, `elementIndex`,
  `expected`, and `count`, with missing and null normalized. Human-facing `message`
  prose is intentionally not compared

Ordinary and catalog differences cause a failing exit code. Two separate,
explicitly reported categories are expected:

1. Java's `java.net.IDN` is IDNA2003, whereas browser URLs use UTS #46. For example,
   `faß.de` becomes `fass.de` in Java; final sigma is folded; some joiners are
   removed; newer Unicode characters can be rejected. ASCII ACE (`xn--`) labels
   must round-trip through `IDN.toUnicode(..., ALLOW_UNASSIGNED)`; malformed ACE is
   rejected, but valid IDNA2008 deviation labels such as `xn--fa-hia.de` are also
   rejected. Newer valid ACE scalars such as emoji can pass with this flag even
   when their raw Unicode host form is rejected. Ordinary ASCII non-ACE hosts
   avoid this IDNA boundary. The adapter does not use `java.net.URI`'s stricter raw-character
   grammar and never resolves a host or accesses the network.
2. Dot-only GS1 values are deliberately lossless. The Java parser retains literal
   or percent-encoded `.` / `..` values, and creation/normalization puts them in
   the query. The JS baseline's browser URL normalization can drop them or reject
   the resulting path. The report counts both acceptance and value differences.

This corpus demonstrates tested behavior; it is not a claim of complete WHATWG
URL or GS1 conformance.
