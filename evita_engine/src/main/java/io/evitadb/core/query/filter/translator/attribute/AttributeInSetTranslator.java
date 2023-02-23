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

import io.evitadb.api.query.filter.AttributeInSet;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
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
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeInSet} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeInSetTranslator implements FilteringConstraintTranslator<AttributeInSet> {

	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nonnull
	@Override
	public Formula translate(@Nonnull AttributeInSet attributeInSet, @Nonnull FilterByVisitor filterByVisitor) {
		final String attributeName = attributeInSet.getAttributeName();
		final Serializable[] comparedValues = attributeInSet.getSet();
		final AttributeSchemaContract attributeDefinition = filterByVisitor.getAttributeSchema(attributeName);
		final List<? extends Serializable> valueStream = Arrays.stream(comparedValues)
			.map(it -> EvitaDataTypes.toTargetType(it, attributeDefinition.getPlainType()))
			.map(it -> it instanceof Comparable<?> comparable ? comparable : String.valueOf(it))
			.map(it -> (Serializable)it)
			.toList();

		if (attributeDefinition instanceof GlobalAttributeSchema globalAttributeSchema &&
			globalAttributeSchema.isUniqueGlobally()) {
			// when entity type is not known and attribute is unique globally - access catalog index instead
			return filterByVisitor.applyOnGlobalUniqueIndex(
				attributeDefinition,
				index -> {
					final EntityReferenceContract[] filteredEntityMaskedIds = valueStream.stream()
						.map(index::getEntityReferenceByUniqueValue)
						.filter(Objects::nonNull)
						.toArray(EntityReferenceContract[]::new);

					return ArrayUtils.isEmpty(filteredEntityMaskedIds) ?
						EmptyFormula.INSTANCE :
						new MultipleEntityFormula(
							new BaseBitmap(
								filterByVisitor.translateEntityReference(filteredEntityMaskedIds)
							)
						);
				}
			);
		} else if (attributeDefinition.isUnique()) {
			// if attribute is unique prefer O(1) hash map lookup over histogram
			return new AttributeFormula(
				attributeName,
				filterByVisitor.applyStreamOnUniqueIndexes(
					attributeDefinition,
					index -> valueStream.stream().map(it ->
						ofNullable(index.getRecordIdByUniqueValue(it))
							.map(x -> (Formula) new ConstantFormula(new ArrayBitmap(x)))
							.orElse(EmptyFormula.INSTANCE)
					)
				)
			);
		} else {
			// use histogram lookup
			return new AttributeFormula(
				attributeName,
				filterByVisitor.applyStreamOnFilterIndexes(
					attributeDefinition,
					index -> valueStream.stream().map(it -> index.getRecordsEqualToFormula((Comparable) it))
				)
			);
		}
	}

}
