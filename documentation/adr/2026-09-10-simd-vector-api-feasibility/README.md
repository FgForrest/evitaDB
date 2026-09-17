---
title: SIMD through the JDK Vector API is pursued in the roaring containers and two query kernels behind an optional provider, and nowhere in object-shaped code
date: 2026-09-10
updated: 2026-09-10 09:50
status: proposed
kind: optimization
issues: [1518, 1539, 1540, 1541, 1542, 1543, 1544, 1545, 1546]
prs: [1519]
areas: [evita_roaring_bitmap, evita_engine/core/query/algebra, evita_engine/core/query/extraResult/translator/reference, evita_engine/index/range, evita_engine/index/price, pom.xml]
supersedes: []
superseded-by: []
relates: [2026-09-08-jdk21-safe-modernization, 2026-09-10-jdk21-virtual-threads-and-scoped-values, 2026-08-24-fulltext-search-lucene-vs-inhouse]
---

# SIMD through the JDK Vector API is pursued in the roaring containers and two query kernels behind an optional provider, and nowhere in object-shaped code

The HNSW work planned for this release (`2026-08-24-fulltext-search-lucene-vs-inhouse`, spike P6) is expected to
bring `jdk.incubator.vector` into the runtime. This record answers whether the same module pays off inside
evitaDB — in the facet generators, the vendored roaring bitmap and the price termination formulas that were named as
candidates, and in the range index's `JoinFormula` / `DisentangleFormula` pair found on the way. The answer is yes
for the container kernels and for two query kernels once they are reshaped, no for anything that walks objects, and
the module stays optional: a provider selects a vector implementation only when the module, C2 and the CPU are
there, and the scalar code remains the reference. Nothing has been implemented or measured; each piece is an issue
with its own gate.

## Why

Two constraints decide the shape. First, the incubator module is not a preview feature: classes that use it compile
to ordinary major-65 files and only need the module *resolvable* at runtime, so the exact-release pin that ruled out
`ScopedValue` (`2026-09-10-jdk21-virtual-threads-and-scoped-values`) does not apply — but on the class path, which
is how `evita-server.jar` starts, the module must be added with `--add-modules jdk.incubator.vector`, and a missing
module or an operation the JIT does not intrinsify turns a vector kernel into a boxed lane loop slower than the
scalar code. Second, the JDK 17 driver floor (`2026-09-08-jdk21-safe-modernization`) must hold; it does, because
neither `evita_roaring_bitmap` nor `evita_engine` is on the driver's dependency path.

## Decisions taken

| Date | Decision | Why | Detail |
|------|----------|-----|--------|
| 2026-09-10 | Ship through an optional provider: `requires static jdk.incubator.vector`, self-detection of module, C2 and CPU at class-init, a scalar twin for every kernel, per-kernel switches, differential tests | A missing module, a non-C2 JIT or an unsuitable CPU must degrade to today's code, never to a slow fallback; forgetting the launcher flag must not crash the server | `analysis.md` §3; #1541 |
| 2026-09-10 | Cardinality-only facet evaluation precedes any kernel | Every facet count and impact materializes the intersection and reads its size although `andCardinality` exists; the scalar change removes at least half of the arithmetic and all allocation, and it changes the baseline a kernel would be measured against | §4.2; #1540 |
| 2026-09-10 | The facet generators themselves are not touched | They only build formula trees; the arithmetic is in the containers, which serve every AND/OR in the engine | §4.1 |
| 2026-09-10 | First kernel: fused AND + population count over the 1024-word bitmap containers | The one reduction loop C2 is not known to vectorize; serves every cardinality, the count pass of `and`, and `FastAggregation.workShyAnd` | §5.1; #1542 |
| 2026-09-10 | Second kernel: array-container intersection *cardinality*, all-pairs on a 128-bit species; the materializing variant is a stretch goal | Count-only needs no `compress` and runs on AVX2 and NEON; materializing needs AVX-512 VBMI2 or shuffle tables | §5.3; #1543 |
| 2026-09-10 | Array-vs-bitmap gather probes are last | Gather-bound, at most 1.5–2× over a branch-free scalar loop | §5.4; #1544 |
| 2026-09-10 | Price termination: struct-of-arrays columns and a sorted merge-join first, SIMD galloping probes and the vector range predicate second | The object, callback and per-entity hash-map shape cannot be vectorized; the layout carries the gain, the lanes add a constant factor where the gallop dominates | §6.2–6.3; #1545 |
| 2026-09-10 | Range index: replace `JoinFormula` + `DisentangleFormula` with a per-chunk counting kernel emitting result words from vector masks | The pair is a signed multiplicity count; the duplicate array is only an intermediate encoding; emission by mask needs no `compress` and is the cleanest fit found | §11; #1546 |
| 2026-09-10 | Status `proposed`: nothing implemented, no benchmark run | Analysis-only session; every child issue carries its measurement gate | #1539 |

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Explicit kernels for bitmap-container word ops without a reduction (`and`/`or`/`xor`/`andNot` into a new array) | C2's auto-vectorizer already emits 256/512-bit code for them; a second implementation buys nothing measurable | A profile shows one of them not vectorized |
| Bit-position extraction (`Util.fillArrayAND`, `fillArrayANDNOT`) | No lane-to-bit primitive in the API; a per-byte position table gains at most ~2× in C and costs static shuffle tables in Java | A profile names it after the count kernels landed |
| Run containers, key-level merges, galloping binary searches | Data-dependent control flow; nothing to vectorize | — |
| A hard `requires jdk.incubator.vector` | Every embedded user and every launcher would need the flag or fail with `NoClassDefFoundError` at first touch | The module leaves incubation and joins the default root set |
| A `ThreadLocal` scratch for the counting kernel's 128 KiB counter array | The 2026-08 usage-statistics work already rejected per-thread striping as hostile to virtual threads; a bounded pool in the manner of `SharedBufferPool` costs nothing more | — |
| A bitmap-only formulation of the price predicate (`delegate ∧ entityIdsWithPrice`) | Answers "has any price in list p", not "selling price within range", which depends on price-list priority per inner record | — |

## Key technical details

- Verified on the installed OpenJDK 21.0.12 (`javap`, `nm -C --defined-only libjvm.so`): the module is present; the
  API used — `fromArray` incl. gathers, `ShortVector.fromCharArray` for the containers' `char[]`, `lanewise`,
  `reduceLanes`, `compare`, `VectorMask.trueCount/toLong/firstTrue`, `compress`/`expand`, `rearrange`, `BIT_COUNT`,
  `UNSIGNED_*` comparisons — exists on 21. Match rules present: `vpopcount_avx_reg` and
  `vpopcount_integral_reg_evex` with `StubRoutines::x86::_vector_popcount_lut` (so lane-wise popcount is intrinsified
  on every AVX2 CPU, natively on AVX-512 VPOPCNTDQ); `vcompress_expand_reg_evex` and `vcompress_mask_reg_evex` only
  (so `compress`/`expand` are AVX-512-only on 21); `rearrangeS`, `rearrangeS_avx`, `rearrangeS_evex` (16-bit shuffles
  intrinsified on AVX2 at 128 and 256 bits).
- Invariants every kernel keeps: small `static` methods with `static final VectorSpecies`; no allocation in the
  loop; a scalar twin selected by the provider and used by the tests; bitmap containers are 1024 words, a multiple
  of every species length, so the popcount kernel has no tail.
- The provider must be able to disable a kernel per CPU generation: the popcount kernel's gain differs between the
  lookup-table path and `vpopcntq`, and `compress` has no AVX2 path.
- Facet path entry points: `MemoizingFacetCalculator.calculateImpact`, `ReferenceSummaryProducer` (`compute().size()`
  sites), `PersistentRoaringBitmap.andCardinality`, `FastAggregation.andCardinality`.
- Range index semantics that the counting kernel must reproduce exactly: `v ∈ result ⟺ count_main(v) >
  count_control(v)`, traced through `DisentangleFormula.computeNextInt` on the javadoc example.

## Verification

Static analysis only; no benchmark, test or throwaway experiment was run (the analysis was explicitly limited to
feasibility). Platform facts were verified against the installed JDK as listed above. A Codex advisory pass over the
numbered hypotheses `H1`–`H22` in `analysis.md` was attempted on 2026-09-10 and failed on Codex's usage limit before
producing any verdict; none of its output was used. Two hypotheses remain unverified and are the first thing to
check before implementing: whether C2 on 21 already auto-vectorizes the `bitCount(a[k] & b[k])` reduction (`H9`,
`-XX:+PrintCompilation` on `BitmapContainer.andCardinality`), and whether the `after` side of
`RangeIndex.getRecordsEnvelopingInclusive` is redundant under exact counting (`H22`, `RangeIndexTest`). Each child
issue names its benchmark gate; the umbrella #1539 restates the measurement rules (production-shaped catalogs,
negotiated quiet window, verify the measured build).

## Consequences & open follow-ups

- Issues, milestone 2026.3: #1539 (umbrella), #1540 (cardinality-only facets, prerequisite), #1541 (provider and
  wiring), #1542 (bitmap popcount kernel), #1543 (array intersection kernel), #1544 (gather probes, low priority),
  #1545 (price columns and merge-join), #1546 (range-index counting kernel). Recommended order: 1540, 1546, 1541,
  1542, 1543, 1545; 1544 only if a profile names it.
- Open before implementation: `H9` and `H22` above; the production CPU generation (`-XX:+PrintIntrinsics` on the
  target); which HNSW library P6 picks and whether it carries its own Panama provider with the same `--add-modules`
  requirement (jVector does, from memory).
- The API written against 21 should be re-checked against the JDK 25 javadoc before a baseline switch; no removals
  of the operations used are known.
- The roaring fork gains files upstream does not have; the `roaring-bitmap-sync` ledger must list them.
- One JVM warning line at startup and one javac warning per kernel module are accepted costs.

## Related work

- `2026-09-10-jdk21-virtual-threads-and-scoped-values` — the sibling question with the opposite answer; both were
  asked right after the JDK 21 bump.
- `2026-09-08-jdk21-safe-modernization` — the driver's JDK 17 floor that the provider design must not touch.
- `2026-08-24-fulltext-search-lucene-vs-inhouse` — the HNSW spike whose library choice brings the incubator module
  into the runtime and shares the launcher-flag cost.

## Supporting material

- `analysis.md` — the feasibility analysis (11 sections, hypotheses `H1`–`H22` written for an independent review):
  platform facts, the shipping mechanism, per-candidate mechanisms with code sketches, the price-path and range-index
  rewrites, and the benchmark order. Kept because it is the evidence base of the eight issues and the list of
  hypotheses still to be confirmed.

## Timeline

- **2026-09-10** — feasibility analysis, binary verification of the intrinsic paths, issues #1539–#1546 filed,
  record written
