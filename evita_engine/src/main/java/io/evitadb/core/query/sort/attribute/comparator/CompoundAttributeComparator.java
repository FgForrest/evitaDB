/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.evitadb.index.attribute.SortIndex.createCombinedComparatorFor;
import static io.evitadb.index.attribute.SortIndex.createComparatorNormalizerFor;

/**
 * Comparator for sorting entities according to a sortable compound attribute value. It combines multiple attribute
 * comparators into one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class CompoundAttributeComparator implements EntityComparator, Serializable {
	@Serial private static final long serialVersionUID = 3531363761422177481L;
	/**
	 * Function for fetching the attribute value from the entity.
	 */
	@Nonnull private final BiFunction<EntityContract, String, Serializable> attributeValueFetcher;
	/**
	 * Function for normalizing the attribute values (such as string values or BigDecimals).
	 */
	@Nonnull private final UnaryOperator<Serializable> normalizer;
	/**
	 * Comparator for comparing the normalized attribute values.
	 */
	@Nonnull private final Comparator<Serializable> comparator;
	/**
	 * Comparator breaking the ties of equal compound values by primary key in the direction of the ordering - the
	 * order in which the compound sort index of the index route holds the entities sharing one value.
	 */
	@Nonnull private final Comparator<EntityContract> pkComparator;
	/**
	 * Comparator sources for each attribute element.
	 */
	@Nonnull private final ComparatorSource[] comparatorSource;
	/**
	 * Array of attribute elements that are to be compared.
	 */
	@Nonnull private final AttributeElement[] attributeElements;
	/**
	 * Memoized normalized value arrays for the entities. Memoization is used because the very same entity may occur
	 * in compare method multiple times.
	 */
	@Nonnull private final IntObjectMap<ComparableArray> memoizedValues = new IntObjectHashMap<>(128);
	/**
	 * Scopes whose entities the comparator may sort - the ordered scopes in which the compound is indexed, so that the
	 * comparator places exactly the entities the compound sort indexes of the index route hold. `null` means every
	 * scope.
	 */
	@Nullable private final Set<Scope> sortableScopes;

	/**
	 * Creates a comparator sorting the entities of every scope.
	 *
	 * @param compoundSchemaContract   the sorted compound
	 * @param locale                   locale of the localized elements, `null` otherwise
	 * @param attributeSchemaExtractor resolves the schemas of the compound elements
	 * @param orderDirection           direction of the ordering
	 */
	public CompoundAttributeComparator(
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nullable Locale locale,
		@Nonnull Function<String, AttributeSchemaContract> attributeSchemaExtractor,
		@Nonnull OrderDirection orderDirection
	) {
		this(compoundSchemaContract, locale, attributeSchemaExtractor, orderDirection, null);
	}

	/**
	 * Creates a comparator sorting only the entities of the passed scopes.
	 *
	 * @param compoundSchemaContract   the sorted compound
	 * @param locale                   locale of the localized elements, `null` otherwise
	 * @param attributeSchemaExtractor resolves the schemas of the compound elements
	 * @param orderDirection           direction of the ordering
	 * @param sortableScopes           scopes whose entities may be sorted (the compound is indexed there), `null` for
	 *                                 every scope
	 */
	public CompoundAttributeComparator(
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nullable Locale locale,
		@Nonnull Function<String, AttributeSchemaContract> attributeSchemaExtractor,
		@Nonnull OrderDirection orderDirection,
		@Nullable Set<Scope> sortableScopes
	) {
		this.sortableScopes = sortableScopes;
		final List<AttributeElement> attributeElements = compoundSchemaContract
			.getAttributeElements();
		this.comparatorSource = attributeElements
			.stream()
			.map(attributeElement -> new ComparatorSource(
				attributeSchemaExtractor.apply(attributeElement.attributeName()).getPlainType(),
				attributeElement.direction(),
				attributeElement.behaviour()
			))
			.toArray(ComparatorSource[]::new);

		// initialize normalizers
		this.normalizer = createComparatorNormalizerFor(
			this.comparatorSource,
			attributeElements
				.stream()
				.mapToInt(
					attributeElement -> attributeSchemaExtractor.apply(attributeElement.attributeName())
						.getIndexedDecimalPlaces()
				)
				.toArray()
		);
		//noinspection rawtypes
		final Comparator baseComparator = createCombinedComparatorFor(locale, this.comparatorSource);
		//noinspection unchecked
		this.comparator = orderDirection == OrderDirection.ASC ?
			baseComparator : (o1, o2) -> baseComparator.compare(o2, o1);
		this.pkComparator = orderDirection == OrderDirection.ASC ?
			Comparator.comparingInt(EntityContract::getPrimaryKeyOrThrowException) :
			Comparator.comparingInt(EntityContract::getPrimaryKeyOrThrowException).reversed();
		this.attributeValueFetcher = locale == null ?
			AttributesContract::getAttribute :
			(entityContract, attributeName) -> entityContract.getAttribute(attributeName, locale);
		this.attributeElements = attributeElements.toArray(new AttributeElement[0]);
	}

	@Nonnull
	@Override
	public Iterable<EntityContract> getNonSortedEntities() {
		return List.of();
	}

	/**
	 * Classifies the entity as sortable when it belongs to one of the {@link #sortableScopes} and carries at least one
	 * element of the compound - the index route stores no compound entry for an entity none of whose elements is
	 * present. The value of a sortable entity is memoized for the comparisons that follow.
	 *
	 * @param entity the entity to classify
	 * @return `true` when the entity is to be sorted by this comparator
	 */
	@Override
	public boolean canSort(@Nonnull EntityContract entity) {
		if (this.sortableScopes != null && !this.sortableScopes.contains(entity.getScope())) {
			return false;
		}
		final Serializable[] valueArray = fetchValues(entity);
		if (ArrayUtils.isEmptyOrItsValuesNull(valueArray)) {
			return false;
		}
		final int primaryKey = entity.getPrimaryKeyOrThrowException();
		if (this.memoizedValues.get(primaryKey) == null) {
			this.memoizedValues.put(primaryKey, toComparableArray(valueArray));
		}
		return true;
	}

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final ComparableArray valueToCompare1 = getAndMemoizeValue(o1);
		final ComparableArray valueToCompare2 = getAndMemoizeValue(o2);
		final int result = this.comparator.compare(valueToCompare1, valueToCompare2);
		return result == 0 ? this.pkComparator.compare(o1, o2) : result;
	}

	/**
	 * Retrieves and memoizes the comparable value array for the given entity.
	 * If the value is already memoized, it returns the memoized value.
	 * Otherwise, computes the value, memoizes it, and then returns it.
	 *
	 * @param entity the entity for which the value is to be memoized, must not be null.
	 * @return the memoized comparable array value for the entity, never null.
	 */
	@Nonnull
	private ComparableArray getAndMemoizeValue(@Nonnull EntityContract entity) {
		ComparableArray value = this.memoizedValues.get(entity.getPrimaryKeyOrThrowException());
		if (value == null) {
			value = toComparableArray(fetchValues(entity));
			this.memoizedValues.put(entity.getPrimaryKeyOrThrowException(), value);
		}
		return value;
	}

	/**
	 * Fetches the raw values of the compound elements of the entity, in the order of the elements.
	 *
	 * @param entity the entity whose values are fetched
	 * @return the values, `null` for an element the entity lacks
	 */
	@Nonnull
	private Serializable[] fetchValues(@Nonnull EntityContract entity) {
		final Serializable[] valueArray = new Serializable[this.attributeElements.length];
		for (int i = 0; i < this.attributeElements.length; i++) {
			valueArray[i] = this.attributeValueFetcher.apply(entity, this.attributeElements[i].attributeName());
		}
		return valueArray;
	}

	/**
	 * Normalizes the raw values of the compound elements into the comparable form.
	 *
	 * @param valueArray the raw values of the compound elements
	 * @return the comparable array of the normalized values
	 */
	@Nonnull
	private ComparableArray toComparableArray(@Nonnull Serializable[] valueArray) {
		return new ComparableArray(this.comparatorSource, (Serializable[]) this.normalizer.apply(valueArray));
	}

}
