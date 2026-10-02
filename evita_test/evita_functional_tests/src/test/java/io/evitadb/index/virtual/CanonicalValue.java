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

package io.evitadb.index.virtual;

import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.NumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.index.attribute.SortIndex.ComparableArray;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Encodes a value an index holds into a string that two values share exactly when they are the same value - the
 * comparison the differential harness and the partition oracle decide equality by.
 *
 * `toString` will not do: it is lossy for exactly the values an index normalizes. A `BigDecimalNumberRange` prints its
 * original endpoints while the index orders and compares it by the bounds encoded at its retained decimal places, so
 * `[3.4,4.5]` kept at one decimal place and the same range kept at two print alike and are different values. A
 * {@link ComparableLocale} prints its language tag and drops the variant, and a {@link ComparableArray} prints its
 * elements by their own lossy `toString`. The encoding therefore spells out every component the value's identity
 * depends on, recursively for compounds, and refuses a class it does not know rather than falling back to its
 * rendering - an unknown class must be taught, never guessed.
 *
 * Use `toString` for failure messages only.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class CanonicalValue {

	private CanonicalValue() {
	}

	/**
	 * Encodes a value losslessly.
	 *
	 * @param value the value, possibly `null`
	 * @return the encoding
	 * @throws IllegalArgumentException when the class of the value (or of one of its components) is not known to the
	 *                                  encoder
	 */
	@Nonnull
	static String encode(@Nullable Object value) {
		final StringBuilder target = new StringBuilder(32);
		encode(value, target);
		return target.toString();
	}

	/**
	 * Appends the encoding of a value.
	 *
	 * @param value  the value, possibly `null`
	 * @param target the builder to append to
	 */
	private static void encode(@Nullable Object value, @Nonnull StringBuilder target) {
		if (value == null) {
			target.append("null");
		} else if (value instanceof final String string) {
			// the length makes the encoding unambiguous whatever the characters, which are kept as they are
			target.append("String(").append(string.length()).append(":").append(string).append(')');
		} else if (value instanceof final Character character) {
			target.append("Character(").append((int) character).append(')');
		} else if (
			value instanceof Boolean || value instanceof Byte || value instanceof Short ||
				value instanceof Integer || value instanceof Long
		) {
			target.append(value.getClass().getSimpleName()).append('(').append(value).append(')');
		} else if (value instanceof final BigDecimal decimal) {
			target.append("BigDecimal(unscaled=").append(decimal.unscaledValue())
				.append(", scale=").append(decimal.scale()).append(')');
		} else if (value instanceof final Instant instant) {
			target.append("Instant(");
			appendInstant(instant, target);
			target.append(')');
		} else if (value instanceof final OffsetDateTime dateTime) {
			target.append("OffsetDateTime(");
			appendInstant(dateTime.toInstant(), target);
			target.append(", offset=").append(dateTime.getOffset().getTotalSeconds()).append(')');
		} else if (value instanceof final LocalDateTime dateTime) {
			target.append("LocalDateTime(");
			appendDate(dateTime.toLocalDate(), target);
			target.append('T');
			appendTime(dateTime.toLocalTime(), target);
			target.append(')');
		} else if (value instanceof final LocalDate date) {
			target.append("LocalDate(");
			appendDate(date, target);
			target.append(')');
		} else if (value instanceof final LocalTime time) {
			target.append("LocalTime(");
			appendTime(time, target);
			target.append(')');
		} else if (value instanceof final NumberRange<?> range) {
			target.append(range.getClass().getSimpleName()).append("(from=");
			encode(range.getPreciseFrom(), target);
			target.append(", to=");
			encode(range.getPreciseTo(), target);
			target.append(", encodedFrom=").append(range.getFrom())
				.append(", encodedTo=").append(range.getTo())
				.append(", decimalPlaces=").append(range.getRetainedDecimalPlaces()).append(')');
		} else if (value instanceof final DateTimeRange range) {
			target.append("DateTimeRange(from=");
			encode(range.getPreciseFrom(), target);
			target.append(", to=");
			encode(range.getPreciseTo(), target);
			target.append(", encodedFrom=").append(range.getFrom())
				.append(", encodedTo=").append(range.getTo()).append(')');
		} else if (value instanceof final ComparableLocale locale) {
			target.append("ComparableLocale(");
			encode(locale.getLocale(), target);
			target.append(')');
		} else if (value instanceof final Locale locale) {
			appendLocale(locale, target);
		} else if (value instanceof final ComparableCurrency currency) {
			target.append("ComparableCurrency(");
			encode(currency.getCurrency(), target);
			target.append(')');
		} else if (value instanceof final Currency currency) {
			target.append("Currency(").append(currency.getCurrencyCode()).append(')');
		} else if (value instanceof final UUID uuid) {
			target.append("UUID(").append(uuid.getMostSignificantBits())
				.append(", ").append(uuid.getLeastSignificantBits()).append(')');
		} else if (value instanceof final Predecessor predecessor) {
			target.append("Predecessor(").append(predecessor.predecessorPk()).append(')');
		} else if (value instanceof final ReferencedEntityPredecessor predecessor) {
			target.append("ReferencedEntityPredecessor(").append(predecessor.predecessorPk()).append(')');
		} else if (value instanceof final ComparableArray array) {
			target.append("ComparableArray");
			appendElements(array.array(), target);
		} else if (value instanceof final Object[] array) {
			target.append(array.getClass().getComponentType().getSimpleName()).append("[]");
			appendElements(array, target);
		} else {
			throw new IllegalArgumentException(
				"The canonical encoding does not know `" + value.getClass().getName() + "` (value `" + value + "`) - " +
					"teach it the components the identity of the class depends on rather than falling back to its " +
					"rendering!"
			);
		}
	}

	/**
	 * Appends the elements of an array, each at its position and nulls included.
	 *
	 * @param elements the elements
	 * @param target   the builder to append to
	 */
	private static void appendElements(@Nonnull Object[] elements, @Nonnull StringBuilder target) {
		target.append('(').append(elements.length).append(")[");
		for (int i = 0; i < elements.length; i++) {
			if (i > 0) {
				target.append(", ");
			}
			target.append(i).append('=');
			encode(elements[i], target);
		}
		target.append(']');
	}

	/**
	 * Appends an instant as its epoch second and nanosecond.
	 *
	 * @param instant the instant
	 * @param target  the builder to append to
	 */
	private static void appendInstant(@Nonnull Instant instant, @Nonnull StringBuilder target) {
		target.append("epochSecond=").append(instant.getEpochSecond()).append(", nano=").append(instant.getNano());
	}

	/**
	 * Appends a date in the ISO form.
	 *
	 * @param date   the date
	 * @param target the builder to append to
	 */
	private static void appendDate(@Nonnull LocalDate date, @Nonnull StringBuilder target) {
		target.append(date.getYear()).append('-').append(date.getMonthValue()).append('-').append(date.getDayOfMonth());
	}

	/**
	 * Appends a time in the ISO form, always with its nanoseconds.
	 *
	 * @param time   the time
	 * @param target the builder to append to
	 */
	private static void appendTime(@Nonnull LocalTime time, @Nonnull StringBuilder target) {
		target.append(time.getHour()).append(':').append(time.getMinute()).append(':').append(time.getSecond())
			.append('.').append(String.format("%09d", time.getNano()));
	}

	/**
	 * Appends a locale with every component its equality depends on - language, script, country, variant and the
	 * extensions in key order.
	 *
	 * @param locale the locale
	 * @param target the builder to append to
	 */
	private static void appendLocale(@Nonnull Locale locale, @Nonnull StringBuilder target) {
		target.append("Locale(language=").append(locale.getLanguage())
			.append(", script=").append(locale.getScript())
			.append(", country=").append(locale.getCountry())
			.append(", variant=").append(locale.getVariant())
			.append(", extensions={");
		boolean first = true;
		for (final Character key : new TreeSet<>(locale.getExtensionKeys())) {
			if (!first) {
				target.append(", ");
			}
			first = false;
			target.append(key).append('=').append(locale.getExtension(key));
		}
		target.append("})");
	}
}
