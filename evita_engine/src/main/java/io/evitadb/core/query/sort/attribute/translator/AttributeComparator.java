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

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.evitadb.index.attribute.SortIndex.createComparatorFor;
import static io.evitadb.index.attribute.SortIndex.createNormalizerFor;
import static java.util.Optional.ofNullable;

/**
 * Attribute comparator sorts entities according to a specified attribute value. It needs to provide a function for
 * accessing the entity attribute value and the simple {@link Comparable} comparator implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@SuppressWarnings("ComparatorNotSerializable")
public class AttributeComparator implements EntityComparator {
	/**
	 * Function to fetch the value of the attribute being sorted for a given entity.
	 */
	@Nonnull private final Function<EntityContract, Comparable<?>> attributeValueFetcher;
	/**
	 * Comparator for comparing entities by their primary key as a fallback.
	 */
	@Nonnull private final Comparator<EntityContract> pkComparator;
	/**
	 * Comparator for comparing the values of the specified attribute.
	 */
	@Nonnull private final Comparator<Comparable<?>> comparator;
	/**
	 * Internal storage for entities that could not be fully sorted due to missing attributes.
	 */
	private CompositeObjectArray<EntityContract> nonSortedEntities;
	/**
	 * Cache for storing attribute values of entities to avoid redundant calculations.
	 */
	private IntObjectMap<Comparable<?>> cache;

	public AttributeComparator(
		@Nonnull String attributeName,
		@Nonnull Class<?> type,
		@Nullable Locale locale,
		@Nonnull OrderDirection orderDirection
	) {
		final ComparatorSource comparatorSource = new ComparatorSource(
			type, orderDirection, OrderBehaviour.NULLS_LAST
		);
		final Optional<UnaryOperator<Serializable>> normalizerFor = createNormalizerFor(comparatorSource);
		final UnaryOperator<Serializable> normalizer = normalizerFor.orElseGet(UnaryOperator::identity);
		this.pkComparator = orderDirection == OrderDirection.ASC ?
			Comparator.comparingInt(EntityContract::getPrimaryKeyOrThrowException) :
			Comparator.comparingInt(EntityContract::getPrimaryKeyOrThrowException).reversed();
		//noinspection unchecked
		this.comparator = createComparatorFor(locale, comparatorSource);
		final Function<EntityContract, Comparable<?>> attributeFetcher = locale == null ?
			entityContract -> (Comparable<?>) normalizer.apply(entityContract.getAttribute(attributeName)) :
			entityContract -> (Comparable<?>) normalizer.apply(entityContract.getAttribute(attributeName, locale));
		this.attributeValueFetcher = entityContract -> {
			//noinspection rawtypes
			final Comparable cachedValue = this.cache.get(entityContract.getPrimaryKeyOrThrowException());
			if (cachedValue == null) {
				//noinspection unchecked,rawtypes
				final Comparable calculatedValue = ofNullable(attributeFetcher.apply(entityContract))
					.orElseGet(() -> (Comparable)MissingComparableValue.INSTANCE);
				this.cache.put(entityContract.getPrimaryKeyOrThrowException(), calculatedValue);
				if (calculatedValue == MissingComparableValue.INSTANCE) {
					this.nonSortedEntities = ofNullable(this.nonSortedEntities)
						.orElseGet(() -> new CompositeObjectArray<>(EntityContract.class));
					this.nonSortedEntities.add(entityContract);
					return null;
				} else {
					return calculatedValue;
				}
			} else {
				return cachedValue == MissingComparableValue.INSTANCE ? null : cachedValue;
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
	public int compare(EntityContract o1, EntityContract o2) {
		final Comparable<?> attribute1 = this.attributeValueFetcher.apply(o1);
		final Comparable<?> attribute2 = this.attributeValueFetcher.apply(o2);
		if (attribute1 != null && attribute2 != null) {
			final int result = this.comparator.compare(attribute1, attribute2);
			if (result == 0) {
				return this.pkComparator.compare(o1, o2);
			} else {
				return result;
			}
		} else if (attribute1 == null && attribute2 != null) {
			return 1;
		} else if (attribute1 != null) {
			return -1;
		} else {
			return 0;
		}
	}

	/**
	 * Stub for non-existent value.
	 */
	@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
	@EqualsAndHashCode
	private static class MissingComparableValue implements Comparable<MissingComparableValue> {
		public static final MissingComparableValue INSTANCE = new MissingComparableValue();

		@Override
		public int compareTo(@Nonnull MissingComparableValue o) {
			return 0;
		}

	}

}
