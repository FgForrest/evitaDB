---
title: Equalized histograms bucket on the quantile function and report a kernel density, not a per-bucket ratio
date: 2026-09-07
updated: 2026-09-07 13:05
status: accepted
kind: fix
issues: [1501]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/histogram, evita_query/src/main/java/io/evitadb/api/query/require/HistogramBehavior.java, evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/HistogramContract.java]
supersedes: []
superseded-by: []
relates: [2026-08-24-price-histogram-per-accessor-granularity]
---

# Equalized histograms bucket on the quantile function and report a kernel density, not a per-bucket ratio

`HistogramBehavior.EQUALIZED` shipped in 2026.2 with two independent defects, both visible on production
data. Bucket boundaries advanced **one** bucket per distinct value however many quantile targets that value
had crossed, so a price shared by hundreds of products starved every bucket after it. And `relativeFrequency`
was `occurrences / bucketWidth` sum-normalised — a quantity estimated from the single gap between two
adjacent prices, so one reprice moved it by orders of magnitude. Bucketing now samples the empirical
quantile function at evenly spaced ranks and charges a heavy value for every rank it absorbs; the height is
read off one global triangular-kernel density estimate over the value axis, normalised against the curve's
own maximum. `relativeFrequency` carries the new number — no new field, no proto change.

## Why

An equalized axis is chosen precisely so that every bucket holds about the same number of records. That makes
`occurrences` approximately constant by construction and therefore uninformative as a bar height — the thing
a reader actually perceives on an equal-pixel equalized axis is the **density-quantile function**
`f(F⁻¹(u))`, how tightly packed the values are at each slider position. The shipped code reached for the
right quantity and estimated it the cheapest possible way: from the width of the bucket itself.

That estimator has a sample size of one. On the Senesi `zrcadla-a-galerky` category (3 237 products) the
bucket holding **81 products rendered at 35.15** while the bucket holding **338 rendered at 6.03** — a
quarter of the records drawing almost six times taller. On the evitaDB demo dataset the same defect inverts a
5-product mass against a single product (0.99 against 99.01), and the shipped `histogram.md` example carries
it mid-histogram. Storefronts had already compensated: the production front end wraps the field in
`h = 10.34 · √relativeFrequency` purely to compress a dynamic range that should never have existed.

The bucketing defect is narrower but sharper. `EqualizedHistogramDataCruncher.java:271-299` used an `if`
where a `while` was needed, so a distinct value that crossed three quantile targets advanced the histogram by
one bucket and kept the other two targets' mass. Tie-free data is unaffected — it degenerates **only** on
ties, which is exactly how retail pricing looks. The same category holds 3 237 products across only **135
distinct prices**, 241 of them at 999 Kč. On a synthetic charm-priced catalogue asking for 20 buckets of 100,
13 of 20 buckets came back under half their target and 32% of the catalogue landed in bar one.

The constraint that made the fix non-obvious: **a boundary can only ever be placed at a value the data
contains.** You cannot split a price in half to balance two buckets. Every candidate design has to decide
what to do when one value is worth more than a bucket, and most of the plausible answers are wrong in a way
that only shows up on real data.

### Previous state

`EQUALIZED` walked the distinct values accumulating weight, and moved to the next bucket the first time the
running total passed `(currentBucket + 1) · totalWeight / effectiveBucketCount`, in floating-point. Heights
were `occurrences · (totalRange / bucketWidth)`, normalised so the buckets summed to 100. `EQUALIZED` padded
the result with interpolated empty buckets to reach the requested count (`padWithEmptyBuckets`);
`EQUALIZED_OPTIMIZED` did not, which was the only difference between them.

## Options considered

Seven designs were measured, not argued. The two that survived contact with production data are below; the
five that did not are in *Rejected outright*, because their failure modes are the useful part.

### Option A — quantile-function bucketing plus one global kernel density (chosen)

Thresholds are the empirical inverse CDF sampled at `k / bucketCount`, de-duplicated, compared in exact
integer arithmetic. A value that absorbs two or more ranks additionally emits a threshold at the *next*
distinct value, closing its mass into a bucket of its own. Heights come from a single triangular-kernel
density estimate over the whole value axis, normalised to the curve maximum.

- **Pros:** the height is a property of the distribution, not of the bucketing, so it is stable under
  repricing (0.01% worst drift), under replication (exactly 0), and under support fragmentation. Bucketing
  becomes a textbook object with a budget proof rather than a hand-tuned walk. Both parts are `O(D + B)`.
- **Cons:** returning fewer than `bucketCount` buckets stops being an edge case and becomes routine (1 785 of
  4 000 random catalogues), which is a client-visible contract change. The bandwidth needs two documented
  departures from Silverman, and neither is obvious from the code alone.

### Option B — damp the equalized axis (declined)

Interpolate the axis between linear and equalized, `u = (1 − α)·linear + α·F(p)`, so that occurrences regain
some meaning and can be used as the height directly.

- **Pros:** no density estimate at all; `relativeFrequency` stays a plain ratio and the whole second half of
  this record disappears.
- **Cons:** counts only become informative to the extent the axis stops being equalized — the two goals are
  in direct opposition, and `α` trades one against the other with no principled setting.
- **Rejected because:** it makes the fix a new tuning knob that has to be threaded through the query grammar,
  gRPC, GraphQL, REST **and the cache key**, in exchange for a histogram that is neither equalized nor
  standard. Worth revisiting only if a client ever asks for a genuinely intermediate axis, which would be a
  new behaviour constant rather than a repair of this one.

### Rejected outright

Each of these was implemented and measured against the production catalogues before being dropped. They are
recorded because every one of them is the *obvious* next thing to try, and the reason each fails is not
visible from the code.

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Smooth over ±1 neighbouring bucket instead of a global kernel | Ties the bandwidth to `bucketCount` — the peak walks to 2 500 at `B ≥ 30`, so asking for more bars changes the shape of the distribution | Never; the coupling is inherent to bucket-space smoothing |
| Adaptive window in rank space rather than value space | Collapses onto the price grid: density at a 600-product mass swings 60 100 → 6.0 depending on how the neighbouring prices happen to fall | A rank-space window is wanted for something other than density |
| Plain IQR over distinct prices (unweighted) | Both fragmentation-sensitive (66x) **and** outlier-*sensitive* — one distant singleton takes `h` from 138 to 1 140, three take it to 2 852. It reintroduces exactly the price-grid dependence the design exists to remove | Never; it is the wrong axis |
| Weighted interdecile range instead of IQR | Deciles trim nothing below `N = 10`; a live 5-product category returns `h = 770 543` | A minimum catalogue size is ever enforced upstream |
| Effective sample size `n_eff = (Σw)² / Σw²` as the count term | Over-smooths: merges the 199 / 499 / 999 charm-price modes into one | The estimate is ever reframed as inference from a sample, which would also change the `D` decision |
| Point-valued quantiles for the spread (nearest rank or interpolated) | Nearest rank is discontinuous — 293 885x from moving one record. Interpolation is continuous but reaches *into* the gap, inventing a value the data does not contain: `h = 368 374` on a live category whose priciest item costs 24 691x the cheapest | Never; band-averaging is continuous *and* stays on the support |

## Decision

**Chosen: Option A.** The driver was stability under irrelevant change. A slider is looked at repeatedly by
the same shopper over a session, and the shipped estimator moved a bar by orders of magnitude when a single
product was repriced by one unit — an input the distribution barely noticed. Only an estimate that pools
information across the whole axis has that property, and once the estimate is global, normalising it against
its own maximum rather than against the returned buckets is what keeps the picture from changing when the
client merely asks for a different number of bars.

Option B would win if occurrences-as-height ever had to be preserved for a client that cannot be changed. No
such client exists: the one production storefront reading this field is already applying a square root to it,
which has to be removed either way.

Two sub-decisions are worth stating because they will look arbitrary:

- **`relativeFrequency` is reused rather than joined by a new field.** The old value was wrong, not
  differently-scoped; shipping the right number under the existing name is a fix, and a parallel field would
  leave every existing client rendering the defect faithfully forever. The cost is a semantic change under a
  stable name, which is why the rendering contract is now written out on
  `HistogramContract.Bucket#relativeFrequency()` and in the user documentation.
- **`representativeValue` was considered and dropped.** Publishing the exact abscissa the density was read at
  would make the response a true `(x, y)` series. Measurement showed that evaluating at the threshold instead
  preserves the peak on all four real catalogues tested, so the field would have bought precision nobody
  could use. It stays available if a client ever needs a genuine density point.

## Key technical details

- **Entry point:** `EqualizedHistogramDataCruncher` — the whole algorithm, reached from
  `PriceHistogramComputer.createHistogramDataCruncher` and `AttributeHistogramComputer.createHistogramDataCruncher`.
  Both now resolve `EQUALIZED` and `EQUALIZED_OPTIMIZED` to the same branch.
- **Bucket count is an upper bound, never a promise.** The budget proof is in the class JavaDoc: with `mᵢ`
  ranks absorbed by start `i`, `Σmᵢ = bucketCount` and the isolation rule adds at most one threshold per start
  with `mᵢ ≥ 2`, so the emitted count cannot exceed `bucketCount`. `padWithEmptyBuckets` and
  `BucketCountMode` are gone — there is nothing to pad, because every boundary sits on a value the data
  contains and no bucket is ever empty.
- **The rank comparison is integer-exact and strict** (`C[j+1] · B > k · N`). Exact, because a boundary decided
  by floating-point rounding is a boundary that moves when the JIT changes its mind. **Strict**, because the walk
  must land on the value that *contains* the rank, not the one that *ends* at it — the two differ only when the
  cumulative weight falls exactly on `k · N / B`, which is precisely what evenly weighted data does at every
  single rank. Under the non-strict form every cut lands one distinct value early: six values of weight seven
  into three buckets gives 7/14/21 instead of 14/14/14, and twenty equal plateaus into twenty buckets gives
  nineteen. Both production corpora bucket identically under either form, so the regression corpus cannot see
  this — `shouldSplitEvenlyWeightedValuesIntoEqualBuckets` and
  `shouldFillEveryBucketWhenPlateauCountMatchesBucketCount` exist solely to pin it.
- **The run-length step is the exact inverse of that comparison.** The batching jumps to
  `(C[j+1] · B − 1) / N + 1`, the smallest rank the walk no longer resolves to the current value. Pairing it with
  the wrong comparison silently re-introduces the off-by-one, so the two must change together.
- **The rank walk is `O(D)`, not `O(bucketCount)`.** `bucketCount` is chosen by the caller and is not bounded
  upstream, so walking one rank at a time would let a request for ten million buckets over three distinct
  values cost ten million iterations. Consecutive ranks landing on the same value are taken in one step via
  `C[j+1] · B / N`, which makes the walk index strictly increase on every iteration. The absorbed-rank counter
  is saturated at 2 for the same reason — only "two or more" is ever asked of it.
- **Two departures from Silverman, both consequences of what the estimate is for.** This is a deterministic
  smoothing of a catalogue known in full, not inference about a latent population from a sample. So the count
  term is `D` (distinct values), not `N` — replication invariance; `N^(−1/5)` would sharpen the curve by 13%
  per doubling of a catalogue whose shape did not change. And the robust spread term caps a heavy value at
  `w' = min(w, (N − w) / 2)`: a value holding more than half the weight otherwise spans the interquartile
  range on its own and drives the IQR to zero *from the inside*.
- **The cap is a `min`, not an `if (w > N/2)`, and that matters.** A hard switch is discontinuous, and a live
  attribute histogram sits at 50.75% of its weight on one value — five records from the cliff, where the
  bandwidth stepped 2.06x. The `min` form starts binding at `w = N/3` and is continuous everywhere.
- **Quantiles for the spread are band-averaged, not point-valued.** `Q̄(p) = 1/(2r) ∫ Q(u) du` over
  `[p − 0.05, p + 0.05]`. This is the least obvious line in the file and the one most likely to be
  "simplified" back into a bug — see *Verification*.
- **The kernel is evaluated by one sliding window**, rewriting `Σ w(1 − |x − v|/h)` as
  `Σw − (1/h)·Σ w|x − v|` and splitting the absolute value at `x` into two incrementally maintained running
  sums. `O(D + B)` where the direct form is `O(D²)`, which matters because the curve maximum is taken over
  *all* distinct values, not just the returned buckets.
- **The `1/(N·h)` normalisation factor is deliberately not applied** — it cancels in the ratio to the peak.
  Someone will eventually "restore" it; it changes nothing but costs two multiplications per point.

## Verification

`EqualizedHistogramDataCruncherTest` — 19 tests, invariant-based rather than pinned to expected numbers,
because the heights are a continuous function of the whole catalogue and a golden-value test would break on
any harmless re-derivation while proving nothing.

- **Differential against an independently formulated reference:** 4 000 random catalogues across six shapes
  (charm-priced, uniform, clustered, dominated, sparse, far-outlier) at bucket counts 2–100 — every threshold
  identical to a reference that locates each bucket start by exact rational search over the cumulative array,
  rather than by an incremental walk. This replaced an earlier differential against the design's own Python
  prototype, which shared the prototype's comparison operator and therefore could only ever prove faithful
  transcription; the off-by-one above survived 4 000 catalogues of that weaker check. **A reference derived
  from the implementation validates transcription, not correctness** — the same caution applies to the
  bandwidth helper inside the test suite, which is a transcription and is documented as such.
- **Bucket budget:** 0 violations of `length ≤ min(bucketCount, D)` in 5 000 catalogues, plus the budget
  proof above. 1 785 of 4 000 returned fewer buckets than requested, which is what makes the contract change
  worth documenting rather than an edge case.
- **Target-crossing accounting:** `bucketMass − heaviestValueWeight ≤ N / bucketCount` holds with **0 strict
  violations and a worst ratio of exactly 1.0000** over 5 000 catalogues. This is the invariant the old code
  broke, stated in a form a test can check from published output alone.
- **Gap insensitivity:** nudging each of the 135 production prices by one unit in turn moves the worst bar by
  **0.010%** (was: orders of magnitude).
- **Replication invariance:** tripling every product's count leaves all 20 thresholds and all 20 heights
  **bit-identical**.
- **Support fragmentation:** scattering every plateau of ≥ 60 products over five adjacent prices leaves the
  half-height profile width **unchanged** (14 bars either way).
- **Quartile-boundary continuity:** walking one record of a hundred across the upper quartile on `0, 1, 2,
  10⁶` gives a worst step of **1.26x**. Nearest-rank quantiles give 293 885x on the same sweep.
- **Dominance sweep:** one value from a third to nine tenths of the catalogue, straight through the `w > N/2`
  point — faintest bar anywhere in the sweep is **1.96**, no bucket goes invisible.
- **Sliding window vs. direct `O(B·D)` evaluation:** agreement within 0.02 on both production catalogues at
  four bucket counts and on 200 random ones.
- **Red → green on the two bucketing regressions**: both fail against the pre-fix engine (7 vs 14, 19 buckets
  vs 20) and pass against the fixed one.
- 552 histogram-tagged tests pass across the functional suite (1 skipped, pre-existing); the cruncher's own
  class holds 22.

## Consequences & open follow-ups

- **The documentation example outputs under `documentation/user/en/query/requirements/examples/histogram/`
  still show the old rendering.** `*.evitaql.string.md` and the `*.json.md` files are generated by
  `UserDocumentationTest` against **`demo.evitadb.io`**, not against a local build, so they cannot be
  refreshed from this branch — they will regenerate once the fix ships and the demo redeploys. The
  `attribute-histogram-equalized.evitaql.string.md` example is currently a faithful picture of the defect: a
  128-product bucket drawing a 15-character bar next to 248-product buckets drawing two.
- **`EQUALIZED_OPTIMIZED` is now a synonym for `EQUALIZED`.** It is kept because it is part of the published
  query grammar and removing it would be a breaking change for no gain. It was not deprecated: a client
  passing it gets exactly what it asks for, and the name is not misleading so much as redundant.
- **Clients must stop applying `sqrt`.** The known storefront transform `h = 10.34 · √relativeFrequency`
  exists only to compress the dynamic range this change removes; post-fix the tallest-to-median ratio is
  roughly 1.2–2.0 and compressing it again flattens a legitimately readable profile. Called out in the issue,
  in the user documentation, and on the record component's JavaDoc.
- **Clients must stop assuming `bucketCount` buckets came back**, and must not scale bars against `max()` of
  the returned buckets — that would re-couple the rendering to the requested bucket count, which is the
  defect being fixed.
- **No serializer work was needed.** The `CacheableBucket` record shape is unchanged (only the values in it
  change), the histogram cache is in-memory only and is never persisted, so no new Kryo vintage class was
  required. This was checked because `FlattenedHistogramComputerSerializer_2026_1` and `_2026_2` are both
  taken and there is no patch-level naming convention — the answer is that the question does not arise.
- **`relativeFrequency` is floored at `0.01` for a non-empty bucket.** A bucket more than five orders of
  magnitude below the peak would otherwise round to `0.00` at scale 2 and read as empty, breaking the
  documented `(0, 100]` contract. Reachable on catalogues with an extreme mass ratio.
- **The last bucket can be zero-width.** Its threshold equals `getMaxValue()` whenever the largest value is
  numerous enough to be isolated — measured at 29–47% of random catalogues depending on the shape mix. Renderers
  must floor the bar width; documented on the record component, in the user documentation and on the cruncher.
- **`EQUALIZED_OPTIMIZED` is no longer cost-penalised.** Both computers charged it the `OPTIMIZED` recomputation
  premium (+50% attribute, +50% price) for work it does not do now that the two behaviours share one cruncher,
  which biased the planner against it. Both now cost it as `EQUALIZED`.
- **Four published contract descriptions had to move with the code**, and none of them is in the engine: the
  GraphQL/OpenAPI field description in `HistogramDescriptor`, the gRPC field comment in `GrpcExtraResults.proto`,
  and the constraint JavaDoc on `PriceHistogram`, `AttributeHistogram` and `ReferenceHistogramStatistics`. A
  contract change is not done when the contract class is edited — the surfaces that *publish* it are separate
  files and none of them shares a symbol with the code, so only a prose search finds them.
- **Not carried over from the design: exposing the measurement abscissa.** See *Decision*.

## Related work

- [2026-08-24-price-histogram-per-accessor-granularity](2026-08-24-price-histogram-per-accessor-granularity.md)
  — the sibling fix in the same `translator/histogram` package; it decides *which records* reach the cruncher,
  this one decides what the cruncher does with them.

## Timeline

- **2026-09-07** — issue #1501 filed with the full derivation, rejected alternatives and the client rendering
  contract; implemented and verified against both production catalogues.
