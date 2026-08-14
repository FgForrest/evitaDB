# How an index's heap footprint is accounted

**Why this file is kept.** Every `getHeapSizeInBytes()` in the index layer is an application of these
rules, and none of them is visible in the code that applies them — a wrong ruling produces a plausible
number, not a failure. A contributor adding an index type, or adding a field to one, needs this to get
the same answer the rest of the layer gives. The rules were signed off 2026-08-06; the parent record
explains what they were adopted for.

## The four accounting rules

They are ordered: 1 and 2 decide *whether* a figure is charged, 3 decides *which* of several
defensible figures, and 4 constrains how the question may be asked at all.

1. **Never charge shared objects** — shared *by contract*, not *interned by the runtime*. Exclude enum
   constants, `Class` objects, `EmptyBitmap.INSTANCE`, borrowed payloads. **Charge boxed
   `Integer`/`Long` in full** — `-XX:AutoBoxCacheMax` moves the interning boundary, so an estimator
   keyed on it would answer differently on two JVMs running the same catalog.
2. **Structure shared with a SUPERSEDED version is charged in full, in both versions.** The test is who
   outlives whom. A copy-on-write alias with a *previous* version is entirely the current version's
   cost: the predecessor is garbage-in-waiting, and reporting the bytes as shared would show them
   belonging to nobody.
3. **When several figures are defensible, report the higher.** Applied after 1 and 2, never instead of
   them. An under-report is the dangerous direction: this number exists to decide which index to act
   on.
4. **Ownership is static per class** — no traversal order, no global already-counted set. A class
   answers the same for a given instance no matter what walked it or in what order, which is what makes
   the figures composable and the tests deterministic.

## Ownership rulings that are not obvious from the code

**Flush-time bookkeeping is charged unless it is cleared at commit** and therefore always empty for a
read-only catalog. Evaluated and **charged**: `AttributeIndex.persisted{Chain,FilterInverted,
FilterRange,Unique,Sort}LeafPages` (empty only in the fresh-index constructor; the from-committed-maps
constructor populates them via `snapshotLeafPages`, and that path serves cold load),
`HistogramIndexMapComponent.persistedLeafPages` (the same baseline for the histogram families, refreshed
at the end of every flush) and `EntityIndex.original{AttributeIndexes,PriceIndexes,FacetIndexes,
HistogramKeys}` (`captureOriginalsFromComponents()` fills them from the committed baseline in every
terminal subclass constructor, load path included). `PageStreamRegistry` stays **excluded**.

The histogram baseline is the one place where a *key* is charged and its `AttributeIndex` counterpart is
not: those snapshots are keyed by the very instances the sub-index maps hold, whereas
`HistogramIndex.persistedLeafPagesOf` mints a fresh `HistogramIndexKey` and the baseline is its only
holder. The manifest's `HistogramIndexStorageKey` is a different record and charged separately at the
index.

**`EntityIndex.components` — the trap whose failure mode is a silent zero.** The list does *not* hold
the sub-indexes; it holds small **wrapper** objects (`PriceIndexComponent`,
`AttributeCardinalityIndexMapComponent`, `HistogramIndexMapComponent`, `ReferenceTypeCardinalityComponent`,
`GroupCardinalityComponent`) that each point at a field. The wrappers are reachable **only** through
this list. Charge the list spine, its backing array **and** every wrapper's own object; exclude what
each wrapper points at, since that is a field charged at the index. Ruling this "elements are charged
as fields" would have charged the wrappers *nowhere* — and the symptom is a number that is quietly
40 B (112 B for a reference-type index) too small per index, not a mismatch anything would catch.
~21 MB across the senesi catalog.

**One wrapper is not a pure adapter, and the same trap catches it one level down.**
`HistogramIndexMapComponent` owns `persistedLeafPages` outright — a plain map no field of the index
points at — so a shell charge alone reports it as free. It therefore prices *itself*, and the two
indexes registering it (`ReferencedTypeEntityIndex`, `ReducedGroupEntityIndex`) each keep a typed
reference for that one call rather than looking it back up out of the list, so a rename cannot silently
turn the charge into a zero. Any future wrapper holding state of its own must do the same.

**Collators: charge the 48-byte private part only**, never the ~30 KB `RBCollationTables`, which is
shared by identity per locale (verified). `CollationKeyCache` is **unreported anywhere** — it is a
static per-locale JVM registry, owned by no index.

**Views and injected references are slot-only.** `SortIndexView.sharedTree` is owned by
`AttributeIndex`. `AttributeIndex.filterIndex` / `uniqueViewIndex` hold views that own no transactional
state — charge the view object, exclude the shared tree it points at. `InvertedIndex.normalizer` /
`comparator` / `plainType` are constructor-injected at every site. `AttributeIndexKey` and
`RepresentativeReferenceKey` are slot-only in sub-indexes — **except** in a histogram, which mints its
own `AttributeIndexKey` in its constructor (one per locale for the localized variant) and is its only
holder: 24 B, charged there.

**`SortIndexChanges` / `ChainIndexChanges` hold a back-reference to their own index** — charge their
own fields, never follow the pointer.

**Price indexes split by body ownership.** `PriceListAndCurrencyPriceRefIndex` charges its tree
**spine only**; the super index owns the bodies. The element-sizer machinery exists for exactly this.
`ReferencedTypeEntityIndex.priceIndex` is `VoidPriceIndex.INSTANCE` — a shared singleton, 0.

**Charged in full, resolved by reading rather than by ruling:** `AttributeIndex.sharedValueIndex` /
`sharedRangeIndex` (javadoc says OWNED); `AttributeIndex.uniqueIndex` (standalone instances,
global-unique-localized only); `EntityIndex.entityIds` (a genuine superset bitmap, distinct object);
`ReferenceTypeCardinalityIndex.memoizedAllReferencedPrimaryKeys` (built from the map's *keys* through a
fresh writer, not a union of the value bitmaps); `TransactionalRangePoint`'s two bitmaps (constructed
fresh); memoized caches generally — `memoizedAllRecordsFormula`, `cachedAscendingArrays`,
`envelopingNowCache`, `UnorderedLookupTree.memoizedArray`.

**`GlobalUniqueIndex` locale maps:** `Locale` is JVM-interned (`LocaleObjectCache`) → slot only. The
boxed ids are charged per holder, i.e. twice, by rule 1.

## The exception that used to be here

**Cached map views were 224 B per index of deliberate under-report, and are now zero.** A `HashMap`
allocates its `keySet`/`values`/`entrySet` view on first use and **caches it in the map**, so every
accessor a construction or flush path reaches a map through parked one more permanent object on it —
fourteen per entity index before any caller touched it, seventeen once flushed. They were real bytes
nothing else owned, but `MapHeapSize` cannot see one without calling the accessor that would *create*
it, which would make measuring a map grow it. Rule 3 classified the flat per-index error as a
convention rather than a defect, and `cachedMapViewBytes` pinned it so it could not drift.

Measuring it is what got rid of it. Every walk on those paths now goes through `forEach`, which
`TransactionalMap` and `PersistentTransactionalMap` override to delegate to the backing map rather than
to iterate the `entrySet()` the JDK default asks for: `AttributeIndex` (`collectKeys`,
`getModifiedStorageParts`, `resetDirty`, `snapshotLeafPages`), `FacetIndex`, `AbstractPriceIndex`, and
the attribute-cardinality and histogram map components. `IndexCardinalityProjection` — the only
production caller of the set-returning key accessors — walks through
`AttributeIndexContract#forEachAttributeIndexKey` instead. **Every empty entity index of every kind now
measures exactly against JOL**, and `EntityIndexHeapSizeTest` asserts that rather than a constant.

Two accessors are deliberately left as they are, and must not be "finished off":
`PriceIndexReadContract#getPriceListAndCurrencyIndexes` and `getPriceIndexesStream`, which the price
query translators call repeatedly against the same index. That is precisely the case the JDK's caching
exists for; converting them would trade one retained view for a fresh walk per query.

**A walk added later that asks for an accessor reappears as a shortfall** in the empty-index cases, and
in `EntityIndexHeapSizeTest.shouldNotAccumulateCachedViewsOnFlush` if it is on the flush path only —
which is the case nothing else in the suite can see, since every other assertion measures a freshly
built index.

## Verification method

Every class is verified end-to-end against JOL with plain `ownedSize`;
`-Djol.magicFieldOffset=true` is on the surefire `argLine`. Two constraints on how such a test may be
written, both learned by having them fail:

- **Measurement must be identity-based.** JOL's `subtract` matches by address, which breaks under
  parallel test execution; and a walk must never descend into a `Class`.
- **JOL cannot walk lambdas.** Hidden classes break `parseInstance`; neither `--add-opens` nor
  shared-root subtraction rescues it. A structure holding a lambda has to be measured around it.
