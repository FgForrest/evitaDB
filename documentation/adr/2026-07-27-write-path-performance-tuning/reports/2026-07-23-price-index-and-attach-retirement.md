# Price-index version pinning and the attach-to-catalog retirement

Consolidated from five working documents (2026-07-22 → 07-25). These are the *architectural* changes
that made the commit-merge prune reach its ceiling — the measurements they enabled are in
`2026-07-27-wal-replay-rounds.md`.

**Staleness note.** Status language is a historical snapshot; all of this has merged.

---

## Part 1 — retiring `attachToCatalog`

**Goal:** shrink the catalog back-edge to a *single guarded implementor* so clean indexes can be
carried forward as the same instance across catalog versions instead of being re-shelled and
re-attached on every copy.

**Result: `CatalogRelatedDataStructure` now has `EntityCollection` as its single implementor.**

### The audit that started it

Every implementor's `createCopyForNewCatalogAttachment` ("the shell") was audited field by field. The
baseline fact that reframed the problem: `EntityCollection.createIndexCopiesForNewCatalogAttachment`
**already reuses non-catalog-related indexes as the same instance** across versions —
`GlobalEntityIndex` and its whole subtree, including the price *super* indexes and every transactional
map, are shared today. **Instance sharing across versions is the established norm**; STM diff layers
are transaction-scoped, not instance-scoped. Shells existed *only* to satisfy the single-use attach.

| class | per-version state in the shell | classification |
|---|---|---|
| `PriceListAndCurrencyPriceRefIndex` | `superIndex` pointer, used at runtime in 4 read paths | **genuine wiring** |
| `PriceRefIndex` | `initCallback` closure capturing `(entityType, catalog)` | **genuine wiring, hidden in a lambda** — same leak surface as a stored field |
| `AbstractReducedEntityIndex` | none of its own | pure propagation; the shell is pure allocation overhead |
| `GlobalUniqueIndex` | `catalog` field; derived classifier caches; `localePkSequence` **not re-primed — a bug** | only the catalog field is authoritative |
| `CatalogIndex` | `catalog` field; rebuilt map of shells | pure propagation |
| `EntityCollection` | catalog field + schema supplier + version read/write-back | **genuine mutual coupling — stays** |

### Two design decisions worth not re-deriving

**The resolver is injected into the executor, not stored on the index.** `EntityIndexLocalMutationExecutor`
holds no `Catalog` — only accessors — so the plan's premise that "the executor owns the catalog
context" was partly wrong. An `EntityTypeClassifierResolver` is injected at construction (where
`this.catalog` is in scope) and threaded per call into `CatalogIndex.insert/removeUniqueAttribute` →
`GlobalUniqueIndex.register/unregisterUniqueKey`. **The rejected alternative — `CatalogIndex` retains a
resolver field — is morally the same back-reference being removed.** Per-call threading keeps the
index layer catalog-free. The interface lives in `io.evitadb.index` (already exported, no JPMS change)
so the index classes need no `core.catalog` import; `Catalog` implements it in two one-liners.

**`CatalogIndex` keeps a fresh wrapper, not a by-reference carry.** `createCopyForNewCatalogAttachment`
returns a *fresh* `CatalogIndex` (fresh `dirty`, fresh map wrapper) that shares the **same**
`GlobalUniqueIndex` instances. Carrying the instance itself would share `CatalogIndex.dirty` across
catalog versions — an unverified behaviour change — and the fresh wrapper preserves the dirty reset
that `goLive`/rename depend on.

### The performance result: flat, and that is the correct outcome

production-catalog JMH `gc.alloc.rate.norm` was **flat (+0.13 %, sign flips across runs)**. Phase 4 is therefore
recorded as a **simplification, explicitly not an optimization**. *Do not re-litigate it expecting a
win.* Its value is that it made the later carry-by-reference work legal.

Verification: `mvn -P full test-compile` clean across all 33 modules; full functional suite
**20 599 run / 0 failures / 0 errors**; and the lock-in test `ReducedIndexCatalogVersionCarryTest` was
proven to **discriminate** — it fails if the carry is reverted.

---

## Part 2 — the `GlobalUniqueIndex` locale-sequence bug (latent data corruption)

Found during the shell audit; fixed and committed independently of the retirement plan.

`GlobalUniqueIndex.createCopyForNewCatalogAttachment` produced a detached copy through a private
constructor that **never primes `localePkSequence`**, leaving it at 0 — while the locale maps
(`localeToIdIndex`, `idToLocaleIndex`) are adopted **by reference** and already contain assigned ids.

The consequence: the first *never-before-seen* locale registered through such a copy is assigned id
**1**, colliding with an existing locale, and `idToLocaleIndex.put(1, newLocale)` **overwrites the
existing mapping in the shared map** — silently corrupting locale decoding of unique-value tuples for
**both** the new and the old catalog version.

The field's own contract stated the invariant that was violated: *"The sequence starts with the
highest assigned id found in `localeToIdIndex` in constructor."*

This is the shape to watch for whenever a copy constructor adopts collections by reference: **shared
mutable state plus an unprimed sequence is a silent corruption, not a crash.**

---

## Part 3 — H2: pass the GLOBAL in instead of holding it

A `PriceListAndCurrencyPriceRefIndex` held a pointer to the super index backing it, and `PriceRefIndex`
held a `SuperIndexResolver` capturing the scope's `GlobalEntityIndex`. **That pointer is a version
pin**: because the GLOBAL is rebuilt on nearly every transaction, every clean reduced index of that
scope had to be re-shelled and re-wired on every commit purely to refresh it.

**The change: stop storing the GLOBAL; pass it as a parameter to the methods that need it.** The
caller always knows which catalog version it is executing against, so pushing the context in at call
time is version-correct by construction.

This is **not** the rejected "Plan B" (dynamic GLOBAL resolution through a stable indirection) — the
distinction is the *direction of the call*. Plan B has the index reach outward for its context; H2 has
the caller hand it in.

### The finding that mattered more than the speedup

Step 1 was built around an assert: the super threaded in must be the one the ref index was wired to.
**It fired immediately**, and chasing it down produced the most important result of this work — two
*different, both-live* super index instances for the same combination.

The reason: `PriceListAndCurrencyPriceSuperIndex.createCopyWithMergedTransactionalMemory` returns
`this` for a clean combination and a new wrapper for a dirty one — but the B+ tree's O(Δ) merge
**reuses the very same `PriceRecordContract` objects**. So a reduced index left pointing at a
superseded wrapper still resolves *identical* records, which is precisely why the old code worked with
a pointer it never refreshed at combination level.

> **Combination-level super identity was never an invariant.** The earlier C1 wiring check asserted
> GLOBAL *entity index* identity — a genuinely different and coarser claim. An assert demanding
> combination identity asserts something that has never been true, so it was **removed rather than
> weakened into something that merely looks equivalent**.

The consequence was stated plainly rather than glossed: step 1 has **no assert-based checkpoint**; its
verification is the passing suite, nothing more. That is weaker than intended — and weaker because the
invariant it wanted to check does not exist, not because checking it was inconvenient. It also
strengthens the case for the change itself: the per-combination pointer was never load-bearing, so
removing it removes bookkeeping rather than a safety property.

### What was deleted

`PriceListAndCurrencyPriceRefIndex.superIndex`, `PriceRefIndex.superIndexResolver`, `wireSuperIndexes`,
`wireOrVerifySuperIndexes`, the whole `SuperIndexResolver` class,
`createCarryByReferenceCopyWithRewiredPrice` + `createReshelledCopy` and both subclass overrides, both
`createCarryByReferenceCopy` methods, `EntityCollection.wireReducedIndexSuperIndexes`/`…SuperIndex`,
and the merger's `globalRebuiltByScope` array. The cascade reached the carry-by-reference shell
constructor itself, whose only caller was the reduced-index shell.

**Net: +81 / −420 lines (−339).** `PrunedIndexMerger.mergeSurviving`'s reduced-index branch collapses
to `return theIndex` — a clean reduced index is now carried **wholesale**, whether or not its scope's
GLOBAL was rebuilt.

What replaced `wireSuperIndex` is `restorePriceRecordsFrom(...)`, which does only the disk-load
price-record-tree rebuild and keeps the load-bearing `priceRecords == null` guard — load correctness,
not version wiring.

### Results

| metric | baseline | after H2 | delta |
|---|---|---|---|
| replay wall-clock | 422.898 s | **360.111 s** | **−14.8 %** |
| changes-visible median | 315.176 ms | **205.981 ms** | **−34.6 %** |
| changes-visible min | 277.632 ms | 176.417 ms | −36.5 % |
| fit | `236.75 + 5.142 · mut` | `127.75 + 4.719 · mut` | **intercept −46.0 %**, slope −8.2 % |
| small tx (≤10 mut) median | 294.86 ms | 193.82 ms | **−34.3 %** |
| big tx (>100 mut) median | 2713.46 ms | 2464.80 ms | −9.2 % |

The baseline fit reproduced the previously recorded `238 + 5.07 · mut` almost exactly — the strongest
available evidence that both sides ran the intended workload. Profiled and unprofiled runs agree on
both sides, and the two post-change profiled runs agree with each other, so the attribution is not a
profiler artifact.

### The safety that was removed, accounted honestly

Production loses the wiring-time GLOBAL identity check for reduced indexes. In its place:

1. **Structural, and stronger than the check it replaces.** The write path resolves the GLOBAL through
   the same `entityIndexCreatingAccessor` that produced the index being mutated; the read path through
   the same `QueryPlanningContext` snapshot. Neither can straddle a version boundary. A *stored*
   pointer could go stale between attach and use; a value resolved from the same snapshot at each use
   cannot.
2. **Two retained asserts** — `PriceSuperIndex.assertIsThisIndex` (write) and
   `PriceListAndCurrencyPriceSuperIndex.resolveLowestPriceRecordsSource` (read) — both falsify a caller
   handing over a *foreign collection's* GLOBAL.
3. **Re-aimed tests that were mutation-tested and FAILED to confirm.** Deliberately breaking
   `FilterByVisitor#getSuperPriceIndex` to resolve a fixed scope regardless of the index's own scope
   leaves the tests green — twice.

> **Production has no test-level tripwire for "a reduced index resolved the wrong scope's or version's
> super price index."** The guarantee rests on (1) and (2) alone. This is recorded at the assertions
> themselves so a future reader does not mistake green for covered.

**Correction from later work:** this document attributed the weak fixture to *prefetching* — with two
products the planner was thought to satisfy the query from entity bodies. That was wrong. The real
cause is `IndexSelectionVisitor`, which marks a reduced index ineligible with `HIGH_CARDINALITY`
unless the candidates' summed cardinality is `<= mainIndexCardinality / 2`; with one product per scope
`1 <= 0` is false, so a reduced index was **never eligible**. Building a real tripwire needs filler
products so the observed entity owns a *minority* of its scope — see
`2026-07-24-trunk-merge-and-index-carry.md`.

### One deliberate behaviour change

`PriceRefIndex.removePrice` previously reached the super through its stored pointer and, when that
target had been terminated, dropped the reduced combination index. Resolving the super freshly never
yields a terminated instance, so the signal was replaced: it now drops the index when the combination
is absent from the GLOBAL's map **or** the record being removed is absent from it.

The narrow case that differs: a super index that is alive and holds the combination but is missing the
record used to raise a hard error, and now drops the reduced combination index instead — a real, if
narrow, reduction in error detection. It was recorded here rather than left to be discovered, and was
subsequently hardened: the drop path now proves nothing live would be lost before discarding.
