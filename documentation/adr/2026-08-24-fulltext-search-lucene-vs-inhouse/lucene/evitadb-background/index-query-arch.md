# evitaDB in-memory index & query architecture — research notes

Scratchpad for the "how would an embedded fulltext index integrate with evitaDB" design analysis.
Every claim carries a `file:line` anchor. **FACT** = read in source. **INFERENCE** = deduced, flagged inline.

Repo root: `/www/oss/evita/evitaDB-dev`. Paths below are relative to it unless absolute.
Verified against branch `dev` @ `6a486f0a56`, 2026-08-13.

---

## 0. Prior art in `documentation/adr/` (read this before proposing anything)

Four records bear directly on a fulltext design. They already contain *measured* answers to questions
this analysis would otherwise re-derive.

Every number quoted in this section was read directly out of the `results.md` files, not taken from a search
result — the tooling in this environment is known to corrupt numeric literals in grep output, and these figures are
the only quantitative content in the whole document.

### 0.1 A radix trie for string keys was measured and rejected — NO-GO

`documentation/performance/individual/RadixTrieMemorySpike/results.md:8-12`:

> **Verdict: NO-GO for the stated targets.** Confirmed against **real evitaDB demo-catalog `Product` data**… the trie
> regresses on **all four** target attributes — `code`, `url`, `published`, `changed` — by **1.13×–2.27×**.

Root cause, `results.md:37-40`: the trie produces ~1.27 nodes/key; at ~48–60 B fixed overhead per node that is
~70–95 B/key of pure overhead, which *exceeds the entire ~22 B raw key*. Worst case is `url_en` under collation:
193.5 B/key trie vs 85.3 B/key B+ tree (`results.md:25-31`).

This matters because `FilterIndex` carries a `TOBEDONE JNO naive and slow - use RadixTree` marker three times
(§3.2) — **that suggestion has already been tried and lost on memory.** Method was JOL `GraphLayout.totalSize()`
measured as delta vs an empty structure of the same type (`results.md:52-61`).

### 0.2 Bucket decomposition was measured and adopted — the current memory floor

`documentation/performance/individual/BucketStoreMemorySpike/results.md:18`: **"Verdict: GO — large, real savings,
opposite of the trie."** Aggregate over 10 measured attributes: 2.37 MB → 0.51 MB, ~4.7× / 79 % reduction of the
value-store heap (`results.md:74-77`), with the caveat that a stored (rather than derived) id column roughly halves
the single-record wins, giving a realistic catalog range of ~2.5–4.7×.

Per-bucket heap, pre-optimization "PROD" → best (`results.md:34-39`): `code` 113.4 B → 10.2 B (11×); `url_en`
117.3 → 10.3; `published` 72.4 → 8.0; `changed` (75 % multi-record) 199.2 → 152.0 (only 0.76×).

`results.md:66-72`: **"The gain tracks cardinality, not datatype."** High-cardinality mostly-single-record
attributes win 8–11×; low-cardinality multi-record attributes are bitmap-bound and ~neutral.

**INFERENCE:** the "PROD" column is the *pre-optimization* baseline; those optimizations are now shipped (columnar
leaves, `ValueToRecordPrimitive`, `FrontCodedStringColumn`). Treat ~10–12 B/bucket as today's single-record floor
for a `code`-like attribute, and 113 B/bucket as a historical high-water mark.

### 0.3 Block sizes are measured per tree, and all three prefer > 64

`documentation/performance/individual/RangeIndexBlockSizeBenchmark/README.md:156-157`:

> Three value-bearing trees have now been measured for block size: `SortIndex` → 256, `InvertedIndex` → 256
> (this batch), `RangeIndex` → 512 (this batch). All three prefer **larger than the tree's
> `DEFAULT_VALUE_BLOCK_SIZE = 64`**.

### 0.4 A normalizer change is an on-disk format change

`documentation/adr/2026-08-10-stored-value-normalization-split.md:210-212`:

> **Invariant for the next person:** any future change to `FilterIndex.getNormalizer` for a type that has already
> been persisted is an on-disk format change, and needs a matching conversion in the BWC reader for the format that
> wrote it. The normalizer is not merely a runtime detail.

Because (`:191-199`) bucket keys are **persisted already normalized** — `InvertedIndex` applies the normalizer in
`addRecord`, and `AttributeIndexLoader` feeds the stored points into the tree verbatim. Directly relevant: any
analysis chain (tokenizer / case folding / stemmer) a fulltext index applies is, by the same argument, a persisted
format decision, not a runtime knob.

Also `:200-204`: two migration strategies coexist — `Migration_2026_2` re-keys eagerly at upgrade time; the
`LocalDateTime` case is handled in the BWC *reader* instead (self-healing, covers every read path). Both compose.

---

## 1. Transactional memory layer

### 1.1 The contract is two interfaces, and it is small

`evita_engine/src/main/java/io/evitadb/core/transaction/memory/TransactionalLayerCreator.java:51-73` — owning a
diff layer:

```java
public interface TransactionalLayerCreator<T> {
	long getId();
	T createLayer();
}
```

`getId()` must come from `TransactionalObjectVersion.SEQUENCE` — `:58-61`: *"Ids must be drawn from
`TransactionalObjectVersion#SEQUENCE`, which is what guarantees the global uniqueness this contract requires…
`TransactionalLayerMaintainer` therefore verifies the invariant and fails loudly on a collision."*

`.../TransactionalStateProducer.java:46-85` — producing the committed form:

```java
public interface TransactionalStateProducer<COPY> {
	@Nonnull
	COPY createCopyWithMergedTransactionalMemory(@Nonnull TransactionalLayerMaintainer transactionalLayer);
	default void removeLayer() { … }
	void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer);
}
```

`.../TransactionalLayerProducer.java:39-71` is the union of both, and adds the two-arg merge that receives the
resolved diff piece:

```java
public interface TransactionalLayerProducer<DIFF_PIECE, COPY>
	extends TransactionalLayerCreator<DIFF_PIECE>, TransactionalStateProducer<COPY> {
	…
	@Nonnull
	COPY createCopyWithMergedTransactionalMemory(
		@Nullable DIFF_PIECE layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	);
}
```

`.../VoidTransactionMemoryProducer.java:39` is the third role — a producer that owns **no** layer:

```java
public interface VoidTransactionMemoryProducer<S> extends TransactionalStateProducer<S> {
}
```

`.../VoidTransactionMemoryProducer.java:27-30`: *"It should be used in all objects that maintain transactionally
modifiable internal data fields but cannot be modified by themselves. I.e. they hold no diff of their own, but they
need to provide a `createCopyWithMergedTransactionalMemory` implementation so that they can create a new instance
consisting of new internal objects."*

**The load-bearing fact: `DIFF_PIECE` is an entirely opaque type parameter.** The framework never inspects it. It
is produced by `createLayer()`, stored in a `long`-keyed registry, handed back to
`createCopyWithMergedTransactionalMemory`, and discarded. Nothing constrains it to be a fine-grained diff.

### 1.2 Commit is a single deep merge cascade producing a new object graph

`.../TransactionalLayerMaintainer.java:317-322` — pure dispatch, no registry lookup for layer-less producers:

```java
	public <S> S getStateCopyWithCommittedChanges(@Nonnull TransactionalStateProducer<S> transactionalStateProducer) {
		// pure dispatch - the producer's own type decides whether a diff layer has to be resolved at all, so objects
		// that own no layer skip the registry lookup that would be guaranteed to miss for them
		return transactionalStateProducer.createCopyWithMergedTransactionalMemory(this);
	}
```

`:336-348` — the single place a layer is resolved and disposed:

```java
	<T, S> S copyWithOwnLayer(@Nonnull TransactionalLayerProducer<T, S> transactionalLayerProducer) {
		final TransactionalLayerEntry<T> transactionalLayerForItem = getEntryIfExists(
			transactionalLayerProducer.getId(), transactionalLayerProducer
		);
		final S copyWithCommittedChanges = transactionalLayerProducer.createCopyWithMergedTransactionalMemory(
			transactionalLayerForItem == null ? null : transactionalLayerForItem.getItem(),
			this
		);
		if (!this.avoidDiscardingState.get() && transactionalLayerForItem != null) {
			transactionalLayerForItem.discard();
		}
		return copyWithCommittedChanges;
	}
```

Safety net — `:357-371` `verifyLayerWasFullySwept()` collects every entry still in state `ALIVE` and throws
`StaleTransactionMemoryException`. So **a layer you create but never consume fails the commit loudly** rather than
silently losing writes.

The top of the cascade: `.../transaction/TransactionTrunkFinalizer.java:104-132`
(`commitCatalogChanges(long, TransactionMutation)`), which calls
`this.lastTransactionLayer.getStateCopyWithCommittedChanges(this.catalogToUpdate)` at `:114` and
`verifyLayerWasFullySwept()` at `:128`. The result is a **new `Catalog` instance** (`:130`
`this.committedCatalog = newCatalog;`).

`evita_engine/src/main/java/io/evitadb/core/catalog/Catalog.java:190-193` and
`evita_engine/src/main/java/io/evitadb/core/collection/EntityCollection.java:199-200` — both are
`TransactionalLayerProducer<DataStoreChanges, …>`.

### 1.3 Readers keep their snapshot by holding a reference — and commit is *pruned*

`EntityCollection.java:1748-1784`, the dirty branch, is the sharpest statement of the model:

```java
		if (transactionalChanges != null) {
			// this is the DIRTY branch, and it must stay the only route for a collection that has pending changes:
			// indexes are rebuilt here by merging their transactional layer, which yields fresh instances. The clean
			// branch below instead forwards indexes to the next catalog version BY REFERENCE
			// (createIndexCopiesForNewCatalogAttachment), which is sound only because a dirty collection never reaches
			// it — routing one through there would share a live transactional layer across two catalog versions
			transactionalLayer.removeTransactionalMemoryLayer(this);
			// this creates copy of the indexes with all changes applied - pruned: only the indexes this transaction
			// actually mutated (plus the ones its key delta added or replaced) are rebuilt, every other index is
			// carried across the catalog version by reference. …
			final IndexTuple indexTuple = pruneMergeIndexes(
				transactionalLayer, transactionalChanges.popLastCommittedDirtyIndexKeys()
			);
```

And `EntityCollection.java:2746-2752`:

```java
// Phase 2 — only the touched indexes, resolved through the merger below; the map applies its own
// key delta around them and derives the next version by path-copying just those keys onto the
// previous immutable snapshot
final ChampMap<EntityIndexKey, EntityIndex> mergedIndexes =
			this.indexes.createCopyWithMergedTransactionalMemory(
				indexChanges, transactionalLayer, rebuiltKeys,
				new PrunedIndexMerger(transactionalLayer, rebuiltKeys, globalsByScope)
			);
```

**Consequences that matter for a fulltext design:**

1. A reader's snapshot is *whatever object graph its `Catalog` reference points at*. There is no version vector, no
   MVCC chain, no read timestamp. Immutability of the committed graph is what makes it safe.
2. Commit cost is **O(touched indexes)**, not O(all indexes) — an untouched index is carried forward by pointer.
   A fulltext index that reconstructs itself on every commit would break this property for its own collection.
3. The index forest map is CHAMP-backed (`ChampMap`), so even the *map* is path-copied rather than rebuilt.

### 1.4 Two coexisting strategies, and which one a new structure picks is a real fork

- **Diff layer over a mutable delegate** — `index/map/TransactionalMap.java:67-76`:
  `private final Map<K,V> mapDelegate;` plus `MapChanges`. Commit rebuilds from the recorded deltas.
- **Persistent immutable (CHAMP)** — `index/map/PersistentTransactionalMap.java:107-123`:
  `private transient volatile Map<K,V> state;`. Commit is `O(Δ·log N)` path-copy, not a full rebuild.
- **Whole-value replacement** — `index/reference/TransactionalReference.java:54-58`:
  `private final AtomicReference<T> value;` plus `ReferenceChanges`. The diff *is* the new value.

`TransactionalReference` is the existence proof for the discriminating question: **a diff layer may be a wholesale
replacement.** Nothing in the contract requires diffability.

### 1.5 Savepoints exist (relevant to partial rollback of an index write)

`TransactionalLayerMaintainer.java:428` `openSavepoint()`, `:445` `commitSavepoint(Savepoint)`, `:478`
`rollbackSavepoint(Savepoint)`, backed by `:667` `private final LongObjectHashMap<Object> mementos = …`. Layers are
snapshotted and restored via `:522 restoreLayer(Object, Object)`. **INFERENCE:** a fulltext structure whose diff
layer is an opaque blob must still support memento snapshot/restore to participate in savepoint rollback — see
`memory/Snapshotable.java` for the contract.

---

## 2. Index taxonomy

### 2.1 `EntityIndexType` — the forest keys (verbatim, `index/EntityIndexType.java:41-100`)

```java
public enum EntityIndexType {
	GLOBAL,
	REFERENCED_ENTITY_TYPE,
	REFERENCED_ENTITY,
	@Deprecated(since = "2024.12", forRemoval = true)
	REFERENCED_HIERARCHY_NODE,
	REFERENCED_GROUP_ENTITY_TYPE,
	REFERENCED_GROUP_ENTITY
}
```

`GLOBAL` is described at `:43-45` as *"the main index with all record ids of particular `Entity#getType()`… can be
compared to SQL DB full-scan"*.

Index key: `index/EntityIndexKey.java:49-53` — `record EntityIndexKey(@Nonnull EntityIndexType type, @Nonnull Scope
scope, @Nullable Serializable discriminator)`. Note **`Scope`** is part of the key (LIVE / ARCHIVED).

A second, orthogonal enum names sub-index kinds — `index/IndexType.java:33-72`: `ENTITY_INDEX, REFERENCE_INDEX,
HIERARCHY_INDEX, FACET_INDEX, ATTRIBUTE_INDEX, ATTRIBUTE_UNIQUE_INDEX, ATTRIBUTE_FILTER_INDEX,
ATTRIBUTE_SORT_INDEX, PRICE_INDEX`.

### 2.2 `EntityIndex` — the container

`index/EntityIndex.java:107-212`:

```java
public abstract class EntityIndex implements
	Index<EntityIndexKey>, AttributeIndexEditorContract, PriceIndexContract, Versioned, IndexDataStructure
	…
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();      // :114
	protected final AttributeIndex attributeIndex;                                     // :122
	protected final TransactionalBoolean dirty;                                        // :131
	protected final TransactionalBitmap entityIds;                                     // :135
	protected final TransactionalMap<Locale, TransactionalBitmap> entityIdsByLanguage; // :139
	@Getter protected final EntityIndexKey indexKey;                                   // :143
	protected final FacetIndex facetIndex;                                             // :149
	protected final HierarchyIndex hierarchyIndex;                                     // :155
	@Getter protected final int primaryKey;                                            // :159
	…
	private final List<IndexComponent> components = new ArrayList<>(8);                // :212
```

`components` at `:212` is **the registration list a new sub-index would join** (see §6.1).

Subtype specialization:

- `GlobalEntityIndex` — `PriceSuperIndex priceIndex` (full prices) + `EntityAttributeIndex`.
  `GlobalEntityIndex.java:159-160`, ctor `:231`.
- `AbstractReducedEntityIndex` — `PriceRefIndex priceIndex` (pointer-only) + `ReferenceAttributeIndex`.
  `AbstractReducedEntityIndex.java:101-102`, ctor `:148`.
- `ReducedEntityIndex` — **adds no fields**; method overrides only. `ReducedEntityIndex.java:67`.
- `ReferencedTypeEntityIndex` — `VoidPriceIndex.INSTANCE` (no prices at all) plus cardinality and
  histogram maps. `ReferencedTypeEntityIndex.java:170-192`.
- `ReducedGroupEntityIndex` — `PersistentTransactionalMap<Integer,Integer> pkCardinalities` plus
  per-group bitmaps. `ReducedGroupEntityIndex.java:96-124`.
- `CatalogIndex` — **only** `TransactionalMap<AttributeKey, GlobalUniqueIndex> uniqueIndex`.
  `CatalogIndex.java:72-94`.

### 2.3 `AttributeIndex` — and the shared-value-tree architecture

`index/attribute/AttributeIndex.java:114-254` (field declarations, verbatim line numbers):

```java
	@Nonnull private final PersistentTransactionalProducerMap<AttributeIndexKey, UniqueIndex> uniqueIndex;       // :144
	@Nonnull private final TransactionalMap<AttributeIndexKey, FilterIndex> filterIndex;                         // :166
	@Nonnull private final TransactionalMap<AttributeIndexKey, UniqueIndex> uniqueViewIndex;                     // :183
	@Nonnull private final PersistentTransactionalProducerMap<AttributeIndexKey, SortIndex> sortIndex;           // :188
	@Nonnull private final PersistentTransactionalProducerMap<AttributeIndexKey, ChainIndex> chainIndex;         // :193
	@Nonnull private final PersistentTransactionalProducerMap<AttributeIndexKey, InvertedIndex> sharedValueIndex; //:201
	@Nonnull private final PersistentTransactionalProducerMap<AttributeIndexKey, RangeIndex> sharedRangeIndex;   // :208
```

**Key architectural fact** (`AttributeIndex.java:194-208`, javadoc): `sharedValueIndex` is the *"OWNED shared
comparator-ordered value→ValueToRecord tree, one per single FILTERABLE attribute key… The `FilterIndex` is a
non-producing view over this tree; a both-flagged `SortIndex` reads its cardinality from it."*

So filter / sort / (foldable) unique **share one `InvertedIndex` per attribute key** rather than keeping three
copies. `FilterIndexView.java:53` and `UniqueIndexView.java:58-68` and `SortIndexView.java:71-79` hold essentially
no own state — the view/fold design exists precisely to avoid duplication.

Every map is keyed by `AttributeIndexKey` —
`evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/storageParts/index/AttributeIndexKey.java:40-43`:

```java
public record AttributeIndexKey(
	@Nullable String referenceName,
	@Nonnull String attributeName,
	@Nullable Locale locale
```

**⇒ the natural sharding grain in evitaDB is `(entityIndex, referenceName, attributeName, locale, scope)`.**

### 2.4 Backing structures, per index kind

- `FilterIndex` — `InvertedIndex invertedIndex` plus an optional `RangeIndex rangeIndex`.
  `FilterIndex.java:148`, `:156`.
- `SortIndex` — `TransactionalUnorderedIntArray sortedRecords` plus an `InvertedIndex`, owned or shared.
  `SortIndex.java:122`; `OwnerSortIndex.java:98`; `SortIndexView.java:79`.
- `ChainIndex` — `TransactionalUnorderedIntArray elements`, three hash maps, a `PageStreamRegistry`.
  `ChainIndex.java:137-183`.
- `OwnerUniqueIndex` — `IntRecordBucketTree tree` plus `TransactionalBitmap recordIds`.
  `OwnerUniqueIndex.java:111`, `:121`.
- `GlobalUniqueIndex` — `LongPayloadBucketTree tree` holding a packed `EntityWithTypeTuple`.
  `GlobalUniqueIndex.java:155`, javadoc `:150-151`.
- `RangeIndex` — `TransactionalLongBPlusTree<TransactionalRangePoint> ranges`. `RangeIndex.java:150`.
- `HierarchyIndex` — `TransactionalMap<Integer,HierarchyNode> itemIndex` plus a level index and orphans.
  `HierarchyIndex.java:118-136`.
- `FacetIndex` — four levels of nested transactional maps down to a `TransactionalBitmap`.
  `FacetIndex.java:78-94` down to `FacetIdIndex.java:47-56`.
- price leaf — `TransactionalElementBPlusTree<PriceRecordContract> priceRecords` plus a
  `RangeIndex validityIndex`. `AbstractPriceListAndCurrencyPriceIndex.java:107`, `:115`.
- `ReferenceTypeCardinalityIndex` — `LongPayloadBucketTree cardinalities`.
  `ReferenceTypeCardinalityIndex.java:132`.

The shared engine is `index/bPlusTree/TransactionalBucketBPlusTree.java:105-187`, which implements **both**
`IntRecordBucketTree` and `LongPayloadBucketTree`. Its leaves are **columnar (structure-of-arrays)**, with the key
column chosen by `ValueColumnFactory.forKey` (`:159-168`): primitive `LongValueColumn` for integral/temporal keys,
`FrontCodedStringColumn` for `String`, boxed otherwise.

`TransactionalUnorderedIntArray` (used by both `SortIndex` and `ChainIndex`) is the heaviest — it is *itself* two
trees: `index/array/TransactionalUnorderedIntArray.java:91` `UnorderedLookupTree positionTree` and `:95`
`TransactionalIntToLongBPlusTree valueIndex`.

**INFERENCE:** a filterable + sortable + unique String attribute therefore carries one shared `InvertedIndex`, plus
a `SortIndex` that is itself three structures, plus possibly a standalone unique tree. Per-attribute structure count
is the real memory multiplier, not per-structure overhead.

### 2.5 `InvertedIndex` — the only value→record-set primitive, and its blocking invariant

`index/invertedIndex/InvertedIndex.java:112-215`:

```java
	private static final int VALUE_BLOCK_SIZE = 256;   // :130
	private final IntRecordBucketTree buckets;         // :175
	@Nonnull private final Function<Object, Serializable> normalizer;  // :179
	@Nonnull @Getter private final Comparator comparator;              // :183
	@Nonnull @Getter private final PageStreamRegistry pageStreamRegistry; // :215
```

Block-size rationale, `:119-129`: *"Benchmarking (`InvertedIndexBlockSizeBenchmark`…) puts the knee at `256` —
versus the tree default `64` it cuts bounded-range and full-sweep latency by ~25% at scale… It is a runtime-only
parameter — it does not affect the persisted form, which is rebuilt into the tree on load."*

Class javadoc `:73-96` describes it as buckets ordered min→max, each with a bitmap; single-record buckets keep their
lone id in a primitive `int` column (no bitmap object) and promote to a `TransactionalBitmap` on a second distinct
id; *"the tree never stores a per-bucket `ValueToRecord` object."*

**THE BLOCKING INVARIANT** — `InvertedIndex.java:94-96`:

> Histogram MUST NOT contain same record id in multiple buckets.

A term→postings index violates this by construction: one document contains many terms. **`InvertedIndex` cannot be
reused verbatim as a postings list.** (FACT that the invariant is stated; INFERENCE that it blocks reuse — but the
inference is direct.)

### 2.6 Bitmaps

`index/bitmap/`: `Bitmap` (`Bitmap.java:46`) is the universal record-set contract.
Implementations: `BaseBitmap.java:44-48` (non-transactional, `PersistentRoaringBitmap` + memoized cardinality),
`TransactionalBitmap.java:56-64` (MVCC, `TransactionalLayerProducer<BitmapChanges, Bitmap>`),
`ArrayBitmap.java:40-43` (`CompositeIntArray`, no removals), `SingleRecordBitmap.java:45-51` (one bare `int` —
*"the leanest possible bitmap representation"*, `:33-34`), `EmptyBitmap.java:41-43` (singleton).
Diff layer: `BitmapChanges.java:41-58` — three `PersistentRoaringBitmap`s (original / insertions / removals) plus a
memoized merge, **per dirty bitmap per transaction**.

`PersistentRoaringBitmap` is vendored at
`evita_roaring_bitmap/src/main/java/io/evitadb/roaringbitmap/PersistentRoaringBitmap.java`.

### 2.7 Memory-hunger: what is measured and what is not

**FACT (negative finding, three independent checks by the taxonomy sweep):**
- No `estimateSize()` / `getSizeInBytes()` on any `io.evitadb.index.*` class. The only hit is a per-key constant:
  `index/price/model/PriceIndexKey.java:44` `public static final int MEMORY_SIZE = …`.
- No test asserts memory numbers (`GraphLayout|totalSize()` over `evita_test/**/*Test.java` → empty).
- JOL is a dependency of `evita_performance_tests` only — `evita_test/evita_performance_tests/pom.xml:151-152`.

So **there is no runtime memory accounting for indexes at all.** The only quantified numbers are the two committed
spike reports in §0.1/§0.2, both offline JOL deltas.

`evita_common/src/main/java/io/evitadb/utils/MemoryMeasuringConstants.java:41-60` is an *interface* of hand-rolled
64-bit estimates (`OBJECT_HEADER_SIZE = 16`, `REFERENCE_SIZE = 8`, …) consumed by storage parts and data types,
**not** by index classes.

The only "≈ N KiB per persisted page" statement in the codebase is
`index/array/UnorderedLookupTree.java:93-99`: *"1024 records ≈ 4 KiB (SSD-page-aligned) per leaf page."*

---

## 3. String handling today

### 3.1 The entire string-search surface

Three pattern constraints, all extending
`evita_query/src/main/java/io/evitadb/api/query/filter/AbstractAttributeFilterStringSearchConstraintLeaf.java:118`,
whose javadoc `:63-67` states:

> **Case Sensitivity and Encoding** — All string search constraints are **case-sensitive** and perform comparisons
> using UTF-8 encoding (Java's native string encoding).

| Constraint | Semantics per javadoc |
|---|---|
| `AttributeContains` (`AttributeContains.java:38-42`) | identical to `String#contains`, case-sensitive |
| `AttributeStartsWith` (`AttributeStartsWith.java:40`) | identical to `String#startsWith`, case-sensitive |
| `AttributeEndsWith` (`AttributeEndsWith.java:40`) | identical to `String#endsWith`, case-sensitive |
| `AttributeEquals` (`AttributeEquals.java:44-45`) | case-sensitive; collation rules for localized attributes |
| `AttributeInSet` (`AttributeInSet.java:41-48`) | OR of `AttributeEquals` |

Type restriction is enforced at runtime —
`evita_engine/.../filter/translator/attribute/AbstractAttributeStringSearchTranslator.java:93-96` asserts
`String.class.equals(attributeDefinition.getPlainType())`.

Array attributes get existential ("ANY element") semantics — `AttributeContains.java:64-65`, implemented via
`anyMatch` at `AbstractAttributeStringSearchTranslator.java:109-122`.

**The codebase already documents its own limitation** —
`AbstractAttributeFilterStringSearchConstraintLeaf.java:88-94`:

> **Performance Considerations** — String search constraints (especially `contains`) may have performance
> implications on large datasets as they typically require full attribute value scanning rather than index lookups.
> For high-performance text search: — Use prefix matching (`startsWith`) when possible — it can leverage sorted
> indexes — Consider dedicated full-text search indexes for complex text queries

**What does NOT exist (FACT, negative finding).** A sweep of `evita_query/src/main/java/` for
`relevance|fulltext|fuzzy|levenshtein|similarity|score|ranking` returns exactly two files, both incidental prose
(`AttributeGreaterThan.java:73` uses "Score filtering" as a usage example; `QueryConstraints.java:1844` describes
`attributeSetExact` as *"useful for preserving external relevance or custom sort orders"* — i.e. relevance computed
**outside** evitaDB). A sweep of `evita_query/`, `evita_engine/`, `evita_api/` for `fulltext|full-text` returns the
one recommendation quoted above.

**There is no scoring channel anywhere in the query model.** The 24 order constraints
(`evita_query/.../order/`) offer: attribute-natural (collated), price, random, reference/entity property, segments,
and caller-supplied explicit sequences (`AttributeSetExact`, `EntityPrimaryKeyExact`). Nothing produces or consumes
a per-record relevance number.

### 3.2 How `attributeContains` is actually evaluated — the central fact

Translator is a three-line constructor —
`evita_engine/.../filter/translator/attribute/AttributeContainsTranslator.java:44-50`:

```java
	public AttributeContainsTranslator() {
		super(
			"contains",
			FilterIndex::getRecordsWhoseValuesContains,
			createPredicate()
		);
	}
```

And `index/attribute/FilterIndex.java:591-596` (spot-checked by me, verbatim):

```java
	@Nonnull
	public Formula getRecordsWhoseValuesContains(@Nonnull String text) {
		/* TOBEDONE JNO naive and slow - use RadixTree */
		final String normalizedText = (String) this.normalizer.apply(text);
		return this.invertedIndex.getRecordsMatchingFormula(value -> ((String) value).contains(normalizedText));
	}
```

`endsWith` is the same shape (`FilterIndex.java:578-583`). `startsWith` is the **only** structured one
(`FilterIndex.java:557-570`, spot-checked verbatim):

```java
	@Nonnull
	public Formula getRecordsWhoseValuesStartWith(@Nonnull String prefix) {
		/* TOBEDONE JNO naive and slow - use RadixTree */
		final String normalizedPrefix = (String) this.normalizer.apply(prefix);
		if (this.comparator != DEFAULT_COMPARATOR) {
			// collation ordering does not guarantee a contiguous prefix run - scan every bucket without early break
			return this.invertedIndex.getRecordsMatchingFormula(value -> ((String) value).startsWith(normalizedPrefix));
		}
		// natural codepoint order: matches form one contiguous run from the anchor, so the index walks the run off its
		// cursor and early-breaks at the first miss (no flyweight / iterator / per-bucket node allocation)
		return this.invertedIndex.getRecordsStartingFromWhile(
			normalizedPrefix, value -> ((String) value).startsWith(normalizedPrefix)
		);
	}
```

The scan itself, `index/invertedIndex/InvertedIndex.java:812-826` (read by me, verbatim):

```java
	@Nonnull
	public Formula getRecordsMatchingFormula(@Nonnull Predicate<Serializable> valuePredicate) {
		final List<Bitmap> bitmaps = new ArrayList<>(64);
		final LeafVersionAccumulator leafVersions = new LeafVersionAccumulator();
		final BucketCursor cursor = this.buckets.cursor();
		while (cursor.next()) {
			if (valuePredicate.test((Serializable) cursor.value())) {
				// record the leaf of each matched bucket so the folded formula keys on the leaves it actually read
				leafVersions.accept(cursor.currentLeafId());
				// read the record set straight off the cursor - no ValueToRecord flyweight is materialized
				bitmaps.add(cursor.records());
			}
		}
		return toSortedOrFormula(bitmaps, leafVersions.toTokenSet());
	}
```

`this.buckets.cursor()` with no argument is `TransactionalBucketBPlusTree.java:1163-1167` →
`new ForwardBucketCursor<>(createLeftmostCursor())`, i.e. **leftmost leaf to exhaustion, no early exit.**

The prefix path really is a B+ tree descent: `TransactionalBucketBPlusTree.java:1176-1180` (`cursor(K value)`) →
`:2355-2367` `createCursor(K key)` builds a root-to-leaf path, and `ForwardBucketCursor`'s constructor
(`:5293-5319`) positions inside the leaf with `getKeyColumn().findKeyPosition(...)` — a binary search.

**Cost statement (FACT for the mechanism; INFERENCE for the sizing).** The loop iterates *buckets* = distinct
indexed values in that `(entityIndex, referenceName, attributeName, locale, scope)` index. So `contains` costs
D × `String.contains` plus an OR-fold of matched bitmaps, where D is the distinct-value count. For a high-cardinality
free-text attribute (descriptions), D ≈ entity count, making it effectively a per-entity substring scan. For a
low-cardinality attribute (brand), D is small and it is cheap.

**Localized attributes lose even the prefix fast path.** `FilterIndex.java:561` is a *reference-identity* check
against `FilterIndex.java:109` `static final Comparator<Comparable> DEFAULT_COMPARATOR = Comparator.naturalOrder();`,
and the sole producer is `FilterIndex.java:300-309`:

```java
	public static Comparator<? extends Comparable> getComparator(
		@Nonnull AttributeIndexKey attributeIndexKey, @Nonnull Class<?> attributeType) {
		final Locale locale = attributeIndexKey.locale();
		if (String.class.isAssignableFrom(attributeType) && locale != null) {
			return new LocalizedStringComparator(locale);
		} else {
			return DEFAULT_COMPARATOR;
		}
	}
```

A localized String attribute gets a **fresh** `LocalizedStringComparator`, so the identity check is true and
`attributeStartsWith` degrades to the same full scan as `contains`. **The prefix optimization only applies to
non-localized String attributes** (`code`, `ean`, `url`).

### 3.3 Normalization: NFD, applied at index time AND query time

`FilterIndex.java:277-284`:

```java
		} else if (String.class.isAssignableFrom(attributeType)) {
			// String keys are normalized to Unicode NFD so the shared value tree holds one canonical form across the
			// unique / filter / sort role-views. …
			return text -> text == null
				? null
				: Normalizer.normalize(String.valueOf(text), Normalizer.Form.NFD);
```

Matched verbatim on the sort side at `SortIndex.java:429-440` (`createNormalizerFor`), applied on every write at
`SortIndex.java:532`, `:572`, `:618`. The query term is normalized through the same function
(`FilterIndex.java:560`, `:581`, `:594`), and the prefetch (non-index) path mirrors it at
`AbstractAttributeStringSearchTranslator.java:137-141`.

**NFD only.** No case folding, no accent stripping, no tokenization, no stemming, no stopwords. NFD gives canonical
equivalence (precomposed `é` ≡ decomposed `e`+U+0301) but `é` ≠ `e` and `Café` ≠ `café`.

### 3.4 Collation is compare-time and cached, never persisted

`evita_common/src/main/java/io/evitadb/comparator/LocalizedStringComparator.java:74-77` builds a `Collator` plus a
`CollationKeyCache`; `:90-102` compares via `Arrays.compareUnsigned(cache.keyFor(o1), cache.keyFor(o2))` when the
cache is live, else `collator.compare`.

**Collation keys are never materialized into the index** — the tree stores NFD `String`s and re-derives ordering
through the comparator on every descent. **INFERENCE (direct):** this is *why* localized `startsWith` cannot use the
prefix seek — the stored byte order is codepoint order, not collation order.

The cache is a JVM system property, **not** an `evita_api` configuration option (a sweep of
`evita_api/src/main/java/io/evitadb/api/configuration/` for `collationKeyCache|CollationKeyCache` returns zero hits):
`evita_common/src/main/java/io/evitadb/comparator/CollationKeyCache.java:89-92`
`static final String SIZE_PROPERTY = "evita.collationKeyCache.size";`. Default is heap-derived, not a fixed
constant — `:203-213` `defaultSize()` = `clamp(highestOneBit((maxHeap/50)/256), 8192, 1048576)` slots **per locale**,
from constants at `:97-126` (`MAX_SIZE = 1 << 20`, `MIN_DEFAULT_SIZE = 8192`, `DEFAULT_HEAP_SHARE_DIVISOR = 50`,
`ESTIMATED_ENTRY_SIZE = 256`, `COLLATOR_STRIPES = 16`). `0` disables it (`:78-79`). Per-locale instances live in
`:116` `private static final ConcurrentHashMap<Locale, CollationKeyCache> INSTANCES = …`; swept by
`evita_engine/src/main/java/io/evitadb/core/cache/CollationKeyCacheSweeper.java:71`.

`RuleBasedCollator.getCollationKey` is `synchronized`, hence the 16-stripe collator pool at
`CollationKeyCache.java:263-270`.

### 3.5 Locale handling

**Yes, a physically separate index per locale.** `AttributeIndexKey` carries `@Nullable Locale locale`
(`AttributeIndexKey.java:43`), and every `AttributeIndex` map is keyed by it (§2.3). A localized attribute `name`
across `cs`, `en`, `de` produces three independent `FilterIndex`/`InvertedIndex` trees.

`entityLocaleEquals` narrows via a precomputed bitmap, not a pre-filter of the scan:
`index/EntityIndex.java:139` `protected final TransactionalMap<Locale, TransactionalBitmap> entityIdsByLanguage;`
read at `:749-758` `getRecordsWithLanguageFormula(Locale)` returning a `LocaleFormula`, called from
`core/query/filter/translator/entity/EntityLocaleEqualsTranslator.java:78` and `:84`.

**INFERENCE:** the locale bitmap AND-folds into the *result*; the `contains` scan still visits every bucket of the
locale-specific tree.

Locale membership is maintained incrementally and schema-validated on write —
`EntityIndex.java:382-398 upsertLanguage(...)`, inverse at `:405`, enumeration at `:763-766`.

### 3.6 String storage in leaves is already the Lucene term-dictionary layout

`index/bPlusTree/FrontCodedStringColumn.java:115` `final class FrontCodedStringColumn<M extends Comparable<M>>
implements ValueColumn<M>`, javadoc `:40-51`:

```
per entry:  varint(sharedPrefixLength)  varint(suffixLength)  suffixBytes(UTF-8)
```

> Every `RESTART_INTERVAL`-th entry is a *restart point* … This is the Lucene term-dictionary layout, measured at
> ~10 B/bucket vs ~96–117 B for the boxed column on real high-cardinality string attributes (codes / EANs / URLs).

Constant, `:116-121`: `private static final int RESTART_INTERVAL = 16;`

Selected for **every** String attribute, localized or not (`:110`). A BMP-safe, natural-order, zero-allocation raw
byte-compare fast path exists (`:94-108`); **a localized comparator always falls through to the allocating `String`
path** (`:107-108`).

---

## 4. Query execution

### 4.1 The pipeline

`core/query/QueryPlanner.java:141-187` `planQuery(QueryPlanningContext)`:

1. `selectIndexes(context)` (`:145`) — `IndexSelectionVisitor` walks the `FilterBy` and proposes candidate
   `TargetIndexes` (`:291-294`). Empty ⇒ immediate empty plan (`:148-150`).
2. `createFilterFormula(context, targetIndexes)` (`:154-156`) — **one formula tree per candidate index**.
3. `preferredPlan = queryPlanBuilders.get(0)` (`:162`) — cheapest first.
4. `createSorter` / `createSlicer` / `createExtraResultProducers` for the winner only (`:174-178`), unless a debug
   mode asks for all.

Cost-based selection is inline, `QueryPlanner.java:369-373`:

```java
						if (result.isEmpty() || adeptFormula.getEstimatedCost() < result.get(0).getEstimatedCost()) {
							result.addFirst(queryPlanBuilder);
						} else {
							result.addLast(queryPlanBuilder);
						}
```

Documented at `:323-329`: the list is *"kept **sorted by estimated cost** as it is built"*, and *"unless
`DebugMode#VERIFY_ALTERNATIVE_INDEX_RESULTS` is on, only that first element is returned; the remaining candidates
are constructed but thrown away, since building them is cheap and comparing their costs is the only way to know
which one wins."*

**This is where latency predictability comes from: candidate plans are *built* (cheap, no computation) and only the
winner is *computed*.** `Formula.java:76-96` makes the rule explicit — `getMemoizedResult()` exists so a tree can be
*described* without being *executed*, because *"anything that renders a rejected alternative by calling `compute()`
would make the query do work it had deliberately decided to skip — telemetry would stop observing the query and
start changing it."*

Post-passes on the formula tree: `core/query/filter/FormulaOptimizer.java` and `FormulaDeduplicator.java`, applied
at `QueryPlanner.java:359-364` via `filterByVisitor.getFormula(new FormulaOptimizer(), prefetchFormulaVisitor)`.

### 4.2 The formula algebra

`core/query/algebra/Formula.java:52` `public interface Formula extends TransactionalDataRelatedStructure,
PrettyPrintable` — `compute()` returns a `Bitmap` (`:72-73`), results memoized. Set algebra is RoaringBitmap-backed
via the `Bitmap` implementations of §2.6 (`AndFormula`/`OrFormula`/`NotFormula` in `algebra/base/`).

Cost model: `getEstimatedCost()` / `getCost()` / `getOperationCost()` / `getCostToPerformanceRatio()` on
`core/query/response/TransactionalDataRelatedStructure.java:106-126`. `getOperationCost()` is documented `:116-118`
as *"product of real measurement of this operation compared to simple no operation formula"*.

### 4.3 Formula caching — what is hashed, and how invalidation works

**Two independent hashes**, both on `TransactionalDataRelatedStructure`:

- `getHash()` (`:82`) — identity of the *computation*. Contract at `:69-81`: same logical contents ⇒ same hash;
  `and(...)` vs `or(...)` ⇒ different hashes.
- `getTransactionalIdHash()` (`:91`) — identity of the *data read*. Contract at `:84-90`: *"computed from distinct,
  sorted transactional ids of all transactional data sources (bitmaps / indexes)."*

Construction, `core/query/algebra/AbstractFormula.java:103-123`:

```java
	protected void initFields(@Nonnull Formula... innerFormulas) {
		this.innerFormulas = innerFormulas;

		// build hash array: [classId, innerFormulaHashes..., additionalHash]
		final int formulaCount = innerFormulas.length;
		final long[] hashArray = new long[formulaCount + 2];
		hashArray[0] = getClassId();
		for (int i = 0; i < formulaCount; i++) {
			hashArray[i + 1] = innerFormulas[i].getHash();
		}
		hashArray[formulaCount + 1] = includeAdditionalHash(HASH_FUNCTION);
		if (!isFormulaOrderSignificant()) {
			// sort only the inner formula hash portion [1, formulaCount+1)
			Arrays.sort(hashArray, 1, formulaCount + 1);
		}
		this.hash = HASH_FUNCTION.hashLongs(hashArray);

		this.transactionalIds = sortAndDeduplicateLongArray(gatherBitmapIdsInternal());
		this.transactionalIdHash = HASH_FUNCTION.hashLongs(this.transactionalIds);
		this.estimatedCost = getEstimatedCostInternal();
	}
```

So **every new formula type owes exactly two methods**: `AbstractFormula.java:311`
`protected abstract long includeAdditionalHash(@Nonnull LongHashFunction hashFunction);` and `:320`
`protected abstract long getClassId();` (*"must not change in time for the same class… must not be inherited"*).

Hash function: `TransactionalDataRelatedStructure.java:53`
`LongHashFunction HASH_FUNCTION = CacheSupervisor.createHashFunction();` (Zero-Allocation-Hashing,
`net.openhft.hashing.LongHashFunction`).

Cache lookup and invalidation, `core/cache/CacheEden.java:195-213`: the record is fetched by `recordHash`
(= `getHash()`), then

```java
					if (cachedRecord.getTransactionalIdHash() == computationalObject.getTransactionalIdHash()) {
```

on mismatch it is a **miss** (`:209-212`), not a stale hit. **Invalidation is therefore implicit: the cached entry
simply stops matching once the underlying transactional ids change.** There is no invalidation broadcast.

**The staleness-granularity mechanism is the key one for a fulltext design.** Formula cache keys must not be so
coarse that any write to the collection invalidates everything. `InvertedIndex` solves this with
**leaf-version tokens** — `InvertedIndex.java:1280-1328`:

```java
	private final class LeafVersionAccumulator {
		private final long[] leafIds = new long[TransactionalDataRelatedStructure.EXCESSIVE_HIGH_CARDINALITY];
		…
		void accept(long leafId) {
			if (this.overflow || (this.haveLast && leafId == this.lastLeafId)) {
				return;
			}
			…
		}

		@Nonnull
		long[] toTokenSet() {
			if (this.overflow || this.leafCount == 0) {
				return new long[]{getId()};
			}
			final long[] tokenSet = Arrays.copyOf(this.leafIds, this.leafCount);
			Arrays.sort(tokenSet);
			return tokenSet;
		}
	}
```

with the cap at `TransactionalDataRelatedStructure.java:49` `int EXCESSIVE_HIGH_CARDINALITY = 100;`, whose javadoc
`:44-48` explains the tradeoff: *"Storing excessive amount of long ids would allocate too much memory in `CacheEden`
that is better invalidate less precisely on each index change than to allocate a lot of memory for precise
invalidation."*

`InvertedIndex.java:1268-1272`: *"The token set is what makes a cached formula over an untouched value range survive
writes to other pages."*

Cacheability plumbing: `core/query/algebra/CacheableFormula.java:42-64` (three methods —
`toSerializableFormula(long, LongHashFunction)`, `getSerializableFormulaSizeEstimate()`,
`getCloneWithComputationCallback(...)`), applied by `core/cache/FormulaCacheVisitor.java:47` (a `FormulaCloner` that
swaps costly formulas for cached results or tracked copies, `:39-42`). A subtree containing a `NonCacheableFormula`
is propagated up and disables caching for its ancestors (`FormulaCacheVisitor.java:106-115`, `:92`).

The cache is a two-stage anteroom/eden design: `core/cache/CacheAnteroom.java`, `core/cache/CacheEden.java` (eviction
by cost-to-performance ratio with a threshold sweep at `CacheEden.java:285-377`), supervised by
`CacheSupervisor.java` / `HeapMemoryCacheSupervisor.java`, with `NoCacheSupervisor.java` as the off switch.

### 4.4 Sorting, paging, prefetch

`core/query/QueryPlan.java:178-210` `sortAndSliceResult(...)`:

```java
			for (Sorter sorter : sorters) {
				sortingContext = sorter.sortAndSlice(sortingContext, result, null);
				if (sortingContext.peak() == result.length) {
					break;
				}
			}
			// append the rest of the records if not all are sorted
			if (sortingContext.peak() < result.length) {
				NoSorter.INSTANCE.sortAndSlice(sortingContext, result, null);
			}
```

Documented at `:165-169`: sorters are a **chain**, each appending and reporting fill level; *"Sorters are allowed to
leave records they cannot order (for instance entities missing the sorted attribute), so whatever remains unfilled
is topped up by `NoSorter#INSTANCE` in the natural primary key order — this is why a partially applicable ordering
still yields a full page rather than a short one."*

Top-K is **not** a heap over scores; it is: compute the full filtering bitmap, then have the sorter chain fill
exactly `[offset, offset+limit)` (`QueryPlan.java:186-197`), short-circuiting when the offset exceeds the total
(`:186-187`). `QueryPlan.java:130` — *"Never empty; a plan with no ordering carries a single `NoSorter#INSTANCE`."*

Prefetch: `QueryPlan.java:119-123` `private final PrefetchOrder prefetcher;`, decided by
`core/query/PrefetchStrategyResolver.java` and `core/query/algebra/prefetch/PrefetchFormulaVisitor` (constructed at
`QueryPlanner.java:357`). Executed at `QueryPlan.java:265-268`
(`executionContext.prefetchEntities(this.prefetcher)`), with the whole plan branching on
`this.prefetcher.isPrefetchedEntitiesSuitableForFiltering()` (`:255`). When prefetch wins, filtering runs over
**entity bodies** rather than indexes — which is why every string translator carries a second predicate path
(§3.3).

---

## 5. Index mutation & persistence hookup

### 5.1 Dirty marking

Every index structure implements `index/IndexDataStructure.java:31-39`, which has **exactly one method**:

```java
public interface IndexDataStructure {

	/**
	 * Method resets the dirty flag for current index. It should not be used except for tests. Otherwise, some updates
	 * may get omitted in subsequent flushes to persistent storage and get lost.
	 */
	void resetDirty();

}
```

The dirty flag itself is `index/bool/TransactionalBoolean.java:52-55` (`private boolean value;` + `BooleanChanges`),
held per structure (e.g. `FilterIndex`'s owner variant at `OwnerFilterIndex.java:56-66`, `InvertedIndex.java:168`,
`ChainIndex.java:159`).

**Caveat (FACT):** `CatalogIndex.java:123` javadoc states *"no production code path ever calls `resetDirty()`: the
flag is a latch for the lifetime of the …"*. **INFERENCE:** for paged indexes the real per-commit change signal is
the per-leaf `PagedLeafHandle.isDirty()` / `clearDirty()` consumed inside `PageStreamRegistry.collectChangedPages`,
not `IndexDataStructure.resetDirty()`.

### 5.2 Flush: `Index.getModifiedStorageParts` → components → parts

`index/Index.java:38-64`:

```java
public interface Index<T extends IndexKey> {
	@Nonnull
	T getIndexKey();
	void getModifiedStorageParts(@Nonnull TrappedChanges trappedChanges);
	default void notifyFlushed() { … }
}
```

`notifyFlushed()` (`:53-63`) is *"the hook where an index may advance its change-detection baseline to the
just-persisted state… so `getModifiedStorageParts` can stay a pure, idempotent read."*

`index/EntityIndex.java:799-805` walks components uniformly:

```java
	public final void getModifiedStorageParts(@Nonnull TrappedChanges trappedChanges) {
		final EntityIndexManifest manifest = new EntityIndexManifest();
		// walk every registered component in deterministic order — each emits its own dirty storage
		// parts and populates the manifest with the live key set it currently owns
		for (IndexComponent component : this.components) {
			component.collectModifiedStorageParts(this.primaryKey, manifest, trappedChanges);
		}
```

then re-emits the manifest only on structural change (`:827-834`), the id bitmaps on membership change (`:837-847`),
and `emitVanishedRootRemovals(...)` (`:854-857`).

`index/component/IndexComponent.java:53-115` (spot-checked by me) — **four methods, one with a default**:

```java
public interface IndexComponent {
	void collectModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull EntityIndexManifest manifest,
		@Nonnull TrappedChanges trappedChanges
	);
	void resetDirty();
	void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer);
	default void emitPersistedFootprintRemovals(
		int entityIndexPrimaryKey,
		@Nonnull TrappedChanges trappedChanges
	) {
		// no persisted leaf pages and no non-manifest root — nothing to reclaim
	}
}
```

Performance constraint stated at `:49-51`: *"Components must be allocation-free in the flush path: the parent index
calls `collectModifiedStorageParts` on every commit, so allocations here are amplified by the number of dirty
indexes in the catalog."*

Existing implementations (8): `AttributeIndexComponent`, `AttributeCardinalityIndexMapComponent`,
`GroupCardinalityComponent`, `HistogramIndexMapComponent`, `PriceIndexComponent`,
`ReferenceTypeCardinalityComponent`. Loaders mirror them under `index/component/loader/` (11 files, incl.
`IndexReloadPlan`, `LoadContext`, `LoadedComponentBundle`).

Drain and write: `core/buffer/TrappedChanges.java:103` `addChangeToStore(StoragePart)` / `:120`
`getTrappedChangesIterator()`; `core/buffer/DataStoreChanges.java:279-301` `popTrappedUpdates()` iterating dirty
indexes and calling `getModifiedStorageParts` + `notifyFlushed`; then
`evita_store/.../catalog/DefaultEntityCollectionPersistenceService.java:594-615` which routes each part to
`putStoragePart`, `removeStoragePart`, or the `DeferredRemovalStoragePart` resolution branch.

### 5.3 Granular paging: **one persisted record per B+ tree leaf**

The split rule, `index/invertedIndex/InvertedIndex.java:977-985`:

```java
	public boolean isPaged() {
		return this.buckets.isRootInternal();
	}
```

with the javadoc `:978-982` — *"Returns whether this index's bucket tree spans more than one leaf and is therefore
persisted in the granular `PAGED` shape (one record per leaf) rather than the inline `SINGLE` shape."*

And `index/attribute/FilterIndex.java:1126-1132`: a single-leaf tree emits the inline `SINGLE` root; a multi-leaf
tree emits *"one `FilterIndexLeafPagePart` per CHANGED leaf plus the fused `PAGED` root carrying each axis's
high-water and ordered live leaf-page list — re-emitted only when an axis's page list changed."*

**Part size is set by the in-memory leaf block size, not by a byte budget:**

- FilterIndex buckets and the OwnerSortIndex value tree — `InvertedIndex`, **256**.
  `index/invertedIndex/InvertedIndex.java:130`.
- FilterIndex range axis — `RangeIndex`, **512**. `index/range/RangeIndex.java:113`.
- Unique, both owner and global — `UniqueIndexBPlusTreeSupport`, **256**.
  `index/attribute/UniqueIndexBPlusTreeSupport.java:52`.
- ReferenceTypeCardinality — its own tree, **256**.
  `index/cardinality/ReferenceTypeCardinalityIndex.java:108`.
- ChainIndex elements — `UnorderedLookupTree`, **1024** (≈ 4 KiB).
  `index/array/UnorderedLookupTree.java:99`.
- Super price index — `TransactionalElementBPlusTree` defaults, **64**.
  `index/bPlusTree/TransactionalElementBPlusTree.java:88`.

**The paging skeleton is generic and reusable** — `index/page/` is three value-agnostic files:
`PageStreamRegistry.java:329-331 collectChangedPages(int, List<H>, PageBuilder<H,P>)` (javadoc `:309-317` calls it
*"the single shared skeleton behind every paged index's flush"*), `PageBuilder.java:42-52`, and
`PageEmission.java:56-62` (a record of `changedPages` / `orderedPageSequences` / `highWaterPageSequence` /
`freedPageSequences` / `pageListChanged`). Leaves implement `index/bPlusTree/PagedLeafHandle.java:36-74` — four
methods plus `int UNASSIGNED_PAGE_SEQUENCE = -1;`.

Page allocation is advance-only, ids never reused — `PageStreamRegistry.java:105-109`; change detection is a
per-leaf dirty flag, not a content hash — `:351-354`.

### 5.4 How big is one persisted part?

**There is no configured maximum part size (FACT).** The 2 MB figure is the *write window*, not a cap:
`evita_api/src/main/java/io/evitadb/api/configuration/StorageOptions.java:153`
`public static final int DEFAULT_OUTPUT_BUFFER_SIZE = 2_097_152; // 2MB`. An oversized part is split across chained
continuation records via `StorageRecord`'s `OnBufferOverflowHandler`
(`evita_store/evita_store_key_value/src/main/java/io/evitadb/store/offsetIndex/model/StorageRecord.java:906-914`
installs it; `:1139-1160` implements the continuation). The `KryoException("Active record exceeds buffer size…")`
branch at `ObservableOutput.java:673-676` is unreachable on the storage-part write path because the handler is
always installed.

Only explicit byte statement in the codebase: `index/array/UnorderedLookupTree.java:93-99` — *"1024 records ≈ 4 KiB
(SSD-page-aligned) per leaf page."*

Observability only (not a bound): `OffsetIndex.java:232-236` `maxRecordSizeBytes`, exposed at `:1172-1173`.

### 5.5 Persisted part inventory and its numbering

**Note: the packages are not where an older map would suggest.** StorageParts live in **`evita_engine`** at
`evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence/storageParts/`, serializers in
`evita_store/evita_store_server/src/main/java/io/evitadb/store/index/`.

`spi/store/catalog/persistence/storageParts/StoragePart.java:47-114` — **two abstract methods**:
`@Nullable Long getStoragePartPK()` and `long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor)`.

Uniqueness is `(class, PK)`, stated at `StoragePart.java:38-39`: *"Uniqueness within a single persistence file is
guaranteed by the combination of the concrete implementation class (which determines the record type discriminator)
and the value returned by `getStoragePartPK()`."* This is why `FacetIndexStoragePart`,
`GroupCardinalityIndexStoragePart` and `ReferenceTypeCardinalityIndexStoragePart` legally share the identical
`pack(entityIndexPK, cid(ReferenceNameKey))`.

PK packing is `evita_common/src/main/java/io/evitadb/utils/NumberUtils.java:259-261`:
`return (((long) numberA) << 32) | (numberB & 0xffffffffL);` — i.e. **a part PK is two ints**. For paged parts, one
half is consumed by `pageSequence`, so the whole sub-index identity must fit in the other `int`
(`AbstractLeafPagePart.java:83-85 computeUniquePartId(int streamId, int pageSequence)`).

That `int` comes from the `KeyCompressor`, described at `storageParts/index/LeafStreamKey.java:38-51` as *"a
bijective, restart-stable, transactionally-allocated dictionary persisted whole in the catalog header"* — so
`compressor.getId(leafStreamKey)` is guaranteed-unique and deterministic, **and the whole dictionary is loaded
eagerly with the catalog header.**

The registry,
`evita_store/evita_store_server/src/main/java/io/evitadb/store/index/service/IndexStoragePartRegistry.java:43-73`
(spot-checked by me, verbatim) — index family owns byte ids **20–46**, next free is **47** (catalog family starts at
50). Discovered by `ServiceLoader`: `evita_store/evita_store_server/src/main/java/module-info.java:42`
`provides io.evitadb.store.shared.service.StoragePartRegistry`
`with io.evitadb.store.index.service.IndexStoragePartRegistry;` (one source line, wrapped here),
consumed at `OffsetIndexRecordTypeRegistry.java:55-61`.

Kryo class ids are **positional and order-sensitive** — `IndexStoragePartConfigurer.java:59-65` starts at
`INDEX_BASE = 600` and increments `index++` per registration, asserting `index < 700` at `:293`. Two smoking guns:
`:133-134` `// skip index, it was used by removed AttributeCardinalityIndexSerializer` / `index++;` and, repeated at
`:190-196`, `:215`, `:224`, `:233`, `:242`, `:251`, `:260`, `:269`, `:277`, `:286`:
*"Appended last to keep the preceding registration ids stable."*

Backward-compatible readers attach by the *previous* `serialVersionUID`, e.g. `IndexStoragePartConfigurer.java:82-88`
`.addBackwardCompatibleSerializer(-4095785894036417656L, new UniqueIndexStoragePartSerializer_2025_5(...))`.
Brand-new types register with none.

Two Kryo assemblies exist and they **differ**: `DefaultCatalogPersistenceService.java:241-248` includes
`CatalogHeaderKryoConfigurer` + `SharedIndexStoragePartConfigurer`;
`DefaultEntityCollectionPersistenceService.java:173-180` **omits both**. Stream keys register in
`CatalogHeaderKryoConfigurer.java` (base 700, assert `< 800` at `:152`); compressor-free shared keys in
`SharedIndexStoragePartConfigurer.java` (base 800, assert `< 900` at `:65`).

---

## 6. Extension seams

### 6.1 Registering a new sub-index inside `EntityIndex`

The seam is `index/component/IndexComponent` (§5.2) plus the `components` list at `EntityIndex.java:212`. A new
index type implements four methods (one defaulted) and is added to that list in the `EntityIndex` constructor; a
matching `ComponentLoader` under `index/component/loader/` handles reload. `IndexComponent.java:45-47` states the
design intent directly: *"registers either itself or a thin adapter as an `IndexComponent`, and the parent index
walks the registered components in a single uniform loop."*

The generic index contracts are: `index/Index.java:38-64` (2 methods + 1 default),
`index/IndexDataStructure.java:31-39` (1 method), `index/IndexProvider.java`, and
`index/IndexMaintainer.java:42-71` — `IndexMaintainer` extends `IndexProvider` with `removeIndex(K)` and a throwing
`getIndexByPrimaryKey(int)` default, and is the seam for *"providing custom implementations to the logic that
creates new `EntityIndex` instances"* (`:32-35`).

### 6.2 How entity mutations reach indexes

`index/mutation/local/EntityIndexLocalMutationExecutor.java:636-654`:

```java
	@Override
	public void applyMutation(@Nonnull LocalMutation<?, ?> localMutation) {
		final GlobalEntityIndex globalIndex = (GlobalEntityIndex) getOrCreateIndex(
			new EntityIndexKey(EntityIndexType.GLOBAL, getScope())
		);
		dispatchViaRegistry(localMutation, globalIndex);
	}

	private <M extends LocalMutation<?, ?>> void dispatchViaRegistry(
		@Nonnull M localMutation,
		@Nonnull GlobalEntityIndex globalIndex
	) {
		final LocalMutationHandler<M> handler = LocalMutationHandlerRegistry.resolve(localMutation);
		handler.apply(localMutation, this, globalIndex);
	}
```

`index/mutation/local/handler/LocalMutationHandlerRegistry.java:42-79` — a class-init immutable map from concrete
`LocalMutation` subclass to a stateless singleton handler, with 16 registrations across the attribute, associated
data, parent, price, reference and scope families. Coverage is enforced:
`LocalMutationHandlerRegistry.java:38-40` — *"A new concrete mutation type added to `evita_api` must be paired with
a new entry here — `LocalMutationHandlerRegistryCoverageTest` enforces this contract via classpath scan."*
Unknown classes throw (`:96-102`).

Attribute handlers are thin — `UpsertAttributeMutationHandler.java:56-63` delegates entirely to
`AttributeMutationFanOut.apply(...)`, which fans the mutation across the global index and reduced indexes; the
concrete index writes happen in `index/mutation/local/AttributeIndexMutator.java`.

**The schema-flag routing is the fulltext hook point.** `AttributeIndexMutator.java:177-179` and `:320-321`:

```java
		if (attributeDefinition.isUniqueInScope(scope) || attributeDefinition.isFilterableInScope(
			scope) || attributeDefinition.isSortableInScope(scope)) {
```

These are the only three flags that cause an attribute value to be indexed at all. The contract is
`evita_api/src/main/java/io/evitadb/api/requestResponse/schema/AttributeSchemaContract.java` —
`isUniqueInScope(Scope)` at `:125`, `isFilterableInScope(Scope)` at `:249`, `isSortableInScope(Scope)` at `:294`,
plus `isLocalized()` at `:307`.

A second, separate registry exists for *index-level* (not entity-level) mutations:
`index/mutation/IndexMutationExecutorRegistry.java:45-95`, currently holding a single entry
(`ReevaluateExpressionMutation` → `ReevaluateExpressionExecutor`). Its javadoc `:36-38` states the extension
contract: *"Adding a new mutation type requires only: a new mutation record, a new stateless executor class, and one
entry in the map below."*

### 6.3 How a query constraint becomes a formula

`core/query/filter/FilterByVisitor.java:155` declares the registry and `:182-...` populates it:

```java
	// one source line at :155, wrapped here to fit the 120-column limit
	private static final Map<Class<? extends FilterConstraint>,
		FilteringConstraintTranslator<? extends FilterConstraint>> TRANSLATORS;
	…
		TRANSLATORS = createHashMap(64);
		TRANSLATORS.put(FilterBy.class, new FilterByTranslator());
		…
		TRANSLATORS.put(AttributeStartsWith.class, new AttributeStartsWithTranslator());
		TRANSLATORS.put(AttributeEndsWith.class, new AttributeEndsWithTranslator());
		TRANSLATORS.put(AttributeContains.class, new AttributeContainsTranslator());
```

**One `put` is the whole registration.** The translator contract is
`core/query/filter/translator/FilteringConstraintTranslator.java`; translators live under
`core/query/filter/translator/{attribute,behavioral,bool,entity,facet,hierarchy,histogram,price,reference}/`.

A translator reaches the index through `filterByVisitor.applyOnFilterIndexes(referenceSchema, attributeDefinition,
index -> …)` — `AbstractAttributeStringSearchTranslator.java:201-209` — and wraps the result in an
`AttributeFormula`. Note the repo has a documented recipe for this whole path: the `new-constraint` skill
(`.claude/skills/`) covers query model, EvitaQL grammar, engine translator, Kryo serializer, external APIs and
docs.

---

## Verdicts

The following are the concrete seams and constraints an embedded fulltext index would face. Each is stated in plain
prose because this section is the part most likely to be lifted into human-facing material.

### V1. A fulltext index can live inside `EntityIndex`, and it does not have to be diff-able

This is the discriminating question, and the answer is yes. The transactional layer treats an index's diff piece as
a completely opaque type parameter: `TransactionalLayerCreator.createLayer()` produces it, the maintainer stores it
in a `long`-keyed registry, and `createCopyWithMergedTransactionalMemory` consumes it
(`TransactionalLayerProducer.java:39-71`, `TransactionalLayerMaintainer.java:336-348`). Nothing anywhere inspects
its shape. `TransactionalReference` (`index/reference/TransactionalReference.java:54-58`) is the existence proof
that a diff layer may simply be a wholesale replacement value. A segment-based fulltext index could therefore hold
"the pending in-memory segment plus a deletion set" as its diff piece and produce a new immutable index on commit,
and readers would still see a consistent snapshot — because snapshot isolation here is nothing more than holding a
reference into an immutable object graph (§1.3), with no version chain to honour.

There is one real cost to be aware of. Commit today is *pruned*: only indexes a transaction actually touched are
rebuilt, and every other index is carried into the next catalog version by pointer
(`EntityCollection.java:1754-1767`, `:2746-2752`). An index that rewrites or re-merges itself on every commit would
give up that property for its own collection, so the merge work has to be proportional to the change, not to the
index size. A structure that only *appends* a segment and defers merging fits this model well; one that rebuilds a
term dictionary per commit does not.

If the index needs to participate in savepoint rollback, it must also support memento snapshot and restore — see
`TransactionalLayerMaintainer.java:428-522` and `transaction/memory/Snapshotable.java`. That is an inference from
the savepoint machinery, not something I confirmed by reading an index that does it.

### V2. Where it would register: `IndexComponent`, four methods

The registration seam is small and explicit. `EntityIndex` keeps a list of components
(`EntityIndex.java:212`) and walks it in one uniform loop during flush (`EntityIndex.java:799-805`). A new sub-index
implements `index/component/IndexComponent.java:53-115` — emit dirty storage parts and announce owned keys into the
shared manifest; reset dirty state; drop transactional layers; and optionally emit removals reclaiming its persisted
footprint when the owning index is dropped. A matching loader under `index/component/loader/` handles reload. Eight
components already do exactly this.

One constraint is stated as a requirement rather than a preference: components must be allocation-free in the flush
path, because `collectModifiedStorageParts` runs on every commit for every dirty index
(`IndexComponent.java:49-51`).

### V3. How its mutations would flow, and the one flag that is missing

Entity mutations reach indexes through a class-init registry keyed by concrete mutation class
(`LocalMutationHandlerRegistry.java:42-103`), dispatched from
`EntityIndexLocalMutationExecutor.applyMutation` (`:636-654`). For attributes specifically, the handler delegates to
a fan-out that ends in `AttributeIndexMutator`, and there the decision of whether to index a value at all is a
single three-way test on schema flags: `isUniqueInScope`, `isFilterableInScope`, `isSortableInScope`
(`AttributeIndexMutator.java:177-179` and `:320-321`).

So a fulltext index does not need a new mutation type or a new dispatch path. **INFERENCE** (from the flag test at
those two call sites, not from anything that states it): what it needs is a fourth attribute-schema flag alongside
the existing three, plus a branch in `AttributeIndexMutator` that routes the value into the new structure. The
repository has a documented eight-layer recipe for adding a schema flag (the `evita-schema-change` skill), covering
contracts, DTOs, builders, mutations, the three external APIs, and both Kryo and WAL serializers — so the cost of
that flag is known and bounded, but it is not small.

### V4. How a fulltext constraint would become a formula

Registration is one map entry: `FilterByVisitor.java:155` declares the translator registry and each constraint gets
a single `put` (`:183` onward). The translator asks the visitor to apply a lambda over the candidate indexes and
wraps the result in a formula.

The formula itself owes exactly two methods to participate in the cost model and the cache:
`getClassId()` and `includeAdditionalHash(LongHashFunction)` (`AbstractFormula.java:311`, `:320`). The identity hash
is then built as `[classId, sorted inner hashes…, additionalHash]` (`AbstractFormula.java:103-123`). For a fulltext
formula, `includeAdditionalHash` would have to cover the query string and every analysis parameter that changes the
result — analyzer, language, fuzziness — because two textually different queries that hash the same would serve each
other's cached results.

The harder half is the *second* hash. Cache validity is decided solely by comparing `getTransactionalIdHash()`
against the cached record (`CacheEden.java:200`), which is derived from the transactional ids of every data source
the formula read (`AbstractFormula.java:120-121`). `InvertedIndex` keeps this granular by emitting **leaf-version
tokens** for exactly the leaves a scan touched, collapsing to the whole-index id only past a cap of 100 distinct
leaves (`InvertedIndex.java:1280-1328`, cap at `TransactionalDataRelatedStructure.java:49`). A fulltext index needs
an equivalent: a cheap, stable token set naming the segments or postings pages a query actually read. Without one it
must report the whole-index id, and then any write anywhere in the collection invalidates every cached fulltext
result. That is the single most important design constraint on the read path, and the mechanism to copy is already
written.

There is also a hard prerequisite in the query language that has nothing to do with indexing. **There is no scoring
channel anywhere in evitaDB's query model** (§3.1): no relevance constraint, no score-based order constraint,
nothing in the 24 order constraints that consumes a per-record number. Ranked retrieval has no place to land today.
The sorter chain (`QueryPlan.java:178-210`) is a plausible insertion point since it already tolerates sorters that
order only part of the result, but the constraint, the ordering type, and the plumbing that carries scores out of
the formula tree would all be new.

### V5. How its memory would be accounted: it would not be, and that is the status quo

There is no runtime memory accounting for indexes at all. No index class exposes a size estimator, no test asserts a
memory number, and JOL is a dependency of the performance-test module only (§2.7). The only quantified figures in
the repository are two offline JOL spike reports. Budgeting a fulltext index therefore means writing a new JOL
spike in the same shape as `BucketStoreMemorySpike` / `RadixTrieMemorySpike` — there is nothing to instrument
against at runtime, and adding runtime accounting would itself be new work.

The relevant baseline numbers, for sizing arguments: the current inverted index costs roughly 10–12 bytes per
distinct single-record value for a high-cardinality string attribute and roughly 150–200 bytes per bucket for
low-cardinality multi-record ones, per `BucketStoreMemorySpike/results.md:34-39`.

### V6. Writing parts is a solved problem — but paging is write-side only, and that is not the same thing

The granular paging machinery is generic and already shared by ten leaf-page part types
(`index/page/PageStreamRegistry.java:309-331`, `PageBuilder`, `PageEmission`, `PagedLeafHandle`). A new paged index
owes: a part class, a payload serializer extending `AbstractLeafPagePartSerializer`, an **appended** registration in
`IndexStoragePartConfigurer` (positional ids — never insert, and a removal must leave a bare `index++` gap, per
`:133-134` and the ten "appended last to keep the preceding registration ids stable" comments), an **appended**
`StoragePartRecord` byte id in `IndexStoragePartRegistry` (47 is the next free before the catalog family at 50), and
a stream key plus its removal part.

Part sizing is not a concern: there is no maximum part size, and an oversized record is split across chained
continuation records automatically (§5.4). The natural unit is one persisted record per tree leaf, sized by the
in-memory block size (256 for inverted, 512 for range, 1024 ≈ 4 KiB for the chain elements tree).

The sharp constraint is stream identity. A page-stream id must be a single `int`, because the other half of the
64-bit part key is consumed by the page sequence (`AbstractLeafPagePart.java:83-85`,
`NumberUtils.java:259-261`). That int comes from the `KeyCompressor`, a bijective dictionary **persisted whole in
the catalog header and loaded eagerly** (`LeafStreamKey.java:38-51`). The existing grain is one dictionary entry per
persisted sub-index — bounded by schema size. **A fulltext design that wanted one page stream per term would put a
data-cardinality-sized dictionary into the catalog header**, which is exactly the thing the current scheme is
structured to avoid. Streams must be keyed at sub-index granularity, not at term granularity.

**The trap in the name.** "Paging" here means granular *persistence* — which leaf gets written as its own record —
and nothing else. Every `PageStreamRegistry` javadoc read during this survey describes it as living *outside*
transactional memory and being consulted *only on the single-writer flush/commit path*, and the registry is
consulted only from `collectChangedPages` (`index/page/PageStreamRegistry.java:329-331`) and its read-path twin
`restoredFrom` (`:246-248`). **INFERENCE** (nobody read a component loader to confirm that nothing evicts): there is
no read-side eviction or off-heap path, so leaves appear to stay heap-resident once loaded.

This flips a conclusion that the checklist above would otherwise invite. `PageStreamRegistry` is *not* an analogue
of Lucene's segment residency management, and a fulltext index built on it does not get demand-paged postings for
free — it inherits evitaDB's fully-in-memory constraint, and its whole term dictionary plus postings must be sized
to fit in heap. If the design depends on keeping cold postings on disk, that mechanism does not exist here and would
have to be built. Confirming or refuting the eviction question is the cheapest next check, and it should happen
before any sizing argument is accepted.

### V7. Two prior findings that constrain the solution space

First, a radix trie for string keys has already been measured against real demo-catalog data and lost on memory by
1.13× to 2.27× (`RadixTrieMemorySpike/results.md:8-12`). The `TOBEDONE JNO naive and slow - use RadixTree` markers
that appear three times in `FilterIndex` (at `:559`, `:580`, `:593`) point at an approach that was tried and
rejected. Any proposal to fix `attributeContains` with a trie needs to answer that measurement.

Second, and more consequential: **the analysis chain is a persisted format decision, not a runtime setting.** Index
keys are stored already normalized, so `2026-08-10-stored-value-normalization-split.md:210-212` establishes that
changing a normalizer is an on-disk format change requiring a backward-compatible reader. A tokenizer, case-folding
rule, stemmer or stopword list sits in exactly that position. Changing any of them after a catalog exists means
either an eager migration sweep or a self-healing BWC reader — the record documents both strategies and why the
reader was preferred for a rare type.

### V8. The gap, stated precisely

The storage substrate for fulltext already exists in this codebase and is unusually close to Lucene's. Leaves store
strings front-coded with a restart interval of 16, described in the source itself as *"the Lucene term-dictionary
layout"* (`FrontCodedStringColumn.java:40-51`, `:116-121`). Postings are RoaringBitmaps. The formula algebra has an
OR-fold, a cost model, and a leaf-versioned cache.

What is missing is not storage. It is three things. There is no analysis chain — the index stores whole attribute
values as single terms, and NFD without case folding means `Café` and `café` do not even unify (§3.3). There is no
scoring channel in the query model (§3.1, V4). And `InvertedIndex` carries an invariant that a term index violates
by construction: *"Histogram MUST NOT contain same record id in multiple buckets"*
(`InvertedIndex.java:94-96`) — one document belongs to many terms, so the existing value→records primitive cannot be
reused verbatim as a postings list without relaxing that invariant or introducing a parallel tree flavour.
