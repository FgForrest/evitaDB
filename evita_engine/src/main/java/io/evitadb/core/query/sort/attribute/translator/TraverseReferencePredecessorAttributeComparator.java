
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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.TraverseByEntityProperty;
import io.evitadb.api.requestResponse.chunk.OffsetAndLimit;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.core.query.sort.EntityReferenceSensitiveComparator;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter.MergeMode;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static io.evitadb.core.query.sort.attribute.MergedSortedRecordsSupplierSorter.createSortedRecordsOffsets;
import static io.evitadb.core.query.sort.attribute.translator.PredecessorAttributeComparator.computeIfAbsent;

/**
 * Attribute comparator sorts entities according to a specified attribute value. It needs to provide a function for
 * accessing the entity attribute value and the simple {@link Comparable} comparator implementation. This implementation
 * adheres to {@link MergeMode#APPEND_ALL} which relates to {@link TraverseByEntityProperty} ordering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class TraverseReferencePredecessorAttributeComparator
	extends AbstractReferenceAttributeComparator
	implements EntityReferenceSensitiveComparator {
	@Serial private static final long serialVersionUID = 2199278500724685085L;
	/**
	 * Supplier providing access to sorted record indexes.
	 */
	private final Supplier<SortedRecordsProvider[]> sortedRecordsSupplier;
	/**
	 * Initialized predicate function that relates to the {@link ReferenceSchema}.
	 */
	private final Predicate<ReferenceContract> pickerPredicate;
	/**
	 * The id of the referenced entity that is being traversed.
	 */
	@Nullable private RepresentativeReferenceKey referenceKey;
	/**
	 * Memoized information about sorted record providers.
	 */
	private SortedRecordsProvider[] sortedRecordsProviders;
	/**
	 * Memoized information of the sorted records offsets (reference key indexes).
	 */
	@Nullable private LinkedHashMap<RepresentativeReferenceKey, OffsetAndLimit> sortedRecordsOffsets;
	/**
	 * Array of caches storing the index positions of entities for each {@link SortedRecordsProvider}.
	 */
	private IntIntMap[] cache;

	public TraverseReferencePredecessorAttributeComparator(
		@Nonnull String attributeName,
		@Nonnull Class<?> type,
		@Nonnull ReferenceSchema referenceSchema,
		@Nullable Locale locale,
		@Nonnull OrderDirection orderDirection,
		@Nonnull Supplier<SortedRecordsProvider[]> sortedRecordsSupplier
	) {
		super(
			attributeName,
			type,
			referenceSchema,
			locale,
			orderDirection
		);
		this.sortedRecordsSupplier = sortedRecordsSupplier;
		if (referenceSchema.getCardinality().allowsDuplicates()) {
			final RepresentativeAttributeDefinition rad = this.referenceSchema.getRepresentativeAttributeDefinition();
			this.pickerPredicate = reference -> {
				if (this.referenceKey != null) {
					return this.referenceKey.referenceKey().equalsInGeneral(reference.getReferenceKey()) &&
						Arrays.equals(
							this.referenceKey.representativeAttributeValues(),
							rad.getRepresentativeValues(reference)
						);
				}
				final Set<RepresentativeReferenceKey> sortedRRKs = Objects.requireNonNull(this.sortedRecordsOffsets)
				                                                          .keySet();
				for (RepresentativeReferenceKey referenceKey : sortedRRKs) {
					if (
						referenceKey.referenceKey().equalsInGeneral(reference.getReferenceKey()) &&
						Arrays.equals(
							referenceKey.representativeAttributeValues(),
							rad.getRepresentativeValues(reference)
						)
					) {
						return true;
					}
				}

				return false;
			};
		} else {
			this.pickerPredicate = referenceContract -> true;
		}
	}

	@Override
	public void withReferencedEntityId(@Nonnull RepresentativeReferenceKey referenceKey, @Nonnull Runnable lambda) {
		try {
			Assert.isPremiseValid(this.referenceKey == null, "Cannot set referenced entity id twice!");
			Assert.isPremiseValid(
				this.referenceName.equals(referenceKey.referenceName()),
				"Referenced entity id must be for the same reference!"
			);
			this.referenceKey = referenceKey;
			lambda.run();
		} finally {
			this.referenceKey = null;
		}
	}

	@Nonnull
	@Override
	protected Optional<ReferenceContract> pickReference(@Nonnull EntityContract entity) {
		return // pick first if none is set
			Optional.of(entity.getReferences(this.referenceName))
			        .filter(it -> !it.isEmpty())
			        .map(it -> {
				        for (ReferenceContract reference : it) {
					        if (this.pickerPredicate.test(reference)) {
						        return reference;
					        }
				        }
				        return null;
			        });
	}

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		if (this.sortedRecordsOffsets == null) {
			this.sortedRecordsProviders = this.sortedRecordsSupplier.get();
			this.sortedRecordsOffsets = createSortedRecordsOffsets(this.sortedRecordsProviders);
			//noinspection ObjectInstantiationInEqualsHashCode
			this.cache = new IntIntMap[this.sortedRecordsProviders.length];
		}
		final ReferenceAttributeValue attribute1 = this.attributeValueFetcher.apply(o1);
		final ReferenceAttributeValue attribute2 = this.attributeValueFetcher.apply(o2);
		// to correctly compare the references we need to compare only attributes on the same reference
		final boolean bothAttributesSpecified = attribute1 != null && attribute2 != null;
		final boolean attributesExistOnSameReference = bothAttributesSpecified && attribute1.referencedKey().equals(
			attribute2.referencedKey());
		if (attributesExistOnSameReference) {
			// if the offset is null, the sorted record provider was not found for the given reference key
			final OffsetAndLimit offsetAndLimit = this.sortedRecordsOffsets == null ?
				null : this.sortedRecordsOffsets.get(attribute1.referencedKey());
			if (offsetAndLimit != null) {
				int result = 0;
				int o1FoundInProvider = -1;
				int o2FoundInProvider = -1;
				for (int i = offsetAndLimit.offset(); i < offsetAndLimit.limit(); i++) {
					final SortedRecordsProvider sortedRecordsProvider = this.sortedRecordsProviders[i];
					if (this.cache[i] == null) {
						// let's create the cache with estimated size multiply 5 expected steps for binary search
						//noinspection ObjectAllocationInLoop,ObjectInstantiationInEqualsHashCode
						this.cache[i] = new IntIntHashMap(this.estimatedCount * 5);
					}
					// and try to find primary keys of both entities in each provider
					final Bitmap allRecords = sortedRecordsProvider.getAllRecords();
					// predicates are used sort out the providers that are not relevant for the given entity
					final int o1Index = o1FoundInProvider > -1 ? -1 : computeIfAbsent(
						this.cache[i], o1.getPrimaryKeyOrThrowException(), allRecords::indexOf);
					final int o2Index = o2FoundInProvider > -1 ? -1 : computeIfAbsent(
						this.cache[i], o2.getPrimaryKeyOrThrowException(), allRecords::indexOf);
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
			} else {
				// if they don't share a ref-key we compare them by position of their sorted record providers
				final OffsetAndLimit offsetAndLimit1 = this.sortedRecordsOffsets == null ?
					null : this.sortedRecordsOffsets.get(attribute1.referencedKey());
				final OffsetAndLimit offsetAndLimit2 = this.sortedRecordsOffsets == null ?
					null : this.sortedRecordsOffsets.get(attribute2.referencedKey());
				if (offsetAndLimit1 != null && offsetAndLimit2 != null) {
					return Integer.compare(offsetAndLimit1.offset(), offsetAndLimit2.offset());
				}
			}
			return 0;
		} else if (bothAttributesSpecified) {
			return RepresentativeReferenceKey.GENERIC_COMPARATOR.compare(
				attribute1.referencedKey(),
				attribute2.referencedKey()
			);
		} else if (attribute1 == null && attribute2 != null) {
			return 1;
		} else if (attribute1 != null) {
			return -1;
		} else {
			return 0;
		}
	}

}
