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
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.comparator.NullsFirstComparatorWrapper;
import io.evitadb.comparator.NullsLastComparatorWrapper;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.page.PageEmission;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A test-only replica of the owner sort index as release 2026.2 and older wrote it: an owned value tree storing every
 * value verbatim - a temporal value raw, not in its millisecond form - ordered by the comparator the release ordered it
 * by, persisted as the inline part or as leaf pages exactly as the owner emitted them. The parts it produces are what a
 * catalog written by such a version holds.
 *
 * The comparator is the one of the release tag `v2026.2.20` (`SortIndex#createComparatorFor` and
 * `SortIndex#createCombinedComparatorFor`), not the current one: a `String` element of a localized index is compared by
 * the bare collator, and a `Locale` element - stored as a {@link ComparableLocale} - by its language tag alone. Each
 * equates two values the current index tells apart (`ab` with and without a zero-width space; `en-US` with and
 * without an ill-formed variant, which the tag drops), so a released compound may hold two such values ordered by its
 * next element, which the current comparator would order the other way.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class LegacyOwnerSortIndexWriter {
	/**
	 * The primary key of the entity index the parts belong to.
	 */
	private final int entityIndexPrimaryKey;
	/**
	 * The attribute or compound the parts belong to.
	 */
	@Nonnull private final AttributeIndexKey attributeIndexKey;
	/**
	 * The comparator base of the index.
	 */
	@Nonnull private final ComparatorSource[] comparatorBase;
	/**
	 * The raw value tree.
	 */
	@Nonnull private final InvertedIndex tree;
	/**
	 * The raw value of every record, in record id order.
	 */
	@Nonnull private final TreeMap<Integer, Serializable> valuesByRecord = new TreeMap<>();

	/**
	 * Creates an empty legacy index.
	 *
	 * @param entityIndexPrimaryKey the primary key of the entity index the parts belong to
	 * @param attributeIndexKey     the attribute or compound the parts belong to
	 * @param comparatorBase        the comparator base
	 */
	public LegacyOwnerSortIndexWriter(
		int entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull ComparatorSource[] comparatorBase
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.attributeIndexKey = attributeIndexKey;
		this.comparatorBase = comparatorBase;
		// the owner used to store the values as it received them - an identity normalizer - ordered by the comparator
		// of the release
		this.tree = new InvertedIndex(
			comparatorBase.length == 1 ? comparatorBase[0].type() : Comparable.class,
			Serializable.class::cast, releasedComparator(attributeIndexKey.locale(), comparatorBase), 0
		);
	}

	/**
	 * Builds the comparator release 2026.2 ordered an owner sort index by - `SortIndex#createComparatorFor` and
	 * `SortIndex#createCombinedComparatorFor` of the tag `v2026.2.20`: a `String` element of a localized index by the
	 * bare collator, a `Locale` element by `ComparableLocale#compareTo` of that tag - the language tag alone - every
	 * other element naturally, each in its own direction and with its own NULL handling.
	 *
	 * @param locale         the locale of the index, or `null`
	 * @param comparatorBase the comparator base
	 * @return the comparator of the release
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Comparator releasedComparator(
		@Nullable Locale locale,
		@Nonnull ComparatorSource[] comparatorBase
	) {
		final Comparator[] elementComparators = new Comparator[comparatorBase.length];
		for (int i = 0; i < comparatorBase.length; i++) {
			final ComparatorSource source = comparatorBase[i];
			final Comparator natural;
			if (String.class.isAssignableFrom(source.type()) && locale != null) {
				natural = new LocalizedStringComparator(locale);
			} else if (Locale.class.isAssignableFrom(source.type())) {
				natural = Comparator.comparing((ComparableLocale it) -> it.getLocale().toLanguageTag());
			} else {
				natural = Comparator.naturalOrder();
			}
			final Comparator directed = source.orderDirection() == OrderDirection.ASC ? natural : natural.reversed();
			elementComparators[i] = source.orderBehaviour() == OrderBehaviour.NULLS_LAST ?
				new NullsLastComparatorWrapper(directed) : new NullsFirstComparatorWrapper(directed);
		}
		if (comparatorBase.length == 1) {
			return elementComparators[0];
		}
		return (left, right) -> {
			final Serializable[] leftElements = ((ComparableArray) left).array();
			final Serializable[] rightElements = ((ComparableArray) right).array();
			for (int i = 0; i < elementComparators.length; i++) {
				final int comparison = elementComparators[i].compare(leftElements[i], rightElements[i]);
				if (comparison != 0) {
					return comparison;
				}
			}
			return 0;
		};
	}

	/**
	 * Stores the raw value for the passed records.
	 *
	 * @param value   the raw value, a {@link ComparableArray} for a compound; a `Locale` as the
	 *                {@link ComparableLocale} the release wrapped it in
	 * @param records the records holding it
	 * @return this writer
	 */
	@Nonnull
	public LegacyOwnerSortIndexWriter add(@Nonnull Serializable value, @Nonnull int... records) {
		this.tree.addRecord(value, records);
		for (int record : records) {
			this.valuesByRecord.put(record, value);
		}
		return this;
	}

	/**
	 * Returns the raw value of every record, in record id order.
	 *
	 * @return the raw values by record
	 */
	@Nonnull
	public SortedMap<Integer, Serializable> getValuesByRecord() {
		return Collections.unmodifiableSortedMap(this.valuesByRecord);
	}

	/**
	 * Returns the distinct values in the order the release persisted them - the order of its comparator.
	 *
	 * @return the persisted values in persisted order
	 */
	@Nonnull
	public List<Serializable> getPersistedValues() {
		final List<Serializable> values = new ArrayList<>(this.tree.getBucketCount());
		final Iterator<ValueToRecord> it = this.tree.getValueIterator();
		while (it.hasNext()) {
			values.add(it.next().getValue());
		}
		return values;
	}

	/**
	 * Builds the owner sort index the current engine builds from the same raw values, written one by one and normalized
	 * on the way - the index a rewrite of the parts this writer produces must equal. A {@link ComparableLocale} is
	 * handed over as the `Locale` it wraps, which is what the engine receives and wraps itself.
	 *
	 * @return the owner sort index of the current engine
	 */
	@Nonnull
	public OwnerSortIndex toCurrentOwner() {
		final OwnerSortIndex owner = this.comparatorBase.length == 1 ?
			new OwnerSortIndex(this.comparatorBase[0].type(), this.attributeIndexKey) :
			new OwnerSortIndex(this.comparatorBase, this.attributeIndexKey);
		for (Map.Entry<Integer, Serializable> entry : this.valuesByRecord.entrySet()) {
			if (entry.getValue() instanceof ComparableArray compound) {
				final Serializable[] elements = compound.array().clone();
				for (int i = 0; i < elements.length; i++) {
					elements[i] = unwrap(elements[i]);
				}
				owner.addRecord(elements, entry.getKey());
			} else {
				owner.addRecord(unwrap(entry.getValue()), entry.getKey());
			}
		}
		return owner;
	}

	/**
	 * Returns the `Locale` a {@link ComparableLocale} wraps, and any other value as it is.
	 *
	 * @param value the stored value or compound element
	 * @return the value the engine receives
	 */
	@Nullable
	private static Serializable unwrap(@Nullable Serializable value) {
		return value instanceof ComparableLocale comparableLocale ? comparableLocale.getLocale() : value;
	}

	/**
	 * Returns the inline part the owner wrote for a single-leaf tree.
	 *
	 * @return the inline part
	 */
	@Nonnull
	public SortIndexStoragePart toInlinePart() {
		final int[] sortedRecords = SortIndexView.reconstructSortedRecords(this.tree);
		final List<Serializable> values = new ArrayList<>(this.tree.getBucketCount());
		final Map<Serializable, Integer> cardinalities = CollectionUtils.createHashMap(8);
		final Iterator<ValueToRecord> it = this.tree.getValueIterator();
		while (it.hasNext()) {
			final ValueToRecord bucket = it.next();
			values.add(bucket.getValue());
			if (bucket.size() > 1) {
				cardinalities.put(bucket.getValue(), bucket.size());
			}
		}
		return new SortIndexStoragePart(
			this.entityIndexPrimaryKey, this.attributeIndexKey, this.comparatorBase, sortedRecords,
			values.toArray(Serializable[]::new), cardinalities
		);
	}

	/**
	 * Returns the leaf pages and the paged root the owner wrote for a multi-leaf tree. The pages are numbered from
	 * zero; the root's high-water mark is raised to `minimalHighWaterPageSequence` so that a page sequence used on
	 * disk before is never handed out again.
	 *
	 * @param minimalHighWaterPageSequence the smallest high-water mark the root may carry
	 * @return the parts, the root last
	 */
	@Nonnull
	public List<StoragePart> toPagedParts(int minimalHighWaterPageSequence) {
		if (!this.tree.isPaged()) {
			throw new IllegalStateException("The legacy tree must span several leaves to be written as pages!");
		}
		final AttributeKeyWithIndexType streamKey =
			new AttributeKeyWithIndexType(this.attributeIndexKey, AttributeIndexType.SORT);
		final PageEmission<InvertedIndex.LeafPage> emission = this.tree.collectChangedPages();
		final List<StoragePart> parts = new ArrayList<>(emission.changedPages().size() + 1);
		for (final InvertedIndex.LeafPage page : emission.changedPages()) {
			parts.add(
				new SortIndexLeafPagePart(
					this.entityIndexPrimaryKey, streamKey, page.pageSequence(), page.buckets(),
					this.comparatorBase.length
				)
			);
		}
		parts.add(
			SortIndexStoragePart.paged(
				this.entityIndexPrimaryKey, this.attributeIndexKey, this.comparatorBase, 0,
				Math.max(emission.highWaterPageSequence(), minimalHighWaterPageSequence),
				emission.orderedPageSequences()
			)
		);
		return parts;
	}
}
