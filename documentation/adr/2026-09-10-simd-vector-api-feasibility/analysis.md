# JDK Vector API (SIMD) for evitaDB internals — feasibility analysis

- **Status:** analysis only, awaiting review. No code changed, no benchmark run, no throwaway experiment.
- **Date:** 2026-09-10, branch `1518-upgrade-to-jdk-21` at `d02ab4a0e`.
- **Runtime facts checked with `javap` on OpenJDK 21.0.12** (`/usr/lib/jvm/java-21-openjdk-amd64`); statements that
  could not be checked that way are labelled *from knowledge*.
- **Machine used for reading:** AMD Ryzen AI 9 HX 370 (Zen 5) with `avx2`, `avx512f`, `avx512bw`,
  `avx512_vpopcntdq`. Production hardware is unknown; every CPU-dependent statement below distinguishes AVX2-only from
  AVX-512, and AArch64 (NEON, 128-bit) separately.
- **Context:** the fulltext ADR (`2026-08-24-fulltext-search-lucene-vs-inhouse`, P6) plans a jVector-versus-in-house
  HNSW spike, so `jdk.incubator.vector` is expected to enter the runtime anyway. This document asks whether the same
  module can pay off in the three areas Johnny named, and how.

Numbered statements `H1`..`H22` are the hypotheses handed to the Codex advisory pass for confirmation or refusal
(`H19`–`H22` were added with §11 after the first pass was attempted).

---

## 1. Decisions at a glance

| Candidate | Where the arithmetic really is | Feasible? | Expected gain | Verdict |
|---|---|---|---|---|
| Facet count / impact generators | Nowhere in the generators — they build formula trees; the work is bitmap AND / OR cardinality in `evita_roaring_bitmap` | n/a | — | **Vectorize the substrate, not the generators**; first fix the algorithmic issue in §4.2 |
| Bitmap-container AND + popcount (`andCardinality`, and the count pass inside `and`) | `BitmapContainer.java:273-279`, `:221-235` | yes | 3–6× on the kernel with AVX-512 VPOPCNTDQ; 1.5–2.5× on AVX2 through the lookup-table popcount HotSpot 21 ships | **GO (gated)** |
| Bitmap-container word ops without a reduction (`and`, `or`, `xor`, `andNot` into a new array) | `BitmapContainer.java` | yes, but pointless | ≈ 0 — C2 auto-vectorizes these loops already | **DO NOT CHANGE** |
| Array-container ∩ array-container, cardinality only | `Util.unsignedLocalIntersect2by2Cardinality` (`Util.java:896-947`) | yes | 2–4× on similar-size inputs; none on the galloping branch | **GO (gated)**, medium complexity |
| Array-container ∩ array-container, materializing | `Util.unsignedLocalIntersect2by2` (`Util.java:1229`) | yes with AVX-512 VBMI2; shuffle-table emulation elsewhere | 2–3× where `compress` is native, uncertain elsewhere | **LOW priority** |
| Array-vs-bitmap probes (`bitValue` loops) | `BitmapContainer.java:200-209`, `:256-264` | yes, via gather | 1.5–2× at best (gather-bound) | **LOW priority** |
| Bit-position extraction (`Util.fillArrayAND`) | `Util.java:218-231` | awkward — no lane↔bit primitive | ≤ 2×, table-driven | **NO** for now |
| Run containers, key-level merge, galloping search | `RunContainer`, `RoaringArray`, `Util.advanceUntil` | no | — | **NO** |
| Price termination formulas as written | `LowestPriceTerminationFormula.java:453+`, `SumPriceTerminationFormula.java:331+`, `PlainPriceTerminationFormulaWithPriceFilter.java:243+` | **no** — objects, callbacks, per-entity hash maps | — | **NO-GO as-is** |
| Price termination after a struct-of-arrays layout | `FilteredPriceRecords` / `ResolvedFilteredPriceRecords` | yes for the range predicate and "entity has a matching price"; segmented min/sum stay scalar | 4–10× on the predicate stage; the layout change itself is the larger win | **Conditional GO**, layout first |
| `JoinFormula` + `DisentangleFormula` (range index, §11) | `JoinFormula.java:338-356`, `DisentangleFormula.java:277-343` | yes, once reformulated as a signed count per chunk | 5–20× from the reformulation alone (estimate); the emission step is the cleanest SIMD fit in this document | **GO** — counting rewrite first, SIMD emission inside it |

**Why, in short.** evitaDB's query arithmetic is set algebra on 16-bit chunks (roaring containers) plus a small
amount of per-record filtering. SIMD fits the container kernels that combine a bitwise op with a *reduction*
(population count), and sorted-set intersection when the two sides have similar size. It does not fit anything that
walks objects, chases pointers, or branches per element — which is what the price formulas do today. The
facet-counting path would benefit indirectly, and there a cheaper scalar change (cardinality without materialization,
§4.2) comes first and should be measured before any vector kernel is written.

---

## 2. Platform facts — the Vector API on JDK 21

- `jdk.incubator.vector` is present in the installed runtime (`java --list-modules` lists `jdk.incubator.vector@21.0.12`).
  It is an **incubator module**, not a preview language feature (JEP 448, sixth incubator on JDK 21).
- **H1.** Unlike `--enable-preview`, using an incubator module does **not** mark class files with minor version
  65535; the classes compile to ordinary major-65 files and load on any JDK 21+ where the module is *resolvable*.
  The only requirement is that the module is in the boot layer at runtime: automatic when the code runs on the
  module path with `requires jdk.incubator.vector` in `module-info.java`; on the class path (`java -jar`, which is how
  `evita-server.jar` starts) it needs `--add-modules jdk.incubator.vector`, otherwise the first touch throws
  `NoClassDefFoundError`. Both javac and the JVM print a one-line "using incubating module(s)" warning.
- API surface verified on 21 with `javap`: `IntVector`/`LongVector`/`ShortVector` `fromArray` (plain, masked, and
  gather `fromArray(species, array, offset, int[] indexMap, mapOffset)`), `ShortVector.fromCharArray` /
  `intoCharArray` (the roaring containers store 16-bit values in `char[]`), `lanewise(Unary|Binary|Ternary)`,
  `reduceLanes`, `compare` → `VectorMask` with `trueCount()`, `toLong()`, `firstTrue()`, `anyTrue()`,
  `compress(mask)` / `expand(mask)`, `rearrange(VectorShuffle)`, `VectorOperators.BIT_COUNT`, `MIN`, `MAX`, `ADD`,
  `UNSIGNED_LT/LE/GT/GE`, `COMPRESS_BITS` / `EXPAND_BITS`.
- **H2 (from knowledge).** The methods listed above survive unchanged into the finalized shape planned for later
  JDKs; JDK 22–25 added operations (two-vector `selectFrom`, saturating arithmetic, `VectorShuffle` conveniences) but
  removed none of these. Code written against them on 21 should compile on 25 without change. This is the opposite of
  the `ScopedValue` situation recorded in `specifications/jdk21-virtual-threads-scoped-values/ANALYSIS.md` §7.
- **H3.** Vector code is fast only when C2 intrinsifies it. Under the interpreter, C1, or when an operation is not
  intrinsified for the CPU, each lane operation falls back to a Java loop over boxed lanes and runs **slower than the
  scalar code it replaced** — typically by an order of magnitude. Consequences: kernels must be small `static`
  methods with `static final VectorSpecies` constants, no allocation in the loop, and every kernel needs a scalar
  twin used under a runtime kill switch and by the tests.
- **H4.** Hardware dependence of the operations this analysis relies on, HotSpot 21.0.12, x86-64. The x86 part was
  checked against the installed `libjvm.so` (`nm -C --defined-only`; the match-rule and macro-assembler symbols are
  present even in the distribution build); the per-instruction cost remarks and the AArch64 part are *from knowledge*
  and the usual `-XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics` check on the target CPU still applies:
  - `LongVector.lanewise(BIT_COUNT)` is intrinsified on **every AVX2 CPU**, not only with `avx512_vpopcntdq`. The
    binary carries both match rules, `vpopcount_avx_reg` and `vpopcount_integral_reg_evex`, the lookup table
    `StubRoutines::x86::_vector_popcount_lut`, and `C2_MacroAssembler::vector_popcount_{byte,short,int,long,integral}`
    (the `vpshufb` nibble-lookup path) next to `vector_popcount_integral_evex` (the `vpopcntq` path). On AVX2 a
    64-bit-lane popcount costs a handful of instructions per vector (two `vpshufb`, adds, `vpsadbw`), so the kernel
    still gains there, less than with native `vpopcntq`. On AArch64 NEON it is `cnt` + `addv` (*from knowledge*).
  - `compress(mask)` / `expand(mask)` are **AVX-512 only** on 21: the only match rules are
    `vcompress_expand_reg_evex` and `vcompress_mask_reg_evex`; no AVX2 rule and no permutation-table stub exist in
    this build, so on AVX2-only CPUs they fall back to the Java lane loop (H3). 16-bit lanes additionally need
    `avx512_vbmi2` (*from knowledge*).
  - `rearrange` on 16-bit lanes has the rules `rearrangeS`, `rearrangeS_avx` and `rearrangeS_evex`, so 128-bit and
    256-bit short shuffles are intrinsified on AVX2 too (the 256-bit AVX2 form is emulated with two in-lane
    shuffles and a blend, *from knowledge*; `vpermw` on AVX-512BW).
  - `VectorMask.trueCount()` (`vpmovmskb` + `popcnt`), `compare`, `and`/`or`/`xor`, `reduceLanes(ADD)` and gathers
    (`vpgatherdd/qq`) are intrinsified on any AVX2 CPU (*from knowledge*, consistent with the rule set found).
  - `VectorShape.preferredShape()` is 256-bit on AVX2, 512-bit on AVX-512 (HotSpot prefers 512 only when
    `UseAVX=3` and the CPU is not penalized), 128-bit on NEON.
- **H5.** There is no Vector API equivalent of `pcmpistrm` / `pcmpestrm` (the SSE4.2 string-compare instructions
  CRoaring uses for 8×8 all-pairs matching in one instruction) and no "positions of set bits" primitive. All-pairs
  matching costs one `compare` per rotation, and bit-position extraction needs lookup tables.

---

## 3. How it would ship — the mechanism

**H6.** The right integration model is the one Lucene uses for its Panama provider: an optional, self-detecting
implementation behind a tiny provider interface, with the scalar code kept as the reference implementation.

1. **Module wiring.** `evita_roaring_bitmap/src/main/java/module-info.java` (and `evita.engine` if a kernel lives
   there) declares `requires static jdk.incubator.vector;`. Static is what makes the module optional at runtime: the
   code compiles, and the classes that import `jdk.incubator.vector` are simply never loaded when the module is
   absent. Neither module is reachable from the Java driver (`evita_roaring_bitmap` is required only by
   `evita_engine`, `evita_engine/pom.xml`), so the JDK 17 driver floor and its enforcer rule are untouched.
2. **Compilation.** `maven-compiler-plugin` `<compilerArgs>` for those modules gains `--add-modules
   jdk.incubator.vector` (the root POM today passes only `-parameters`, `pom.xml:750-752`). javac then warns
   "using incubating module(s)"; the build does not treat warnings as errors (no `failOnWarning` / `-Werror` in the
   root POM).
3. **Provider selection at class-init.** A `VectorKernels` (working name; run the naming check before creating it)
   holder resolves once: if `ModuleLayer.boot().findModule("jdk.incubator.vector")` is present, the system property
   kill switch is not set, `VectorShape.preferredShape().vectorBitSize() >= 256` (128-bit NEON is allowed but gains
   little for the popcount kernel), and the JVM is HotSpot with C2 enabled (`-XX:TieredStopAtLevel` < 4 or `-Xint`
   disable it), it instantiates the Panama implementation reflectively; otherwise the scalar one. The decision is
   logged once at INFO, and exposed through the existing system statistics so an operator can see which one runs.
4. **Runtime flag.** `--add-modules jdk.incubator.vector` goes into `docker/entrypoint.sh`, `evita_server/run-server.sh`,
   `evita_server/dist/run.sh`, the surefire `argLine` (`pom.xml:809-815`), the JMH fork arguments and the IDE run
   configurations — the same surface list parts/04 of the ScopedValue analysis enumerated for `--enable-preview`,
   but with a far milder failure mode: forgetting the flag means the scalar path runs, not a startup crash. Embedded
   users get the same instruction in `documentation/user/en/operate/run.md` and the Java-embedding page.
5. **Tests.** Every kernel is tested three ways: the scalar and vector implementations against each other on
   randomized inputs (differential test), the existing roaring test suite run with the vector provider forced on,
   and the existing suite with it forced off. CI runs both modes.
6. **Per-kernel switch and CPU visibility.** The gain of the popcount kernel differs by CPU generation (native
   `vpopcntq` versus the lookup-table path, H4) and `compress` is AVX-512 only, so the provider records which
   `preferredShape()` it runs with, exposes a per-kernel enable switch (system property) as cheap insurance, and the
   benchmark gate in §8 must be run on the actual production CPU generation, not on a developer laptop.

Cost of the mechanism: one provider interface, two implementations per kernel, ~10 launcher/config edits, one
startup warning line. No class-file pin, no consumer lock-in, and the driver is unaffected.

---

## 4. Candidate 1 — facet counting (`FacetFormulaGenerator`, `ImpactFormulaGenerator`)

### 4.1 What the generators do

Both classes only *assemble formula trees*. `FacetFormulaGenerator.generateFormula` (`:74-103`) caches one tree per
`FacetRelationType` and swaps a `FacetGroupAndFormula` / `FacetGroupOrFormula` into it through
`MutableFormulaFinderAndReplacer`; `ImpactFormulaGenerator` (`:83-150`) does the same keyed by reference, relation
type and group. The arithmetic happens when the consumer evaluates the tree:

- `MemoizingFacetCalculator.calculateImpact` (`:110-127`): `hypotheticalFormula.compute().size()`.
- `ReferenceSummaryProducer` (`:1312-1318`): `this.resultFormula.compute().size()` for the count formula.
- `AbstractFacetFormulaGenerator` (`:582`) and `ImpactFormulaGenerator.hasSenseAlone` (`:198, :207`):
  `formula.compute().isEmpty()`.

`compute()` of an `AndFormula` goes to `RoaringBitmapBackedBitmap.and` (`:297-300`) →
`PersistentRoaringBitmap.and(x1, x2)` (`PersistentRoaringBitmap.java:286-311`) → `Container.and` per shared key.
`FacetGroupAndFormula.computeInternal` (`:118-123`) and `FacetGroupOrFormula` do the same for the group.

**Verdict for the generators themselves: nothing to vectorize.** The SIMD question is entirely about the container
kernels (§5), which serve every AND/OR in the query engine, not only facets.

### 4.2 The algorithmic finding that comes first

**H7.** Every facet count and impact is obtained by *materializing* the intersection and reading its size, although
`PersistentRoaringBitmap.andCardinality` (`:323-345`) and `FastAggregation.andCardinality` exist. Materializing costs,
per shared container: `BitmapContainer.and` (`:221-235`) runs `andCardinality` (pass 1, 1024 words) and then either a
second 1024-word pass into a fresh `BitmapContainer` or `Util.fillArrayAND` (bit extraction) into a fresh
`ArrayContainer`; then a `RoaringArray` and a `PersistentRoaringBitmap` are allocated; then `getCardinality()` sums
the cached container cardinalities. A count-only evaluation does pass 1 and nothing else. For array-container pairs the
difference is `unsignedLocalIntersect2by2` writing into a buffer versus `unsignedLocalIntersect2by2Cardinality`
counting.

This is a scalar change in the formula layer — a `computeCardinality()` on `Formula` that `AndFormula`,
`FacetGroupAndFormula` and the constant formulas implement by delegating to `andCardinality`, with `compute().size()`
as the default — and it removes at least half of the arithmetic plus all of the allocation on this path. It should
be done and measured **before** any vector kernel, because it changes the baseline the kernel would be measured
against and it is likely to be the bigger win.

### 4.3 What SIMD adds on top

- **H8.** With cardinality-only evaluation, the per-facet cost is one `andCardinality` per shared container:
  bitmap×bitmap (§5.1, the popcount kernel), array×array (§5.3), or array×bitmap (§5.4). For a production
  e-commerce catalog of ~157 000 products a bitmap spans at most 3 containers, so the arithmetic per facet is a few
  thousand cycles at most, and the formula-tree machinery around it (visitor, memoization keys, `initialize`,
  result objects) is of the same order. The vector kernels therefore cannot deliver more than the fraction of the
  facet-summary time the container arithmetic represents, which only a profile of the facet-summary benchmark can
  give. A catalog with millions of entities per collection shifts this balance toward the arithmetic.
- **Batch shape.** The impact computation evaluates N facet bitmaps against the *same* base bitmap. A kernel that
  takes one base container and streams N facet containers keeps the base in L1 and turns the problem into the shape
  SIMD likes best. That is a scalar restructuring (loop order) with a vector inner loop; it is what CRoaring users do
  for faceting, and it is only reachable once §4.2 exists.

---

## 5. Candidate 2 — `PersistentRoaringBitmap` and its containers

`PersistentRoaringBitmap` (4 344 lines) is upstream RoaringBitmap v1.6.12 with copy-on-write container sharing.
Its own code is key-level merging (`char` keys, linear or galloping, `:286-311`, `:323-345`) and bookkeeping;
none of that is vector material. The kernels are in the containers and `Util`.

### 5.1 Bitmap-container AND with popcount — the best fit

Scalar today (`BitmapContainer.java:273-279`; the same loop shape at `:331`, `:388`, `:427`, `:911`,
`Util.java:326-361`):

```java
for (int k = 0; k < 1024; ++k) newCardinality += Long.bitCount(this.bitmap[k] & value2.bitmap[k]);
```

**H9.** C2 vectorizes plain word loops (`answer[k] = a[k] & b[k]`) on its own, but on JDK 21 it does not reliably
vectorize a loop whose body is `bitCount(a & b)` accumulated into an int (a widening reduction over a popcount of a
long lane), so this loop runs at roughly one word per cycle: ~1 000–1 500 cycles per container pair.

Vector mechanism (species `LongVector.SPECIES_PREFERRED`; 8 words per step on AVX-512, 4 on AVX2):

```java
LongVector acc = LongVector.zero(S);
for (int k = 0; k < 1024; k += S.length()) {
    LongVector a = LongVector.fromArray(S, x, k);
    LongVector b = LongVector.fromArray(S, y, k);
    acc = acc.add(a.and(b).lanewise(VectorOperators.BIT_COUNT));   // vpandq + vpopcntq + vpaddq
}
return (int) acc.reduceLanes(VectorOperators.ADD);
```

1024 is a multiple of every species length, so there is no tail. **H10.** With `vpopcntq` the loop is bound by the
two loads per step: ~128 steps ≈ 150–300 cycles per pair, i.e. 3–6× over scalar; the same kernel gives `orCardinality`,
`xorCardinality`, `andNotCardinality`, `getCardinality` recomputation and the count pass of `and`/`iand`. The
fused variant that also stores `a.and(b)` into the result container turns `BitmapContainer.and` from two passes
into one. On AVX2-only x86 the lookup-table popcount makes this a smaller gain (H4; estimate 1.5–2.5×, to be measured);
on NEON it is 2 words per step with a native byte-popcount, roughly break-even to 1.5×.

### 5.2 Word ops without a reduction — leave alone

`and`/`or`/`xor`/`andNot` into a fresh array, `Arrays.fill`, `ior`/`ixor` in place: the auto-vectorizer already emits
256/512-bit code for these; an explicit kernel buys nothing measurable and costs a second implementation. **Skip.**

### 5.3 Array-container ∩ array-container — cardinality first

Scalar today: a branchy two-pointer merge (`Util.java:896-947` count-only, `:1229-1290` materializing), dispatched
by `unsignedIntersect2by2` (`:818-834`) to a galloping search when one side is 25× smaller.

Vector mechanism (the classic all-pairs block intersection): load 8 (128-bit species) or 16 (256-bit) `char`s from
each side with `ShortVector.fromCharArray`; compare block A against block B and against each of the other 7 (15)
rotations of B — `b.rearrange(ROTATION[r])` with precomputed `VectorShuffle` constants — OR the masks; the number of
matches is `mask.trueCount()`; then advance the side whose last element is smaller (both when equal), exactly as
the scalar merge does but 8×8 pairs at a time. Values are unsigned 16-bit, so use `UNSIGNED_*` comparisons for the
advance test; equality is sign-agnostic.

- **H11.** For the count-only kernel no `compress` is needed, only `compare`, `or`, `rearrange` (in-lane on a 128-bit
  species) and `trueCount`, all of which intrinsify on any AVX2 CPU and on NEON. Expected 2–4× over the scalar merge
  when the two inputs have comparable sizes, which is exactly the branch `unsignedIntersect2by2` sends them to.
  The galloping branch (sizes differing by more than 25×) stays scalar; SIMD does not help a binary search.
- **H12.** The materializing variant needs the matched lanes written out in order: `a.compress(mask).intoCharArray`
  is native only with AVX-512 VBMI2 (16-bit lanes); elsewhere it is a 256-entry shuffle-table emulation per 8 lanes
  (a `VectorShuffle[256]` constant table plus `rearrange`), or widening to 32-bit lanes to use `vpcompressd` on
  AVX-512F. Doable, but the gain is uncertain on AVX2 and the table costs 256 shuffle objects per species. Low
  priority; the count kernel is the one that matters for facets.

### 5.4 Array-vs-bitmap probes

`BitmapContainer.and(ArrayContainer)` (`:200-209`) and `andCardinality(ArrayContainer)` (`:256-264`) probe one bit
per array element: `(bitmap[v >>> 6] >>> v) & 1`. Vector mechanism: widen 8 `char`s to an `IntVector`, compute
word indices (`v >>> 6`) into an `int[]` index map, gather the words with `LongVector.fromArray(S, bitmap, 0, idx, 0)`,
shift each lane by `v & 63` (per-lane variable shift, `vpsrlvq`), AND with 1, sum. **H13.** Gathers run at roughly
one lane per cycle on current x86, so the gain is 1.5–2× at most and the scalar loop already has no branches;
low priority.

### 5.5 Bit extraction, runs, keys

- `Util.fillArrayAND` / `fillArrayANDNOT` (`:218-231`): one `numberOfTrailingZeros` per set bit. A vector version needs
  a per-byte position table (as CRoaring's `bitset_extract_setbits`), ~2× in C. **Not worth a Java kernel now.**
- `RunContainer` (3 648 lines of interval arithmetic), `RoaringArray` key merges, `advanceUntil` / binary searches:
  data-dependent control flow, **no**.

### 5.6 Where the kernels would live

Inside `evita_roaring_bitmap` next to the loops they replace, behind the provider of §3, so that every caller in the
engine — `AndFormula`, `OrFormula`, `NotFormula`, `FacetGroup*Formula`, `FastAggregation.workShyAnd` (which already
uses a 1024-long aggregation buffer, `FastAggregation.java:109-122`) — benefits without change. Sync with upstream
RoaringBitmap (`roaring-bitmap-sync` skill) gets one more file family to carry; upstream Java RoaringBitmap has no
SIMD path to merge, which keeps the fork's delta explicit.

---

## 6. Candidate 3 — price termination formulas

### 6.1 What the three `computeInternal` methods do

All three (`LowestPriceTerminationFormula.java:453-590`, `SumPriceTerminationFormula.java:331-440`,
`PlainPriceTerminationFormulaWithPriceFilter.java:243-320`) share one shape:

1. take the delegate's entity bitmap and walk it in batches (`RoaringBitmapBatchArrayIterator`);
2. for each entity id, ask every `PriceRecordLookup` (one per price list, in priority order) to
   `forEachPriceOfEntity(entityId, lastExpectedEntity, consumer)`;
3. the lookup (`ResolvedFilteredPriceRecords.PriceRecordIterator`, `:110-215`) binary-searches an `int[] entityIds`
   side array (built once per lookup) inside a narrowing window, then invokes the consumer per
   `PriceRecordContract` **object**; the super-index path (`PriceListAndCurrencyPriceSuperIndex.forEachLowestPriceRecordOfEntity`,
   `:315-327`) goes through a `Map<Integer, EntityPrices>` holder;
4. per entity, an HPPC `IntObjectHashMap<PriceRecordContract>` deduplicates by inner record id across price lists;
5. the predicate (`PricePredicate`, an `int` range `fromAsInt..toAsInt` applied to `priceWithTax` or
   `priceWithoutTax`, `:113-116`) and the min / sum / plain decision run on the map's values; `Sum` allocates a
   `CumulatedVirtualPriceRecord` per entity;
6. results go to two `RoaringBitmapWriter`s and a `CompositeObjectArray` of chosen records.

`PriceRecord` is a record of five `int`s (`PriceRecord.java:51-56`) — array-of-structs, boxed behind an interface
with three implementations.

**H14.** Nothing in this shape is vectorizable: the data are objects reached through a callback, the inner loop
branches per record, and the per-entity work is a hash map. The Vector API needs contiguous primitive arrays and a
loop body without data-dependent control flow. **As written: NO-GO.**

### 6.2 What would make it feasible — struct of arrays

The lookups already build a parallel `int[] entityIds` (`ResolvedFilteredPriceRecords.java:121-148`). Extending
`FilteredPriceRecords` to carry the whole record set as parallel primitive arrays sorted by entity id, then by
price-list priority — `int[] entityId, innerRecordId, priceWithTax, priceWithoutTax, internalPriceId` — turns the
three formulas into array passes:

- **Range predicate** (`PlainPriceTerminationFormulaWithPriceFilter`, and the `sellingPricePredicate` step of the
  other two): `IntVector.fromArray(price, i).compare(GE, from).and(compare(LE, to))` gives a mask for 8 (AVX2) or
  16 (AVX-512) records per step; `mask.toLong()` written into a bitset, or `compress` on the entity-id vector, yields
  the matching records. This is the textbook columnar filter (H15: 4–10× over the per-object predicate on the same
  data, and allocation-free).
- **"Entity has at least one matching price"**: OR-reduce the predicate mask over each entity's segment; segment
  boundaries are `entityId[i] != entityId[i+1]`, itself a vector compare of two shifted loads.
- **Lowest price per entity with price-list priority and inner-record dedup**: a segmented reduction with a
  secondary key. Segments are short (one to a few prices per entity in a real catalog, per the `EntityPrices`
  javadoc), so the vector version degenerates to scalar work on masks; the win here comes from the layout, not the
  lanes. Keep scalar.
- **Sum with missing-component prices**: same, scalar over the SoA arrays.
- **Joining the delegate bitmap with the price rows**: the delegate's ids arrive in sorted batches; the row
  `entityId[]` is sorted; this is a sorted-int intersection (the `IntVector` cousin of §5.3), or simpler, a
  per-batch binary search that the lookups already do.

**H16.** The SoA refactor is the real optimization here (cache-friendly scans, no callbacks, no per-entity map, no
`CumulatedVirtualPriceRecord` allocation); SIMD on the predicate is a second-order add-on. It is a self-contained
line of work under the price index, with `PriceRecordBackingBenchmark` and the price-filter functional suites as
harness. **Conditional GO**, ordered after the layout change.

### 6.3 A rewrite that allows SIMD *and* beats the current search

The question is not only "can lanes be applied" but "is there a shape that is faster than today's binary search
over a shrinking window, and that SIMD then accelerates further". There is, and it is a **sorted merge-join over
columns**, with search kept only for the sparse case.

**What the current shape costs.** Per entity of the delegate, per price list: one `Arrays.binarySearch` over the
window (`ResolvedFilteredPriceRecords.java:195`), a rewind to the first record of that entity, one callback per
record, one hash-map probe and put per record, and per entity a map `clear()` (`Lowest`) or a fresh
`IntObjectHashMap` plus a `CumulatedVirtualPriceRecord` (`Sum`, `:395-420`). The searches are cache-cold at their
first steps and the per-record work is pointer chasing through record objects. Asymptotically it is
`|D| · P · log w + records · (callback + hash)` with `w` the window width, and none of it vectorizes (H14).

**The rewrite, in order.**

1. **Columns instead of records.** Each `FilteredPriceRecords` carries parallel `int[]` columns sorted by
   `(entityId, innerRecordId)`: `entityId`, `innerRecordId`, `priceWithTax`, `priceWithoutTax`, `internalPriceId`.
   The per-query variant is a one-pass copy of the arrays that already exist (`PriceRecordContract[]` sorted by
   entity pk, `SortingForm.ENTITY_PK`), which is enough to test the design. The persistent variant stores the
   price pages of `PriceListAndCurrencyPriceSuperIndex` as columns instead of object arrays — smaller (five ints,
   20 bytes per price, versus an object header plus reference), scannable in place, but a storage-format change
   with Kryo and backward-compatibility work; treat it as its own line of work after the per-query variant proves
   the gain.

2. **P-way merge instead of P binary searches per entity.** The delegate bitmap is walked in sorted batches
   already (`RoaringBitmapBatchArrayIterator`). Keep one cursor per price list. For each entity `e` of the batch,
   advance every cursor to the first row with `entityId >= e`; the rows `[cursor, next entity)` are that list's
   prices for `e`. Because both sides are sorted, the cursors only move forward and total movement is linear in
   the rows touched. The per-entity inner-record deduplication with price-list priority becomes a merge too: the
   `P` runs for `e` are each sorted by `innerRecordId`, so the highest-priority list wins per inner record by
   walking the runs together — no hash map, no callback, no allocation.

3. **Keep search for the sparse case, but make it wide.** When the delegate is much sparser than the price rows
   (a few hundred ids spread over hundreds of thousands of rows) a linear cursor advance scans rows that belong
   to nobody in the batch; that is the case the current window trick handles. The remedy is galloping (exponential
   probe, then narrow), which is what the cursor advance does when the gap is large. Here SIMD applies directly:
   a probe compares 8 (AVX2) or 16 (AVX-512) consecutive `entityId`s against the broadcast target in one
   `IntVector.compare(GE, e)` and `firstTrue()` gives the position, so the linear part of each gallop moves
   8–16 rows per instruction and the binary part needs `log₁₆` instead of `log₂` steps. This is strictly better
   than today's scalar binary search on the same window and it has no data-dependent branches inside the probe.

4. **Vectorize the per-row work that remains.** Segment boundaries per price list
   (`entityId[i] != entityId[i+1]`) come from one compare of two shifted loads per 8–16 rows. The range predicate
   runs over the aggregated selling-price column as a mask (`compare(GE, from).and(compare(LE, to))`, H15). For
   `Lowest`, the aggregate is a segmented `MIN`; segments in a real catalog are one to a few rows, so a masked
   `reduceLanes(MIN)` per segment is possible but a scalar min over the columns is already branch-light and
   cache-hot — keep it scalar until a profile says otherwise. For `Sum`, the same with `ADD`, plus the scalar
   missing-component step.

5. **Emit.** The output bitmap is built from the mask bits of the predicate (`mask.toLong()` into a word buffer,
   or `compress` of the entity-id vector on AVX-512), and the selected prices are appended to columns, not to a
   `CompositeObjectArray` of objects; consumers that need `PriceRecordContract` objects (sorting, histograms) get
   them from a thin view over the columns.

**Why it is more effective than now, independent of SIMD.** Sequential access instead of `P` cold binary searches
per entity; zero allocation per entity; no callbacks; the inner-record priority merge replaces a hash map per
entity. The cost model becomes `rows touched + |D| · P · probes`, where a probe is one vector compare, and the
touched rows are bounded by the windows the current code already computes. SIMD then adds a constant factor on the
probes, the segmentation and the predicate — the parts that are not memory-bound.

**What it does not change.** The semantics: the selling price is still "first price list in priority order that
has a price for this inner record", then min or sum, then the predicate — nothing here is a precomputable range
slice of the index, which is why a bitmap-only formulation (`D ∧ entityIdsWithPrice_p` through the §5 kernels)
answers only "has any price in list p" and not "selling price within range".

**Gate.** `PriceRecordBackingBenchmark` extended with the three termination shapes on a production-shaped price
set at three delegate selectivities (dense, 10 %, 0.1 %), scalar-columnar versus today first, then the vector
probe on top. Expect the columnar rewrite alone to carry most of the gain at every selectivity, and the vector
probe to matter at low selectivity where the gallop dominates.

---

## 7. Risks and constraints that apply to every kernel

- **JIT dependence (H3).** Correctness is not at risk — the fallback is exact — but performance can invert. Guard
  with the provider, and never let vector code run under `-Xint` / C1-only test configurations (test mode should
  force the scalar provider unless a test asks otherwise).
- **CPU dependence (H4).** Two of the three useful kernels differ by CPU generation. The production CPU generation
  must be known before the gate (§8) is meaningful; a Zen 5 laptop is not it.
- **AArch64.** 128-bit NEON halves every gain; SVE on Graviton is exposed only through `preferredShape()`
  (*from knowledge*: HotSpot 21 uses SVE for the Vector API when present).
- **Startup / warmup.** Vector kernels reach C2 after the usual thresholds; nothing evitaDB-specific.
- **Warnings.** One JVM warning line at startup; one javac warning per compilation of the kernel modules.
- **Upstream sync.** The roaring fork gains files upstream does not have; the sync skill's ledger must list them.
- **Memory.** None: kernels are allocation-free; shuffle tables (if §5.3 materializing is ever done) are a few KB
  of static objects.
- **H17.** The Java driver is unaffected: `evita_roaring_bitmap` and `evita_engine` are not on the driver's
  dependency path, so the JDK 17 floor and the enforcer rule (ADR `2026-09-08-jdk21-safe-modernization`) hold.

---

## 8. Recommended order and the gates (to run later, in a negotiated window)

1. **Cardinality-only facet evaluation (§4.2), scalar.** Gate: the facet-count and impact states in
   `evita_performance_tests` (`ArtificialFacetFilteringAndSummarizingCountState`,
   `ArtificialFacetAndHierarchyFilteringAndSummarizingImpactState`, and their `Client*` twins) on a
   production-shaped catalog; expect allocation per facet to drop to near zero and time to drop measurably.
   Profile afterwards to learn what share the container arithmetic still has — that number decides whether step 2
   is worth doing at all (H8).
1b. **Counting kernel for the range index (§11), scalar first.** Independent of the roaring provider; its SIMD
   emission is optional and can be added under the same provider later. Gate: `RangeIndexBlockSizeBenchmark`
   extended with enveloping / from / to queries at k = 10, 100, 1 000 thresholds, `JoinFormulaTest`,
   `DisentangleFormulaTest` and `RangeIndexTest` green, results byte-identical to today's formulas.
2. **Provider mechanism (§3) + popcount kernel (§5.1).** Gate: `RoaringBitmapGroup` / `ArrayOrRoaringBitmap` spikes
   extended with an `andCardinality` case, ≥ 3× on the kernel on AVX-512, no regression on AVX2 (kernel disabled
   there), differential test green; then the facet states again.
3. **Array-container count kernel (§5.3).** Gate: ≥ 2× on the kernel for equal-size inputs of 256–4 096 elements;
   the full roaring test suite in both provider modes.
4. **Price SoA layout (§6.2), scalar**, as its own issue; the predicate kernel only if a profile of
   `PriceRecordBackingBenchmark` and the price-filter functional cases shows the predicate stage on top.
5. Everything in §5.4–5.5 stays parked unless a profile names it.

**H18.** Expected end state: facet summaries and every multi-way AND in query evaluation benefit through the
container kernels; price filtering benefits only after its own layout work; the generators, run containers and
key merges never change.

---

## 9. Open questions (to verify, not to guess)

- Which operations intrinsify on the production CPU generation (H4): the installed `libjvm.so` settles which
  code paths exist (§2), not which one a given CPU takes; run any small Vector API loop with
  `-XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics` on the target and read the `VectorSupport` lines.
- Whether C2 on 21 auto-vectorizes the `bitCount(a & b)` reduction already (H9): `-XX:+PrintCompilation`
  `-XX:CompileCommand=print` on `BitmapContainer.andCardinality` — if it does, §5.1's gain shrinks to the AVX-512
  popcount width advantage only.
- Share of container arithmetic in the facet-summary profile after §4.2 (H8).
- Which HNSW library the P6 spike picks and whether it already carries a Panama provider with the same
  `--add-modules` requirement (jVector does, *from knowledge*), which would make §3 step 4 a shared cost.

---

## 10. Advisory and verification log

- **2026-09-10 08:55–09:00 UTC, Codex advisory pass (job `task-mtval5ka-mtvxb7`, fresh thread, read-only):** ran
  for five minutes, fetched OpenJDK sources and inspected `libjvm.so`, then failed on the Codex usage limit ("try
  again at 11:38 AM") before emitting any verdict. The only output is a preamble sentence; it is **not** a verdict on
  any hypothesis and must not be read as one. A retry was scheduled for after the limit reset and cancelled when the session ended; the independent check of
  the hypotheses, `H9` and `H22` first, is still owed.
- **2026-09-10 09:02 UTC, own check of the installed HotSpot binary** (`nm -C --defined-only
  /usr/lib/jvm/java-21-openjdk-amd64/lib/server/libjvm.so`): found the popcount, compress/expand and rearrange match
  rules listed under H4. Outcome: the original H4 claim that `BIT_COUNT` on long lanes is not intrinsified on AVX2
  was **wrong** and has been corrected above (the AVX2 lookup-table path exists in 21.0.12); the claim that
  `compress`/`expand` are AVX-512-only on 21 is **confirmed**; 16-bit `rearrange` is intrinsified on AVX2 in both
  widths. §1, §3 step 6, §5.1 and §9 were amended accordingly.
- H9 (whether C2 auto-vectorizes the `bitCount(a & b)` reduction) remains unverified; the presence of the
  `vpopcount_avx_reg` rule means SuperWord *could* emit it on AVX2, so the `-XX:+PrintCompilation` check in §9 is
  the deciding one and §5.1's gain shrinks if it already does.

---

## 11. Candidates 4 and 5 — `JoinFormula` and `DisentangleFormula` (range index)

### 11.1 What they compute, and for whom

- Only `RangeIndex` builds them (`RangeIndex.java:680-709`). `createDisentangleFormulaIfNecessary(id, left, right)`
  receives two families of bitmaps: the `starts` and `ends` of the threshold points (`TransactionalRangePoint`,
  one `TransactionalBitmap` each, `TransactionalRangePoint.java:73-74`) over a prefix or suffix of the sorted point
  sequence — `getRecordsFrom` (`:526-542`), `getRecordsTo` (`:554-573`), `getRecordsEnvelopingInclusive`
  (`:581-620`), `getRecordsWithRangesOverlapping` (`:721-736`).
- `JoinFormula.computeInternal` (`:338-356`) is a k-way merge that keeps duplicates: a two-way fast path
  (`joinTwoBitmaps`, `:114-150`) or a `PriorityQueue<IntIteratorPointer>` over 256-int batch iterators with one
  poll and one offer per element (`O(N log k)`), appended to a `CompositeIntArray` and wrapped as an `ArrayBitmap`.
- `DisentangleFormula.computeInternal` (`:277-308`) is a two-cursor merge over `PrimitiveIterator.OfInt`s in which
  every control occurrence cancels one main occurrence (`computeNextInt`, `:314-343`); the output goes through a
  `RoaringBitmapWriter`, which collapses whatever duplicates survive. Traced on the javadoc example
  (`[3,3,6,9,12]` vs `[2,3,4,6,8,10,12]` → `[3,9]`), the net rule is
  `v ∈ result ⟺ count_main(v) > count_control(v)`.
- **H19.** The `Join → Disentangle` pair is therefore a *signed multiplicity count*:
  `{ v : Σ_i [v ∈ P_i] − Σ_j [v ∈ M_j] > 0 }`, with `P` the `starts` (or `ends`) family and `M` the other. The
  duplicate-carrying `ArrayBitmap` is only an intermediate encoding of the counts; no consumer needs the duplicates.
  The `getAsOrFormula` path taken when the right side is empty (`:680`) is a plain set union and stays as it is.

### 11.2 Why the current shape is expensive

`N = Σ|P_i| + Σ|M_j|` elements each pass an iterator virtual call, a `log k` priority-queue reheap, a
`CompositeIntArray.add`, then a second per-element merge, then a `RoaringBitmapWriter.add`. For an enveloping query
on a date-range index with thousands of thresholds, `k` is in the thousands and `N` is the number of range endpoints
up to the threshold — for a catalog in which every product carries validity ranges, that is a pass over most of the
catalog's endpoints per query, mitigated only by `envelopingNowCache` (`now` queries) and the formula cache. None of
it vectorizes: it is iterators and data-dependent branches, the same objection as H14.

### 11.3 The rewrite: count per chunk, emit by mask

One formula replaces the pair (working name to be fixed after the naming check; `MultiplicityFormula` and
`CountingRangeFormula` are free as of today):

1. **Inputs:** `Bitmap[] plus`, `Bitmap[] minus`, all roaring-backed
   (`RoaringBitmapBackedBitmap.getRoaringBitmap`).
2. **Walk the union of container keys** (the 16-bit high halves) of all inputs in ascending order — a k-way merge
   over tiny key arrays, or a 65 536-bit presence set filled from every input's keys.
3. **Per key, count into a scratch `short[65536]`** (128 KiB, taken from a bounded pool in the manner of
   `SharedBufferPool`, not a `ThreadLocal` — the 2026-08-19 usage-statistics ADR already rejected per-thread
   striping as hostile to virtual threads), zeroed with `Arrays.fill` (auto-vectorized). For each input container
   at that key: `ArrayContainer` → scatter `cnt[v] += ±1` per element (scalar, ≤ 4 096 per container);
   `RunContainer` → `cnt[a..b] += ±1` per run, or ±1 at the run borders plus one prefix scan when runs are many;
   `BitmapContainer` → scatter per set bit, or, when several dense inputs meet at one key, bit-sliced vertical
   adders over the 1 024 words (word-wise `AND`/`XOR`/`OR` planes, auto-vectorized or explicit `LongVector`).
4. **Emit by mask.** For each block of 16 (AVX2) or 32 (AVX-512) counters, `ShortVector.compare(GT, 0)` yields a
   mask whose `toLong()` is directly a slice of one word of the result `BitmapContainer` (with `byte` counters the
   block is 32 or 64 lanes and one AVX-512 compare produces a whole word). `BIT_COUNT` on the words gives the
   cardinality; convert to an `ArrayContainer` when it is ≤ 4 096, as `BitmapContainer.and` already does.
   **H20.** Because the mask *is* the output, this step needs no `compress`, no shuffle table and no gather — only
   `compare` and `toLong()`, which intrinsify on every AVX2 CPU and on NEON; it is the cleanest SIMD fit in this
   document.
5. **Sparse chunks.** When the elements at a key are few, the 65 536-counter scan (1 024–2 048 vector compares,
   a few microseconds) dominates; below a measured threshold fall back to today's sorted merge for that chunk, or
   restrict the scan to the `[min, max]` value span the inputs touched.
6. **Fusion.** `getRecordsEnvelopingInclusive` ANDs two such results (`before`, `after`); with two counter arrays
   the fused kernel emits `cntBefore > 0 & cntAfter > 0` in one pass and the `AndFormula` disappears.

Counter width: `short` (a record with more than 32 767 ranges in one 65 536-id chunk would overflow, which is not a
realistic shape; `byte` overflows at 127, which validity histories can reach, so not `byte`).

Complexity: `O(N)` scatters plus `O(chunks × 1 024)` vector operations plus the emission, against today's
`O(N log k)` merge plus an `O(N)` merge, both with per-element virtual calls and allocation. **H21.** The expected
gain is 5–20× for `k` in the hundreds and `N` around 10⁵, with the emission step contributing a constant factor on
top; this is an estimate to be measured with `RangeIndexBlockSizeBenchmark` (the 2021 figure in
`DisentangleFormula.java:69-73`, 3–8 ms per computation over 1 M numbers, is the shape of the scalar baseline).
`JoinFormulaTest`, `DisentangleFormulaTest` and `RangeIndexTest` are the correctness harness; results must be
byte-identical to the current formulas.

Where SIMD applies, precisely: the emission (step 4), the vertical adders for dense inputs (step 3), the counter
reset (already vectorized), and optionally a prefix scan for run containers. The scatter increments stay scalar;
AVX-512 scatter with conflict detection is not exposed by the Vector API.

### 11.4 A question the counting form raises

**H22.** With exact counts, "valid at `t`" is decided by the prefix alone: `#starts(≤ t) − #ends(< t) > 0`. The
`after` side that `getRecordsEnvelopingInclusive` ANDs in (`:600-604`) would then be redundant *provided* every
range contributes both endpoints to the index — the border sentinels at `Long.MIN_VALUE` / `Long.MAX_VALUE`
(`RangeIndex.java:283-288`) suggest open ranges do. If `RangeIndexTest` confirms it, the enveloping query halves.
Nothing above assumes it; it is flagged for verification, not applied.
