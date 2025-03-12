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
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.ReferenceSortedRecordsSupplier;
import io.evitadb.index.bitmap.Bitmap;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
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
	 * Name of the attribute this comparator is used for.
	 */
	private final String attributeName;
	/**
	 * Optional reference to reference schema, if the attribute is a reference attribute.
	 */
	@Nullable private final ReferenceSchemaContract referenceSchema;
	/**
	 * Optional reference to entity schema, the reference is targeting (null if the reference is null, or targets
	 * non-managed entity type).
	 */
	@Nullable private final EntitySchemaContract referencedEntitySchema;
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
	private CompositeObjectArray<EntityContract> nonSortedEntities;
	/**
	 * Estimated count of entities used for initializing data structures such as cache.
	 */
	private int estimatedCount = 100;
	/**
	 * Cache for storing predicates that filter {@link SortedRecordsProvider} instances based on the
	 * reference schema and attribute name.
	 */
	private IntObjectMap<Predicate<SortedRecordsProvider>> entityPredicateCache;
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
		this.entityPredicateCache = new IntObjectHashMap<>(entityCount);
	}

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final SortedRecordsProvider[] sortedRecordsProviders = getSortedRecordsProviders();
		int o1FoundInProvider = -1;
		int o2FoundInProvider = -1;
		int result = 0;

		final Predicate<SortedRecordsProvider> srpPredicate1 = createSortedRecordsProviderPredicateFor(o1, sortedRecordsProviders);
		final Predicate<SortedRecordsProvider> srpPredicate2 = createSortedRecordsProviderPredicateFor(o2, sortedRecordsProviders);

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
			final int o1Index = o1FoundInProvider > -1 ? -1 : srpPredicate1.test(sortedRecordsProvider) ? computeIfAbsent(this.cache[i], o1.getPrimaryKeyOrThrowException(), allRecords::indexOf) : -1;
			final int o2Index = o2FoundInProvider > -1 ? -1 : srpPredicate2.test(sortedRecordsProvider) ? computeIfAbsent(this.cache[i], o2.getPrimaryKeyOrThrowException(), allRecords::indexOf) : -1;
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
	 * Creates a predicate for filtering {@link SortedRecordsProvider} instances that should be applied
	 * for the given {@link EntityContract} entity and the provided array of {@link SortedRecordsProvider}.
	 * This method takes into account the reference schema's cardinality and the presence of the specified
	 * attribute to determine which sorted records provider(s) to filter.
	 *
	 * @param entity the entity for which the predicate is being created
	 * @param sortedRecordsProviders an array of {@link SortedRecordsProvider} instances to filter
	 * @return a predicate that filters the {@link SortedRecordsProvider} instances based on the
	 *         specified criteria for the given entity
	 */
	@Nonnull
	private Predicate<SortedRecordsProvider> createSortedRecordsProviderPredicateFor(
		@Nonnull EntityContract entity,
		@Nonnull SortedRecordsProvider[] sortedRecordsProviders
	) {
		final Predicate<SortedRecordsProvider> cachedPredicate = this.entityPredicateCache.get(entity.getPrimaryKeyOrThrowException());
		if (cachedPredicate == null) {
			final Predicate<SortedRecordsProvider> calculatedPredicate;
			if (this.referenceSchema == null || this.referenceSchema.getCardinality() == Cardinality.ZERO_OR_ONE || this.referenceSchema.getCardinality() == Cardinality.EXACTLY_ONE) {
				return srp -> true;
			} else {
				final Collection<ReferenceContract> references = entity.getReferences(this.referenceSchema.getName());
				if (references.size() <= 1) {
					calculatedPredicate = srp -> true;
				} else {
					final ReferenceKey referenceKey;
					if (this.referencedEntitySchema != null && this.referencedEntitySchema.isHierarchyIndexedInAnyScope()) {
						// we rely on the fact that the sorted record providers are sorted by the deep-first search order
						// of the hierarchy here (or other sort criteria user defined)
						referenceKey = Arrays.stream(sortedRecordsProviders)
							.filter(ReferenceSortedRecordsSupplier.class::isInstance)
							.map(ReferenceSortedRecordsSupplier.class::cast)
							.filter(
								rsrp -> {
									final ReferenceKey theRefKey = rsrp.getReferenceKey();
									return entity.getReference(theRefKey.referenceName(), theRefKey.primaryKey())
										.map(it -> it.getAttribute(this.attributeName) != null)
										.orElse(null);
								})
							.findFirst()
							.map(ReferenceSortedRecordsSupplier::getReferenceKey)
							.orElse(null);
					} else {
						referenceKey = references.stream()
							.filter(it -> it.getAttribute(this.attributeName) != null)
							.map(ReferenceContract::getReferenceKey)
							.findFirst()
							.orElse(null);
					}
					calculatedPredicate = referenceKey == null ?
						// if the reference key is null, we can use the default predicate
						srp -> true :
						// otherwise we must accept only the sorted records provider that matches the reference key
						srp -> srp instanceof ReferenceSortedRecordsSupplier rsrp && rsrp.getReferenceKey().equals(referenceKey);
				}
				this.entityPredicateCache.put(entity.getPrimaryKeyOrThrowException(), calculatedPredicate);
				return calculatedPredicate;
			}
		} else {
			return cachedPredicate;
		}
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
