/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
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
public class AttributeComparator implements EntityComparator {
	@Nonnull private final Function<EntityContract, Comparable<?>> attributeValueFetcher;
	@Nonnull private final Comparator<EntityContract> pkComparator;
	@Nonnull private final Comparator<Comparable<?>> comparator;
	private CompositeObjectArray<EntityContract> nonSortedEntities;

	public AttributeComparator(
		@Nonnull String attributeName,
		@Nonnull Class<?> type,
		@Nullable Locale locale,
		@Nonnull AttributeExtractor attributeExtractor,
		@Nonnull OrderDirection orderDirection
		) {
		final ComparatorSource comparatorSource = new ComparatorSource(
			type, orderDirection, OrderBehaviour.NULLS_LAST
		);
		final Optional<UnaryOperator<Object>> normalizerFor = createNormalizerFor(comparatorSource);
		final UnaryOperator<Object> normalizer = normalizerFor.orElseGet(UnaryOperator::identity);
		this.pkComparator = orderDirection == OrderDirection.ASC ?
			Comparator.comparingInt(EntityContract::getPrimaryKey) :
			Comparator.comparingInt(EntityContract::getPrimaryKey).reversed();
		//noinspection unchecked
		this.comparator = createComparatorFor(locale, comparatorSource);
		this.attributeValueFetcher = locale == null ?
			entityContract -> (Comparable<?>) normalizer.apply(attributeExtractor.extract(entityContract, attributeName)) :
			entityContract -> (Comparable<?>) normalizer.apply(attributeExtractor.extract(entityContract, attributeName, locale));
	}

	@Nonnull
	@Override
	public Iterable<EntityContract> getNonSortedEntities() {
		return ofNullable((Iterable<EntityContract>) nonSortedEntities)
			.orElse(Collections.emptyList());
	}

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final Comparable<?> attribute1 = attributeValueFetcher.apply(o1);
		final Comparable<?> attribute2 = attributeValueFetcher.apply(o2);
		if (attribute1 != null && attribute2 != null) {
			final int result = comparator.compare(attribute1, attribute2);
			if (result == 0) {
				return pkComparator.compare(o1, o2);
			} else {
				return result;
			}
		} else if (attribute1 == null && attribute2 != null) {
			this.nonSortedEntities = ofNullable(this.nonSortedEntities)
				.orElseGet(() -> new CompositeObjectArray<>(EntityContract.class));
			this.nonSortedEntities.add(o1);
			return 1;
		} else if (attribute1 != null) {
			this.nonSortedEntities = ofNullable(this.nonSortedEntities)
				.orElseGet(() -> new CompositeObjectArray<>(EntityContract.class));
			this.nonSortedEntities.add(o2);
			return -1;
		} else {
			this.nonSortedEntities = ofNullable(this.nonSortedEntities)
				.orElseGet(() -> new CompositeObjectArray<>(EntityContract.class));
			this.nonSortedEntities.add(o1);
			this.nonSortedEntities.add(o2);
			return 0;
		}
	}
}
