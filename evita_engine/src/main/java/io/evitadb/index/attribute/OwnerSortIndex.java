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
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.function.UnaryOperator;

import static io.evitadb.utils.Assert.isTrue;

/**
 * Owner variant of {@link SortIndex}. It OWNS an {@link InvertedIndex} (value → records bitmap) that is the full source
 * of truth for value ordering and cardinality, on top of the shared {@link SortIndex#sortedRecords} façade the base
 * orchestrates. It backs:
 *
 * - sort-only single attributes (no both-filterable twin, so no shared filter tree to source values from), and
 * - ALL sortable attribute compounds (a compound has no shared twin by construction).
 *
 * The owned tree replaces the former `value → cardinality` B+ tree: a value's cardinality is now simply the size of its
 * record bucket (cardinality `1` is a compact single-record bucket; a drained bucket is auto-dropped). The base value-side
 * READS (the ordered `(value, cardinality)` cursors, presence, distinct-value reconstruction) walk this tree through
 * {@link SortIndex#valueTreeOrNull()}; this class supplies only the write hooks (add/remove a record), the persisted
 * columns and the mode-specific {@link #getValueCardinality(Serializable)} (throw-on-miss broken-invariant guard).
 *
 * It is a full {@link TransactionalLayerProducer} that commits both
 * {@link SortIndex#sortedRecords} (in the base) and its own {@link #ownedTree}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
public final class OwnerSortIndex extends SortIndex {
	@Serial private static final long serialVersionUID = -7212759886731351L;

	/**
	 * Owned {@link InvertedIndex} keyed by each distinct (already-normalized) value — ordered by
	 * {@link SortIndex#comparator} — mapped to the bitmap of every record sharing that value. The value's cardinality is
	 * the bucket size (a single-record value is a compact primitive bucket, a multi-record value an overflow bitmap), so
	 * the former parallel `sortedRecordsValues` array plus sparse `valueCardinalities` map collapses into this one tree.
	 * Commit derives the next snapshot by path-copying only the `O(log N)` nodes on each mutated key's root→leaf path
	 * instead of rebuilding a full contiguous `Serializable[]` on every committed transaction.
	 *
	 * The tree is built with an IDENTITY normalizer because the base {@link SortIndex#addRecord} / {@link SortIndex#removeRecord}
	 * already normalize the value (and wrap compounds into {@link ComparableArray}) before the value-side hooks run, so the
	 * tree must store the value verbatim. Compound values ({@link ComparableArray}) are ordered solely by
	 * {@link SortIndex#comparator} and stored in the universal boxed key column; a single-attribute value picks the column
	 * kind from its plain type (front-coded for `String`, boxed otherwise — the non-natural-order comparator gates out the
	 * primitive numeric / temporal columns).
	 */
	private final InvertedIndex ownedTree;

	/**
	 * Creates a fresh, empty owned {@link InvertedIndex} ordered by the passed comparator. The tree uses an IDENTITY
	 * normalizer (the base already normalizes values before the value-side hooks) and selects its leaf key-column kind
	 * from the plain type: a single attribute uses its declared type (front-coded `String`, boxed otherwise), a compound
	 * uses the universal boxed key column ({@link Comparable}.class).
	 *
	 * @param comparatorBase       one descriptor per element (a single entry for plain attributes)
	 * @param comparator           the comparator that defines the value ordering
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 * @return a new empty owned value → records tree
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes"})
	private static InvertedIndex createOwnedTree(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nonnull Comparator comparator,
		int indexedDecimalPlaces
	) {
		final Class<?> plainType = comparatorBase.length == 1 ? comparatorBase[0].type() : Comparable.class;
		return new InvertedIndex(
			plainType,
			Serializable.class::cast,
			comparator,
			indexedDecimalPlaces
		);
	}

	/**
	 * Builds the owned {@link InvertedIndex} from the persisted flat arrays (the SINGLE / legacy on-disk shape): the
	 * record ids in `sortedRecords` are laid out value-block by value-block in value order, so this slices each value's
	 * block (length = its cardinality, defaulting to `1` for values absent from the sparse `cardinalities` map) off the
	 * front of `sortedRecords` and registers it under the value. The values are already sorted by `comparator` and already
	 * normalized (the part stores normalized values), so the IDENTITY-normalizer tree stores them verbatim.
	 *
	 * @param comparatorBase       one descriptor per element (a single entry for plain attributes)
	 * @param comparator           the comparator that defines the value ordering
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 * @param sortedRecords        record ids ordered by their associated values, blocked per value
	 * @param sortedRecordValues   the naturally sorted distinct values backing `sortedRecords`
	 * @param cardinalities        counts for values shared by more than one record (cardinality `1` is implicit)
	 * @return a populated owned value → records tree
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes"})
	private static InvertedIndex buildOwnedTree(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nonnull Comparator comparator,
		int indexedDecimalPlaces,
		@Nonnull int[] sortedRecords,
		@Nonnull Serializable[] sortedRecordValues,
		@Nonnull Map<Serializable, Integer> cardinalities
	) {
		final InvertedIndex tree = createOwnedTree(comparatorBase, comparator, indexedDecimalPlaces);
		int offset = 0;
		for (final Serializable value : sortedRecordValues) {
			final int cardinality = cardinalities.getOrDefault(value, 1);
			if (cardinality == 1) {
				tree.addRecord(value, sortedRecords[offset]);
			} else {
				tree.addRecord(value, Arrays.copyOfRange(sortedRecords, offset, offset + cardinality));
			}
			offset += cardinality;
		}
		return tree;
	}

	/**
	 * Rebuilds an owner sort index from its persisted GRANULAR (`PAGED`) leaf pages — the multi-leaf counterpart of the
	 * flat-array {@link #buildOwnedTree} bridge. The owned {@link InvertedIndex} is reassembled boundary-stable from the
	 * persisted leaf pages via {@link InvertedIndex#fromPersistedPages}, then the positional {@link SortIndex#sortedRecords}
	 * façade — which a PAGED owner does NOT persist — is reconstructed by concatenating the reloaded tree's buckets in
	 * comparator order (ascending record ids within each value), reproducing byte-for-byte the array the live owner holds.
	 *
	 * The reassembly uses the IDENTICAL identity normalizer (`value -> (Serializable) value`), plain-type derivation
	 * (`comparatorBase.length == 1 ? comparatorBase[0].type() : Comparable.class`) and combined comparator (direction +
	 * NULL handling) {@link #createOwnedTree} uses, so the reloaded tree is indistinguishable from a freshly built one: the
	 * persisted leaf values are ALREADY normalized (the base normalizes before the value-side hooks), so the identity
	 * normalizer stores them verbatim, and the comparator orders those normalized keys.
	 *
	 * @param comparatorBase        one descriptor per element (a single entry for plain attributes)
	 * @param referenceKey          owning reference discriminator, or `null` for the global index
	 * @param attributeIndexKey     identifies the indexed attribute / compound
	 * @param indexedDecimalPlaces  decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 * @param orderedPageSequences  the persisted leaf-page sequences in ascending key order (the PAGED root's leaf list)
	 * @param perPageBuckets        the buckets of each leaf page, positionally aligned with `orderedPageSequences`
	 * @param highWaterPageSequence the persisted stream high-water (largest page sequence ever allocated)
	 * @return a rehydrated, boundary-stable PAGED owner sort index
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes"})
	public static OwnerSortIndex fromPersistedPages(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		int indexedDecimalPlaces,
		@Nonnull int[] orderedPageSequences,
		@Nonnull ValueToRecordBitmap[][] perPageBuckets,
		int highWaterPageSequence
	) {
		// the combined comparator (direction + NULL handling) is derived exactly as the SortIndex base derives its own
		// `comparator` from the same comparatorBase + locale, so the reloaded tree orders keys identically to a live owner
		final Comparator comparator = comparatorBase.length == 1
			? createComparatorFor(attributeIndexKey.locale(), comparatorBase[0])
			: createCombinedComparatorFor(attributeIndexKey.locale(), comparatorBase);
		final Class<?> plainType = comparatorBase.length == 1 ? comparatorBase[0].type() : Comparable.class;
		final InvertedIndex ownedTree = InvertedIndex.fromPersistedPages(
			plainType, orderedPageSequences, perPageBuckets, highWaterPageSequence,
			Serializable.class::cast, comparator, indexedDecimalPlaces
		);
		// the positional sortedRecords façade is not persisted for a PAGED owner; reconstruct it from the reloaded tree
		final int[] sortedRecords = SortIndexView.reconstructSortedRecords(ownedTree);
		return new OwnerSortIndex(
			comparatorBase, referenceKey, attributeIndexKey, indexedDecimalPlaces,
			new TransactionalUnorderedIntArray(sortedRecords), ownedTree
		);
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
		this(attributeType, referenceKey, attributeIndexKey, 0);
	}

	/**
	 * Creates an empty owner sort index for a single attribute, carrying the `BigDecimal` scaling decimal places so the
	 * index's own normalizer scales `BigDecimal` keys identically to the shared filter value tree.
	 *
	 * @param attributeType        the comparable type of the indexed attribute
	 * @param referenceKey         discriminator of the owning {@link io.evitadb.index.AbstractReducedEntityIndex}, or `null`
	 * @param attributeIndexKey    identifies the indexed attribute (and its locale, if localized)
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 */
	public OwnerSortIndex(
		@Nonnull Class<?> attributeType,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		int indexedDecimalPlaces
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
		this.ownedTree = createOwnedTree(this.comparatorBase, this.comparator, indexedDecimalPlaces);
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
		this.ownedTree = createOwnedTree(this.comparatorBase, this.comparator, getIndexedDecimalPlaces());
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
		this(
			comparatorBase, referenceKey, attributeIndexKey, 0,
			sortedRecords, sortedRecordValues, cardinalities
		);
	}

	/**
	 * Rehydrates an owner sort index carrying the persisted `BigDecimal` scaling decimal places (see the no-places
	 * overload for the array-consistency contract).
	 *
	 * @param comparatorBase       one descriptor per element (a single entry for plain attributes)
	 * @param referenceKey         owning reference discriminator, or `null` for the global index
	 * @param attributeIndexKey    identifies the indexed attribute / compound
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 * @param sortedRecords        record ids ordered by their associated values, blocked per value
	 * @param sortedRecordValues   the naturally sorted distinct values backing `sortedRecords`
	 * @param cardinalities        counts for values shared by more than one record (cardinality 1 is implicit)
	 */
	public OwnerSortIndex(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		int indexedDecimalPlaces,
		@Nonnull int[] sortedRecords,
		@Nonnull Serializable[] sortedRecordValues,
		@Nonnull Map<Serializable, Integer> cardinalities
	) {
		super(
			comparatorBase, referenceKey, attributeIndexKey, indexedDecimalPlaces,
			new TransactionalUnorderedIntArray(sortedRecords)
		);
		this.ownedTree = buildOwnedTree(
			this.comparatorBase, this.comparator, indexedDecimalPlaces,
			sortedRecords, sortedRecordValues, cardinalities
		);
	}

	/**
	 * Internal constructor used by {@link #copyWithMergedValueSide} to wrap the already-merged (committed) sorted-records
	 * façade and owned tree directly, instead of rebuilding them from arrays (preserves the structural sharing of the
	 * underlying two-tree backing and the owned {@link InvertedIndex} across commits).
	 */
	private OwnerSortIndex(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		int indexedDecimalPlaces,
		@Nonnull TransactionalUnorderedIntArray sortedRecords,
		@Nonnull InvertedIndex ownedTree
	) {
		super(comparatorBase, referenceKey, attributeIndexKey, indexedDecimalPlaces, sortedRecords);
		this.ownedTree = ownedTree;
	}

	@Nullable
	@Override
	protected InvertedIndex valueTreeOrNull() {
		return this.ownedTree;
	}

	@Nonnull
	@Override
	protected UnaryOperator<Serializable> effectiveNormalizer() {
		return this.normalizer;
	}

	@Nonnull
	@Override
	SortIndex bindSharedTree(@Nullable InvertedIndex committedSharedTree) {
		// owner indexes own their value tree and never source from a shared tree, so binding is a no-op: carry forward
		return this;
	}

	@Override
	int getValueCardinality(@Nonnull Serializable value) {
		// the owned tree stores every present value explicitly (a bucket of cardinality >= 1), so a miss for a value the
		// caller has already proven present is a broken invariant (tree out of sync with the sorted-records value index)
		final int cardinality = this.ownedTree.cardinalityOf(value);
		if (cardinality <= 0) {
			throw new GenericEvitaInternalError("Unexpected cardinality: " + cardinality);
		}
		return cardinality;
	}

	@Override
	protected boolean valuePresentForRemoval(@Nonnull Serializable normalizedValue, int recordId) {
		// owner mode validates the VALUE against its owned tree BEFORE mutating (fail-before-mutate): removing a value the
		// tree never held is an illegal argument, surfaced as IllegalArgumentException by the removeRecord precondition
		return valuePresent(normalizedValue);
	}

	@Nonnull
	@Override
	protected int[] storagePartSortedRecords() {
		// owner mode persists its full positional array (its own source of truth for the sort order)
		return getSortedRecords();
	}

	@Nonnull
	@Override
	protected Serializable[] storagePartSortedValues() {
		return getSortedRecordValues();
	}

	@Nonnull
	@Override
	protected CardinalityColumns storagePartCardinalities() {
		// single ascending walk of the owned tree into positionally-aligned sparse columns: only values shared by more
		// than one record are emitted (cardinality 1 is implied on load), so no intermediate map is materialized on the
		// commit/flush hot path
		final CompositeObjectArray<Serializable> values = new CompositeObjectArray<>(Serializable.class);
		final CompositeIntArray cardinalities = new CompositeIntArray();
		final Iterator<ValueToRecord> it = this.ownedTree.getValueIterator();
		while (it.hasNext()) {
			final ValueToRecord bucket = it.next();
			final int cardinality = bucket.size();
			if (cardinality > 1) {
				values.add(bucket.getValue());
				cardinalities.add(cardinality);
			}
		}
		return new CardinalityColumns(values.toArray(), cardinalities.toArray());
	}

	@Override
	protected void doAppendStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink) {
		// every leaf page (and removal) carries the SORT-typed sub-index identity, so its stream id is disjoint from the
		// FILTER stream of the same attribute and resolved store-side when the page's primary key is assigned
		final AttributeKeyWithIndexType streamKey =
			new AttributeKeyWithIndexType(getAttributeIndexKey(), AttributeIndexType.SORT);
		if (this.ownedTree.isPaged()) {
			// PAGED shape: emit one leaf page per CHANGED leaf, one removal per freed leaf, and a PAGED root carrying the
			// high-water + the ordered live leaf-page list (the positional sortedRecords / distinct values are NOT written -
			// they are reconstructed from the reloaded leaf pages on load)
			final PageEmission<InvertedIndex.LeafPage> emission = this.ownedTree.collectChangedPages();
			for (final InvertedIndex.LeafPage page : emission.changedPages()) {
				sink.addChangeToStore(
					new SortIndexLeafPagePart(
						entityIndexPrimaryKey, streamKey, page.pageSequence(), page.buckets(), this.comparatorBase.length
					)
				);
			}
			// remove the leaf pages a merge dropped this commit so they don't leak (the OffsetIndex never reclaims an
			// unreferenced-but-never-removed record - page ids are advance-only and never re-keyed)
			for (final int freedPageSequence : emission.freedPageSequences()) {
				sink.addChangeToStore(new SortIndexLeafPageRemoval(entityIndexPrimaryKey, streamKey, freedPageSequence));
			}
			sink.addChangeToStore(
				SortIndexStoragePart.paged(
					entityIndexPrimaryKey, getAttributeIndexKey(), this.comparatorBase, getIndexedDecimalPlaces(),
					emission.highWaterPageSequence(), emission.orderedPageSequences()
				)
			);
			return;
		}
		// SINGLE shape (possibly just collapsed from PAGED): remove every leaf page from its prior PAGED life (the SINGLE
		// root no longer references them) BEFORE dropping the page bookkeeping, then forget the stream so a later regrow
		// into PAGED starts from a clean baseline and re-emits every leaf
		for (final int freedPageSequence : this.ownedTree.livePageSequences()) {
			sink.addChangeToStore(new SortIndexLeafPageRemoval(entityIndexPrimaryKey, streamKey, freedPageSequence));
		}
		this.ownedTree.forgetPageStream();
		appendSingleStoragePart(entityIndexPrimaryKey, sink);
	}

	@Override
	protected void onFirstRecordForValue(@Nonnull Serializable normalizedValue, int recordId) {
		// the value's first record creates a fresh single-record bucket
		this.ownedTree.addRecord(normalizedValue, recordId);
	}

	@Override
	protected void onValueCardinalityIncreased(@Nonnull Serializable normalizedValue, int recordId) {
		// a globally-unique record id never already lives in the bucket, so the add grows its cardinality by exactly one
		this.ownedTree.addRecord(normalizedValue, recordId);
	}

	@Override
	protected void onValueCardinalityDecreased(@Nonnull Serializable normalizedValue, int recordId) {
		// drop the record from the bucket; the value keeps at least one other record so the bucket survives
		this.ownedTree.removeRecord(normalizedValue, recordId);
	}

	@Override
	protected void onLastRecordForValueRemoved(@Nonnull Serializable normalizedValue, int recordId) {
		// drop the value's last record; the drained bucket is auto-removed from the tree
		this.ownedTree.removeRecord(normalizedValue, recordId);
	}

	@Override
	protected void removeValueSideLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.ownedTree.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	protected SortIndex copyWithMergedValueSide(
		@Nonnull TransactionalUnorderedIntArray mergedSortedRecords,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		final InvertedIndex committed =
			transactionalLayer.getStateCopyWithCommittedChanges(this.ownedTree);
		return new OwnerSortIndex(
			this.comparatorBase,
			getReferenceKey(),
			getAttributeIndexKey(),
			getIndexedDecimalPlaces(),
			mergedSortedRecords,
			committed
		);
	}

}
