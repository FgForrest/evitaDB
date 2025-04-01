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


import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.TraverseByEntityProperty;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.query.sort.EntityReferenceSensitiveComparator;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter.MergeMode;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Comparator for sorting entities according to a sortable compound attribute value. It combines multiple attribute
 * comparators into one. This implementation adheres to {@link MergeMode#APPEND_ALL} which relates
 * to {@link TraverseByEntityProperty} ordering.
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
	@Nullable private Integer referencedEntityId;

	public TraverseReferenceCompoundAttributeComparator(
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nonnull ReferenceSchemaContract referenceSchema,
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
	 * Temporarily sets the ID of a referenced entity, executes a given lambda expression,
	 * and then resets the referenced entity ID to null to ensure proper cleanup.
	 *
	 * @param referencedEntityId the ID of the referenced entity to be temporarily set
	 * @param lambda the lambda expression to execute while the referenced entity ID is set
	 * @throws GenericEvitaInternalError if the referenced entity ID has already been set
	 */
	@Override
	public void withReferencedEntityId(int referencedEntityId, @Nonnull Runnable lambda) {
		try {
			Assert.isPremiseValid(this.referencedEntityId == null, "Cannot set referenced entity id twice!");
			this.referencedEntityId = referencedEntityId;
			lambda.run();
		} finally {
			this.referencedEntityId = null;
		}
	}

	@Nonnull
	@Override
	protected Optional<ReferenceContract> pickReference(@Nonnull EntityContract entity) {
		Assert.isPremiseValid(this.referencedEntityId != null, "Referenced entity id must be set!");
		return entity.getReference(this.referenceName, this.referencedEntityId);
	}

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final ReferenceAttributeValue valueToCompare1 = getAndMemoizeValue(o1);
		final ReferenceAttributeValue valueToCompare2 = getAndMemoizeValue(o2);
		if (valueToCompare1 != ReferenceAttributeValue.MISSING && valueToCompare2 != ReferenceAttributeValue.MISSING) {
			return valueToCompare1.compareTo(valueToCompare2);
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

}
