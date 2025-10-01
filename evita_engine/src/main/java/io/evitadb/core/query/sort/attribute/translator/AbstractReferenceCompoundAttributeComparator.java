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


import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.evitadb.index.attribute.SortIndex.createCombinedComparatorFor;
import static io.evitadb.index.attribute.SortIndex.createNormalizerFor;

/**
 * Comparator for sorting entities according to a sortable compound attribute value. It combines multiple attribute
 * comparators into one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public abstract class AbstractReferenceCompoundAttributeComparator implements EntityComparator, Serializable {
	@Serial private static final long serialVersionUID = -5754681102504086372L;
	/**
	 * Comparator sources for each attribute element.
	 */
	@Nonnull private final ComparatorSource[] comparatorSource;
	/**
	 * Array of attribute elements that are to be compared.
	 */
	@Nonnull protected final AttributeElement[] attributeElements;
	/**
	 * Function for fetching the attribute value from the entity.
	 */
	@Nonnull protected final BiFunction<ReferenceContract, String, Serializable> attributeValueFetcher;
	/**
	 * Comparator for comparing the normalized attribute values.
	 */
	@Nonnull protected final Comparator<ComparableArray> comparator;
	/**
	 * Function for normalizing the attribute values (such as string values or BigDecimals).
	 */
	@Nonnull protected final UnaryOperator<Serializable> normalizer;
	/**
	 * Mandatory reference to reference schema, if the attribute is a reference attribute.
	 */
	@Nonnull protected final ReferenceSchema referenceSchema;
	/**
	 * Memoized normalized value arrays for the entities. Memoization is used because the very same entity may occur
	 * in compare method multiple times.
	 */
	@Nonnull private final IntObjectMap<ReferenceAttributeValue> memoizedValues = new IntObjectHashMap<>(128);
	/**
	 * Container for entities that cannot be sorted using the resolved {@link SortedRecordsProvider}.
	 */
	private CompositeObjectArray<EntityContract> nonSortedEntities;

	public AbstractReferenceCompoundAttributeComparator(
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nonnull ReferenceSchema referenceSchema,
		@Nullable Locale locale,
		@Nonnull Function<String, AttributeSchemaContract> attributeSchemaExtractor,
		@Nonnull OrderDirection orderDirection
	) {
		this.referenceSchema = referenceSchema;
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
		final Comparator<ComparableArray> baseComparator = createCombinedComparatorFor(locale, this.comparatorSource);
		this.comparator = orderDirection == OrderDirection.ASC ? baseComparator : baseComparator.reversed();
		this.attributeValueFetcher = locale == null ?
			AttributesContract::getAttribute :
			(referenceContract, attributeName) -> referenceContract.getAttribute(attributeName, locale);
		this.attributeElements = attributeElements.toArray(new AttributeElement[0]);
	}

	@Nonnull
	@Override
	public Iterable<EntityContract> getNonSortedEntities() {
		return this.nonSortedEntities == null ? Collections.emptyList() : this.nonSortedEntities;
	}

	@Override
	public abstract int compare(EntityContract o1, EntityContract o2);

	/**
	 * Retrieves and memoizes the comparable value array for the given entity.
	 * If the value is already memoized, it returns the memoized value.
	 * Otherwise, computes the value, memoizes it, and then returns it.
	 *
	 * @param entity the entity for which the value is to be memoized, must not be null.
	 * @return the memoized comparable array value for the entity, never null.
	 */
	@Nonnull
	protected ReferenceAttributeValue getAndMemoizeValue(@Nonnull EntityContract entity) {
		final ReferenceAttributeValue memoizedValue = this.memoizedValues.get(entity.getPrimaryKeyOrThrowException());
		if (memoizedValue == null) {
			final Optional<ReferenceContract> referenceContract = pickReference(entity);
			final ReferenceAttributeValue calculatedValue = referenceContract
				.map(reference -> {
					final Serializable[] valueArray = new Serializable[this.attributeElements.length];
					for (int i = 0; i < this.attributeElements.length; i++) {
						final AttributeElement attributeElement = this.attributeElements[i];
						valueArray[i] = this.attributeValueFetcher.apply(reference, attributeElement.attributeName());
					}
					if (ArrayUtils.isEmptyOrItsValuesNull(valueArray)) {
						return ReferenceAttributeValue.MISSING;
					} else {
						return new ReferenceAttributeValue(
							reference.getReferencedPrimaryKey(),
							new ComparableArray(
								this.comparatorSource,
								(Serializable[]) this.normalizer.apply(valueArray)
							),
							this.comparator
						);
					}
				})
				.orElse(ReferenceAttributeValue.MISSING);
			this.memoizedValues.put(entity.getPrimaryKeyOrThrowException(), calculatedValue);
			// if the value is missing, we need to add the entity to the non-sorted entities
			if (calculatedValue == ReferenceAttributeValue.MISSING) {
				if (this.nonSortedEntities == null) {
					this.nonSortedEntities = new CompositeObjectArray<>(EntityContract.class);
				}
				this.nonSortedEntities.add(entity);
			}
			return calculatedValue;
		} else {
			return memoizedValue;
		}
	}

	/**
	 * Abstract method for selecting a {@link ReferenceContract} instance based on the given {@link EntityContract}.
	 * This method provides a way to retrieve a specific reference from the entity, if applicable.
	 *
	 * @param entity the entity from which the reference is to be selected, must not be null
	 * @return the selected {@link ReferenceContract}, or null if no appropriate reference exists
	 */
	@Nonnull
	protected abstract Optional<ReferenceContract> pickReference(@Nonnull EntityContract entity);

	/**
	 * Represents a data structure that encapsulates a reference key and its associated attribute value.
	 * This record is primarily intended for use in sorting operations, where entities are sorted based
	 * on associated reference attribute values.
	 *
	 * The {@code referenceKey} uniquely identifies a reference schema and optionally its referenced entity.
	 * The {@code attributeValue} represents a comparable value associated with the reference key for the purposes
	 * of comparison and sorting.
	 */
	protected record ReferenceAttributeValue(
		int referencedEntityPrimaryKey,
		@Nonnull ComparableArray attributeValues,
		@Nonnull Comparator<ComparableArray> comparator
	) implements Comparable<ReferenceAttributeValue> {
		public static final ReferenceAttributeValue MISSING = new ReferenceAttributeValue(
			-1,
			new ComparableArray(new ComparatorSource[0], new Serializable[0]),
			(o1, o2) -> {
				throw new UnsupportedOperationException("Cannot compare missing values");
			}
		);

		@Override
		public int compareTo(@Nonnull ReferenceAttributeValue o) {
			final int result = this.comparator.compare(this.attributeValues, o.attributeValues);
			if (result == 0) {
				return Integer.compare(this.referencedEntityPrimaryKey, o.referencedEntityPrimaryKey);
			} else {
				return result;
			}
		}

	}
}
