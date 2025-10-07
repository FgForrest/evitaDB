/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.query.filter.AttributeInRange;
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
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.NumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.Index;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeInRange} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeInRangeTranslator extends AbstractAttributeTranslator
	implements FilteringConstraintTranslator<AttributeInRange> {

	/**
	 * Creates a predicate to check if any attribute value in a stream is valid within a given date-time range.
	 * The predicate will evaluate each optional attribute value in the stream and determine if it falls within the date-time range specified by the given moment.
	 *
	 * @param theMoment The date-time moment against which the attribute values are validated.
	 * @return A predicate that evaluates whether any attribute value within the stream is within the specified date-time range.
	 */
	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> getDateTimeRangePredicate(@Nonnull OffsetDateTime theMoment) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Predicate<DateTimeRange> predicate = attrValue -> attrValue != null && attrValue.isValidFor(theMoment);
					final Serializable attrValue = Objects.requireNonNull(attr.get().value());
					if (attrValue.getClass().isArray()) {
						return Arrays.stream((Object[]) attrValue).map(DateTimeRange.class::cast).anyMatch(predicate);
					} else {
						return predicate.test((DateTimeRange) attrValue);
					}
				}
			}
		);
	}

	/**
	 * Creates a predicate to check if any attribute value in a stream is valid within a given numeric range.
	 * The predicate will evaluate each optional attribute value in the stream and determine if it falls within
	 * the range specified by the given BigDecimal value.
	 *
	 * @param theValue The BigDecimal value against which the attribute values are validated.
	 * @return A predicate that evaluates whether any attribute value within the stream is within the specified numeric range.
	 */
	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> getNumberRangePredicate(@Nonnull BigDecimal theValue) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Predicate<BigDecimalNumberRange> predicate = attrValue -> attrValue != null && attrValue.isWithin(theValue);
					final Serializable attrValue = Objects.requireNonNull(attr.get().value());
					if (attrValue.getClass().isArray()) {
						return Arrays.stream((Object[]) attrValue).map(BigDecimalNumberRange.class::cast).anyMatch(predicate);
					} else {
						return predicate.test((BigDecimalNumberRange) attrValue);
					}
				}
			}
		);
	}

	/**
	 * Creates a predicate to check if any attribute value in a stream is valid within a given numeric range.
	 * The predicate will evaluate each optional attribute value in the stream and determine if it falls within
	 * the range specified by the given Number value.
	 *
	 * @param theValue The Number value against which the attribute values are validated.
	 * @return A predicate that evaluates whether any attribute value within the stream is within the specified numeric range.
	 */
	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> getNumberRangePredicate(@Nonnull Number theValue) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Predicate<NumberRange<Number>> predicate = attrValue -> attrValue != null && attrValue.isWithin(theValue);
					final Serializable attrValue = Objects.requireNonNull(attr.get().value());
					if (attrValue.getClass().isArray()) {
						//noinspection unchecked
						return Arrays.stream((Object[]) attrValue).anyMatch(it -> predicate.test((NumberRange<Number>) it));
					} else {
						//noinspection unchecked
						return predicate.test((NumberRange<Number>) attrValue);
					}
				}
			}
		);
	}

	/**
	 * Creates an {@link AttributeBitmapFilter} instance to filter entities based on a range constraint on an attribute.
	 *
	 * @param attributeInRange an instance of {@link AttributeInRange} containing the range constraint information.
	 * @param filterByVisitor  an instance of {@link FilterByVisitor} providing the current processing context and helper methods.
	 * @param attributeName    the name of the attribute to apply the filter on.
	 * @return an {@link AttributeBitmapFilter} that can be applied to filter entities based on the given range constraint.
	 */
	@Nonnull
	private static AttributeBitmapFilter createAlternativeBitmapFilter(
		@Nonnull AttributeInRange attributeInRange,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull String attributeName
	) {
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			attributeContent(attributeName),
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

	/**
	 * Computes a comparable long value from the provided filter constraint based on the attribute definition type.
	 *
	 * @param filterConstraint    AttributeInRange object containing the constraint information.
	 * @param filterByVisitor     FilterByVisitor object providing additional context and operations.
	 * @param attributeDefinition AttributeSchemaContract object defining the attribute schema.
	 * @return A long representation of the comparable value derived from the filter constraint.
	 * @throws EvitaInvalidUsageException If the attribute type is not supported.
	 */
	private static long getComparableValue(
		@Nonnull AttributeInRange filterConstraint,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AttributeSchemaContract attributeDefinition
	) {
		if (NumberRange.class.isAssignableFrom(attributeDefinition.getPlainType())) {
			final Number theValue = filterConstraint.getTheValue();
			Assert.notNull(theValue, "Argument of InRange must not be null.");
			if (theValue instanceof BigDecimal) {
				return BigDecimalNumberRange.toComparableLong((BigDecimal) theValue, attributeDefinition.getIndexedDecimalPlaces());
			} else {
				return theValue.longValue();
			}
		} else if (DateTimeRange.class.isAssignableFrom(attributeDefinition.getPlainType())) {
			final OffsetDateTime theMoment = ofNullable(filterConstraint.getTheMoment()).orElseGet(filterByVisitor::getNow);
			return DateTimeRange.toComparableLong(theMoment);
		} else {
			throw new EvitaInvalidUsageException("Range types accepts only Number or DateTime types - type " + filterConstraint.getUnknownArgument() + " cannot be used!");
		}
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull AttributeInRange attributeInRange, @Nonnull FilterByVisitor filterByVisitor) {
		final String attributeName = attributeInRange.getAttributeName();

		if (filterByVisitor.isEntityTypeKnown()) {
			final AttributeSchemaContract attributeSchema = filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE);
			final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
			final AttributeKey attributeKey = createAttributeKey(filterByVisitor, attributeSchema);
			final long comparableValue = getComparableValue(attributeInRange, filterByVisitor, attributeSchema);
			final AttributeFormula filteringFormula = new AttributeFormula(
				attributeSchema instanceof GlobalAttributeSchemaContract,
				attributeKey,
				filterByVisitor.applyOnFilterIndexes(
					processingScope.getReferenceSchema(),
					attributeSchema,
					index -> index.getRecordsValidInFormula(comparableValue)
				)
			);
			if (filterByVisitor.isPrefetchPossible()) {
				return new SelectionFormula(
					filteringFormula,
					createAlternativeBitmapFilter(attributeInRange, filterByVisitor, attributeName)
				);
			} else {
				return filteringFormula;
			}
		} else {
			return new EntityFilteringFormula(
				"attribute in range filter",
				createAlternativeBitmapFilter(attributeInRange, filterByVisitor, attributeName)
			);
		}
	}

}
