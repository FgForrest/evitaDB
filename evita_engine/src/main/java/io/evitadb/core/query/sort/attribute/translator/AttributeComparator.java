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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.sort.attribute.translator;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.dataType.array.CompositeObjectArray;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;

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
	@Nonnull private final Comparator<Comparable<?>> comparator;
	private CompositeObjectArray<EntityContract> nonSortedEntities;

	public AttributeComparator(
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull AttributeExtractor attributeExtractor,
		@Nonnull Comparator<Comparable<?>> comparator
	) {
		this.comparator = comparator;
		this.attributeValueFetcher = locale == null ?
			entityContract -> attributeExtractor.extract(entityContract, attributeName) :
			entityContract -> attributeExtractor.extract(entityContract, attributeName, locale);
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
			return comparator.compare(attribute1, attribute2);
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
