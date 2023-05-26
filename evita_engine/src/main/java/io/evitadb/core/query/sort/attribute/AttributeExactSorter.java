/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.sort.attribute;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.array.CompositeObjectArray;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import lombok.RequiredArgsConstructor;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Optional.ofNullable;

/**
 * AttributeExactSorter sorter outputs filtered results in an order of the attribute values in {@link #exactOrder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeExactSorter extends AbstractRecordsSorter {
	private static final int[] EMPTY_RESULT = new int[0];
	/**
	 * Name of the attribute the sorter sorts along.
	 */
	private final String attributeName;
	/**
	 * Sort index that could be used for translation of attribute values to entity primary keys.
	 */
	private final SortIndex sortIndex;
	/**
	 * The attribute values whose order must be maintained in the sorted result.
	 */
	@SuppressWarnings("rawtypes")
	private final Comparable[] exactOrder;
	/**
	 * This sorter instance will be used for sorting entities, that cannot be sorted by this sorter.
	 */
	private final Sorter unknownRecordIdsSorter;

	@SuppressWarnings("rawtypes")
	public AttributeExactSorter(@Nonnull String attributeName, @Nonnull Comparable[] exactOrder, @Nonnull SortIndex sortIndex) {
		this.attributeName = attributeName;
		this.exactOrder = exactOrder;
		this.sortIndex = sortIndex;
		this.unknownRecordIdsSorter = NoSorter.INSTANCE;
	}

	@SuppressWarnings("rawtypes")
	public AttributeExactSorter(@Nonnull String attributeName, @Nonnull Comparable[] exactOrder, @Nonnull SortIndex sortIndex, @Nonnull Sorter unknownRecordIdsSorter) {
		this.attributeName = attributeName;
		this.exactOrder = exactOrder;
		this.sortIndex = sortIndex;
		this.unknownRecordIdsSorter = unknownRecordIdsSorter;
	}

	@Nonnull
	@Override
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		return new AttributeExactSorter(
			attributeName,
			exactOrder,
			sortIndex,
			sorterForUnknownRecords
		);
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return unknownRecordIdsSorter;
	}

	@Nonnull
	@Override
	public int[] sortAndSlice(@Nonnull QueryContext queryContext, @Nonnull Formula input, int startIndex, int endIndex) {
		final Bitmap selectedRecordIds = input.compute();
		if (selectedRecordIds.isEmpty()) {
			return EMPTY_RESULT;
		} else {
			if (queryContext.getPrefetchedEntities() == null) {
				return sortOutputBasedOnIndex(queryContext, startIndex, endIndex, selectedRecordIds);
			} else {
				return sortOutputByPrefetchedEntities(queryContext, startIndex, endIndex, selectedRecordIds);
			}
		}
	}

	/**
	 * Sorts the selected primary key ids by the order of attributes in {@link SortIndex}.
	 */
	@Nonnull
	private int[] sortOutputBasedOnIndex(
		@Nonnull QueryContext queryContext, int startIndex, int endIndex, @Nonnull Bitmap selectedRecordIds
	) {
		final int[] entireResult = selectedRecordIds.getArray();
		final int length = Math.min(entireResult.length, endIndex - startIndex);
		if (length < 0) {
			throw new IndexOutOfBoundsException("Index: " + startIndex + ", Size: " + entireResult.length);
		}

		// retrieve array of "sorted" primary keys based on data from index
		@SuppressWarnings({"unchecked"})
		final int[] exactPkOrder = Arrays.stream(this.exactOrder)
			.map(sortIndex::getRecordsEqualTo)
			.flatMapToInt(Bitmap::stream)
			.toArray();

		// now sort the real result by the exactPkOrder
		final int lastSortedItem = ArrayUtils.sortAlong(exactPkOrder, entireResult);

		// if there are no more records to sort or no additional sorter is present, return entire result
		if (lastSortedItem + 1 == selectedRecordIds.size() || unknownRecordIdsSorter == NoSorter.INSTANCE) {
			return entireResult;
		} else {
			// otherwise, collect the not sorted record ids
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			for (int i = lastSortedItem; i < entireResult.length; i++) {
				writer.add(entireResult[i]);
			}
			// pass them to another sorter
			final int recomputedStartIndex = Math.max(0, startIndex - lastSortedItem);
			final int recomputedEndIndex = Math.max(0, endIndex - lastSortedItem);
			final int[] unsortedResult = unknownRecordIdsSorter.sortAndSlice(
				queryContext, new ConstantFormula(new BaseBitmap(writer.get())),
				recomputedStartIndex, recomputedEndIndex
			);
			// and combine with our result
			System.arraycopy(unsortedResult, 0, entireResult, lastSortedItem, unsortedResult.length);
			return entireResult;
		}
	}

	/**
	 * Sorts the selected primary key ids by the order of attributes retrieved from prefetched entities and compared
	 * by comparator in the place.
	 */
	@Nonnull
	private int[] sortOutputByPrefetchedEntities(
		@Nonnull QueryContext queryContext, int startIndex, int endIndex, @Nonnull Bitmap selectedRecordIds
	) {
		// collect entity primary keys
		final OfInt it = selectedRecordIds.iterator();
		final List<EntityContract> entities = new ArrayList<>(selectedRecordIds.size());
		while (it.hasNext()) {
			int id = it.next();
			entities.add(queryContext.translateToEntity(id));
		}

		// create the comparator instance
		final AttributePositionComparator entityComparator = new AttributePositionComparator(
			attributeName, exactOrder
		);

		// sort the entities by the comparator
		entities.sort(entityComparator);

		// collect entity primary keys that miss the attribute we sort along
		int notFoundRecordsCnt = 0;
		final RoaringBitmap notFoundRecords = new RoaringBitmap();
		for (EntityContract entityContract : entityComparator.getNonSortedEntities()) {
			if (notFoundRecords.checkedAdd(queryContext.translateEntity(entityContract))) {
				notFoundRecordsCnt++;
			}
		}

		// collect the result
		final AtomicInteger index = new AtomicInteger();
		final int[] result = new int[selectedRecordIds.size()];
		entities.subList(0, selectedRecordIds.size() - notFoundRecordsCnt)
			.stream()
			.skip(startIndex)
			.limit((long) endIndex - startIndex)
			.mapToInt(queryContext::translateEntity)
			.forEach(pk -> result[index.getAndIncrement()] = pk);

		// and return appending the not sorted records
		return returnResultAppendingUnknown(
			queryContext,
			new SortResult(result, index.get()),
			notFoundRecords,
			unknownRecordIdsSorter,
			startIndex, endIndex
		);
	}

	/**
	 * The {@link EntityComparator} implementation that compares two attributes of the {@link EntityContract}, but
	 * tracks the entities that miss the attribute whatsoever.
	 */
	@SuppressWarnings({"rawtypes", "ObjectInstantiationInEqualsHashCode", "ComparatorNotSerializable"})
	@RequiredArgsConstructor
	private static class AttributePositionComparator implements EntityComparator {
		private final String attributeName;
		private final Comparable[] attributeValues;
		private CompositeObjectArray<EntityContract> nonSortedEntities;

		@Nonnull
		@Override
		public Iterable<EntityContract> getNonSortedEntities() {
			return ofNullable((Iterable<EntityContract>) nonSortedEntities)
				.orElse(Collections.emptyList());
		}

		@Override
		public int compare(EntityContract o1, EntityContract o2) {
			final Serializable attribute1 = o1.getAttribute(attributeName);
			final Serializable attribute2 = o2.getAttribute(attributeName);
			if (attribute1 == null && attribute2 == null) {
				this.nonSortedEntities = ofNullable(this.nonSortedEntities)
					.orElseGet(() -> new CompositeObjectArray<>(EntityContract.class));
				this.nonSortedEntities.add(o1);
				this.nonSortedEntities.add(o2);
				return 0;
			} else if (attribute1 == null) {
				this.nonSortedEntities = ofNullable(this.nonSortedEntities)
					.orElseGet(() -> new CompositeObjectArray<>(EntityContract.class));
				this.nonSortedEntities.add(o1);
				return -1;
			} else if (attribute2 == null) {
				this.nonSortedEntities = ofNullable(this.nonSortedEntities)
					.orElseGet(() -> new CompositeObjectArray<>(EntityContract.class));
				this.nonSortedEntities.add(o2);
				return 1;
			} else {
				final int attribute1Index = ArrayUtils.indexOf(attribute1, attributeValues);
				final int attribute2Index = ArrayUtils.indexOf(attribute2, attributeValues);
				return Integer.compare(attribute1Index, attribute2Index);
			}
		}

	}

}
