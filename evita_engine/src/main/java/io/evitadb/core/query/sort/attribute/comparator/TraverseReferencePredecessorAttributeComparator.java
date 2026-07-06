
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
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static io.evitadb.core.query.sort.attribute.comparator.PredecessorAttributeComparator.comparePositionsAcrossProviders;
import static io.evitadb.core.query.sort.attribute.sorter.MergedSortedRecordsSupplierSorter.createSortedRecordsOffsets;

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
	 * Sink appending an entity that no provider could place to the inherited {@code nonSortedEntities} container
	 * (created on first use). Allocated once so {@code comparePositionsAcrossProviders} can hand off unsortable
	 * entities without allocating a capturing lambda on the comparison hot path.
	 */
	private final Consumer<EntityContract> unsortedCollector = this::addUnsorted;
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
			this.pickerPredicate = referenceContract ->
				this.referenceKey == null ||
					this.referenceKey.referenceKey().equals(referenceContract.getReferenceKey());
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
		final Collection<ReferenceContract> references = entity.getReferences(this.referenceName);
		for (ReferenceContract reference : references) {
			if (this.pickerPredicate.test(reference)) {
				return Optional.of(reference);
			}
		}
		return Optional.empty();
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
				// scan only the providers belonging to the shared reference key
				return comparePositionsAcrossProviders(
					this.sortedRecordsProviders, this.cache,
					offsetAndLimit.offset(), offsetAndLimit.limit(),
					this.estimatedCount, o1, o2, this.unsortedCollector
				);
			} else {
				// if they don't share a ref-key we compare them by position of their sorted record providers
				final OffsetAndLimit offsetAndLimit1 = this.sortedRecordsOffsets == null
					? null : this.sortedRecordsOffsets.get(attribute1.referencedKey());
				final OffsetAndLimit offsetAndLimit2 = this.sortedRecordsOffsets == null
					? null : this.sortedRecordsOffsets.get(attribute2.referencedKey());
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

	/**
	 * Appends an entity that none of the scanned providers could place to the inherited {@code nonSortedEntities}
	 * container, lazily creating it on first use.
	 *
	 * @param entity the entity to park at the end of the sorted result
	 */
	private void addUnsorted(@Nonnull EntityContract entity) {
		if (this.nonSortedEntities == null) {
			this.nonSortedEntities = new CompositeObjectArray<>(EntityContract.class);
		}
		this.nonSortedEntities.add(entity);
	}

}
