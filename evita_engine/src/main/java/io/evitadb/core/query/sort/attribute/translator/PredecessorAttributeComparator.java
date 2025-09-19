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

package io.evitadb.core.query.sort.attribute.translator;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.bitmap.Bitmap;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
	/**
	 * Supplier providing an array of {@link SortedRecordsProvider} objects used for sorting.
	 */
	private final Supplier<SortedRecordsProvider[]> sortedRecordsSupplier;
	/**
	 * Cached array of resolved {@link SortedRecordsProvider} objects to avoid repeatedly invoking the supplier.
	 */
	private SortedRecordsProvider[] resolvedSortedRecordsProviders;
	/**
	 * Container for entities that cannot be sorted using the resolved {@link SortedRecordsProvider}.
	 */
	@Nullable private CompositeObjectArray<EntityContract> nonSortedEntities;
	/**
	 * Estimated count of entities used for initializing data structures such as cache.
	 */
	private int estimatedCount = 100;
	/**
	 * Array of caches storing the index positions of entities for each {@link SortedRecordsProvider}.
	 */
	private IntIntMap[] cache;

	@Nonnull
	@Override
	public Iterable<EntityContract> getNonSortedEntities() {
		return this.nonSortedEntities == null ? Collections.emptyList() : this.nonSortedEntities;
	}

	@Override
	public void prepareFor(int entityCount) {
		this.estimatedCount = entityCount;
		this.nonSortedEntities = null;
	}

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final SortedRecordsProvider[] sortedRecordsProviders = getSortedRecordsProviders();
		int o1FoundInProvider = -1;
		int o2FoundInProvider = -1;
		int result = 0;

		// scan all providers
		if (this.cache == null) {
			//noinspection ObjectInstantiationInEqualsHashCode
			this.cache = new IntIntMap[sortedRecordsProviders.length];
		}
		for (int i = 0; i < sortedRecordsProviders.length; i++) {
			final SortedRecordsProvider sortedRecordsProvider = sortedRecordsProviders[i];
			if (this.cache[i] == null) {
				// let's create the cache with estimated size multiply 5 expected steps for binary search
				//noinspection ObjectAllocationInLoop,ObjectInstantiationInEqualsHashCode
				this.cache[i] = new IntIntHashMap(this.estimatedCount * 5);
			}
			// and try to find primary keys of both entities in each provider
			final Bitmap allRecords = sortedRecordsProvider.getAllRecords();
			// predicates are used sort out the providers that are not relevant for the given entity
			final int o1Index = o1FoundInProvider > -1 ? -1 : computeIfAbsent(this.cache[i], o1.getPrimaryKeyOrThrowException(), allRecords::indexOf);
			final int o2Index = o2FoundInProvider > -1 ? -1 : computeIfAbsent(this.cache[i], o2.getPrimaryKeyOrThrowException(), allRecords::indexOf);
			// if both entities are found in the same provider, compare their positions
			if (o1Index >= 0 && o2Index >= 0) {
				result = Integer.compare(
					sortedRecordsProvider.getRecordPositions()[o1Index],
					sortedRecordsProvider.getRecordPositions()[o2Index]
				);
				o1FoundInProvider = i;
				o2FoundInProvider = i;
			} else if (o1Index >= 0) {
				// if only one entity is found, it is considered to be smaller than the other one
				result = result == 0 ? 1 : result;
				o1FoundInProvider = i;
			} else if (o2Index >= 0) {
				// if only one entity is found, it is considered to be smaller than the other one
				result = result == 0 ? -1 : result;
				o2FoundInProvider = i;
			}
			// if both entities are found, we can stop searching
			if (o1FoundInProvider > -1 && o2FoundInProvider > -1) {
				break;
			}
		}
		if (o1FoundInProvider == -1 || o2FoundInProvider == -1 && this.nonSortedEntities == null) {
			// if any of the entities is not found, and we don't have the container to store them, create it
			//noinspection ObjectInstantiationInEqualsHashCode
			this.nonSortedEntities = new CompositeObjectArray<>(EntityContract.class);
		}
		// if any of the entities is not found, store it in the container
		if (o1FoundInProvider == -1) {
			this.nonSortedEntities.add(o1);
		}
		if (o2FoundInProvider == -1) {
			this.nonSortedEntities.add(o2);
		}
		// when both entities are not found in the same provider, the result is invalid
		if (o1FoundInProvider != o2FoundInProvider) {
			// we need to prefer the provider that was found first
			result = Integer.compare(o1FoundInProvider, o2FoundInProvider);
		}
		// return the result
		return result;
	}

	/**
	 * Retrieves an array of {@link SortedRecordsProvider} instances from the {@code sortedRecordsSupplier}.
	 * If the array is not already resolved, it initializes the array by invoking the supplier.
	 *
	 * @return an array of {@link SortedRecordsProvider} containing sorted records
	 */
	@Nonnull
	private SortedRecordsProvider[] getSortedRecordsProviders() {
		if (this.resolvedSortedRecordsProviders == null) {
			this.resolvedSortedRecordsProviders = this.sortedRecordsSupplier.get();
		}
		return this.resolvedSortedRecordsProviders;
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
	static int computeIfAbsent(@Nonnull IntIntMap cache, @Nonnull Integer primaryKey, @Nonnull IntUnaryOperator indexLocator) {
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
