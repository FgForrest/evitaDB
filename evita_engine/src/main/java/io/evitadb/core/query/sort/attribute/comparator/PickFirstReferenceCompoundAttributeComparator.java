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

package io.evitadb.core.query.sort.attribute.comparator;


import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.PickFirstByEntityProperty;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.core.query.sort.reference.sorter.PickFirstReducedIndexResolver;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Comparator for sorting entities according to a sortable compound attribute value. It combines multiple attribute
 * comparators into one. This implementation adheres to {@link MergeMode#APPEND_FIRST} which relates
 * to {@link PickFirstByEntityProperty} ordering.
 *
 * It is the prefetch-route twin of the index route built by {@link PickFirstReducedIndexResolver}, and must pick the
 * very row that route picks: an entity sorts on its first reference, in target order, that carries any element of the
 * compound - references sharing one target in the order of their representative attribute values - and entities with
 * equal compound values follow in the order of their primary keys in the direction of the ordering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class PickFirstReferenceCompoundAttributeComparator extends AbstractReferenceCompoundAttributeComparator {
	@Serial private static final long serialVersionUID = 2199278500724685085L;

	/**
	 * Resolver and derived reference order providing the target order the index route walks.
	 */
	@Nonnull private final transient PickFirstReferenceTargetRanking targetRanking;

	/**
	 * Creates the comparator for the given compound and wires it to the resolver that provides the target order
	 * its index-route twin ({@link PickFirstReducedIndexResolver}) walks.
	 *
	 * @param compoundSchemaContract   the schema of the sortable attribute compound
	 * @param referenceSchema          the schema of the reference the compound belongs to
	 * @param locale                   the locale to use when reading localized attribute values, or `null` when
	 *                                 none of the compound elements are localized
	 * @param attributeSchemaExtractor resolves an attribute name of a compound element to its schema
	 * @param orderDirection           the direction to sort in
	 * @param indexResolver            resolver providing the target order the index route walks
	 */
	public PickFirstReferenceCompoundAttributeComparator(
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nonnull ReferenceSchema referenceSchema,
		@Nullable Locale locale,
		@Nonnull Function<String, AttributeSchemaContract> attributeSchemaExtractor,
		@Nonnull OrderDirection orderDirection,
		@Nonnull PickFirstReducedIndexResolver indexResolver
	) {
		super(
			compoundSchemaContract,
			referenceSchema,
			locale,
			attributeSchemaExtractor,
			orderDirection
		);
		this.targetRanking = new PickFirstReferenceTargetRanking(referenceSchema, indexResolver);
	}

	@Override
	public void prepareForSelection(@Nonnull Bitmap entityPrimaryKeys) {
		this.targetRanking.prepareForSelection(entityPrimaryKeys);
	}

	@Override
	@Nonnull
	protected Optional<ReferenceContract> pickReference(@Nonnull EntityContract entity) {
		// find the first reference in target order that carries any element of the compound
		return entity.getReferences(this.referenceSchema.getName())
			.stream()
			.filter(this::carriesAnyElement)
			.min(this.targetRanking.referenceOrder());
	}

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final ReferenceAttributeValue valueToCompare1 = getAndMemoizeValue(o1);
		final ReferenceAttributeValue valueToCompare2 = getAndMemoizeValue(o2);
		if (valueToCompare1 != ReferenceAttributeValue.MISSING && valueToCompare2 != ReferenceAttributeValue.MISSING) {
			// the picked references are compared on their values only - which target they belong to decided which
			// reference was picked and must not decide the order of the entities as well
			final int result = this.comparator.compare(valueToCompare1.attributeValues(), valueToCompare2.attributeValues());
			return result == 0 ? this.pkComparator.compare(o1, o2) : result;
		} else {
			if (valueToCompare1 != ReferenceAttributeValue.MISSING) {
				return -1;
			} else if (valueToCompare2 != ReferenceAttributeValue.MISSING) {
				return 1;
			} else {
				return 0;
			}
		}
	}

	/**
	 * Tells whether the reference carries a value of at least one element of the compound, read in the locale of the
	 * query - the condition under which the index route holds the reference in the compound's sort index.
	 *
	 * @param reference the reference to test
	 * @return `true` when at least one element has a value
	 */
	private boolean carriesAnyElement(@Nonnull ReferenceContract reference) {
		for (AttributeElement attributeElement : this.attributeElements) {
			if (this.attributeValueFetcher.apply(reference, attributeElement.attributeName()) != null) {
				return true;
			}
		}
		return false;
	}

}
