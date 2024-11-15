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

package io.evitadb.core.query.filter.translator.attribute;

import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.MultipleEntityFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.ArrayBitmap;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.core.query.filter.translator.attribute.AbstractAttributeComparisonTranslator.createAlternativeBitmapFilter;
import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeEquals} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeEqualsTranslator extends AbstractAttributeTranslator
	implements FilteringConstraintTranslator<AttributeEquals> {

	/**
	 * Creates an {@link AttributeFormula} that targets a globally unique attribute schema.
	 *
	 * @param filterByVisitor       The filter visitor that applies filtering logic on global unique indexes.
	 * @param globalAttributeSchema The schema defining global attributes.
	 * @param attributeKey          The key representing the specific attribute.
	 * @param comparedValue         The value to be compared against in the unique index.
	 * @return An {@link AttributeFormula} targeting the globally unique attribute.
	 */
	@Nonnull
	private static AttributeFormula createGloballyUniqueAttributeFormula(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull GlobalAttributeSchema globalAttributeSchema,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Serializable comparedValue
	) {
		// when entity type is not known and attribute is unique globally - access catalog index instead
		return new AttributeFormula(
			true,
			attributeKey,
			filterByVisitor.applyOnFirstGlobalUniqueIndex(
				globalAttributeSchema,
				index -> {
					final EntityReferenceContract<EntityReference> entityReference = index.getEntityReferenceByUniqueValue(
						comparedValue, attributeKey.locale()
					);
					//noinspection unchecked
					return entityReference == null ?
						EmptyFormula.INSTANCE :
						new MultipleEntityFormula(
							new long[]{index.getId()},
							filterByVisitor.translateEntityReference(entityReference)
						);
				}
			)
		);
	}

	/**
	 * Creates an {@link AttributeFormula} that targets a unique attribute schema.
	 *
	 * @param filterByVisitor     The filter visitor that applies filtering logic on unique indexes.
	 * @param attributeDefinition The schema defining the attributes.
	 * @param attributeKey        The key representing the specific attribute.
	 * @param comparedValue       The value to be compared against in the unique index.
	 * @return An {@link AttributeFormula} targeting the unique attribute.
	 */
	@Nonnull
	private static AttributeFormula createUniqueAttributeFormula(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Serializable comparedValue
	) {
		// if attribute is unique prefer O(1) hash map lookup over histogram
		return new AttributeFormula(
			attributeDefinition instanceof GlobalAttributeSchemaContract,
			attributeKey,
			filterByVisitor.applyOnFirstUniqueIndex(
				attributeDefinition, index -> {
					final Integer recordId = index.getRecordIdByUniqueValue(comparedValue);
					return ofNullable(recordId)
						.map(it -> (Formula) new ConstantFormula(new ArrayBitmap(recordId)))
						.orElse(EmptyFormula.INSTANCE);
				}
			)
		);
	}

	/**
	 * Creates an {@link AttributeFormula} that is filterable based on the provided parameters.
	 *
	 * @param filterByVisitor     The filter visitor that applies filtering logic on attribute indexes.
	 * @param attributeDefinition The schema defining the attributes.
	 * @param attributeKey        The key representing the specific attribute.
	 * @param comparedValue       The value to be compared against in the attribute index.
	 * @return An {@link AttributeFormula} representing the filterable attribute logic.
	 */
	@Nonnull
	private static AttributeFormula createFilterableAttributeFormula(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Serializable comparedValue
	) {
		// use histogram lookup
		return new AttributeFormula(
			attributeDefinition instanceof GlobalAttributeSchemaContract,
			attributeKey,
			filterByVisitor.applyOnFilterIndexes(
				attributeDefinition, index -> index.getRecordsEqualToFormula(comparedValue)
			)
		);
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull AttributeEquals attributeEquals, @Nonnull FilterByVisitor filterByVisitor) {
		final String attributeName = attributeEquals.getAttributeName();
		final Serializable attributeValue = attributeEquals.getAttributeValue();
		final Optional<GlobalAttributeSchemaContract> optionalGlobalAttributeSchema = getOptionalGlobalAttributeSchema(filterByVisitor, attributeName);

		if (filterByVisitor.isEntityTypeKnown() || optionalGlobalAttributeSchema.isPresent()) {
			final Set<Scope> scopes = filterByVisitor.getScopes();
			final AttributeSchemaContract attributeDefinition = optionalGlobalAttributeSchema
				.map(AttributeSchemaContract.class::cast)
				.orElseGet(() -> filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE));
			final AttributeKey attributeKey = createAttributeKey(filterByVisitor, attributeDefinition);

			final Class<? extends Serializable> plainType = attributeDefinition.getPlainType();
			final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(plainType);
			final Serializable comparedValue = normalizer.apply(EvitaDataTypes.toTargetType(attributeValue, plainType));

			if (attributeDefinition instanceof GlobalAttributeSchema globalAttributeSchema &&
				scopes.stream().anyMatch(globalAttributeSchema::isUniqueGloballyInScope)) {
				return createGloballyUniqueAttributeFormula(
					filterByVisitor, globalAttributeSchema, attributeKey, comparedValue
				);
			} else if (scopes.stream().anyMatch(attributeDefinition::isUnique)) {
				return createUniqueAttributeFormula(
					filterByVisitor, attributeDefinition, attributeKey, comparedValue
				);
			} else {
				return createFilterableAttributeFormula(
					filterByVisitor, attributeDefinition, attributeKey, comparedValue
				);
			}
		} else {
			return new EntityFilteringFormula(
				"attribute equals filter",
				createAlternativeBitmapFilter(filterByVisitor, attributeName, attributeValue, result -> result == 0)
			);
		}
	}

}
