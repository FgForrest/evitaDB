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

package io.evitadb.core.query.algebra.identity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;

/**
 * Detached ordered bucket values. Unsupported values are replaced by an opaque marker.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Immutable
public final class ValueList {
	/** Detached supported values; unsupported objects are never retained. */
	private final Object[] values;
	/** Precomputed content hash. */
	private final long hash64;
	/** Retained content weight, saturated to `Integer.MAX_VALUE` once any value is unsupported. */
	private final int weight;
	/** Whether unsupported values were encountered. */
	private final boolean opaque;

	/** Copies and normalizes bucket values without retaining mutable unsupported objects. */
	public ValueList(@Nonnull Object... values) {
		this.values = new Object[values.length];
		long hash = KeyAccumulator.mix(0, values.length);
		int weight = 0;
		boolean opaque = false;
		for (int i = 0; i < values.length; i++) {
			final Object value = values[i];
			final Object normalized;
			if (value != null && value.getClass() == BigDecimal.class) {
				final BigDecimal decimal = ((BigDecimal) value).stripTrailingZeros();
				normalized = new BigDecimal(decimal.unscaledValue(), decimal.scale());
			} else {
				normalized = value;
			}
			if (supported(normalized)) {
				this.values[i] = normalized;
				hash = KeyAccumulator.mix(hash, valueHash(normalized));
				weight = KeyAccumulator.sumWeight(weight, valueWeight(normalized));
			} else {
				this.values[i] = IdentityToken.OPAQUE;
				hash = KeyAccumulator.mix(hash, IdentityToken.OPAQUE.hash64());
				weight = Integer.MAX_VALUE;
				opaque = true;
			}
		}
		this.hash64 = hash;
		this.weight = weight;
		this.opaque = opaque;
	}

	/** Recognizes only the closed immutable bucket value domain. */
	private static boolean supported(@Nullable Object value) {
		return value instanceof String || value instanceof Byte || value instanceof Short ||
			value instanceof Integer || value instanceof Long || value instanceof Boolean ||
			value instanceof Character ||
			value instanceof OffsetDateTime || value instanceof LocalDateTime || value instanceof LocalDate ||
			value instanceof LocalTime || value instanceof Locale || value instanceof Currency ||
			value instanceof UUID ||
			value != null && value.getClass() == BigDecimal.class;
	}

	/** Hashes one supported value with its type discriminator and no temporary accumulator. */
	private static long valueHash(@Nonnull Object value) {
		if (value instanceof final String text) {
			return KeyAccumulator.mix(1, KeyAccumulator.contentHash(text));
		} else if (value instanceof final Byte number) {
			return KeyAccumulator.mix(2, number.byteValue());
		} else if (value instanceof final Short number) {
			return KeyAccumulator.mix(3, number.shortValue());
		} else if (value instanceof final Integer number) {
			return KeyAccumulator.mix(4, number.intValue());
		} else if (value instanceof final Long number) {
			return KeyAccumulator.mix(5, number.longValue());
		} else if (value instanceof final Boolean flag) {
			return KeyAccumulator.mix(6, flag.booleanValue() ? 1 : 0);
		} else if (value instanceof final Character character) {
			return KeyAccumulator.mix(7, character.charValue());
		} else if (value instanceof final OffsetDateTime moment) {
			return KeyAccumulator.mix(KeyAccumulator.mix(KeyAccumulator.mix(8, moment.toEpochSecond()),
				moment.getNano()), moment.getOffset().getTotalSeconds());
		} else if (value instanceof final LocalDateTime moment) {
			return KeyAccumulator.mix(KeyAccumulator.mix(9, moment.toLocalDate().toEpochDay()),
				moment.toLocalTime().toNanoOfDay());
		} else if (value instanceof final LocalDate day) {
			return KeyAccumulator.mix(10, day.toEpochDay());
		} else if (value instanceof final LocalTime time) {
			return KeyAccumulator.mix(11, time.toNanoOfDay());
		} else if (value instanceof final Locale locale) {
			return KeyAccumulator.mix(12, KeyAccumulator.localeHash(locale));
		} else if (value instanceof final Currency currency) {
			return KeyAccumulator.mix(13, KeyAccumulator.contentHash(currency.getCurrencyCode()));
		} else if (value instanceof final UUID uuid) {
			return KeyAccumulator.mix(KeyAccumulator.mix(14, uuid.getMostSignificantBits()),
				uuid.getLeastSignificantBits());
		} else if (value instanceof final BigDecimal decimal) {
			return KeyAccumulator.mix(15, KeyAccumulator.decimalHash(decimal.unscaledValue(), decimal.scale()));
		}
		throw new IllegalArgumentException("Unsupported bucket value.");
	}

	/** Charges supported retained values by content, never assigning unbounded values a unit weight. */
	private static int valueWeight(@Nonnull Object value) {
		if (value instanceof final String text) {
			return KeyAccumulator.textWeight(text.length());
		} else if (value instanceof final Locale locale) {
			return KeyAccumulator.textWeight(KeyAccumulator.localeTextLength(locale));
		} else if (value instanceof final Currency currency) {
			return KeyAccumulator.textWeight(currency.getCurrencyCode().length());
		} else if (value instanceof final BigDecimal decimal) {
			return KeyAccumulator.decimalWeight(decimal.unscaledValue());
		}
		return 1;
	}

	/** Returns the number of bucket values. */
	public int size() {
		return this.values.length;
	}

	/** Returns the precomputed content hash. */
	public long hash64() {
		return this.hash64;
	}

	/** Returns content-sensitive retained weight. */
	public int weight() {
		return this.weight;
	}

	/** Reports unsupported values. */
	public boolean isOpaque() {
		return this.opaque;
	}

	/** Refuses cross-query reuse for unsupported values or an oversized sequence. */
	public boolean isQueryLocal() {
		return this.opaque || size() > TokenList.CAP;
	}

	/** Compares normalized ordered values. */
	@Override
	public boolean equals(@Nullable Object other) {
		return this == other || other instanceof final ValueList that && Arrays.equals(this.values, that.values);
	}

	/** Folds the precomputed hash. */
	@Override
	public int hashCode() {
		return Long.hashCode(this.hash64);
	}
}
