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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
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
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.NumberRange;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.Index;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.FilterIndex;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.dataType.EvitaDataTypes.toTargetType;
import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeBetween} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeBetweenTranslator extends AbstractAttributeTranslator
	implements FilteringConstraintTranslator<AttributeBetween> {

	/**
	 * Generates a predicate to determine if a stream of optional attribute values contains an element
	 * that falls within the specified date-time range.
	 *
	 * @param from the starting bound of the range (inclusive), can be null.
	 * @param to the ending bound of the range (inclusive), can be null.
	 * @return a predicate that evaluates whether any element within the stream falls within the specified date-time range.
	 */
	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> getDateTimePredicate(
		@Nullable OffsetDateTime from,
		@Nullable OffsetDateTime to
	) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					//noinspection rawtypes
					final Predicate<Comparable> predicate = theValue -> {
						if (theValue == null) {
							return false;
						} else if (from != null && to != null) {
							return ((DateTimeRange) theValue).overlaps(DateTimeRange.between(from, to));
						} else if (from != null) {
							return ((DateTimeRange) theValue).overlaps(DateTimeRange.since(from));
						} else if (to != null) {
							return ((DateTimeRange) theValue).overlaps(DateTimeRange.until(to));
						} else {
							throw new GenericEvitaInternalError("Between query can never be created with both bounds null!");
						}
					};
					final Serializable theValue = Objects.requireNonNull(attr.get().value());
					if (theValue.getClass().isArray()) {
						return Arrays.stream((Object[]) theValue).map(Comparable.class::cast).anyMatch(predicate);
					} else {
						//noinspection rawtypes
						return predicate.test((Comparable) theValue);
					}
				}
			}
		);
	}

	/**
	 * Generates a predicate to determine if a stream of optional attribute values contains an element
	 * that falls within the specified numerical range.
	 *
	 * @param from the starting bound of the range (inclusive), can be null.
	 * @param to the ending bound of the range (inclusive), can be null.
	 * @return a predicate that evaluates whether any element within the stream falls within the specified numerical range.
	 */
	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> getNumberRangePredicate(
		@Nullable Number from,
		@Nullable Number to
	) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					//noinspection rawtypes
					final Predicate<Comparable> predicate = theValue -> {
						if (theValue == null) {
							return false;
						} else if (from != null && to != null) {
							if (from instanceof BigDecimal fromBD && to instanceof BigDecimal toBD) {
								return ((BigDecimalNumberRange) theValue).overlaps(BigDecimalNumberRange.between(fromBD, toBD));
							} else if (from instanceof Long fromL && to instanceof Long toL) {
								return ((LongNumberRange) theValue).overlaps(LongNumberRange.between(fromL, toL));
							} else if (from instanceof Integer fromI && to instanceof Integer toI) {
								return ((IntegerNumberRange) theValue).overlaps(IntegerNumberRange.between(fromI, toI));
							} else if (from instanceof Short fromS && to instanceof Short toS) {
								return ((ShortNumberRange) theValue).overlaps(ShortNumberRange.between(fromS, toS));
							} else if (from instanceof Byte fromB && to instanceof Byte toB) {
								return ((ByteNumberRange) theValue).overlaps(ByteNumberRange.between(fromB, toB));
							} else {
								throw new GenericEvitaInternalError("Unexpected input type: " + from + ", " + to);
							}
						} else if (from != null) {
							if (from instanceof BigDecimal fromBD) {
								return ((BigDecimalNumberRange) theValue).overlaps(BigDecimalNumberRange.from(fromBD));
							} else if (from instanceof Long fromL) {
								return ((LongNumberRange) theValue).overlaps(LongNumberRange.from(fromL));
							} else if (from instanceof Integer fromI) {
								return ((IntegerNumberRange) theValue).overlaps(IntegerNumberRange.from(fromI));
							} else if (from instanceof Short fromS) {
								return ((ShortNumberRange) theValue).overlaps(ShortNumberRange.from(fromS));
							} else if (from instanceof Byte fromB) {
								return ((ByteNumberRange) theValue).overlaps(ByteNumberRange.from(fromB));
							} else {
								throw new GenericEvitaInternalError("Unexpected input type: " + from);
							}
						} else if (to != null) {
							if (to instanceof BigDecimal toBD) {
								return ((BigDecimalNumberRange) theValue).overlaps(BigDecimalNumberRange.to(toBD));
							} else if (to instanceof Long toL) {
								return ((LongNumberRange) theValue).overlaps(LongNumberRange.to(toL));
							} else if (to instanceof Integer toI) {
								return ((IntegerNumberRange) theValue).overlaps(IntegerNumberRange.to(toI));
							} else if (to instanceof Short toS) {
								return ((ShortNumberRange) theValue).overlaps(ShortNumberRange.to(toS));
							} else if (to instanceof Byte toB) {
								return ((ByteNumberRange) theValue).overlaps(ByteNumberRange.to(toB));
							} else {
								throw new GenericEvitaInternalError("Unexpected input type: " + to);
							}
						} else {
							throw new GenericEvitaInternalError("Between query can never be created with both bounds null!");
						}
					};
					final Serializable theValue = Objects.requireNonNull(attr.get().value());
					if (theValue.getClass().isArray()) {
						return Arrays.stream((Object[]) theValue).map(Comparable.class::cast).anyMatch(predicate);
					} else {
						//noinspection rawtypes
						return predicate.test((Comparable) theValue);
					}
				}
			}
		);
	}

	/**
	 * Creates a predicate for filtering streams of optional attribute values, ensuring that the attributes fall within a specified range.
	 *
	 * @param normalizer The function used to normalize the attribute values.
	 * @param comparator The comparator used to compare the attribute values.
	 * @param from The lower bound of the comparable range, may be null.
	 * @param to The upper bound of the comparable range, may be null.
	 * @return A predicate that returns true if any attribute in the stream falls within the specified range.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> getComparablePredicate(
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator comparator,
		@Nullable Serializable from,
		@Nullable Serializable to
	) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Predicate<Comparable> predicate = theValue -> {
						if (theValue == null) {
							return false;
						} else {
							if (from != null && to != null) {
								return comparator.compare(theValue, from) >= 0 &&
									comparator.compare(theValue, to) <= 0;
							} else if (from != null) {
								return comparator.compare(theValue, from) >= 0;
							} else if (to != null) {
								return comparator.compare(theValue, to) <= 0;
							} else {
								throw new GenericEvitaInternalError("Between query can never be created with both bounds null!");
							}
						}
					};
					final Serializable theValue = Objects.requireNonNull(attr.get().value());
					if (theValue.getClass().isArray()) {
						return Arrays.stream((Object[]) theValue)
							.map(normalizer)
							.map(Comparable.class::cast)
							.anyMatch(predicate);
					} else {
						return predicate.test((Comparable) normalizer.apply(theValue));
					}
				}
			}
		);
	}

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
		final Predicate<BigDecimal> requestedPredicate;
		final long comparableFrom;
		final long comparableTo;

		final Class<? extends Serializable> attributeType = attributeSchema.getPlainType();
		if (NumberRange.class.isAssignableFrom(attributeType)) {
			final BigDecimal fromBigDecimal = EvitaDataTypes.toTargetType(from, BigDecimal.class);
			final BigDecimal toBigDecimal = EvitaDataTypes.toTargetType(to, BigDecimal.class);
			if (attributeSchema.getIndexedDecimalPlaces() > 0) {
				comparableFrom = getBigDecimalComparable(fromBigDecimal, attributeSchema.getIndexedDecimalPlaces(), Long.MIN_VALUE);
				comparableTo = getBigDecimalComparable(toBigDecimal, attributeSchema.getIndexedDecimalPlaces(), Long.MAX_VALUE);
			} else {
				comparableFrom = getLongComparable(toTargetType(from, Long.class), Long.MIN_VALUE);
				comparableTo = getLongComparable(toTargetType(to, Long.class), Long.MAX_VALUE);
			}
			requestedPredicate = createBigDecimalPredicate(fromBigDecimal, toBigDecimal);
		} else if (DateTimeRange.class.isAssignableFrom(attributeType)) {
			comparableFrom = getOffsetDateTimeComparable(toTargetType(from, OffsetDateTime.class), Long.MIN_VALUE);
			comparableTo = getOffsetDateTimeComparable(toTargetType(to, OffsetDateTime.class), Long.MAX_VALUE);
			requestedPredicate = null;
		} else {
			throw new GenericEvitaInternalError("Unexpected Range type!");
		}
		final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
		return new AttributeFormula(
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
	 * Creates an alternative bitmap filter for a specified attribute within a range defined by 'from' and 'to'.
	 *
	 * @param filterByVisitor The visitor used to apply filter indexes.
	 * @param attributeName The name of the attribute to filter.
	 * @param from The starting value of the range.
	 * @param to The ending value of the range.
	 * @return The constructed AttributeBitmapFilter.
	 */
	@Nonnull
	private static AttributeBitmapFilter createAlternativeBitmapFilter(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull String attributeName,
		@Nullable Serializable from,
		@Nullable Serializable to
	) {
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			attributeContent(attributeName),
			processingScope::getAttributeSchema,
			(entityContract, theAttributeName) -> processingScope.getAttributeValueStream(entityContract, theAttributeName, filterByVisitor.getLocale()),
			attributeSchema -> {
				final Class<? extends Serializable> attributeType = attributeSchema.getPlainType();
				final ReferenceSchemaContract referenceSchema = processingScope.getReferenceSchema();
				//noinspection rawtypes
				final Comparator comparator = FilterIndex.getComparator(
					AttributeIndex.createAttributeKey(
						referenceSchema,
						attributeSchema,
						filterByVisitor.getLocale()
					),
					attributeType
				);
				final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(attributeType);
				final Serializable comparableFrom = normalizer.apply(from);
				final Serializable comparableTo = normalizer.apply(to);

				if (Range.class.isAssignableFrom(attributeType)) {
					if (NumberRange.class.isAssignableFrom(attributeType)) {
						return getNumberRangePredicate((Number) comparableFrom, (Number) comparableTo);
					} else if (DateTimeRange.class.isAssignableFrom(attributeType)) {
						return getDateTimePredicate((OffsetDateTime) comparableFrom, (OffsetDateTime) comparableTo);
					} else {
						throw new GenericEvitaInternalError("Unexpected type!");
					}
				} else {
					return getComparablePredicate(normalizer, comparator, comparableFrom, comparableTo);
				}
			},
			AttributeTrait.FILTERABLE
		);
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
					final BigDecimal fromBigDecimal = EvitaDataTypes.toTargetType(from, BigDecimal.class);
					final BigDecimal toBigDecimal = EvitaDataTypes.toTargetType(to, BigDecimal.class);
					requestedPredicate = createBigDecimalPredicate(fromBigDecimal, toBigDecimal);
				} else {
					requestedPredicate = null;
				}

				final ProcessingScope<? extends Index<?>> processingScope = filterByVisitor.getProcessingScope();
				final Serializable convertedFrom = EvitaDataTypes.toTargetType(from, attributeType);
				final Serializable convertedTo = EvitaDataTypes.toTargetType(to, attributeType);
				filteringFormula = new AttributeFormula(
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
					createAlternativeBitmapFilter(filterByVisitor, attributeName, from, to)
				);
			} else {
				return filteringFormula;
			}
		} else {
			return new EntityFilteringFormula(
				"attribute between filter",
				createAlternativeBitmapFilter(filterByVisitor, attributeName, from, to)
			);
		}
	}

}
