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
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.sort.EntityReferenceSensitiveComparator;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter.MergeMode;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import static io.evitadb.core.query.sort.EntityReferenceSensitiveComparator.getReferenceByRepresentativeReferenceKey;

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
	@Nullable private RepresentativeReferenceKey referenceKey;

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

	@Nonnull
	@Override
	protected Optional<ReferenceContract> pickReference(@Nonnull EntityContract entity) {
		Assert.isPremiseValid(this.referenceKey != null, "Referenced entity id must be set!");
		return getReferenceByRepresentativeReferenceKey(entity, this.referenceSchema, this.referenceKey);
	}

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final ReferenceAttributeValue attribute1 = getAndMemoizeValue(o1);
		final ReferenceAttributeValue attribute2 = getAndMemoizeValue(o2);
		// to correctly compare the references we need to compare only attributes on the same reference
		final boolean bothAttributesSpecified = attribute1 != ReferenceAttributeValue.MISSING && attribute2 != ReferenceAttributeValue.MISSING;
		final boolean attributesExistOnSameReference = bothAttributesSpecified && attribute1.referencedEntityPrimaryKey() == attribute2.referencedEntityPrimaryKey();
		if (attributesExistOnSameReference) {
			return attribute1.compareTo(attribute2);
		} else if (bothAttributesSpecified) {
			return Integer.compare(attribute1.referencedEntityPrimaryKey(), attribute2.referencedEntityPrimaryKey());
		} else {
			if (attribute1 != ReferenceAttributeValue.MISSING) {
				return -1;
			} else if (attribute2 != ReferenceAttributeValue.MISSING) {
				return 1;
			} else {
				return 0;
			}
		}
	}

}
