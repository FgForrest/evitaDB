/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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


import com.carrotsearch.hppc.IntIntMap;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static io.evitadb.index.attribute.SortIndex.createComparatorFor;
import static io.evitadb.index.attribute.SortIndex.createNormalizerFor;
import static java.util.Optional.ofNullable;
/**
 * Attribute comparator sorts entities according to a specified attribute value. It needs to provide a function for
 * accessing the entity attribute value and the simple {@link Comparable} comparator implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public abstract class AbstractReferenceAttributeComparator implements EntityComparator, Serializable {
	@Serial private static final long serialVersionUID = -2934397896189324016L;
	/**
	 * Function to fetch the value of the attribute being sorted for a given entity.
	 */
	@Nonnull protected final Function<EntityContract, ReferenceAttributeValue> attributeValueFetcher;
	/**
	 * Comparator for comparing entities by their primary key as a fallback.
	 */
	@Nonnull protected final Comparator<EntityContract> pkComparator;
	/**
	 * Supplier of the index of the referenced id positions in the main ordering.
	 */
	@Nonnull protected final Supplier<IntIntMap> referencePositionMapSupplier;
	/**
	 * Internal storage for entities that could not be fully sorted due to missing attributes.
	 */
	protected CompositeObjectArray<EntityContract> nonSortedEntities;
	/**
	 * Memoized result from {@link #referencePositionMapSupplier} supplier.
	 */
	protected IntIntMap referencePositionMap;
	/**
	 * Cache for storing attribute values of entities to avoid redundant calculations.
	 */
	protected IntObjectMap<ReferenceAttributeValue> cache;

	public AbstractReferenceAttributeComparator(
		@Nonnull String attributeName,
		@Nonnull Class<?> type,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Locale locale,
		@Nonnull OrderDirection orderDirection,
		@Nonnull Supplier<IntIntMap> referencePositionMapSupplier
	) {
		this.referencePositionMapSupplier = referencePositionMapSupplier;
		this.pkComparator = orderDirection == OrderDirection.ASC ?
			Comparator.comparingInt(EntityContract::getPrimaryKeyOrThrowException) :
			Comparator.comparingInt(EntityContract::getPrimaryKeyOrThrowException).reversed();
		final ComparatorSource comparatorSource = new ComparatorSource(
			type, orderDirection, OrderBehaviour.NULLS_LAST
		);
		final Optional<UnaryOperator<Serializable>> normalizerFor = createNormalizerFor(comparatorSource);
		final UnaryOperator<Serializable> normalizer = normalizerFor.orElseGet(UnaryOperator::identity);
		//noinspection rawtypes
		final Comparator valueComparator = createComparatorFor(locale, comparatorSource);
		final String referenceName = referenceSchema.getName();
		final Function<ReferenceContract, Comparable<?>> attributeExtractor = locale == null ?
			referenceContract -> referenceContract.getAttribute(attributeName) :
			referenceContract -> referenceContract.getAttribute(attributeName, locale);

		this.attributeValueFetcher = entityContract -> {
			final ReferenceAttributeValue cachedValue = this.cache.get(entityContract.getPrimaryKeyOrThrowException());
			if (cachedValue == null) {
				// initialize the reference position map if it hasn't been initialized yet
				if (this.referencePositionMap == null) {
					this.referencePositionMap = this.referencePositionMapSupplier.get();
				}
				// find the reference contract that has the attribute we are looking for
				final ReferenceAttributeValue calculatedValue = entityContract.getReferences(referenceName)
					.stream()
					.filter(it -> attributeExtractor.apply(it) != null)
					.min(Comparator.comparingInt(it -> this.referencePositionMap.get(it.getReferencedPrimaryKey())))
					.map(
						it -> new ReferenceAttributeValue(
							it.getReferencedPrimaryKey(),
							(Comparable<?>) normalizer.apply(it.getAttribute(attributeName)),
							valueComparator
						)
					)
					.orElse(ReferenceAttributeValue.MISSING_VALUE);

				this.cache.put(entityContract.getPrimaryKeyOrThrowException(), calculatedValue);
				if (calculatedValue == ReferenceAttributeValue.MISSING_VALUE) {
					this.nonSortedEntities = ofNullable(this.nonSortedEntities)
						.orElseGet(() -> new CompositeObjectArray<>(EntityContract.class));
					this.nonSortedEntities.add(entityContract);
					return null;
				} else {
					return calculatedValue;
				}
			} else {
				return cachedValue == ReferenceAttributeValue.MISSING_VALUE ? null : cachedValue;
			}
		};
	}

	@Override
	public void prepareFor(int entityCount) {
		this.cache = new IntObjectHashMap<>(Math.min(entityCount, 1024));
	}

	@Nonnull
	@Override
	public Iterable<EntityContract> getNonSortedEntities() {
		return ofNullable((Iterable<EntityContract>) this.nonSortedEntities)
			.orElse(Collections.emptyList());
	}

	@Override
	public abstract int compare(EntityContract o1, EntityContract o2);

	/**
	 * Represents a data structure that encapsulates a reference key and its associated attribute value.
	 * This record is primarily intended for use in sorting operations, where entities are sorted based
	 * on associated reference attribute values.
	 *
	 * The {@code referenceKey} uniquely identifies a reference schema and optionally its referenced entity.
	 * The {@code attributeValue} represents a comparable value associated with the reference key for the purposes
	 * of comparison and sorting.
	 */
	@SuppressWarnings("rawtypes")
	protected record ReferenceAttributeValue(
		int referencedEntityPrimaryKey,
		@Nonnull Comparable<?> attributeValue,
		@Nonnull Comparator comparator
	) implements Comparable<ReferenceAttributeValue> {
		public static final ReferenceAttributeValue MISSING_VALUE = new ReferenceAttributeValue(-1, ClassifierType.ENTITY, Comparator.naturalOrder());

		@Override
		public int compareTo(@Nonnull ReferenceAttributeValue o) {
			//noinspection unchecked
			final int result = this.comparator.compare(this.attributeValue, o.attributeValue);
			if (result == 0) {
				return Integer.compare(this.referencedEntityPrimaryKey, o.referencedEntityPrimaryKey);
			} else {
				return result;
			}
		}

	}
}
