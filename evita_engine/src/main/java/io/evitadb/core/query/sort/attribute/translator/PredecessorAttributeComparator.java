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

package io.evitadb.core.query.sort.attribute.translator;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter;
import io.evitadb.index.array.CompositeObjectArray;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.bitmap.Bitmap;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/**
 * This implementation of {@link EntityComparator} is used to sort entities by the position of their predecessors in
 * the {@link ChainIndex}. This should be still way faster than masking the pre-sorted bitmaps in the standard
 * ordering by the index content (see {@link PreSortedRecordsSorter}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@SuppressWarnings("ComparatorNotSerializable")
@RequiredArgsConstructor
public class PredecessorAttributeComparator implements EntityComparator {
	private final Supplier<SortedRecordsProvider[]> sortedRecordsSupplier;
	private SortedRecordsProvider[] resolvedSortedRecordsProviders;
	private CompositeObjectArray<EntityContract> nonSortedEntities;
	private int estimatedCount = 100;
	private IntIntMap cache;

	@Nonnull
	@Override
	public Iterable<EntityContract> getNonSortedEntities() {
		return nonSortedEntities == null ? Collections.emptyList() : nonSortedEntities;
	}

	@Override
	public void prepareFor(int entityCount) {
		this.estimatedCount = entityCount;
	}

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final SortedRecordsProvider[] sortedRecordsProviders = getSortedRecordsProviders();
		boolean o1Found = false;
		boolean o2Found = false;
		int result = 0;
		// scan all providers
		for (SortedRecordsProvider sortedRecordsProvider : sortedRecordsProviders) {
			if (cache == null) {
				// let's create the cache with estimated size multiply 5 expected steps for binary search
				cache = new IntIntHashMap(estimatedCount * 5);
			}
			// and try to find primary keys of both entities in each provider
			final Bitmap allRecords = sortedRecordsProvider.getAllRecords();
			final int o1Index = o1Found ? -1 : computeIfAbsent(cache, o1.getPrimaryKey(), allRecords::indexOf);
			final int o2Index = o2Found ? -1 : computeIfAbsent(cache, o2.getPrimaryKey(), allRecords::indexOf);
			// if both entities are found in the same provider, compare their positions
			if (o1Index >= 0 && o2Index >= 0) {
				result = Integer.compare(
					sortedRecordsProvider.getRecordPositions()[o1Index],
					sortedRecordsProvider.getRecordPositions()[o2Index]
				);
				o1Found = true;
				o2Found = true;
			} else if (o1Index >= 0) {
				// if only one entity is found, it is considered to be smaller than the other one
				result = result == 0 ? 1 : result;
				o1Found = true;
			} else if (o2Index >= 0) {
				// if only one entity is found, it is considered to be smaller than the other one
				result = result == 0 ? -1 : result;
				o2Found = true;
			}
			// if both entities are found, we can stop searching
			if (o1Found && o2Found) {
				break;
			}
		}
		if (!(o1Found || o2Found) && nonSortedEntities == null) {
			// if any of the entities is not found, and we don't have the container to store them, create it
			nonSortedEntities = new CompositeObjectArray<>(EntityContract.class);
		}
		// if any of the entities is not found, store it in the container
		if (!o1Found) {
			nonSortedEntities.add(o1);
		}
		if (!o2Found) {
			nonSortedEntities.add(o2);
		}
		// return the result
		return result;
	}

	private SortedRecordsProvider[] getSortedRecordsProviders() {
		if (resolvedSortedRecordsProviders == null) {
			resolvedSortedRecordsProviders = sortedRecordsSupplier.get();
		}
		return resolvedSortedRecordsProviders;
	}

	/**
	 * This method is used to cache the results of the `indexOf` method. It is used to speed up the
	 * sorting process.
	 *
	 * @param cache        cache to use
	 * @param primaryKey   primary key of the entity to find
	 * @param indexLocator function to compute the index of the entity
	 * @return index of the entity
	 */
	private static int computeIfAbsent(@Nonnull IntIntMap cache, @Nonnull Integer primaryKey, @Nonnull IntUnaryOperator indexLocator) {
		final int result = cache.get(primaryKey);
		// when the value was not found 0 is returned
		if (result == 0) {
			final int computedIndex = indexLocator.applyAsInt(primaryKey);
			// if the index was computed as 0 we need to remap it to some other "rare" value to distinguish it from NULL value
			cache.put(primaryKey, computedIndex == 0 ? Integer.MIN_VALUE : computedIndex);
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
