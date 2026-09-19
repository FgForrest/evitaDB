---
title: The roaring containers get an optional Vector API provider, fused word kernels and a CRoaring-style lazy array union; the all-pairs and gather kernels are measured and rejected
date: 2026-09-19
updated: 2026-09-19 12:10
status: proposed
kind: optimization
issues: [1539, 1541, 1542, 1543, 1544]
prs: [1608]
areas: [evita_roaring_bitmap, evita_engine/core, evita_test/evita_performance_tests, pom.xml, docker]
supersedes: []
superseded-by: []
relates: [2026-09-10-simd-vector-api-feasibility, 2026-09-18-range-index-counting-kernel, 2026-07-07-roaring-bitmap-vendoring]
---

# The roaring containers get an optional Vector API provider, fused word kernels and a CRoaring-style lazy array union; the all-pairs and gather kernels are measured and rejected

The feasibility record `2026-09-10-simd-vector-api-feasibility` proposed four pieces of SIMD work in the vendored
roaring bitmap: an optional `jdk.incubator.vector` provider (#1541), a fused AND + population-count kernel for
bitmap containers (#1542), an all-pairs SIMD intersection of array containers (#1543) and a gather-based
array-versus-bitmap probe (#1544). This record says what happened when each was built and measured on a
production catalog, why the campaign's biggest change turned out to be a scalar policy borrowed from CRoaring
rather than a vector kernel, which of the four issues' mechanisms were rejected with numbers, and how a kernel that
measured 1.42× on a real-operand replay turned out to cost 7 % end to end and was caught before merge.

## Why

Two facts, both established in the first hour and both contradicting the feasibility analysis, drove every
decision below.

1. **C2 on JDK 21.0.12 already auto-vectorises the `bitCount(a[k] & b[k])` reduction** (hypothesis H9 of the
   analysis, marked "unverified" there). With `-XX:-UseSuperWord` the scalar loop is 1.7× slower; with it on, the
   explicit `LongVector` popcount kernel is at best 1.2× faster and, for `andCardinality`, 0.85× — a loss. The
   feasibility estimate of 3–6× had assumed a scalar loop that does not exist on this JIT.
2. **The production workload is not word arithmetic.** A census of 52.5 M container operations issued by 2,600
   facet, hierarchy, price and attribute queries against a production e-commerce catalog (475 k entities, 565 k
   entity indexes) found 92.7 % of them to be `lazyIOR(BitmapContainer, ArrayContainer)` — a scatter of a
   median-4-value array into an 8 KiB bitmap — and bitmap × bitmap pairs to be 0.23 %. A JFR profile of the same
   queries put all roaring work at 40.9 % of query CPU, of which the lazy-union machinery is 26.4 points,
   hashing 4.4, intersection 2.8 and galloping search 2.4. 95.3 % of the 1.93 M bitmaps built by lazy unions
   demote straight back to an `ArrayContainer` at repair time; 1.87 M array→bitmap promotions × 8 KiB were 23 %
   of every byte the workload allocated.

The numbers reorder the campaign: the provider is still built (the extraction kernels need it, and the issues ask
for it), the bitmap kernels are wired but earn little, the two array-side vector kernels are rejected, and the
change that reaches the profile is applying CRoaring's `ARRAY_LAZY_LOWERBOUND` idea — keep a lazy array × array
union an array while it is small — to the multi-way union path. The vendored `ArrayContainer.lazyor` already
carries that branch with the same 1024 bound; `PersistentRoaringBitmap.naivelazyor`, the path every engine union
takes, bypasses it by promoting the accumulator to a bitmap *before* merging.

### Previous state

`BitmapContainer` held its word loops inline: a popcount pass followed by a store pass for `iand`/`iandNot`,
`clone()` + OR loop + recount for `or`, a recount loop after every in-place OR/XOR. `PersistentRoaringBitmap.
naivelazyor` promoted the accumulator to a `BitmapContainer` on the first shared key unconditionally (calling
`toBitmapContainer()` before `lazyIOR`), so the container-level `ArrayContainer.lazyor` branch that keeps small
unions sparse up to `ARRAY_LAZY_LOWERBOUND = 1024` was never reached from a multi-way union; every such union of
small arrays therefore allocated and zeroed 8 KiB per key, scattered into it, popcounted 1024 words at repair and
walked them again to extract an array. Nothing in the module used the Vector API.

## Decisions taken

| Date | Decision | Why | Detail |
|------|----------|-----|--------|
| 2026-09-18 | Ship the provider as designed (`requires static`, self-detecting holder, scalar twin per kernel, per-kernel switches, both-mode surefire), with one failure boundary and the javac flag scoped to the roaring POM | A missing module, a C1-only JIT or a narrow CPU must run today's code; a root-level javac flag would let a stray Vector API import into the JDK 17 driver modules compile clean | §Key technical details; `f1e470a3b` |
| 2026-09-18 | `SystemStatus` is not extended; the decision is logged once at `Evita` construction through `RoaringKernels.vectorKernelsSummary()` | A field ripples through 27 files across gRPC/GraphQL/REST/driver for a diagnostic string | deviation from #1541 point 3 |
| 2026-09-18 → 19 | Fuse the bitmap word loops into single-pass store + count kernels. First shipped count-first wherever the container type depends on the result; the real-operand replay then showed count-first buys nothing on `iand`/`and` (257 vs 246 ns per pair two-pass, because 99.94 % of real bitmap×bitmap intersections stay dense) while one fused pass is 152 ns — so `iand`/`iandNot` fuse first in place and demote from the result words when sparse, `and` allocates and fuses, and only `andNot` (47.8 % of real results demote) keeps count-first | Fusion is the measurable win (1.6–2.0× on `iand`, 1.35× on `or`), and the *scalar* fused loop captures nearly all of it; a pass spent counting before a result that is dense anyway is a pass wasted | `a4c246a0e` + the policy fix-up |
| 2026-09-18 | Lazy branches (`cardinality == -1`) keep count-free loops | Writing the popcount there turns `repairAfterLazy` into a no-op; a 3-value result would stay an 8 KiB bitmap that compares unequal to its canonical form (`equals` is `false` across the array/bitmap boundary) | `LazyCardinalityProtocolTest` |
| 2026-09-18 | Vector defaults per kernel from JMH: `cardinality`, `cardinalityInRange`, the fused store kernels and the sparse extraction on; the count-only kernels (`andCardinality` and siblings) delegate to the scalar loops | precise run (3 forks × 10 × 2 s): vector `andCardinality` 79.5–86.9 ns vs scalar 71.6–72.4 (0.83–0.90×, a loss); vector `cardinality` 47.5–49.4 vs 58.4 (1.18–1.23×) | §Verification |
| 2026-09-18 | #1543's all-pairs kernel and #1544's gather probe are rejected with numbers; no array-intersection kernel ships | see *Rejected outright* | families 2 and 3 |
| 2026-09-19 | Lazy array union on the multi-way path (`naivelazyor`) with its own `LAZY_ARRAY_UNION_BOUND` (64 values, chosen by the replay below; first shipped as 256) and an N ≤ 64 input guard in the varargs `naive_or`, rather than routing through the existing container-level `ArrayContainer.lazyor` (bound 1024) | the profile's 26 % is the bitmap round trip for unions whose result is an array 95 % of the time; the container-level bound alone is not usable on the multi-way path because every array fold copies the accumulator, so the cost is quadratic in the number of inputs — a wide union of tiny arrays must promote early, which the input-count guard enforces and a cardinality bound cannot | §Key technical details, §Verification |
| 2026-09-19 | Sparse-aware extraction kernel (skip all-zero 8-word blocks) behind the provider, routed through `fillArray`, `fillLeastSignificant16bits`, `toArrayContainer` | `Util.fillArray` is 5 % of query CPU and the bitmaps it walks are mostly empty words | §Verification |
| 2026-09-19 | `FacetReferenceIndex.getFacetReferencingEntityIdsFormula` groups facet ids by group id instead of `Collectors.groupingBy` on the `FacetGroupIndex` object | `FacetGroupIndex` is Lombok `@Data`: hashing the key walks its `TransactionalMap` → every `FacetIdIndex` → every `TransactionalBitmap` → every container, 5.4 % of facet-query CPU per the profile's caller breakdown — the only caller of `ArrayContainer.hashCode` in the workload | engine change folded into this campaign |
| 2026-09-19 | `ArrayContainer.hashCode` / `RunContainer.hashCode` compute their (unchanged) value from the last seven entries only, in O(1) | The vendored loop reads `hash += 31 * hash + c` (an upstream `+=`, i.e. base 32 = 2^5), so only the last seven values can reach a 32-bit result; the method was 3.8 % of query CPU for a value a seven-step loop reproduces exactly (20,000 random containers, zero mismatches). The value is kept identical on purpose; the poor distribution is a separate decision | §Rejected outright, §Consequences |

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| #1543 all-pairs 8×8 block intersection as written in the issue (`ShortVector.SPECIES_128`, rotation loop) | 0.5–0.6× of the scalar merge at every size and density. Without `pcmpestrm` (CRoaring's instruction, not expressible in the Vector API) an 8×8 block costs 8 compares + 7 rotations + 7 mask ORs; unrolling the rotation loop and summing eight `trueCount()`s instead of OR-ing masks (valid because block values are distinct) recovers 1.2–1.5× at ≥ 1024 equal-size elements, but both operands are ≥ 257 values in only 4.4 % of production intersections (0.16 % of all container work) | a workload whose array intersections are large on both sides |
| 512-bit all-pairs (32 short lanes) | 2.5× slower than the merge: block granularity coarsens the merge advance | — |
| branch-free scalar merge | 2.5–6× slower except on identical inputs (12×): one loop-carried step per element of both inputs, while the branchy merge's skip loops advance several elements per predicted branch and run at ~5 IPC on skewed inputs (0.16 ns per step at 64 × 4096) | — |
| broadcast probe (walk the short side, compare each value against a 16- or 32-lane window of the long side; cached block maximum) | the vector code is optimal — six instructions, mask never leaves a k-register — and on synthetic shapes the cached-max 16-lane form is a consistent 1.3–1.7× on equal sizes and 3.4–7× on 64× skews; **on 300 real production pairs (median shorter side 4 values) it is 3× slower than today's galloping-dispatch merge (275 vs 85 ns per pair), and the plain form only ties it**. The production mix is dominated by tiny operands where the kernel's per-call setup exceeds the whole merge | a workload whose array intersections are mostly ≥ 64 × ≥ 1024 |
| #1544 gather-based probe | 0.9–1.0× of the scalar probe at 256/1024/4096 synthetic values and 0.89× on 900 real probe pairs (255 vs 228 ns per pair): the scalar `(bitmap[v>>>6] >>> v) & 1` loop runs at ~0.3 ns per value, the load-throughput bound, and a gather cannot beat it | — |
| vector `andCardinality` / `orCardinality` / `xorCardinality` / `andNotCardinality` | 0.85× of the auto-vectorised scalar loop (H9 refuted) | a JIT that stops auto-vectorising the reduction, e.g. after a JDK baseline change |
| fused OR into a fresh array as a *headline* | the 8 KiB allocation is the floor: three-pass 479 ns, scalar fused 394, vector fused 333 with the allocation in the timed region; `clone()` enjoys C2's zeroing elimination, a vector store into `new long[]` does not | — |
| root-level javac `--add-modules jdk.incubator.vector` | compiles under `--release 17` (verified) but makes the incubator module resolvable inside the driver modules, eroding the JDK 17 floor the enforcer rule protects | — |
| extending `SystemStatus` with the kernel summary | 27-file ripple across gRPC/GraphQL/REST/driver for a diagnostic string | an operator surface that genuinely needs it |
| a `ThreadLocal` scratch for lazy-union accumulators | already rejected by the 2026-08 usage-statistics work as hostile to virtual threads | — |
| VP2INTERSECT | not expressible in the Vector API; and CRoaring does not use it either (its array intersection is `pcmpestrm`), so nothing is lost against the reference implementation | a native/FFM provider, its own decision |
| a vector polynomial-hash kernel for `ArrayContainer.hashCode` | the shipped recurrence is base 32, so the result depends on the last seven values; an O(1) tail loop returns the identical int and no kernel is needed | the hash is changed to a real base-31 polynomial (a behaviour change with its own record) |
| word-batched array-to-bitmap scatter (one read-modify-write per distinct word in `ilazyor`/`ior`/`or`/`loadData`) | 1.42× on the value-weighted batch replay, **+7.3 % end to end** on the catalog-wide facet summaries of the same catalog; the median operation scatters 4 values into 4 distinct words, where batching saves no write and adds a data-dependent branch per value (bisected on a quiet box, §Verification) | a per-operation replay on real operands that shows a win — the batch's 5.35× write reduction is real only for the mean-42-value tail |
| the VBMI2 `compress` extraction for `fillArrayAND`/`ANDNOT`/`XOR` | every caller sits on the ≤ 4096-result branch where it measures 0.87–1.7×; it reaches 3.6–8.7× only at ≥ 12.5 % density, which those sites never see | a dense extraction site |

## Key technical details

- **Provider** (`io.evitadb.roaringbitmap.kernel.VectorKernels`): scalar implementations are assigned first; the
  optional attempt — module in `ModuleLayer.boot()`, JIT usable via `HotSpotDiagnosticMXBean` (`UseCompiler`,
  `TieredStopAtLevel`) with an input-arguments fallback, reflective load of the vector class whose static
  `isSupported()` checks `VectorShape.preferredShape() >= 256` bits, a self-test of every kernel against its scalar
  twin on fixed inputs and all three output aliasings — sits in one `catch (Throwable)`. The holder links no
  Vector API type, so a class-path launch without `--add-modules` cannot fail at class-init. Every kernel uses
  `SPECIES_PREFERRED`; a hard-coded 512-bit species is silently emulated under `-XX:MaxVectorSize=16`. The
  self-test proves linkage and correctness under the interpreter, not that C2 intrinsifies an operation; the
  differential test and the both-mode surefire are the real protection.
- **Fused kernels and copy-on-write**: every in-place container mutation is preceded by
  `PersistentRoaringBitmap.copyIfShared`; `RunContainer.full()` is a fresh instance; `new BitmapContainer(long[],
  int)` aliases its array (why `workShyAnd` clones before appending). Never drop the `clone()` in
  `or/xor/andNot(ArrayContainer)` or `lazyor` in the name of fusion — receivers may be shared.
- **Lazy array union**: `naivelazyor`'s shared-key branch unions two `ArrayContainer`s in place while
  `acc + in <= LAZY_ARRAY_UNION_BOUND` and keeps a known cardinality; once promoted, today's path. The varargs
  `naive_or` skips the policy for more than 64 inputs so a 5,000-input union does not pay dozens of merges before
  promoting. CRoaring's bound is 1024; the Java bound is lower because each fold copies the accumulator.
- **Upstream sync**: `evita_roaring_bitmap/UPSTREAM_SYNC.md` lists the `kernel` package, `RoaringKernels`, the
  `Util.unsignedLocalIntersect2by2` promotion, the lazy-union policy and the routed sites as evita-only
  divergences; an upstream edit to a word loop now lands in `ScalarBitmapKernels` and `VectorBitmapKernels`, not
  in the container.
- Entry points the engine actually uses: `PersistentRoaringBitmap.or(...)` → `FastAggregation.naive_or` →
  `naivelazyor` → `repairAfterLazy`; `RoaringBitmapBackedBitmap.and` → `FastAggregation.naive_and` → container
  `iand`; `NotFormula` → `andNot`. `andCardinality` has no engine caller (#1540 has not landed).

## Verification

Box: AMD Ryzen AI 9 HX 370 (Zen 5, AVX-512 with VPOPCNTDQ/VBMI2), OpenJDK 21.0.12, one JMH fork, `/proc/stat`
busy gate ≤ 20 % before and averaged across every run; ratios are the finding, absolutes ±10 %.

- **Tests**: the vendored roaring suite runs twice (vector where available, and with `-Devita.roaring.vector=false`):
  18,023 / 0 failures in each execution on the final tree (17,915 before this work); new:
  `VectorKernelsDifferentialTest`, `VectorKernelsSelectionTest`, `LazyCardinalityProtocolTest`, `LazyArrayUnionTest`,
  `ContainerTailHashCodeTest`, `ScatterAndFuseFirstIntersectionTest`, `ScalarArrayKernelsTest`, plus the quality-pass
  witnesses (partial-vector tails, both aliasings, the `mergeBulk` sparse policy, the reflective load contract, an
  allocation-differential test for the
  facet bucket map, and the opt-in `-Devita.roaring.vector.require=true` that turns a silent scalar fallback into a
  failure). Counterfactuals watched
  red and then green again: a kernel missing its last species block fails the differential test (`expected: <591>
  but was: <585>`) and the holder reports `bitmap=scalar (self-test mismatch in andCardinality)`; a vector `extract`
  skipping its last lane fails `shouldExtractLikeScalar` (`<591> but was: <521>`) and the holder falls back with
  `self-test mismatch in extract`; forcing a cardinality in `iand`'s lazy branch fails
  `shouldLeaveLazyCardinalityUnknown` (`expected: <-1> but was: <5000>`); `LAZY_ARRAY_UNION_BOUND = 0` fails
  `shouldKeepTheAccumulatorSparseUnderTheBound` (`expected: <ArrayContainer> but was: <BitmapContainer>`) while the
  promotion assertions stay green; `HASH_CONTRIBUTING_VALUES = 6` fails four of the six hash tests. Functional
  subset `facet | query | indexing` on the final tree: 12,726 / 0 failures (7 skipped). A Codex adversarial review
  of the branch found one issue (property reads outside the provider's fail-safe boundary, fixed) and approved the
  rest; the four-agent quality pass (test-architect, bug-hunter, simplifier, javadoc) landed as one commit.
- **Bitmap kernels (JMH, 3 forks × 10 × 2 s, busy ≤ 15 %)**: `cardinality` 58.4 → 47.5–49.4 ns (1.18–1.23×);
  `andCardinality` 71.6–72.4 → 79.5–86.9 (0.83–0.90×, rejected); `cardinalityInRange` 56.6 → 40.7 (1.39×, first
  wave); fused AND into an existing destination: two passes 215–230 ns → scalar fused 137–157 (1.5×) → vector fused
  118–143 (1.6–1.8×); fused OR into a fresh array (allocation in the timed region): three passes 461–468 → scalar
  fused 387–419 (1.15×) → vector fused 336–348 (1.35×).
- **Auto-vectorisation control (`-XX:-UseSuperWord`, 2 forks × 8)**: the scalar loops fall to 216–225 ns for
  `cardinality`/`andCardinality` and 384–424 ns for the two-pass AND while the vector kernels stay at 49–84 ns — the
  explicit kernels are worth 2.6–4× only on a JIT that does not auto-vectorise the reduction, which is why they are
  kept behind the provider as insurance rather than removed.
- **Array intersection (JMH, families 2 and the real-operand replay, 3 forks × 10 × 2 s)**: see *Rejected outright*;
  on the 300 real pairs (per pair) today's materialising path 84.2 ± 1.0 ns is the fastest arm, the plain
  broadcast count 82.9 (vs today's count 122.7 — the count path skips galloping today and has no engine caller),
  broadcast materialising 103.8, cached-max broadcast 287–290, reloading broadcast 270–315, summed all-pairs 416,
  branch-free merge 984.
- **Gather probe (JMH, family 3)**: 78 / 323 / 1405 ns scalar vs 89 / 331 / 1357 gather at 256 / 1024 / 4096 synthetic
  values; on 900 real array-against-bitmap operand pairs (median 46 probed values) 228.4 → 255.5 ns per pair (0.89×) —
  the index round trip through the `int[8]` scratch is a store and a load per eight probes that 46 values cannot
  amortise.
- **Extraction (JMH)**: skip-empty-blocks kernel vs the scalar `tzcnt` walk on synthetic containers: 5.3× / 5.6× /
  4.9× / 2.5× / 1.2× at 1 / 4 / 16 / 64 / 256 set bits, 3.6–4.4× on clustered bits, 0.50× / 0.60× at 1024 / 4096
  (hence the cardinality gate); on the 300 real pre-repair bitmaps (median 36 values in 12 non-zero words of 1,024,
  mean 423 values; 89.7 % leave at least 93.75 % of their words empty) 645 → 375 ns per container for the 16-bit
  form (1.72×) and 780 → 357 ns for the 32-bit `toArray` form (2.18× like for like; the bench report's table
  reads 1.80× for that arm because it ratios every arm against the 16-bit baseline).
  The VBMI2 compress form is 2.6–4.4× on the dense real operands and ≤ 1.4× on the sparse ones; it is not shipped
  because `compress` has no AVX2 path and the provider cannot yet gate on VBMI2.
- **Hash (JMH)**: `ArrayContainer.hashCode` 4.4 / 56.5 / 272 / 1,248 / 4,658 ns at 4 / 64 / 256 / 1024 / 4096 values
  before; 3.3–5.9 ns flat after (the vector polynomial forms measured 11–17× at ≥ 256 — an order of magnitude
  short of the constant-time answer); on 5,728 real array containers from the operand dump (mean cardinality 305)
  259.9 → 3.6 ns per container (71.5×), the vector forms 26.8–31.1 ns.
- **Scatter — the measurement that lied, and how it was caught**: a word-batched loop (one read-modify-write per
  distinct word) measured 31.0 → 21.8 ns per pair (1.42×) on the JMH batch replay of the 300 real `lazyIOR` pairs
  and shipped. The first quiet-box end-to-end pair then read **+7.3 %** on the catalog-wide facet summaries
  (19,178 → 20,577 ms, after-arm minimum above before-arm maximum) and +3.8 % in total, where every earlier
  pair had run under the bench chain's load and read anywhere between −0.6 % and +6.3 % on that line. Two
  after-only bisect arms on the quiet box, same serialized queries: the commit reverted whole → 19,470 ms; only
  the two loops returned to one write per value with the fuse-first intersections kept → 19,372 ms. The loops
  were the regression; fuse-first is innocent. The operands explain it: the 300 real arrays have median 4 /
  mean 42.2 values in median 2 / mean 7.9 distinct words; value-weighted, 81.3 % of values share a word with
  their predecessor (5.35× fewer writes overall), but 123 of the 300 arrays share no word at all and 121 of the
  187 arrays with ≤ 8 values have every value in its own word. The batch (one invocation = all 300 pairs) is
  value-weighted and dominated by the long tail; the engine is operation-weighted (48.6 M `lazyIOR` per query
  mix, median 4 values in 4 words), where the batched loop performs the same writes plus a data-dependent
  branch per value. Reverted in the same line of work. A per-operation probe (one scatter per invocation, pools of
  4,096 distinct operands re-based by random multiples of 64, 3 forks × 10 × 1 s, busy 5.9 %) then measured the
  batched loop against the per-value one, ns per operation: 4 values in 4 words (the median real operation)
  4.04 → 6.25 (**0.65×**); 4 values in one word 3.91 → 4.91 (0.80×); 64 values in 64 words 29.0 → 41.4 (0.70×);
  64 values in 8 words 31.3 → 28.9 (1.08×); the 300 real arrays one per invocation 30.8 → 22.8 (1.35×); the same
  4-word operand repeated every invocation 3.64 / 5.98 against 4.04 / 6.25 unrepeated, so operand repetition is
  worth ≤ 10 % and is not the mechanism. The batched loop loses on every short shape whether or not the values
  cluster — which explains why the median operation loses while the batch replay won — but it does not explain
  the regression: the 300 arrays are a uniform reservoir sample of the 48.6 M operations, so their per-operation
  mean (1.35× in favour of batching) predicts an aggregate *improvement* that the engine did not show. The cause
  therefore lies in a condition the fixture does not model. One candidate was tested and refuted: with a single
  L1-hot destination (a wide fold scatters a thousand arrays into one key's bitmap) instead of the fixture's
  256 × 8 KiB pool, the batched loop still wins 1.50× on the sampled real operations (30.5 → 20.3 ns) and still
  loses 0.63× on 4 and on 64 distinct-word values (3.72 → 5.87, 23.1 → 36.6 ns). Two candidates remain untested:
  the sample is uniform over the *whole* mix while the regressing line is one query kind, whose operands may be
  long and spread across words (the shape where batching loses 0.63×), and the JIT's compilation of the larger
  helper inside the engine's `lazyIOR` call chain, which the probe's standalone kernels do not exercise. The
  arithmetic of the loop shapes alone (≤ 2.2 ns per median operation) cannot produce the ≈ 29 ns per `lazyIOR`
  the pass lost, so whichever it is, it is not the loop. The decision rests on the end-to-end bisect, not on the
  probe; the probe's job was to rule the obvious explanations out, and it did (`benchmarks/REPORT.md`).
- **Lazy array union (JMH, corrected baselines — today's per-key fold that promotes before the first merge)**: a
  64-value bound wins 24–43× on 2-input unions of 1–16-value chunks, 8–31× on 4 inputs, 9–17× on 8, 9× on 16 single
  values and 1.4× on 64 single values, and never falls below 0.75× on the synthetic grid (0.81–0.84× where 8–16 inputs
  of 16 values just cross it); a 256-value bound keeps the small-union wins but loses 0.23–0.57× on 16–64 inputs of 4–16
  values, and the container-level 1024 bound (`ArrayContainer.lazyor`, never reached from a multi-way union before) is
  0.02× on 256 inputs of 4 values — the per-fold copy is quadratic in the input count, which is why the input-count
  guard is the load-bearing half. Real-union replay (300 unions from the production catalog, 80/70/60/50/40 across the
  2–4 / 5–16 / 17–64 / 65–256 / 257+ input strata; ns per union, today's per-key fold → 64-value bound → 256-value
  bound, both with the N ≤ 64 guard): 2,594 → 620 (4.2×) → 501 (5.2×); 8,504 → 5,019 (1.7×) → 4,315 (2.0×); 29,193 →
  25,410 (1.15×) → 29,329 (1.0×); 38,423 → 38,055 → 38,055; 206,241 → 203,907 → 203,907. Weighted by the census (176,178
  / 80,825 / 19,598 / 10,771 / 6,892 unions per stratum) today's fold costs 12,070 ns per union and both bounds 9,680 ns
  — **1.247×**, a tie; 64 is chosen because its regressions are nowhere (worst 0.97× unguarded) while 256's sit inside
  the guard's range (0.23–0.57× on 64 inputs of 4–16 values on the synthetic grid). Moving the guard to 16 costs the
  17–64 stratum its 1.15× for nothing (1.215× weighted). The union fold is 26 % of facet-query CPU on the profile, so
  1.25× there is worth roughly 5 % of query CPU by arithmetic — the end-to-end figures above are the measurement. The
  real `naive_or` end to end is 1.8× slower than its per-key fold on the 257+ stratum (369 vs 206 µs per union): the
  `RoaringArray` bookkeeping of a thousand-input fold, the next lever.
- **End to end** (`RoaringQueryMixTiming`, 2,600 facet/hierarchy/price/attribute queries replayed from one
  serialized query file so both builds do identical work — 38,842,917 records matched in every pass of both arms;
  2 warm-up + 5 timed passes, medians; final tree vs the `dev` tip, quiet box, 10.6 % busy across the pair):
  facet + hierarchy summary COUNTS 1,604 → 1,197 ms (**−25.4 %**), IMPACT 771 → 500 ms (**−35.1 %**),
  catalog-wide facet summary COUNTS 19,208 → 19,525 ms (+1.7 %; before 19,133–19,640, after 19,226–19,568, the
  spreads overlap — three quiet after-arms on the per-value scatter read 19,372 / 19,470 / 19,525 against before
  arms of 19,178 / 19,208, a residual of 1–2 % that is not attributed), attribute filtering 141 → 139 ms, price
  filtering 5,365 → 5,103 ms (−4.9 %); **total 27,070 → 26,553 ms (−1.9 %)**. The catalog-wide summary is 72 % of
  the mix and consists of unions of 1,000+ inputs that the wide-fold guard keeps on the bitmap path; what remains
  there is the per-input `naivelazyor` walk and the scatter, which no kernel in this campaign touches. Six earlier
  pairs ran under the bench chain's load and are superseded by this one; the first quiet pair, on the tree that
  still carried the word-batched scatter, is the one that exposed it (§Scatter above). The query generator is not
  deterministic across JVMs despite its fixed seed (three distinct query checksums and two distinct record totals
  were observed for the same build), which is why the harness serializes and replays the query set.
- **Census and profile**: `census/REPORT.md`, `census/PROFILE.md` in the supporting material.

## Consequences & open follow-ups

- The startup line `roaring vector kernels: …` names the implementation in force; a launcher without
  `--add-modules jdk.incubator.vector` runs the scalar kernels silently apart from that line. One javac and one
  JVM incubating-module warning are accepted costs.
- The JDK 17 driver floor is untouched: no driver module requires `evita_roaring_bitmap`.
- The Vector API pays for itself only where a loop was not already auto-vectorised and has no branchy scalar
  competitor. Of everything measured, the vector code that stays on by default is `cardinality` (1.2×),
  `cardinalityInRange` (1.4×), the fused store kernels (1.1–1.35× over their scalar fused twins) and the
  block-skipping extraction (1.7–2.2× on real pre-repair bitmaps — and that one is an early-exit loop that a
  scalar OR of eight words could reproduce without the incubator module; it is kept on the vector path because
  the provider is there, not because it needs to be). The three largest wins of the campaign — the lazy array
  union, the constant-time hash and the facet grouping by id — are algorithmic. The next SIMD proposal should
  start from a profile of a production workload, not from a loop that looks vectorisable.
- Above the container layer the profile names the next lever: facet-summary construction performs ~113 unions
  per query with a mean of 53 inputs, `HashMap` traffic and `TimSort` are 28 % of CPU, and #1540
  (cardinality-only facet evaluation) is still open.
- `ArrayContainer.hashCode` collides for any two containers that share their last seven values (inherited from upstream
  RoaringBitmap; `BitmapContainer.hashCode` is `Arrays.hashCode(words)` and is unaffected). Whether bitmap-keyed engine
  maps suffer from it is answered by the profile's caller breakdown (TBD); changing the value is a behaviour change to
  be decided separately.
- `RunContainer.equals(ArrayContainer)` answers `true` for the same value set while the two hash codes differ
  (an upstream `equals`/`hashCode` contract violation, unrelated to the tail change and left untouched); nothing in
  the engine keys a map on a container, so it is harmless today and documented here so nobody starts.
- **A batch replay weights by value; the engine weights by operation.** The word-batched scatter passed a
  differential test, a code review and a real-operand JMH replay at 1.42× and still cost 7.3 % end to end,
  because one invocation over 300 pairs is dominated by the few long operands while the engine performs
  48.6 M median-4-value operations. Every kernel measured on a batch fixture in this record (the array replay,
  the probe replay, the extraction replay) carries the same caveat; the union replay does not, because its
  strata are weighted by the census population. The rule that follows: a kernel whose caller is dominated by
  short operands is measured per operation with unrepeated operands, and no kernel ships on a replay ratio
  alone without an end-to-end pair on a quiet box — the pairs run under the bench chain's load read anywhere
  from −0.6 % to +6.3 % on the line that turned out to carry a real +7.3 %.
- Implementation constants to sweep before changing: `PersistentRoaringBitmap.LAZY_ARRAY_UNION_BOUND` (64) and
  `FastAggregation.LAZY_ARRAY_UNION_MAX_INPUTS` (64); `ArrayContainer.HASH_CONTRIBUTING_VALUES` (7) is arithmetic,
  not a tuning knob.
- `Util.unsignedIntersect2by2` gallops on backing-array *capacity*, not on the logical lengths (upstream quirk),
  so the documented 25× rule fires less often than it reads; CRoaring's threshold is 64×. Reported, not changed.
- Two defects in the client benchmark states (`facetedReferences` keyed by entity type where the query API keys
  by reference name; `hierarchyWithin` given an entity type) make every `Client*Facet*` and hierarchy benchmark
  run empty or fail against a real catalog; the timing harness works around them. To be fixed separately.

## Related work

- `2026-09-10-simd-vector-api-feasibility` — the analysis this record measures against; H9 and the all-pairs
  estimate did not survive, the provider design did.
- `2026-09-18-range-index-counting-kernel` — the sibling kernel campaign that likewise found the win in the scalar
  shape of the algorithm.
- `2026-07-07-roaring-bitmap-vendoring` — why the module is a hard fork and carries a sync ledger.

## Supporting material

- `census/REPORT.md` — container histogram, operation mix, intersection shapes, lazy-union structure and
  allocation of the production workload; the evidence for every "reach" claim above.
- `census/PROFILE.md` — the JFR profile: where facet-query CPU goes, by package and by method.
- `benchmarks/REPORT.md` — the JMH measurements: every family with its synthetic grid and its real-operand replay,
  the auto-vectorisation control, the superseded runs and why they were superseded, the anomalies that changed a
  conclusion, and what was deliberately not measured.

## Timeline

- **2026-09-18** — provider, fused kernels, census, profile, JMH families 1–3; all-pairs and gather rejected
- **2026-09-19** — lazy array union, extraction and hash kernels, wave 4 and end-to-end measurement; the quiet-box
  pair caught the word-batched scatter at +7.3 %, two bisect arms convicted the loops, reverted; record written
