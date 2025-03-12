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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
public class ReferenceAttributeComparator implements EntityComparator {
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
	 * Function to fetch the value of the attribute being sorted for a given entity.
	 */
	@Nonnull private final Function<EntityContract, ReferenceAttributeValue> attributeValueFetcher;
	/**
	 * Comparator for comparing entities by their primary key as a fallback.
	 */
	@Nonnull private final Comparator<EntityContract> pkComparator;
	/**
	 * Internal storage for entities that could not be fully sorted due to missing attributes.
	 */
	private CompositeObjectArray<EntityContract> nonSortedEntities;

	public ReferenceAttributeComparator(
		@Nonnull String attributeName,
		@Nonnull Class<?> type,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable EntitySchemaContract referencedEntitySchema,
		@Nullable Locale locale,
		@Nonnull OrderDirection orderDirection
	) {
		this.referenceSchema = referenceSchema;
		this.referencedEntitySchema = referencedEntitySchema;
		final ComparatorSource comparatorSource = new ComparatorSource(
			type, orderDirection, OrderBehaviour.NULLS_LAST
		);
		final Optional<UnaryOperator<Object>> normalizerFor = createNormalizerFor(comparatorSource);
		final UnaryOperator<Object> normalizer = normalizerFor.orElseGet(UnaryOperator::identity);
		this.pkComparator = orderDirection == OrderDirection.ASC ?
			Comparator.comparingInt(EntityContract::getPrimaryKeyOrThrowException) :
			Comparator.comparingInt(EntityContract::getPrimaryKeyOrThrowException).reversed();
		//noinspection rawtypes
		final Comparator valueComparator = createComparatorFor(locale, comparatorSource);
		final String referenceName = this.referenceSchema.getName();
		final Function<ReferenceContract, Comparable<?>> attributeExtractor = locale == null ?
			referenceContract -> referenceContract.getAttribute(attributeName) :
			referenceContract -> referenceContract.getAttribute(attributeName, locale);
		//noinspection unchecked
		this.attributeValueFetcher = entityContract -> entityContract.getReferences(referenceName)
				.stream()
				.filter(it -> attributeExtractor.apply(it) != null)
				.map(
					it -> new ReferenceAttributeValue(
						it.getReferencedPrimaryKey(),
						(Comparable<?>) normalizer.apply(it.getAttribute(attributeName)),
						valueComparator
					)
				)
				.findFirst()
				.orElse(null);
	}

	@Nonnull
	@Override
	public Iterable<EntityContract> getNonSortedEntities() {
		return ofNullable((Iterable<EntityContract>) this.nonSortedEntities)
			.orElse(Collections.emptyList());
	}

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final ReferenceAttributeValue attribute1 = this.attributeValueFetcher.apply(o1);
		final ReferenceAttributeValue attribute2 = this.attributeValueFetcher.apply(o2);
		if (attribute1 != null && attribute2 != null) {
			final int result = attribute1.compareTo(attribute2);
			if (result == 0) {
				return this.pkComparator.compare(o1, o2);
			} else {
				return result;
			}
		} else if (attribute1 == null && attribute2 != null) {
			//noinspection ObjectInstantiationInEqualsHashCode
			this.nonSortedEntities = ofNullable(this.nonSortedEntities)
				.orElseGet(() -> new CompositeObjectArray<>(EntityContract.class));
			this.nonSortedEntities.add(o1);
			return 1;
		} else if (attribute1 != null) {
			//noinspection ObjectInstantiationInEqualsHashCode
			this.nonSortedEntities = ofNullable(this.nonSortedEntities)
				.orElseGet(() -> new CompositeObjectArray<>(EntityContract.class));
			this.nonSortedEntities.add(o2);
			return -1;
		} else {
			//noinspection ObjectInstantiationInEqualsHashCode
			this.nonSortedEntities = ofNullable(this.nonSortedEntities)
				.orElseGet(() -> new CompositeObjectArray<>(EntityContract.class));
			this.nonSortedEntities.add(o1);
			this.nonSortedEntities.add(o2);
			return 0;
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
		@Nonnull Comparable<?> attributeValue,
		@Nonnull Comparator<Comparable<?>> comparator
	) implements Comparable<ReferenceAttributeValue> {

		@Override
		public int compareTo(@Nonnull ReferenceAttributeValue o) {
			final int pkComparison = Integer.compare(this.referencedEntityPrimaryKey, o.referencedEntityPrimaryKey);
			if (pkComparison == 0) {
				return this.comparator.compare(this.attributeValue, o.attributeValue);
			} else {
				return pkComparison;
			}
		}

	}

}
