/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
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
import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;

import static java.util.Optional.ofNullable;

/**
 * AttributeExactSorter sorter outputs filtered results in an order of the attribute values in {@link #exactOrder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeExactSorter implements Sorter {
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
	private final Serializable[] exactOrder;

	public AttributeExactSorter(
		@Nonnull String attributeName,
		@Nonnull Serializable[] exactOrder,
		@Nonnull SortIndex sortIndex
	) {
		this.attributeName = attributeName;
		this.exactOrder = exactOrder;
		this.sortIndex = sortIndex;
	}

	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		if (sortingContext.queryContext().getPrefetchedEntities() == null) {
			return sortOutputBasedOnIndex(sortingContext, result, skippedRecordsConsumer);
		} else {
			return sortOutputByPrefetchedEntities(sortingContext, result, skippedRecordsConsumer);
		}
	}

	/**
	 * Sorts the selected primary key ids by the order of attributes in {@link SortIndex}.
	 */
	@Nonnull
	private SortingContext sortOutputBasedOnIndex(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final int recomputedStartIndex = sortingContext.recomputedStartIndex();
		final int recomputedEndIndex = sortingContext.recomputedEndIndex();

		final int[] entireResult = sortingContext.nonSortedKeys().getArray();
		final int length = Math.min(entireResult.length, recomputedEndIndex - recomputedStartIndex);
		if (length < 0) {
			throw new IndexOutOfBoundsException("Index: " + recomputedStartIndex + ", Size: " + entireResult.length);
		}

		// retrieve array of "sorted" primary keys based on data from index
		final int[] exactPkOrder = Arrays.stream(this.exactOrder)
			.map(this.sortIndex::getRecordsEqualTo)
			.flatMapToInt(Bitmap::stream)
			.toArray();

		// now sort the real result by the exactPkOrder
		final int lastSortedItem = ArrayUtils.sortAlong(exactPkOrder, entireResult);

		// copy the sorted data to result
		final int toAppend = Math.min(lastSortedItem, recomputedEndIndex - recomputedStartIndex);
		if (skippedRecordsConsumer != null) {
			for (int i = 0; i < Math.min(recomputedStartIndex, entireResult.length); i++) {
				skippedRecordsConsumer.accept(entireResult[i]);
			}
		}
		System.arraycopy(entireResult, recomputedStartIndex, result, sortingContext.peak(), toAppend);

		// if there are no more records to sort or no additional sorter is present, return entire result
		if (lastSortedItem == entireResult.length) {
			return sortingContext.createResultContext(
				EmptyBitmap.INSTANCE,
				toAppend,
				recomputedStartIndex
			);
		} else {
			// otherwise, collect the not sorted record ids
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			for (int i = lastSortedItem; i < entireResult.length; i++) {
				writer.add(entireResult[i]);
			}
			// pass them to another sorter
			final RoaringBitmap outputBitmap = writer.get();
			return sortingContext.createResultContext(
				outputBitmap.isEmpty() ?
					EmptyBitmap.INSTANCE : new BaseBitmap(outputBitmap),
				toAppend,
				recomputedStartIndex
			);
		}
	}

	/**
	 * Sorts the selected primary key ids by the order of attributes retrieved from prefetched entities and compared
	 * by comparator in the place.
	 */
	@Nonnull
	private SortingContext sortOutputByPrefetchedEntities(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final int recomputedStartIndex = sortingContext.recomputedStartIndex();
		final int recomputedEndIndex = sortingContext.recomputedEndIndex();
		final int peak = sortingContext.peak();
		final QueryExecutionContext queryContext = sortingContext.queryContext();

		// collect entity primary keys
		final Bitmap nonSortedKeys = sortingContext.nonSortedKeys();
		final OfInt it = nonSortedKeys.iterator();
		final List<EntityContract> entities = new ArrayList<>(nonSortedKeys.size());
		while (it.hasNext()) {
			int id = it.next();
			entities.add(queryContext.translateToEntity(id));
		}

		// create the comparator instance
		final AttributePositionComparator entityComparator = new AttributePositionComparator(
			this.attributeName, this.exactOrder
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
		final List<EntityContract> finalEntities = entities.subList(0, nonSortedKeys.size() - notFoundRecordsCnt);
		final int skippedRecords = Math.min(recomputedStartIndex, finalEntities.size());
		if (skippedRecordsConsumer != null) {
			for (int i = 0; i < skippedRecords; i++) {
				skippedRecordsConsumer.accept(queryContext.translateEntity(finalEntities.get(i)));
			}
		}
		finalEntities
			.stream()
			.skip(recomputedStartIndex)
			.limit((long) recomputedEndIndex - recomputedStartIndex)
			.mapToInt(queryContext::translateEntity)
			.forEach(pk -> result[peak + index.getAndIncrement()] = pk);

		// and return appending the not sorted records
		return sortingContext.createResultContext(
			notFoundRecords.isEmpty() ?
				EmptyBitmap.INSTANCE : new BaseBitmap(notFoundRecords),
			index.get(),
			skippedRecords
		);
	}

	/**
	 * The {@link EntityComparator} implementation that compares two attributes of the {@link EntityContract}, but
	 * tracks the entities that miss the attribute whatsoever.
	 */
	@SuppressWarnings({"ObjectInstantiationInEqualsHashCode", "ComparatorNotSerializable"})
	@RequiredArgsConstructor
	private static class AttributePositionComparator implements EntityComparator {
		private final String attributeName;
		private final Serializable[] attributeValues;
		private int estimatedCount = 100;
		private ObjectIntMap<Serializable> cache;
		@Nullable private CompositeObjectArray<EntityContract> nonSortedEntities;

		@Override
		public void prepareFor(int entityCount) {
			this.estimatedCount = entityCount;
			this.nonSortedEntities = null;
		}

		@Nonnull
		@Override
		public Iterable<EntityContract> getNonSortedEntities() {
			return ofNullable((Iterable<EntityContract>) this.nonSortedEntities)
				.orElse(Collections.emptyList());
		}

		@Override
		public int compare(EntityContract o1, EntityContract o2) {
			final Serializable attribute1 = o1.getAttribute(this.attributeName);
			final Serializable attribute2 = o2.getAttribute(this.attributeName);
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
				if (this.cache == null) {
					// let's create the cache with estimated size multiply 5 expected steps for binary search
					this.cache = new ObjectIntHashMap<>(this.estimatedCount * 5);
				}
				final int attribute1Index = computeIfAbsent(this.cache, attribute1, it -> ArrayUtils.indexOf(it, this.attributeValues));
				final int attribute2Index = computeIfAbsent(this.cache, attribute2, it -> ArrayUtils.indexOf(it, this.attributeValues));
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
