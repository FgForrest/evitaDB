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

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.EqualsAndHashCode.CacheStrategy;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Optional.ofNullable;

/**
 * Range type that envelopes {@link Number} types.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(of = {"fromToCompare", "toToCompare"}, cacheStrategy = CacheStrategy.LAZY)
public abstract sealed class NumberRange<T extends Number> implements Range<T>, Serializable, Comparable<NumberRange<T>>
	permits BigDecimalNumberRange, LongNumberRange, IntegerNumberRange, ShortNumberRange, ByteNumberRange {
	@Serial private static final long serialVersionUID = 7690351814641934282L;
	private static final Pattern SIMPLE_NUMBER_PARSE_PATTERN = Pattern.compile("([\\d\\\\.]+?)");
	private static final Pattern PARSE_PATTERN = Pattern.compile("^" + Pattern.quote(OPEN_CHAR) + "([\\d\\\\.]+?)?\\s*" + Pattern.quote(INTERVAL_JOIN) + "\\s*([\\d\\\\.]+?)?" + Pattern.quote(CLOSE_CHAR) + "$");
	public static final Function<String, String[]> PARSE_FCT = string -> {
		final Matcher matcher = PARSE_PATTERN.matcher(string);
		if (matcher.matches()) {
			return new String[]{matcher.group(1), matcher.group(2)};
		} else {
			final Matcher simpleNumberMatcher = SIMPLE_NUMBER_PARSE_PATTERN.matcher(string);
			return simpleNumberMatcher.matches() ? new String[] {simpleNumberMatcher.group(1), simpleNumberMatcher.group(1)} : null;
		}
	};

	@Nullable protected final T from;
	protected final long fromToCompare;
	@Nullable @Getter protected final Integer retainedDecimalPlaces;
	@Nullable protected final T to;
	protected final long toToCompare;

	protected NumberRange(@Nullable T from, @Nullable T to, @Nullable Integer retainedDecimalPlaces, long fromToCompare, long toToCompare) {
		this.from = from;
		this.to = to;
		this.retainedDecimalPlaces = retainedDecimalPlaces;
		this.fromToCompare = fromToCompare;
		this.toToCompare = toToCompare;
	}

	@Override
	public long getFrom() {
		return this.fromToCompare;
	}

	@Override
	public long getTo() {
		return this.toToCompare;
	}

	@Nullable
	@Override
	public T getPreciseFrom() {
		return this.from;
	}

	@Nullable
	@Override
	public T getPreciseTo() {
		return this.to;
	}

	/**
	 * Returns TRUE when value to check is withing the current number range (inclusive).
	 */
	@Override
	public boolean isWithin(@Nonnull T valueToCheck) {
		Assert.notNull(valueToCheck, "Cannot resolve within range with NULL value!");
		final long valueToCompare = toComparableLong(EvitaDataTypes.toTargetType(valueToCheck, getSupportedType()), 0L);
		return this.fromToCompare <= valueToCompare && valueToCompare <= this.toToCompare;
	}

	@Override
	public int compareTo(@Nonnull NumberRange o) {
		final int leftBoundCompare = Long.compare(getFrom(), o.getFrom());
		final int rightBoundCompare = Long.compare(getTo(), o.getTo());
		if (leftBoundCompare != 0) {
			return leftBoundCompare;
		} else {
			return rightBoundCompare;
		}
	}

	@Nonnull
	@Override
	public String toString() {
		return OPEN_CHAR + ofNullable(this.from).map(Object::toString).orElse("") +
			INTERVAL_JOIN + ofNullable(this.to).map(Object::toString).orElse("") + CLOSE_CHAR;
	}

	/**
	 * Converts the original number to a long.
	 */
	protected abstract long toComparableLong(@Nullable T valueToCheck, long defaultValue);

	/**
	 * Returns the type supported by the concrete extension of this class.
	 */
	protected abstract Class<T> getSupportedType();

	protected void assertNotFloatingPointType(@Nullable Number from, @Nonnull final String argName) {
		if (from instanceof Float || from instanceof Double) {
			throw new EvitaInvalidUsageException("For " + argName + " number with floating point use BigDecimal that keeps the precision!");
		}
	}

	protected void assertEitherBoundaryNotNull(@Nullable Number from, @Nullable Number to) {
		if (from == null && to == null) {
			throw new EvitaInvalidUsageException("From and to cannot be both null at the same time in NumberRange type!");
		}
	}

	protected void assertFromLesserThanTo(@Nullable Number from, @Nullable Number to) {
		//noinspection unchecked,rawtypes
		if (!(from == null || to == null || ((Comparable) from).compareTo(to) <= 0)) {
			throw new EvitaInvalidUsageException("From must be before or equals to to!");
		}
	}

}
