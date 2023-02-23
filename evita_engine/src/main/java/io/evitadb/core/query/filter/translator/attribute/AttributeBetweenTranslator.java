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

import io.evitadb.api.query.filter.AttributeBetween;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
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
import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeBetween} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeBetweenTranslator implements FilteringConstraintTranslator<AttributeBetween> {

	@SuppressWarnings({"rawtypes", "unchecked"})
	@Override
	@Nonnull
	public Formula translate(@Nonnull AttributeBetween filterConstraint, @Nonnull FilterByVisitor filterByVisitor) {
		final String attributeName = filterConstraint.getAttributeName();
		final Serializable from = filterConstraint.getFrom();
		final Serializable to = filterConstraint.getTo();

		if (filterByVisitor.isEntityTypeKnown()) {
			final AttributeSchemaContract attributeDefinition = filterByVisitor.getAttributeSchema(attributeName);
			final Class<? extends Serializable> attributeType = attributeDefinition.getPlainType();
			final AttributeFormula filteringFormula;
			if (Range.class.isAssignableFrom(attributeType)) {
				final long comparableFrom;
				final long comparableTo;
				if (NumberRange.class.isAssignableFrom(attributeType)) {
					if (attributeDefinition.getIndexedDecimalPlaces() > 0) {
						comparableFrom = getBigDecimalComparable(from, attributeDefinition.getIndexedDecimalPlaces(), Long.MIN_VALUE);
						comparableTo = getBigDecimalComparable(to, attributeDefinition.getIndexedDecimalPlaces(), Long.MAX_VALUE);
					} else {
						comparableFrom = getLongComparable(from, Long.MIN_VALUE);
						comparableTo = getLongComparable(to, Long.MAX_VALUE);
					}
				} else if (DateTimeRange.class.isAssignableFrom(attributeType)) {
					comparableFrom = getOffsetDateTimeComparable(from, Long.MIN_VALUE);
					comparableTo = getOffsetDateTimeComparable(to, Long.MAX_VALUE);
				} else {
					throw new EvitaInternalError("Unexpected Range type!");
				}
				filteringFormula = new AttributeFormula(
					attributeName,
					filterByVisitor.applyOnFilterIndexes(
						attributeDefinition, index -> index.getRecordsOverlappingFormula(comparableFrom, comparableTo)
					)
				);
			} else {
				final Comparable comparableFrom = (Comparable) EvitaDataTypes.toTargetType(from, attributeType);
				final Comparable comparableTo = (Comparable) EvitaDataTypes.toTargetType(to, attributeType);
				filteringFormula = new AttributeFormula(
					attributeName,
					filterByVisitor.applyOnFilterIndexes(
						attributeDefinition,
						index -> {
							if (comparableFrom != null && comparableTo != null) {
								return index.getRecordsBetweenFormula(comparableFrom, comparableTo);
							} else if (comparableFrom == null) {
								return index.getRecordsLesserThanEqFormula(comparableTo);
							} else {
								return index.getRecordsGreaterThanEqFormula(comparableFrom);
							}
						}
					)
				);
			}
			if (filterByVisitor.isPrefetchPossible()) {
				return new SelectionFormula(
					filterByVisitor,
					filteringFormula,
					createAlternativeBitmapFilter(filterByVisitor, attributeName, from, to)
				);
			} else {
				return filteringFormula;
			}
		} else {
			return new EntityFilteringFormula(
				"attribute between filter",
				filterByVisitor,
				createAlternativeBitmapFilter(filterByVisitor, attributeName, from, to)
			);
		}
	}

	@SuppressWarnings("rawtypes")
	@Nonnull
	private static AttributeBitmapFilter createAlternativeBitmapFilter(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull String attributeName,
		@Nonnull Serializable from,
		@Nonnull Serializable to
	) {
		final ProcessingScope processingScope = filterByVisitor.getProcessingScope();
		return new AttributeBitmapFilter(
			attributeName,
			processingScope.getRequirements(),
			processingScope::getAttributeSchema,
			(entityContract, theAttributeName) -> processingScope.getAttributeValueStream(entityContract, theAttributeName, filterByVisitor.getLocale()),
			attributeSchema -> {
				final Comparable comparableFrom = (Comparable) EvitaDataTypes.toTargetType(from, attributeSchema.getPlainType());
				final Comparable comparableTo = (Comparable) EvitaDataTypes.toTargetType(to, attributeSchema.getPlainType());
				if (Range.class.isAssignableFrom(attributeSchema.getPlainType())) {
					if (NumberRange.class.isAssignableFrom(attributeSchema.getPlainType())) {
						return getNumberRangePredicate((Number) comparableFrom, (Number) comparableTo);
					} else if (DateTimeRange.class.isAssignableFrom(attributeSchema.getPlainType())) {
						return getDateTimePredicate((OffsetDateTime) comparableFrom, (OffsetDateTime) comparableTo);
					} else {
						throw new EvitaInternalError("Unexpected type!");
					}
				} else {
					return getComparablePredicate(comparableFrom, comparableTo);
				}
			}
		);
	}

	@Nonnull
	private static Long getOffsetDateTimeComparable(@Nullable Serializable from, long minValue) {
		final OffsetDateTime comparableValue = EvitaDataTypes.toTargetType(from, OffsetDateTime.class);
		return ofNullable(comparableValue).map(DateTimeRange::toComparableLong).orElse(minValue);
	}

	@Nonnull
	private static Long getLongComparable(@Nullable Serializable from, long minValue) {
		final Long comparableValue = EvitaDataTypes.toTargetType(from, Long.class);
		return ofNullable(comparableValue).orElse(minValue);
	}

	@Nonnull
	private static Long getBigDecimalComparable(@Nullable Serializable value, int indexedDecimalPlaces, long defaultValue) {
		final BigDecimal comparableValue = EvitaDataTypes.toTargetType(value, BigDecimal.class);
		return ofNullable(comparableValue)
			.map(it -> BigDecimalNumberRange.toComparableLong(it, indexedDecimalPlaces))
			.orElse(defaultValue);
	}

	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> getDateTimePredicate(@Nullable OffsetDateTime comparableValueFrom, @Nullable OffsetDateTime comparableValueTo) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Serializable theValue = attr.get().getValue();
					if (theValue == null) {
						return false;
					} else if (comparableValueFrom != null && comparableValueTo != null) {
						return ((DateTimeRange) theValue).overlaps(DateTimeRange.between(comparableValueFrom, comparableValueTo));
					} else if (comparableValueFrom != null) {
						return ((DateTimeRange) theValue).overlaps(DateTimeRange.since(comparableValueFrom));
					} else if (comparableValueTo != null) {
						return ((DateTimeRange) theValue).overlaps(DateTimeRange.until(comparableValueTo));
					} else {
						throw new EvitaInternalError("Between query can never be created with both bounds null!");
					}
				}
			}
		);
	}

	@SuppressWarnings("ConstantConditions")
	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> getNumberRangePredicate(@Nullable Number comparableValueFrom, @Nullable Number comparableValueTo) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Serializable theValue = attr.get().getValue();
					if (theValue == null) {
						return false;
					} else if (comparableValueFrom != null && comparableValueTo != null) {
						if (comparableValueFrom instanceof BigDecimal || comparableValueTo instanceof BigDecimal) {
							return ((BigDecimalNumberRange) theValue).overlaps(BigDecimalNumberRange.between((BigDecimal) comparableValueFrom, (BigDecimal) comparableValueTo));
						} else if (comparableValueFrom instanceof Long || comparableValueTo instanceof Long) {
							return ((LongNumberRange) theValue).overlaps(LongNumberRange.between((Long) comparableValueFrom, (Long) comparableValueTo));
						} else if (comparableValueFrom instanceof Integer || comparableValueTo instanceof Integer) {
							return ((IntegerNumberRange) theValue).overlaps(IntegerNumberRange.between((Integer) comparableValueFrom, (Integer) comparableValueTo));
						} else if (comparableValueFrom instanceof Short || comparableValueTo instanceof Short) {
							return ((ShortNumberRange) theValue).overlaps(ShortNumberRange.between((Short) comparableValueFrom, (Short) comparableValueTo));
						} else if (comparableValueFrom instanceof Byte || comparableValueTo instanceof Byte) {
							return ((ByteNumberRange) theValue).overlaps(ByteNumberRange.between((Byte) comparableValueFrom, (Byte) comparableValueTo));
						} else {
							throw new EvitaInternalError("Unexpected input type: " + comparableValueFrom + ", " + comparableValueTo);
						}
					} else if (comparableValueFrom != null) {
						if (comparableValueFrom instanceof BigDecimal) {
							return ((BigDecimalNumberRange) theValue).overlaps(BigDecimalNumberRange.from((BigDecimal) comparableValueFrom));
						} else if (comparableValueFrom instanceof Long) {
							return ((LongNumberRange) theValue).overlaps(LongNumberRange.from((Long) comparableValueFrom));
						} else if (comparableValueFrom instanceof Integer) {
							return ((IntegerNumberRange) theValue).overlaps(IntegerNumberRange.from((Integer) comparableValueFrom));
						} else if (comparableValueFrom instanceof Short) {
							return ((ShortNumberRange) theValue).overlaps(ShortNumberRange.from((Short) comparableValueFrom));
						} else if (comparableValueFrom instanceof Byte) {
							return ((ByteNumberRange) theValue).overlaps(ByteNumberRange.from((Byte) comparableValueFrom));
						} else {
							throw new EvitaInternalError("Unexpected input type: " + comparableValueFrom);
						}
					} else if (comparableValueTo != null) {
						if (comparableValueTo instanceof BigDecimal) {
							return ((BigDecimalNumberRange) theValue).overlaps(BigDecimalNumberRange.to((BigDecimal) comparableValueTo));
						} else if (comparableValueTo instanceof Long) {
							return ((LongNumberRange) theValue).overlaps(LongNumberRange.to((Long) comparableValueTo));
						} else if (comparableValueTo instanceof Integer) {
							return ((IntegerNumberRange) theValue).overlaps(IntegerNumberRange.to((Integer) comparableValueTo));
						} else if (comparableValueTo instanceof Short) {
							return ((ShortNumberRange) theValue).overlaps(ShortNumberRange.to((Short) comparableValueTo));
						} else if (comparableValueTo instanceof Byte) {
							return ((ByteNumberRange) theValue).overlaps(ByteNumberRange.to((Byte) comparableValueTo));
						} else {
							throw new EvitaInternalError("Unexpected input type: " + comparableValueTo);
						}
					} else {
						throw new EvitaInternalError("Between query can never be created with both bounds null!");
					}
				}
			}
		);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	public static Predicate<Stream<Optional<AttributeValue>>> getComparablePredicate(@Nullable Comparable<?> comparableFrom, @Nullable Comparable<?> comparableTo) {
		return attrStream -> attrStream.anyMatch(
			attr -> {
				if (attr.isEmpty()) {
					return false;
				} else {
					final Serializable theValue = attr.get().getValue();
					if (theValue == null) {
						return false;
					} else if (comparableFrom != null && comparableTo != null) {
						return ((Comparable) theValue).compareTo(comparableFrom) >= 0 && ((Comparable) theValue).compareTo(comparableTo) <= 0;
					} else if (comparableFrom != null) {
						return ((Comparable) theValue).compareTo(comparableFrom) >= 0;
					} else if (comparableTo != null) {
						return ((Comparable) theValue).compareTo(comparableTo) <= 0;
					} else {
						throw new EvitaInternalError("Between query can never be created with both bounds null!");
					}
				}
			}
		);
	}

}
