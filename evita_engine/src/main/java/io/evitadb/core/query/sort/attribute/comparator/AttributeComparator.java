/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.dataType.Scope;
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
import java.util.Set;
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
	 * Function to fetch the normalized value of the attribute being sorted for a given entity, `null` when the entity
	 * lacks it. It neither memoizes nor reports anything.
	 */
	@Nonnull private final Function<EntityContract, Comparable<?>> normalizedValueFetcher;
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
	 * Scopes whose entities the comparator may sort - the scopes in which the attribute is sortable, so that the
	 * comparator places exactly the entities the sort indexes of the index route hold. `null` means every scope.
	 */
	@Nullable private final Set<Scope> sortableScopes;
	/**
	 * Internal storage for entities that could not be fully sorted due to missing attributes.
	 */
	@Nullable private CompositeObjectArray<EntityContract> nonSortedEntities;
	/**
	 * Cache for storing attribute values of entities to avoid redundant calculations.
	 */
	private IntObjectMap<Comparable<?>> cache;

	/**
	 * Creates a comparator sorting the entities of every scope.
	 *
	 * @param attributeName  name of the sorted attribute
	 * @param type           type of the sorted attribute
	 * @param locale         locale of a localized attribute, `null` otherwise
	 * @param orderDirection direction of the ordering
	 */
	public AttributeComparator(
		@Nonnull String attributeName,
		@Nonnull Class<?> type,
		@Nullable Locale locale,
		@Nonnull OrderDirection orderDirection
	) {
		this(attributeName, type, locale, orderDirection, null);
	}

	/**
	 * Creates a comparator sorting only the entities of the passed scopes.
	 *
	 * @param attributeName  name of the sorted attribute
	 * @param type           type of the sorted attribute
	 * @param locale         locale of a localized attribute, `null` otherwise
	 * @param orderDirection direction of the ordering
	 * @param sortableScopes scopes whose entities may be sorted (the attribute is sortable there), `null` for every
	 *                       scope
	 */
	public AttributeComparator(
		@Nonnull String attributeName,
		@Nonnull Class<?> type,
		@Nullable Locale locale,
		@Nonnull OrderDirection orderDirection,
		@Nullable Set<Scope> sortableScopes
	) {
		this.sortableScopes = sortableScopes;
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
		this.normalizedValueFetcher = locale == null ?
			entityContract -> (Comparable<?>) normalizer.apply(entityContract.getAttribute(attributeName)) :
			entityContract -> (Comparable<?>) normalizer.apply(entityContract.getAttribute(attributeName, locale));
		this.attributeValueFetcher = entityContract -> {
			//noinspection rawtypes
			final Comparable cachedValue = this.cache.get(entityContract.getPrimaryKeyOrThrowException());
			if (cachedValue == null) {
				//noinspection unchecked,rawtypes
				final Comparable calculatedValue = ofNullable(this.normalizedValueFetcher.apply(entityContract))
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
		this.nonSortedEntities = null;
	}

	/**
	 * Classifies the entity as sortable when it belongs to one of the {@link #sortableScopes} and carries the sorted
	 * attribute. The value is memoized for the comparisons that follow; an entity without it is memoized as missing
	 * but not reported through {@link #getNonSortedEntities()}, because it is never compared - the caller passes it
	 * on by itself.
	 *
	 * @param entity the entity to classify
	 * @return `true` when the entity is to be sorted by this comparator
	 */
	@Override
	public boolean canSort(@Nonnull EntityContract entity) {
		if (this.sortableScopes != null && !this.sortableScopes.contains(entity.getScope())) {
			return false;
		}
		final int primaryKey = entity.getPrimaryKeyOrThrowException();
		final Comparable<?> cachedValue = this.cache.get(primaryKey);
		if (cachedValue == null) {
			final Comparable<?> calculatedValue = this.normalizedValueFetcher.apply(entity);
			this.cache.put(primaryKey, calculatedValue == null ? MissingComparableValue.INSTANCE : calculatedValue);
			return calculatedValue != null;
		} else {
			return cachedValue != MissingComparableValue.INSTANCE;
		}
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
