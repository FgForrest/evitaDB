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


import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.TraverseByEntityProperty;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.sort.EntityReferenceSensitiveComparator;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.evitadb.core.query.sort.EntityReferenceSensitiveComparator.getFirstValuedReference;

/**
 * Comparator for sorting entities according to a sortable compound attribute value. It combines multiple attribute
 * comparators into one. This implementation adheres to {@link MergeMode#APPEND_ALL} which relates
 * to {@link TraverseByEntityProperty} ordering.
 *
 * The comparator sorts one traversal block at a time - the block key, naming the referenced entity of the block, is
 * bound by {@link #withReferencedEntityId(RepresentativeReferenceKey, Runnable)}. An entity is represented in the
 * block by its first row to that referenced entity, in the order of the representative attribute values, that carries
 * at least one element of the compound; an entity without such a row is classified as not sortable
 * ({@link #canSort(EntityContract)}), so it is passed on to the next block rather than claimed by this one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class TraverseReferenceCompoundAttributeComparator
	extends AbstractReferenceCompoundAttributeComparator
	implements EntityReferenceSensitiveComparator
{
	@Serial private static final long serialVersionUID = 2199278500724685085L;
	/**
	 * The name of the reference that is being traversed.
	 */
	private final String referenceName;
	/**
	 * The id of the referenced entity that is being traversed.
	 */
	@Nullable private RepresentativeReferenceKey referenceKey;
	/**
	 * Tells whether a row carries at least one element of the compound - created once, so picking a row allocates no
	 * lambda.
	 */
	@Nonnull private final Predicate<ReferenceContract> carriesValue = this::carriesAnyElement;

	public TraverseReferenceCompoundAttributeComparator(
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nonnull ReferenceSchema referenceSchema,
		@Nullable Locale locale,
		@Nonnull Function<String, AttributeSchemaContract> attributeSchemaExtractor,
		@Nonnull OrderDirection orderDirection
	) {
		super(
			compoundSchemaContract,
			referenceSchema,
			locale,
			attributeSchemaExtractor,
			orderDirection
		);
		this.referenceName = referenceSchema.getName();
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
	 * entity, in the order of the representative attribute values, that carries at least one element of the compound.
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
	 * Tells whether the row carries at least one element of the compound - the condition under which
	 * {@link #canSort(EntityContract)} admits an entity represented by the row.
	 *
	 * @param reference the row
	 * @return `true` when some element of the compound is set on the row
	 */
	private boolean carriesAnyElement(@Nonnull ReferenceContract reference) {
		for (AttributeElement attributeElement : this.attributeElements) {
			if (this.attributeValueFetcher.apply(reference, attributeElement.attributeName()) != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Compares two entities classified as sortable by {@link #canSort(EntityContract)} within the bound block by the
	 * compound values of their representing rows - which rows they are takes no part; entities with equal values follow
	 * their own primary keys in the direction of the ordering, as on the index route. Entities without
	 * the value never get here, the sorter passes them on to the next block before any comparison.
	 *
	 * @param o1 the first entity
	 * @param o2 the second entity
	 * @return the comparison result
	 */
	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final ReferenceAttributeValue attribute1 = getAndMemoizeValue(o1);
		final ReferenceAttributeValue attribute2 = getAndMemoizeValue(o2);
		Assert.isPremiseValid(
			attribute1 != ReferenceAttributeValue.MISSING && attribute2 != ReferenceAttributeValue.MISSING,
			"Only entities classified as sortable within the bound block may be compared!"
		);
		final int result = attribute1.compareTo(attribute2);
		return result == 0 ? this.pkComparator.compare(o1, o2) : result;
	}

}
