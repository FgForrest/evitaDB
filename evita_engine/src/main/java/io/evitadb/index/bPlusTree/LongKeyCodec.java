/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.index.bPlusTree;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Reversible, order-preserving codec between a boxed key type {@code M} and a primitive {@code long}, used by
 * {@link LongValueColumn} to store integral / temporal attribute keys without per-element boxing.
 *
 * Every codec is an exact **bijection** {@code M ↔ long} that is also **monotonic** under natural ordering — i.e.
 * {@code a.compareTo(b)} has the same sign as {@code Long.compare(encode(a), encode(b))}. This is the safety invariant
 * that lets {@link LongValueColumn#findKeyPosition} binary-search the primitive {@code long[]} and obtain exactly the
 * position the boxed comparator would have produced. Because of this invariant the column may only be selected when the
 * tree's comparator is natural order (see {@link ValueColumnFactory}).
 *
 * All unchecked casts to/from {@code M} are confined to this class.
 *
 * @param <M> the boxed key type
 */
@SuppressWarnings("unchecked")
enum LongKeyCodec {

	/**
	 * {@link Integer} ↔ its {@code int} value (sign-preserving, hence monotonic).
	 */
	INTEGER(Integer.class) {
		@Override
		public <M> long encode(@Nonnull M value) {
			return (Integer) value;
		}

		@Nonnull
		@Override
		public <M> M decode(long raw) {
			return (M) (Integer) (int) raw;
		}
	},

	/**
	 * {@link Long} ↔ itself (identity, trivially monotonic).
	 */
	LONG(Long.class) {
		@Override
		public <M> long encode(@Nonnull M value) {
			return (Long) value;
		}

		@Nonnull
		@Override
		public <M> M decode(long raw) {
			return (M) (Long) raw;
		}
	},

	/**
	 * {@link Short} ↔ its {@code short} value (sign-preserving, hence monotonic).
	 */
	SHORT(Short.class) {
		@Override
		public <M> long encode(@Nonnull M value) {
			return (Short) value;
		}

		@Nonnull
		@Override
		public <M> M decode(long raw) {
			return (M) (Short) (short) raw;
		}
	},

	/**
	 * {@link Byte} ↔ its {@code byte} value (sign-preserving, hence monotonic).
	 */
	BYTE(Byte.class) {
		@Override
		public <M> long encode(@Nonnull M value) {
			return (Byte) value;
		}

		@Nonnull
		@Override
		public <M> M decode(long raw) {
			return (M) (Byte) (byte) raw;
		}
	},

	/**
	 * {@link Boolean} ↔ {@code 0 / 1}, matching {@code false < true} natural order.
	 */
	BOOLEAN(Boolean.class) {
		@Override
		public <M> long encode(@Nonnull M value) {
			return ((Boolean) value) ? 1L : 0L;
		}

		@Nonnull
		@Override
		public <M> M decode(long raw) {
			return (M) (Boolean) (raw != 0L);
		}
	},

	/**
	 * {@link Character} ↔ its (unsigned) {@code char} code point, matching natural character order.
	 */
	CHARACTER(Character.class) {
		@Override
		public <M> long encode(@Nonnull M value) {
			return (Character) value;
		}

		@Nonnull
		@Override
		public <M> M decode(long raw) {
			return (M) (Character) (char) raw;
		}
	},

	/**
	 * {@link LocalDate} ↔ its epoch-day, which is monotonic with chronological (natural) date order.
	 */
	LOCAL_DATE(LocalDate.class) {
		@Override
		public <M> long encode(@Nonnull M value) {
			return ((LocalDate) value).toEpochDay();
		}

		@Nonnull
		@Override
		public <M> M decode(long raw) {
			return (M) LocalDate.ofEpochDay(raw);
		}
	},

	/**
	 * {@link LocalTime} ↔ its nano-of-day, which is monotonic with natural time-of-day order.
	 */
	LOCAL_TIME(LocalTime.class) {
		@Override
		public <M> long encode(@Nonnull M value) {
			return ((LocalTime) value).toNanoOfDay();
		}

		@Nonnull
		@Override
		public <M> M decode(long raw) {
			return (M) LocalTime.ofNanoOfDay(raw);
		}
	};

	/**
	 * The exact (boxed) type this codec encodes / decodes.
	 */
	@Nonnull private final Class<?> type;

	LongKeyCodec(@Nonnull Class<?> type) {
		this.type = type;
	}

	/**
	 * Returns the codec for the given (already normalized) key type, or {@code null} when the type has no
	 * order-preserving {@code long} encoding (e.g. {@code String}, {@code BigDecimal}, {@code Instant}).
	 *
	 * @param type the (normalized) key type
	 * @return the matching codec, or {@code null} when unsupported
	 */
	@Nullable
	static LongKeyCodec forType(@Nonnull Class<?> type) {
		for (final LongKeyCodec codec : values()) {
			if (codec.type == type) {
				return codec;
			}
		}
		return null;
	}

	/**
	 * Encodes a boxed key into its primitive {@code long} representation.
	 *
	 * @param value the boxed key (never {@code null})
	 * @param <M>   the boxed key type
	 * @return the order-preserving {@code long} encoding
	 */
	public abstract <M> long encode(@Nonnull M value);

	/**
	 * Decodes a primitive {@code long} back into its boxed key (boxing boundary — call only at the decode edge).
	 *
	 * @param raw the encoded value
	 * @param <M> the boxed key type
	 * @return the boxed key
	 */
	@Nonnull
	public abstract <M> M decode(long raw);

	/**
	 * Returns the exact (boxed) type this codec round-trips, used to allocate cold-path boxed arrays.
	 *
	 * @return the boxed key type
	 */
	@Nonnull
	public Class<?> type() {
		return this.type;
	}
}
