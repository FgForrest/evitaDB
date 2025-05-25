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


import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.evitadb.index.attribute.SortIndex.createCombinedComparatorFor;
import static io.evitadb.index.attribute.SortIndex.createNormalizerFor;

/**
 * Comparator for sorting entities according to a sortable compound attribute value. It combines multiple attribute
 * comparators into one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class CompoundAttributeComparator implements EntityComparator, Serializable {
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

	public CompoundAttributeComparator(
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nullable Locale locale,
		@Nonnull Function<String, AttributeSchemaContract> attributeSchemaExtractor,
		@Nonnull OrderDirection orderDirection
	) {
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
		this.normalizer = createNormalizerFor(this.comparatorSource);
		//noinspection rawtypes
		final Comparator baseComparator = createCombinedComparatorFor(locale, this.comparatorSource);
		//noinspection unchecked
		this.comparator = orderDirection == OrderDirection.ASC ?
			baseComparator : (o1, o2) -> baseComparator.compare(o2, o1);
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

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final ComparableArray valueToCompare1 = getAndMemoizeValue(o1);
		final ComparableArray valueToCompare2 = getAndMemoizeValue(o2);
		return this.comparator.compare(valueToCompare1, valueToCompare2);
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
			final Serializable[] valueArray = new Serializable[this.attributeElements.length];
			for (int i = 0; i < this.attributeElements.length; i++) {
				final AttributeElement attributeElement = this.attributeElements[i];
				valueArray[i] = this.attributeValueFetcher.apply(entity, attributeElement.attributeName());
			}
			value = new ComparableArray(
				this.comparatorSource,
				(Serializable[]) this.normalizer.apply(valueArray)
			);
			this.memoizedValues.put(entity.getPrimaryKeyOrThrowException(), value);
		}
		return value;
	}

}
