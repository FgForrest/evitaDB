/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.query.filter.translator.attribute.alternative;

import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Range;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;

import static io.evitadb.utils.CollectionUtils.createLinkedHashSet;

/**
 * Tells whether a single value of an attribute satisfies one attribute constraint - the per-value test of the
 * constraint, which answers it from the value itself instead of probing an index of the attribute.
 *
 * The verdict is the one the attribute indexes give for the same value, because the matcher is built on the
 * definitions the indexes are built on: the constraint value is converted by {@link EvitaDataTypes#toTargetType} and
 * both raw String sides use the injected effective key function once. Other types use
 * {@link FilterIndex#getNormalizer(Class, int)}, and comparison uses
 * {@link FilterIndex#getComparator(AttributeIndexKey, Class)}. Two operators are not a comparison at all and reproduce
 * the operator of the {@link io.evitadb.index.range.RangeIndex} instead - the overlap of an `attributeBetween` over a
 * range-typed attribute and the envelope of an `attributeInRange` - over the thresholds the attribute translators
 * encode and hand in already encoded.
 *
 * One deliberate difference: a `BigDecimal` is compared as {@link NumberUtils#scaleToInteger(BigDecimal, int)} - the
 * value the index encodes into an `int`, kept as a `BigDecimal`. It orders and equals exactly like the `int` wherever
 * that fits, and does not overflow where it does not, so no stored value makes the matcher throw.
 *
 * An array value matches when any of its elements does, as the filter index keeps one entry per element; an empty
 * array and a missing value match nothing.
 *
 * Instances are built once per constraint, so the conversion, the normalization of the constraint value and the
 * construction of the comparator are paid once, not per value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Immutable
public sealed interface AttributeValueMatcher {

	/**
	 * Tells whether the attribute value satisfies the constraint.
	 *
	 * @param value the value of the attribute, an array of values for an array attribute, NULL when there is none
	 * @return true when the value, or any element of an array value, satisfies the constraint
	 */
	default boolean matches(@Nullable Serializable value) {
		if (value instanceof Object[] elements) {
			for (Object element : elements) {
				if (element != null && matchesElement(element)) {
					return true;
				}
			}
			return false;
		}
		return value != null && matchesElement(value);
	}

	/**
	 * Tells whether a single, non-array value satisfies the constraint.
	 *
	 * @param value the value, or one element of an array value
	 * @return true when the value satisfies the constraint
	 */
	boolean matchesElement(@Nonnull Object value);

	/**
	 * Creates the matcher of a comparison - `attributeEquals`, `attributeLessThan`, `attributeLessThanEquals`,
	 * `attributeGreaterThan` or `attributeGreaterThanEquals`.
	 *
	 * @param attributeIndexKey    key of the filter index the attribute is read from, its locale selects the comparator
	 * @param plainType            the plain type of the attribute
	 * @param stringNormalizer     the effective String key function, bound to the lookup role and fold locale
	 * @param indexedDecimalPlaces the decimal places the attribute's numbers are indexed with
	 * @param value                the value of the constraint
	 * @param resultPredicate      the test of the result of comparing the attribute value with the constraint value
	 * @return the matcher
	 */
	@Nonnull
	static AttributeValueMatcher comparing(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> plainType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<String> stringNormalizer,
		@Nonnull Serializable value,
		@Nonnull IntPredicate resultPredicate
	) {
		final Function<Object, Serializable> normalizer = getNormalizer(plainType, indexedDecimalPlaces,
			stringNormalizer);
		return new Comparing(
			getComparator(attributeIndexKey, plainType),
			normalizer,
			normalizeConstraintValue(normalizer, plainType, value),
			resultPredicate
		);
	}

	/**
	 * Creates the matcher of an `attributeInSet` - a value equal to any of the constraint values.
	 *
	 * @param attributeIndexKey    key of the filter index the attribute is read from, its locale selects the comparator
	 * @param plainType            the plain type of the attribute
	 * @param stringNormalizer     the effective String key function, bound to the lookup role and fold locale
	 * @param indexedDecimalPlaces the decimal places the attribute's numbers are indexed with
	 * @param values               the values of the constraint
	 * @return the matcher
	 */
	@Nonnull
	static AttributeValueMatcher equalToAny(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> plainType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<String> stringNormalizer,
		@Nonnull Serializable[] values
	) {
		final Function<Object, Serializable> normalizer = getNormalizer(plainType, indexedDecimalPlaces,
			stringNormalizer);
		final Set<Serializable> normalizedValues = createLinkedHashSet(values.length);
		for (final Serializable value : values) {
			normalizedValues.add(normalizeConstraintValue(normalizer, plainType, value));
		}
		return new EqualToAny(getComparator(attributeIndexKey, plainType), normalizer,
			normalizedValues.toArray(Serializable[]::new));
	}

	/**
	 * Creates the matcher of an `attributeBetween` over an attribute that is not range-typed - the closed interval of
	 * the filter index. Like the filter index, it refuses an interval whose lower bound exceeds the upper one with the
	 * very message the index uses, but here regardless of whether any value is stored.
	 *
	 * @param attributeIndexKey    key of the filter index the attribute is read from, its locale selects the comparator
	 * @param plainType            the plain type of the attribute
	 * @param stringNormalizer     the effective String key function, bound to the lookup role and fold locale
	 * @param indexedDecimalPlaces the decimal places the attribute's numbers are indexed with
	 * @param from                 the lower bound, NULL when the interval is open from below
	 * @param to                   the upper bound, NULL when the interval is open from above
	 * @return the matcher
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the bounds of the interval are inverted
	 */
	@Nonnull
	static AttributeValueMatcher within(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> plainType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<String> stringNormalizer,
		@Nullable Serializable from,
		@Nullable Serializable to
	) {
		final Function<Object, Serializable> normalizer = getNormalizer(plainType, indexedDecimalPlaces,
			stringNormalizer);
		final Comparator<Object> comparator = getComparator(attributeIndexKey, plainType);
		final Serializable normalizedFrom = normalizer.apply(EvitaDataTypes.toTargetType(from, plainType));
		final Serializable normalizedTo = normalizer.apply(EvitaDataTypes.toTargetType(to, plainType));
		// the check `InvertedIndex#getRecordsInternal` makes, with its message - there it fires only where the
		// index holds a value, here it does not depend on data
		Assert.isTrue(
			normalizedFrom == null || normalizedTo == null || comparator.compare(normalizedFrom, normalizedTo) <= 0,
			() -> "From must be lower than to: " + normalizedFrom + " vs. " + normalizedTo
		);
		return new Within(comparator, normalizer, normalizedFrom, normalizedTo);
	}

	/**
	 * Creates the matcher of an `attributeBetween` over a range-typed attribute - the overlap the range index answers.
	 *
	 * @param plainType            the plain range type of the attribute
	 * @param indexedDecimalPlaces the decimal places the attribute's numbers are indexed with
	 * @param from                 the lower bound encoded as the range index threshold it is probed with
	 * @param to                   the upper bound encoded as the range index threshold it is probed with
	 * @return the matcher
	 */
	@Nonnull
	static AttributeValueMatcher overlapping(
		@Nonnull Class<? extends Serializable> plainType,
		int indexedDecimalPlaces,
		long from,
		long to
	) {
		return new Overlapping(FilterIndex.getNormalizer(plainType, indexedDecimalPlaces), from, to);
	}

	/**
	 * Creates the matcher of an `attributeInRange` - a range enveloping the point, both ends inclusive.
	 *
	 * @param plainType            the plain type of the range-typed attribute
	 * @param indexedDecimalPlaces the decimal places the attribute's numbers are indexed with
	 * @param point                the point encoded as the range index threshold it is probed with
	 * @return the matcher
	 */
	@Nonnull
	static AttributeValueMatcher enveloping(
		@Nonnull Class<? extends Serializable> plainType,
		int indexedDecimalPlaces,
		long point
	) {
		return new Enveloping(FilterIndex.getNormalizer(plainType, indexedDecimalPlaces), point);
	}

	/**
	 * Creates the matcher of a string search - `attributeStartsWith`, `attributeEndsWith` or `attributeContains`.
	 * Both raw sides use the bound String key function once, just as the filter index does.
	 * An empty normalized pattern matches every present String value.
	 *
	 * @param stringNormalizer the effective String key function
	 * @param textToSearch the text of the constraint
	 * @param predicate    the test of a value against the searched text, both already keys
	 * @return the matcher
	 */
	@Nonnull
	static AttributeValueMatcher searching(
		@Nonnull String textToSearch,
		@Nonnull UnaryOperator<String> stringNormalizer,
		@Nonnull BiPredicate<String, String> predicate
	) {
		return new Searching(stringNormalizer.apply(textToSearch), stringNormalizer, predicate);
	}

	/**
	 * Returns the normalizer the attribute values and the constraint values are compared in:
	 * {@link FilterIndex#getNormalizer(Class, int)}, except that a `BigDecimal` becomes
	 * {@link NumberUtils#scaleToInteger(BigDecimal, int)} instead of its `int` encoding (see the class
	 * documentation). Strings use the injected key function on raw values once. Anything else reaching the normalizer
	 * of a string attribute -
	 * an array passed as the value of a scalar comparison - is stringified by the index's normalizer, exactly as the
	 * index stringifies it, and so matches no row, as the index lookup finds nothing.
	 *
	 * @param plainType            the plain type of the attribute
	 * @param stringNormalizer     the effective String key function, bound to the lookup role and fold locale
	 * @param indexedDecimalPlaces the decimal places the attribute's numbers are indexed with
	 * @return the normalizer
	 */
	@Nonnull
	private static Function<Object, Serializable> getNormalizer(
		@Nonnull Class<? extends Serializable> plainType,
		int indexedDecimalPlaces,
		@Nonnull UnaryOperator<String> stringNormalizer
	) {
		if (BigDecimal.class.isAssignableFrom(plainType)) {
			return value -> value instanceof BigDecimal number ?
				NumberUtils.scaleToInteger(number, indexedDecimalPlaces) : (Serializable) value;
		} else if (String.class.isAssignableFrom(plainType)) {
			return value -> value == null ? null : stringNormalizer.apply(value.toString());
		} else {
			return FilterIndex.getNormalizer(plainType, indexedDecimalPlaces);
		}
	}

	/**
	 * Converts the value of the constraint to the plain type of the attribute and normalizes it.
	 *
	 * @param normalizer the normalizer of the attribute
	 * @param plainType  the plain type of the attribute
	 * @param value      the value of the constraint
	 * @return the normalized value
	 */
	@Nonnull
	private static Serializable normalizeConstraintValue(
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Class<? extends Serializable> plainType,
		@Nonnull Serializable value
	) {
		return normalizer.apply(Objects.requireNonNull(EvitaDataTypes.toTargetType(value, plainType)));
	}

	/**
	 * Returns the comparator the filter index of the attribute orders its values by.
	 *
	 * @param attributeIndexKey key of the filter index, whose locale selects the localized comparator of a string
	 * @param plainType         the plain type of the attribute
	 * @return the comparator
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	private static Comparator<Object> getComparator(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<? extends Serializable> plainType
	) {
		return (Comparator) FilterIndex.getComparator(attributeIndexKey, plainType);
	}

	/**
	 * Matches a value whose comparison with the normalized constraint value passes the test of the result.
	 *
	 * @param comparator      the comparator of the filter index
	 * @param normalizer      the normalizer of the attribute values
	 * @param value           the normalized constraint value
	 * @param resultPredicate the test of the comparison result
	 */
	record Comparing(
		@Nonnull Comparator<Object> comparator,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Serializable value,
		@Nonnull IntPredicate resultPredicate
	) implements AttributeValueMatcher {

		@Override
		public boolean matchesElement(@Nonnull Object value) {
			return this.resultPredicate.test(this.comparator.compare(this.normalizer.apply(value), this.value));
		}

	}

	/**
	 * Matches a value equal to any of the normalized constraint values.
	 *
	 * @param comparator the comparator of the filter index, whose zero result is the equality of its keys
	 * @param normalizer the normalizer of the attribute values
	 * @param values     the normalized constraint values
	 */
	record EqualToAny(
		@Nonnull Comparator<Object> comparator,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Serializable[] values
	) implements AttributeValueMatcher {

		@Override
		public boolean matchesElement(@Nonnull Object value) {
			final Serializable normalizedValue = this.normalizer.apply(value);
			for (Serializable candidate : this.values) {
				if (this.comparator.compare(normalizedValue, candidate) == 0) {
					return true;
				}
			}
			return false;
		}

	}

	/**
	 * Matches a value inside the closed interval of the normalized bounds.
	 *
	 * @param comparator the comparator of the filter index
	 * @param normalizer the normalizer of the attribute values
	 * @param from       the normalized lower bound, NULL when the interval is open from below
	 * @param to         the normalized upper bound, NULL when the interval is open from above
	 */
	record Within(
		@Nonnull Comparator<Object> comparator,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nullable Serializable from,
		@Nullable Serializable to
	) implements AttributeValueMatcher {

		@Override
		public boolean matchesElement(@Nonnull Object value) {
			final Serializable normalizedValue = this.normalizer.apply(value);
			return (this.from == null || this.comparator.compare(normalizedValue, this.from) >= 0) &&
				(this.to == null || this.comparator.compare(normalizedValue, this.to) <= 0);
		}

	}

	/**
	 * Matches a range having a point in common with the interval of the encoded bounds, as
	 * {@link io.evitadb.index.range.RangeIndex#getRecordsWithRangesOverlapping(long, long)} does: nothing for an
	 * inverted interval, otherwise a range starting no later than its end and ending no sooner than its start.
	 *
	 * @param normalizer the normalizer of the attribute values, which rescales a `BigDecimal` range to the indexed
	 *                   decimal places
	 * @param from       the encoded lower bound
	 * @param to         the encoded upper bound
	 */
	record Overlapping(
		@Nonnull Function<Object, Serializable> normalizer,
		long from,
		long to
	) implements AttributeValueMatcher {

		@Override
		public boolean matchesElement(@Nonnull Object value) {
			final Range<?> range = (Range<?>) this.normalizer.apply(value);
			return this.from <= this.to && range.getFrom() <= this.to && range.getTo() >= this.from;
		}

	}

	/**
	 * Matches a range enveloping the encoded point, both ends inclusive, as
	 * {@link io.evitadb.index.range.RangeIndex#getRecordsEnvelopingInclusive(long)} does.
	 *
	 * @param normalizer the normalizer of the attribute values, which rescales a `BigDecimal` range to the indexed
	 *                   decimal places
	 * @param point      the encoded point
	 */
	record Enveloping(
		@Nonnull Function<Object, Serializable> normalizer,
		long point
	) implements AttributeValueMatcher {

		@Override
		public boolean matchesElement(@Nonnull Object value) {
			final Range<?> range = (Range<?>) this.normalizer.apply(value);
			return range.getFrom() <= this.point && this.point <= range.getTo();
		}

	}

	/**
	 * Matches a string passing the search against the searched key, using the bound function once on raw values.
	 *
	 * @param stringNormalizer the effective String key function for raw attribute values
	 * @param textToSearch the searched key
	 * @param predicate    the test of a value against the searched text
	 */
	record Searching(
		@Nonnull String textToSearch,
		@Nonnull UnaryOperator<String> stringNormalizer,
		@Nonnull BiPredicate<String, String> predicate
	) implements AttributeValueMatcher {

		@Override
		public boolean matchesElement(@Nonnull Object value) {
			return this.predicate.test(this.stringNormalizer.apply((String) value), this.textToSearch);
		}

	}

}
