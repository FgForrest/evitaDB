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
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.EqualsAndHashCode.CacheStrategy;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Optional.ofNullable;

/**
 * Range type that envelopes {@link java.time.OffsetDateTime} types.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(of = {"fromToCompare", "toToCompare"}, cacheStrategy = CacheStrategy.LAZY)
public final class DateTimeRange implements Range<OffsetDateTime>, Serializable, Comparable<DateTimeRange> {
	@Serial private static final long serialVersionUID = 7690351814641934282L;
	private static final Pattern PARSE_PATTERN = Pattern.compile("^" + Pattern.quote(OPEN_CHAR) + "(\\S+?)?\\s*" + Pattern.quote(INTERVAL_JOIN) + "\\s*(\\S+?)?" + Pattern.quote(CLOSE_CHAR) + "$");
	public static final Function<String, String[]> PARSE_FCT = string -> {
		final Matcher matcher = PARSE_PATTERN.matcher(string);
		return matcher.matches() ? new String[]{matcher.group(1), matcher.group(2)} : null;
	};

	@Nullable private final OffsetDateTime from;
	@Nullable private final OffsetDateTime to;
	private final long fromToCompare;
	private final long toToCompare;

	private DateTimeRange(@Nullable OffsetDateTime from, @Nullable OffsetDateTime to) {
		assertEitherBoundaryNotNull(from, to);
		assertFromLesserThanTo(from, to);
		this.from = from;
		this.to = to;
		this.fromToCompare = toComparableLong(ofNullable(from).orElseGet(() -> LocalDateTime.MIN.atOffset(to.getOffset())));
		this.toToCompare = toComparableLong(ofNullable(to).orElseGet(() -> LocalDateTime.MAX.atOffset(from.getOffset())));
	}

	/**
	 * Converts unknown value to a long that is comparable with {@link DateTimeRange}.
	 */
	@Nonnull
	public static Long toComparableLong(@Nonnull OffsetDateTime theMoment) {
		return theMoment.toEpochSecond();
	}

	/**
	 * Parses string to {@link DateTimeRange} or throws an exception. String must conform to the format produced
	 * by {@link DateTimeRange#toString()} method.
	 */
	@Nonnull
	public static DateTimeRange fromString(@Nonnull String string) throws DataTypeParseException {
		Assert.isTrue(
			string.startsWith(OPEN_CHAR) && string.endsWith(CLOSE_CHAR),
			() -> new DataTypeParseException("DateTimeRange must start with " + OPEN_CHAR + " and end with " + CLOSE_CHAR + "!")
		);
		final int delimiter = string.indexOf(INTERVAL_JOIN, 1);
		Assert.isTrue(
			delimiter > -1,
			() -> new DataTypeParseException("DateTimeRange must contain " + INTERVAL_JOIN + " to separate since and until dates!")
		);
		final OffsetDateTime since = delimiter == 1 ? null : parseDateTime(string.substring(1, delimiter));
		final OffsetDateTime until = delimiter == string.length() - 2 ? null : parseDateTime(string.substring(delimiter + 1, string.length() - 1));
		if (since == null && until != null) {
			return until(until);
		} else if (since != null && until == null) {
			return since(since);
		} else if (since != null) {
			return between(since, until);
		} else {
			throw new DataTypeParseException("Range has no sense with both limits open to infinity!");
		}
	}

	private static OffsetDateTime parseDateTime(@Nonnull String substring) {
		try {
			return OffsetDateTime.from(DateTimeFormatter.ISO_DATE_TIME.parse(substring));
		} catch (DateTimeException ex) {
			throw new DataTypeParseException("Unable to parse date from string: " + substring);
		}
	}

	/**
	 * Method creates new DateTimeRange instance.
	 */
	@Nonnull
	public static DateTimeRange between(@Nonnull OffsetDateTime from, @Nonnull OffsetDateTime to) {
		return new DateTimeRange(from, to);
	}

	/**
	 * Method creates new DateTimeRange instance.
	 */
	@Nonnull
	public static DateTimeRange between(@Nonnull LocalDateTime from, @Nonnull LocalDateTime to, @Nonnull ZoneOffset zoneOffset) {
		return new DateTimeRange(from.atOffset(zoneOffset), to.atOffset(zoneOffset));
	}

	/**
	 * Method creates new DateTimeRange instance when only upper range bound is available.
	 */
	@Nonnull
	public static DateTimeRange until(@Nonnull OffsetDateTime to) {
		return new DateTimeRange(null, to);
	}

	/**
	 * Method creates new DateTimeRange instance when only upper range bound is available.
	 */
	@Nonnull
	public static DateTimeRange until(@Nonnull LocalDateTime to, @Nonnull ZoneOffset zoneOffset) {
		return new DateTimeRange(null, to.atOffset(zoneOffset));
	}

	/**
	 * Method creates new DateTimeRange instance when only lower range bound is available.
	 */
	@Nonnull
	public static DateTimeRange since(@Nonnull OffsetDateTime from) {
		return new DateTimeRange(from, null);
	}

	/**
	 * Method creates new DateTimeRange instance when only lower range bound is available.
	 */
	@Nonnull
	public static DateTimeRange since(@Nonnull LocalDateTime from, @Nonnull ZoneOffset zoneOffset) {
		return new DateTimeRange(from.atOffset(zoneOffset), null);
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
	public OffsetDateTime getPreciseFrom() {
		return this.from;
	}

	@Nullable
	@Override
	public OffsetDateTime getPreciseTo() {
		return this.to;
	}

	@Override
	public boolean isWithin(@Nonnull OffsetDateTime valueToCheck) {
		final long comparedValue = DateTimeRange.toComparableLong(valueToCheck);
		return this.fromToCompare <= comparedValue && comparedValue <= this.toToCompare;
	}

	@Nonnull
	@Override
	public Range<OffsetDateTime> cloneWithDifferentBounds(@Nullable OffsetDateTime from, @Nullable OffsetDateTime to) {
		Assert.isTrue(from != null || to != null, "At least one bound must be non-null!");
		return new DateTimeRange(from, to);
	}

	/**
	 * Returns true if passed moment is within the specified range (inclusive).
	 */
	public boolean isValidFor(@Nonnull OffsetDateTime theMoment) {
		final long comparedValue = theMoment.toEpochSecond();
		return this.fromToCompare <= comparedValue && this.toToCompare >= comparedValue;
	}

	@Override
	public int compareTo(@Nonnull DateTimeRange o) {
		final int leftBoundCompare = Long.compare(getFrom(), o.getFrom());
		final int rightBoundCompare = Long.compare(getTo(), o.getTo());
		if (leftBoundCompare != 0) {
			return leftBoundCompare;
		} else {
			return rightBoundCompare;
		}
	}

	/**
	 * Formats {@link DateTimeRange} to string.
	 */
	@Nonnull
	@Override
	public String toString() {
		return OPEN_CHAR + ofNullable(this.from).map(DateTimeFormatter.ISO_OFFSET_DATE_TIME::format).orElse("") +
			INTERVAL_JOIN + ofNullable(this.to).map(DateTimeFormatter.ISO_OFFSET_DATE_TIME::format).orElse("") + CLOSE_CHAR;
	}

	private static void assertEitherBoundaryNotNull(@Nullable OffsetDateTime from, @Nullable OffsetDateTime to) {
		if (from == null && to == null) {
			throw new EvitaInvalidUsageException("From and to cannot be both null at the same time in DateTimeRange type!");
		}
	}

	private static void assertFromLesserThanTo(@Nullable OffsetDateTime from, @Nullable OffsetDateTime to) {
		if (!(from == null || to == null || from.equals(to) || from.isBefore(to))) {
			throw new EvitaInvalidUsageException("From must be before or equals to to!");
		}
	}

}
