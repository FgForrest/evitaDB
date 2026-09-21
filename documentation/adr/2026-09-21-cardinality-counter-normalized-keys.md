---
title: The reference-attribute cardinality counter keys on the index key, and a storage-protocol migration re-keys the counters already written
date: 2026-09-21
updated: 2026-09-21 13:20
status: accepted
kind: fix
issues: [1620]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/index/cardinality, evita_engine/src/main/java/io/evitadb/index/attribute, evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog, evita_store/evita_store_server/src/main/java/io/evitadb/store/index/serializer]
supersedes: []
superseded-by: []
relates: [2026-08-10-stored-value-normalization-split, 2026-09-04-millisecond-temporal-precision]
---

# The cardinality counter keys on the normalized index key, and counters already on disk are re-keyed once, at an upgrade

A reference attribute's cardinality index ref-counts how many owners contribute one entry to a *shared* filter
index, so the entry is dropped only when the last of them leaves. It counted RAW attribute values while the index
it guards keys on `FilterIndex.getNormalizer`'s output, which is deliberately many-to-one. Two owners whose values
differed only below that resolution therefore held two counters over one index entry, and the first of them to
leave removed the entry the other still needed. The counter now normalizes its key through the same function the
index does, and a one-time storage-protocol migration (6 → 7) re-keys every counter already written, summing the
counts that collapse onto one key. The same migration audits what it repairs and reports, as an error at startup,
any catalog whose index entries were already dropped before the upgrade — damage that re-keying cannot undo.

## Why

`FilterIndex.getNormalizer(plainType, indexedDecimalPlaces)` is the single seam every consumer of an attribute
index reads its keys through, and on five of its branches it is **many-to-one by design**: a `BigDecimal` becomes
an order-preserving scaled `int`, a `String` becomes Unicode NFD, an `OffsetDateTime` becomes an `Instant` that
discards the offset. That is the point of it — canonical equivalence is a feature, and the shared value tree holds
one entry per canonical key.

`AttributeCardinalityIndex` did not go through that seam. Its key is `(recordId, value)` on the raw value, so for
a reference attribute declared with `indexedDecimalPlaces(0)`, one owner contributing `1.2` and another
contributing `1.4` produced **two counters over the one tree entry keyed `1`**. When the first owner moved away,
its own counter reached zero, `CardinalityChange.BOUNDARY_CROSSED` fired, and the entry was removed — while the
second owner was still relying on it.

The consequence is quiet and then loud. The surviving owner is silently missing from filtering results on that
attribute. Later, removing its last contribution fails the tree's `Sanity check - record not found!` premise and
rolls that transaction back. The state is recoverable rather than terminal — an insert of any colliding value puts
the entry back — but nothing tells anyone it happened, and the eventual failure surfaces far from its cause.

Four folds could have caused this in a released version, verified against the tag's own sources rather than a
changelog (`git show v2026.2.7:evita_engine/.../FilterIndex.java`): NFD, `BigDecimal`, `BigDecimalNumberRange` and
the `OffsetDateTime` offset collapse. The **millisecond truncation** that today folds temporal values a second way
is younger — absent from v2026.2.5, .6 and .7 — so it cannot have damaged any catalog this work can meet. The two
temporal mechanisms are separate and only the second is unreleased; conflating them understates the exposure,
which an earlier revision of this analysis did.

### Previous state

The counter has keyed on the raw value since it was introduced, and it was correct for as long as the index keyed
on the raw value too. What made it wrong was the accumulation of normalizer branches on the *other* side of the
relationship — NFD arriving with the collation work, the scaled-`int` encoding with conditional bucket indexing,
`Instant` with the temporal work in `2026-08-10-stored-value-normalization-split`. Each was a local, well-argued
change to how the index stores a key; none of them looked like a change to the counter, because the counter is in
a different package and does not call `getNormalizer` at all. The invariant that broke was never written down
anywhere the three changes would have passed.

## Options considered

### Option A — normalize at the counter, and re-key the persisted counters once at an upgrade (chosen)

`AttributeCardinalityIndex.normalizeKey` runs every key through `FilterIndex.getNormalizer` before it reaches the
map, so the counter and the tree agree by construction from the next write onwards. Counters already on disk are
brought into the same form by `Migration_2026_3`, a one-time storage-protocol 6 → 7 migration, which sums the
counts of keys that collapse onto one key.

- **Pros:** the disk ends up canonical, so every later read is free and no code path has to remember that stored
  keys might be raw. The summing repair runs once rather than on every load. It follows the precedent already in
  the tree: `Migration_2026_2` eagerly re-keys `String` and `BigDecimal` **filter** parts for exactly the same
  reason.
- **Cons:** it needs a storage-protocol bump, a `serialVersionUID` bump and a backward-compatible reader — three
  moving parts where the alternative needs none. It is also one-way: see *Consequences*.

### Option B — re-key at load, in `AttributeCardinalityIndexMapLoader` (declined)

Normalize the keys as the counter is rehydrated, leaving the bytes on disk untouched. This was the initial
recommendation and it is genuinely strong.

- **Pros:** no protocol bump, no uid bump, no BWC reader, no migration. It covers every read path unconditionally,
  including parts an eager sweep might not visit, and it is self-healing — once the index is next flushed the
  stored keys are canonical anyway. Decisively, it runs with the entity schema fully linked, including reflected
  references, which the migration's standalone `EntitySchemaStoragePart` does not.
- **Cons:** the cost is paid on every load of every catalog, for ever, including the overwhelming majority of
  counters that were never affected; and the disk stays in a shape that is wrong under the current contract, so
  anything that reads a storage part directly — a migration, a diagnostic, a repair tool — has to know it.
- **Rejected because:** the cost is permanent and unbounded in time, while the correction is finite and known. A
  normalization the engine *always* applies on the way in belongs in the stored form, not in a filter applied
  on the way out, and `Migration_2026_2` had already established that this is how this codebase resolves the
  same trade-off for the same family of keys. It becomes the better option again the moment a normalizer has to
  change for a type whose scale cannot be established from storage alone — which is exactly the case the
  migration degrades on today (see *Consequences*).

### Option C — detect the collision and refuse to open the catalog (declined)

Scan the counters at load, and fail the catalog with a diagnostic naming the affected attributes.

- **Pros:** cheapest to write; leaves no chance of a silent wrong repair.
- **Rejected because:** it refuses service for a condition that is repairable without operator involvement, and
  it refuses it for catalogs that are merely *at risk* rather than damaged — a raw-keyed counter is not itself
  corruption. Refusing to open trades a real, immediate availability loss for a hypothetical one. The useful half
  of this option — saying something out loud — was kept and applied to the case that genuinely cannot be repaired,
  as the startup error described below.

## Decision

**Chosen: Option A**, with the reporting half of Option C folded into it.

The deciding factor is the asymmetry between a finite cost and a perpetual one. Re-keying at load is cheaper to
build and permanently more expensive to run; the migration is the reverse, and the codebase had already made this
call once, in the same area, for the same reason.

Option B wins again if a future normalizer change lands on a type whose canonical form cannot be reconstructed
from storage parts alone. That is not hypothetical: `resolveScale` already degrades on an attribute the entity
schema cannot answer for, and the loader would not, because it holds the linked schema. A change of that shape
should be read as the trigger to supersede this record rather than to extend the migration.

## Key technical details

- **The engine fix is one call site per direction.** `ReferencedTypeEntityIndex` and `ReducedGroupEntityIndex`
  normalize the value before `addRecord` / `removeRecord` (eight sites) and pass the RAW value to the filter index
  as before — the filter index normalizes its own keys, and handing it a pre-normalized value would double-encode.
- **`AttributeCardinalityIndex.normalizeKey` caches the normalizer, keyed on the scale it was built for.** Two of
  `getNormalizer`'s branches capture `indexedDecimalPlaces` in a lambda, so calling it per record would allocate
  on every write of a `BigDecimal` reference attribute. The cached record is a plain transient field: worst case
  under a racing scale change is a redundant rebuild, never a wrong key.
- **`FilterIndex.getNormalizedKeyType` exists because normalization changes the value's TYPE**, and the counter's
  own `assertValueCompatible` rejected anything that was not an instance of the declared type. A `BigDecimal`
  counter legitimately holds an `Integer` now; the guard accepts the declared type or its normalized type, and
  nothing else.
- **The stored format had to change, and that is what forced the protocol bump.** The old serializer wrote each
  key value with `kryo.writeObject` and read it back as the index's *declared* value type — the value carried no
  class of its own. That is unreadable once the stored key is an `Integer` in a `BigDecimal`-typed index, so
  `AttributeCardinalityIndexStoragePartSerializer` now uses `writeClassAndObject`, the `serialVersionUID` moved,
  and `AttributeCardinalityIndexStoragePartSerializer_2026_2` reads the old layout (its `write` throws).
- **The backward-compatible reader deliberately does NOT convert, which deviates from a recorded invariant.**
  `2026-08-10-stored-value-normalization-split` chose a converting BWC reader for a comparable normalization
  change and recorded that any future `getNormalizer` change for an already-persisted type needs a matching
  conversion in the reader for the format that wrote it. It cannot be honoured here: a Kryo serializer has no
  schema, and `indexedDecimalPlaces` — without which a `BigDecimal` key cannot be canonicalized — lives in the
  entity schema and in the sibling filter part, neither reachable from a serializer. The conversion therefore
  moved up to the migration, which holds both. The invariant is intact in spirit (the conversion exists, once,
  before anything reads the keys) and violated in letter (it is not in the reader), which is why it is recorded
  here rather than quietly done.
- **`resolveScale` prefers the sibling filter index's FROZEN scale over the schema's current one**, falls back to
  the schema, and gives up rather than guessing. An earlier shape looked only at the schema and threw when it
  could not answer — which marked five real catalogs CORRUPTED on boot in testing, because a `CARDINALITY` part
  can exist for an attribute the owning schema cannot answer for (an inherited attribute of a reflected
  reference, or one the schema has since dropped). **A migration degrades on a part it cannot price; it never
  aborts the catalog.**
- **The audit compares the counter against the entries it guards** (`countOrphanedCounters`). Both sides use the
  same `recordId`, by construction — the index hands the identical id to the counter and to the filter index in
  one call — so a counter naming a record the bucket does not contain is an entry that was dropped early.

## Verification

- `ReferenceAttributeIndexKeyCollisionFunctionalTest` — 10 tests at the public API. **6 fail against the unfixed
  build** (counterfactual run with the engine change reverted): the colocated-value query and write paths, the
  Unicode NFD case, the array branch, the temporal offset collapse, and the grouped-index sibling case. The other
  four are deliberate confirming-negatives — a `Currency` control (bijective normalizer), a range control, a
  temporal control, and a unique-only case that asserts the write is **rejected** with
  `UniqueValueViolationException`. That last one started as an attempted exploit and refuted itself: a folded
  unique is enforced on the shared tree's normalized key, so the collision is caught at write time. It is kept as
  the assertion that this is so.
- `Migration_2026_3_Test` — 7 tests on the re-key transform, including the summing branch that is the repair.
- `Migration_2026_3_RepairRoundTripTest` — 3 tests driving the repair over real bytes: a part written exactly as
  2026.2 wrote it, through the `serialVersionUID` dispatch to the backward-compatible reader, through the
  re-key, out through the current serializer and back. This is what the pure transform cannot show — that the
  repaired key (an `Integer` in a `BigDecimal`-typed counter) survives the format it has to be stored in.
- `EvitaBackwardCompatibilityTest` — the integration oracle: five published demo-dataset catalogs (2025.1,
  2025.3, 2025.6, 2026.1, 2026.2) downloaded and opened for real, the 2026.2 one entering this migration directly
  and the others chaining through every earlier step. All five reach protocol 7; six cardinality parts are
  re-keyed across them and none is skipped. It is also what caught the CORRUPTED-on-boot defect described under
  `resolveScale`, before either code reviewer reported it.
- **The audit reports nothing on healthy data.** Across those same five catalogs it raises zero damage errors —
  the check that a diagnostic recommending a reindex does not fire on catalogs that do not need one — and exactly
  one "not checked" warning: `stocks.quantityOnStock in entity index 26 (filter index is stored in paged form)`.
  That single line is also the evidence that the paged gap is real rather than theoretical: a published demo
  dataset already contains one, so any future repair work must cover the paged shape to be worth building.
- The project's own `kryo-bwc-audit` reports no findings for the serializer change.
- The summing branch is proven non-vacuous by counterfactual: with the merge changed from `Integer::sum` to
  last-wins, `shouldSumCollidingBigDecimalCountersAcrossAStorageRoundTrip` fails `expected: <2> but was: <1>` and
  `shouldSumCollidingStringCountersAcrossAStorageRoundTrip` fails `expected: <4> but was: <1>`, together with
  three transform tests — while the serial-version test correctly keeps passing, since it does not depend on the
  merge.
- Full functional regression: **24,380 tests**. The first run surfaced four `EntityIndexHeapSizeTest` failures,
  all one defect in this change: that suite compares evitaDB's own `getHeapSizeInBytes()` against a real JOL
  measurement byte for byte, and the cached-normalizer field grew the object by eight bytes while the estimate
  still declared three reference slots. Fixed — the estimate now counts the slot, and the lazily-built normalizer
  itself once present — and the suite is green. The only remaining error is `ExportS3ServiceTest`, which requires
  a Docker environment this host does not provide.

## Consequences & open follow-ups

- **The upgrade is one-way, and it fails loudly rather than subtly.** A catalog on protocol 7 cannot be opened by
  2026.2: that version's `DefaultCatalogPersistenceService` throws `StoredProtocolVersionNotSupportedException`
  from the header check before any storage part is read. Restoring a pre-upgrade backup is therefore the only
  downgrade path, and the failure names the reason. (An earlier reading of this had the failure being
  data-dependent — surfacing only for catalogs that actually contain a rewritten part. It is not: the header
  check runs first and refuses uniformly.)
- **A catalog damaged BEFORE the upgrade is reported, not repaired.** Summing the counters restores the correct
  count over an entry that is already gone. `Migration_2026_3` detects this by comparing each counter against the
  bucket it names and logs an ERROR at startup — catalog, collection, attributes, number of missing entries — and
  recommends a reindex of that collection. Repair was considered and deliberately not built: it would mean an
  upgrade writing reconstructed index content with no undo, on an inference that has never been exercised against
  a catalog known to be damaged. **Decide it on evidence from the diagnostic, not ahead of it.**
- **That report is emitted once, at the upgrade, and never again** — the migration runs once per catalog, and a
  check on every start is the perpetual cost this record rejected under Option B. An operator who misses the
  startup error has no second chance from the engine; the damage then surfaces the way it did before, as an index
  premise failure on a later write. Re-reading it means re-reading that boot's log.
- **Two populations are reported as "not checked" rather than as clean**, because a diagnostic that reports clean
  without looking is worse than none. (1) **Paged filter indexes** — their buckets live in separate
  `FilterIndexLeafPagePart` records, so the comparison needs a different walk. Note that `Migration_2026_2`'s
  total silence about paging is *not* a precedent: paging does not exist in 2026.1, which wrote every protocol-5
  catalog, but it does in 2026.2, which wrote every protocol-6 one. (2) **`OffsetDateTime` counters** — they can
  genuinely carry this damage, but the audit has no key space to compare in: today's normalizer truncates to
  milliseconds while the truncation of stored keys happens at load rather than in storage
  (`2026-09-04-millisecond-temporal-precision`), and a catalog that reached protocol 6 by upgrade from 2026.1 may
  hold temporal buckets that are not `Instant` at all until a load re-anchors them.
- **`Migration_2026_3` calls `Migration_2026_2.resolveIndexedDecimalPlaces`**, so the older migration cannot be
  deleted without moving that method. Accepted knowingly; the instruction to move it lives at the call site.
- **Open question, not chased:** `AttributeCardinalityIndex.normalizeKey` reads the *live* schema's
  `indexedDecimalPlaces`, so it matters whether `ModifyAttributeSchemaTypeMutation` forces a synchronous filter
  index rebuild when that value changes. If it does not, counters written either side of the change are keyed at
  different scales. This is moot for the migration, which prefers the frozen scale, but it bears on the engine
  fix. Neither reviewer chased it either.

## Related work

- `2026-08-10-stored-value-normalization-split` — introduced the `Instant` branch of `getNormalizer` and set the
  "conversion belongs in the BWC reader" rule this record knowingly departs from, with reasons above.
- `2026-09-04-millisecond-temporal-precision` — the second temporal fold, and the load-time repair that makes
  temporal counters unauditable from storage parts.

## Timeline

- **2026-09-20** — defect reported from a production e-commerce catalog as an index premise failure
- **2026-09-21** — root cause proven by counterfactual; engine fix, format change and 6 → 7 migration implemented
- **2026-09-21** — migration verified against five real catalogs; detection and startup diagnostic added
