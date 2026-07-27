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

package io.evitadb.core.query.sort.attribute.comparator;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.attribute.ChainIndex;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.function.Consumer;
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
	/**
	 * Sink appending an entity that no provider could place to {@link #nonSortedEntities} (created on first use).
	 * Allocated once so {@link #comparePositionsAcrossProviders} can hand off unsortable entities without allocating
	 * a capturing lambda on the comparison hot path.
	 */
	private final Consumer<EntityContract> unsortedCollector = this::addUnsorted;

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
		// scan all providers
		if (this.cache == null) {
			//noinspection ObjectInstantiationInEqualsHashCode
			this.cache = new IntIntMap[sortedRecordsProviders.length];
		}
		return comparePositionsAcrossProviders(
			sortedRecordsProviders, this.cache, 0, sortedRecordsProviders.length,
			this.estimatedCount, o1, o2, this.unsortedCollector
		);
	}

	/**
	 * Appends an entity that none of the scanned providers could place to {@link #nonSortedEntities}, lazily
	 * creating the container on first use.
	 *
	 * @param entity the entity to park at the end of the sorted result
	 */
	private void addUnsorted(@Nonnull EntityContract entity) {
		if (this.nonSortedEntities == null) {
			//noinspection ObjectInstantiationInEqualsHashCode
			this.nonSortedEntities = new CompositeObjectArray<>(EntityContract.class);
		}
		this.nonSortedEntities.add(entity);
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
	 * Shared K-way provider scan behind both predecessor comparators. Walks the sorted-records providers in the
	 * index range `[fromIndex, toIndex)`, resolving each of the two entities' positions within the first provider
	 * that contains them (positions are memoized per provider in `cache`), and returns the comparison result
	 * following the predecessor-ordering contract:
	 *
	 * - both entities found in the same provider: ordered by their positions,
	 * - only one found: that one sorts first,
	 * - found in different providers: the earlier (lower-index) provider wins.
	 *
	 * Every entity not found in any scanned provider is handed to `unsortedCollector` so the caller can park it at
	 * the end of the sorted result.
	 *
	 * @param sortedRecordsProviders providers to scan, ordered by precedence
	 * @param cache                  per-provider position cache, lazily populated (must span `[fromIndex, toIndex)`)
	 * @param fromIndex              first provider index to scan (inclusive)
	 * @param toIndex                provider index to stop at (exclusive)
	 * @param estimatedCount         expected entity count, used to size a freshly created per-provider cache
	 * @param o1                     first entity to compare
	 * @param o2                     second entity to compare
	 * @param unsortedCollector      sink for entities not found in any scanned provider
	 * @return the comparison result following the contract above
	 */
	static int comparePositionsAcrossProviders(
		@Nonnull SortedRecordsProvider[] sortedRecordsProviders,
		@Nonnull IntIntMap[] cache,
		int fromIndex,
		int toIndex,
		int estimatedCount,
		@Nonnull EntityContract o1,
		@Nonnull EntityContract o2,
		@Nonnull Consumer<EntityContract> unsortedCollector
	) {
		int result = 0;
		int o1FoundInProvider = -1;
		int o2FoundInProvider = -1;
		for (int i = fromIndex; i < toIndex; i++) {
			final SortedRecordsProvider sortedRecordsProvider = sortedRecordsProviders[i];
			if (cache[i] == null) {
				// let's create the cache with estimated size multiply 5 expected steps for binary search
				//noinspection ObjectAllocationInLoop
				cache[i] = new IntIntHashMap(estimatedCount * 5);
			}
			// resolve each entity's sorted position directly (tree-direct or array-backed, per the provider);
			// the cache stores the resolved position and POSITION_NOT_FOUND (-1) for absent records
			final int o1Position = o1FoundInProvider > -1 ? -1
				: computeIfAbsent(cache[i], o1.getPrimaryKeyOrThrowException(), sortedRecordsProvider::positionOf);
			final int o2Position = o2FoundInProvider > -1 ? -1
				: computeIfAbsent(cache[i], o2.getPrimaryKeyOrThrowException(), sortedRecordsProvider::positionOf);
			// if both entities are found in the same provider, compare their positions
			if (o1Position >= 0 && o2Position >= 0) {
				result = Integer.compare(o1Position, o2Position);
				o1FoundInProvider = i;
				o2FoundInProvider = i;
			} else if (o1Position >= 0) {
				// if only one entity is found, it is considered to be smaller than the other one
				result = result == 0 ? 1 : result;
				o1FoundInProvider = i;
			} else if (o2Position >= 0) {
				// if only one entity is found, it is considered to be smaller than the other one
				result = result == 0 ? -1 : result;
				o2FoundInProvider = i;
			}
			// if both entities are found, we can stop searching
			if (o1FoundInProvider > -1 && o2FoundInProvider > -1) {
				break;
			}
		}
		// hand over any entity we could not place so the caller can park it at the end of the result
		if (o1FoundInProvider == -1) {
			unsortedCollector.accept(o1);
		}
		if (o2FoundInProvider == -1) {
			unsortedCollector.accept(o2);
		}
		// when the entities were found in different providers, order them by provider precedence
		if (o1FoundInProvider != o2FoundInProvider) {
			result = Integer.compare(o1FoundInProvider, o2FoundInProvider);
		}
		return result;
	}

	/**
	 * Memoizes each entity's resolved sorted position per provider, so a primary key is located only
	 * once per sort. A resolved position of `0` is stored as the reserved `Integer.MIN_VALUE` remap
	 * to stay distinct from the HPPC map's absent-default `0`, and decoded back to `0` on read;
	 * `POSITION_NOT_FOUND` (`-1`) and positive positions are stored verbatim and memoized.
	 *
	 * @param cache        cache to use
	 * @param primaryKey   primary key of the entity to find
	 * @param indexLocator function to compute the sorted position of the entity
	 * @return sorted position of the entity
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
