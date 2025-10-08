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
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.math.BigDecimal;
import java.math.RoundingMode;

import static java.util.Optional.ofNullable;

/**
 * Specialized {@link NumberRange} for {@link BigDecimal}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public final class BigDecimalNumberRange extends NumberRange<BigDecimal> {
	public static final BigDecimalNumberRange INFINITE = new BigDecimalNumberRange();
	@Serial private static final long serialVersionUID = -7031165388993390172L;

	/**
	 * Method creates new BigDecimalRange instance.
	 */
	@Nonnull
	public static BigDecimalNumberRange between(@Nonnull BigDecimal from, @Nonnull BigDecimal to) {
		return new BigDecimalNumberRange(from, to);
	}

	/**
	 * Method creates new BigDecimalRange instance when only lower range bound is available.
	 */
	@Nonnull
	public static BigDecimalNumberRange from(@Nonnull BigDecimal from) {
		return new BigDecimalNumberRange(from, null);
	}

	/**
	 * Method creates new BigDecimalRange instance when only upper range bound is available.
	 */
	@Nonnull
	public static BigDecimalNumberRange to(@Nonnull BigDecimal to) {
		return new BigDecimalNumberRange(null, to);
	}

	/**
	 * Method creates new BigDecimalRange instance.
	 *
	 * @param retainedDecimalPlaces defines how many fractional places will be kept for comparison
	 */
	@Nonnull
	public static BigDecimalNumberRange between(@Nonnull BigDecimal from, @Nonnull BigDecimal to, int retainedDecimalPlaces) {
		return new BigDecimalNumberRange(from, to, retainedDecimalPlaces);
	}

	/**
	 * Method creates new BigDecimalRange instance when only lower range bound is available.
	 *
	 * @param retainedDecimalPlaces defines how many fractional places will be kept for comparison
	 */
	@Nonnull
	public static BigDecimalNumberRange from(@Nonnull BigDecimal from, int retainedDecimalPlaces) {
		return new BigDecimalNumberRange(from, null, retainedDecimalPlaces);
	}

	/**
	 * Method creates new BigDecimalRange instance when only upper range bound is available.
	 *
	 * @param retainedDecimalPlaces defines how many fractional places will be kept for comparison
	 */
	@Nonnull
	public static BigDecimalNumberRange to(@Nonnull BigDecimal to, int retainedDecimalPlaces) {
		return new BigDecimalNumberRange(null, to, retainedDecimalPlaces);
	}

	/**
	 * Converts unknown value to a long that is comparable with {@link NumberRange} using retained decimal places.
	 */
	@Nonnull
	public static Long toComparableLong(@Nonnull BigDecimal theValue, int retainedDecimalPlaces) {
		return theValue.setScale(retainedDecimalPlaces, RoundingMode.HALF_UP)
			.scaleByPowerOfTen(retainedDecimalPlaces)
			.longValueExact();
	}

	/**
	 * Converts unknown value to a long that is comparable with {@link NumberRange} using retained decimal places.
	 */
	@Nonnull
	public static Long toComparableLong(@Nullable BigDecimal theValue, int retainedDecimalPlaces, long nullValue) {
		return ofNullable(theValue)
			.map(it -> toComparableLong(theValue, retainedDecimalPlaces))
			.orElse(nullValue);
	}

	/**
	 * Parses string to {@link NumberRange} or throws an exception. String must conform to the format produced
	 * by {@link NumberRange#toString()} method. Parsed Number range always uses {@link BigDecimal} for numbers.
	 */
	@Nonnull
	public static BigDecimalNumberRange fromString(@Nonnull String string) throws DataTypeParseException {
		Assert.isTrue(
			string.startsWith(OPEN_CHAR) && string.endsWith(CLOSE_CHAR),
			() -> new DataTypeParseException("NumberRange must start with " + OPEN_CHAR + " and end with " + CLOSE_CHAR + "!")
		);
		final int delimiter = string.indexOf(INTERVAL_JOIN, 1);
		Assert.isTrue(
			delimiter > -1,
			() -> new DataTypeParseException("NumberRange must contain " + INTERVAL_JOIN + " to separate from and to dates!")
		);
		final BigDecimal from = delimiter == 1 ? null : parseBigDecimal(string.substring(1, delimiter));
		final BigDecimal to = delimiter == string.length() - 2 ? null : parseBigDecimal(string.substring(delimiter + 1, string.length() - 1));
		if (from == null && to != null) {
			return to(to);
		} else if (from != null && to == null) {
			return from(from);
		} else if (from != null) {
			return between(from, to);
		} else {
			throw new DataTypeParseException("Range has no sense with both limits open to infinity!");
		}
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of original Entity from different
	 * package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static BigDecimalNumberRange _internalBuild(@Nullable BigDecimal from, @Nullable BigDecimal to, @Nullable Integer retainedDecimalPlaces, long fromToCompare, long toToCompare) {
		return new BigDecimalNumberRange(from, to, retainedDecimalPlaces, fromToCompare, toToCompare);
	}

	/**
	 * Creates a union of two BigDecimalNumberRanges. If either range is infinite, the result is an infinite range.
	 * If the ranges overlap, a new BigDecimalNumberRange is created from the common bounds;
	 * otherwise, an infinite range is returned as a simplification for non-overlapping ranges.
	 *
	 * @param rangeA The first BigDecimalNumberRange.
	 * @param rangeB The second BigDecimalNumberRange.
	 * @return A new BigDecimalNumberRange representing the union of rangeA and rangeB. If the ranges do not overlap,
	 *         the result is an infinite range.
	 */
	@Nonnull
	public static BigDecimalNumberRange union(@Nonnull BigDecimalNumberRange rangeA, @Nonnull BigDecimalNumberRange rangeB) {
		if (rangeA == INFINITE || rangeB == INFINITE) {
			return INFINITE;
		} else {
			final BigDecimal from = rangeA.from == null ? null : (rangeB.from == null ? null : rangeA.from.min(rangeB.from));
			final BigDecimal to = rangeA.to == null ? null : (rangeB.to == null ? null : rangeA.to.max(rangeB.to));
			final boolean leftLesserThanRight = from != null && to != null && from.compareTo(to) > 0;
			final BigDecimal recalculatedFrom = leftLesserThanRight ? to : from;
			final BigDecimal recalculatedTo = leftLesserThanRight ? from : to;
			if (recalculatedFrom == null && recalculatedTo == null) {
				return INFINITE;
			} else {
				return new BigDecimalNumberRange(
					recalculatedFrom,
					recalculatedTo,
					Math.max(
						rangeA.retainedDecimalPlaces == null ? resolveDefaultRetainedDecimalPlaces(rangeA.from, rangeA.to) : rangeA.retainedDecimalPlaces,
						rangeB.retainedDecimalPlaces == null ? resolveDefaultRetainedDecimalPlaces(rangeB.from, rangeB.to) : rangeB.retainedDecimalPlaces
					)
				);
			}
		}
	}

	/**
	 * Computes the intersection of two BigDecimalNumberRanges. If both ranges are infinite, the result is also infinite.
	 * In case of no intersection, the result is infinite range (we have no representation for empty range).
	 *
	 * @param rangeA The first BigDecimalNumberRange.
	 * @param rangeB The second BigDecimalNumberRange.
	 * @return A new BigDecimalNumberRange representing the intersection of rangeA and rangeB.
	 */
	@Nonnull
	public static BigDecimalNumberRange intersect(@Nonnull BigDecimalNumberRange rangeA, @Nonnull BigDecimalNumberRange rangeB) {
		if (rangeA == INFINITE && rangeB == INFINITE) {
			return INFINITE;
		} else {
			final BigDecimal from = rangeA.from == null ? rangeB.from : (rangeB.from == null ? rangeA.from : rangeA.from.max(rangeB.from));
			final BigDecimal to = rangeA.to == null ? rangeB.to : (rangeB.to == null ? rangeA.to : rangeA.to.min(rangeB.to));
			if (rangeA.overlaps(rangeB) && (from != null || to != null)) {
				return new BigDecimalNumberRange(
					from, to,
					Math.max(
						rangeA.retainedDecimalPlaces == null ? resolveDefaultRetainedDecimalPlaces(rangeA.from, rangeA.to) : rangeA.retainedDecimalPlaces,
						rangeB.retainedDecimalPlaces == null ? resolveDefaultRetainedDecimalPlaces(rangeB.from, rangeB.to) : rangeB.retainedDecimalPlaces
					)
				);
			} else {
				// simplification - the result is empty range, but we have no representation for it
				return INFINITE;
			}
		}
	}

	@Override
	public boolean isWithin(@Nonnull BigDecimal valueToCheck) {
		Assert.notNull(valueToCheck, "Cannot resolve within range with NULL value!");
		final long valueToCompare = toComparableLong(valueToCheck, ofNullable(this.retainedDecimalPlaces).orElse(0), 0L);
		return this.fromToCompare <= valueToCompare && valueToCompare <= this.toToCompare;
	}

	@Nonnull
	private static BigDecimal parseBigDecimal(@Nonnull String toBeNumber) {
		try {
			return new BigDecimal(toBeNumber);
		} catch (NumberFormatException ex) {
			throw new DataTypeParseException("String " + toBeNumber + " is not a number!");
		}
	}

	private BigDecimalNumberRange() {
		super(null, null, 0, Long.MIN_VALUE, Long.MAX_VALUE);
	}

	private BigDecimalNumberRange(@Nullable BigDecimal from, @Nullable BigDecimal to, @Nullable Integer retainedDecimalPlaces, long fromToCompare, long toToCompare) {
		super(from, to, retainedDecimalPlaces, fromToCompare, toToCompare);
	}

	private BigDecimalNumberRange(@Nullable BigDecimal from, @Nullable BigDecimal to) {
		super(
			from, to, null,
			toComparableLong(from, resolveDefaultRetainedDecimalPlaces(from, to), Long.MIN_VALUE),
			toComparableLong(to, resolveDefaultRetainedDecimalPlaces(from, to), Long.MAX_VALUE)
		);
		assertEitherBoundaryNotNull(from, to);
		assertFromLesserThanTo(from, to);
	}

	private BigDecimalNumberRange(@Nullable BigDecimal from, @Nullable BigDecimal to, int retainedDecimalPlaces) {
		super(
			from, to, retainedDecimalPlaces,
			toComparableLong(from, retainedDecimalPlaces, Long.MIN_VALUE),
			toComparableLong(to, retainedDecimalPlaces, Long.MAX_VALUE)
		);
		assertEitherBoundaryNotNull(from, to);
		assertFromLesserThanTo(from, to);
	}

	@Nonnull
	@Override
	public Range<BigDecimal> cloneWithDifferentBounds(@Nullable BigDecimal from, @Nullable BigDecimal to) {
		Assert.isTrue(from != null || to != null, "At least one bound must be non-null!");
		if (this.retainedDecimalPlaces != null) {
			return new BigDecimalNumberRange(from, to, this.retainedDecimalPlaces);
		} else {
			return new BigDecimalNumberRange(from, to);
		}
	}

	/**
	 * Return range that is complement to this range. If this range is infinite, the result is also infinite.
	 * If the range is bounded from both sides, the result is infinite (since we'd have to return multiple ranges).
	 * If the range is bounded from one side, the result is range from the other side to infinity.
	 *
	 * @param precision The number of decimal places to consider for the inverse operation.
	 * @return A new BigDecimalNumberRange representing the inverse of this range.
	 */
	@Nonnull
	public BigDecimalNumberRange inverse(int precision) {
		if (this == BigDecimalNumberRange.INFINITE || (this.from != null && this.to != null) || (this.from == null && this.to == null)) {
			return BigDecimalNumberRange.INFINITE;
		} else if (this.to != null) {
			return BigDecimalNumberRange.from(this.to.add(BigDecimal.ONE.movePointLeft(precision)));
		} else {
			return BigDecimalNumberRange.to(this.from.subtract(BigDecimal.ONE.movePointLeft(precision)));
		}
	}

	@Override
	protected long toComparableLong(@Nullable BigDecimal valueToCheck, long defaultValue) {
		return toComparableLong(valueToCheck, ofNullable(this.retainedDecimalPlaces).orElse(0), defaultValue);
	}

	@Override
	protected Class<BigDecimal> getSupportedType() {
		return BigDecimal.class;
	}

	/**
	 * If no explicit retained places were passed from client, places are resolved from actual numbers.
	 */
	private static int resolveDefaultRetainedDecimalPlaces(@Nullable BigDecimal from, @Nullable BigDecimal to) {
		if (from == null && to == null) {
			return 0;
		}
		if (from == null) {
			return to.scale();
		} else if (to == null) {
			return from.scale();
		} else {
			return Math.max(from.scale(), to.scale());
		}
	}
}
