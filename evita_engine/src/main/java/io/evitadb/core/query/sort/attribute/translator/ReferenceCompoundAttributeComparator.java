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
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.evitadb.index.attribute.SortIndex.createCombinedComparatorFor;
import static io.evitadb.index.attribute.SortIndex.createNormalizerFor;

/**
 * Comparator for sorting entities according to a sortable compound attribute value. It combines multiple attribute
 * comparators into one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ReferenceCompoundAttributeComparator implements EntityComparator, Serializable {
	@Serial private static final long serialVersionUID = -5754681102504086372L;
	/**
	 * Optional reference to reference schema, if the attribute is a reference attribute.
	 */
	@Nonnull private final ReferenceSchemaContract referenceSchema;
	/**
	 * Optional reference to entity schema, the reference is targeting (null if the reference is null, or targets
	 * non-managed entity type).
	 */
	@Nullable private final EntitySchemaContract referencedEntitySchema;
	/**
	 * Function for fetching the attribute value from the entity.
	 */
	@Nonnull private final BiFunction<ReferenceContract, String, Object> attributeValueFetcher;
	/**
	 * Function for normalizing the attribute values (such as string values or BigDecimals).
	 */
	@Nonnull private final Function<Object, Object> normalizer;
	/**
	 * Comparator for comparing the normalized attribute values.
	 */
	@Nonnull private final Comparator<ComparableArray> comparator;
	/**
	 * Array of attribute elements that are to be compared.
	 */
	@Nonnull private final AttributeElement[] attributeElements;
	/**
	 * Memoized normalized value arrays for the entities. Memoization is used because the very same entity may occur
	 * in compare method multiple times.
	 */
	@Nonnull private final IntObjectMap<ReferenceAttributeValue> memoizedValues = new IntObjectHashMap<>(128);
	/**
	 * Container for entities that cannot be sorted using the resolved {@link SortedRecordsProvider}.
	 */
	private CompositeObjectArray<EntityContract> nonSortedEntities;

	public ReferenceCompoundAttributeComparator(
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable EntitySchemaContract referencedEntitySchema,
		@Nullable Locale locale,
		@Nonnull Function<String, AttributeSchemaContract> attributeSchemaExtractor,
		@Nonnull OrderDirection orderDirection
	) {
		this.referenceSchema = referenceSchema;
		this.referencedEntitySchema = referencedEntitySchema;
		final List<AttributeElement> attributeElements = compoundSchemaContract
			.getAttributeElements();
		final ComparatorSource[] comparatorSource = attributeElements
			.stream()
			.map(attributeElement -> new ComparatorSource(
				attributeSchemaExtractor.apply(attributeElement.attributeName()).getPlainType(),
				attributeElement.direction(),
				attributeElement.behaviour()
			))
			.toArray(ComparatorSource[]::new);

		// initialize normalizers
		this.normalizer = createNormalizerFor(comparatorSource);
		final Comparator<ComparableArray> baseComparator = createCombinedComparatorFor(locale, comparatorSource);
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
	public int compare(EntityContract o1, EntityContract o2) {
		final ReferenceAttributeValue valueToCompare1 = getAndMemoizeValue(o1);
		final ReferenceAttributeValue valueToCompare2 = getAndMemoizeValue(o2);
		if (valueToCompare1 != ReferenceAttributeValue.MISSING && valueToCompare2 != ReferenceAttributeValue.MISSING) {
			return valueToCompare1.compareTo(valueToCompare2);
		} else {
			// if any of the entities is not found, and we don't have the container to store them, create it
			//noinspection ObjectInstantiationInEqualsHashCode
			this.nonSortedEntities = new CompositeObjectArray<>(EntityContract.class);
			// if any of the entities is not found, store it in the container
			if (valueToCompare1 == ReferenceAttributeValue.MISSING) {
				this.nonSortedEntities.add(o1);
			}
			if (valueToCompare2 == ReferenceAttributeValue.MISSING) {
				this.nonSortedEntities.add(o2);
			}

			if (valueToCompare1 != ReferenceAttributeValue.MISSING) {
				return 1;
			} else if (valueToCompare2 != ReferenceAttributeValue.MISSING) {
				return -1;
			} else {
				return 0;
			}
		}
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
	private ReferenceAttributeValue getAndMemoizeValue(@Nonnull EntityContract entity) {
		final ReferenceAttributeValue memoizedValue = this.memoizedValues.get(entity.getPrimaryKeyOrThrowException());
		if (memoizedValue == null) {
			final ReferenceContract referenceContract = entity.getReferences(this.referenceSchema.getName())
				.stream()
				.filter(it -> Arrays.stream(this.attributeElements).anyMatch(ae -> it.getAttribute(ae.attributeName()) != null))
				.findFirst()
				.orElse(null);

			final ReferenceAttributeValue calculatedValue;
			if (referenceContract == null) {
				calculatedValue = ReferenceAttributeValue.MISSING;
			} else {
				final Object[] valueArray = new Comparable<?>[this.attributeElements.length];
				for (int i = 0; i < this.attributeElements.length; i++) {
					final AttributeElement attributeElement = this.attributeElements[i];
					valueArray[i] = this.attributeValueFetcher.apply(referenceContract, attributeElement.attributeName());
				}
				calculatedValue = new ReferenceAttributeValue(
					referenceContract.getReferencedPrimaryKey(),
					new ComparableArray(
						(Comparable<?>[]) this.normalizer.apply(valueArray)
					),
					this.comparator
				);
			}
			this.memoizedValues.put(entity.getPrimaryKeyOrThrowException(), calculatedValue);
			return calculatedValue;
		} else {
			return memoizedValue;
		}
	}

	/**
	 * Represents a data structure that encapsulates a reference key and its associated attribute value.
	 * This record is primarily intended for use in sorting operations, where entities are sorted based
	 * on associated reference attribute values.
	 *
	 * The {@code referenceKey} uniquely identifies a reference schema and optionally its referenced entity.
	 * The {@code attributeValue} represents a comparable value associated with the reference key for the purposes
	 * of comparison and sorting.
	 */
	private record ReferenceAttributeValue(
		int referencedEntityPrimaryKey,
		@Nonnull ComparableArray attributeValues,
		@Nonnull Comparator<ComparableArray> comparator
	) implements Comparable<ReferenceAttributeValue> {
		public static final ReferenceAttributeValue MISSING = new ReferenceAttributeValue(-1, new ComparableArray(new Comparable[0]), Comparator.naturalOrder());

		@Override
		public int compareTo(@Nonnull ReferenceAttributeValue o) {
			final int pkComparison = Integer.compare(this.referencedEntityPrimaryKey, o.referencedEntityPrimaryKey);
			if (pkComparison == 0) {
				return this.comparator.compare(this.attributeValues, o.attributeValues);
			} else {
				return pkComparison;
			}
		}

	}

}
