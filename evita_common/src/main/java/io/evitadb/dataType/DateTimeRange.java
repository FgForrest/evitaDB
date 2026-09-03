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
 * ## Comparison granularity
 *
 * Both bounds are reduced to a **whole number of milliseconds since the epoch** by
 * {@link #toComparableLong(OffsetDateTime)}, and that pair of longs is the type's entire ordering and equality
 * surface — {@link #compareTo}, {@code equals} and {@code hashCode} all derive from it and from nothing else. Two
 * ranges whose bounds differ only below the millisecond are therefore the same range. This matches the millisecond
 * guarantee every other temporal data type carries (see {@code EvitaDataTypes#toSupportedType}); before that
 * alignment the comparison was made on whole **seconds**, so ranges less than a second apart used to be equal and no
 * longer are.
 *
 * ## The open-bound sentinels
 *
 * An absent bound is represented by the constants {@link #OPEN_FROM_THRESHOLD} / {@link #OPEN_TO_THRESHOLD} —
 * the very {@code Long.MIN_VALUE} / {@code Long.MAX_VALUE} pair the five {@code NumberRange} subtypes already spend
 * on their own open bounds, so all six {@link Range} implementations now share one representation of "unbounded".
 * They are deliberately **constants** rather than a moment derived from the other bound's zone offset: the derived
 * form (`LocalDateTime.MIN.atOffset(other.getOffset())`) overflows a `long` once expressed in milliseconds, and it
 * made one logical "open" bound take a different numeric value for every offset it was paired with.
 *
 * A **closed** bound beyond the millisecond-representable window saturates onto
 * {@link #SATURATED_FROM_THRESHOLD} / {@link #SATURATED_TO_THRESHOLD} — one step inside the sentinels, so a real
 * bound can never be mistaken for an open one, and no arithmetic here can overflow. The window spans roughly
 * ±292 million years, far outside anything a scalar temporal attribute can even represent, so the saturation is
 * unreachable for every practical bound and behaves as "effectively unbounded" for the rest.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(of = {"fromToCompare", "toToCompare"}, cacheStrategy = CacheStrategy.LAZY)
public final class DateTimeRange implements Range<OffsetDateTime>, Serializable, Comparable<DateTimeRange> {
	@Serial private static final long serialVersionUID = 7690351814641934282L;
	/**
	 * The comparison long an absent lower bound takes — identical to the {@code NumberRange} family's open-from
	 * sentinel, so every {@link Range} implementation agrees on what "unbounded from" means.
	 */
	public static final long OPEN_FROM_THRESHOLD = Long.MIN_VALUE;
	/**
	 * The comparison long an absent upper bound takes — identical to the {@code NumberRange} family's open-to
	 * sentinel.
	 */
	public static final long OPEN_TO_THRESHOLD = Long.MAX_VALUE;
	/**
	 * The comparison long a **closed** bound below {@link #MIN_REPRESENTABLE_EPOCH_SECOND} saturates onto. One step
	 * inside {@link #OPEN_FROM_THRESHOLD} so that a closed bound is never read back as an open one.
	 */
	public static final long SATURATED_FROM_THRESHOLD = Long.MIN_VALUE + 1;
	/**
	 * The comparison long a **closed** bound above {@link #MAX_REPRESENTABLE_EPOCH_SECOND} saturates onto. One step
	 * inside {@link #OPEN_TO_THRESHOLD}, mirroring {@link #SATURATED_FROM_THRESHOLD}.
	 */
	public static final long SATURATED_TO_THRESHOLD = Long.MAX_VALUE - 1;
	/**
	 * The lowest epoch second whose millisecond expansion still fits {@link #SATURATED_FROM_THRESHOLD}. Anything
	 * below it saturates. `second * 1000` is the whole expansion on this side, because the nanosecond remainder a
	 * moment carries is never negative.
	 */
	public static final long MIN_REPRESENTABLE_EPOCH_SECOND = SATURATED_FROM_THRESHOLD / 1000L;
	/**
	 * The highest epoch second whose millisecond expansion still fits {@link #SATURATED_TO_THRESHOLD}. The
	 * subtracted `999` is the largest millisecond remainder the expansion can add on top of `second * 1000`.
	 */
	public static final long MAX_REPRESENTABLE_EPOCH_SECOND = (SATURATED_TO_THRESHOLD - 999L) / 1000L;
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
		// an absent bound takes the offset-independent constant - never a moment derived from the other bound, which
		// would overflow in milliseconds and would give one logical "open" bound a different value per paired offset
		this.fromToCompare = from == null ? OPEN_FROM_THRESHOLD : toComparableLong(from);
		this.toToCompare = to == null ? OPEN_TO_THRESHOLD : toComparableLong(to);
	}

	/**
	 * Reduces a moment to the `long` this type compares by: the whole number of **milliseconds** since the epoch,
	 * truncated toward the past (a moment's nanosecond field is never negative, so the sub-millisecond remainder is
	 * simply dropped).
	 *
	 * A moment outside the millisecond-representable window saturates onto {@link #SATURATED_FROM_THRESHOLD} /
	 * {@link #SATURATED_TO_THRESHOLD} rather than overflowing. `OffsetDateTime` reaches year ±999999999, whose epoch
	 * second is ~3.16e16 — a thousand times that does not fit a `long` — so the guard is not theoretical; it is what
	 * lets {@code OffsetDateTime.MAX} be used as a bound at all. The saturated value stays one step inside the
	 * open-bound sentinels so such a bound is still unambiguously a closed one.
	 *
	 * @param theMoment the moment to reduce
	 * @return the comparison long
	 */
	@Nonnull
	public static Long toComparableLong(@Nonnull OffsetDateTime theMoment) {
		final long epochSecond = theMoment.toEpochSecond();
		if (epochSecond < MIN_REPRESENTABLE_EPOCH_SECOND) {
			return SATURATED_FROM_THRESHOLD;
		} else if (epochSecond > MAX_REPRESENTABLE_EPOCH_SECOND) {
			return SATURATED_TO_THRESHOLD;
		} else {
			return epochSecond * 1000L + theMoment.getNano() / 1_000_000;
		}
	}

	/**
	 * Parses string to {@link DateTimeRange} or throws an exception. String must conform to the format produced
	 * by {@link DateTimeRange#toString()} method.
	 */
	@Nonnull
	public static DateTimeRange fromString(@Nonnull String string) throws DataTypeParseException {
		return Range.parseRange(
			string, DateTimeRange::parseDateTime,
			DateTimeRange::until, DateTimeRange::since, DateTimeRange::between
		);
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
	 *
	 * @see #isWithin(OffsetDateTime)
	 */
	public boolean isValidFor(@Nonnull OffsetDateTime theMoment) {
		return isWithin(theMoment);
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

	/**
	 * Refuses a range whose lower bound lies after its upper one.
	 *
	 * The comparison is made on the **instant**, which is the order this type sorts and compares by everywhere else:
	 * {@link #toComparableLong(OffsetDateTime)} reduces a bound to its epoch millisecond and both {@code equals} and
	 * {@code compareTo} are derived from the two resulting longs. An offset-sensitive test would be stricter than the
	 * type's own notion of equality — it would reject a legitimate zero-width range whose two bounds name the same
	 * moment at two different offsets ({@code 12:00+02:00} … {@code 11:00+01:00}), while treating it as equal to the
	 * very range it refused to build.
	 *
	 * @param from the lower bound, or {@code null} for an open one
	 * @param to   the upper bound, or {@code null} for an open one
	 */
	private static void assertFromLesserThanTo(@Nullable OffsetDateTime from, @Nullable OffsetDateTime to) {
		if (!(from == null || to == null || !from.isAfter(to))) {
			throw new EvitaInvalidUsageException("From must be before or equals to to!");
		}
	}

}
