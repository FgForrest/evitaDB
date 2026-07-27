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

package io.evitadb.dataType;

import io.evitadb.dataType.exception.DataTypeParseException;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Interface can be used for any range with lower and upper bounds that are convertible to {@code long} value.
 * Implementations of this interface can be filtered by {@link io.evitadb.api.query.filter.InRange} constraint.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public sealed interface Range<T> extends Serializable permits DateTimeRange, NumberRange {
	String OPEN_CHAR = "[";
	String CLOSE_CHAR = "]";
	String INTERVAL_JOIN = ",";

	/**
	 * Method consolidates passed array of {@link Range} so that no two ranges overlap or extend one another.
	 * If these data are passed (same year, same time):
	 *
	 * - [1,15]
	 * - [15,20]
	 * - [10,50]
	 * - [60,80]
	 *
	 * Only these ranges will be returned:
	 *
	 * - [1,50]
	 * - [60,80]
	 *
	 * Because other ranges overlap one another.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	static <S, T extends Range<S>> T[] consolidateRange(@Nonnull T[] ranges) {
		if (ArrayUtils.isEmpty(ranges)) {
			return ranges;
		}
		final T[] clone = (T[]) Array.newInstance(ranges.getClass().getComponentType(), ranges.length);
		System.arraycopy(ranges, 0, clone, 0, ranges.length);
		Arrays.sort(clone);

		final List<T> result = new ArrayList<>(clone.length);
		T previousRange = null;
		for (final T currentRange : clone) {
			if (previousRange == null) {
				previousRange = currentRange;
			} else if (previousRange.overlaps(currentRange)) {
				previousRange = (T) previousRange.cloneWithDifferentBounds(
					previousRange.getFrom() <= currentRange.getFrom() ? previousRange.getPreciseFrom() : currentRange.getPreciseFrom(),
					previousRange.getTo() >= currentRange.getTo() ? previousRange.getPreciseTo() : currentRange.getPreciseTo()
				);
			} else {
				result.add(previousRange);
				previousRange = currentRange;
			}
		}
		result.add(previousRange);

		return result.toArray(length -> (T[]) Array.newInstance(ranges.getClass().getComponentType(), length));
	}

	/**
	 * Picks the correct one-sided / two-sided factory based on which of {@code from} / {@code to}
	 * is null. Common parsing helper that captures the open-ended bound semantics shared by every
	 * {@link Range} implementation:
	 *
	 * - {@code from == null, to != null} → {@code toOnlyFactory(to)} (open-from)
	 * - {@code from != null, to == null} → {@code fromOnlyFactory(from)} (open-to)
	 * - {@code from != null, to != null} → {@code betweenFactory(from, to)}
	 * - {@code from == null, to == null} → throws {@link DataTypeParseException} (a range with
	 *   both ends open to infinity is rejected by every implementation's parser).
	 *
	 * @param from               lower bound, may be {@code null} to denote "open from minus infinity"
	 * @param to                 upper bound, may be {@code null} to denote "open to plus infinity"
	 * @param toOnlyFactory      factory invoked when only {@code to} is provided (e.g.
	 *                           {@code IntegerNumberRange::to} or {@code DateTimeRange::until})
	 * @param fromOnlyFactory    factory invoked when only {@code from} is provided (e.g.
	 *                           {@code IntegerNumberRange::from} or {@code DateTimeRange::since})
	 * @param betweenFactory     factory invoked when both bounds are provided
	 * @param <T>                bound value type
	 * @param <R>                produced {@link Range} subtype
	 * @return the materialized range
	 * @throws DataTypeParseException when both {@code from} and {@code to} are {@code null}
	 */
	@Nonnull
	static <T, R> R materializeOpenEndedRange(
		@Nullable T from,
		@Nullable T to,
		@Nonnull Function<T, R> toOnlyFactory,
		@Nonnull Function<T, R> fromOnlyFactory,
		@Nonnull BiFunction<T, T, R> betweenFactory
	) {
		if (from == null && to != null) {
			return toOnlyFactory.apply(to);
		} else if (from != null && to == null) {
			return fromOnlyFactory.apply(from);
		} else if (from != null) {
			return betweenFactory.apply(from, to);
		} else {
			throw new DataTypeParseException("Range has no sense with both limits open to infinity!");
		}
	}

	/**
	 * Parses a range serialized in the canonical `[from,to]` form (as produced by the range `toString()` methods)
	 * into a concrete range instance. The textual structure is identical for every range type, so the boundary
	 * delimiting and open-ended detection are handled here; only the parsing of an individual boundary value and
	 * the target range factories differ per type and are supplied by the caller.
	 *
	 * An empty boundary (e.g. `[,to]` or `[from,]`) is treated as an open end and passed as `null` to
	 * {@link #materializeOpenEndedRange(Object, Object, Function, Function, BiFunction)}.
	 *
	 * @param string          the serialized range, must be wrapped in {@link #OPEN_CHAR} / {@link #CLOSE_CHAR} and
	 *                        contain a single {@link #INTERVAL_JOIN} boundary separator
	 * @param boundParser     parses a single boundary substring into a boundary value
	 * @param toOnlyFactory   builds a range open on the lower end (only the upper boundary is known)
	 * @param fromOnlyFactory builds a range open on the upper end (only the lower boundary is known)
	 * @param betweenFactory  builds a range bounded on both ends
	 * @param <T>             the boundary value type
	 * @param <R>             the concrete range type
	 * @return the parsed range instance
	 * @throws DataTypeParseException if the string is not wrapped correctly, or does not contain exactly one
	 *                               boundary separator
	 */
	@Nonnull
	static <T, R> R parseRange(
		@Nonnull String string,
		@Nonnull Function<String, T> boundParser,
		@Nonnull Function<T, R> toOnlyFactory,
		@Nonnull Function<T, R> fromOnlyFactory,
		@Nonnull BiFunction<T, T, R> betweenFactory
	) {
		Assert.isTrue(
			string.startsWith(OPEN_CHAR) && string.endsWith(CLOSE_CHAR),
			() -> new DataTypeParseException("Range must start with " + OPEN_CHAR + " and end with " + CLOSE_CHAR + "!")
		);
		final int delimiter = string.indexOf(INTERVAL_JOIN, 1);
		Assert.isTrue(
			delimiter > -1,
			() -> new DataTypeParseException("Range must contain " + INTERVAL_JOIN + " to separate its boundaries!")
		);
		Assert.isTrue(
			string.indexOf(INTERVAL_JOIN, delimiter + 1) == -1,
			() -> new DataTypeParseException("Range must contain only a single " + INTERVAL_JOIN + " to separate its boundaries!")
		);
		final T from = delimiter == 1 ? null : boundParser.apply(string.substring(1, delimiter));
		final T to = delimiter == string.length() - 2
			? null
			: boundParser.apply(string.substring(delimiter + 1, string.length() - 1));
		return materializeOpenEndedRange(from, to, toOnlyFactory, fromOnlyFactory, betweenFactory);
	}

	/**
	 * Lower bound of the range (inclusive). This value is used for range comparisons.
	 */
	long getFrom();

	/**
	 * Upper bound of the range (inclusive). This value is used for range comparisons.
	 */
	long getTo();

	/**
	 * Returns original from value used when range was created without any loss of precision.
	 */
	@Nullable
	T getPreciseFrom();

	/**
	 * Returns original to value used when range was created without any loss of precision.
	 */
	@Nullable
	T getPreciseTo();

	/**
	 * Returns TRUE when value to check is within the current range (inclusive).
	 */
	boolean isWithin(@Nonnull T valueToCheck);

	/**
	 * Creates new range of the same type with changed from and to boundaries.
	 * This method is used from {@link #consolidateRange(Range[])}
	 */
	@Nonnull
	Range<T> cloneWithDifferentBounds(@Nullable T from, @Nullable T to);

	/**
	 * Returns true if two ranges overlap one another (using inclusive boundaries).
	 *
	 * @throws IllegalArgumentException when ranges are not of the same type
	 */
	default boolean overlaps(@Nonnull Range<T> otherRange) {
		Assert.isTrue(
			this.getClass().equals(otherRange.getClass()),
			"Ranges `" + this.getClass().getName() + "` and `" + otherRange.getClass().getName() + "` are not comparable!"
		);
		return (getFrom() >= otherRange.getFrom() && getTo() <= otherRange.getTo()) ||
			(getFrom() <= otherRange.getFrom() && getTo() >= otherRange.getTo()) ||
			(getFrom() >= otherRange.getFrom() && getFrom() <= otherRange.getTo()) ||
			(getTo() <= otherRange.getTo() && getTo() >= otherRange.getFrom());
	}

}
