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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.filter.translator.attribute;

import io.evitadb.api.query.filter.AttributeInRange;
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
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.NumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeInRange} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeInRangeTranslator implements FilteringConstraintTranslator<AttributeInRange> {

	@Nonnull
	@Override
	public Formula translate(@Nonnull AttributeInRange attributeInRange, @Nonnull FilterByVisitor filterByVisitor) {
		final String attributeName = attributeInRange.getAttributeName();

		if (filterByVisitor.isEntityTypeKnown()) {
			final AttributeSchemaContract attributeDefinition = filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE);
			final long comparableValue = getComparableValue(attributeInRange, filterByVisitor, attributeDefinition);
			final AttributeFormula filteringFormula = new AttributeFormula(
				attributeDefinition.isLocalized() ?
					new AttributeKey(attributeName, filterByVisitor.getLocale()) : new AttributeKey(attributeName),
				filterByVisitor.applyOnFilterIndexes(
					attributeDefinition, index -> index.getRecordsValidInFormula(comparableValue)
				)
			);
			if (filterByVisitor.isPrefetchPossible()) {
				return new SelectionFormula(
					filterByVisitor,
					filteringFormula,
					createAlternativeBitmapFilter(attributeInRange, filterByVisitor, attributeName)
				);
			} else {
				return filteringFormula;
			}
		} else {
			return new EntityFilteringFormula(
				"attribute in range filter",
				filterByVisitor,
				createAlternativeBitmapFilter(attributeInRange, filterByVisitor, attributeName)
			);
		}
	}

	@Nonnull
	private static AttributeBitmapFilter createAlternativeBitmapFilter(
		@Nonnull AttributeInRange attributeInRange,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull String attributeName
	) {
		final ProcessingScope processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			processingScope.getRequirements(),
			processingScope::getAttributeSchema,
			(entityContract, theAttributeName) -> processingScope.getAttributeValueStream(entityContract, theAttributeName, filterByVisitor.getLocale()),
			attributeSchema -> {
				if (NumberRange.class.isAssignableFrom(attributeSchema.getPlainType())) {
					final Number theValue = attributeInRange.getTheValue();
					Assert.notNull(theValue, "Argument of InRange must not be null.");
					if (theValue instanceof BigDecimal) {
						return getNumberRangePredicate((BigDecimal) theValue);
					} else {
						return getNumberRangePredicate(theValue);
					}
				} else if (DateTimeRange.class.isAssignableFrom(attributeSchema.getPlainType())) {
					final OffsetDateTime theMoment = ofNullable(attributeInRange.getTheMoment()).orElseGet(filterByVisitor::getNow);
					return getDateTimeRangePredicate(theMoment);
				} else {
					throw new EvitaInvalidUsageException("Range types accepts only Number or DateTime types - type " + attributeInRange.getUnknownArgument() + " cannot be used!");
				}
			}
		);
	}

	private static long getComparableValue(
		@Nonnull AttributeInRange filterConstraint,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AttributeSchemaContract attributeDefinition
	) {
		final long comparableValue;
		if (NumberRange.class.isAssignableFrom(attributeDefinition.getPlainType())) {
			final Number theValue = filterConstraint.getTheValue();
			Assert.notNull(theValue, "Argument of InRange must not be null.");
			if (theValue instanceof BigDecimal) {
				comparableValue = BigDecimalNumberRange.toComparableLong((BigDecimal) theValue, attributeDefinition.getIndexedDecimalPlaces());
			} else {
				comparableValue = theValue.longValue();
			}
		} else if (DateTimeRange.class.isAssignableFrom(attributeDefinition.getPlainType())) {
			final OffsetDateTime theMoment = ofNullable(filterConstraint.getTheMoment()).orElseGet(filterByVisitor::getNow);
			comparableValue = DateTimeRange.toComparableLong(theMoment);
		} else {
			throw new EvitaInvalidUsageException("Range types accepts only Number or DateTime types - type " + filterConstraint.getUnknownArgument() + " cannot be used!");
		}
		return comparableValue;
	}

	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> getDateTimeRangePredicate(@Nonnull OffsetDateTime theMoment) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Predicate<DateTimeRange> predicate = attrValue -> attrValue != null && attrValue.isValidFor(theMoment);
					final Serializable attrValue = attr.get().value();
					if (attrValue.getClass().isArray()) {
						return Arrays.stream((Object[])attrValue).map(DateTimeRange.class::cast).anyMatch(predicate);
					} else {
						return predicate.test((DateTimeRange) attrValue);
					}
				}
			}
		);
	}

	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> getNumberRangePredicate(@Nonnull BigDecimal theValue) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Predicate<BigDecimalNumberRange> predicate = attrValue -> attrValue != null && attrValue.isWithin(theValue);
					final Serializable attrValue = attr.get().value();
					if (attrValue.getClass().isArray()) {
						return Arrays.stream((Object[])attrValue).map(BigDecimalNumberRange.class::cast).anyMatch(predicate);
					} else {
						return predicate.test((BigDecimalNumberRange) attrValue);
					}
				}
			}
		);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> getNumberRangePredicate(@Nonnull Number theValue) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Predicate<NumberRange> predicate = attrValue -> attrValue != null && attrValue.isWithin(theValue);
					final Serializable attrValue = attr.get().value();
					if (attrValue.getClass().isArray()) {
						return Arrays.stream((Object[])attrValue).map(NumberRange.class::cast).anyMatch(predicate);
					} else {
						return predicate.test((NumberRange) attrValue);
					}
				}
			}
		);
	}

}
