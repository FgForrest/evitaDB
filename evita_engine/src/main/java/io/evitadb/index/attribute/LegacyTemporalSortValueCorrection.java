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

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;

/**
 * Loads an owner {@link SortIndex} persisted before its temporal values were normalized, normalizing them on the way.
 *
 * An owner sort index - a sort-only attribute, every sortable attribute compound, and a sortable attribute that is
 * filterable too until the catalog is next loaded - used to store `OffsetDateTime`, `LocalDateTime` and `LocalTime`
 * values verbatim, while the filter index and every sort index viewing it stored their millisecond form. Such a
 * catalog still holds the raw values on disk. This class brings them to the form the index works with now:
 *
 * - every value is normalized by the very normalizer the index applies to a new value;
 * - values that now coincide - one moment written at two offsets, or two values differing only below the
 *   millisecond - are merged into one, and their records are ordered by record id within it as in any other block;
 * - the values are re-sorted, because the normalization may reorder them: two compound values tying on a temporal
 *   element only after it was normalized are decided by the next element, which may order them the other way;
 * - a paged index gives up the identity of every leaf page, so the first flush that writes the index again writes all
 *   its pages in normalized form and frees the legacy ones.
 *
 * The correction is made in memory only and repeats on every load until the index is written again, which happens
 * the first time a record of it changes. **The sort index is not flagged dirty at load**, and its own dirty flag is
 * what gates its emission, so a flush of the owning entity index for any other reason - a write to a sibling
 * attribute, say - leaves the legacy pages on disk untouched. Until the index is written, a paged one keeps every leaf
 * released and its page registry seeded from the persisted root's page list, so the first write that does flush it
 * frees every legacy page and writes the normalized ones, and dropping the emptied index reclaims the legacy pages the
 * root still lists. Nothing between the load and that write consumes the released leaves: the baseline an entity
 * index captures when it is built only announces keys (`IndexComponent#announceKeys`) and collects nothing.
 *
 * An index holding no temporal value, or holding only values already normalized, is loaded exactly as before.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @deprecated the correction is needed only for catalogs written before temporal sort values were normalized; it can
 * be removed only once every such sort index has been MUTATED - a record added to it or removed from it - by a
 * version containing it: merely loading and flushing a catalog does not rewrite an index nobody wrote to (see above).
 * It goes together with the call sites in {@link SortIndex#create(ComparatorSource[], RepresentativeReferenceKey,
 * AttributeIndexKey, int, int[], Serializable[], Map, java.util.function.Supplier)} and
 * {@link OwnerSortIndex#fromPersistedPages}, and with {@link InvertedIndex#fromPersistedPagesNormalizingLegacyKeys}
 * (whose legacy-order gate is part of the same unit)
 */
@Deprecated(since = "2026.3", forRemoval = true)
final class LegacyTemporalSortValueCorrection {

	private LegacyTemporalSortValueCorrection() {
		// static helpers only
	}

	/**
	 * Returns `true` when an owner sort index of the passed elements may hold raw temporal values - when any element
	 * is an `OffsetDateTime`, `LocalDateTime` or `LocalTime`.
	 *
	 * @param comparatorBase one descriptor per element (a single entry for plain attributes)
	 * @return `true` when the persisted values have to be checked and possibly corrected
	 */
	static boolean isApplicable(@Nonnull ComparatorSource[] comparatorBase) {
		for (final ComparatorSource comparatorSource : comparatorBase) {
			if (SortIndex.isTemporal(comparatorSource.type())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Rehydrates an owner sort index from its persisted inline arrays, normalizing them when any value is raw. When
	 * every value is normalized already, the index is built from the arrays exactly as before.
	 *
	 * @param comparatorBase       one descriptor per element (a single entry for plain attributes)
	 * @param referenceKey         owning reference discriminator, or `null` for the global index
	 * @param attributeIndexKey    identifies the indexed attribute / compound
	 * @param indexedDecimalPlaces decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 * @param sortedRecords        record ids ordered by their associated values, blocked per value
	 * @param sortedRecordValues   the persisted distinct values backing `sortedRecords`
	 * @param cardinalities        counts for values shared by more than one record (cardinality `1` is implicit)
	 * @return the rehydrated owner sort index
	 */
	@Nonnull
	static OwnerSortIndex fromPersistedArrays(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		int indexedDecimalPlaces,
		@Nonnull int[] sortedRecords,
		@Nonnull Serializable[] sortedRecordValues,
		@Nonnull Map<Serializable, Integer> cardinalities
	) {
		final Function<Object, Serializable> normalizer = createNormalizer(comparatorBase);
		final Serializable[] normalizedValues = new Serializable[sortedRecordValues.length];
		boolean legacyValueFound = false;
		for (int i = 0; i < sortedRecordValues.length; i++) {
			normalizedValues[i] = normalizer.apply(sortedRecordValues[i]);
			//noinspection NonShortCircuitBooleanExpression
			legacyValueFound |= normalizedValues[i] != sortedRecordValues[i];
		}
		if (!legacyValueFound) {
			return new OwnerSortIndex(
				comparatorBase, referenceKey, attributeIndexKey, indexedDecimalPlaces,
				sortedRecords, sortedRecordValues, cardinalities
			);
		}
		// the tree inserts each block under its normalized value: a value that coincides with one already inserted
		// joins its bucket, and the tree keeps the values in the order they compare in now, whatever the persisted one
		final Comparator<?> comparator = createComparator(comparatorBase, attributeIndexKey);
		final InvertedIndex ownedTree =
			OwnerSortIndex.createOwnedTree(comparatorBase, comparator, indexedDecimalPlaces);
		int offset = 0;
		for (int i = 0; i < sortedRecordValues.length; i++) {
			// the cardinalities are keyed by the persisted value, not by the normalized one
			final int cardinality = cardinalities.getOrDefault(sortedRecordValues[i], 1);
			ownedTree.addRecord(normalizedValues[i], Arrays.copyOfRange(sortedRecords, offset, offset + cardinality));
			offset += cardinality;
		}
		// the persisted positional array orders the records by the raw values - rebuild it from the tree, which
		// orders merged blocks by record id like every other block
		return new OwnerSortIndex(
			comparatorBase, referenceKey, attributeIndexKey, indexedDecimalPlaces,
			new TransactionalUnorderedIntArray(SortIndexView.reconstructSortedRecords(ownedTree)), ownedTree
		);
	}

	/**
	 * Rebuilds an owner sort index from its persisted leaf pages, normalizing them when any value is raw - see
	 * {@link InvertedIndex#fromPersistedPagesNormalizingLegacyKeys} for how the pages are re-sorted and rewritten.
	 *
	 * @param comparatorBase        one descriptor per element (a single entry for plain attributes)
	 * @param referenceKey          owning reference discriminator, or `null` for the global index
	 * @param attributeIndexKey     identifies the indexed attribute / compound
	 * @param indexedDecimalPlaces  decimal-places scale used to encode `BigDecimal` values (0 for other types)
	 * @param orderedPageSequences  the persisted leaf-page sequences in ascending key order (the root's leaf list)
	 * @param perPageBuckets        the buckets of each leaf page, positionally aligned with `orderedPageSequences`
	 * @param highWaterPageSequence the persisted stream high-water (largest page sequence ever allocated)
	 * @return the rehydrated owner sort index
	 */
	@Nonnull
	static OwnerSortIndex fromPersistedPages(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		int indexedDecimalPlaces,
		@Nonnull int[] orderedPageSequences,
		@Nonnull ValueToRecord[][] perPageBuckets,
		int highWaterPageSequence
	) {
		final Comparator<?> comparator = createComparator(comparatorBase, attributeIndexKey);
		final Class<?> plainType = comparatorBase.length == 1 ? comparatorBase[0].type() : Comparable.class;
		final InvertedIndex ownedTree = InvertedIndex.fromPersistedPagesNormalizingLegacyKeys(
			plainType, orderedPageSequences, perPageBuckets, null, highWaterPageSequence,
			createNormalizer(comparatorBase), comparator, indexedDecimalPlaces
		);
		return new OwnerSortIndex(
			comparatorBase, referenceKey, attributeIndexKey, indexedDecimalPlaces,
			new TransactionalUnorderedIntArray(SortIndexView.reconstructSortedRecords(ownedTree)), ownedTree
		);
	}

	/**
	 * Creates the comparator an owner sort index of the passed elements orders its values by - the one
	 * {@link SortIndex} derives for itself.
	 *
	 * @param comparatorBase    one descriptor per element (a single entry for plain attributes)
	 * @param attributeIndexKey identifies the indexed attribute / compound, and its locale
	 * @return the value comparator
	 */
	@Nonnull
	private static Comparator<?> createComparator(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nonnull AttributeIndexKey attributeIndexKey
	) {
		return comparatorBase.length == 1
			? SortIndex.createComparatorFor(attributeIndexKey.locale(), comparatorBase[0])
			: SortIndex.createCombinedComparatorFor(attributeIndexKey.locale(), comparatorBase);
	}

	/**
	 * Creates the normalizer of persisted values: the temporal normalizer the sort index applies to a new value
	 * ({@link FilterIndex#getNormalizer(Class, int)}), applied to a persisted plain value or to the temporal elements
	 * of a persisted {@link ComparableArray}. Every other element is left as persisted - it was normalized when it was
	 * written, and not every normalizer accepts its own output. The function returns the very instance it is given
	 * when that is normalized already, which is how a legacy value is told apart from a current one.
	 *
	 * @param comparatorBase one descriptor per element (a single entry for plain attributes)
	 * @return the normalizer of persisted values
	 */
	@Nonnull
	private static Function<Object, Serializable> createNormalizer(@Nonnull ComparatorSource[] comparatorBase) {
		//noinspection unchecked
		final Function<Object, Serializable>[] elementNormalizers = new Function[comparatorBase.length];
		for (int i = 0; i < comparatorBase.length; i++) {
			final Class<?> type = comparatorBase[i].type();
			elementNormalizers[i] = SortIndex.isTemporal(type) ? FilterIndex.getNormalizer(type, 0) : null;
		}
		if (comparatorBase.length == 1) {
			return elementNormalizers[0];
		} else {
			return value -> {
				final ComparableArray persisted = (ComparableArray) value;
				final Serializable[] elements = persisted.array();
				Serializable[] normalized = null;
				for (int i = 0; i < elements.length; i++) {
					final Function<Object, Serializable> elementNormalizer = elementNormalizers[i];
					if (elementNormalizer != null) {
						final Serializable normalizedElement = elementNormalizer.apply(elements[i]);
						if (normalizedElement != elements[i]) {
							// copy on the first change - the persisted array stays as it was read
							if (normalized == null) {
								normalized = Arrays.copyOf(elements, elements.length);
							}
							normalized[i] = normalizedElement;
						}
					}
				}
				return normalized == null ? persisted : new ComparableArray(normalized);
			};
		}
	}

}
