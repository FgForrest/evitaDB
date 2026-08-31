---
title: Cut the trigram substring query path's per-candidate cost sixfold, and leave the selectivity gate alone
date: 2026-08-31
updated: 2026-08-31 15:10
status: accepted
kind: optimization
issues: [1454]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/index/trigram, evita_engine/src/main/java/io/evitadb/index/invertedIndex, evita_engine/src/main/java/io/evitadb/index/bPlusTree, evita_engine/src/main/java/io/evitadb/index/bitmap, evita_engine/src/main/java/io/evitadb/core/query/algebra/base, evita_engine/src/main/java/io/evitadb/core/query/filter/translator/attribute]
supersedes: []
superseded-by: []
relates: [2026-08-24-fulltext-search-lucene-vs-inhouse, 2026-08-31-front-coded-column-stores-wtf8]
---

# Cut the trigram substring query path's per-candidate cost sixfold, and leave the selectivity gate alone

The trigram substring accelerator answers `attributeContains` by intersecting trigram postings into
candidate value ids and then verifying each candidate exactly. Verification dominated, and it was
doing the same work several times over. Three changes removed that redundancy — one bucket
resolution per candidate instead of three, one posting read per trigram per query instead of two, and
no verification at all when the pattern is exactly one trigram wide and the predicate is plain
containment. Together they made the accelerated path **3.20x faster** on a production corpus. A
fourth change, loosening the planner gate that decides whether to use the index at all, was measured,
committed, adversarially reviewed, and **withdrawn** — the measurement was confounded and the gate
turned out to be structurally wrong for reduced-index fan-out in a way no scalar can fix.

## Why

The accelerator shipped correct and fast enough to be worth having, but nobody had asked where its
time actually went once it was admitted. On a production e-commerce catalog the answer was
uncomfortable: **55-87% of an accelerated query was verification**, and verification was repeating
lookups it had already made.

The constraint that shaped every option is that **the index may not grow**. Positional postings —
the obvious next step, and what Zoekt and SQLite's FTS5 `detail=full` do — cost roughly 2x index heap
and were rejected outright before this work began. Everything here therefore had to come from doing
less work per candidate, not from storing more.

### Previous state

Per matching candidate, `InvertedIndex#getRecordsOfValueIdsMatching` asked the bucket tree three
separate questions — *what value does this id name?*, *which leaf page does the answer depend on?*,
*what records does that bucket hold?* — and each resolved the same slot independently, the third by a
full root-to-leaf descent by key. Per query, the selectivity gate priced the pattern by reading every
trigram's posting to find the cheapest, discarded them, and the intersection then read every one
again. And every candidate was verified with the caller's exact predicate, including the candidates
of a three-character search, where the trigram *is* the pattern and the predicate can only ever
return true.

## Decisions

| # | Decision | Rejected alternative | Rejected because |
|---|---|---|---|
| 1 | **Fuse the three per-candidate probes into one** (`097d0ee80`) | Cache the resolved value alongside the candidate array | Costs heap per query and still pays the descent once; the slot already holds every answer, so caching is paying to avoid re-asking a question that need not be asked twice |
| 2 | **Return the postings from the pricing probe** (`de82612d4`) | Leave the double read; it is "only" a few tree descents | Measured +22 ns per trigram, and `TrigramPostingStore#get` allocates an `Optional` per call. Small but strictly free — the gate and the intersection now share one read |
| 3 | **Skip verification when it is provably the identity** (`68d7681f0`, `cfc13717e`) | Skip it whenever the pattern produced one trigram | **Wrong condition.** `extractUniqueTrigrams` deduplicates, so `0000` is four code points collapsing to the single trigram `000`, whose posting holds values that do not contain `0000`. The condition must be on the **code point count** |
| 4 | **Do not build the escalating gate (B3)** | Intersect the two cheapest postings for a tighter bound before declining | Sound and provably safe, and worth **zero** here: 10 of 14 declined patterns carry one trigram so there is nothing to intersect, and the other four miss the threshold by an order of magnitude even with a perfect estimate. Design recorded at the branch where it would go |
| 5 | **Leave `REQUIRED_NARROWING_FACTOR` at 12** (`5fc572b58`, reverted by `681494300`) | Lower it to 4 on a measured crossover of 55% | The crossover run forced the gate by **lowering the constant being measured**, which also re-plants a benchmark class sized on `n / factor` into the shared corpus; and "admits at most 1/factor of the corpus" is false for fan-out (below) |
| 6 | **Fold the intersection into an accumulator the query owns** (`3ebcf184f`, `aa1d3c0ab`) | Keep allocating a fresh Roaring result per posting | A chain of static `and`s allocates a bitmap, its `RoaringArray` and its containers per trigram and drops the previous set immediately. Only the first is needed: `and` is the ownership boundary, and everything after it folds in place. Also demotes to the `int[]` path once the accumulator is narrow enough, since a bitmap `and` costs work proportional to the wider side |
| 7 | **Answer a posting lookup with `null`** (`5e169cb9b`) | Leave the `Optional` | It was unwrapped immediately, once per trigram per query, and escape analysis is not guaranteed across the polymorphic descent. No fork worth the name — recorded only so the measured +22 ns of decision 2 is not later misattributed to this wrapper |
| 8 | **Key the value id directory by primitive longs** (`67dffdb0a`, test fix `b5a2d59dc`) | Wait for a measurement | The leaf indirection was a `Map<Long, …>` keyed by a value that is already a `long`, so every candidate boxed one — and leaf ids pass the `Long` cache's 127 almost at once, several hundred leaves per attribute here. Taken on mechanism: an allocation removed is workload-independent, unlike a threshold retune |
| 9 | **Verify containment against the stored UTF-8 bytes** (`88b48fe9f`) | Refine `StringSearchShape` into `CONTAINS`/`PREFIX`/`SUFFIX` so anchored predicates could match bytes too | The enum's `ANCHORED` is documented as the safe answer for a caller that is unsure, and splitting it destroys that. Containment alone needs no change to it, and anchored predicates keep verifying as before |
| 10 | **Fold an OR's single-record operands inside `computeInternal`** (`16e0e1959`) | Coalesce what `MatchedBuckets` carries | Wrong twice: tests legitimately assert `recordSets().length` is the number of buckets answered, and collapsing the operand array to one entry takes `toSortedOrFormula`'s `ConstantFormula` branch, which carries **no leaf-version token set** — an allocation win traded for a staleness-tracking hole |

A fifth change was not a decision but a defect found on the way: `intersectFromBitmapPosting` assumed
a posting's representation followed its cardinality and cast every later posting to a bitmap
(`c4653bdb4`). Postings promote to Roaring at 128 but demote to `int[]` only at 64 — the asymmetry is
deliberate hysteresis — so cardinalities 65..128 admit **both** forms and an ordinary query threw
`ClassCastException`. The bug predates this work.

## Key technical details

- **`TransactionalBucketBPlusTree#recordsOfMatchingValueId`** is the fused probe. Every slot-dependent
  read happens **before** either caller-supplied callback runs. That ordering is load-bearing, not
  tidiness: a predicate inserting a lower key into the same leaf would shift the parallel columns and
  the method would return a *neighbouring* bucket's records. The chain it replaced was immune for a
  different reason — it re-found the bucket by key afterwards — so the immunity had to be
  re-established rather than inherited.
- **`PatternPostings`** is per-query scratch and package-private. The postings it points at are the
  index's own, shared by reference across index versions, and are strictly read-only. It carries its
  bound explicitly and is **not** ordered on construction: the gate needs only the minimum, which one
  linear pass finds, so a declined pattern is never charged for an insertion sort that is quadratic on
  a descending input.
- **`StringSearchShape`** is how a caller states what its predicate needs from an occurrence. It is
  an enum rather than a boolean because `match(..., true)` beside an `endsWith` predicate would
  silently return wrong answers. The claim is **witnessed, not trusted**: before skipping verification
  the predicate is applied to the pattern flanked by NUL on both sides, which containment accepts and
  an anchored predicate refuses. One call per query against a verification pass over every candidate.
  It is a *necessary* condition, not a proof — a predicate testing containment **and** something else
  passes the witness and is still mis-served. No finite set of witnesses characterises an arbitrary
  `BiPredicate`, so that gap is closed by the enum instead: it has no member such a predicate could be
  declared under, whereas passing an anchored predicate under `CONTAINMENT` is the plausible slip, and
  that is the one the witness catches.
- **Skipping verification also skips two incidental checks** — the `asString` type guard, and the
  front-coded column's corrupt-blob premise, since the key is never decoded. Neither was this path's
  to make; both are documented at `TrigramSubstringSearch#verificationIsRedundant`.
- **Both branches of the intersection now dispatch on what a posting actually *is*.** Nothing may
  infer a posting's representation from its cardinality.

## Verification

Measured on a **production e-commerce catalog**, three identifier-like ASCII attributes (118,772 /
118,508 / 86,455 distinct values), 171 discovered patterns, both arms forced and compared
**bitmap-by-bitmap before any timing was taken** — on real data there is no oracle but arm parity, and
a driver producing numbers without establishing it would be timing two different questions.

Cumulative on the trigram path, same patterns before and after:

| pattern class | n | median | p90 | max |
|---|--:|--:|--:|--:|
| all | 171 | **3.20x** | 7.34x | 8.45x |
| exactly 3 code points | 98 | **5.98x** | 8.05x | 8.45x |
| longer | 73 | 1.66x | 3.15x | 4.31x |

Against the scan it displaces, the median went from **237x to 989x**. The scan beat the accelerated
arm on **9 of 171 patterns before and 0 after**.

Mechanisms confirmed individually, not just outcomes: the fused probe cut the per-match term from
**0.61 to 0.15 µs** and stayed linear in match count on both sides; the single posting read saves a
flat **+22 ns per trigram** (p25 +17, p75 +35); the verification short-circuit saves **+54 ns per
candidate** (p25 +51, p75 +56), about 40% of the 129-141 ns those rows spend per candidate, matching
what is removed — a front-coded restart-point walk, a `String` allocation and a `contains` call.

**Decisions 6 to 10 were measured together**, after being taken on mechanism alone. Two shaded jars — `a09d74f4b`
and the tip — built one at a time and copied aside, because a shared local repo lets the second `install`
overwrite the first. **Both jars were verified with `javap` before either was trusted**: the tip carries
`toRoaringBitmapsFoldingSingles`, `supportsUtf8Matching`/`containsUtf8At` and `retainRemaining`, and the baseline
carries none of them. `SubstringQueryBenchmark` through the public API, `arm=TRIGRAM`, 100,000 entities, every
cell confirmed `accelerated=true` and both arms confirmed to build an identical fixture.

The instrument is **allocation**, because four of the five changes remove allocations rather than work, and
`gc.alloc.rate.norm` is near-deterministic where timing at this scale is not:

| cell | matches | baseline B/op | tip B/op | change |
|---|--:|--:|--:|--:|
| THRESHOLD | 8,333 | 3,453,052 | 794,930 | **−77%** |
| MEDIUM | 1,000 | 490,105 | 109,512 | **−78%** |
| RARE | 4 | 31,936 | 22,552 | −29% |

**`RARE` is the control, and it is what makes the rest readable.** With four matches it moved by a FIXED
9,384 B/op rather than proportionally — which separates the two mechanisms: ~9.4 KB per query scaling with
*trigrams* (the accumulator and the posting lookup, ~5 trigrams for these patterns), and ~320–370 B per *match*
on top of it (318 B/match at THRESHOLD, 371 B/match at MEDIUM — two cells 8x apart agreeing within 15%). That
per-match figure is also what the removed objects add up to: a `SingleRecordBitmap`, an `int[1]`, a Roaring
bitmap with its array and container, a `String` with its `byte[]`, and a boxed `Long`. Had the control scaled
proportionally, the saving would not be coming from where these commits claim it does.

End-to-end latency, measured separately with no profiler attached, 3 forks x 5 iterations (15 samples per cell):

| cell | baseline µs/op | tip µs/op | change | 99.9% intervals |
|---|--:|--:|--:|---|
| THRESHOLD | 1,461.0 ±47.8 | 1,281.6 ±17.4 | **−12.3%** | separated by 114.2 µs |
| MEDIUM | 147.2 ±2.3 | 131.8 ±2.9 | **−10.4%** | separated by 10.3 µs |
| RARE | 5.7 ±0.2 | 5.0 ±0.1 | **−13.4%** | separated by 0.5 µs |

A single-fork run with the profiler attached put MEDIUM's intervals in overlap; the fork replication is what
made the timing claim sayable, and it is not sayable without it.

**These figures do not transfer to the production pattern mix unchanged.** Every benchmark pattern class is 6–7
characters, so the byte verifier applies to every cell measured — whereas 98 of the 171 production patterns are
exactly three code points and already skip verification, where it does nothing at all. The allocation result is
the durable claim; the latency figure is an upper bound on a workload made entirely of long patterns.

Decision 6's hazard is pinned rather than argued. `shouldNotMutateThePostingsAnIntersectionReads` builds a fixture
where the answer is a **strict subset** of the cheapest posting — without that an in-place fold writes back
identical contents and the corruption is invisible — and applying the obvious simplification (seed the accumulator
from `postings[0]`) reddens it alone, with the index's own posting shrunk from 180 ids to 150. The container-level
safety was verified by enumeration, not by reading the javadoc: all 18 `and`/`iand` implementations in the three
vendored container classes were checked, and none returns its argument, so no posting's container can alias into
an accumulator that has already declared itself sole owner.

Decisions 9 and 10 rest on equivalence claims that are asserted rather than argued. The byte matcher is checked
against `String#contains` over a cross product of keys and patterns covering NFD combining marks, precomposed
forms, supplementary characters and three-byte sequences — the claim being that byte containment and code-point
containment are one predicate, because UTF-8 is self-synchronizing and a continuation byte can never begin a
sequence. **Inverting the matcher reddens 13 of 30 substring tests**, which is what establishes the query path
reaches it at all; without that check a wired-but-unreached change passes every suite and reads as finished.
Corrupting decision 10's folded ids reddens 3 of its 4 tests, the fourth being the single-operand case that
deliberately takes the unfolded path. The `OrFormula` change was verified against **5201 tests** across the query,
cache and index suites rather than the substring ones, because that class is shared by the whole engine.

Correctness: `Tests run: 478, Failures: 0` across the B+tree, inverted-index, trigram and substring
suites. Every guard added here is **calibrated by counterfactual** — reverting the cast fix reddens
only the representation-overlap test, replacing the code-point condition with a trigram count reddens
only the deduplication test, and dropping the shape check reddens only the anchored test. The
verification short-circuit is additionally proven on real data: because the shape is a runtime branch,
both settings run from one jar over one corpus and must return identical bitmaps, which arm parity
verified per pattern across four runs.

## Consequences & open follow-ups

- **The selectivity gate does not mean what its name says, for reduced-index fan-out.**
  `accelerationThreshold` compares a **global** candidate bound against `sumDistinctValuesUpTo`, which
  adds up each target index's own bucket count — and those counts overlap, because a value living in
  twenty reduced indexes is counted twenty times. A fan-out whose sum reaches `k` times the global
  distinct count therefore admits a posting covering `k / factor` of the whole attribute. This is true
  at 12; a smaller factor only widens it. **No scalar fixes this** — it needs a second input or the
  planner's own costing. This is the most consequential thing the campaign found and the reason
  decision 5 was withdrawn rather than merely re-measured.
- **The gate holds most of the workload's wall-clock, and every optimization on the admitted path raises its
  share.** Re-costing the 159 patterns measured across three attributes from their own recorded per-arm timings —
  a declined pattern charged the scan it actually runs, an admitted one charged the trigram arm — puts **59% of
  the total (90.5 ms of 153.4 ms) in the 16 patterns the gate declines**. Applying this campaign's own per-class
  speedups to the admitted side raises that to roughly **80%**, because every microsecond taken off the admitted
  path shrinks its own denominator. This is the first quantification of what the gate forfeits, and it reorders
  everything that remains: the admitted path has diminishing returns by construction.
  **The caveat is load-bearing, and it cannot be lifted before the accelerator ships.** Those patterns are
  weighted equally because of how they were discovered, not because production traffic looks like that — and this
  is new functionality, so there is no traffic to count declines in. The discovered set is the best model of the
  workload that can exist until the feature has users.
  **That makes the conservative gate the correct launch posture, not a defect to fix now.** A decline falls back
  to the scan, which is exactly what every one of these queries did before this index existed, so a forfeited win
  costs a user nothing they already had — and the campaign measured a decline at within 2% of 1.00x, so it is not
  even a tax. A gate loosened on unvalidated numbers fails the other way: it makes queries **slower than before
  the feature**, on day one, visibly. At 12 the gate errs toward declining, and for a first release that is the
  right direction to err in. Instrument decline counts at launch and revisit this with the first real ones.
- **What remains on the admitted path is small, and its reach is smaller than it looks.** An external read-only
  consultation, given this record and the commits, proposed matching a candidate's UTF-8 bytes without
  materialising a `String`; replacing the boxed leaf maps with primitive ones; coalescing single-record matched
  buckets before the final union; and regrouping candidates by leaf. Three reach corrections apply, and each was
  checked against the measured table rather than reasoned about:
  **55% of admitted time is spent on patterns of exactly three code points, which already skip verification** —
  the byte matcher cannot touch it; the same 55% is single-trigram, so there is no intersection there to improve
  either; and the most expensive admitted queries are `N/V≈1` with near-zero false positives, which makes the
  singleton-coalescing idea — ranked lowest of the four — the one aimed squarely at the actual hot spot. Cost
  tracks **hits** (0.412 µs) more closely than candidates (0.357 µs). Regrouping by leaf straddles zero at the
  candidate widths this corpus produces and should be instrumented before it is built.
- **A targeted test run is scoped by where a class sits, not by what it touches.** Decision 8 shipped green and
  broke `LeafIndexHeapSizeTest`, which reaches the directory reflectively and cast its leaf table to
  `java.util.Map`. The suites it was verified against were the tree, inverted-index and trigram ones; that test
  lives under `io.evitadb.index` and was not among them. When a change alters a type that anything reads
  reflectively, the net has to be drawn around the *readers*, and reflection makes those invisible to the compiler.
- **What remains after these: deferring the record set until a candidate matches.** The byte matcher settles a
  candidate without touching its records, so a rejected candidate need not have a bucket materialised at all —
  worth 30–60 ns on the patterns whose false-positive rate runs to 58–89%. It is not taken here because it means
  reading the records *after* a match test, and the ordering of those reads is a load-bearing invariant against a
  mutating predicate (see `recordsOfMatchingValueId`). A built-in matcher cannot mutate, so the exception is sound
  — but it must be written down as an exception rather than discovered later as an inconsistency.
- **Under an unmeasurable workload, prefer the optimizations whose benefit does not depend on the workload.** A
  per-candidate cost reduction — one less allocation, one less `String` built — pays the same fraction of its
  own stage whatever mix of queries arrives, and is provable on the corpus at hand by arm parity plus a
  counterfactual. Gate tuning is the opposite: its entire value is a function of the query distribution, which is
  precisely the thing that cannot be known before release. This is why the admitted-path items rank above the
  gate for now despite the gate holding far more of the measured time — not because the 59% is wrong, but because
  it is the one number here that release will either confirm or destroy.
- **The gate is nonetheless costing real work.** On the measured corpus every one of the 14 patterns
  it declines would have been faster accelerated (2.13x-5.34x, median ~4x), and none of the 152 it
  admits loses. It should not be assumed correct merely because it is current — but it has already
  been wrong in both directions once each, so the bar for moving it is a measured sign change and not
  a plausible argument. What would settle it: a width that wins and a wider one that **loses**, with
  confidence intervals, at `n >= 1e6`, on a corpus that does not change between arms, plus a fan-out
  case and a long multi-trigram pattern.
- **Never force the gate by lowering `REQUIRED_NARROWING_FACTOR`.**
  `SubstringPatternClass.THRESHOLD` plants into `n / factor` values and every class plants into the one
  shared corpus, so lowering it lengthens every value and moves the cost being measured. Force the arm
  at the call site with a counter answering `Long.MAX_VALUE`. Recorded at the benchmark too.
- **Adding pattern classes changes every generated fixture**, so scores may not be spliced across such
  a change. This invalidates comparing the new crossover curve against the older one.
- **An optimization can narrow the window a concurrency test was calibrated against, and the test then
  passes for the wrong reason.** `LongRunningValueIdDirectoryConcurrencyTest` guards the value id
  directory's lazy rebuild; its own documentation says to enable it after touching that rebuild, and
  decision 8 touched it. Run deliberately, it passed — but so did its counterfactual (the `synchronized`
  removed from `InvertedIndex#refreshValueIdDirectory`), which means the green run proved nothing. The
  race is intact: at 8 readers it fails 3 of 3 once `ROUNDS` goes from 500 to 2000, on both shapes the
  class documents. The cause is decision 8 itself — a shorter rebuild is a narrower window to collide
  inside. **A probabilistic guard has to be re-calibrated by anything that changes the speed of the code
  it races**, which is exactly the class of change an optimization campaign consists of. The sweep also
  showed the failing round clusters at 267-450 whatever the reader count, so the window opens with tree
  size, and more readers buy overlap rather than margin.
- **`evita_test/evita_long_running_tests` must be built in a shared reactor with
  `evita_test/evita_functional_tests`, never alone**, and building it alone fails in a way that
  convincingly accuses the wrong code. It consumes the functional module's `test-jar`, and that module
  sets `maven.install.skip=true`, so the jar is never refreshed in the local repository — `-pl
  evita_test/evita_long_running_tests` therefore resolves whatever `-tests` jar is lying in `~/.m2`,
  possibly months old, and reports dozens of compile errors against `AssertionUtils` signatures in test
  code that is perfectly correct. `mvn install` on the functional module does not help; only a shared
  reactor does, because only that resolves the sibling's `target/` output:
  `mvn -pl evita_test/evita_functional_tests,evita_test/evita_long_running_tests test`. All of this is
  documented at the dependency declaration in that module's own `pom.xml`, and this campaign read the
  error before it read the pom — concluding the module was broken on the mainline, which it is not. **A
  compile error in code you did not touch is a classpath question first and a code question second.**
- **Two defects survived into the campaign's own commits and were caught by review, not by tests.** An
  `upsert` whose updater returned `null` stored it, and the `Optional.ofNullable` introduced by decision 2
  then reported the key as ABSENT — a silent wrong answer where the previous `Optional.of` would have
  thrown. And the single-record fold of decision 10 sorted record ids **signed** while Roaring orders its
  containers **unsigned**, so a negative id would have been filled into the wrong end of the container
  array; the answer stayed correct, the comment claiming an efficient one-pass fill did not. Both are
  fixed. Both share a shape worth naming: **an optimization that is answer-identical by construction
  cannot be guarded by a parity test**, and parity is what most of this campaign's tests assert.
- **`StringSearchShape`'s flank witness is a necessary condition, not a proof** — see the decision above.
  It is stated here too because the tempting next step is to "strengthen" it with more witnesses, and no
  finite set of them characterises an arbitrary `BiPredicate`. The enum is what closes the gap.
- **`FrontCodedStringColumn` did not round-trip unpaired surrogates** — a separate, pre-existing
  defect this review surfaced, since fixed by storing the column's keys as WTF-8; see
  `2026-08-31-front-coded-column-stores-wtf8`. Its one consequence for this record is that
  `TrigramSubstringSearch#encodesWithoutLoss`, described above as unreachable defence in depth, is
  now a live guard: a surrogate-bearing value can be indexed on an accelerated attribute.
- The prior record's per-candidate costs and its 9.5% crossover describe code that no longer exists.

## Related work

- [2026-08-24-fulltext-search-lucene-vs-inhouse](2026-08-24-fulltext-search-lucene-vs-inhouse/README.md)
  — the decision to build this index rather than adopt Lucene, and where the gate constant, the
  no-positional-postings constraint and the original crossover measurement come from. This record
  supersedes its per-candidate cost figures and its crossover, and contradicts its conclusion that the
  gate's next increment is a second *input to the same scalar*: fan-out shows the scalar itself is the
  wrong shape.

## Timeline

- **2026-08-30** — campaign opened against the prior record's open follow-ups; measurement harness
  rebuilt after the previous driver was lost with its scratch directory
- **2026-08-30** — decisions 1 and 2 implemented, measured and committed
- **2026-08-31** — pre-existing `ClassCastException` found by adversarial review of decision 2, fixed
- **2026-08-31** — decision 3 implemented, reviewed, hardened after review; decision 4 taken
- **2026-08-31** — decision 5 committed, adversarially reviewed, and reverted the same day
- **2026-08-31** — decisions 6 to 10 implemented, measured together and committed
- **2026-08-31** — adversarial review and a four-dimension quality pass over the campaign's commits; two
  defects fixed with tests, seven documentation claims corrected, the directory concurrency guard
  re-calibrated
