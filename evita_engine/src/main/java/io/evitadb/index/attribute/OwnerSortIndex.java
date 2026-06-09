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
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree.EntryCursor;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.function.UnaryOperator;

import static io.evitadb.utils.Assert.isTrue;

/**
 * Owner variant of {@link SortIndex}. It OWNS a `value → cardinality` {@link TransactionalObjectBPlusTree} that is the
 * full source of truth for value ordering and cardinality, on top of the shared {@link SortIndex#sortedRecords} façade
 * the base orchestrates. It backs:
 *
 * - sort-only single attributes (no both-filterable twin, so no shared filter tree to source values from), and
 * - ALL sortable attribute compounds (a compound has no shared twin by construction).
 *
 * It is a full {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} that commits both
 * {@link SortIndex#sortedRecords} (in the base) and its own {@link #sortedValues} tree.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
public final class OwnerSortIndex extends SortIndex {
	@Serial private static final long serialVersionUID = -7212759886731351L;
	/**
	 * Leaf block size of the {@link #sortedValues} tree. ORDER BY drives a full ordered cursor sweep over this tree,
	 * which is cache-miss bound from chasing scattered leaf nodes; larger leaves mean fewer, longer, more sequential
	 * runs and far fewer cold-leaf first-touch misses. Benchmarking (`SortIndexArrayVsBPlusTreeBenchmark`; results and
	 * analysis under `documentation/performance/individual/SortIndexArrayVsBPlusTreeBenchmark/`) puts the knee at `256`
	 * — it roughly halves the high-distinct sweep latency versus the tree default `64`, while `512`+ gives diminishing
	 * returns and costs more per write (a commit path-copies a whole leaf, so write cost scales with this size). It is
	 * a runtime-only parameter — it does not affect the persisted form, which is rebuilt into the tree on load.
	 */
	private static final int VALUE_BLOCK_SIZE = 256;
	private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;
	private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);

	/**
	 * Comparator-ordered B+ tree holding every distinct value as a key (ordered by {@link SortIndex#comparator}) mapped
	 * to its cardinality (the number of records sharing that value, always `>= 1`). It replaces the former parallel pair
	 * of a `sortedRecordsValues` array plus a sparse `valueCardinalities` map: the sorted distinct values are the tree
	 * keys, and the cardinality is stored inline as the value (so cardinality `1` is now stored explicitly rather than
	 * being implied to save memory). Commit derives the next snapshot by path-copying only the `O(log N)` nodes on each
	 * mutated key's root→leaf path - the same structural-sharing win already used by
	 * {@link io.evitadb.index.invertedIndex.InvertedIndex} - instead of rebuilding a full contiguous `Serializable[]`
	 * on every committed transaction.
	 *
	 * The key type is the raw {@link Comparable} because compound values ({@link ComparableArray}) are ordered solely
	 * by {@link SortIndex#comparator} and do not implement {@link Comparable} themselves; the tree always uses the
	 * supplied comparator and never the keys' natural order, so the raw key is safe. The unchecked key casts are confined
	 * to the private `tree*` helper methods.
	 */
	@SuppressWarnings("rawtypes") private final TransactionalObjectBPlusTree sortedValues;

	/**
	 * Creates a fresh, empty {@link #sortedValues} tree ordered by the passed comparator (cardinality values are plain
	 * {@link Integer}s). The cardinality value is an immutable {@link Integer} that never needs transactional wrapping,
	 * so no value wrapper is supplied. See {@link #VALUE_BLOCK_SIZE} for the read-vs-write block-size trade-off.
	 *
	 * @param comparator the comparator that defines the key (value) ordering
	 * @return a new empty comparator-ordered value → cardinality tree
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static TransactionalObjectBPlusTree createEmptyTree(@Nonnull Comparator comparator) {
		return new TransactionalObjectBPlusTree<>(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			Comparable.class, Integer.class, comparator
		);
	}

	/**
	 * Builds a {@link #sortedValues} tree from the persisted arrays: every value from `sortedRecordValues` becomes a
	 * key, with its cardinality taken from `cardinalities` (defaulting to `1` for values absent from the sparse map,
	 * matching the legacy "cardinality 1 is implied" storage convention). The values are already sorted by `comparator`.
	 *
	 * @param sortedRecordValues the naturally sorted distinct values
	 * @param cardinalities      counts for values shared by more than one record (cardinality `1` is implicit)
	 * @param comparator         the comparator that defines the key (value) ordering
	 * @return a populated comparator-ordered value → cardinality tree
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static TransactionalObjectBPlusTree buildTree(
		@Nonnull Serializable[] sortedRecordValues,
		@Nonnull Map<Serializable, Integer> cardinalities,
		@Nonnull Comparator comparator
	) {
		final TransactionalObjectBPlusTree tree = createEmptyTree(comparator);
		for (final Serializable value : sortedRecordValues) {
			tree.insert((Comparable) value, cardinalities.getOrDefault(value, 1));
		}
		return tree;
	}

	/**
	 * Creates an empty owner sort index for a single attribute that belongs to the global {@link GlobalEntityIndex}
	 * (no discriminating reference key). Convenience overload that defaults the sort order to ascending with NULLs last.
	 *
	 * @param attributeType     the comparable type of the indexed attribute
	 * @param attributeIndexKey identifies the indexed attribute (and its locale, if localized)
	 */
	public OwnerSortIndex(
		@Nonnull Class<?> attributeType,
		@Nonnull AttributeIndexKey attributeIndexKey
	) {
		this(attributeType, null, attributeIndexKey);
	}

	/**
	 * Creates an empty owner sort index for a single attribute, defaulting the sort order to ascending with NULLs last.
	 * The internal {@link ComparatorSource} is derived from `attributeType`.
	 *
	 * @param attributeType     the comparable type of the indexed attribute
	 * @param referenceKey      discriminator of the owning {@link io.evitadb.index.AbstractReducedEntityIndex}, or `null`
	 *                          when this index lives in the global {@link GlobalEntityIndex}
	 * @param attributeIndexKey identifies the indexed attribute (and its locale, if localized)
	 */
	public OwnerSortIndex(
		@Nonnull Class<?> attributeType,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey
	) {
		super(
			new ComparatorSource[]{
				new ComparatorSource(attributeType, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			},
			referenceKey,
			attributeIndexKey,
			new TransactionalUnorderedIntArray()
		);
		this.sortedValues = createEmptyTree(this.comparator);
	}

	/**
	 * Creates an empty owner sort index for a sortable attribute compound (multiple attribute elements) that belongs
	 * to the global {@link GlobalEntityIndex}. Convenience overload of the reference-key aware constructor.
	 *
	 * @param comparatorSources one descriptor per compound element, in element order; at least two are required
	 * @param attributeIndexKey identifies the indexed compound (and its locale, if localized)
	 */
	public OwnerSortIndex(
		@Nonnull ComparatorSource[] comparatorSources,
		@Nonnull AttributeIndexKey attributeIndexKey
	) {
		this(comparatorSources, null, attributeIndexKey);
	}

	/**
	 * Creates an empty owner sort index for a sortable attribute compound. Each element of `comparatorSources` describes
	 * one compound element (type, direction, NULL handling); the combined comparator orders records lexicographically
	 * across those elements.
	 *
	 * @param comparatorSources one descriptor per compound element, in element order; at least two are required
	 * @param referenceKey      discriminator of the owning {@link io.evitadb.index.AbstractReducedEntityIndex}, or `null`
	 *                          when this index lives in the global {@link GlobalEntityIndex}
	 * @param attributeIndexKey identifies the indexed compound (and its locale, if localized)
	 * @throws IllegalArgumentException if fewer than two comparator sources are supplied
	 */
	public OwnerSortIndex(
		@Nonnull ComparatorSource[] comparatorSources,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey
	) {
		super(comparatorSources, referenceKey, attributeIndexKey, new TransactionalUnorderedIntArray());
		isTrue(
			comparatorSources.length > 1,
			"At least two comparators are required to create a compound SortIndex by this constructor!"
		);
		this.sortedValues = createEmptyTree(this.comparator);
	}

	/**
	 * Rehydrates an owner sort index from its persisted state (typically a {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart}
	 * read back from disk). Works for both single-attribute and compound indexes: the comparator/normalizer is chosen by
	 * the length of `comparatorBase`. The presorted arrays are taken as-is, so the caller is responsible for their mutual
	 * consistency (`sortedRecords` aligned with `sortedRecordValues`, and `cardinalities` holding only values whose
	 * cardinality is greater than one).
	 *
	 * @param comparatorBase     one descriptor per element (a single entry for plain attributes)
	 * @param referenceKey       owning reference discriminator, or `null` for the global index
	 * @param attributeIndexKey  identifies the indexed attribute / compound
	 * @param sortedRecords      record ids ordered by their associated values, blocked per value
	 * @param sortedRecordValues the naturally sorted distinct values backing `sortedRecords`
	 * @param cardinalities      counts for values shared by more than one record (cardinality 1 is implicit)
	 */
	public OwnerSortIndex(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull int[] sortedRecords,
		@Nonnull Serializable[] sortedRecordValues,
		@Nonnull Map<Serializable, Integer> cardinalities
	) {
		super(comparatorBase, referenceKey, attributeIndexKey, new TransactionalUnorderedIntArray(sortedRecords));
		this.sortedValues = buildTree(sortedRecordValues, cardinalities, this.comparator);
	}

	/**
	 * Internal constructor used by {@link #copyWithMergedValueSide} to wrap the already-merged (committed) sorted-records
	 * façade and value tree directly, instead of rebuilding them from arrays (preserves the structural sharing of the
	 * underlying two-tree backing and the B+ tree across commits).
	 */
	private OwnerSortIndex(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull TransactionalUnorderedIntArray sortedRecords,
		@SuppressWarnings("rawtypes") @Nonnull TransactionalObjectBPlusTree sortedValues
	) {
		super(comparatorBase, referenceKey, attributeIndexKey, sortedRecords);
		this.sortedValues = sortedValues;
	}

	@Nonnull
	@Override
	protected UnaryOperator<Serializable> effectiveNormalizer() {
		return this.normalizer;
	}

	@Nonnull
	@Override
	@SuppressWarnings("rawtypes")
	protected Comparator effectiveComparator() {
		return this.comparator;
	}

	@Nonnull
	@Override
	SortIndex bindSharedTree(@Nullable InvertedIndex committedSharedTree) {
		// owner indexes own their value tree and never source from a shared tree, so binding is a no-op: carry forward
		return this;
	}

	@Nonnull
	@Override
	public Serializable[] getSortedRecordValues() {
		final Serializable[] result = new Serializable[this.sortedValues.size()];
		final Iterator<?> it = this.sortedValues.keyIterator();
		int index = 0;
		while (it.hasNext()) {
			result[index++] = (Serializable) it.next();
		}
		return result;
	}

	@Override
	int getValueCardinality(@Nonnull Serializable value) {
		// the live sortedValues tree stores every present value explicitly (cardinality >= 1), so a miss for a value the
		// caller has already proven present is a broken invariant (tree out of sync with the sorted-records value index)
		return getValueCardinalityOrThrow(value);
	}

	@Override
	protected boolean valuePresent(@Nonnull Serializable normalizedValue) {
		return treeSearch(normalizedValue) != null;
	}

	@Override
	protected boolean valuePresentForRemoval(@Nonnull Serializable normalizedValue, int recordId) {
		return treeSearch(normalizedValue) != null;
	}

	@Override
	int valueCount() {
		return this.sortedValues.size();
	}

	@Nonnull
	@Override
	ValueCardinalityCursor valueCursor() {
		return new TreeValueCursor(this.sortedValues.entryCursor());
	}

	@Nonnull
	@Override
	ValueCardinalityCursor valueReverseCursor() {
		return new TreeEntryReverseCursor(this.sortedValues.entryReverseCursor());
	}

	@Nonnull
	@Override
	protected Serializable[] storagePartSortedValues() {
		return getSortedRecordValues();
	}

	@Nonnull
	@Override
	protected Map<Serializable, Integer> storagePartCardinalities() {
		return materializeCardinalities();
	}

	@Override
	protected int preRemovalCardinality(@Nonnull Serializable normalizedValue) {
		return getValueCardinalityOrThrow(normalizedValue);
	}

	@Override
	protected void onFirstRecordForValue(@Nonnull Serializable normalizedValue) {
		treeInsert(normalizedValue);
	}

	@Override
	protected void onValueCardinalityIncreased(@Nonnull Serializable normalizedValue) {
		treeUpsert(normalizedValue, crd -> crd + 1);
	}

	@Override
	protected void onValueCardinalityDecreased(@Nonnull Serializable normalizedValue) {
		treeUpsert(normalizedValue, crd -> crd - 1);
	}

	@Override
	protected void onLastRecordForValueRemoved(@Nonnull Serializable normalizedValue) {
		treeDelete(normalizedValue);
	}

	@Override
	protected void removeValueSideLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.sortedValues.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	protected SortIndex copyWithMergedValueSide(
		@Nonnull TransactionalUnorderedIntArray mergedSortedRecords,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		@SuppressWarnings({"rawtypes", "unchecked"}) final TransactionalObjectBPlusTree committedValues =
			(TransactionalObjectBPlusTree) transactionalLayer.getStateCopyWithCommittedChanges(this.sortedValues);
		return new OwnerSortIndex(
			this.comparatorBase,
			getReferenceKey(),
			getAttributeIndexKey(),
			mergedSortedRecords,
			committedValues
		);
	}

	/**
	 * Owner-mode pre-removal cardinality read with a fail-fast guard (the value's presence is guaranteed by the public
	 * `removeRecord` callers via {@link #valuePresentForRemoval}).
	 */
	private int getValueCardinalityOrThrow(@Nonnull Serializable normalizedValue) {
		final Integer cardinality = treeSearch(normalizedValue);
		if (cardinality == null) {
			throw new GenericEvitaInternalError("Unexpected cardinality: null");
		}
		return cardinality;
	}

	/**
	 * Inserts a brand-new value into {@link #sortedValues} with an initial cardinality of `1`. A value reaches this
	 * method only via its very first record (the {@link #treeSearch} miss branch in the base `addRecordInternal`); every
	 * later record sharing the value bumps the inline cardinality through {@link #treeUpsert}, so the initial count is
	 * always `1`. Confines the unchecked cast of the value to the raw {@link Comparable} key type (safe because the tree
	 * orders by {@link SortIndex#comparator}).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void treeInsert(@Nonnull Serializable value) {
		this.sortedValues.insert((Comparable) value, 1);
	}

	/**
	 * Updates the inline cardinality of an existing value in {@link #sortedValues}. Confines the unchecked cast of the
	 * value to the raw {@link Comparable} key type.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void treeUpsert(@Nonnull Serializable value, @Nonnull UnaryOperator<Integer> updater) {
		this.sortedValues.upsert((Comparable) value, updater);
	}

	/**
	 * Returns the inline cardinality of the passed value, or `null` when the value is not present in
	 * {@link #sortedValues}. Confines the unchecked cast of the value to the raw {@link Comparable} key type.
	 */
	@Nullable
	@SuppressWarnings({"unchecked", "rawtypes"})
	private Integer treeSearch(@Nonnull Serializable value) {
		return (Integer) this.sortedValues.search((Comparable) value).orElse(null);
	}

	/**
	 * Deletes the passed value (and its inline cardinality) from {@link #sortedValues}. Confines the unchecked cast of
	 * the value to the raw {@link Comparable} key type.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void treeDelete(@Nonnull Serializable value) {
		this.sortedValues.delete((Comparable) value);
	}

	/**
	 * Materialises the sparse cardinality map for serialization: only values shared by more than one record are
	 * emitted (cardinality `1` is implied on load), reproducing the exact byte layout the storage format expects.
	 */
	@Nonnull
	@SuppressWarnings("rawtypes")
	private Map<Serializable, Integer> materializeCardinalities() {
		final Map<Serializable, Integer> result = CollectionUtils.createHashMap(this.sortedValues.size());
		final EntryCursor cursor = this.sortedValues.entryCursor();
		while (cursor.hasNext()) {
			final Serializable value = (Serializable) cursor.next();
			final int cardinality = (Integer) cursor.value();
			if (cardinality > 1) {
				result.put(value, cardinality);
			}
		}
		return result;
	}

	/**
	 * Owner-mode {@link ValueCardinalityCursor} backed by the {@link #sortedValues} forward entry cursor.
	 */
	@SuppressWarnings("rawtypes")
	private static final class TreeValueCursor implements ValueCardinalityCursor {
		private final EntryCursor cursor;
		private int cardinality;

		TreeValueCursor(@Nonnull EntryCursor cursor) {
			this.cursor = cursor;
		}

		@Override
		public boolean hasNext() {
			return this.cursor.hasNext();
		}

		@Nonnull
		@Override
		public Serializable next() {
			final Serializable value = (Serializable) this.cursor.next();
			this.cardinality = (Integer) this.cursor.value();
			return value;
		}

		@Override
		public int cardinality() {
			return this.cardinality;
		}
	}

	/**
	 * Owner-mode reverse {@link ValueCardinalityCursor} backed by the {@link #sortedValues} reverse entry cursor.
	 */
	@SuppressWarnings("rawtypes")
	private static final class TreeEntryReverseCursor implements ValueCardinalityCursor {
		private final EntryCursor cursor;
		private int cardinality;

		TreeEntryReverseCursor(@Nonnull EntryCursor cursor) {
			this.cursor = cursor;
		}

		@Override
		public boolean hasNext() {
			return this.cursor.hasNext();
		}

		@Nonnull
		@Override
		public Serializable next() {
			final Serializable value = (Serializable) this.cursor.next();
			this.cardinality = (Integer) this.cursor.value();
			return value;
		}

		@Override
		public int cardinality() {
			return this.cardinality;
		}
	}

}
