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

package io.evitadb.utils;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * String utils contains shared utility method for working with Numbers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class NumberUtils {

	private NumberUtils() {
	}

	/**
	 * Method returns true if the parameter type represents a number convertible to an integer.
	 * @param parameterType parameter type
	 * @return true if the parameter type represents a number convertible to an integer
	 */
	public static boolean isIntConvertibleNumber(@Nonnull Class<?> parameterType) {
		return int.class.equals(parameterType) ||
			long.class.equals(parameterType) ||
			short.class.equals(parameterType) ||
			byte.class.equals(parameterType) ||
			Number.class.isAssignableFrom(parameterType);
	}

	/**
	 * This method sums two numbers. The target number type is derived from the number `a`, number `b` is automatically
	 * converted to the same type and applied. Method checks that there is no loss of precision during sum.
	 */
	@SuppressWarnings("RedundantCast")
	@Nonnull
	public static Number sum(@Nonnull Number a, @Nonnull Number b) {
		if (a instanceof Byte) {
			final long longResult = convertToLong(a) + convertToLong(b);
			final byte byteResult = (byte) (((byte) a) + convertToByte(b));
			if (longResult != byteResult) {
				throw new ArithmeticException("byte overflow: " + longResult);
			}
			return byteResult;
		} else if (a instanceof Short) {
			final long longResult = convertToLong(a) + convertToLong(b);
			final short shortResult = (short) (((short) a) + convertToShort(b));
			if (longResult != shortResult) {
				throw new ArithmeticException("short overflow: " + longResult);
			}
			return shortResult;
		} else if (a instanceof Integer) {
			final long longResult = convertToLong(a) + convertToLong(b);
			final int intResult = (((int) a) + convertToInt(b));
			if (longResult != intResult) {
				throw new ArithmeticException("int overflow: " + longResult);
			}
			return intResult;
		} else if (a instanceof Long) {
			return (long) (((long) a) + convertToLong(b));
		} else if (a instanceof BigDecimal) {
			return ((BigDecimal) a).add(convertToBigDecimal(b));
		} else {
			throw new IllegalArgumentException("Unsupported number type: " + a.getClass());
		}
	}

	/**
	 * Converts unknown number to {@link byte}. Number overflow is checked during conversion process.
	 */
	public static byte convertToByte(@Nonnull Number number) {
		if (number instanceof Byte) {
			return (byte) number;
		} else if (number instanceof BigDecimal) {
			return ((BigDecimal) number).byteValueExact();
		} else {
			final byte converted = (byte) number.longValue();
			if (number.longValue() != converted) {
				throw new ArithmeticException("byte overflow: " + number);
			}
			return converted;
		}
	}

	/**
	 * Converts unknown number to {@link short}. Number overflow is checked during conversion process.
	 */
	public static short convertToShort(@Nonnull Number number) {
		if (number instanceof Short) {
			return (short) number;
		} else if (number instanceof BigDecimal) {
			return ((BigDecimal) number).shortValueExact();
		} else {
			final short converted = (short) number.longValue();
			if (number.longValue() != converted) {
				throw new ArithmeticException("byte overflow: " + number);
			}
			return converted;
		}
	}

	/**
	 * Converts unknown number to {@link int}. Number overflow is checked during conversion process.
	 */
	public static int convertToInt(@Nonnull Number number) {
		if (number instanceof Byte) {
			return ((byte) number);
		} else if (number instanceof Short) {
			return ((short) number);
		} else if (number instanceof Integer) {
			return (int) number;
		} else if (number instanceof BigDecimal) {
			return ((BigDecimal) number).intValueExact();
		} else if (number instanceof Float) {
			throw new ArithmeticException("Cannot convert float to integer exactly!");
		} else if (number instanceof Double) {
			throw new ArithmeticException("Cannot convert double to integer exactly!");
		} else {
			final int converted = (int) number.longValue();
			if (number.longValue() != converted) {
				throw new ArithmeticException("int overflow: " + number);
			}
			return converted;
		}
	}

	/**
	 * Converts {@link BigDecimal} to {@link int} scaling it to the accepted decimal places. Precision loss is verified
	 * during conversion process.
	 */
	public static int convertToInt(@Nonnull BigDecimal number, int acceptDecimalPlaces) {
		try {
			return number.stripTrailingZeros()
				.scaleByPowerOfTen(acceptDecimalPlaces)
				.setScale(0, RoundingMode.HALF_UP)
				.intValueExact();
		} catch (ArithmeticException ex) {
			throw new ArithmeticException(
				"Cannot convert big decimal " + number +
					" to exact integer by using " + acceptDecimalPlaces + " decimal places!"
			);
		}
	}

	/**
	 * Converts passed {@link BigDecimal} number to integer value with rounding and overflow handling.
	 *
	 * @param number             number to convert
	 * @param indexedPricePlaces number of decimal places to keep in the integer value
	 * @return converted integer value
	 * @throws EvitaInvalidUsageException if the number is too large to be converted to integer
	 */
	public static int convertExternalNumberToInt(@Nonnull BigDecimal number, int indexedPricePlaces) {
		try {
			return convertToInt(number, indexedPricePlaces);
		} catch (ArithmeticException ex) {
			throw new EvitaInvalidUsageException(ex.getMessage(), ex);
		}
	}

	/**
	 * Converts unknown number to {@link long}.
	 */
	public static long convertToLong(@Nonnull Number number) {
		if (number instanceof BigDecimal) {
			return ((BigDecimal) number).longValueExact();
		} else {
			return number.longValue();
		}
	}

	/**
	 * Converts unknown number to {@link BigDecimal}.
	 */
	@Nonnull
	public static BigDecimal convertToBigDecimal(@Nonnull Number number) {
		if (number instanceof Byte) {
			return new BigDecimal(number.toString());
		} else if (number instanceof Short) {
			return new BigDecimal(number.toString());
		} else if (number instanceof Integer) {
			return new BigDecimal(number.toString());
		} else if (number instanceof Long) {
			return new BigDecimal(number.toString());
		} else if (number instanceof BigDecimal) {
			return ((BigDecimal) number);
		} else if (number instanceof Float) {
			return new BigDecimal(number.toString());
		} else if (number instanceof Double) {
			return new BigDecimal(number.toString());
		} else {
			throw new IllegalArgumentException("Unsupported number type: " + number.getClass());
		}
	}

	/**
	 * Converts a {@link Number} to a specific target numeric type supported by evitaDB (`Byte`, `Short`, `Integer`,
	 * `Long`, or `BigDecimal`). For integral target types, the value is first converted to {@link BigDecimal}
	 * and then narrowed using exact-value methods to detect overflow. For `BigDecimal` targets, trailing zeros
	 * are stripped to ensure consistent equality semantics.
	 *
	 * @param number     the source number to convert
	 * @param targetType the desired numeric class
	 * @return the converted number in the target type
	 * @throws ArithmeticException      if the value does not fit in the target type
	 * @throws IllegalArgumentException if the target type is not one of the supported types
	 */
	@Nonnull
	public static Number convertToNumericType(
		@Nonnull Number number,
		@Nonnull Class<? extends Serializable> targetType
	) {
		if (targetType == Byte.class) {
			return convertToBigDecimal(number).byteValueExact();
		} else if (targetType == Short.class) {
			return convertToBigDecimal(number).shortValueExact();
		} else if (targetType == Integer.class) {
			return convertToBigDecimal(number).intValueExact();
		} else if (targetType == Long.class) {
			return convertToBigDecimal(number).longValueExact();
		} else if (targetType == BigDecimal.class) {
			return convertToBigDecimal(number).stripTrailingZeros();
		}
		throw new IllegalArgumentException("Unsupported target numeric type: " + targetType);
	}

	/**
	 * Packs two integer numbers into a single long — `numberA` occupies the high-order 32 bits, `numberB` the low-order
	 * 32 bits. Inverse of {@link #unpack(long)} / {@link #unpackHigh(long)} / {@link #unpackLow(long)}.
	 * Solution taken from https://stackoverflow.com/questions/12772939/java-storing-two-ints-in-a-long/12772968
	 */
	public static long pack(int numberA, int numberB) {
		return (((long) numberA) << 32) | (numberB & 0xffffffffL);
	}

	/**
	 * Packs two 16-bit values and one 32-bit value into a single long with the layout `high16:16 | mid16:16 | low32:32`.
	 * Both `high16` and `mid16` MUST fit into an unsigned 16-bit field (range `0..65535`); a value that overflows the
	 * field would be silently truncated, so it is rejected loudly instead — packing such a value is a broken caller
	 * assumption, not a recoverable condition. The `low32` value occupies the full low 32 bits and is stored
	 * sign-preservingly (any int is valid). Inverse of {@link #unpackHigh16(long)} / {@link #unpackMid16(long)} /
	 * {@link #unpackLow32(long)}.
	 *
	 * @param high16 value stored in the high 16-bit field (must be in the unsigned 16-bit range `0..65535`)
	 * @param mid16  value stored in the mid 16-bit field (must be in the unsigned 16-bit range `0..65535`)
	 * @param low32  value stored in the low 32-bit field (any int)
	 * @return the packed long
	 */
	public static long pack(int high16, int mid16, int low32) {
		// hand-rolled bounds check keeps this hot-path packer zero-allocation on success (no eager string, no lambda)
		if ((high16 & ~0xFFFF) != 0) {
			throw new GenericEvitaInternalError(
				"Value " + high16 + " does not fit into the high 16-bit field of a packed long.");
		}
		if ((mid16 & ~0xFFFF) != 0) {
			throw new GenericEvitaInternalError(
				"Value " + mid16 + " does not fit into the mid 16-bit field of a packed long.");
		}
		return ((long) high16 << 48) | ((long) mid16 << 32) | (low32 & 0xFFFFFFFFL);
	}

	/**
	 * Inverse method to {@link #pack(int, int)}. Allocates a two-element array holding the high-order
	 * component at index 0 and the low-order component at index 1. On hot paths that need only one of
	 * the two halves prefer the allocation-free {@link #unpackHigh(long)} / {@link #unpackLow(long)}.
	 */
	public static int[] unpack(long number) {
		return new int[]{
			unpackHigh(number),
			unpackLow(number)
		};
	}

	/**
	 * Returns the high-order 32 bits of `number` as an int — the index-0 component of
	 * {@link #unpack(long)}, i.e. the `numberA` that was passed to {@link #pack(int, int)}. Unlike
	 * {@link #unpack(long)} this performs no array allocation, so it is safe to call on hot paths that
	 * decompose a packed long.
	 */
	public static int unpackHigh(long number) {
		return (int) (number >> 32);
	}

	/**
	 * Returns the low-order 32 bits of `number` as an int — the index-1 component of
	 * {@link #unpack(long)}, i.e. the `numberB` that was passed to {@link #pack(int, int)}. Unlike
	 * {@link #unpack(long)} this performs no array allocation, so it is safe to call on hot paths that
	 * decompose a packed long.
	 */
	public static int unpackLow(long number) {
		return (int) number;
	}

	/**
	 * Returns the high 16-bit field (bits 48..63) of a long packed by {@link #pack(int, int, int)} as an unsigned int in
	 * the range `0..65535`.
	 */
	public static int unpackHigh16(long packed) {
		return (int) ((packed >>> 48) & 0xFFFF);
	}

	/**
	 * Returns the mid 16-bit field (bits 32..47) of a long packed by {@link #pack(int, int, int)} as an unsigned int in
	 * the range `0..65535`.
	 */
	public static int unpackMid16(long packed) {
		return (int) ((packed >>> 32) & 0xFFFF);
	}

	/**
	 * Returns the low 32-bit field (bits 0..31) of a long packed by {@link #pack(int, int, int)} as a sign-preserving int.
	 */
	public static int unpackLow32(long packed) {
		return (int) packed;
	}

	/**
	 * Normalizes BigDecimal value to the form which can be used as a reliable key in a map.
	 * @param bigDecimal value to be normalized
	 * @return normalized value
	 */
	@Nonnull
	public static BigDecimal normalize(@Nonnull BigDecimal bigDecimal) {
		return bigDecimal.stripTrailingZeros();
	}

	/**
	 * Returns the value with `BigDecimal` trailing zeros stripped if the value is a `BigDecimal`;
	 * otherwise returns the value unchanged. Used at the boundary between entity storage and
	 * indexes to ensure consistent `BigDecimal` representation in index keys.
	 *
	 * @param value the serializable value to normalize
	 * @return normalized value
	 */
	@Nonnull
	public static Serializable normalizeIfBigDecimal(@Nonnull Serializable value) {
		if (value instanceof BigDecimal bd) {
			return normalize(bd);
		} else if (value instanceof BigDecimal[] bdArray) {
			final BigDecimal[] normalized = new BigDecimal[bdArray.length];
			for (int i = 0; i < bdArray.length; i++) {
				normalized[i] = normalize(bdArray[i]);
			}
			return normalized;
		}
		return value;
	}

	/**
	 * Normalizes a value into the canonical form fed to the attribute indexes at the index-write boundary.
	 *
	 * This is the precision-aware counterpart of {@link #normalizeIfBigDecimal(Serializable)}: in addition to
	 * stripping trailing zeros from scalar `BigDecimal` values (so numerically equal values map to the same
	 * inverted-index key), it re-encodes {@link BigDecimalNumberRange} values to the attribute schema's
	 * `indexedDecimalPlaces`. A range constructed from `BigDecimal`s whose own scale differs from the schema
	 * (e.g. via {@link BigDecimalNumberRange#between(BigDecimal, BigDecimal)}) carries comparable longs frozen
	 * at that intrinsic scale; left unchanged, those longs would not line up with the query bounds (which are
	 * always encoded at `indexedDecimalPlaces`), so range overlap tests would silently miss. Re-encoding here
	 * keeps the stored value untouched (store-verbatim) while guaranteeing the range index uses the same scale
	 * as the query side. Both directions of an index mutation (insert and remove) must run through this method
	 * so the encoded longs stay symmetric.
	 *
	 * Array variants are handled element-wise (preserving `null` elements); all other types are returned
	 * unchanged. This null-guarding of array elements is the reason this method is kept separate from
	 * {@link #normalizeIfBigDecimal(Serializable)} rather than folded into it — do not merge the two, the
	 * differing element handling is a deliberate behavioral distinction.
	 *
	 * @param value                the value to normalize for indexing
	 * @param indexedDecimalPlaces the attribute schema's indexed decimal places — the authoritative scale used
	 *                             to encode `BigDecimalNumberRange` bounds into comparable longs
	 * @return the value in its canonical index form
	 */
	@Nonnull
	public static Serializable normalizeForIndexing(@Nonnull Serializable value, int indexedDecimalPlaces) {
		if (value instanceof BigDecimal bd) {
			return normalize(bd);
		} else if (value instanceof BigDecimalNumberRange range) {
			return normalizeRangeForIndexing(range, indexedDecimalPlaces);
		} else if (value instanceof BigDecimal[] bdArray) {
			final BigDecimal[] normalized = new BigDecimal[bdArray.length];
			for (int i = 0; i < bdArray.length; i++) {
				normalized[i] = bdArray[i] == null ? null : normalize(bdArray[i]);
			}
			return normalized;
		} else if (value instanceof BigDecimalNumberRange[] rangeArray) {
			final BigDecimalNumberRange[] normalized = new BigDecimalNumberRange[rangeArray.length];
			for (int i = 0; i < rangeArray.length; i++) {
				normalized[i] = rangeArray[i] == null ?
					null : normalizeRangeForIndexing(rangeArray[i], indexedDecimalPlaces);
			}
			return normalized;
		}
		return value;
	}

	/**
	 * Re-encodes a {@link BigDecimalNumberRange} so its comparable-long bounds use `indexedDecimalPlaces`.
	 * Returns the original instance when it already carries the requested scale, or when it has no bounds to
	 * re-scale (fully open / infinite range).
	 *
	 * @param range                the range to re-encode
	 * @param indexedDecimalPlaces the authoritative scale to encode the bounds with
	 * @return a range whose comparable-long bounds are encoded at `indexedDecimalPlaces`
	 */
	@Nonnull
	private static BigDecimalNumberRange normalizeRangeForIndexing(
		@Nonnull BigDecimalNumberRange range,
		int indexedDecimalPlaces
	) {
		if (range.getEffectiveRetainedDecimalPlaces() == indexedDecimalPlaces) {
			// already encoded at the requested scale (explicitly or by natural bound scale) - nothing to do
			return range;
		}
		final BigDecimal from = range.getPreciseFrom();
		final BigDecimal to = range.getPreciseTo();
		if (from != null && to != null) {
			return BigDecimalNumberRange.between(from, to, indexedDecimalPlaces);
		} else if (from != null) {
			return BigDecimalNumberRange.from(from, indexedDecimalPlaces);
		} else if (to != null) {
			return BigDecimalNumberRange.to(to, indexedDecimalPlaces);
		} else {
			// fully open / infinite range - no bounds to encode
			return range;
		}
	}

}
