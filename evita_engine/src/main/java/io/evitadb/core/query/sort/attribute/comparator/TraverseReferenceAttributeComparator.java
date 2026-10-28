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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.TraverseByEntityProperty;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.sort.EntityReferenceSensitiveComparator;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

import static io.evitadb.core.query.sort.EntityReferenceSensitiveComparator.getFirstValuedReference;

/**
 * Attribute comparator sorts entities according to a specified attribute value. It needs to provide a function for
 * accessing the entity attribute value and the simple {@link Comparable} comparator implementation. This implementation
 * adheres to {@link MergeMode#APPEND_ALL} which relates to {@link TraverseByEntityProperty} ordering.
 *
 * The comparator sorts one traversal block at a time - the block key, naming the referenced entity of the block, is
 * bound by {@link #withReferencedEntityId(RepresentativeReferenceKey, Runnable)}. An entity is represented in the
 * block by its first row to that referenced entity, in the order of the representative attribute values, that
 * carries the attribute; an entity without such a row is classified as not sortable ({@link #canSort(EntityContract)}),
 * so it is passed on to the next block rather than claimed by this one. Entities of a block compare by the values of
 * their rows only - which row represents an entity never decides its position - and then by their primary keys in the
 * direction of the ordering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class TraverseReferenceAttributeComparator
	extends AbstractReferenceAttributeComparator
	implements EntityReferenceSensitiveComparator
{
	@Serial private static final long serialVersionUID = 2199278500724685085L;
	/**
	 * The id of the referenced entity that is being traversed.
	 */
	@Nullable private RepresentativeReferenceKey referenceKey;
	/**
	 * Tells whether a row carries the sorted attribute - created once, so picking a row allocates no lambda.
	 */
	@Nonnull private final Predicate<ReferenceContract> carriesValue =
		reference -> this.attributeExtractor.apply(reference) != null;

	/**
	 * Creates the comparator for the given reference attribute.
	 *
	 * @param attributeName        the name of the sortable attribute
	 * @param type                 the type of the attribute value
	 * @param indexedDecimalPlaces the `indexedDecimalPlaces` of the attribute schema - the scale a range value is
	 *                             compared at, so the comparator orders the entities as the sort index does
	 * @param referenceSchema      the schema of the reference the attribute belongs to
	 * @param locale               the locale to use when reading localized attribute values, or `null` when the
	 *                             attribute is not localized
	 * @param orderDirection       the direction to sort in
	 */
	public TraverseReferenceAttributeComparator(
		@Nonnull String attributeName,
		@Nonnull Class<?> type,
		int indexedDecimalPlaces,
		@Nonnull ReferenceSchema referenceSchema,
		@Nullable Locale locale,
		@Nonnull OrderDirection orderDirection
	) {
		super(
			attributeName,
			type,
			indexedDecimalPlaces,
			referenceSchema,
			locale,
			orderDirection
		);
	}

	/**
	 * Binds the comparator to the key of the sorted block for the duration of the lambda, which classifies and sorts
	 * the entities of the block; the key is released whatever the outcome.
	 *
	 * @param referenceKey the key of the sorted block
	 * @param lambda       the classification and sorting of the block
	 */
	@Override
	public void withReferencedEntityId(@Nonnull RepresentativeReferenceKey referenceKey, @Nonnull Runnable lambda) {
		try {
			Assert.isPremiseValid(this.referenceKey == null, "Cannot set referenced entity id twice!");
			Assert.isPremiseValid(this.referenceName.equals(referenceKey.referenceName()), "Referenced entity id must be for the same reference!");
			this.referenceKey = referenceKey;
			lambda.run();
		} finally {
			this.referenceKey = null;
		}
	}

	/**
	 * Picks the row of the entity that represents it in the bound block: its first row to the block's referenced
	 * entity, in the order of the representative attribute values, that carries the attribute.
	 *
	 * @param entity the entity whose reference is picked
	 * @return the representing row, empty when no row of the entity to the block's referenced entity carries the value
	 */
	@Nonnull
	@Override
	protected Optional<ReferenceContract> pickReference(@Nonnull EntityContract entity) {
		Assert.isPremiseValid(this.referenceKey != null, "Referenced entity id must be set!");
		return getFirstValuedReference(entity, this.referenceSchema, this.referenceKey.referenceKey(), this.carriesValue);
	}

	/**
	 * Compares two entities classified as sortable by {@link #canSort(EntityContract)} within the bound block by the
	 * values of their representing rows - equal values fall back to the primary key in the direction of the ordering.
	 * The representative attribute values of the rows take no part: they decided which row represents an entity, not
	 * where the entity goes, so an entity represented by a later row with a lower value still precedes one represented
	 * by an earlier row with a higher value. Entities without the value never get here, the sorter passes them on to
	 * the next block before any comparison.
	 *
	 * @param o1 the first entity
	 * @param o2 the second entity
	 * @return the comparison result
	 */
	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final ReferenceAttributeValue attribute1 = this.attributeValueFetcher.apply(o1);
		final ReferenceAttributeValue attribute2 = this.attributeValueFetcher.apply(o2);
		Assert.isPremiseValid(
			attribute1 != null && attribute2 != null,
			"Only entities classified as sortable within the bound block may be compared!"
		);
		//noinspection unchecked
		final int result = attribute1.comparator().compare(attribute1.attributeValue(), attribute2.attributeValue());
		return result == 0 ? this.pkComparator.compare(o1, o2) : result;
	}

}
