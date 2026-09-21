---
title: One filter-index entry has one identity, and the counter, the array write path and the localized key order now all use it
date: 2026-09-21
updated: 2026-09-21 15:35
status: accepted
kind: fix
issues: [1620]
prs: []
areas: [evita_engine/src/main/java/io/evitadb/index/cardinality, evita_engine/src/main/java/io/evitadb/index/attribute, evita_common/src/main/java/io/evitadb/comparator, evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog, evita_store/evita_store_server/src/main/java/io/evitadb/store/index/serializer]
supersedes: []
superseded-by: []
relates: [2026-08-10-stored-value-normalization-split, 2026-09-04-millisecond-temporal-precision]
---

# One filter-index entry has one identity, and everything that describes an entry now uses it

A filter index holds **one entry per key**, where the key is `FilterIndex.getNormalizer`'s output ordered by the
index's comparator. Three separate structures describe those entries, and each of them disagreed with the tree
about what "the same entry" is — in three different ways, all with the same consequence: an entry dropped while
something still needed it, or a removal that fails an internal premise over perfectly valid data.

1. **The reference-attribute cardinality counter** ref-counts how many owners contribute one entry, so the entry
   is dropped only when the last leaves. It counted RAW values while the tree keys on the normalized ones, so two
   owners whose values differed only below that resolution held two counters over one entry and the first to
   leave removed the entry the other still needed. The counter now normalizes through the same function, and a
   one-time storage-protocol migration (6 → 7) re-keys every counter already written, summing the counts that
   collapse onto one key.
2. **An array attribute's write path** passed the raw elements straight through, so an array carrying two elements
   that canonicalize onto one key described that key twice. Adding is idempotent and hid it; removal asserts
   membership first, so the second element found the record already gone. The write path now folds an array onto
   the distinct keys it actually addresses, and the two delta entry points assert that a delta names each key at
   most once. The unique indexes carried the same defect in their own shape and are fixed with it.
3. **The localized `String` key order** was a bare `Collator`, which equates strings `equals` keeps apart — so for
   that one attribute flavour the tree merged entries that every `equals`-keyed structure, the counter included,
   kept separate. The index-key order now breaks the collation tie with `String.compareTo`.

The unifying rule, which was written down nowhere before this record: **a structure that describes filter-index
entries must derive its notion of identity from the index, never from the raw value and never from `equals` alone.**

## Why

`FilterIndex.getNormalizer(plainType, indexedDecimalPlaces)` is the single seam every consumer of an attribute
index reads its keys through, and on four of its branches it is **many-to-one by design**: a `BigDecimal` becomes
an order-preserving scaled `int`, a `BigDecimalNumberRange` has its thresholds rescaled to the same scale, a
`String` becomes Unicode NFD, and an `OffsetDateTime` becomes an `Instant` that discards the offset. That is the
point of it — canonical equivalence is a feature, and the shared value tree holds one entry per canonical key.

`AttributeCardinalityIndex` did not go through that seam. Its key is `(recordId, value)` on the raw value, so for
a reference attribute declared with `indexedDecimalPlaces(0)`, one owner contributing `1.2` and another
contributing `1.4` produced **two counters over the one tree entry keyed `1`**. When the first owner moved away,
its own counter reached zero, `CardinalityChange.BOUNDARY_CROSSED` fired, and the entry was removed — while the
second owner was still relying on it.

The consequence is quiet and then loud. The surviving owner is silently missing from filtering results on that
attribute. Later, removing its last contribution fails the tree's `Sanity check - record not found!` premise and
rolls that transaction back. The state is recoverable rather than terminal — an insert of any colliding value puts
the entry back — but nothing tells anyone it happened, and the eventual failure surfaces far from its cause.

All four folds could have caused this in a released version, verified against the tags' own sources rather than a
changelog (`git show <tag>:evita_engine/.../FilterIndex.java` for v2026.2.5, .6, .7, .13 and .14 — the whole
released 2026.2 line): NFD, `BigDecimal`, `BigDecimalNumberRange` and the `OffsetDateTime` offset collapse, all
present and unchanged across every one of them. The **millisecond truncation** that today folds temporal values a
second way is younger — absent from all five tags — so it cannot have damaged any catalog this work can meet. The
two temporal mechanisms are separate and only the second is unreleased; conflating them understates the exposure,
which an earlier revision of this analysis did.

### Previous state

The counter has keyed on the raw value since it was introduced, and it was correct for as long as the index keyed
on the raw value too. What made it wrong was the accumulation of normalizer branches on the *other* side of the
relationship — NFD arriving with the collation work, the scaled-`int` encoding with conditional bucket indexing,
`Instant` with the temporal work in `2026-08-10-stored-value-normalization-split`. Each was a local, well-argued
change to how the index stores a key; none of them looked like a change to the counter, because the counter is in
a different package and does not call `getNormalizer` at all. The invariant that broke was never written down
anywhere the three changes would have passed.

### The array write path never folded, and the counter was hiding it

Writing the counter fix exposed a second defect of the same family, older and independent of it. `FilterIndex`
iterates an array attribute's RAW elements. The bucket axis is a **set** — one bitmap per key, a record is in it
or it is not — so an array whose elements canonicalize onto one key describes that key twice. The two sides are
asymmetric, which is why only removal misbehaves: adding a record id to a bitmap twice is a no-op, while
`removeRecordFromHistogramAndValueIndex` asserts membership *before* it removes, so the second colliding element
finds the record already gone and throws `Sanity check - record not found!` over data that is entirely valid.

It reaches `ReducedEntityIndex` and `GlobalEntityIndex` — a plain entity `String[]` is enough. It is *masked* on
the two counter-bearing classes, because their counter pre-filters the array down to the elements whose count
crossed the 0/1 boundary, which leaves one element per key. That is correct **by accident**: the counter exists
to ref-count owners, not to deduplicate an array, and nothing said so.

The same shape sits in both unique indexes. `OwnerUniqueIndex.unregisterUniqueKeyValue` and its `GlobalUniqueIndex
twin verify every array element and then mutate every array element, re-deriving ownership as they go, so a value
the array repeats is retired twice: the first occurrence removes the sole entry and the second finds no owner.
Reachable because registration explicitly tolerates re-claiming a value the same record already owns, and because
`AttributeIndexMutator` runs `NumberUtils.normalizeForIndexing`, which strips trailing zeros — so a user's
`{1.20, 1.2}` arrives as `{1.2, 1.2}`.

### "Same normalized value" is not "same entry" for a localized String

Both fixes above first measured identity as `equals` of the normalized value. That is wrong for exactly one
attribute flavour, and a design review caught it. `InvertedIndex` normalizes and then descends a tree ordered by
the index's **comparator**, and for a localized `String` attribute that comparator was `LocalizedStringComparator`
— a `Collator` at default strength with no tie-break. A collator is deliberately not consistent with `equals`: a
JDK 21 probe over `en` / `cs` / `de` shows `"ab"`, `"a<ZWSP>b"`, `"a<ZWJ>b"`, `"a<LRM>b"` and `"a<U+0001>b"` all
comparing equal with identical collation keys while being `!equals` (soft hyphen, NBSP, BOM and case are
distinguished, so the trigger is narrow — but zero-width characters are exactly what pasted CMS content carries).

So for that flavour the tree merged entries that the counter, keyed by `equals` in a `HashMap`, kept apart — which
is defect (1) again, reached through collation instead of through normalization, and untouched by the counter fix.

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

### Option D — fold the array onto its distinct keys inside `FilterIndex` (chosen)

The two whole-value array loops keep the FIRST RAW element per distinct key; the two delta loops instead
`Assert.isPremiseValid` that the incoming array names each key at most once. The unique indexes fold their arrays
onto distinct values through one shared helper.

- **Pros:** fixes the primitive rather than one caller, so `ReducedEntityIndex`, `GlobalEntityIndex` and every
  future caller are covered at once. The raw element is kept because the tree normalizes its own keys — handing
  it a pre-normalized value would fold twice and address the wrong bucket.
- **Cons:** the fold and the delta assertion measure identity in the same place, so a comparator that is not
  consistent with `equals` silently defeats both (which is what Option F/G exist to prevent).

**Why the delta is asserted and not folded.** An earlier draft folded all four loops and it was wrong. A delta
states which keys the record is joining or leaving *entirely*; folding a duplicate would quietly accept a broken
contract, and on the removal side it would still be wrong, because whether the record keeps a bucket through an
element the delta does not mention is a question only the caller's own multiplicity bookkeeping can answer. The
range axis of the same two methods IS self-sufficient — it reads the record's remaining ranges back out of the
index — so the asymmetry is deliberate and must not be "harmonized" away later.

### Option E — give `ReducedEntityIndex` a cardinality counter too (declined)

Extend the mechanism that masks the defect on the reference-type index to the classes that lack it.

- **Pros:** reuses a structure that already exists and demonstrably suppresses the symptom.
- **Rejected because:** it fixes one caller of a broken primitive and leaves `GlobalEntityIndex` — and every later
  caller — exposed, since the defect is in `FilterIndex` itself. Worse, it is not deployable:
  `AttributeCardinalityIndexMapLoader` reads the counter from a **persisted** storage part and never rebuilds it,
  while multiplicity is recoverable only from the entity body, so every existing catalog would load an empty
  counter and throw `Cardinality … is null` on the first removal. Revisit only if a counter ever becomes
  reconstructible without a full reindex.

### Option F — tie-break the shared `LocalizedStringComparator` (declined)

Make the collation order consistent with `equals` at its source, fixing every user at once.

- **Pros:** one change, one place; both index roles and any future user inherit it.
- **Rejected because:** that class is contractually *a cached collator* — "exactly the same total order as
  `Collator.compare` for that locale" — and three tests enforce it: every pair in a national corpus must match
  `Collator.compare`'s sign, canonically equivalent NFC and NFD forms must compare **equal**, and a caller that
  supplies a `PRIMARY`-strength collator must keep its deliberate `"a" == "á"`. A tie-break breaks all three, and
  the last two are behaviour a user can legitimately depend on. This was proposed, implemented, and reverted when
  its own test suite refused it.

### Option G — raise the collator's strength to `Collator.IDENTICAL` (declined)

Fold the distinction into the cached collation key instead of comparing again after it.

- **Pros:** genuinely attractive — the hot compare path stays a single `Arrays.compareUnsigned` with **zero**
  added per-comparison cost, and the extra work moves into key computation, which `CollationKeyCache` already
  performs once per distinct value. Measured to work: at `IDENTICAL` all four ignorable characters are
  distinguished while Czech collation is preserved (`h` before `ch`, `c` before `č`).
- **Rejected because:** it costs **+32% on every cached collation key** (374 → 494 bytes on a 59-character
  sample) and +5% to compute one, and — decisively — it makes the stored key order depend on the JDK's
  `IDENTICAL` collation implementation, where `String.compareTo`'s UTF-16 code-unit order cannot shift under an
  upgrade. Since the tie-break measured free, paying memory *and* adding version sensitivity buys nothing.
  Revisit if a tie-break ever shows up in a profile.

## Decision

**Chosen: Option A**, with the reporting half of Option C folded into it — plus **Option D** for the array write
path, and a tie-break scoped to the index key space rather than to the shared comparator (Options F and G both
declined).

**The tie-break's placement is load-bearing, not a detail.** It lives in
`EqualsConsistentLocalizedStringComparator`, in `io.evitadb.index.attribute`, and both `FilterIndex.getComparator`
and `SortIndex.createComparatorFor` derive from it — both, because the two roles share one value tree per
attribute and a tree built under one order and searched under another mis-places every key. It is sound there and
unsound in `evita_common` for one concrete reason: an index key space is narrower than a general string, because
`FilterIndex.getNormalizer` (and `SortIndex.createNormalizerFor`, verbatim) has already folded every `String` key
to Unicode NFD. Two canonically equivalent forms are therefore the same `String` by the time the comparator sees
them, so the tie-break provably cannot separate an NFC/NFD pair — it separates only what the normalizer left
distinct and the collation merged. **If that NFD normalization is ever removed, the tie-break stops being safe**;
`CollatedKeyIdentityTest.CanonicalEquivalence` fails loudly in that case rather than letting it pass.

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
- **The audit normalizes BOTH sides, and that is load-bearing rather than tidy.** The first shape of it
  canonicalized the counter key and then looked that key up among the *raw* stored buckets. A stored bucket is
  canonical only under the normalizer of the version that **wrote** it, which is not today's, so the two sides
  were in different key spaces and the lookup missed on entries that were perfectly healthy — an ERROR telling
  the operator of an intact catalog to reindex it. A false alarm here is strictly worse than no diagnostic,
  because the whole value of the message is that someone acts on it.

  The pointed case is `BigDecimalNumberRange`, and it is not hypothetical: `Migration_2026_2#rekeyFilterIndex`
  re-keys `String` and `BigDecimal` filter parts and returns `false` for everything else, `getNormalizer` had no
  range branch at all before 2026.2, and `NumberRange` equality is defined on the **scaled** thresholds — so a
  catalog that reached protocol 6 by upgrade from 2026.1 still holds raw, un-rescaled ranges whose stored key
  differs from the counter's canonical one on every entry.

  Putting the stored value through the same normalizer removes the whole class of problem: the normalizer is
  idempotent, so an already-canonical bucket passes through untouched and an older one lands in exactly the key
  space the counter is in. Two buckets that then collapse onto one key must have their records **unioned** —
  overwriting drops the losing bucket's records and reintroduces the same false alarm from the other side. This
  is also what made auditing `OffsetDateTime` counters possible at all: the earlier exclusion of temporal types
  was a symptom of the raw comparison, not a property of the type.

- **The fold measures identity with `equals`, and that is exact only because of the tie-break.** Natural order is
  consistent with `equals` for every key class the filter index stores — a scaled `Integer`, an `Instant`, a
  rescaled range whose equality is over the very fields `compareTo` reads, a non-localized `String` — and the
  localized `String` order is made so by `EqualsConsistentLocalizedStringComparator`. A comparator added here that
  is NOT consistent with `equals` reopens the hole and would have to fold on the comparator instead. The
  dependency is stated at both ends, in `FilterIndex#foldOntoDistinctIndexKeys` and in the comparator's javadoc.
- **The fold is linear and allocates only when it folds.** Accepted elements stay a prefix of the caller's own
  array until the first collision forces a compacted copy, so the overwhelmingly common no-collision case returns
  the input array untouched. An intermediate version ran a quadratic comparator pass on every array write — a
  real write-path regression on attributes with nothing to do with collation — and was removed once the tie-break
  made bucket identity equal `equals` for every comparator in play.
- **The delta entry points validate before either axis is mutated.** The range-type check was hoisted out of the
  range branch, so a malformed delta now leaves the index untouched instead of mutating the range axis and then
  throwing. `removeRecordDelta` also now consumes the array `verifyValueArray` returns rather than the raw one,
  matching its three siblings — the old asymmetry meant a `Serializable`-but-not-`Comparable` element type was
  added as `String.valueOf(x)` and removed as `x`.
- **`AttributeCardinalityIndex` is deliberately NOT part of the fold.** It must keep RAW multiplicity — that is
  what makes "the last owner has left" answerable — so the bucket axis folds and the counter loops never do.
  Folding the counter would reintroduce defect (1) from the other side.

## Verification

- `ReferenceAttributeIndexKeyCollisionFunctionalTest` — 10 tests at the public API. **6 fail against the unfixed
  build** (counterfactual run with the engine change reverted): the colocated-value query and write paths, the
  Unicode NFD case, the array branch, the temporal offset collapse, and the grouped-index sibling case. The other
  four are deliberate confirming-negatives — a `Currency` control (bijective normalizer), a range control, a
  temporal control, and a unique-only case that asserts the write is **rejected** with
  `UniqueValueViolationException`. That last one started as an attempted exploit and refuted itself: a folded
  unique is enforced on the shared tree's normalized key, so the collision is caught at write time. It is kept as
  the assertion that this is so.

  Two of the controls originally proved nothing: they ended in a bare `assertDoesNotThrow`, which stays green in
  the very state they exist to distinguish themselves from — two values that never shared an entry, so no entry
  could be lost. They now assert the premise and the outcome. The sub-millisecond control first proves both owners
  land in ONE entry (both spellings query back `{1, 2}`), then that the survivor is still reachable through that
  entry after the first owner leaves — the assertion the defect fails, and fails *silently*, without throwing
  anything. The bijective control now checks that the re-inserted `Currency` is queryable at its new key, absent
  from its old one, and that the other owner's separate entry is undisturbed.
- `Migration_2026_3_Test` — 7 tests on the re-key transform, including the summing branch that is the repair.
- `Migration_2026_3_AuditTest` — 15 tests on the audit itself, which neither of the other two can reach (it needs
  both halves of a storage pair). **Proven by counterfactual**: reverting the stored-side normalization to the raw
  bucket value turns 4 of the 15 red — a healthy range counter reports 1 missing entry instead of 0, two colliding
  buckets report 1 instead of 0, a genuinely damaged collection over-reports 2 instead of 1, and an undamaged
  string counter reports **2 phantom missing entries where the truth is 0**. That last number is the whole point:
  it is a REINDEX error printed over intact data. The suite also pins the pairing invariant across seven types and
  all three sibling shapes (`countOrphanedCounters(…) == NOT_VERIFIABLE` exactly when `whyNotChecked(…) != null`),
  `resolveScale`'s authority order with a sibling scale of 2 against a schema declaring 4, and the operator-facing
  message: the attribute-list cap and its tally, and the reference / locale / index address of one counter.
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
- `FilterIndexArrayFoldTest` — 15 tests over the array fold on whole-value writes, deltas and the range axis,
  including the localized flavour. **Proven by counterfactual twice**: reverting the fold leaves the three
  whole-value collision cases (NFD string, scaled `BigDecimal`, offset-collapsed `OffsetDateTime`), the bystander
  case and the range-axis case red with `Sanity check - record not found!`; and while identity was still measured
  by `equals`, the two collated cases were red for exactly that reason and the other twelve green.
- `UniqueIndexArrayDuplicateTest` — 4 tests on the unique siblings. The registration case is green before the fix
  and is what makes the state reachable at all; the two unregistration cases (owner and global) are the defect.
- `AttributeCardinalityIndexTest.RawMultiplicity` — 2 tests that were green before this work and exist to STAY
  green: they pin that the counter counts every colliding array element and releases its entry only on the last
  removal. They are the guard against someone folding the counter loops along with the bucket ones.
- `CollatedKeyIdentityTest` — 8 tests on the collated identity decision, in four parts. The premise (a bare
  `Collator` equates the two spellings, the index does not); canonical equivalence (NFC and NFD forms of
  `žluťoučký kůň` land in ONE bucket, which is the invariant that makes the tie-break safe here); the migration
  hazard below; and that the tie-break changes no ordering decision the collation actually makes, checked over
  every ordered pair of a Czech word list where collation and codepoint order genuinely disagree.
- `FilterIndexTest.shouldReturnLocalizedComparatorForLocalizedString` asserts the PROPERTY, not only the type — a
  type check cannot see whether the order it admits identifies buckets correctly, which was the whole defect.
- **The tie-break was measured before adoption, not argued.** JMH, 3 forks × 5 iterations, a 100,000-value
  localized tree, `InvertedIndex.getRecordsEqualTo` (ns/op, lower is better):

  | | baseline | `compareTo` | `equals`-first |
  |---|---|---|---|
  | hit, 8-char code | 633.9 ± 24.9 | 605.4 ± 30.3 | 623.5 ± 36.7 |
  | hit, 48-char accented | 972.6 ± 37.0 | 990.6 ± 47.6 | 957.1 ± 26.3 |
  | miss, 8-char code | 604.1 ± 35.5 | 608.7 ± 47.1 | 696.3 ± 97.8 |
  | miss, 48-char accented | 1395.3 ± 35.3 | 1454.5 ± 48.9 | 1452.9 ± 52.3 |

  Every error bar overlaps; on the case predicted to be worst (long accented hits) the two tie-break shapes land
  at +1.8% and −1.6%, i.e. noise pointing both ways. The hypothesis that an intrinsic `String.equals` guard would
  beat a plain `compareTo` is **not supported** — it wins on one fixture and loses on the other — so the simpler
  shape was taken. The arms are a JMH `@Param` so they interleave rather than being two builds compared
  back-to-back, and the tie-break arms carry a lambda indirection production does not, making these an upper
  bound. The write path is bracketed rather than measured: a new key ends on a miss and pays nothing, an existing
  key ends on a hit and pays once.
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

- **The tie-break strands records that a pre-tie-break engine mis-filed, and that is accepted knowingly.** A
  catalog written before this change can hold a record whose entity value is `"a<ZWSP>b"` but which sits in the
  `"ab"` bucket, because the collation merged them at index time and only one spelling reached the disk. Under
  the new order those are different keys, so that record's removal names a bucket that does not exist and fails
  the index premise. `CollatedKeyIdentityTest.MigrationHazard` proves both halves over one stored state: it
  succeeds under the old comparator and throws `Sanity check - record not found!` under the current one, while
  data written after the change round-trips cleanly. **The hazard is migration-only** — it strands records that
  are already mis-filed and creates no new ones.

  **No diagnostic was built for it, deliberately.** Finding these records means comparing entity bodies against
  the tree, which is a reindex-grade scan; and the remedy for a catalog that has them is a reindex anyway, so a
  diagnostic would cost a full scan to recommend what the failure itself already forces. Note that
  `Migration_2026_3`'s audit *does* count such a record — its `buckets` map is keyed by `equals`, so the second
  spelling misses and is tallied as an orphaned counter. That is the right recommendation reached by the wrong
  route, and the number must not be read as a count of collated damage.
- **The delta premise now fails at INSERT where the old code failed at removal**, for the one input that can
  reach it: a localized reference `String[]` holding two collator-equal elements on a counter-bearing index. The
  counter produces two boundary crossings, the delta names one key twice, and the premise fires. The data was
  already doomed at that point — the old behaviour was a silent desync followed by a failure on the way out — so
  moving the failure to the write is the intended trade, not a regression.
- **`LocalizedStringComparator` is untouched and must stay that way.** Its contract is to be a cached collator,
  and it is tested as one. Anyone tempted to "simplify" by moving the tie-break down into it should read Option F
  and then run `LocalizedStringComparatorTest`, which refuses the change in three different ways.
- **The array defect is pre-existing and shipped.** Every released 2026.2 engine carries it, so catalogs in the
  field may already have a record missing from a bucket its array should have put it in. Tracing the transactional
  and warm-up rollback paths well enough to bound that population was considered and dropped for the same reason
  as the diagnostic above: the remedy is a reindex either way.
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
  catalog, but it does in 2026.2, which wrote every protocol-6 one. (2) **Counters whose sibling filter index is
  no longer in storage** — there is simply nothing to compare against. Both reasons are named individually in the
  warning, because "an index the audit cannot walk" and "an index that is not there" send an operator to different
  places.
- **All four collision-prone types ARE audited, temporal included** — an earlier revision of this record excluded
  `OffsetDateTime` on the grounds that the audit had no key space to compare in. That was a consequence of
  comparing a normalized counter key against a raw stored bucket, and it disappeared with the fix below; see
  *The audit normalizes both sides*.
- **`Migration_2026_3` calls `Migration_2026_2.resolveIndexedDecimalPlaces`**, so the older migration cannot be
  deleted without moving that method. Accepted knowingly; the instruction to move it lives at the call site.
- **A schema change to `indexedDecimalPlaces` is accepted and rebuilds nothing — checked, not assumed.**
  `AttributeCardinalityIndex.normalizeKey` reads the *live* schema's scale while the tree's keys sit at the scale
  frozen into its storage part, so this record left open whether `ModifyAttributeSchemaTypeMutation` forces a
  synchronous rebuild. It does not: no reindexing machinery exists at all (`EntityCollection` says so outright
  where it *refuses* a newly-declared filter accelerator on a non-empty collection, for exactly that reason), and
  nothing refuses or intercepts a change of `indexedDecimalPlaces` — every mutation simply carries the value
  through into the rebuilt schema object. What exists instead is `FilterIndex.assertIndexedDecimalPlacesUnchanged`,
  which throws `the index must be rebuilt before it can be modified` on the next write that touches the index.
  Counters written either side of such a change are therefore keyed at different scales, and the divergence is
  loud rather than silent — a changed scale changes every non-zero key, so an insert reports `BOUNDARY_CROSSED`
  and carries the write on into the tree where that guard fires, and a removal throws `Cardinality … is null`.
  `normalizeKey`'s javadoc states this at the site. It is moot for the migration, which prefers the frozen scale.

## Related work

- `2026-08-10-stored-value-normalization-split` — introduced the `Instant` branch of `getNormalizer` and set the
  "conversion belongs in the BWC reader" rule this record knowingly departs from, with reasons above.
- `2026-09-04-millisecond-temporal-precision` — the second temporal fold, and the load-time repair that makes
  temporal counters unauditable from storage parts.

## Timeline

- **2026-09-20** — defect reported from a production e-commerce catalog as an index premise failure
- **2026-09-21** — root cause proven by counterfactual; engine fix, format change and 6 → 7 migration implemented
- **2026-09-21** — migration verified against five real catalogs; detection and startup diagnostic added
- **2026-09-21** — review found the audit comparing a normalized key against raw stored buckets; both sides
  normalized, colliding buckets unioned, and the audit covered by `Migration_2026_3_AuditTest`
- **2026-09-21** — writing the counter's tests exposed the unfolded array write path, present since long before
  #1620 and masked by the counter on the two classes that have one; fold and delta premise added, unique siblings
  fixed with them
- **2026-09-21** — design review found both fixes measuring identity by `equals`, which is wrong for a localized
  `String`; the collated gap was confirmed by probe, the tie-break was implemented in the shared comparator,
  **reverted** when that class's own contract suite refused it, and re-scoped to the index key space
- **2026-09-21** — tie-break measured against `IDENTICAL` strength and against a plain `compareTo`; adopted
