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

import io.evitadb.api.query.filter.AttributeEndsWith;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeBitmapFilter;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.core.query.filter.translator.attribute.AttributeContainsTranslator.assertStringType;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeEndsWith} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeEndsWithTranslator implements FilteringConstraintTranslator<AttributeEndsWith> {

	@Nonnull
	@Override
	public Formula translate(@Nonnull AttributeEndsWith attributeEndsWith, @Nonnull FilterByVisitor filterByVisitor) {
		final String attributeName = attributeEndsWith.getAttributeName();
		final String textToSearch = attributeEndsWith.getTextToSearch();

		if (filterByVisitor.isEntityTypeKnown()) {
			final AttributeSchemaContract attributeDefinition = filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE);
			assertStringType(attributeDefinition);
			final Formula filteringFormula = filterByVisitor.applyOnFilterIndexes(
				attributeDefinition, index -> {
					/* TOBEDONE JNO naive and slow - use RadixTree */
					final Formula[] foundRecords = index.getValues()
						.stream()
						.filter(it -> ((String)it).endsWith(textToSearch))
						.map(index::getRecordsEqualToFormula)
						.toArray(Formula[]::new);
					return ArrayUtils.isEmpty(foundRecords) ?
						EmptyFormula.INSTANCE : FormulaFactory.or(foundRecords);
				}
			);
			if (filterByVisitor.isPrefetchPossible()) {
				return new SelectionFormula(
					filterByVisitor,
					new AttributeFormula(
						attributeDefinition.isLocalized() ?
							new AttributeKey(attributeName, filterByVisitor.getLocale()) : new AttributeKey(attributeName),
						filteringFormula
					),
					createAlternativeBitmapFilter(filterByVisitor, attributeName, textToSearch)
				);
			} else {
				return filteringFormula;
			}
		} else {
			return new EntityFilteringFormula(
				"attribute ends with filter",
				filterByVisitor,
				createAlternativeBitmapFilter(filterByVisitor, attributeName, textToSearch)
			);
		}
	}

	@Nonnull
	private static AttributeBitmapFilter createAlternativeBitmapFilter(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull String attributeName,
		@Nonnull String textToSearch
	) {
		final ProcessingScope processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			processingScope.getRequirements(),
			processingScope::getAttributeSchema,
			(entityContract, theAttributeName) -> processingScope.getAttributeValueStream(entityContract, theAttributeName, filterByVisitor.getLocale()),
			attributeSchema -> {
				assertStringType(attributeSchema);
				return getPredicate(textToSearch);
			}
		);
	}

	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> getPredicate(String textToSearch) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Serializable theValue = attr.get().getValue();
					return theValue != null && ((String) theValue).endsWith(textToSearch);
				}
			}
		);
	}

}
