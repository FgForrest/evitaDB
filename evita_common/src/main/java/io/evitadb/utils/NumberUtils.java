/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

import javax.annotation.Nonnull;
import java.math.BigDecimal;

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
			return number.stripTrailingZeros().scaleByPowerOfTen(acceptDecimalPlaces).intValueExact();
		} catch (ArithmeticException ex) {
			throw new IllegalArgumentException(
				"Cannot convert big decimal " + number +
					" to exact integer by using " + acceptDecimalPlaces + " decimal places!"
			);
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
		} else {
			throw new IllegalArgumentException("Unsupported number type: " + number.getClass());
		}
	}

	/**
	 * Creates long number as a composition of two integer numbers.
	 * Solution taken from https://stackoverflow.com/questions/12772939/java-storing-two-ints-in-a-long/12772968
	 */
	public static long join(int numberA, int numberB) {
		return (((long) numberA) << 32) | (numberB & 0xffffffffL);
	}

	/**
	 * Inverse method to {@link #join(int, int)}.
	 */
	public static int[] split(long number) {
		return new int[]{
			(int) (number >> 32),
			(int) (number)
		};
	}

}
