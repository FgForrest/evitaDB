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
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A test-only replica of the owner sort index as it was written before temporal sort values were normalized: an owned
 * value tree storing every value verbatim, ordered by the owner's comparator, persisted as the inline part or as leaf
 * pages exactly as the owner emitted them. The parts it produces are what a catalog written by such a version holds.
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
		@SuppressWarnings("rawtypes") final Comparator comparator = comparatorBase.length == 1 ?
			SortIndex.createComparatorFor(attributeIndexKey.locale(), comparatorBase[0]) :
			SortIndex.createCombinedComparatorFor(attributeIndexKey.locale(), comparatorBase);
		// the owner used to store the values as it received them - an identity normalizer
		this.tree = new InvertedIndex(
			comparatorBase.length == 1 ? comparatorBase[0].type() : Comparable.class,
			Serializable.class::cast, comparator, 0
		);
	}

	/**
	 * Stores the raw value for the passed records.
	 *
	 * @param value   the raw value, a {@link ComparableArray} for a compound
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
