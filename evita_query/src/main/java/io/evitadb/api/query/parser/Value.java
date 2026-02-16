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

package io.evitadb.api.query.parser;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.StreamSupport;

/**
 * Represents EvitaQL single value or variadic array of values. It is wrapper for parsed values of supported data
 * types, either literals or arguments. Types of {@link EvitaDataTypes} plus iterables and arrays have first-class
 * support. Variadic arrays can be arrays or iterables.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 * @see EvitaDataTypes
 */
@Getter
@EqualsAndHashCode(cacheStrategy = EqualsAndHashCode.CacheStrategy.LAZY)
@ToString
public class Value {

	/**
	 * Concrete value of parsed literal or parameter in target data type.
	 */
	@Nonnull
	private final Object actualValue;

	/**
	 * Target data type of parsed value
	 */
	@Nonnull
	private final Class<?> type;

	/**
	 * Casts a variadic value item to the expected type, throwing if the type does not match.
	 */
	@Nonnull
	private static <T> T variadicValueItemAsSpecificType(@Nonnull Object item, @Nonnull Class<T> type) {
		// verified type is a precondition ensured by the parser visitor
		Assert.isPremiseValid(
			type.isInstance(item),
			"Expected variadic value items of type `" + type.getName() + "` but got `"
				+ item.getClass().getName() + "`."
		);
		return type.cast(item);
	}

	/**
	 * Asserts that the given value is an instance of the expected type.
	 */
	private static void assertValueIsOfType(@Nonnull Object theValue, @Nonnull Class<?> type) {
		// verified type is a precondition ensured by the parser visitor
		Assert.isPremiseValid(
			type.isInstance(theValue),
			"Expected value of type `" + type.getName() + "` but got `"
				+ theValue.getClass().getName() + "`."
		);
	}

	/**
	 * Converts a value to an enum instance, handling both direct enum values and {@link EnumWrapper}.
	 */
	@Nonnull
	private static <T extends Enum<T>> T valueAsEnum(@Nonnull Object theValue, @Nonnull Class<T> enumType) {
		if (theValue instanceof Enum<?>) {
			return asSpecificType(theValue, enumType);
		} else if (theValue instanceof EnumWrapper) {
			return asSpecificType(theValue, EnumWrapper.class).toEnum(enumType);
		} else {
			throw new EvitaInvalidUsageException(
				"Expected enum value but got `" + theValue.getClass().getName() + "`."
			);
		}
	}

	/**
	 * Casts the given value to the expected type after asserting type compatibility.
	 */
	@Nonnull
	private static <T> T asSpecificType(@Nonnull Object theValue, @Nonnull Class<T> type) {
		assertValueIsOfType(theValue, type);
		return type.cast(theValue);
	}

	public Value(@Nonnull Object actualValue) {
		this.actualValue = actualValue;
		this.type = actualValue.getClass();
	}

	/**
	 * Returns the value cast to {@link Serializable}.
	 */
	@Nonnull
	public Serializable asSerializable() {
		return asSpecificType(this.actualValue, Serializable.class);
	}

	/**
	 * Returns the value cast to {@link Comparable}.
	 */
	@Nonnull
	public Comparable<?> asComparable() {
		return asSpecificType(this.actualValue, Comparable.class);
	}

	/**
	 * Returns the value cast to both {@link Serializable} and {@link Comparable}.
	 */
	@Nonnull
	public <T extends Serializable & Comparable<?>> T asSerializableAndComparable() {
		assertValueIsOfType(this.actualValue, Serializable.class);
		assertValueIsOfType(this.actualValue, Comparable.class);
		//noinspection unchecked
		return (T) this.actualValue;
	}

	/**
	 * Returns the value as a {@link String}, converting from {@link Character} if needed.
	 */
	@Nonnull
	public String asString() {
		if (this.actualValue instanceof final Character characterValue) {
			return characterValue.toString();
		}
		return asSpecificType(this.actualValue, String.class);
	}

	/**
	 * Returns the value cast to {@link Number}.
	 */
	@Nonnull
	public Number asNumber() {
		return asSpecificType(this.actualValue, Number.class);
	}

	/**
	 * Returns the value converted to the specified number type.
	 */
	@Nonnull
	public <T extends Number> T asNumber(@Nonnull Class<T> numberType) {
		return Objects.requireNonNull(EvitaDataTypes.toTargetType(asNumber(), numberType));
	}

	/**
	 * Returns the value as an int, with overflow protection for Long values.
	 */
	public int asInt() {
		if (this.actualValue instanceof final Long longNumber) {
			try {
				return Math.toIntExact(longNumber);
			} catch (ArithmeticException e) {
				throw new EvitaInvalidUsageException(
					"`Long` number was passed when `Integer` desired but `" + this.actualValue
						+ "` value is too big for `Integer`."
				);
			}
		}
		return asNumber().intValue();
	}

	/**
	 * Returns the value as a long.
	 */
	public long asLong() {
		return asNumber().longValue();
	}

	/**
	 * Returns the value cast to boolean.
	 */
	public boolean asBoolean() {
		return asSpecificType(this.actualValue, Boolean.class);
	}

	/**
	 * Returns the value cast to {@link BigDecimal}.
	 */
	@Nonnull
	public BigDecimal asBigDecimal() {
		return asSpecificType(this.actualValue, BigDecimal.class);
	}

	/**
	 * Returns the value cast to {@link OffsetDateTime}.
	 */
	@Nonnull
	public OffsetDateTime asOffsetDateTime() {
		return asSpecificType(this.actualValue, OffsetDateTime.class);
	}

	/**
	 * Returns the value cast to {@link LocalDateTime}.
	 */
	@Nonnull
	public LocalDateTime asLocalDateTime() {
		return asSpecificType(this.actualValue, LocalDateTime.class);
	}

	/**
	 * Returns the value cast to {@link LocalDate}.
	 */
	@Nonnull
	public LocalDate asLocalDate() {
		return asSpecificType(this.actualValue, LocalDate.class);
	}

	/**
	 * Returns the value cast to {@link LocalTime}.
	 */
	@Nonnull
	public LocalTime asLocalTime() {
		return asSpecificType(this.actualValue, LocalTime.class);
	}

	/**
	 * Returns the value cast to {@link DateTimeRange}.
	 */
	@Nonnull
	public DateTimeRange asDateTimeRange() {
		return asSpecificType(this.actualValue, DateTimeRange.class);
	}

	/**
	 * Returns the value cast to {@link BigDecimalNumberRange}.
	 */
	@Nonnull
	public BigDecimalNumberRange asBigDecimalNumberRange() {
		return asSpecificType(this.actualValue, BigDecimalNumberRange.class);
	}

	/**
	 * Returns the value cast to {@link LongNumberRange}.
	 */
	@Nonnull
	public LongNumberRange asLongNumberRange() {
		return asSpecificType(this.actualValue, LongNumberRange.class);
	}

	/**
	 * Returns the value converted to the specified enum type.
	 */
	@Nonnull
	public <T extends Enum<T>> T asEnum(@Nonnull Class<T> enumType) {
		return valueAsEnum(this.actualValue, enumType);
	}

	/**
	 * Returns the value as an array of the specified enum type.
	 */
	@Nonnull
	public <T extends Enum<T>> T[] asEnumArray(@Nonnull Class<T> enumType) {
		try {
			return asArray(v -> valueAsEnum(v, enumType), enumType);
		} catch (ClassCastException e) {
			throw new EvitaInvalidUsageException(
				"Unexpected type of value array `" + this.actualValue.getClass().getName() + "`."
			);
		}
	}

	/**
	 * Returns the value as a {@link Locale}, converting from string if needed.
	 */
	@Nonnull
	public Locale asLocale() {
		if (this.actualValue instanceof Locale) {
			return asSpecificType(this.actualValue, Locale.class);
		} else if (this.actualValue instanceof String) {
			return Objects.requireNonNull(
				EvitaDataTypes.toTargetType(asSpecificType(this.actualValue, String.class), Locale.class)
			);
		} else {
			// verified type is a precondition ensured by the parser visitor
			throw new EvitaInvalidUsageException(
				"Expected locale or string value but got `" + this.actualValue.getClass().getName() + "`."
			);
		}
	}

	/**
	 * Returns the value as a {@link Currency}, converting from string if needed.
	 */
	@Nonnull
	public Currency asCurrency() {
		if (this.actualValue instanceof Currency) {
			return asSpecificType(this.actualValue, Currency.class);
		} else if (this.actualValue instanceof String) {
			return Objects.requireNonNull(
				EvitaDataTypes.toTargetType(asSpecificType(this.actualValue, String.class), Currency.class)
			);
		} else {
			throw new EvitaInvalidUsageException(
				"Expected currency or string value but got `" + this.actualValue.getClass().getName() + "`."
			);
		}
	}

	/**
	 * Returns the value as a {@link UUID}, converting from string if needed.
	 */
	@Nonnull
	public UUID asUuid() {
		if (this.actualValue instanceof UUID) {
			return asSpecificType(this.actualValue, UUID.class);
		} else if (this.actualValue instanceof String) {
			return Objects.requireNonNull(
				EvitaDataTypes.toTargetType(asSpecificType(this.actualValue, String.class), UUID.class)
			);
		} else {
			throw new EvitaInvalidUsageException(
				"Expected UUID or string value but got `" + this.actualValue.getClass().getName() + "`."
			);
		}
	}

	/**
	 * Returns the value as a String array.
	 */
	@Nonnull
	public String[] asStringArray() {
		try {
			return asArray(v -> variadicValueItemAsSpecificType(v, String.class), String.class);
		} catch (ClassCastException e) {
			throw new EvitaInvalidUsageException(
				"Unexpected type of value array `" + this.actualValue.getClass().getName() + "`."
			);
		}
	}

	/**
	 * Returns the value as a Serializable array.
	 */
	@Nonnull
	public Serializable[] asSerializableArray() {
		try {
			return asArray(Serializable.class::cast, Serializable.class);
		} catch (ClassCastException e) {
			throw new EvitaInvalidUsageException(
				"Unexpected type of value array `" + this.actualValue.getClass().getName() + "`."
			);
		}
	}

	/**
	 * Returns the value as an Integer array, with overflow protection for Long values.
	 */
	@Nonnull
	public Integer[] asIntegerArray() {
		try {
			return asArray(
				v -> {
					if (v instanceof final Long longNumber) {
						try {
							return Math.toIntExact(longNumber);
						} catch (ArithmeticException e) {
							throw new EvitaInvalidUsageException(
								"`Long` number was passed when `Integer` desired but `" + this.actualValue
									+ "` value is too big for `Integer`."
							);
						}
					}
					return variadicValueItemAsSpecificType(v, Number.class).intValue();
				},
				Integer.class
			);
		} catch (ClassCastException e) {
			// verified type is a precondition ensured by the parser visitor
			throw new EvitaInvalidUsageException(
				"Unexpected type of value array `" + this.actualValue.getClass().getName() + "`."
			);
		}
	}

	/**
	 * Returns the value as a Locale array, converting from strings if needed.
	 */
	@Nonnull
	public Locale[] asLocaleArray() {
		try {
			return asArray(
				v -> {
					if (v instanceof Locale) {
						return variadicValueItemAsSpecificType(v, Locale.class);
					} else if (v instanceof String) {
						return EvitaDataTypes.toTargetType(
							variadicValueItemAsSpecificType(v, String.class), Locale.class
						);
					} else {
						throw new EvitaInvalidUsageException(
							"Expected variadic value items of type locale or string value but got `"
								+ v.getClass().getName() + "`."
						);
					}
				},
				Locale.class
			);
		} catch (ClassCastException e) {
			throw new EvitaInvalidUsageException(
				"Unexpected type of value array `" + this.actualValue.getClass().getName() + "`."
			);
		}
	}

	/**
	 * Converts the value (an iterable or array) to a typed array using the provided transformer.
	 */
	@Nonnull
	private <T> T[] asArray(@Nonnull Function<Object, T> itemTransformer, @Nonnull Class<T> expectedItemType) {
		final Object values = this.actualValue;
		if (values instanceof Iterable<?> iterableValues) {
			if (!iterableValues.iterator().hasNext()) {
				//noinspection unchecked
				return (T[]) Array.newInstance(expectedItemType, 0);
			}

			//noinspection unchecked
			return StreamSupport.stream(iterableValues.spliterator(), false)
				.map(itemTransformer)
				.toArray(size -> (T[]) Array.newInstance(expectedItemType, size));
		} else if (values.getClass().isArray()) {
			final int length = Array.getLength(values);

			if (length == 0) {
				//noinspection unchecked
				return (T[]) Array.newInstance(expectedItemType, 0);
			}

			final Object[] iterableValues = new Object[length];
			for (int i = 0; i < length; i++) {
				iterableValues[i] = Array.get(values, i);
			}

			//noinspection unchecked
			return Arrays.stream(iterableValues)
				.map(itemTransformer)
				.toArray(size -> (T[]) Array.newInstance(expectedItemType, size));
		} else {
			throw new EvitaInvalidUsageException(
				"Expected value of iterable type or array but got `"
					+ this.actualValue.getClass().getName() + "`."
			);
		}
	}
}
