/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.core.query.filter.translator.attribute;

import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.prefetch.MultipleEntityFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.index.bitmap.ArrayBitmap;

import javax.annotation.Nonnull;
import java.io.Serializable;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeEquals} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeEqualsTranslator implements FilteringConstraintTranslator<AttributeEquals> {

	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nonnull
	@Override
	public Formula translate(@Nonnull AttributeEquals attributeEquals, @Nonnull FilterByVisitor filterByVisitor) {
		final String attributeName = attributeEquals.getAttributeName();
		final Serializable attributeValue = attributeEquals.getAttributeValue();
		final AttributeSchemaContract attributeDefinition = filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE);
		final Serializable targetType = EvitaDataTypes.toTargetType(attributeValue, attributeDefinition.getPlainType());
		final Comparable comparableValue = targetType instanceof Comparable comparable ? comparable : targetType.toString();

		if (attributeDefinition instanceof GlobalAttributeSchema globalAttributeSchema &&
			globalAttributeSchema.isUniqueGlobally()) {
			// when entity type is not known and attribute is unique globally - access catalog index instead
			return filterByVisitor.applyOnGlobalUniqueIndex(
				attributeDefinition, index -> {
					final EntityReferenceContract<EntityReference> entityReference = index.getEntityReferenceByUniqueValue((Serializable) comparableValue);
					return entityReference == null ?
						EmptyFormula.INSTANCE :
						new MultipleEntityFormula(
							filterByVisitor.translateEntityReference(entityReference)
						);
				}
			);
		} else if (attributeDefinition.isUnique()) {
			// if attribute is unique prefer O(1) hash map lookup over histogram
			return new AttributeFormula(
				attributeName,
				filterByVisitor.applyOnUniqueIndexes(
					attributeDefinition, index -> {
						final Integer recordId = index.getRecordIdByUniqueValue((Serializable) comparableValue);
						return ofNullable(recordId).map(it -> (Formula) new ConstantFormula(new ArrayBitmap(recordId))).orElse(EmptyFormula.INSTANCE);
					}
				)
			);
		} else {
			// use histogram lookup
			return new AttributeFormula(
				attributeName,
				filterByVisitor.applyOnFilterIndexes(
					attributeDefinition, index -> index.getRecordsEqualToFormula(comparableValue)
				)
			);
		}
	}

}
