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

import io.evitadb.api.query.filter.AttributeGreaterThan;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
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
import io.evitadb.dataType.EvitaDataTypes;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeGreaterThan} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeGreaterThanTranslator implements FilteringConstraintTranslator<AttributeGreaterThan> {

	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nonnull
	@Override
	public Formula translate(@Nonnull AttributeGreaterThan attributeGreaterThan, @Nonnull FilterByVisitor filterByVisitor) {
		final String attributeName = attributeGreaterThan.getAttributeName();
		final Serializable attributeValue = attributeGreaterThan.getAttributeValue();

		if (filterByVisitor.isEntityTypeKnown()) {
			final AttributeSchemaContract attributeDefinition = filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE);
			final Class<? extends Serializable> attributeType = attributeDefinition.getPlainType();
			final Comparable comparableValue = (Comparable) EvitaDataTypes.toTargetType(attributeValue, attributeType);

			final Predicate<BigDecimal> requestedPredicate;
			if (Number.class.isAssignableFrom(attributeType)) {
				final BigDecimal fromBigDecimal = EvitaDataTypes.toTargetType((Serializable) comparableValue, BigDecimal.class);
				requestedPredicate = threshold -> {
					if (fromBigDecimal != null && threshold.compareTo(fromBigDecimal) <= 0) {
						return false;
					}
					return true;
				};
			} else {
				requestedPredicate = null;
			}

			final AttributeFormula filteringFormula = new AttributeFormula(
				attributeDefinition instanceof GlobalAttributeSchemaContract,
				attributeDefinition.isLocalized() ?
					new AttributeKey(attributeName, filterByVisitor.getLocale()) : new AttributeKey(attributeName),
				filterByVisitor.applyOnFilterIndexes(
					attributeDefinition, index -> index.getRecordsGreaterThanFormula(comparableValue)
				),
				requestedPredicate
			);
			if (filterByVisitor.isPrefetchPossible()) {
				return new SelectionFormula(
					filteringFormula,
					createAlternativeBitmapFilter(filterByVisitor, attributeName, attributeValue)
				);
			} else {
				return filteringFormula;
			}
		} else {
			return new EntityFilteringFormula(
				"attribute greater than filter",
				createAlternativeBitmapFilter(filterByVisitor, attributeName, attributeValue)
			);
		}
	}

	@SuppressWarnings("rawtypes")
	@Nonnull
	private static AttributeBitmapFilter createAlternativeBitmapFilter(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull String attributeName,
		@Nonnull Serializable attributeValue
	) {
		final ProcessingScope processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			processingScope.getRequirements(),
			processingScope::getAttributeSchema,
			(entityContract, theAttributeName) -> processingScope.getAttributeValueStream(entityContract, theAttributeName, filterByVisitor.getLocale()),
			attributeSchema -> {
				final Comparable comparableValue = (Comparable) EvitaDataTypes.toTargetType(attributeValue, attributeSchema.getPlainType());
				return getPredicate(comparableValue);
			}
		);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> getPredicate(Comparable<?> comparableValue) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Predicate<Comparable> predicate = theValue -> theValue != null && theValue.compareTo(comparableValue) > 0;
					final Serializable theValue = attr.get().value();
					if (theValue.getClass().isArray()) {
						return Arrays.stream((Object[])theValue).map(Comparable.class::cast).anyMatch(predicate);
					} else {
						return predicate.test((Comparable)theValue);
					}
				}
			}
		);
	}

}
