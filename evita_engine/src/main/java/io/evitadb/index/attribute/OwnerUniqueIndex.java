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

package io.evitadb.index.attribute;

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.IndexHeapSize;
import io.evitadb.index.bPlusTree.IntRecordBucketTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.LeafPageHandle;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.collidingKeysError;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.findFirstNonAscendingKey;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.normalizerFor;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.plainTypeOf;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.toDeclaredValue;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.toDistinctKeys;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.toKey;
import static io.evitadb.index.attribute.UniqueIndexBPlusTreeSupport.toPersistedKeys;
import static io.evitadb.utils.Assert.isTrue;

/**
 * Owner variant of {@link UniqueIndex}. It OWNS its value→record-id mappings and fully participates in the commit
 * cycle. Used for a localized attribute unique across locales (`UNIQUE_WITHIN_COLLECTION`), whose locale-less
 * uniqueness cannot be folded into the per-locale shared filter tree.
 *
 * The value to record id relation is kept in a {@link TransactionalBucketBPlusTree} keyed by the unique value, where
 * each bucket holds exactly one record id (uniqueness is enforced on insert, so the bucket's overflow bitmap is never
 * allocated). String keys are stored in a prefix-compressed front-coded leaf column (auto-selected by
 * {@link ValueColumnFactory#forFilterKey}), which is the memory win driving this backing: URL-slug unique attributes
 * share long common prefixes that a hash map cannot exploit. Persistence is granular — large indexes are written as
 * individual {@link UniqueIndexLeafPagePart} leaf pages so a single edit rewrites one ~KB leaf instead of the whole
 * value map (see {@link #appendStorageParts}).
 *
 * The index keys every value exactly as the attribute's filter index does - through
 * {@link FilterIndex#getNormalizer(Class, int)} at the attribute's `indexedDecimalPlaces` - so two values the filter
 * index treats as one are one unique value here too (see {@link UniqueIndexBPlusTreeSupport}). Every entry point
 * converts its value to the key, and the storage parts carry the key as a value of the declared type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class OwnerUniqueIndex extends UniqueIndex {
	@Serial private static final long serialVersionUID = 2639205026498958517L;
	/**
	 * Single page stream per owner unique index — its value bucket tree (mirrors {@code InvertedIndex.BUCKET_PAGE_STREAM}).
	 */
	private static final int UNIQUE_PAGE_STREAM = 0;

	/**
	 * Internal flag that tracks whether the index contents became dirty and need to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * The plain (array-unwrapped) attribute type — drives the normalizer and leaf-column choice for tree rebuilds.
	 */
	@Nonnull private final Class<? extends Serializable> plainType;
	/**
	 * The scale `BigDecimal` and `BigDecimalNumberRange` values are keyed at, frozen when the index is created (or
	 * loaded) from the attribute schema's `indexedDecimalPlaces`; `0` for every other type. A schema that later
	 * declares another scale refuses further writes until the index is rebuilt - see
	 * {@link FilterIndex#assertIndexedDecimalPlacesUnchanged}.
	 */
	@Getter private final int indexedDecimalPlaces;
	/**
	 * Converts every value to its key in {@link #tree} - the filter index's normalizer for {@link #plainType} at
	 * {@link #indexedDecimalPlaces}, see {@link UniqueIndexBPlusTreeSupport#normalizerFor}.
	 */
	@Nonnull private final Function<Object, Serializable> normalizer;
	/**
	 * Keeps the unique value to record id mappings. Each bucket holds exactly one record id; for String keys the leaf
	 * column is front-coded. Large trees persist granularly as leaf pages (see {@link #pageStreamRegistry}).
	 */
	@Nonnull private final IntRecordBucketTree tree;
	/**
	 * Owner-resident page bookkeeping for the granular leaf-page storage layout (the advance-only page allocator, the
	 * high-water and the live-page set of {@link #tree}). It lives OUTSIDE transactional memory and is carried BY
	 * REFERENCE through {@link #createCopyWithMergedTransactionalMemory}, exactly like {@code InvertedIndex}.
	 */
	@Nonnull private final PageStreamRegistry pageStreamRegistry;

	/**
	 * Creates a fresh, empty value tree (int payload column holding the owning record id) keyed like the filter index
	 * — see {@link UniqueIndexBPlusTreeSupport#newIntPayloadTree}.
	 *
	 * @param plainType            the plain (array-unwrapped) attribute type
	 * @param indexedDecimalPlaces the scale `BigDecimal` and `BigDecimalNumberRange` keys are encoded at
	 * @return the fresh empty int-payload bucket tree
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree createEmptyTree(@Nonnull Class<?> plainType, int indexedDecimalPlaces) {
		return UniqueIndexBPlusTreeSupport.newIntPayloadTree(plainType, indexedDecimalPlaces);
	}

	/**
	 * Creates an empty index for a freshly encountered attribute - the entry point when an entity introduces a
	 * unique attribute that has not been indexed yet.
	 *
	 * @param entityType           type of the entity this index belongs to
	 * @param attributeIndexKey    key identifying the indexed attribute
	 * @param attributeType        declared type of the attribute value
	 * @param indexedDecimalPlaces the attribute schema's `indexedDecimalPlaces` (the scale `BigDecimal` values are
	 *                             keyed at; ignored for other types)
	 */
	public OwnerUniqueIndex(
		@Nonnull String entityType,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> attributeType,
		int indexedDecimalPlaces
	) {
		super(entityType, attributeIndexKey, attributeType);
		this.dirty = new TransactionalBoolean();
		this.plainType = plainTypeOf(attributeType);
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.normalizer = normalizerFor(this.plainType, indexedDecimalPlaces);
		this.tree = createEmptyTree(this.plainType, indexedDecimalPlaces);
		this.pageStreamRegistry = new PageStreamRegistry();
	}

	/**
	 * Reconstructs a `SINGLE`-shape index from its persisted inline value/payload columns - the path taken when loading
	 * an inline (SINGLE) index back from storage. The tree is rebuilt by inserting every `(value, recordId)` pair, each
	 * value converted to its key; a pair of values naming one key refuses to load (see {@link #seedTree}).
	 *
	 * @param entityType           type of the entity this index belongs to
	 * @param attributeIndexKey    key identifying the indexed attribute
	 * @param attributeType        declared type of the attribute value
	 * @param indexedDecimalPlaces the attribute schema's `indexedDecimalPlaces`
	 * @param values               restored unique values
	 * @param recordIds            restored record ids, positionally aligned with `values`
	 */
	public OwnerUniqueIndex(
		@Nonnull String entityType,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> attributeType,
		int indexedDecimalPlaces,
		@Nonnull Serializable[] values,
		@Nonnull int[] recordIds
	) {
		this(entityType, attributeIndexKey, attributeType, indexedDecimalPlaces);
		seedTree(values, recordIds);
	}

	/**
	 * Private constructor used by {@link #createCopyWithMergedTransactionalMemory} and {@link #fromPersistedPages} to
	 * wrap an already-built tree directly (no re-seeding). The committed/assembled tree already carries its column kind
	 * and leaf page sequences, so the page bookkeeping is carried by reference.
	 *
	 * @param entityType           type of the entity this index belongs to
	 * @param attributeIndexKey    key identifying the indexed attribute
	 * @param attributeType        declared type of the attribute value
	 * @param indexedDecimalPlaces the scale the keys of `tree` are encoded at
	 * @param tree                 the already-built value tree
	 * @param pageStreamRegistry   the owner-resident page bookkeeping, carried BY REFERENCE
	 */
	private OwnerUniqueIndex(
		@Nonnull String entityType,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> attributeType,
		int indexedDecimalPlaces,
		@Nonnull TransactionalBucketBPlusTree tree,
		@Nonnull PageStreamRegistry pageStreamRegistry
	) {
		super(entityType, attributeIndexKey, attributeType);
		this.dirty = new TransactionalBoolean();
		this.plainType = plainTypeOf(attributeType);
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.normalizer = normalizerFor(this.plainType, indexedDecimalPlaces);
		this.tree = tree;
		this.pageStreamRegistry = pageStreamRegistry;
	}

	/**
	 * Rebuilds a `PAGED` owner unique index from its persisted leaf pages, preserving the original leaf boundaries and
	 * page identities (mirrors {@code InvertedIndex.fromPersistedPages}). One leaf per persisted page is built, each
	 * stamped with its page sequence, and the page-stream bookkeeping (high-water + live set) is restored, so the first
	 * post-restart commit rewrites only genuinely-changed leaves rather than re-paginating the whole index.
	 *
	 * Every persisted value is converted to its key first. A page whose keys do not come out strictly ascending is
	 * refused, and when the cause is two values naming one key the error names both records (see
	 * {@link UniqueIndexBPlusTreeSupport#collidingKeysError}).
	 *
	 * @param entityType            type of the entity this index belongs to
	 * @param attributeIndexKey     key identifying the indexed attribute
	 * @param attributeType         declared type of the attribute value
	 * @param indexedDecimalPlaces  the attribute schema's `indexedDecimalPlaces`
	 * @param orderedPageSequences  the persisted leaf-page sequences in ascending key order
	 * @param perPageValues         the values of each leaf page, positionally aligned with `orderedPageSequences`
	 * @param perPageRecordIds      the record ids of each leaf page, positionally aligned with `perPageValues`
	 * @param highWaterPageSequence the persisted stream high-water (largest page sequence ever allocated)
	 * @return the rebuilt, boundary-stable `PAGED` owner unique index
	 */
	@Nonnull
	public static OwnerUniqueIndex fromPersistedPages(
		@Nonnull String entityType,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> attributeType,
		int indexedDecimalPlaces,
		@Nonnull int[] orderedPageSequences,
		@Nonnull Serializable[][] perPageValues,
		@Nonnull int[][] perPageRecordIds,
		int highWaterPageSequence
	) {
		Assert.isPremiseValid(
			orderedPageSequences.length == perPageValues.length && perPageValues.length == perPageRecordIds.length,
			"The number of page sequences must match the number of leaf-page arrays."
		);
		Assert.isPremiseValid(orderedPageSequences.length > 0, "A paged owner unique index must have at least one leaf page.");
		final Class<?> plainType = plainTypeOf(attributeType);
		final Function<Object, Serializable> normalizer = normalizerFor(plainType, indexedDecimalPlaces);
		final List<TransactionalBucketBPlusTree> pageTrees = new ArrayList<>(orderedPageSequences.length);
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final Object[] keys = toPersistedKeys(normalizer, perPageValues[i]);
			final int[] records = perPageRecordIds[i];
			// a page never exceeds a leaf's capacity, so this single-leaf tree never splits; bulk-build the leaf's
			// columns in one pass instead of `values.length` sequential addRecord calls - see
			// bulkLoadSingleRecordPage's javadoc. An owner-unique index page structurally cannot hold a value
			// shared by more than one record (uniqueness is enforced one layer up, at registerUniqueKey), so no
			// overflow bucket is ever needed here
			final TransactionalBucketBPlusTree pageTree = createEmptyTree(plainType, indexedDecimalPlaces);
			final long[] payloads = new long[keys.length];
			for (int j = 0; j < keys.length; j++) {
				payloads[j] = records[j];
			}
			try {
				pageTree.bulkLoadSingleRecordPage(keys, payloads, keys.length);
			} catch (GenericEvitaInternalError ex) {
				// the load refused keys that are not strictly ascending - name the records when two of them collide
				final int clash = findFirstNonAscendingKey(keys);
				if (clash > 0 && UniqueIndexBPlusTreeSupport.KEY_ORDER.compare(
					(Comparable<?>) keys[clash - 1], (Comparable<?>) keys[clash]) == 0) {
					throw collidingKeysError(
						describe(attributeIndexKey, entityType), keys[clash], plainType, indexedDecimalPlaces,
						"record `" + records[clash - 1] + "`", "record `" + records[clash] + "`"
					);
				}
				throw ex;
			}
			pageTrees.add(pageTree);
		}
		final TransactionalBucketBPlusTree tree =
			createEmptyTree(plainType, indexedDecimalPlaces).assembleFromSingleLeafTrees(
				pageTrees, orderedPageSequences,
				"owner unique index for attribute " + attributeIndexKey + " of entity `" + entityType + "`"
			);
		final PageStreamRegistry pageStreamRegistry = PageStreamRegistry.restoredFrom(
			UNIQUE_PAGE_STREAM, highWaterPageSequence, tree.leafPageHandles()
		);
		return new OwnerUniqueIndex(
			entityType, attributeIndexKey, attributeType, indexedDecimalPlaces, tree, pageStreamRegistry
		);
	}

	@Override
	public void registerUniqueKey(@Nonnull Object value, int recordId) {
		registerUniqueKeyValue(value, recordId);
	}

	@Override
	public int unregisterUniqueKey(@Nonnull Object value, int recordId) {
		return unregisterUniqueKeyValue(value, recordId);
	}

	@Nullable
	@Override
	public Integer getRecordIdByUniqueValue(@Nonnull Serializable value) {
		final Bitmap records = this.tree.getRecordsEqualTo((Comparable) toKey(this.normalizer, value));
		return records.isEmpty() ? null : records.getFirst();
	}

	/**
	 * {@inheritDoc}
	 *
	 * # What is charged, and what is not
	 *
	 * The value tree is charged in full, its **keys included** — they are attribute values this index owns, priced by
	 * {@link IndexHeapSize#OWNED_KEY_SIZER}. No record-id set is kept beside it - the owning records are read off the
	 * tree's payloads when {@link #size()} is asked for them - so the tree is the whole of the data this index holds.
	 *
	 * {@link #plainType} is a `Class` and {@link #normalizer} is fixed scaffolding chosen by the attribute type, so
	 * both contribute their slot alone — the same call {@code FilterIndex} and {@code SortIndex} make, for the same
	 * reason. {@link #pageStreamRegistry} is excluded as single-writer flush bookkeeping.
	 */
	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the dirty / plainType / normalizer / tree / pageStreamRegistry slots, then the indexedDecimalPlaces int
		return getSharedHeapSizeInBytes(5L * layout.referenceSize() + Integer.BYTES)
			+ this.dirty.getHeapSizeInBytes()
			+ this.tree.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER);
	}

	/**
	 * {@inheritDoc}
	 *
	 * Counted on demand as the distinct owners of the tree's values: a record owns one value per locale here, so the
	 * value count alone would over-count it. The cost is one cursor walk over the tree plus a transient bitmap of the
	 * owners, `O(values)` rather than `O(1)`, which is why only the index statistics call it - never a query path.
	 *
	 * A walk racing an in-place warm-up write reads a bounded, possibly slightly stale picture rather than failing:
	 * the bucket cursors bound every leaf read by its observable live run for exactly this session-free management
	 * reader (see `documentation/adr/2026-09-03-content-sized-value-tree-columns.md`).
	 */
	@Override
	public int size() {
		final BaseBitmap owners = new BaseBitmap();
		final BucketCursor cursor = this.tree.cursor();
		while (cursor.next()) {
			owners.add(cursor.singleRecordId());
		}
		return owners.size();
	}

	@Override
	public int getDistinctValueCount() {
		return this.tree.size();
	}

	@Override
	public boolean isEmpty() {
		// the index is empty exactly when no value remains, for any record and any locale: a localized attribute unique
		// across locales has a locale-less key, so one record owns a distinct value per locale here. The tree's own
		// `size()` is an O(1) value counter, unlike this index's record-counting `size()`
		return this.tree.size() == 0;
	}

	/**
	 * Returns whether this index's value tree spans more than one leaf and is therefore persisted in the granular
	 * `PAGED` shape (one record per leaf) rather than the inline `SINGLE` shape.
	 *
	 * @return true when the tree has an internal root (≥ 2 leaves)
	 */
	public boolean isPaged() {
		return this.tree.isRootInternal();
	}

	@Override
	public void appendStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink) {
		if (!this.dirty.isTrue()) {
			return;
		}
		final AttributeKeyWithIndexType streamKey =
			new AttributeKeyWithIndexType(getAttributeIndexKey(), AttributeIndexType.UNIQUE);
		if (this.tree.isRootInternal()) {
			// PAGED: one leaf page per CHANGED leaf + a removal per freed leaf + a PAGED root carrying the high-water and
			// the ordered live leaf-page list
			final PageEmission<LeafPage> emission = collectChangedPages();
			for (final LeafPage page : emission.changedPages()) {
				sink.addChangeToStore(
					new UniqueIndexLeafPagePart(
						entityIndexPrimaryKey, streamKey, page.pageSequence(), page.values(), page.recordIds()
					)
				);
			}
			for (final int freedPageSequence : emission.freedPageSequences()) {
				sink.addChangeToStore(new UniqueIndexLeafPageRemoval(entityIndexPrimaryKey, streamKey, freedPageSequence));
			}
			// the PAGED root carries only the high-water + ordered live leaf-page list (plus the immutable value type), so
			// it needs rewriting only when that list changed (a leaf was allocated or freed). A commit that just mutated
			// leaf CONTENT leaves the persisted root byte-identical — skip it, collapsing the steady-state root cost to O(1)
			if (emission.pageListChanged()) {
				sink.addChangeToStore(
					UniqueIndexStoragePart.paged(
						entityIndexPrimaryKey, getAttributeIndexKey(), getType(),
						emission.highWaterPageSequence(), emission.orderedPageSequences(), null
					)
				);
			}
		} else {
			// SINGLE shape: the index spans one leaf. If it just collapsed from PAGED, remove every prior leaf page (the
			// inline root no longer references them) BEFORE dropping the bookkeeping, then forget the stream so a later
			// regrow into PAGED starts from a clean baseline and re-emits every leaf.
			// Reclaim against what the previous flush left ON DISK: its staged set while still unpublished (a warm-up
			// flush never reaches the commit-merge that publishes), else the published set. The published set alone lags a
			// whole flush behind, so every page of the collapsed stream would leak — the append-only OffsetIndex never
			// reclaims a record that is neither superseded nor explicitly removed.
			for (final int freedPageSequence : this.pageStreamRegistry.pendingLivePageSequences(UNIQUE_PAGE_STREAM)) {
				sink.addChangeToStore(new UniqueIndexLeafPageRemoval(entityIndexPrimaryKey, streamKey, freedPageSequence));
			}
			this.pageStreamRegistry.forget(UNIQUE_PAGE_STREAM);
			// the small index is a single embedded leaf: capture its value/payload columns directly off the tree (no map
			// materialization) and carry them inline on the root, exactly as a leaf page would
			final InlineSnapshot snapshot = inlineSnapshot();
			sink.addChangeToStore(
				new UniqueIndexStoragePart(
					entityIndexPrimaryKey, getAttributeIndexKey(), getType(), snapshot.values(), snapshot.recordIds()
				)
			);
		}
	}

	@Nonnull
	@Override
	public int[] currentLeafPageSequences() {
		return this.pageStreamRegistry.pendingLivePageSequences(UNIQUE_PAGE_STREAM);
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	@Nonnull
	@Override
	public UniqueIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		final boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			final TransactionalBucketBPlusTree committedTree =
				(TransactionalBucketBPlusTree) transactionalLayer.getStateCopyWithCommittedChanges(this.tree);
			// publish the page baseline staged by this commit's flush: the merge runs only AFTER the flush has durably
			// written the changed leaf pages + root, so the staged live set now reflects what is on disk. The registry is
			// then carried BY REFERENCE into the committed copy, so the surviving owner keeps it (mirrors InvertedIndex).
			this.pageStreamRegistry.publishStaged();
			return new OwnerUniqueIndex(
				getEntityType(), getAttributeIndexKey(), getType(), this.indexedDecimalPlaces, committedTree,
				this.pageStreamRegistry
			);
		} else {
			return this;
		}
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this.dirty);
		this.tree.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	UniqueIndex bindFilterView(@Nullable FilterIndex committedFilterView) {
		// an owner index owns its own value→record map and reads nothing from a shared filter view — the bind target is
		// irrelevant, so it carries forward unchanged (mirrors OwnerSortIndex.bindSharedTree)
		return this;
	}

	@Nonnull
	@Override
	InlineSnapshot inlineSnapshot() {
		final CompositeObjectArray<Serializable> snapshotValues = new CompositeObjectArray<>(Serializable.class);
		final CompositeIntArray snapshotRecordIds = new CompositeIntArray();
		final BucketCursor cursor = this.tree.cursor();
		while (cursor.next()) {
			Assert.isPremiseValid(cursor.isSingle(), "A unique index bucket must hold exactly one record!");
			snapshotValues.add(toDeclaredValue(this.plainType, this.indexedDecimalPlaces, cursor.value()));
			snapshotRecordIds.add(cursor.singleRecordId());
		}
		return new InlineSnapshot(snapshotValues.toArray(), snapshotRecordIds.toArray());
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Inserts every persisted `(value, recordId)` pair (positionally-aligned value/payload columns) into the (fresh)
	 * tree, converting each value to its key. Used by the SINGLE load constructor. The keys are distinct, so no
	 * overflow bitmap is ever allocated - and a pair that is not refuses to load: a key already present joins its
	 * bucket instead of adding one, which the O(1) bucket count tells right away (see
	 * {@link UniqueIndexBPlusTreeSupport#collidingKeysError}).
	 *
	 * @param values    the persisted values
	 * @param recordIds the persisted record ids, positionally aligned with `values`
	 */
	private void seedTree(@Nonnull Serializable[] values, @Nonnull int[] recordIds) {
		final Object[] keys = toPersistedKeys(this.normalizer, values);
		for (int i = 0; i < keys.length; i++) {
			this.tree.addRecord((Comparable) keys[i], recordIds[i]);
			if (this.tree.size() != i + 1) {
				final Bitmap owners = this.tree.getRecordsEqualTo((Comparable) keys[i]);
				final int otherRecordId = owners.getFirst() == recordIds[i] ? owners.getLast() : owners.getFirst();
				throw collidingKeysError(
					describe(getAttributeIndexKey(), getEntityType()), keys[i], this.plainType,
					this.indexedDecimalPlaces, "record `" + otherRecordId + "`", "record `" + recordIds[i] + "`"
				);
			}
		}
	}

	/**
	 * Describes the index for an operator: the attribute, its locale when it has one, and the entity type.
	 *
	 * @param attributeIndexKey the indexed attribute
	 * @param entityType        the entity type the index belongs to
	 * @return the description
	 */
	@Nonnull
	private static String describe(@Nonnull AttributeIndexKey attributeIndexKey, @Nonnull String entityType) {
		return "unique index of attribute `" + attributeIndexKey.attributeName() + "`" +
			(attributeIndexKey.locale() == null ? "" : " in locale `" + attributeIndexKey.locale().toLanguageTag() + "`") +
			(attributeIndexKey.referenceName() == null ? "" : " of reference `" + attributeIndexKey.referenceName() + "`") +
			" of entity `" + entityType + "`";
	}

	/**
	 * Promotes the page set staged by the PREVIOUS flush to the live change-detection baseline, so this flush's freed
	 * -page diff and root re-emission decision are taken against what disk actually holds.
	 *
	 * {@link #pageStreamRegistry}'s live set answers "which leaf pages does this stream have on disk". {@link
	 * #collectChangedPages()} derives two things from it: which pages a leaf merge freed (so a {@link
	 * UniqueIndexLeafPageRemoval} is emitted and the page is actually dropped from storage, rather than left as an
	 * unreferenced record the append-only OffsetIndex would copy forward forever) and whether the ordered leaf-page
	 * list changed at all (so the `PAGED` root carrying it is re-emitted instead of skipped as unchanged). That live
	 * set only ever advances by {@link PageStreamRegistry#publishStaged()}, which {@link
	 * #createCopyWithMergedTransactionalMemory} calls at the transactional commit-merge.
	 *
	 * A WARM_UP (bulk) flush never reaches a commit-merge — it runs the same collect path but is never wrapped in a
	 * transaction, so nothing ever calls {@code createCopyWithMergedTransactionalMemory} for it. Left alone, the live
	 * set of a freshly re-indexed catalog would stay EMPTY for the whole warm-up while disk moved on underneath it. A
	 * leaf MERGE (unlike a split) drops a page without creating one: the surviving leaf absorbs its sibling IN PLACE,
	 * keeping its own page sequence and dirty flag, so nothing is freshly allocated. With an empty live baseline the
	 * freed-page diff for that merge is vacuously empty and the list-changed check sees no change either, so the
	 * dropped page is neither removed from storage nor dropped from the persisted root's leaf list. On the next cold
	 * load the tree is assembled from the surviving leaf (holding the absorbed keys) followed by its stale,
	 * still-listed sibling, whose first key no longer sorts after the survivor's last — the cross-leaf overlap check
	 * then fails fast.
	 *
	 * Publishing HERE, before every flush rather than only at the commit-merge, is safe for every path because a failed
	 * flush is never followed by another flush of the same data. This call runs at COLLECT time, before this flush has
	 * written anything (the baseline-capture pass re-enters the collect path), so it cannot rest on the previous
	 * flush's bytes having landed — and it does not need to. A flush that fails during trunk incorporation SUSPENDS the
	 * catalog's transaction processing ({@code TransactionManager.suspend}); a flush that fails during warm-up POISONS
	 * the catalog unpublishable ({@code Catalog.markUnpublishable}), so every later flush of it refuses
	 * deterministically. The two are the same invariant in different dresses: after a failed flush nothing ever diffs
	 * against the baseline it left behind, because no later flush of that data runs at all. Whatever a SUCCEEDING flush
	 * leaves staged is exactly the page set it wrote, regardless of whether a commit-merge ever ran for it. (If the
	 * process crashes instead, the registry itself is gone and gets rebuilt from disk on restart, where a burnt page
	 * sequence is harmless since allocation is advance-only.) On the transactional path this call is simply a no-op
	 * (the merge already published, so nothing is left staged) — that is a side effect, not the reason it is safe.
	 */
	private void publishPreviousFlush() {
		this.pageStreamRegistry.publishStaged();
	}

	/**
	 * Walks the value tree leaf-by-leaf and returns the granular write-path emission for this commit: the leaf pages
	 * that changed since the last flush, the full ordered list of live leaf-page sequences (the `PAGED` root's leaf
	 * list), the stream high-water, and the freed page sequences a leaf merge dropped. Mirrors
	 * {@code InvertedIndex.collectChangedPages} with the slim value+pk payload.
	 *
	 * Before staging, any set still staged by the PREVIOUS flush is promoted to live — see
	 * {@link #publishPreviousFlush()} for why that is both necessary and safe.
	 *
	 * @return the changed leaf pages, the ordered live page-sequence list, the high-water, and the freed pages
	 */
	@Nonnull
	private PageEmission<LeafPage> collectChangedPages() {
		publishPreviousFlush();
		// this.tree is a raw bucket tree, so the handle list and its cursors are raw too — bucket values are read as
		// Object and cast to Serializable exactly as the whole-tree snapshot does
		final List<LeafPageHandle> handles = this.tree.leafPageHandles();
		return this.pageStreamRegistry.collectChangedPages(
			UNIQUE_PAGE_STREAM, handles,
			(pageSequence, handle) -> {
				final BucketCursor cursor = handle.cursor();
				final CompositeObjectArray<Serializable> pageValues = new CompositeObjectArray<>(Serializable.class);
				final CompositeIntArray pageRecordIds = new CompositeIntArray();
				while (cursor.next()) {
					Assert.isPremiseValid(cursor.isSingle(), "A unique index bucket must hold exactly one record!");
					pageValues.add(toDeclaredValue(this.plainType, this.indexedDecimalPlaces, cursor.value()));
					pageRecordIds.add(cursor.singleRecordId());
				}
				return new LeafPage(pageSequence, pageValues.toArray(), pageRecordIds.toArray());
			}
		);
	}

	/**
	 * Array-dispatching entry point for registration. Every value is converted to its key first. When `key` is an
	 * array (an array-typed attribute), every element is first checked for a conflicting owner and only then
	 * registered, so a violation on any element aborts the whole operation before mutating the index. The array is
	 * folded onto its distinct keys first, so a value the array repeats - in any spelling - occupies its single tree
	 * entry once. Scalar keys are delegated straight to the single-value overload. Finally marks the index dirty.
	 *
	 * @param key      single unique value or an array of unique values to register
	 * @param recordId record id that should own the value(s)
	 * @throws UniqueValueViolationException when any value is already owned by any record, this one included
	 */
	private <T extends Serializable & Comparable<T>> void registerUniqueKeyValue(@Nonnull Object key, int recordId) {
		if (key instanceof @Nonnull final Object[] valueArray) {
			// one value is one tree entry however many times the array repeats it - see #foldOntoDistinctValues
			final Object[] distinctKeys = toDistinctKeys(this.normalizer, valueArray);
			// first verify removed data without modifications
			for (Object keyItem : distinctKeys) {
				final T theKeyItem = (T) keyItem;
				assertUniqueKeyIsFree(theKeyItem, recordId, getRecordIdByKey(theKeyItem));
			}
			// now perform alteration
			for (Object keyItem : distinctKeys) {
				//noinspection unchecked
				registerUniqueKeyValue((T) keyItem, recordId);
			}
		} else {
			//noinspection unchecked
			registerUniqueKeyValue((T) toKey(this.normalizer, key), recordId);
		}

		this.dirty.setToTrue();
	}

	/**
	 * Registers a single unique key to a record id after asserting the key is free.
	 *
	 * @param key      unique key to register, already converted by {@link UniqueIndexBPlusTreeSupport#toKey}
	 * @param recordId record id that should own the key
	 * @throws UniqueValueViolationException when the key is already owned by any record
	 */
	private <T extends Serializable & Comparable<T>> void registerUniqueKeyValue(@Nonnull T key, int recordId) {
		assertUniqueKeyIsFree(key, recordId, getRecordIdByKey(key));
		this.tree.addRecord(key, recordId);
	}

	/**
	 * Returns the record owning an already converted key, or `null` when the key is free.
	 *
	 * @param key the tree key
	 * @return the owning record id, or `null`
	 */
	@Nullable
	private Integer getRecordIdByKey(@Nonnull Comparable<?> key) {
		final Bitmap records = this.tree.getRecordsEqualTo((Comparable) key);
		return records.isEmpty() ? null : records.getFirst();
	}

	/**
	 * Array-dispatching entry point for de-registration. Every value is converted to its key first. When `key` is an
	 * array, every element's ownership is first verified and only then removed, so a mismatch on any element aborts
	 * the operation before mutating the index; the array branch returns {@link Integer#MIN_VALUE} as a sentinel since
	 * no single record id applies. The array is folded onto its distinct keys first, so a value the array repeats - in
	 * any spelling - is retired once rather than being sought a second time after its only entry is gone.
	 * Scalar keys are delegated to the single-value overload and return the removed record id. Finally marks the
	 * index dirty.
	 *
	 * @param key              single unique value or an array of unique values to unregister
	 * @param expectedRecordId record id expected to currently own the value(s)
	 * @return the removed record id for a scalar key, or {@link Integer#MIN_VALUE} for the array branch
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when any value is absent or owned by a different record
	 */
	private <T extends Serializable & Comparable<T>> int unregisterUniqueKeyValue(@Nonnull Object key, int expectedRecordId) {
		final int returnValue;
		if (key instanceof @Nonnull final Object[] valueArray) {
			// one value is one tree entry however many times the array repeats it - see #foldOntoDistinctValues
			final Object[] distinctKeys = toDistinctKeys(this.normalizer, valueArray);
			// first verify removed data without modifications
			for (Object keyItem : distinctKeys) {
				final T theKeyItem = (T) keyItem;
				assertUniqueKeyOwnership(theKeyItem, expectedRecordId, getRecordIdByKey(theKeyItem));
			}
			// now perform alteration
			for (Object keyItem : distinctKeys) {
				unregisterUniqueKeyValue((T) keyItem, expectedRecordId);
			}

			returnValue = Integer.MIN_VALUE;
		} else {
			returnValue = unregisterUniqueKeyValue((T) toKey(this.normalizer, key), expectedRecordId);
		}

		this.dirty.setToTrue();
		return returnValue;
	}

	/**
	 * Removes a single unique key, asserting it was owned by `expectedRecordId`. The ownership assertion
	 * guarantees the removed mapping was non-null and equal to `expectedRecordId`, so beyond it the boxed
	 * `existingRecordId` and the primitive `expectedRecordId` are interchangeable; the primitive is used to
	 * avoid unboxing the (provably non-null) {@link Integer}.
	 *
	 * @param key              unique key to unregister, already converted by {@link UniqueIndexBPlusTreeSupport#toKey}
	 * @param expectedRecordId record id expected to currently own the value
	 * @return the removed record id (always equal to `expectedRecordId`)
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the value is absent or owned by a different record
	 */
	private <T extends Serializable & Comparable<T>> int unregisterUniqueKeyValue(@Nonnull T key, int expectedRecordId) {
		final Integer existingRecordId = getRecordIdByKey(key);
		// this throws unless existingRecordId is non-null AND equals expectedRecordId, so past this point the two
		// are interchangeable; using the primitive expectedRecordId avoids unboxing the (provably non-null) Integer
		assertUniqueKeyOwnership(key, expectedRecordId, existingRecordId);
		this.tree.removeRecord(key, expectedRecordId);
		return expectedRecordId;
	}

	/**
	 * Enforces the registration invariant: a value may be claimed only when it is currently unowned - **even by the
	 * record that already owns it**.
	 *
	 * This index serves only a localized attribute unique across locales, so its key carries no locale and a record's
	 * values in different locales all land here. A value it already owns therefore arrives from another locale (or
	 * from a second reference carrying the same value), and that is the second occurrence the uniqueness contract
	 * forbids: `unique` means once per collection, whatever the locale. Tolerating it would also leave one tree entry
	 * standing for two registrations, so unregistering either would drop the value the other still holds. The upsert
	 * path unregisters a record's prior value before registering its new one, so an unchanged value never re-arrives.
	 *
	 * @param key              key being registered (shown as a value of the declared type in the violation message)
	 * @param recordId         record id attempting to claim the value
	 * @param existingRecordId record id currently owning the value, or `null` if the value is free
	 * @throws UniqueValueViolationException when the value is already owned by any record, this one included
	 */
	private <T extends Serializable & Comparable<T>> void assertUniqueKeyIsFree(@Nonnull T key, int recordId, @Nullable Integer existingRecordId) {
		if (existingRecordId != null) {
			throw new UniqueValueViolationException(
				getAttributeIndexKey().attributeName(), getAttributeIndexKey().locale(),
				toDeclaredValue(this.plainType, this.indexedDecimalPlaces, key),
				getEntityType(), existingRecordId, getEntityType(), recordId
			);
		}
	}

	/**
	 * Enforces the de-registration invariant: the value must currently exist and be owned by exactly
	 * `expectedRecordId`. The failure message distinguishes a missing key (`existingRecordId` is `null`) from a key
	 * owned by a different record.
	 *
	 * @param theKey           key being unregistered (shown as a value of the declared type in the failure message)
	 * @param expectedRecordId record id expected to own the value
	 * @param existingRecordId record id actually found, or `null` if the value was absent
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the value is absent or owned by a different record
	 */
	private <T extends Serializable & Comparable<T>> void assertUniqueKeyOwnership(@Nonnull T theKey, int expectedRecordId, @Nullable Integer existingRecordId) {
		final Serializable key = toDeclaredValue(this.plainType, this.indexedDecimalPlaces, theKey);
		isTrue(
			Objects.equals(existingRecordId, expectedRecordId),
			() -> existingRecordId == null ?
				"No unique key exists for `" + getAttributeIndexKey().attributeName() + "` key: `" + key + "`" + (getAttributeIndexKey().locale() == null ? "" : " in locale `" + getAttributeIndexKey().locale().toLanguageTag() + "`") + "!" :
				"Unique key exists for `" + getAttributeIndexKey().attributeName() + "` key: `" + key + "`" + (getAttributeIndexKey().locale() == null ? "" : " in locale `" + getAttributeIndexKey().locale().toLanguageTag() + "`") + " belongs to record with id `" + existingRecordId + "` and not `" + expectedRecordId + "` as expected!"
		);
	}

	/**
	 * One changed leaf page of the granular write-path emission: its page sequence and its slim `(value, recordId)`
	 * columns in ascending key order.
	 *
	 * @param pageSequence the leaf's page sequence within the stream
	 * @param values       the leaf's values in ascending key order
	 * @param recordIds    the single record id owning each value, aligned with `values`
	 */
	private record LeafPage(int pageSequence, @Nonnull Serializable[] values, @Nonnull int[] recordIds) {
	}

}
