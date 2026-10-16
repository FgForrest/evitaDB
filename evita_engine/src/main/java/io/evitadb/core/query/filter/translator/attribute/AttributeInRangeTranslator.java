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
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeValueMatcher;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.NumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.Index;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeInRange} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeInRangeTranslator extends AbstractAttributeTranslator
	implements FilteringConstraintTranslator<AttributeInRange>, AttributeValueMatchingTranslator<AttributeInRange> {

	/**
	 * Computes a comparable long value from the provided filter constraint based on the attribute definition type -
	 * the point the {@link io.evitadb.index.range.RangeIndex} of the attribute is probed with.
	 *
	 * The matcher of this translator tests a single stored range against the very same point instead of probing an
	 * index, so both read one encoding.
	 *
	 * @param filterConstraint     AttributeInRange object containing the constraint information.
	 * @param now                  supplier of the moment of the query, consulted when the constraint names no moment
	 *                             for a date-time range
	 * @param plainType            the plain type of the attribute
	 * @param indexedDecimalPlaces the decimal places the attribute's numbers are indexed with
	 * @return A long representation of the comparable value derived from the filter constraint.
	 * @throws EvitaInvalidUsageException If the attribute type is not supported.
	 */
	public static long getComparableValue(
		@Nonnull AttributeInRange filterConstraint,
		@Nonnull Supplier<OffsetDateTime> now,
		@Nonnull Class<?> plainType,
		int indexedDecimalPlaces
	) {
		if (NumberRange.class.isAssignableFrom(plainType)) {
			final Number theValue = filterConstraint.getTheValue();
			Assert.notNull(theValue, "Argument of InRange must not be null.");
			if (theValue instanceof BigDecimal) {
				return BigDecimalNumberRange.toComparableLong((BigDecimal) theValue, indexedDecimalPlaces);
			} else {
				// non-BigDecimal numeric arguments must be scaled by indexedDecimalPlaces too, otherwise the
				// probe (e.g. `90`) is encoded as a raw long while the range index holds scale-encoded bounds
				// (e.g. `900000` at 4 decimal places) - mirrors AttributeBetweenTranslator's bound encoding
				return BigDecimalNumberRange.toComparableLong(
					BigDecimal.valueOf(theValue.longValue()), indexedDecimalPlaces
				);
			}
		} else if (DateTimeRange.class.isAssignableFrom(plainType)) {
			final OffsetDateTime theMoment = ofNullable(filterConstraint.getTheMoment()).orElseGet(now);
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
			final AttributeValueMatcher matcher = createMainQueryMatcher(
				attributeInRange, filterByVisitor, attributeSchema
			);
			final long comparableValue = getComparableValue(
				attributeInRange, filterByVisitor::getNow,
				attributeSchema.getPlainType(), attributeSchema.getIndexedDecimalPlaces()
			);
			final boolean isNowOnDateTimeRange =
				DateTimeRange.class.isAssignableFrom(attributeSchema.getPlainType())
					&& attributeInRange.getTheMoment() == null;
			final AttributeFormula filteringFormula = new AttributeFormula(
				attributeSchema instanceof GlobalAttributeSchemaContract,
				attributeKey,
				filterByVisitor.applyOnFilterIndexes(
					processingScope.getReferenceSchema(),
					attributeSchema,
					isNowOnDateTimeRange
						? index -> index.getRecordsValidNowFormula(comparableValue)
						: index -> index.getRecordsValidInFormula(comparableValue)
				)
			);
			if (filterByVisitor.isPrefetchPossible()) {
				return new SelectionFormula(
					filteringFormula,
					createAlternativeBitmapFilter(attributeInRange, filterByVisitor, matcher)
				);
			} else {
				return filteringFormula;
			}
		} else {
			return new EntityFilteringFormula(
				"attribute in range filter",
				createEntityFilteringBitmapFilter(attributeInRange, filterByVisitor)
			);
		}
	}

	@Nonnull
	@Override
	public AttributeValueMatcher createMatcher(
		@Nonnull AttributeInRange attributeInRange,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Supplier<OffsetDateTime> now
	) {
		return AttributeValueMatcher.enveloping(
			attributeSchema.getPlainType(), attributeSchema.getIndexedDecimalPlaces(),
			getComparableValue(
				attributeInRange, now, attributeSchema.getPlainType(), attributeSchema.getIndexedDecimalPlaces()
			)
		);
	}

}
