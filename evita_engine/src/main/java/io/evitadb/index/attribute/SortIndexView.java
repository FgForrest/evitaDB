/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Iterator;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * View variant of {@link SortIndex}. It owns ONLY its sort-specific {@link SortIndex#sortedRecords} ordering and sources
 * everything value-related — the ordered distinct values, per-value cardinality, value-space comparator and normalizer —
 * from a shared {@link InvertedIndex} owned by {@link AttributeIndex} (the `sharedValueIndex` map). This is the variant
 * for a both-filterable-and-sortable single attribute: the filter side already holds a `value → records` tree, so the
 * sort index need not duplicate it and instead folds onto it.
 *
 * It is STILL a {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} — it commits its own
 * {@link SortIndex#sortedRecords} façade — so it lives in {@link AttributeIndex}'s transactional `sortIndex` map exactly
 * like an owner, unlike the non-producer filter/unique views. The value-side abstract hooks of {@link SortIndex} all
 * delegate to the shared tree (or no-op, since the shared tree's mutations are owned by the FILTER block).
 *
 * The shared tree is held as a DIRECT, immutable reference — never mutated in place. It is resolved once at view
 * creation (the view only ever exists when the tree already exists; an absent tree yields an {@link OwnerSortIndex}
 * instead) and re-established at every commit by {@link AttributeIndex} producing a FRESH view-copy that wraps the
 * just-committed tree (carried forward by reference when the tree is identity-unchanged — the common case). Because the
 * reference is never reassigned on a published instance, a committed view shared with an older live snapshot can never be
 * made to observe a newer tree (no snapshot-isolation hazard). Within a transaction the held instance stays valid: writes
 * flow through the tree's own diff layer, and a both-flagged remove that empties and drops the tree leaves the instance
 * orphaned-but-readable (an emptied tree), which every read tolerates exactly as it tolerates a `null` shared tree.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
public final class SortIndexView extends SortIndex {
	@Serial private static final long serialVersionUID = 8197667840221351901L;
	/**
	 * Direct reference to the shared {@link InvertedIndex} for the same attribute key, owned by {@link AttributeIndex}.
	 * Immutable per instance — a commit produces a fresh view-copy bound to the committed tree rather than reassigning
	 * this field, so the reference is safe to share across snapshot versions. Transient: a deserialized view is re-bound
	 * by the loader (through {@link AttributeIndex}) before use.
	 */
	@Nullable private final InvertedIndex sharedTree;

	/**
	 * Creates a fresh, empty view sort index for a single attribute whose values are sourced from the shared tree the
	 * supplier resolves. Uses the single-attribute default ASC/NULLS_LAST ordering, which is the precondition for the
	 * shared tree's ascending natural/localized order to coincide with this index's comparator.
	 *
	 * @param attributeType     the comparable type of the indexed attribute
	 * @param referenceKey      discriminator of the owning {@link io.evitadb.index.AbstractReducedEntityIndex}, or `null`
	 *                          for the global {@link io.evitadb.index.GlobalEntityIndex}
	 * @param attributeIndexKey identifies the indexed attribute (and its locale, if localized)
	 * @param sharedTree        the shared tree this view folds onto (resolved once at creation; non-null for a view)
	 */
	SortIndexView(
		@Nonnull Class<?> attributeType,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		int indexedDecimalPlaces,
		@Nonnull InvertedIndex sharedTree
	) {
		super(
			new ComparatorSource[]{
				new ComparatorSource(attributeType, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			},
			referenceKey,
			attributeIndexKey,
			indexedDecimalPlaces,
			new TransactionalUnorderedIntArray()
		);
		this.sharedTree = sharedTree;
	}

	/**
	 * Rehydrates a view sort index from its persisted (slim) state: the persisted `sortedRecordValues` / `cardinalities`
	 * are omitted from a view storage part and the value ordering / cardinality is sourced from the shared tree instead.
	 * `sortedRecords` is taken as-is.
	 *
	 * @param comparatorBase    one descriptor per element (a single entry for plain attributes)
	 * @param referenceKey      owning reference discriminator, or `null` for the global index
	 * @param attributeIndexKey identifies the indexed attribute
	 * @param sortedRecords     record ids ordered by their associated values, blocked per value
	 * @param sharedTree        the shared tree this view folds onto (resolved once at load; non-null for a view)
	 */
	SortIndexView(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		int indexedDecimalPlaces,
		@Nonnull int[] sortedRecords,
		@Nonnull InvertedIndex sharedTree
	) {
		super(
			comparatorBase, referenceKey, attributeIndexKey, indexedDecimalPlaces,
			new TransactionalUnorderedIntArray(sortedRecords)
		);
		this.sharedTree = sharedTree;
	}

	/**
	 * Internal constructor used by {@link #copyWithMergedValueSide} and {@link #bindSharedTree} to wrap an
	 * already-merged (committed) sorted-records façade directly with a given shared-tree reference.
	 */
	private SortIndexView(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		int indexedDecimalPlaces,
		@Nonnull TransactionalUnorderedIntArray sortedRecords,
		@Nullable InvertedIndex sharedTree
	) {
		super(comparatorBase, referenceKey, attributeIndexKey, indexedDecimalPlaces, sortedRecords);
		this.sharedTree = sharedTree;
	}

	@Nonnull
	@Override
	protected UnaryOperator<Serializable> effectiveNormalizer() {
		final InvertedIndex shared = this.sharedTree;
		if (shared != null) {
			final Function<Object, Serializable> sharedNormalizer = shared.getNormalizer();
			return sharedNormalizer::apply;
		}
		// shared tree transiently absent - fall back to this index's own normalizer; the value is only used to
		// drive sortedRecords removal, where the exact normalization no longer matters (the shared tree is empty)
		return this.normalizer;
	}

	@Nullable
	@Override
	protected InvertedIndex valueTreeOrNull() {
		return this.sharedTree;
	}

	/**
	 * {@inheritDoc}
	 *
	 * {@link #sharedTree} contributes its **slot alone**. The tree belongs to the enclosing {@code AttributeIndex},
	 * which charges it once as the filter index it also is; a view holds no transactional state of its own over it
	 * and is rebuilt fresh against the committed tree on every commit. Charging it here would report the same tree
	 * twice for every attribute that is both filterable and sortable — which is most of them.
	 */
	@Override
	public long getHeapSizeInBytes() {
		// the sharedTree slot, on top of the base's own fields - and nothing beyond it
		return getSharedHeapSizeInBytes(VMLayout.current().referenceSize());
	}

	@Nonnull
	@Override
	SortIndex bindSharedTree(@Nullable InvertedIndex committedSharedTree) {
		// O(Δ) carry-forward: when the committed tree is the very instance this view already wraps (an untouched key —
		// the producer-map merge keeps an unmutated tree identity-stable), the view is already exactly correct, so return it unchanged
		// and share it across snapshot versions (safe: immutable). Only a replaced tree needs a fresh, immutable copy that
		// shares the committed sorted-records façade. Never reassign the field on a published instance.
		if (committedSharedTree == this.sharedTree) {
			return this;
		}
		return new SortIndexView(
			this.comparatorBase,
			getReferenceKey(),
			getAttributeIndexKey(),
			getIndexedDecimalPlaces(),
			this.sortedRecords,
			committedSharedTree
		);
	}

	/**
	 * Rebuilds the positional `sortedRecords` array of a view-mode sort index from its shared {@link InvertedIndex} at
	 * load time, so a slim view part need not persist it (the source of the churn elimination). Concatenates the shared
	 * tree's buckets in comparator order (the view's sort order) and, within each value, its ascending record ids —
	 * reproducing byte-for-byte the array the live view holds, because the both-flagged invariant guarantees the shared
	 * tree holds exactly the view's record set. The loader uses this for every view-mode part and ignores any positional
	 * array a legacy full part still carries (self-healing to the slim shape on the next reflush). An empty shared tree
	 * legitimately yields an empty array.
	 *
	 * @param shared the shared filter tree the view folds onto (fully loaded before SORT in the loader's FILTER-first pass)
	 * @return the reconstructed positional sorted-records array (ascending ids within each per-value block)
	 */
	@Nonnull
	public static int[] reconstructSortedRecords(@Nonnull InvertedIndex shared) {
		// pre-size to the exact total record count (sum of all bucket sizes) and fill via arraycopy - no intermediate
		// growable buffer on the load path
		final int[] result = new int[shared.getLength()];
		final Iterator<ValueToRecord> it = shared.getValueIterator();
		int offset = 0;
		while (it.hasNext()) {
			final Bitmap recordIds = it.next().getRecordIds();
			final int[] ids = recordIds.getArray();
			System.arraycopy(ids, 0, result, offset, ids.length);
			offset += ids.length;
		}
		return result;
	}

	@Override
	int getValueCardinality(@Nonnull Serializable value) {
		// cardinality is owned by the shared inverted index; the SORT block reads it in its pre-mutation state
		final int shared = sharedCardinalityOf(value);
		return Math.max(shared, 1);
	}

	@Override
	protected boolean valuePresentForRemoval(@Nonnull Serializable normalizedValue, int recordId) {
		// the value's cardinality lives in the shared tree (possibly many records); assert the record's own presence
		// against sortedRecords - the structure the sort block is about to mutate
		return this.sortedRecords.indexOf(recordId) >= 0;
	}

	@Nonnull
	@Override
	protected int[] storagePartSortedRecords() {
		// slim view-mode part: the positional sortedRecords is re-derivable from the shared FILTER tree on load (see
		// reconstructSortedRecords), so it is omitted to eliminate the whole-array rewrite on every both-flagged commit
		return ArrayUtils.EMPTY_INT_ARRAY;
	}

	@Nonnull
	@Override
	protected Serializable[] storagePartSortedValues() {
		// slim view-mode part: the distinct values are re-derivable from the shared FILTER part on load
		return ArrayUtils.EMPTY_SERIALIZABLE_ARRAY;
	}

	@Nonnull
	@Override
	protected CardinalityColumns storagePartCardinalities() {
		// slim view-mode part: the cardinalities are re-derivable from the shared FILTER part on load
		return new CardinalityColumns(ArrayUtils.EMPTY_SERIALIZABLE_ARRAY, ArrayUtils.EMPTY_INT_ARRAY);
	}

	@Override
	protected void doAppendStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink) {
		// a view never owns a value tree, so it never pages: it emits a single slim SINGLE part whose empty value columns
		// and omitted positional sortedRecords are re-derived from the shared FILTER part on load
		appendSingleStoragePart(entityIndexPrimaryKey, sink);
	}

	@Override
	protected void onFirstRecordForValue(@Nonnull Serializable normalizedValue, int recordId) {
		// no-op: the shared tree's value set is mutated by the FILTER block, not the view
	}

	@Override
	protected void onValueCardinalityIncreased(@Nonnull Serializable normalizedValue, int recordId) {
		// no-op: cardinality lives in the shared tree
	}

	@Override
	protected void onValueCardinalityDecreased(@Nonnull Serializable normalizedValue, int recordId) {
		// no-op: cardinality lives in the shared tree
	}

	@Override
	protected void onLastRecordForValueRemoved(@Nonnull Serializable normalizedValue, int recordId) {
		// no-op: the shared tree's value set is mutated by the FILTER block, not the view
	}

	@Override
	protected void removeValueSideLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// no-op: a view owns no value tree, only the shared one (owned and discharged by AttributeIndex)
	}

	@Nonnull
	@Override
	protected SortIndex copyWithMergedValueSide(
		@Nonnull TransactionalUnorderedIntArray mergedSortedRecords,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// carry the current (pre-commit) tree reference; the parent's createCopy re-binds it to the committed tree via
		// bindSharedTree (a no-op carry-forward when the tree is unchanged, a fresh copy when it was replaced)
		return new SortIndexView(
			this.comparatorBase,
			getReferenceKey(),
			getAttributeIndexKey(),
			getIndexedDecimalPlaces(),
			mergedSortedRecords,
			this.sharedTree
		);
	}

	/**
	 * Allocation-free read of the shared inverted index cardinality of an already-normalized value.
	 */
	private int sharedCardinalityOf(@Nonnull Serializable normalizedValue) {
		final InvertedIndex shared = this.sharedTree;
		// shared tree transiently absent ⇒ cardinality 0
		return shared == null ? 0 : shared.cardinalityOf(normalizedValue);
	}

}
