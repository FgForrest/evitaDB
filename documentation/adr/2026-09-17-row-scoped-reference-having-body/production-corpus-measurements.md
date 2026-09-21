# Production-corpus measurements, 2026-09-17

Supporting material for `README.md`. Everything here was measured on a restored **production retail
catalog**, not on a fixture, and it is what prices the open `⊤` decision. It cannot be regenerated without
that catalog: restoring it takes a 55 GB heap and about half a minute of index building, and the archive is
not part of the repository.

Four throwaway harnesses produced it, run under the long-running test module and not committed: one restored
the catalog and reported its shape, one measured per-reference family size and bare-existence latency, one
reported the plan the engine actually chose via `queryTelemetry()`, and one measured the negated shape
against the same references. A fifth weighed the reduced-index heap. §5 records the traps they hit, which
are properties of the engine rather than of the harnesses.

## 0. Corpus shape

18 collections. Product 160,216 · Media 228,150 · PickupPoint 46,693 · ParameterValue 29,159 · Group 6,652 ·
Category 648 · ProductBundle 2,629 · Tag 175 · Stock 9.

Restore is 7 s and pure IO; **activation** — building the in-memory catalog and its indexes — is 27 s and is
where the memory goes. A 5 GB heap dies during activation; 55 GB completes with room to spare.

Eight references carry `ZERO_OR_MORE_WITH_DUPLICATES`, including `Product.media` and
`Product.relatedProducts`, so the corpus contains the **mixed** owner block natively — the shape that makes
row-scoped semantics observable. It did not have to be synthesised. Six references carry a `groupType`, so
the `groupHaving` adapter has production data to run against.

## 1. Family shape and bare-existence cost

Fastest of 5 timed runs after 3 warm-ups. `rows` is exact rather than sampled: the write path forbids two
rows of one owner from sharing a reduced-index key, so per-index entity counts sum to the row count without
double counting.

| reference | family | rows | owners | rows/owner | bare existence |
|---|---|---|---|---|---|
| `Product.media` | 169,102 | 169,370 | 53,430 | 3.17 | **204 ms** |
| `ParameterValue.products` | 160,216 | 1,584,304 | 25,880 | 61.2 | **222 ms** |
| `Category.products` | 87,513 | 226,143 | 588 | 384.6 | 1.4 ms |
| `Parameter.parameterValues` | 29,103 | 29,103 | 279 | 104.3 | 0.8 ms |
| `Product.parameterValues` | 21,313 | 1,157,547 | 119,447 | 9.69 | 21.9 ms |
| `Product.relatedProducts` | 13,162 | 120,642 | 27,576 | 4.38 | 9.6 ms |
| `Product.groups` | 3,971 | 395,136 | 119,383 | 3.31 | 3.2 ms |
| `Product.categories` | 588 | 226,143 | 87,513 | 2.58 | 1.4 ms |
| `Product.tags` | 80 | 279,696 | 94,724 | 2.95 | 0.27 ms |
| `Product.bonusVisibilities` | 9 | 923,222 | 105,507 | 8.75 | 0.64 ms |
| `Product.stocks` | 9 | 193,533 | 114,109 | 1.70 | 0.21 ms |

**Cost tracks family size, not row count.** `Product.tags` holds 280k rows in 80 indexes and answers in
267 µs; `Product.media` holds fewer rows across 169,102 indexes and takes 204 ms.

## 2. The negated shape on the unmodified engine

Three shapes per reference, each bound to one referenced entity taken from that reference's own family.
Fastest of 3 timed runs after 2 warm-ups.

| reference | bare | targeted positive | negated | bare owners | negated result |
|---|---|---|---|---|---|
| `Product.media` | 228.1 ms | 0.33 ms | 59.9 ms | 53,430 | **119,447** |
| `ParameterValue.products` | 229.6 ms | 0.68 ms | 47.4 ms | 25,880 | **29,159** |
| `Category.products` | 1.28 ms | 0.37 ms | **23.8 ms** | 588 | **648** |
| `Parameter.parameterValues` | 0.93 ms | 0.24 ms | **5.2 ms** | 279 | **304** |
| `Product.parameterValues` | 25.7 ms | 0.25 ms | 4.8 ms | 119,447 | 119,447 |
| `Product.categories` | 1.26 ms | 0.29 ms | 0.32 ms | 87,513 | **119,447** |
| `Product.tags` | 0.48 ms | 0.26 ms | 0.25 ms | 94,724 | **119,447** |

`referenceHaving(R, not(...))` must be a **subset** of the owners holding any row of `R` — the bare column.
It was not. `Product.media` returned 119,447 owners where at most 53,430 can qualify: **2.2× more owners
than possess the reference at all**. For three references the count is exactly the whole collection. No
exception was raised on any shape; the caller got a plausible answer.

All four `Product.*` negations returned the *identical* count regardless of which reference was asked about
— the signature of `⊤` resolving to a reference-independent super set.

**These numbers are not a performance baseline.** Where the negation looks cheaper than bare existence
(`Product.media`, 59.9 ms against 228.1 ms) that is the cost of *not doing the work*: the query
short-circuits to a super set and skips the family walk. The honest baseline for a corrected negation is the
**bare** column.

The targeted positive is fast everywhere (0.24–0.68 ms) because the body narrows index discovery to the one
matching reduced index. The family walk is paid only when the body **cannot** narrow discovery — a bare
`referenceHaving(R)`, or a negation.

## 3. Reduced-index heap — the denominator

Families of 4,000 or fewer weighed exactly; larger ones sampled on an even stride of ~1,000 indexes and
scaled. **Total reduced-index heap: 4.92 GB** (5,285,394,056 bytes) across 41 indexed references;
6,333,156 reference rows catalog-wide.

| reference | heap | family | rows |
|---|---|---|---|
| `Product.groups` | 1742.5 MB | 3,971 | 395,136 |
| `Product.categories` | 893.3 MB | 588 | 226,143 |
| `Product.media` | 832.3 MB | 169,102 | 169,370 |
| `ParameterValue.products` | 594.5 MB | 160,216 | 1,584,304 |
| `Category.products` | 503.9 MB | 87,513 | 226,143 |
| `Product.brand` | 137.4 MB | 149 | 51,466 |
| `Parameter.parameterValues` | 126.5 MB | 29,103 | 29,103 |
| `Product.parameterValues` | 83.3 MB | 21,313 | 1,157,547 |

**Family size drives latency; rows-per-index drives memory.** `Product.groups` holds 1.74 GB in only 3,971
indexes and answers bare existence in 3.2 ms; `Product.media` holds *less* memory across 169,102 indexes and
takes 204 ms. Optimising one does not trade against the other, and a proposal must say which it buys.

Against that denominator, the structures the `⊤` options propose are small:

| structure | size | share of 4.92 GB |
|---|---|---|
| per-owner counts, dense `int[]` | 2,821,612 ints = **10.76 MB** | **0.21 %** |
| per-owner counts, dense `short[]` | **5.38 MB** | 0.11 % |
| per-owner counts, sparse paired arrays | 822,000 entries = **6.27 MB** | **0.12 %** |
| owner bitmaps | ~1.5M owner bits, all references | **< 0.05 %** |
| per-reference row totals | 41 longs | negligible |

Dense and sparse land in the same order of magnitude, so **the choice between them is an implementation
detail, not a design fork**. Either way the structure costs well under half a percent of the footprint it
accelerates — which is what makes the earlier "822,000 entries is too many" objection wrong: the count was
right and the inference from it was not.

## 4. Where the time actually goes, and three levers that remove it

The dominant cost of a bare `referenceHaving` is **index selection, not execution**. Every probed query
walked the whole reduced-index family only to reject the reduced-index plan and run the global one. Three
levers remove that, ordered by cost to build:

| lever | new state | removes |
|---|---|---|
| 1. memoize the target-index list | none | family materialisation when the option is rejected |
| 2. one `long` per (reference, scope) | 1 long / reference | the whole-family bitmap sum in the eligibility check |
| 3. per-(reference, scope) owner bitmap | 1 bitmap / reference | the execution-side family OR |

**Lever 1.** Plan creation is gated on eligibility, so a rejected option's index list is never used *for
planning* — but it **is** read when the query orders by the same reference, so the list must become a
memoized supplier rather than disappear. `TargetIndexes#isEmpty/isGlobalIndex/isCatalogIndex` also touch it;
all three are answerable without it (a reduced-index option is by construction neither global nor catalog,
and emptiness is the family size).

**Lever 2.** The sum the eligibility check computes is Σ|ownerSet| over the family, which by the injectivity
invariant is exactly the reference's **total row count** — the `rows` column of §1. One counter per
(reference, scope) turns a 169,102-bitmap walk into a field read, maintained by one increment or decrement
per reference-row mutation. It must remain Σ **rows**, not owners: they differ 3:1 on `Product.media` and
61:1 on `ParameterValue.products`, and substituting owners would silently move the `HIGH_CARDINALITY`
threshold and change which queries take which plan.

These are a query-planning fix adjacent to the correctness work, not part of it.

## 5. Traps these measurements hit

Properties of the engine and the build, worth keeping whether or not the harnesses are ever rebuilt.

- **`-DargLine` is silently ignored by the long-running test module** — its surefire configuration hardcodes
  `<argLine>${longRunningArgLine} …</argLine>`. Verify the heap a run actually got with
  `ps -eo args | grep surefirebooter`.
- **evitaDB swallows `OutOfMemoryError` during catalog activation.** `ProgressingFuture` logs it per task and
  the progress bar keeps climbing. One run caught 142 OOMs, then spent seven hours at ~1700 % CPU in GC with
  no further log output. Always pass `-XX:+ExitOnOutOfMemoryError`, and detect a stuck run by log **mtime**,
  never by process liveness.
- **Activation is persisted.** Once a catalog has been activated, every later engine open starts loading it,
  and an explicit `activateCatalog` collides with that (`BEING_ACTIVATED`). Wait on `CatalogState`'s
  `active`/`transitional` flags instead.
- **`getCatalogNames()` lists a restored catalog while it is still an `UnusableCatalog`.** Presence there
  does not mean it is loaded.
