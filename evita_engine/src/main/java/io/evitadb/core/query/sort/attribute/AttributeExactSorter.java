/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query.sort.attribute;

import com.carrotsearch.hppc.ObjectIntHashMap;
import com.carrotsearch.hppc.ObjectIntMap;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.dataType.array.CompositeObjectArray;
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
import java.util.function.ToIntFunction;

import static java.util.Optional.ofNullable;

/**
 * AttributeExactSorter sorter outputs filtered results in an order of the attribute values in {@link #exactOrder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeExactSorter extends AbstractRecordsSorter {
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
	public Sorter cloneInstance() {
		return new AttributeExactSorter(
			attributeName,
			exactOrder,
			sortIndex
		);
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

	@Override
	public int sortAndSlice(@Nonnull QueryExecutionContext queryContext, @Nonnull Formula input, int startIndex, int endIndex, @Nonnull int[] result, int peak) {
		final Bitmap selectedRecordIds = input.compute();
		if (selectedRecordIds.isEmpty()) {
			return 0;
		} else {
			if (queryContext.getPrefetchedEntities() == null) {
				return sortOutputBasedOnIndex(queryContext, startIndex, endIndex, selectedRecordIds, result, peak);
			} else {
				return sortOutputByPrefetchedEntities(queryContext, startIndex, endIndex, selectedRecordIds, result, peak);
			}
		}
	}

	/**
	 * Sorts the selected primary key ids by the order of attributes in {@link SortIndex}.
	 */
	private int sortOutputBasedOnIndex(
		@Nonnull QueryExecutionContext queryContext, int startIndex, int endIndex, @Nonnull Bitmap selectedRecordIds,
		@Nonnull int[] result, int peak
	) {
		final int[] entireResult = selectedRecordIds.getArray();
		final int length = Math.min(entireResult.length, endIndex - startIndex);
		if (length < 0) {
			throw new IndexOutOfBoundsException("Index: " + startIndex + ", Size: " + entireResult.length);
		}

		// retrieve array of "sorted" primary keys based on data from index
		@SuppressWarnings({"unchecked"}) final int[] exactPkOrder = Arrays.stream(this.exactOrder)
			.map(sortIndex::getRecordsEqualTo)
			.flatMapToInt(Bitmap::stream)
			.toArray();

		// now sort the real result by the exactPkOrder
		final int lastSortedItem = ArrayUtils.sortAlong(exactPkOrder, entireResult);

		// copy the sorted data to result
		final int toAppend = Math.min(lastSortedItem, endIndex - startIndex);
		System.arraycopy(entireResult, startIndex, result, peak, toAppend);

		// if there are no more records to sort or no additional sorter is present, return entire result
		if (lastSortedItem == selectedRecordIds.size()) {
			return peak + toAppend;
		} else {
			// otherwise, collect the not sorted record ids
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			for (int i = lastSortedItem; i < entireResult.length; i++) {
				writer.add(entireResult[i]);
			}
			// pass them to another sorter
			final int recomputedStartIndex = Math.max(0, startIndex - lastSortedItem);
			final int recomputedEndIndex = Math.max(0, endIndex - lastSortedItem);
			final RoaringBitmap outputBitmap = writer.get();
			return unknownRecordIdsSorter.sortAndSlice(
				queryContext, outputBitmap.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(new BaseBitmap(outputBitmap)),
				recomputedStartIndex, recomputedEndIndex, result, peak + toAppend
			);
		}
	}

	/**
	 * Sorts the selected primary key ids by the order of attributes retrieved from prefetched entities and compared
	 * by comparator in the place.
	 */
	private int sortOutputByPrefetchedEntities(
		@Nonnull QueryExecutionContext queryContext, int startIndex, int endIndex, @Nonnull Bitmap selectedRecordIds,
		@Nonnull int[] result, int peak
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
		entities.subList(0, selectedRecordIds.size() - notFoundRecordsCnt)
			.stream()
			.skip(startIndex)
			.limit((long) endIndex - startIndex)
			.mapToInt(queryContext::translateEntity)
			.forEach(pk -> result[peak + index.getAndIncrement()] = pk);

		// pass them to another sorter
		final int recomputedStartIndex = Math.max(0, startIndex - index.get());
		final int recomputedEndIndex = Math.max(0, endIndex - index.get());

		// and return appending the not sorted records
		final int[] borrowedBuffer = queryContext.borrowBuffer();
		try {
			return returnResultAppendingUnknown(
				queryContext,
				notFoundRecords,
				unknownRecordIdsSorter,
				recomputedStartIndex, recomputedEndIndex,
				result, peak + index.get(),
				borrowedBuffer
			);
		} finally {
			queryContext.returnBuffer(borrowedBuffer);
		}
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
		private int estimatedCount = 100;
		private ObjectIntMap<Serializable> cache;
		private CompositeObjectArray<EntityContract> nonSortedEntities;

		@Override
		public void prepareFor(int entityCount) {
			this.estimatedCount = entityCount;
		}

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
				// and try to find primary keys of both entities in each provider
				if (cache == null) {
					// let's create the cache with estimated size multiply 5 expected steps for binary search
					cache = new ObjectIntHashMap<>(estimatedCount * 5);
				}
				final int attribute1Index = computeIfAbsent(cache, attribute1, it -> ArrayUtils.indexOf(it, attributeValues));
				final int attribute2Index = computeIfAbsent(cache, attribute2, it -> ArrayUtils.indexOf(it, attributeValues));
				return Integer.compare(attribute1Index, attribute2Index);
			}
		}

		/**
		 * This method is used to cache the results of the `indexOf` method. It is used to speed up the
		 * sorting process.
		 *
		 * @param cache        cache to use
		 * @param attribute    attribute of the entity to find
		 * @param indexLocator function to compute the index of the entity
		 * @return index of the entity
		 */
		private static int computeIfAbsent(@Nonnull ObjectIntMap<Serializable> cache, @Nonnull Serializable attribute, @Nonnull ToIntFunction<Serializable> indexLocator) {
			final int result = cache.get(attribute);
			// when the value was not found 0 is returned
			if (result == 0) {
				final int computedIndex = indexLocator.applyAsInt(attribute);
				// if the index was computed as 0 we need to remap it to some other "rare" value to distinguish it from NULL value
				cache.put(attribute, computedIndex == 0 ? Integer.MIN_VALUE : computedIndex);
				return computedIndex;
			} else if (result == Integer.MIN_VALUE) {
				// when the "rare" value was found - we know it represents index 0
				return 0;
			} else {
				// otherwise cached value was found
				return result;
			}
		}

	}

}
