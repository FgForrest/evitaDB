/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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


import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.index.RepresentativeReferenceKey;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.SortedRecordsSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;

/**
 * The ReferencePredecessorComparator is an implementation of the {@link ReferenceComparator} interface that allows
 * sorting of {@link ReferenceContract} instances based on their predecessor relationships in the reference chain.
 * This comparator takes into consideration a specified attribute key, locale, and provides the sorting logic
 * via a specified {@link ReferenceOrderByVisitor}, and a supplier for sorted records.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@SuppressWarnings("ComparatorNotSerializable")
public class ReferencePredecessorComparator implements ReferenceComparator, ReferenceComparator.EntityPrimaryKeyAwareComparator {
	@Nullable private final ReferenceComparator nextComparator;
	/**
	 * The function used to calculate the representative key for a given reference.
	 */
	private final Function<ReferenceContract, RepresentativeReferenceKey> representativeKeyCalculator;
	/**
	 * The attribute key used to identify the attribute by which the references are sorted.
	 */
	private final AttributeKey attributeKey;
	/**
	 * The visitor used to retrieve the chain index for a given entity primary key and reference key.
	 */
	private final ReferenceOrderByVisitor referenceOrderByVisitor;
	/**
	 * The supplier used to provide sorted records for a given chain index.
	 */
	private final Function<ChainIndex, SortedRecordsSupplier> sortedRecordsSupplierProvider;
	/**
	 * The predicate used to resolve the comparability of two reference keys.
	 */
	private final BiPredicate<RepresentativeReferenceKey, RepresentativeReferenceKey> comparabilityResolver;
	/**
	 * The function used to resolve the primary key of an entity based on the entity primary key and reference key.
	 */
	private final ToIntBiFunction<Integer, ReferenceKey> primaryKeyResolver;
	/**
	 * The entity primary key used to resolve the record position for a given reference key.
	 */
	private Integer entityPrimaryKey;
	/**
	 * A set containing references that have not been sorted.
	 */
	private IntHashSet nonSortedReferences;
	/**
	 * A cache used to store the record positions for a given primary key.
	 */
	private final IntIntMap pkToRecordPositionCache = new IntIntHashMap(128);

	public ReferencePredecessorComparator(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull ReferenceOrderByVisitor referenceOrderByVisitor,
		@Nonnull Function<ChainIndex, SortedRecordsSupplier> sortedRecordsSupplierProvider,
		@Nonnull BiPredicate<RepresentativeReferenceKey, RepresentativeReferenceKey> comparabilityResolver,
		@Nonnull ToIntBiFunction<Integer, ReferenceKey> primaryKeyResolver
	) {
		this.attributeKey = locale == null ? new AttributeKey(attributeName) : new AttributeKey(attributeName, locale);
		this.referenceOrderByVisitor = referenceOrderByVisitor;
		this.sortedRecordsSupplierProvider = sortedRecordsSupplierProvider;
		this.comparabilityResolver = comparabilityResolver;
		this.primaryKeyResolver = primaryKeyResolver;
		this.nextComparator = null;
		if (referenceSchema.getCardinality().allowsDuplicates()) {
			final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
			this.representativeKeyCalculator = rc -> new RepresentativeReferenceKey(
				rc.getReferenceKey(), rad.getRepresentativeValues(rc)
			);
		} else {
			this.representativeKeyCalculator = rc -> new RepresentativeReferenceKey(rc.getReferenceKey());
		}
	}

	private ReferencePredecessorComparator(
		@Nonnull Function<ReferenceContract, RepresentativeReferenceKey> representativeKeyCalculator,
		@Nullable ReferenceComparator nextComparator,
		@Nonnull AttributeKey attributeKey,
		@Nonnull ReferenceOrderByVisitor referenceOrderByVisitor,
		@Nonnull Function<ChainIndex, SortedRecordsSupplier> sortedRecordsSupplierProvider,
		@Nonnull BiPredicate<RepresentativeReferenceKey, RepresentativeReferenceKey> comparabilityResolver,
		@Nonnull ToIntBiFunction<Integer, ReferenceKey> primaryKeyResolver
	) {
		this.representativeKeyCalculator = representativeKeyCalculator;
		this.nextComparator = nextComparator;
		this.attributeKey = attributeKey;
		this.referenceOrderByVisitor = referenceOrderByVisitor;
		this.sortedRecordsSupplierProvider = sortedRecordsSupplierProvider;
		this.comparabilityResolver = comparabilityResolver;
		this.primaryKeyResolver = primaryKeyResolver;
	}

	@Override
	public void setEntityPrimaryKey(int entityPrimaryKey) {
		// clear the cache when the entity primary key is set, cache keys are valid only for the current entity
		this.pkToRecordPositionCache.clear();
		this.entityPrimaryKey = entityPrimaryKey;
	}

	@Override
	public int getNonSortedReferenceCount() {
		return this.nonSortedReferences == null ? 0 : this.nonSortedReferences.size();
	}

	@Nonnull
	@Override
	public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
		return new ReferencePredecessorComparator(
			this.representativeKeyCalculator,
			comparatorForUnknownRecords,
			this.attributeKey,
			this.referenceOrderByVisitor,
			this.sortedRecordsSupplierProvider,
			this.comparabilityResolver,
			this.primaryKeyResolver
		);
	}

	@Nullable
	@Override
	public ReferenceComparator getNextComparator() {
		return this.nextComparator;
	}

	@Override
	public int compare(ReferenceContract o1, ReferenceContract o2) {
		// nulls are sorted at the end of the list
		if (o1 == null && o2 == null) {
			return 0;
		} else if (o1 == null) {
			return -1;
		} else if (o2 == null) {
			return 1;
		} else {
			final RepresentativeReferenceKey referenceKey1 = this.representativeKeyCalculator.apply(o1);
			final RepresentativeReferenceKey referenceKey2 = this.representativeKeyCalculator.apply(o2);
			// first test whether both references relate to the same entity
			if (this.comparabilityResolver.test(referenceKey1, referenceKey2)) {
				final int position1 = getRecordPosition(referenceKey1);
				final int position2 = getRecordPosition(referenceKey2);
				// if both positions are negative, add both references to the non-sorted references set (they were not found)
				if (position1 < 0 && position2 < 0) {
					final IntHashSet nsr = getNonSortedReferences();
					nsr.add(referenceKey1.primaryKey());
					nsr.add(referenceKey2.primaryKey());
					// sort by primary key if both references are non-sorted
					return Integer.compare(referenceKey1.primaryKey(), referenceKey2.primaryKey());
				} else if (position1 < 0) {
					// if the first reference is non-sorted, add it to the non-sorted references set
					final IntHashSet nsr = getNonSortedReferences();
					nsr.add(referenceKey1.primaryKey());
					return -1;
				} else if (position2 < 0) {
					// if the second reference is non-sorted, add it to the non-sorted references set
					final IntHashSet nsr = getNonSortedReferences();
					nsr.add(referenceKey2.primaryKey());
					return 1;
				} else {
					// if both references are found, compare their positions
					return Integer.compare(position1, position2);
				}
			} else {
				// if the references relate to different entities, compare the primary keys of the references
				return RepresentativeReferenceKey.GENERIC_COMPARATOR.compare(referenceKey1, referenceKey2);
			}
		}
	}

	/**
	 * Retrieves a set containing references that have not been sorted.
	 * If the set is not initialized, it initializes a new IntHashSet with a default size.
	 *
	 * @return an IntHashSet containing non-sorted references
	 */
	@Nonnull
	private IntHashSet getNonSortedReferences() {
		this.nonSortedReferences = this.nonSortedReferences == null ?
			new IntHashSet(64) : this.nonSortedReferences;
		return this.nonSortedReferences;
	}

	/**
	 * Retrieves the record position associated with the specified {@link ReferenceKey}.
	 *
	 * The method looks up the position using a cache. If the position is not found in the cache,
	 * it computes the position by querying the respective chain index and updates the cache.
	 *
	 * @param representativeReferenceKey The key referencing a specific record.
	 * @return The position of the record identified by the given reference key, or a computed position
	 *         if not found in the cache.
	 */
	private int getRecordPosition(@Nonnull RepresentativeReferenceKey representativeReferenceKey) {
		final int pkToLookup = this.primaryKeyResolver.applyAsInt(this.entityPrimaryKey, representativeReferenceKey.referenceKey());
		final int position = this.pkToRecordPositionCache.getOrDefault(pkToLookup, -1);
		if (position == -1) {
			final ChainIndex chainIndex = this.referenceOrderByVisitor.getChainIndex(this.entityPrimaryKey, representativeReferenceKey, this.attributeKey).orElse(null);
			final SortedRecordsProvider sortedRecordsSupplier = chainIndex == null ? SortedRecordsProvider.EMPTY : this.sortedRecordsSupplierProvider.apply(chainIndex);
			final int index = sortedRecordsSupplier.getAllRecords().indexOf(pkToLookup);
			final int computedPosition = index < 0 ? -2 : sortedRecordsSupplier.getRecordPositions()[index];
			this.pkToRecordPositionCache.put(pkToLookup, computedPosition);
			return computedPosition;
		} else {
			return position;
		}
	}

}
