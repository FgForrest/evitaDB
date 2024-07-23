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

	@Override
	public boolean isWithin(@Nonnull BigDecimal valueToCheck) {
		Assert.notNull(valueToCheck, "Cannot resolve within range with NULL value!");
		final long valueToCompare = toComparableLong(valueToCheck, ofNullable(retainedDecimalPlaces).orElse(0), 0L);
		return fromToCompare <= valueToCompare && valueToCompare <= toToCompare;
	}

	@Nonnull
	private static BigDecimal parseBigDecimal(@Nonnull String toBeNumber) {
		try {
			return new BigDecimal(toBeNumber);
		} catch (NumberFormatException ex) {
			throw new DataTypeParseException("String " + toBeNumber + " is not a number!");
		}
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

	@Override
	protected long toComparableLong(@Nullable BigDecimal valueToCheck, long defaultValue) {
		return toComparableLong(valueToCheck, ofNullable(retainedDecimalPlaces).orElse(0), defaultValue);
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
