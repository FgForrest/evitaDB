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

import io.evitadb.api.query.filter.AttributeBetween;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.attribute.BetweenAttributeFormula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.attribute.alternative.AttributeValueMatcher;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.NumberRange;
import io.evitadb.dataType.Range;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.Index;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static io.evitadb.dataType.EvitaDataTypes.toTargetType;
import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeBetween} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeBetweenTranslator extends AbstractAttributeTranslator
	implements FilteringConstraintTranslator<AttributeBetween>, AttributeValueMatchingTranslator<AttributeBetween> {

	/**
	 * Creates a predicate for filtering {@link BigDecimal} objects within a specified range.
	 *
	 * @param from The lower bound of the range, may be null.
	 * @param to   The upper bound of the range, may be null.
	 * @return A predicate that returns true if the {@link BigDecimal} object falls within the specified range.
	 */
	@Nonnull
	private static Predicate<BigDecimal> createBigDecimalPredicate(@Nullable BigDecimal from, @Nullable BigDecimal to) {
		return threshold -> (from == null || threshold.compareTo(from) >= 0) &&
			(to == null || threshold.compareTo(to) <= 0);
	}

	/**
	 * Creates an {@link AttributeFormula} for a range attribute based on the provided parameters.
	 *
	 * @param filterByVisitor     The visitor used to apply filter indexes.
	 * @param from                The starting value of the range.
	 * @param to                  The ending value of the range.
	 * @param attributeSchema The schema definition of the attribute.
	 * @param attributeKey        The key of the attribute being filtered.
	 * @return The constructed {@link AttributeFormula}.
	 */
	@Nonnull
	private static AttributeFormula createRangeAttributeFormula(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nullable Serializable from,
		@Nullable Serializable to,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull AttributeKey attributeKey
	) {
		final Class<? extends Serializable> attributeType = attributeSchema.getPlainType();
		final int indexedDecimalPlaces = attributeSchema.getIndexedDecimalPlaces();
		final Predicate<BigDecimal> requestedPredicate = NumberRange.class.isAssignableFrom(attributeType) ?
			createBigDecimalPredicate(toTargetType(from, BigDecimal.class), toTargetType(to, BigDecimal.class)) :
			null;
		final long comparableFrom = encodeRangeBound(attributeType, indexedDecimalPlaces, from, Long.MIN_VALUE);
		final long comparableTo = encodeRangeBound(attributeType, indexedDecimalPlaces, to, Long.MAX_VALUE);
		final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
		return new BetweenAttributeFormula(
			attributeSchema instanceof GlobalAttributeSchemaContract,
			attributeKey,
			filterByVisitor.applyOnFilterIndexes(
				processingScope.getReferenceSchema(),
				attributeSchema,
				index -> index.getRecordsOverlappingFormula(comparableFrom, comparableTo)
			),
			requestedPredicate
		);
	}

	/**
	 * Encodes one bound of an {@link AttributeBetween} over a range-typed attribute into the `long` threshold the
	 * {@link io.evitadb.index.range.RangeIndex} of the attribute keys its ranges by - the very value
	 * {@link FilterIndex#getRecordsOverlappingFormula(long, long)} is probed with. A number range bound is encoded at
	 * the attribute's indexed decimal places when it keeps any and as a plain `long` otherwise, a date-time range bound
	 * as its comparable moment.
	 *
	 * The matcher of this translator tests a single stored range against the very same thresholds instead of probing
	 * an index, so both read one encoding.
	 *
	 * @param rangeType            the plain type of the range-typed attribute
	 * @param indexedDecimalPlaces the decimal places the attribute's numbers are indexed with
	 * @param bound                the bound of the constraint, NULL when that side of the interval is open
	 * @param unboundedValue       the threshold an open side is encoded as
	 * @return the encoded threshold
	 * @throws GenericEvitaInternalError when the type is no range type the engine knows
	 */
	public static long encodeRangeBound(
		@Nonnull Class<?> rangeType,
		int indexedDecimalPlaces,
		@Nullable Serializable bound,
		long unboundedValue
	) {
		if (NumberRange.class.isAssignableFrom(rangeType)) {
			if (indexedDecimalPlaces > 0) {
				return getBigDecimalComparable(
					toTargetType(bound, BigDecimal.class), indexedDecimalPlaces, unboundedValue
				);
			} else {
				return getLongComparable(toTargetType(bound, Long.class), unboundedValue);
			}
		} else if (DateTimeRange.class.isAssignableFrom(rangeType)) {
			return getOffsetDateTimeComparable(toTargetType(bound, OffsetDateTime.class), unboundedValue);
		} else {
			throw new GenericEvitaInternalError("Unexpected Range type!");
		}
	}

	/**
	 * Converts an OffsetDateTime value to a comparable long representation.
	 * If the given OffsetDateTime value is null, a default value is returned.
	 *
	 * @param value        The OffsetDateTime value to be converted, may be null.
	 * @param defaultValue The default long value to return if the OffsetDateTime value is null.
	 * @return The long representation of the OffsetDateTime value, or the default value if the OffsetDateTime is null.
	 */
	@Nonnull
	private static Long getOffsetDateTimeComparable(@Nullable OffsetDateTime value, long defaultValue) {
		return ofNullable(value).map(DateTimeRange::toComparableLong).orElse(defaultValue);
	}

	/**
	 * Returns a Long value that is either the provided value or a default value if the provided value is null.
	 *
	 * @param value        The possibly null Long to be returned.
	 * @param defaultValue The long value to be returned if the provided value is null.
	 * @return The provided Long value, or the default value if the provided value is null.
	 */
	@Nonnull
	private static Long getLongComparable(@Nullable Long value, long defaultValue) {
		return ofNullable(value).orElse(defaultValue);
	}

	/**
	 * Converts a BigDecimal value to a comparable long representation based on the specified number of decimal places.
	 * If the given BigDecimal value is null, a default value is returned.
	 *
	 * @param value                The BigDecimal value to be converted, may be null.
	 * @param indexedDecimalPlaces The number of decimal places to consider in the conversion.
	 * @param defaultValue         The default long value to return if the BigDecimal value is null.
	 * @return The long representation of the BigDecimal value or the default value if the BigDecimal is null.
	 */
	@Nonnull
	private static Long getBigDecimalComparable(@Nullable BigDecimal value, int indexedDecimalPlaces, long defaultValue) {
		return ofNullable(value)
			.map(it -> BigDecimalNumberRange.toComparableLong(it, indexedDecimalPlaces))
			.orElse(defaultValue);
	}

	@Override
	@Nonnull
	public Formula translate(@Nonnull AttributeBetween filterConstraint, @Nonnull FilterByVisitor filterByVisitor) {
		final String attributeName = filterConstraint.getAttributeName();

		final Serializable from = filterConstraint.getFrom();
		final Serializable to = filterConstraint.getTo();

		final Optional<GlobalAttributeSchemaContract> optionalGlobalAttributeSchema = getOptionalGlobalAttributeSchema(filterByVisitor, attributeName, AttributeTrait.FILTERABLE);
		if (filterByVisitor.isEntityTypeKnown() || optionalGlobalAttributeSchema.isPresent()) {
			final AttributeSchemaContract attributeSchema = optionalGlobalAttributeSchema
				.map(AttributeSchemaContract.class::cast)
				.orElseGet(() -> filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE));
			// created before any index is read, so an interval or a value the attribute cannot hold is refused whatever
			// it stores
			final AttributeValueMatcher matcher = createMainQueryMatcher(
				filterConstraint, filterByVisitor, attributeSchema
			);
			final AttributeKey attributeKey = createAttributeKey(filterByVisitor, attributeSchema);
			final Class<? extends Serializable> attributeType = attributeSchema.getPlainType();

			final AttributeFormula filteringFormula;
			final Predicate<BigDecimal> requestedPredicate;
			if (Range.class.isAssignableFrom(attributeType)) {
				filteringFormula = createRangeAttributeFormula(
					filterByVisitor, from, to, attributeSchema, attributeKey
				);
			} else {
				if (Number.class.isAssignableFrom(attributeType)) {
					final BigDecimal fromBigDecimal = toTargetType(from, BigDecimal.class);
					final BigDecimal toBigDecimal = toTargetType(to, BigDecimal.class);
					requestedPredicate = createBigDecimalPredicate(fromBigDecimal, toBigDecimal);
				} else {
					requestedPredicate = null;
				}

				final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
				final Serializable convertedFrom = toTargetType(from, attributeType);
				final Serializable convertedTo = toTargetType(to, attributeType);
				filteringFormula = new BetweenAttributeFormula(
					attributeSchema instanceof GlobalAttributeSchemaContract,
					attributeKey,
					filterByVisitor.applyOnFilterIndexes(
						processingScope.getReferenceSchema(),
						attributeSchema,
						index -> {
							if (convertedFrom != null && convertedTo != null) {
								return index.getRecordsBetweenFormula(convertedFrom, convertedTo);
							} else if (convertedFrom == null && convertedTo != null) {
								return index.getRecordsLesserThanEqFormula(convertedTo);
							} else if (convertedFrom != null) {
								return index.getRecordsGreaterThanEqFormula(convertedFrom);
							} else {
								throw new GenericEvitaInternalError("Between query can never be created with both bounds null!");
							}
						}
					),
					requestedPredicate
				);
			}
			if (filterByVisitor.isPrefetchPossible()) {
				return new SelectionFormula(
					filteringFormula,
					createAlternativeBitmapFilter(filterConstraint, filterByVisitor, matcher, AttributeTrait.FILTERABLE)
				);
			} else {
				return filteringFormula;
			}
		} else {
			return new EntityFilteringFormula(
				"attribute between filter",
				createEntityFilteringBitmapFilter(filterConstraint, filterByVisitor, AttributeTrait.FILTERABLE)
			);
		}
	}

	@Nonnull
	@Override
	public AttributeValueMatcher createMatcher(
		@Nonnull AttributeBetween attributeBetween,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Supplier<OffsetDateTime> now
	) {
		final Class<? extends Serializable> plainType = attributeSchema.getPlainType();
		final int indexedDecimalPlaces = attributeSchema.getIndexedDecimalPlaces();
		if (Range.class.isAssignableFrom(plainType)) {
			return AttributeValueMatcher.overlapping(
				plainType, indexedDecimalPlaces,
				encodeRangeBound(plainType, indexedDecimalPlaces, attributeBetween.getFrom(), Long.MIN_VALUE),
				encodeRangeBound(plainType, indexedDecimalPlaces, attributeBetween.getTo(), Long.MAX_VALUE)
			);
		} else {
			return AttributeValueMatcher.within(
				attributeIndexKey, plainType, indexedDecimalPlaces, attributeBetween.getFrom(), attributeBetween.getTo()
			);
		}
	}

	@Nonnull
	@Override
	public Serializable[] getIndexProbeValues(@Nonnull AttributeBetween attributeBetween) {
		return new Serializable[]{attributeBetween.getFrom(), attributeBetween.getTo()};
	}

}
