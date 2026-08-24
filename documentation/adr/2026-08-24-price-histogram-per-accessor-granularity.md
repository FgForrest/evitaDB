---
title: Price histogram granularity is decided per accessor, not all-or-nothing across the query
date: 2026-08-24
updated: 2026-08-24 13:10
status: proposed
kind: fix
issues: [1433]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/core/query/algebra/price, evita_engine/src/main/java/io/evitadb/core/query/extraResult/translator/histogram]
supersedes: []
superseded-by: []
relates: []
---

# Price histogram granularity is decided per accessor, not all-or-nothing across the query

The per-inner-record price histogram shipped in #1159 only engaged when *every*
`FilteredPriceRecordAccessor` in the query exposed the per-inner-record side-output. Because a
`filterBy` builds one price branch per `PriceInnerRecordHandling` present in the catalog, and only
the `LOWEST_PRICE` branch has per-inner-record data points to give, that rule could never be
satisfied by a candidate pool mixing handling modes — the histogram silently fell back to
per-entity granularity, which is exactly the symptom #1159 was opened to remove. The capability
probe is now read per accessor: exposing accessors contribute their per-inner-record records, and
the unchanged per-entity collector tops up every entity those records did not cover.

## Why

A category listing that contains both simple products (`NONE`) and master/variant products
(`LOWEST_PRICE`) is the ordinary shape of an e-commerce catalog, not an edge case. In such a pool
the shipped code capped the histogram's upper bound at the highest *price for sale*, hiding every
non-cheapest variant price above it — reported on a live 2026.2 deployment as a histogram maximum
of 5 000 CZK for a pool containing an 8 000 CZK variant.

The constraint that made this non-obvious is that the two halves of the answer are produced by
completely different machinery. The `LOWEST_PRICE` half arrives as an eagerly-collected array from
`LowestPriceTerminationFormula`'s side-output; the `NONE`/`SUM` half is produced by an
entity-driven collector that walks the filter result and asks each accessor for the prices of one
entity at a time. There is no single call that yields both, so "just merge the accessors" is not
available as a one-liner.

### Previous state

`FilteredPriceRecords.allAccessorsExposePerInnerRecordHistogram` returned `true` only when the
collection was non-empty and every member exposed the side-output; `PriceHistogramComputer` used it
to choose between two whole-query paths. Two independent causes closed that gate on a mixed pool:

- the `SHALLOW` accessor scan descends through `PlainPriceTerminationFormula` (which is not a
  `FilteredPriceRecordAccessor`) and surfaces the inner `PriceIdToEntityIdTranslateFormula`, which
  inherits the interface default of `false`;
- `SelectionFormula` implements the accessor interface unconditionally, so a wrapper the planner
  inserted above a price-free sub-tree was gathered into the histogram's accessor list and reported
  `false` on its own.

Either was sufficient. Neither was visible in tests, because the per-inner-record fixtures added in
#1165 used a homogeneous `LOWEST_PRICE` pool, and the shared assertion helper *dispatched on the
composition of the pool* — asserting per-entity counts the moment one entity was not
`LOWEST_PRICE`. The assertion adapted to the engine, so it could not detect the engine getting
coarser.

## Options considered

### Option A — partition the accessor set, top up the remainder (chosen)

Read `exposesPerInnerRecordHistogramRecords()` per accessor. Merge the exposing accessors'
side-output, narrow it to the entities the whole filter matched, then run the existing
`FilteredPriceRecordsCollector` over `baseline \ covered` and concatenate. `SelectionFormula`'s
probe becomes "any inner accessor exposes" and its histogram records become "only the exposing
inners' contribution".

- **Pros:** each half keeps running through the code path that already has passing assertions
  behind it; double counting is impossible by construction rather than by argument, which is what
  makes it hold on the prefetch plan too (there a `SelectionFormula`'s per-entity alternative would
  happily answer for a `LOWEST_PRICE` entity, but it is never asked — that entity is not in the
  remainder); no accessor is asked for a shape it does not have.
- **Cons:** the histogram now computes the relaxed baseline bitmap on the per-inner-record path,
  which it previously skipped; one extra `contains` per per-inner-record price.

### Option B — stop the `SHALLOW` scan at the termination formula (declined)

The issue's own prescription: fix the accessor collection so each handling branch contributes its
*termination* formula rather than the raw translate formula underneath it — by making
`PlainPriceTerminationFormula` a `FilteredPriceRecordAccessor`.

- **Pros:** attacks the surfaced-wrong-node problem at its origin; the accessor list would then read
  as one entry per handling branch, which is easier to reason about.
- **Rejected because:** `PlainPriceTerminationFormula` owns no price records at all — it is the
  no-price-filter variant that deliberately postpones resolving the entity price, and it delegates
  straight to `getDelegate().compute()`. Stopping the scan there yields *zero* contribution from the
  `NONE` branch, so those entities would vanish from the histogram entirely — worse than the bug.
  Making it a real accessor means giving it a record store and a resolution step it exists to avoid.
  Revisit if `PlainPriceTerminationFormula` ever has to resolve entity prices for another reason.

### Option C — give `PriceIdToEntityIdTranslateFormula` the capability (declined)

Let the node the `SHALLOW` scan actually surfaces answer `true`, so the merge covers every branch.

- **Pros:** a one-line change at the exact node the scan returns; no partitioning logic anywhere.
- **Rejected because:** it returns one raw record per translated price id (`SortingForm.NOT_SORTED`),
  not per-entity winners. Merging those inflates the buckets — an entity present in two queried
  price lists would contribute twice. The per-entity view for that branch only exists after the
  entity-driven collector has picked the winner, which is precisely what Option A keeps doing.

## Decision

**Chosen: Option A.** The driver is that the two halves are genuinely different computations and
the correct answer is their union, not a choice between them. Options B and C both try to make one
mechanism serve both, and each breaks on the same fact: the `NONE` branch's per-entity winner is not
materialised anywhere until the collector selects it.

Option B becomes worth revisiting only if `PlainPriceTerminationFormula` gains eager price
resolution for an unrelated reason — at that point it could contribute directly and the accessor
list would get simpler.

## Key technical details

- **Entry point:** `PriceHistogramComputer.getPriceRecords()` partitions via
  `FilteredPriceRecords.collectPerInnerRecordHistogramAccessors(...)`; the two-pass assembly lives
  in `collectPerInnerRecordHistogramRecords(List)`.
- **Invariant — the probe is per accessor.** `exposesPerInnerRecordHistogramRecords()` returning
  `false` forfeits *that accessor's* contribution and nothing else. Any future reader that ANDs the
  probe across a collection reintroduces this bug. `SelectionFormula` follows the same rule with
  "any inner exposes".
- **The `SHALLOW` scan still surfaces `PriceIdToEntityIdTranslateFormula`, and that is now inert —
  not a leftover.** It keeps answering `false`, but a `false` no longer closes the gate for anyone
  else, and the branch it stands for is picked up by the entity-driven remainder pass. Nothing needs
  to change at the scan; see Option B for why stopping it earlier would be a regression.
- **The side-output is not narrowed at its source.** `LowestPriceTerminationFormula` fills its
  funnel from its own delegate — the price sub-tree — so it covers entities that the non-price parts
  of the query (`entityPrimaryKeyInSet`, attribute filters, hierarchy constraints) exclude. The
  computer narrows it to the baseline; removing that narrowing turns a 10-data-point histogram into a
  108-data-point one on the fixture named below. This latent over-count predates this change and was
  simply unreachable while the whole path was gated off for anything but price-only queries.
- **The remainder pass must not reuse `priceRecordsLookupResult`.** That result comes from
  `FilteredPricesSorter`, built over the full accessor set and the full filter result, so it already
  contains a per-entity record for every `LOWEST_PRICE` entity that pass 1 expanded.
- **`covered` is built with incremental `PersistentRoaringBitmap.add`, not
  `RoaringBitmapBackedBitmap.buildWriter()`.** The constant-memory writer materialises a container
  when the high bits change and so assumes ascending input; concatenating several accessors'
  side-outputs restarts at each accessor's lowest entity primary key.

## Verification

- `CombinedPriceEntityByPriceFilteringFunctionalTest` (mixed `NONE`/`LOWEST_PRICE`/`SUM` pool) — the
  four `shouldReturnPriceHistogram*` tests are the reproduction. Restoring the all-or-nothing gate on
  the fixed code reproduces every one of them: `expected 78 but was 70` (twice), `58/52`, and `8/6` on
  the prefetch variant. After the fix all four pass.
- **The prefetch variant needs *both* halves of the gate restored to reproduce.** Reverting only
  `PriceHistogramComputer` leaves it green, because on a prefetched plan the single top-level accessor
  is the `SelectionFormula` wrapper — the all-or-nothing decision was being taken *inside* it, by its
  own all-true probe over the inners. That is what makes the wrapper change load-bearing rather than
  cosmetic, and it is worth knowing before concluding a future gate change is covered.
- `FindFirstPriceEntityByPriceFilteringFunctionalTest`, new test
  `shouldLimitPerInnerRecordHistogramToEntitiesMatchedByNonPriceConstraint` — pins the narrowing with
  an `entityPrimaryKeyInSet` handle over a pure `LOWEST_PRICE` fixture. Counterfactual check: with the
  narrowing removed it reports `expected 10 but was 108`, and the mixed prefetch test `expected 8 but
  was 39`.
- `SelectionFormulaHistogramCapabilityTest` — the capability probe now asserts "any", plus a new
  *Accessor set partitioning* group covering the price-free wrapper and the surfaced translate
  formula.
- **Both named reproductions are covered, and the coverage is asserted rather than measured.** The
  `CombinedPrice...` fixture carries all three handlings in every pool the histogram tests query, so
  the `LOWEST_PRICE + SUM` mix the issue names is exercised and not merely assumed.
  `assertPoolMixesInnerRecordHandling` pins that for the index-plan pools, and the prefetch test pins
  two properties of the six entities it selects: that they span every handling the dataset offers, and
  — wherever the dataset has `LOWEST_PRICE` — that at least one of them is a *multi*-inner-record
  master. **The second guard is not redundant with the first.** An intermediate version of the picker
  produced a perfectly balanced `{NONE=2, LOWEST_PRICE=2, SUM=2}` pool whose masters all happened to
  carry a single variant; per-entity and per-inner-record granularity return identical numbers for
  such a pool, and the deliberately regressed engine passed against it. Handling diversity is not
  granularity diversity, and only the latter can detect this bug.
- **Cross-plan and cached-payload agreement.** `VERIFY_ALTERNATIVE_INDEX_RESULTS` and
  `VERIFY_POSSIBLE_CACHING_TREES` are now enabled on the per-inner-record tests; they had been
  omitted since #1159 because the old all-or-nothing gate could flip between rebuilt plans and the
  verifier raised `InconsistentResultsException`. The caching verifier substitutes
  `FlattenedFormulaWithFilteredPricesForHistogram` for the flagged LP, which is what gives the cached
  form of the hybrid path its coverage.
- Full sweep `-Dgroups="price | histogram | cache"`: 1609 tests, 0 failures, 4 skipped (1607 before
  the two prefetch overrides were enabled).

## Consequences & open follow-ups

- The shared assertion helper `EntityByPriceFilteringFunctionalTest.assertHistogramIntegrity` no
  longer dispatches on the pool's composition — it derives the expectation from each entity's own
  handling, so a future regression to coarser granularity fails the test instead of changing which
  branch it takes. `assertHistogramIntegrityPerEntity` was deleted; the per-inner-record helper
  remains for the pure-`LOWEST_PRICE` call sites.
- **The prefetch histogram test's fixture is now derived, not hard-coded.**
  `shouldReturnPriceHistogramWithoutBeingAffectedByPriceFilterUsingPrefetch` used to pick its six
  entities with per-price-list `getPriceForSale` comparisons and assert a literal
  `assertEquals(3, result.getTotalRecordCount())`. That only models `NONE` handling, which is why the
  `FindFirst` and `Sum` overrides had silently been left without `@Test` — enabling them failed on the
  fixture's own precondition, not on anything the test was written to check. Selection now goes
  through `PricesContract.hasPriceInInterval`, the model's own per-handling reading of `priceBetween`,
  and the expected count is the size of the group it selected. Both overrides now carry `@Test` and
  pass. The lesson generalises: a fixture predicate that hand-rolls price semantics per price list is
  only valid for the `NONE` dataset, and the suite has a subclass per handling mode.
  **What changed is the predicate, not the constants.** The `[50, 150]` band and the two
  `assertEquals(3, ...)` group sizes are still hard-coded in the base method and inherited by all four
  datasets; they hold on every one of them today, but a data-generator change that shifts the price
  distribution can still starve a group. The difference is the failure mode: it would now be an honest
  "this dataset cannot supply six suitable entities", not a precondition that was wrong by
  construction for three datasets out of four. Deriving the band from the dataset is the next step if
  that ever bites.
- `shouldReturnProductsHavingPriceInCurrencyAndPriceListInIntervalWithTaxOrderByPriceAscendingWithoutExplicitAnd`
  is the one remaining `@Test`-less override in the price hierarchy (in both the `FindFirst` and `Sum`
  subclasses). It is an ordering test, not a histogram one, so it is out of scope here — but it is the
  same class of gap and worth the same treatment.
- `SUM` still contributes one data point per entity, per the #1159 specification table. Nothing in
  this change makes that assumption load-bearing anywhere new — a future `SUM` per-inner-record
  requirement only has to flip its termination formula's probe to `true`.

## Timeline

- **2026-05-18** — #1159 fix merged (PR #1165); per-inner-record path introduced behind the
  all-or-nothing capability gate
- **2026-08-24** — mixed-pool defect reported as #1433, reproduced, fixed; record written with the
  implementation, `status: proposed` until the PR merges (flip to `accepted` and fill `prs:` then —
  `date:` stays as written)
