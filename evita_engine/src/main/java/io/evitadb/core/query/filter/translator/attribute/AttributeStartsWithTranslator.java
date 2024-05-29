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

import io.evitadb.api.query.filter.AttributeStartsWith;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeBitmapFilter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.core.query.filter.translator.attribute.AttributeContainsTranslator.assertStringType;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeStartsWith} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeStartsWithTranslator implements FilteringConstraintTranslator<AttributeStartsWith> {

	@Nonnull
	@Override
	public Formula translate(@Nonnull AttributeStartsWith attributeStartsWith, @Nonnull FilterByVisitor filterByVisitor) {
		final String attributeName = attributeStartsWith.getAttributeName();
		final String textToSearch = attributeStartsWith.getTextToSearch();

		if (filterByVisitor.isEntityTypeKnown()) {
			final AttributeSchemaContract attributeDefinition = filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE);
			assertStringType(attributeDefinition);

			final AttributeFormula filteringFormula = new AttributeFormula(
				attributeDefinition.isLocalized() ?
					new AttributeKey(attributeName, filterByVisitor.getLocale()) : new AttributeKey(attributeName),
				filterByVisitor.applyOnFilterIndexes(
					attributeDefinition, index -> {
						/* TOBEDONE JNO naive and slow - use RadixTree */
						return index.getRecordsWhoseValuesStartWith(textToSearch);
					}
				)
			);

			if (filterByVisitor.isPrefetchPossible()) {
				return new SelectionFormula(
					filterByVisitor,
					filteringFormula,
					createAlternativeBitmapFilter(filterByVisitor, attributeName, textToSearch)
				);
			} else {
				return filteringFormula;
			}
		} else {
			return new EntityFilteringFormula(
				"attribute starts with filter",
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
					final Predicate<String> predicate = theValue -> theValue != null && theValue.startsWith(textToSearch);
					final Serializable theValue = attr.get().value();
					if (theValue.getClass().isArray()) {
						return Arrays.stream((Object[])theValue).map(String.class::cast).anyMatch(predicate);
					} else {
						return predicate.test((String)theValue);
					}
				}
			}
		);
	}

}
