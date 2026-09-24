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

package io.evitadb.core.query.sort.attribute.comparator;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.PickFirstByEntityProperty;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.core.query.sort.reference.sorter.PickFirstReducedIndexResolver;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;
import java.util.Optional;

/**
 * Attribute comparator sorts entities according to a specified attribute value. It needs to provide a function for
 * accessing the entity attribute value and the simple {@link Comparable} comparator implementation. This implementation
 * adheres to {@link MergeMode#APPEND_FIRST} which relates to {@link PickFirstByEntityProperty} ordering.
 *
 * It is the prefetch-route twin of the index route built by {@link PickFirstReducedIndexResolver}, and must pick the
 * very row that route picks: an entity sorts on its first reference, in target order, that carries the attribute -
 * references sharing one target in the order of their representative attribute values - and entities with equal
 * values follow in the order of their primary keys in the direction of the ordering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class PickFirstReferenceAttributeComparator extends AbstractReferenceAttributeComparator {
	@Serial private static final long serialVersionUID = 2969632214608241409L;
	/**
	 * Resolver and derived reference order providing the target order the index route walks.
	 */
	@Nonnull private final transient PickFirstReferenceTargetRanking targetRanking;

	/**
	 * Creates the comparator for the given attribute and wires it to the resolver that provides the target order
	 * its index-route twin ({@link PickFirstReducedIndexResolver}) walks.
	 *
	 * @param attributeName   the name of the sortable attribute
	 * @param type            the type of the attribute value
	 * @param referenceSchema the schema of the reference the attribute belongs to
	 * @param locale          the locale to use when reading localized attribute values, or `null` when the attribute
	 *                        is not localized
	 * @param orderDirection  the direction to sort in
	 * @param indexResolver   resolver providing the target order the index route walks
	 */
	public PickFirstReferenceAttributeComparator(
		@Nonnull String attributeName,
		@Nonnull Class<?> type,
		@Nonnull ReferenceSchema referenceSchema,
		@Nullable Locale locale,
		@Nonnull OrderDirection orderDirection,
		@Nonnull PickFirstReducedIndexResolver indexResolver
	) {
		super(
			attributeName,
			type,
			referenceSchema,
			locale,
			orderDirection
		);
		this.targetRanking = new PickFirstReferenceTargetRanking(referenceSchema, indexResolver);
	}

	@Override
	public void prepareForSelection(@Nonnull Bitmap entityPrimaryKeys) {
		this.targetRanking.prepareForSelection(entityPrimaryKeys);
	}

	@Nonnull
	@Override
	protected Optional<ReferenceContract> pickReference(@Nonnull EntityContract entity) {
		// find the first reference in target order that has the attribute we are looking for
		return entity.getReferences(this.referenceName)
			.stream()
			.filter(it -> this.attributeExtractor.apply(it) != null)
			.min(this.targetRanking.referenceOrder());
	}

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final ReferenceAttributeValue attribute1 = this.attributeValueFetcher.apply(o1);
		final ReferenceAttributeValue attribute2 = this.attributeValueFetcher.apply(o2);
		if (attribute1 != null && attribute2 != null) {
			// the picked references are compared on their values only - which target they belong to decided which
			// reference was picked and must not decide the order of the entities as well
			//noinspection unchecked
			final int result = attribute1.comparator().compare(attribute1.attributeValue(), attribute2.attributeValue());
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

}
