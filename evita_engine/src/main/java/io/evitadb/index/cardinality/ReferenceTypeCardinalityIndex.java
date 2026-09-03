/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.cardinality;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.core.transaction.memory.WarmUpTouchStamped;
import io.evitadb.dataType.array.CompositeLongArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.IndexHeapSize;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bPlusTree.LongPayloadBucketTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.LeafPageHandle;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import io.evitadb.index.result.CardinalityChange;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NumberUtils;
import io.evitadb.utils.VMLayout;
import lombok.Getter;
import lombok.Setter;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

import static io.evitadb.core.transaction.Transaction.isTransactionAvailable;
import static java.util.Optional.ofNullable;

/**
 * This index is used solely in {@link ReferencedTypeEntityIndex} for storing cardinality index of referenced entity
 * primary keys and also cardinality of {@link AbstractReducedEntityIndex} primary keys. It also provides information about
 * set of index primary keys for each referenced entity primary key that are present in the index.
 *
 * The index allows adding and removing keys, and retrieving the cardinalities of all keys.
 *
 * The index allows us to track the number of occurrences of a key in indexes that allow multiple occurrences of
 * the record in the index. In order to correctly remove the key from the index, we need to know how many times
 * the key is present in the index and remove it only when the last occurrence is evicted. This is where the cardinality
 * index comes in.
 *
 * The composed-key → cardinality count map is the second-largest churn wall at scale, so it is backed by a UNIQUE
 * {@link LongPayloadBucketTree} (composed signed `long` key → count widened to a `long` payload) and persisted
 * GRANULARLY as individual leaf pages, exactly like {@link io.evitadb.index.attribute.GlobalUniqueIndex}: a single
 * reference change rewrites one ~KB leaf page instead of the whole multi-MB part. The companion
 * {@link #referencedPrimaryKeysIndex} is the smaller member and always rides inline on the root storage part.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ReferenceTypeCardinalityIndex
	implements VoidTransactionMemoryProducer<ReferenceTypeCardinalityIndex>, IndexDataStructure,
	WarmUpTouchStamped, Serializable {
	@Serial private static final long serialVersionUID = -7416602590381722682L;
	/**
	 * This structure's first-touch mark for the warm-up savepoint mechanism: the stamp of the
	 * {@link WarmUpSavepoint} that most recently captured its pre-image. {@link WarmUpTouchStamped}
	 * carries the requirements the field has to meet, and why breaking one of them corrupts a
	 * rollback rather than merely slowing it down.
	 */
	@Getter @Setter private transient long warmUpTouchStamp;

	/**
	 * Block-size geometry of the cardinality bucket tree — a 256-entry leaf with the matching minimum split thresholds
	 * (identical to the unique-index value trees this paging clones).
	 */
	private static final int VALUE_BLOCK_SIZE = 256;
	private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;
	private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);
	/**
	 * Single page stream per cardinality index — its composed-key bucket tree (mirrors {@code OwnerUniqueIndex.UNIQUE_PAGE_STREAM}).
	 */
	private static final int CARDINALITY_PAGE_STREAM = 0;
	/**
	 * Natural signed-`long` order for the composed keys: positive whole-index-PK counters and negative per-reference
	 * counters interleave correctly (the {@code LongKeyCodec} identity encoding preserves sign), and a positive key can
	 * never collide with a negative one.
	 */
	private static final Comparator<Comparable<?>> NATURAL_ORDER = (Comparator) Comparator.naturalOrder();

	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * Holds the cardinalities of different entities as a UNIQUE single-`long` bucket tree: each composed signed `long`
	 * key holds exactly one `long` count. Keys are `+pack(indexPrimaryKey, 0)` (the per-whole-index-PK running total,
	 * positive) and `-pack(indexPrimaryKey, referencedEntityPrimaryKey)` (the per-tuple count, negative). Large trees
	 * persist granularly as leaf pages (see {@link #pageStreamRegistry}).
	 */
	@Nonnull private final LongPayloadBucketTree cardinalities;
	/**
	 * Per-index page bookkeeping for the granular leaf-page storage layout (the advance-only page allocator, the
	 * high-water and the live-page set of {@link #cardinalities}). It lives OUTSIDE transactional memory and is carried
	 * BY REFERENCE through {@link #createCopyWithMergedTransactionalMemory}, exactly like {@code OwnerUniqueIndex}.
	 */
	@Nonnull private final PageStreamRegistry pageStreamRegistry;
	/**
	 * Index that for each referenced entity primary key keeps the bitmap of all reduced entity index primary keys that
	 * contains entity primary keys referencing this entity.
	 */
	@Nonnull @Getter private final TransactionalMap<Integer, TransactionalBitmap> referencedPrimaryKeysIndex;
	/**
	 * Helper bitmap that contains all referenced entity primary keys that are present in keys of
	 * {@link #referencedPrimaryKeysIndex}.
	 */
	@Nullable private volatile PersistentRoaringBitmap memoizedAllReferencedPrimaryKeys;

	/**
	 * Creates a fresh, empty cardinality bucket tree (single-`long` payload column holding the count) ordered by natural
	 * signed-`long` key order.
	 *
	 * @return the fresh empty long-payload bucket tree
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree createEmptyTree() {
		// the runtime key is always `Long`; the tree is constructed with the erased `Comparable` key type exactly like the
		// unique-index value trees, while the `Long` value-column factory selects the primitive long[] leaf column
		final Class keyType = Comparable.class;
		final ValueColumnFactory factory = ValueColumnFactory.forKey(Long.class, NATURAL_ORDER);
		return TransactionalBucketBPlusTree.withLongPayload(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			keyType, (Comparator) NATURAL_ORDER, factory
		);
	}

	/**
	 * Rebuilds a `PAGED` cardinality index from its persisted leaf pages, preserving the original leaf boundaries and
	 * page identities (mirrors {@code OwnerUniqueIndex.fromPersistedPages}). One leaf per persisted page is built from the
	 * positionally-aligned key + count columns, each stamped with its page sequence, and the page-stream bookkeeping
	 * (high-water + live set) is restored, so the first post-restart commit rewrites only genuinely-changed leaves rather
	 * than re-paginating the whole index.
	 *
	 * @param indexDescription       a full identification of this index for corruption diagnostics (e.g. the reference)
	 * @param orderedPageSequences   the persisted leaf-page sequences in ascending key order
	 * @param perPageKeys            the composed `long` keys of each leaf page, positionally aligned with `orderedPageSequences`
	 * @param perPagePayloads        the counts of each leaf page, positionally aligned with `perPageKeys`
	 * @param highWaterPageSequence  the persisted stream high-water (largest page sequence ever allocated)
	 * @param referencedPrimaryKeys  the inline companion `referencedEntityPrimaryKey → reduced-index-PK bitmap` map
	 * @return the rebuilt, boundary-stable `PAGED` cardinality index
	 */
	@Nonnull
	public static ReferenceTypeCardinalityIndex fromPersistedPages(
		@Nonnull String indexDescription,
		@Nonnull int[] orderedPageSequences,
		@Nonnull long[][] perPageKeys,
		@Nonnull long[][] perPagePayloads,
		int highWaterPageSequence,
		@Nonnull Map<Integer, TransactionalBitmap> referencedPrimaryKeys
	) {
		Assert.isPremiseValid(
			orderedPageSequences.length == perPageKeys.length && perPageKeys.length == perPagePayloads.length,
			"The number of page sequences must match the number of leaf-page arrays."
		);
		Assert.isPremiseValid(orderedPageSequences.length > 0, "A paged cardinality index must have at least one leaf page.");
		final List<TransactionalBucketBPlusTree> pageTrees = new ArrayList<>(orderedPageSequences.length);
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final long[] keys = perPageKeys[i];
			final long[] payloads = perPagePayloads[i];
			// a page never exceeds a leaf's capacity, so this single-leaf tree never splits
			final TransactionalBucketBPlusTree pageTree = createEmptyTree();
			for (int j = 0; j < keys.length; j++) {
				pageTree.addLongRecord(keys[j], payloads[j]);
			}
			pageTrees.add(pageTree);
		}
		final TransactionalBucketBPlusTree tree =
			createEmptyTree().assembleFromSingleLeafTrees(pageTrees, orderedPageSequences, "cardinality index for " + indexDescription);
		final PageStreamRegistry registry = PageStreamRegistry.restoredFrom(
			CARDINALITY_PAGE_STREAM, highWaterPageSequence, tree.leafPageHandles()
		);
		return new ReferenceTypeCardinalityIndex(tree, registry, referencedPrimaryKeys);
	}

	/**
	 * Creates a fresh, empty cardinality index: an empty cardinality bucket tree and an empty companion
	 * `referencedEntityPrimaryKey → reduced-index-PK bitmap` map. Used when a reference type starts being indexed and
	 * there is no previously persisted state to restore from.
	 */
	public ReferenceTypeCardinalityIndex() {
		this.dirty = new TransactionalBoolean();
		this.cardinalities = createEmptyTree();
		this.pageStreamRegistry = new PageStreamRegistry();
		this.referencedPrimaryKeysIndex = new TransactionalMap<>(
			CollectionUtils.createHashMap(16), TransactionalBitmap.class, TransactionalBitmap::new);
	}

	/**
	 * Reconstructs the index from a previously-persisted SINGLE-shape state: the composed-key → cardinality count map is
	 * replayed into a fresh bucket tree (insert order is arbitrary; the UNIQUE tree handles it) and the companion
	 * `referencedEntityPrimaryKey → reduced-index-PK bitmap` map is re-wrapped. Used by the SINGLE reload path in
	 * {@link io.evitadb.index.component.loader.ReferenceTypeCardinalityLoader} and by `Migration_2025_6`.
	 *
	 * @param cardinalities         the persisted composed-key → cardinality count map
	 * @param referencedPrimaryKeys the persisted `referencedEntityPrimaryKey → reduced-index-PK bitmap` companion map
	 */
	public ReferenceTypeCardinalityIndex(
		@Nonnull Map<Long, Integer> cardinalities,
		@Nonnull Map<Integer, TransactionalBitmap> referencedPrimaryKeys
	) {
		this.dirty = new TransactionalBoolean();
		final TransactionalBucketBPlusTree tree = createEmptyTree();
		// the seeding map is unordered; addLongRecord into a UNIQUE tree handles arbitrary insert order
		for (final Entry<Long, Integer> entry : cardinalities.entrySet()) {
			tree.addLongRecord(entry.getKey(), entry.getValue());
		}
		this.cardinalities = tree;
		this.pageStreamRegistry = new PageStreamRegistry();
		this.referencedPrimaryKeysIndex = new TransactionalMap<>(
			referencedPrimaryKeys, TransactionalBitmap.class, TransactionalBitmap::new);
	}

	/**
	 * Private constructor used by {@link #createCopyWithMergedTransactionalMemory} and {@link #fromPersistedPages} to
	 * adopt an already-built tree directly (no re-seeding); the assembled/committed tree already carries its leaf-page
	 * sequences, so the page bookkeeping is carried by reference.
	 *
	 * @param committedTree         the already-built cardinality tree to adopt
	 * @param pageStreamRegistry    the per-index page bookkeeping, carried BY REFERENCE
	 * @param referencedPrimaryKeys the companion map to re-wrap into a {@link TransactionalMap}
	 */
	private ReferenceTypeCardinalityIndex(
		@Nonnull LongPayloadBucketTree committedTree,
		@Nonnull PageStreamRegistry pageStreamRegistry,
		@Nonnull Map<Integer, TransactionalBitmap> referencedPrimaryKeys
	) {
		this.dirty = new TransactionalBoolean();
		this.cardinalities = committedTree;
		this.pageStreamRegistry = pageStreamRegistry;
		this.referencedPrimaryKeysIndex = new TransactionalMap<>(
			referencedPrimaryKeys, TransactionalBitmap.class, TransactionalBitmap::new);
	}

	/**
	 * Returns cardinalities of all keys in the index as a freshly materialized map (a cursor walk over the backing
	 * tree). This is a test / migration / inspection helper only — the persistence hot path captures the tree's columns
	 * directly via {@link #appendStorageParts} and never builds this map.
	 *
	 * @return cardinalities of all keys in the index
	 */
	@Nonnull
	public Map<Long, Integer> getCardinalities() {
		final BucketCursor cursor = this.cardinalities.cursor();
		final Map<Long, Integer> result = CollectionUtils.createHashMap(Math.max(16, this.cardinalities.size()));
		while (cursor.next()) {
			result.put((Long) cursor.value(), (int) cursor.longRecordId());
		}
		return result;
	}

	/**
	 * Increases cardinality of the given (indexPrimaryKey, referencedEntityPrimaryKey) tuple by one.
	 * If the indexPrimaryKey was not yet tracked at all (cardinality 0 -> 1 for the whole index
	 * primary key), the method returns `BOUNDARY_CROSSED` so callers can propagate the new entry to
	 * membership-only downstream indexes. Otherwise the cardinality is incremented and
	 * `NO_BOUNDARY_CROSSING` is returned. The fine-grained bookkeeping of the referenced primary key
	 * bitmap is performed unconditionally.
	 *
	 * @param indexPrimaryKey            primary key of the entity index that tracks relation between
	 *                                   the record and the referenced entity
	 * @param referencedEntityPrimaryKey primary key of the referenced entity
	 * @return `BOUNDARY_CROSSED` if this call caused the index primary key to enter the index for
	 *         the first time, `NO_BOUNDARY_CROSSING` otherwise
	 */
	@Nonnull
	public CardinalityChange addRecord(int indexPrimaryKey, int referencedEntityPrimaryKey) {
		Assert.isPremiseValid(
			indexPrimaryKey != 0,
			"Index primary key must not be zero!"
		);

		final boolean added = addCardinality(NumberUtils.pack(indexPrimaryKey, 0));
		if (addCardinality(-1L * NumberUtils.pack(indexPrimaryKey, referencedEntityPrimaryKey))) {
			TransactionalBitmap indexIdBitmap = this.referencedPrimaryKeysIndex.get(referencedEntityPrimaryKey);
			if (indexIdBitmap == null) {
				indexIdBitmap = new TransactionalBitmap();
				this.referencedPrimaryKeysIndex.put(referencedEntityPrimaryKey, indexIdBitmap);
			}
			indexIdBitmap.add(indexPrimaryKey);
		}

		if (!isTransactionAvailable()) {
			recordWarmUpSavepointTouch();
			this.memoizedAllReferencedPrimaryKeys = null;
		}
		this.dirty.setToTrue();
		return added ? CardinalityChange.BOUNDARY_CROSSED : CardinalityChange.NO_BOUNDARY_CROSSING;
	}

	/**
	 * Decreases cardinality of the given (indexPrimaryKey, referencedEntityPrimaryKey) tuple by one.
	 * If the cardinality of the indexPrimaryKey reaches zero overall, the tuple is removed from the
	 * index and `BOUNDARY_CROSSED` is returned so callers can propagate the removal to
	 * membership-only downstream indexes. Otherwise the cardinality is decremented and
	 * `NO_BOUNDARY_CROSSING` is returned.
	 *
	 * @param indexPrimaryKey            primary key of the entity index that tracks relation between
	 *                                   the record and the referenced entity
	 * @param referencedEntityPrimaryKey primary key of the referenced entity
	 * @return `BOUNDARY_CROSSED` if the index primary key fell out of the index entirely,
	 *         `NO_BOUNDARY_CROSSING` otherwise
	 */
	@Nonnull
	public CardinalityChange removeRecord(int indexPrimaryKey, int referencedEntityPrimaryKey) {
		Assert.isPremiseValid(
			indexPrimaryKey != 0,
			"Index primary key must not be zero!"
		);

		final boolean removed = removeCardinality(NumberUtils.pack(indexPrimaryKey, 0));
		if (removeCardinality(-1L * NumberUtils.pack(indexPrimaryKey, referencedEntityPrimaryKey))) {
			final TransactionalBitmap indexIdBitmap = this.referencedPrimaryKeysIndex.get(referencedEntityPrimaryKey);
			Assert.isPremiseValid(
				indexIdBitmap != null,
				() -> new GenericEvitaInternalError(
					"Referenced entity primary key " + referencedEntityPrimaryKey + " is unexpectedly not found in the index!")
			);
			// remove the index primary key from the bitmap
			indexIdBitmap.remove(indexPrimaryKey);
			// clean up empty bitmap to avoid memory leaks
			if (indexIdBitmap.isEmpty()) {
				final TransactionalBitmap removedBitmap = this.referencedPrimaryKeysIndex.remove(referencedEntityPrimaryKey);
				if (removedBitmap != null) {
					final TransactionalLayerMaintainer transactionalLayer = Transaction.getTransactionalLayerMaintainer();
					if (transactionalLayer != null) {
						removedBitmap.removeLayer(transactionalLayer);
					}
				}
			}
		}
		if (!isTransactionAvailable()) {
			recordWarmUpSavepointTouch();
			this.memoizedAllReferencedPrimaryKeys = null;
		}
		this.dirty.setToTrue();
		return removed ? CardinalityChange.BOUNDARY_CROSSED : CardinalityChange.NO_BOUNDARY_CROSSING;
	}

	/**
	 * Records, for the warm-up savepoint bracketing the current root entity mutation if one is open, that
	 * {@link #memoizedAllReferencedPrimaryKeys} has to be left INVALIDATED should the mutation be rolled back (see
	 * {@link WarmUpSavepoint}).
	 *
	 * Both mutators already null the memo on the forward path; the journal entry covers a read performed LATER inside
	 * the same root entity mutation, which would repopulate it from the half-mutated cardinalities and leave it stale
	 * once those are rewound. Re-invalidating on restore costs one recomputation and makes no claim about a captured
	 * bitmap's validity.
	 *
	 * Recorded once per savepoint, and only from the non-transactional branch - inside a transaction no warm-up
	 * savepoint is ever open. Outside a savepoint it costs one {@link ThreadLocal} read returning `null`.
	 */
	private void recordWarmUpSavepointTouch() {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint != null && savepoint.claimFirstTouch(this)) {
			savepoint.pushPostRestoreInvalidation(() -> this.memoizedAllReferencedPrimaryKeys = null);
		}
	}

	/**
	 * Returns TRUE if this contains no data.
	 *
	 * @return TRUE if this contains no data
	 */
	public boolean isEmpty() {
		return this.cardinalities.size() == 0;
	}

	/**
	 * Returns an unmodifiable view of all referenced entity primary keys tracked by this index. For a
	 * `REFERENCED_GROUP_ENTITY_TYPE` index these are the group entity PKs; for a `REFERENCED_ENTITY_TYPE`
	 * index these are the referenced (facet) entity PKs.
	 *
	 * Used by ReevaluateExpressionExecutor to iterate all groups when resolving group PKs for
	 * {@link DependencyType#REFERENCED_ENTITY_ATTRIBUTE} dependencies on grouped references.
	 *
	 * @return unmodifiable set of all tracked referenced entity primary keys
	 */
	@Nonnull
	public Set<Integer> getAllTrackedReferencedEntityPrimaryKeys() {
		return Collections.unmodifiableSet(this.referencedPrimaryKeysIndex.keySet());
	}

	/**
	 * Returns all tracked referenced entity primary keys as a {@link Bitmap}. Outside of a transactional
	 * context the underlying {@link PersistentRoaringBitmap} is memoized so repeated query-time calls (histogram
	 * boundary resolution iterates this set for every surviving histogram) do not rebuild it.
	 *
	 * **Read-only contract** — the returned bitmap aliases the memoized snapshot; callers must not
	 * mutate it. All production call sites (see
	 * {@code ReferenceHistogramAccumulator.collectGroupedPending} for iteration and
	 * {@code ReferenceHistogramAccumulator.pickBoundaryPk} for `PersistentRoaringBitmap.and` intersection) treat
	 * it as immutable. A defensive copy on every call would negate the memoization benefit.
	 *
	 * @return bitmap of referenced entity primary keys, may be {@link EmptyBitmap#INSTANCE}
	 */
	@Nonnull
	public Bitmap getAllTrackedReferencedEntityPrimaryKeysAsBitmap() {
		if (this.referencedPrimaryKeysIndex.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		if (Transaction.isTransactionAvailable()) {
			return new BaseBitmap(buildReferencedPrimaryKeysBitmap());
		}
		PersistentRoaringBitmap result = this.memoizedAllReferencedPrimaryKeys;
		if (result == null) {
			result = buildReferencedPrimaryKeysBitmap();
			this.memoizedAllReferencedPrimaryKeys = result;
		}
		return new BaseBitmap(result);
	}

	/**
	 * Builds a fresh {@link PersistentRoaringBitmap} snapshot from all keys currently present in
	 * {@link #referencedPrimaryKeysIndex}. Called either to populate {@link #memoizedAllReferencedPrimaryKeys}
	 * (outside a transaction) or to produce a one-shot bitmap within a transaction (where memoization is skipped
	 * because the index contents may change before the bitmap is consumed).
	 *
	 * @return a new {@link PersistentRoaringBitmap} containing all referenced entity primary keys tracked by this index
	 */
	@Nonnull
	private PersistentRoaringBitmap buildReferencedPrimaryKeysBitmap() {
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		for (final Integer referencedEntityId : this.referencedPrimaryKeysIndex.keySet()) {
			writer.add(referencedEntityId);
		}
		return writer.get();
	}

	/**
	 * Retrieves all reference indexes associated with the given referenced entity primary key.
	 *
	 * @param referencedEntityPrimaryKey the primary key of the referenced entity for which the indexes are to be retrieved
	 * @return an array of all reference indexes primary keys associated with the specified referenced entity primary key
	 */
	public int[] getAllReferenceIndexes(int referencedEntityPrimaryKey) {
		return ofNullable(this.referencedPrimaryKeysIndex.get(referencedEntityPrimaryKey))
			.map(TransactionalBitmap::getArray)
			.orElse(ArrayUtils.EMPTY_INT_ARRAY);
	}

	/**
	 * Returns the set of referenced entity primary keys (i.e., the keys of the forward mapping) whose
	 * index primary key bitmaps have a non-empty intersection with the given set of index primary keys.
	 *
	 * This is the **reverse** of {@link #getIndexPrimaryKeys(PersistentRoaringBitmap)}: given a bitmap of
	 * reduced-index PKs, it identifies which referenced entity PKs are associated with them.
	 *
	 * @param indexPrimaryKeys bitmap of reduced-index primary keys to look up
	 * @return bitmap of referenced entity primary keys whose index PKs overlap with the input;
	 *         never {@code null}, may be {@link EmptyBitmap#INSTANCE}
	 */
	@Nonnull
	public Bitmap getReferencedPrimaryKeysForIndexPks(@Nonnull Bitmap indexPrimaryKeys) {
		if (indexPrimaryKeys.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		final PersistentRoaringBitmap indexPksBitmap = RoaringBitmapBackedBitmap.getRoaringBitmap(indexPrimaryKeys);
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		for (Map.Entry<Integer, TransactionalBitmap> entry : this.referencedPrimaryKeysIndex.entrySet()) {
			if (
				PersistentRoaringBitmap.intersects(
					indexPksBitmap,
					RoaringBitmapBackedBitmap.getRoaringBitmap(entry.getValue())
				)
			) {
				writer.add(entry.getKey());
			}
		}
		final PersistentRoaringBitmap result = writer.get();
		return result.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(result);
	}

	/**
	 * Constructs a Formula representing the intersection of the primary keys managed by this index
	 * and the referenced entity primary keys provided as input.
	 *
	 * @param referencedEntityPrimaryKeys an array of referenced entity primary keys to be intersected with
	 *                                    the primary keys managed by this index
	 * @return a Formula representing the intersection of the primary keys; returns an empty formula if
	 *         the input array is empty
	 */
	@Nonnull
	public Bitmap getIndexPrimaryKeys(@Nonnull PersistentRoaringBitmap referencedEntityPrimaryKeys) {
		if (referencedEntityPrimaryKeys.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		} else {
			PersistentRoaringBitmap allReferencedPrimaryKeys;
			if (Transaction.isTransactionAvailable()) {
				final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
				for (Integer referencedEntityId : this.referencedPrimaryKeysIndex.keySet()) {
					writer.add(referencedEntityId);
				}
				allReferencedPrimaryKeys = writer.get();
			} else {
				allReferencedPrimaryKeys = this.memoizedAllReferencedPrimaryKeys;
				if (allReferencedPrimaryKeys == null) {
					final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
					for (Integer referencedEntityId : this.referencedPrimaryKeysIndex.keySet()) {
						writer.add(referencedEntityId);
					}
					allReferencedPrimaryKeys = writer.get();
					this.memoizedAllReferencedPrimaryKeys = allReferencedPrimaryKeys;
				}
			}
			final PersistentRoaringBitmap matchingReferencedEntityPks = PersistentRoaringBitmap.and(
				allReferencedPrimaryKeys,
				referencedEntityPrimaryKeys
			);
			final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			for (Integer matchingReferencedEntityPk : matchingReferencedEntityPks) {
				final TransactionalBitmap indexIds = Objects.requireNonNull(
					this.referencedPrimaryKeysIndex.get(matchingReferencedEntityPk)
				);
				indexIds.forEach(writer::add);
			}
			return new BaseBitmap(writer.get());
		}
	}

	/**
	 * Returns whether this index's cardinality tree spans more than one leaf and is therefore persisted in the granular
	 * `PAGED` shape rather than the inline `SINGLE` shape.
	 *
	 * @return true when the tree has an internal root (≥ 2 leaves)
	 */
	public boolean isPaged() {
		return this.cardinalities.isRootInternal();
	}


	/**
	 * Emits a removal for every leaf page this index currently has ON DISK, used when the owning entity index is
	 * dropped and its whole persisted footprint must be reclaimed from the append-only storage. Reads only the
	 * persisted page baseline (never the live tree) and has no side effects — in particular it does NOT
	 * {@code forget} the stream, because the index is being discarded rather than reshaped.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param referenceName         the reference this cardinality index belongs to
	 * @param sink                  the accumulator collecting the removal instructions
	 */
	public void emitPersistedLeafPageRemovals(
		int entityIndexPrimaryKey,
		@Nonnull String referenceName,
		@Nonnull TrappedChanges sink
	) {
		for (final int persistedPageSequence : this.pageStreamRegistry.pendingLivePageSequences(CARDINALITY_PAGE_STREAM)) {
			sink.addChangeToStore(
				new ReferenceTypeCardinalityIndexLeafPageRemoval(entityIndexPrimaryKey, referenceName, persistedPageSequence)
			);
		}
	}

	/**
	 * Appends this index's modified storage parts to the flush sink. PAGED: one leaf page per CHANGED leaf, a removal per
	 * freed leaf, plus a PAGED root carrying the high-water, the ordered live leaf-page list and the INLINE companion
	 * map. SINGLE: if the index just collapsed from PAGED, remove every prior leaf page, forget the stream, then write
	 * the inline root. Mirrors {@code OwnerUniqueIndex.appendStorageParts}.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index (the sub-index identity with `referenceName`)
	 * @param referenceName         the reference name of this sub-index
	 * @param sink                  the flush sink receiving the changed parts
	 */
	public void appendStorageParts(int entityIndexPrimaryKey, @Nonnull String referenceName, @Nonnull TrappedChanges sink) {
		if (!this.dirty.isTrue()) {
			return;
		}
		if (this.cardinalities.isRootInternal()) {
			// PAGED: one leaf page per CHANGED leaf + a removal per freed leaf + a PAGED root carrying the high-water, the
			// ordered live leaf-page list and the inline companion map
			final PageEmission<LeafPage> emission = collectChangedPages();
			for (final LeafPage page : emission.changedPages()) {
				sink.addChangeToStore(
					new ReferenceTypeCardinalityIndexLeafPagePart(
						entityIndexPrimaryKey, referenceName, page.pageSequence(), page.keys(), page.payloads()
					)
				);
			}
			for (final int freedPageSequence : emission.freedPageSequences()) {
				sink.addChangeToStore(
					new ReferenceTypeCardinalityIndexLeafPageRemoval(entityIndexPrimaryKey, referenceName, freedPageSequence)
				);
			}
			// NOTE: unlike the pure page-list roots (Chain / OwnerUnique / OwnerSort / FilterIndex), this root also
			// carries the inline referencedPrimaryKeysIndex, which moves in lockstep with the tree — so it is re-emitted
			// every dirty commit and CANNOT use the PageEmission.pageListChanged() skip. Making it O(1) would need that
			// companion map split into its own sibling storage part (follow-up).
			sink.addChangeToStore(
				ReferenceTypeCardinalityIndexStoragePart.paged(
					entityIndexPrimaryKey, referenceName,
					emission.highWaterPageSequence(), emission.orderedPageSequences(), this.referencedPrimaryKeysIndex
				)
			);
		} else {
			// SINGLE shape: the index spans one leaf. If it just collapsed from PAGED, remove every prior leaf page (the
			// inline root no longer references them) BEFORE dropping the bookkeeping, then forget the stream so a later
			// regrow into PAGED starts from a clean baseline and re-emits every leaf.
			// Reclaim against what the previous flush left ON DISK: its staged set while still unpublished (a warm-up
			// flush never reaches the commit-merge that publishes), else the published set. The published set alone lags a
			// whole flush behind, so every page of the collapsed stream would leak — the append-only OffsetIndex never
			// reclaims a record that is neither superseded nor explicitly removed.
			for (final int freedPageSequence : this.pageStreamRegistry.pendingLivePageSequences(CARDINALITY_PAGE_STREAM)) {
				sink.addChangeToStore(
					new ReferenceTypeCardinalityIndexLeafPageRemoval(entityIndexPrimaryKey, referenceName, freedPageSequence)
				);
			}
			this.pageStreamRegistry.forget(CARDINALITY_PAGE_STREAM);
			// the small index is a single embedded leaf: capture its key/count columns directly off the tree (no map
			// materialization) and carry them inline on the root, exactly as a leaf page would
			final InlineSnapshot snapshot = inlineSnapshot();
			sink.addChangeToStore(
				new ReferenceTypeCardinalityIndexStoragePart(
					entityIndexPrimaryKey, referenceName, snapshot.keys(), snapshot.payloads(), this.referencedPrimaryKeysIndex
				)
			);
		}
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	/**
	 * Returns the heap this index occupies, in bytes — its own object, its dirty flag, the cardinality tree, the
	 * referenced-key index and the helper bitmap over it when one has been built.
	 *
	 * # What is charged, and what is not
	 *
	 * The cardinality tree's keys are composed `long`s under natural order, so its leaves keep them inline in a
	 * primitive column - but its **internal nodes still box one separator key per leaf boundary**, and with the
	 * leaves storing values nothing else holds those boxes. They are therefore priced through
	 * {@link IndexHeapSize#OWNED_KEY_SIZER} like any other key this structure owns; the tree itself decides which of
	 * its keys the sizer is asked about.
	 *
	 * {@link #memoizedAllReferencedPrimaryKeys} is charged in full. It is built from the map's **keys** through a
	 * fresh writer rather than by unioning the value bitmaps, so it aliases nothing that is also charged below.
	 * Being lazily built it makes the figure **jump on first use**, the same way every memoized projection in this
	 * layer does.
	 *
	 * {@link #pageStreamRegistry} is excluded: single-writer flush bookkeeping carried by reference across commits,
	 * not index content.
	 *
	 * Walking the cardinality tree is `O(entries / blockSize)` rather than `O(1)`, so this belongs to
	 * the index detail call and must never be called from a query path.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		final long boxedInteger = layout.sizeOfObject(Integer.BYTES);
		// warmUpTouchStamp + the dirty / cardinalities / pageStreamRegistry / referencedPrimaryKeysIndex /
		// memoizedAllReferencedPrimaryKeys slots
		long size = layout.sizeOfObject(Long.BYTES + 5L * layout.referenceSize())
			+ this.dirty.getHeapSizeInBytes()
			+ this.cardinalities.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER)
			+ this.referencedPrimaryKeysIndex.getHeapSizeInBytes(
				key -> boxedInteger, TransactionalBitmap::getHeapSizeInBytes
			);
		// read the volatile field ONCE: a concurrent reader can publish or drop the projection between two reads
		final PersistentRoaringBitmap allReferenced = this.memoizedAllReferencedPrimaryKeys;
		if (allReferenced != null) {
			size += allReferenced.getHeapSizeInBytes(RoaringBitmapBackedBitmap.ROARING_HEAP_LAYOUT);
		}
		return size;
	}

	/*
		TransactionalLayerProducer implementation
	 */

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.cardinalities.removeLayer(transactionalLayer);
		this.referencedPrimaryKeysIndex.removeLayer(transactionalLayer);
		this.dirty.removeLayer(transactionalLayer);
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Nonnull
	@Override
	public ReferenceTypeCardinalityIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// we can safely throw away dirty flag now
		final boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			final TransactionalBucketBPlusTree committedTree =
				(TransactionalBucketBPlusTree) transactionalLayer.getStateCopyWithCommittedChanges(this.cardinalities);
			// publish the page baseline staged by this commit's flush: the merge runs only AFTER the flush has durably
			// written the changed leaf pages + root, so the staged live set now reflects what is on disk. The registry is
			// then carried BY REFERENCE into the committed copy, so the surviving index keeps it (mirrors OwnerUniqueIndex).
			// This is the EARLIEST publish point on the transactional path only; it is not the only one — a staged set
			// that never reaches a merge (the warm-up path has no merge at all) is published by the next flush instead,
			// see `publishPreviousFlush`. (No discard counterpart is needed: a pre-flush abort never stages, and a
			// failed flush suspends this catalog's transaction processing — on the warm-up path it marks the
			// catalog unpublishable instead, the same invariant in another dress — so no later flush ever diffs against
			// the baseline a failed one left behind; restart rebuilds a clean registry from disk.)
			this.pageStreamRegistry.publishStaged();
			return new ReferenceTypeCardinalityIndex(
				committedTree,
				this.pageStreamRegistry,
				transactionalLayer.getStateCopyWithCommittedChanges(this.referencedPrimaryKeysIndex)
			);
		} else {
			return this;
		}
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Increases the cardinality of the given composed key by one. If the key is not present in the tree, it is added
	 * with a cardinality of 1 and the method returns true. Otherwise the existing count is overwritten with `count + 1`
	 * (remove + re-add, since the UNIQUE tree holds exactly one payload per key) and the method returns false.
	 *
	 * @param composedKey the composed signed `long` key whose cardinality is to be updated
	 * @return true if the key was not already present in the index, false otherwise
	 */
	private boolean addCardinality(long composedKey) {
		final OptionalLong existing = this.cardinalities.getLongRecordEqualTo(composedKey);
		if (existing.isEmpty()) {
			this.cardinalities.addLongRecord(composedKey, 1L);
			return true;
		}
		this.cardinalities.removeLongRecord(composedKey);
		this.cardinalities.addLongRecord(composedKey, existing.getAsLong() + 1L);
		return false;
	}

	/**
	 * Decreases the cardinality associated with the given composed key by one. If the cardinality reaches zero, the key
	 * is removed from the tree and the method returns true. If the key does not exist, an exception is thrown. Otherwise
	 * the count is overwritten with `count - 1` and the method returns false.
	 *
	 * @param composedKey the composed signed `long` key whose cardinality is to be updated
	 * @return true if the key was removed from the index, false otherwise
	 * @throws GenericEvitaInternalError if the cardinality of the given key is null
	 */
	private boolean removeCardinality(long composedKey) {
		final OptionalLong existing = this.cardinalities.getLongRecordEqualTo(composedKey);
		if (existing.isEmpty()) {
			throw new GenericEvitaInternalError(
				"Cardinality of index PK `" + composedKey + "` is null"
			);
		}
		final long updated = existing.getAsLong() - 1L;
		if (updated == 0L) {
			this.cardinalities.removeLongRecord(composedKey);
			return true;
		}
		this.cardinalities.removeLongRecord(composedKey);
		this.cardinalities.addLongRecord(composedKey, updated);
		return false;
	}

	/**
	 * Returns the whole cardinality tree as sorted, positionally-aligned `(key, count)` primitive columns, built by a
	 * single cursor walk — the same shape the `SINGLE` storage part and a leaf page carry. Allocation-lean (no map and no
	 * boxing): feeds the inline `SINGLE` write path.
	 *
	 * @return the inline snapshot of every entry in ascending key order
	 */
	@Nonnull
	private InlineSnapshot inlineSnapshot() {
		final CompositeLongArray snapshotKeys = new CompositeLongArray();
		final CompositeLongArray snapshotPayloads = new CompositeLongArray();
		final BucketCursor cursor = this.cardinalities.cursor();
		while (cursor.next()) {
			snapshotKeys.add((Long) cursor.value());
			snapshotPayloads.add(cursor.longRecordId());
		}
		return new InlineSnapshot(snapshotKeys.toArray(), snapshotPayloads.toArray());
	}

	/**
	 * Promotes the page set staged by the PREVIOUS flush to the live change-detection baseline, so this flush's
	 * freed-page diff is taken against what disk actually holds.
	 *
	 * The registry's live set answers "which leaf pages does this stream have on disk", and the write path derives the
	 * freed-page reclaim from it — which pages a leaf merge dropped from the `(key, count)` bucket tree, so a
	 * {@link ReferenceTypeCardinalityIndexLeafPageRemoval} is emitted for each and their entries stop being copied
	 * forward. It advances solely by publishing, which {@link #createCopyWithMergedTransactionalMemory} does at the
	 * commit-merge.
	 *
	 * A WARM_UP (bulk) flush never reaches a commit-merge: it runs the very same collect pipeline as a transaction, but
	 * the merge that publishes only ever runs for one. Left alone, the live set of a freshly re-indexed catalog would
	 * therefore stay EMPTY for the whole warm-up while disk moved on, making the freed-page diff of every warm-up flush
	 * of this cardinality tree vacuously empty. A leaf MERGE is the one structural event that drops a page without
	 * creating one — the survivor absorbs its sibling IN PLACE, keeping its own page and dirty flag, so nothing is
	 * allocated — which leaves the dropped page unremoved and therefore ORPHANED on disk. Unlike a pure page-list root
	 * (Chain / OwnerUnique / OwnerSort / FilterIndex), this `PAGED` root can never go stale on a cold reload: it also
	 * carries the inline {@link #referencedPrimaryKeysIndex}, so it is re-emitted every dirty commit regardless of
	 * whether the leaf list changed, and its `leafPageSequences` is always derived from the CURRENT tree rather than the
	 * registry — so {@link #fromPersistedPages} never re-reads the dropped page and no cross-leaf overlap can occur. The
	 * observable failure here is therefore not a reload-time corruption but a silent storage LEAK: the orphaned leaf
	 * page is unreferenced by the root yet was never explicitly removed, so the append-only OffsetIndex — which
	 * reclaims space only for records it is told to remove, never by reachability — copies it forward at every future
	 * compaction.
	 *
	 * Publishing a staged set HERE — rather than only at the merge — is correct for every path, because of one
	 * invariant: **a failed flush is never followed by another flush of the same data**. Note that this publish runs at
	 * COLLECT time, before this flush has written anything (the baseline-capture pass re-enters this pipeline), so it
	 * cannot lean on the previous flush's bytes having landed by now. It does not need to: a flush that fails during
	 * trunk incorporation SUSPENDS the catalog's transaction processing ({@code TransactionManager.suspend}), and a
	 * flush that fails on the warm-up path makes the catalog UNPUBLISHABLE
	 * ({@code Catalog.markUnpublishable}), so every later flush of it refuses deterministically. Those two
	 * are the same invariant in different dresses: after a failed flush no later flush of that data ever runs, so
	 * nothing can ever diff against the baselines it left behind. A flush that does NOT fail leaves `staged` holding
	 * exactly the page set it wrote — the baseline the next flush must diff against — regardless of which path staged
	 * it, and regardless of whether a merge ever ran. (Should the process die instead, {@link #fromPersistedPages}
	 * rebuilds the registry from disk on restart — page allocation is advance-only, so a burnt id is harmless.) That is
	 * what makes this safe in its own right — not the fact that it happens to be a no-op on the transactional path
	 * (where the merge published first, leaving nothing staged). The commit handshake is untouched.
	 */
	private void publishPreviousFlush() {
		this.pageStreamRegistry.publishStaged();
	}

	/**
	 * Walks the cardinality tree leaf-by-leaf and returns the granular write-path emission for this commit: the leaf
	 * pages that changed since the last flush, the full ordered list of live leaf-page sequences (the `PAGED` root's leaf
	 * list), the stream high-water, and the freed page sequences a leaf merge dropped. Mirrors
	 * {@code OwnerUniqueIndex.collectChangedPages} with the slim `(key, count)` columns.
	 *
	 * Before staging, any set still staged by the PREVIOUS flush is promoted to live: see
	 * {@link #publishPreviousFlush()} for why that is both necessary and safe.
	 *
	 * @return the changed leaf pages, the ordered live page-sequence list, the high-water, and the freed pages
	 */
	@Nonnull
	private PageEmission<LeafPage> collectChangedPages() {
		publishPreviousFlush();
		// this.cardinalities is a raw bucket tree, so the handle list and its cursors are raw too — bucket keys are read
		// as Object and cast to Long exactly as the whole-tree snapshot does
		final List<LeafPageHandle> handles = this.cardinalities.leafPageHandles();
		return this.pageStreamRegistry.collectChangedPages(
			CARDINALITY_PAGE_STREAM, handles,
			(pageSequence, handle) -> {
				final BucketCursor cursor = handle.cursor();
				final CompositeLongArray pageKeys = new CompositeLongArray();
				final CompositeLongArray pagePayloads = new CompositeLongArray();
				while (cursor.next()) {
					pageKeys.add((Long) cursor.value());
					pagePayloads.add(cursor.longRecordId());
				}
				return new LeafPage(pageSequence, pageKeys.toArray(), pagePayloads.toArray());
			}
		);
	}

	/**
	 * The whole cardinality tree captured as positionally-aligned `(key, count)` primitive columns — the inline `SINGLE`
	 * shape, the same representation a leaf page carries.
	 *
	 * @param keys     the composed signed `long` keys in ascending key order
	 * @param payloads the cardinality counts, positionally aligned with `keys`
	 */
	private record InlineSnapshot(@Nonnull long[] keys, @Nonnull long[] payloads) {
	}

	/**
	 * One changed leaf page of the granular write-path emission: its page sequence and its slim `(key, count)` columns in
	 * ascending key order.
	 *
	 * @param pageSequence the leaf's page sequence within the stream
	 * @param keys         the leaf's composed signed `long` keys in ascending key order
	 * @param payloads     the cardinality count of each key, aligned with `keys`
	 */
	private record LeafPage(int pageSequence, @Nonnull long[] keys, @Nonnull long[] payloads) {
	}

}
