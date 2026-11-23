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

package io.evitadb.core.query.sort.attribute.comparator;


import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor.ChainLookup;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;

/**
 * The ReferencePredecessorComparator is an implementation of the {@link ReferenceComparator} interface that allows
 * sorting of {@link ReferenceContract} instances based on their predecessor relationships in the reference chain.
 * This comparator takes into consideration a specified attribute key, locale, and provides the sorting logic
 * via a {@link ReferenceOrderByVisitor}, the scope of the chain partition, and a supplier for sorted records.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@NotThreadSafe
@SuppressWarnings("ComparatorNotSerializable")
public class ReferencePredecessorComparator implements ReferenceComparator {
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
	private final Function<SortedRecordsSupplierFactory, SortedRecordsProvider> sortedRecordsSupplierProvider;
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
	 * Index scopes in which the ordering was translated.
	 */
	private final Set<Scope> translatedScopes;
	/**
	 * Schemas validated during translation, with a fixed lookup scope for reflected references.
	 */
	private final ChainLookup chainLookup;
	/**
	 * Scope of the chain partition used for the current owner.
	 */
	private Scope lookupScope;
	/**
	 * Whether ranking uses a chain position instead of checking for a predecessor value on the row.
	 */
	private final boolean requiresPosition;
	/**
	 * A cache used to store the record positions for a given primary key.
	 */
	private final IntIntMap pkToRecordPositionCache = new IntIntHashMap(128);

	/**
	 * Creates a reference chain ordering in the visitor's current scopes.
	 *
	 * @param referenceSchema schema of the references being ordered
	 * @param attributeName predecessor attribute
	 * @param locale locale of the predecessor attribute, or `null`
	 * @param referenceOrderByVisitor visitor used for chain lookup
	 * @param sortedRecordsSupplierProvider provider of chain positions in the requested direction
	 * @param comparabilityResolver checks whether two rows can be compared by chain position
	 * @param primaryKeyResolver selects the record primary key for chain position lookup
	 * @param requiresPosition whether ranking uses a chain position instead of a predecessor value
	 */
	public ReferencePredecessorComparator(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull ReferenceOrderByVisitor referenceOrderByVisitor,
		@Nonnull Function<SortedRecordsSupplierFactory, SortedRecordsProvider> sortedRecordsSupplierProvider,
		@Nonnull BiPredicate<RepresentativeReferenceKey, RepresentativeReferenceKey> comparabilityResolver,
		@Nonnull ToIntBiFunction<Integer, ReferenceKey> primaryKeyResolver,
		boolean requiresPosition
	) {
		this.attributeKey = locale == null ? new AttributeKey(attributeName) : new AttributeKey(attributeName, locale);
		this.referenceOrderByVisitor = referenceOrderByVisitor;
		this.sortedRecordsSupplierProvider = sortedRecordsSupplierProvider;
		this.comparabilityResolver = comparabilityResolver;
		this.primaryKeyResolver = primaryKeyResolver;
		final Set<Scope> scopes = referenceOrderByVisitor.getChainScopes(attributeName);
		this.translatedScopes = scopes.isEmpty() ? Set.of(Scope.DEFAULT_SCOPE) : EnumSet.copyOf(scopes);
		this.chainLookup = referenceOrderByVisitor.translateChainLookup(attributeName, this.translatedScopes);
		this.requiresPosition = requiresPosition;
		if (referenceSchema.getCardinality().allowsDuplicates()) {
			final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
			// The partition key includes representative values even when the response hides them.
			this.representativeKeyCalculator = rc -> new RepresentativeReferenceKey(
				rc.getReferenceKey(),
				rad.getRepresentativeValues(rawReference(rc))
			);
		} else {
			this.representativeKeyCalculator = rc -> new RepresentativeReferenceKey(rc.getReferenceKey());
		}
	}

	/**
	 * Returns the stored row behind a decorated reference, which still carries the attribute values the response
	 * hides.
	 *
	 * @param reference row as handed to the comparator
	 * @return the undecorated row
	 */
	@Nonnull
	private static ReferenceContract rawReference(@Nonnull ReferenceContract reference) {
		return reference instanceof ReferenceDecorator rd ? rd.getDelegate() : reference;
	}

	/**
	 * Prepares chain lookups for the owner and clears record positions cached for the previous owner.
	 *
	 * @param ownerPrimaryKey primary key of the owner
	 * @param ownerScope scope of the owner
	 */
	@Override
	public void prepareOwner(int ownerPrimaryKey, @Nonnull Scope ownerScope) {
		final Scope reflectedScope = this.chainLookup.reflectedScope();
		if (reflectedScope == null) {
			Assert.isPremiseValid(
				this.translatedScopes.contains(ownerScope),
				() -> "Reference chain ordering was not translated for the owner's scope " + ownerScope + "."
			);
			this.lookupScope = ownerScope;
		} else {
			this.lookupScope = reflectedScope;
		}
		this.pkToRecordPositionCache.clear();
		this.entityPrimaryKey = ownerPrimaryKey;
	}

	/**
	 * Checks the stored value of a plain predecessor or the position of a referenced entity predecessor.
	 *
	 * @param reference row to classify
	 * @return whether the comparator can rank this row
	 */
	@Override
	public boolean canRank(@Nonnull ReferenceContract reference) {
		if (this.requiresPosition) {
			return getRecordPosition(this.representativeKeyCalculator.apply(reference)) >= 0;
		}
		final AttributeValue predecessor = rawReference(reference).getAttributeValue(this.attributeKey).orElse(null);
		return predecessor != null && !predecessor.dropped() && predecessor.value() != null;
	}

	/**
	 * Compares references by chain position when comparable, otherwise by their representative keys.
	 *
	 * @param o1 first row
	 * @param o2 second row
	 * @return the order of the rows
	 */
	@Override
	public int compare(@Nullable ReferenceContract o1, @Nullable ReferenceContract o2) {
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
				if (position1 < 0 && position2 < 0) {
					return Integer.compare(referenceKey1.primaryKey(), referenceKey2.primaryKey());
				}
				return Integer.compare(position1, position2);
			} else {
				// if the references relate to different entities, compare the primary keys of the references
				return RepresentativeReferenceKey.GENERIC_COMPARATOR.compare(referenceKey1, referenceKey2);
			}
		}
	}

	/**
	 * Retrieves the record position associated with the specified {@link ReferenceKey} in the prepared scope.
	 *
	 * The method looks up the position using a cache. If the position is not found in the cache,
	 * it computes the position by querying the respective chain index and updates the cache.
	 *
	 * @param representativeReferenceKey The key referencing a specific record.
	 * @return The position of the record identified by the given reference key, or a computed position
	 *         if not found in the cache.
	 */
	private int getRecordPosition(@Nonnull RepresentativeReferenceKey representativeReferenceKey) {
		final int pkToLookup = this.primaryKeyResolver.applyAsInt(
			this.entityPrimaryKey, representativeReferenceKey.referenceKey()
		);
		final int position = this.pkToRecordPositionCache.getOrDefault(pkToLookup, -1);
		if (position == -1) {
			final SortedRecordsSupplierFactory chainIndex = this.referenceOrderByVisitor.getChainIndex(
				this.entityPrimaryKey, representativeReferenceKey, this.attributeKey, this.lookupScope, this.chainLookup
			).orElse(null);
			final SortedRecordsProvider sortedRecordsSupplier = chainIndex == null ? SortedRecordsProvider.EMPTY : this.sortedRecordsSupplierProvider.apply(chainIndex);
			// resolve the position directly (tree-direct or array-backed); -1 (not cached) is reserved by this cache,
			// so an absent record (POSITION_NOT_FOUND == -1) is stored as -2
			final int resolvedPosition = sortedRecordsSupplier.positionOf(pkToLookup);
			final int computedPosition = resolvedPosition == SortedRecordsProvider.POSITION_NOT_FOUND ? -2 : resolvedPosition;
			this.pkToRecordPositionCache.put(pkToLookup, computedPosition);
			return computedPosition;
		} else {
			return position;
		}
	}

}
