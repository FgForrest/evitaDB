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
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.StreamSupport;

/**
 * Represents EvitaQL single value or variadic array of values. It is wrapper for parsed values of supported data types, either literals or arguments.
 * Types of {@link EvitaDataTypes} plus iterables and arrays has first-class support. Variadic arrays can be arrays or iterables.
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

	@Nonnull
	private static <T> T variadicValueItemAsSpecificType(@Nonnull Object item, @Nonnull Class<T> type) {
		// correct passed type from client should be checked at visitor level, here should be should correct checked type
		// if everything is correct on parser side
		Assert.isPremiseValid(
			type.isInstance(item),
			"Expected variadic value items of type `" + type.getName() + "` but got `" + item.getClass().getName() + "`."
		);
		return type.cast(item);
	}

	private static void assertValueIsOfType(@Nonnull Object theValue, @Nonnull Class<?> type) {
		// correct passed type from client should be checked at visitor level, here should be should correct checked type
		// if everything is correct on parser side
		Assert.isPremiseValid(
			type.isInstance(theValue),
			"Expected value of type `" + type.getName() + "` but got `" + theValue.getClass().getName() + "`."
		);
	}

	private static <T extends Enum<T>> @Nonnull T valueAsEnum(@Nonnull Object theValue, @Nonnull Class<T> enumType) {
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

	@Nonnull
	private static <T> T asSpecificType(@Nonnull Object theValue, @Nonnull Class<T> type) {
		assertValueIsOfType(theValue, type);
		return type.cast(theValue);
	}

	public Value(@Nonnull Object actualValue) {
		this.actualValue = actualValue;
		this.type = actualValue.getClass();
	}

	@Nonnull
	public Serializable asSerializable() {
		return asSpecificType(this.actualValue, Serializable.class);
	}

	@Nonnull
	public Comparable<?> asComparable() {
		return asSpecificType(this.actualValue, Comparable.class);
	}

	@Nonnull
	public <T extends Serializable & Comparable<?>> T asSerializableAndComparable() {
		assertValueIsOfType(this.actualValue, Serializable.class);
		assertValueIsOfType(this.actualValue, Comparable.class);
		//noinspection unchecked
		return (T) this.actualValue;
	}

	@Nonnull
	public String asString() {
		if (this.actualValue instanceof final Character characterValue) {
			return characterValue.toString();
		}
		return asSpecificType(this.actualValue, String.class);
	}

	@Nonnull
	public Number asNumber() {
		return asSpecificType(this.actualValue, Number.class);
	}

	/**
	 * Casts original value to {@link Number} and tries to convert that value into desired type.
	 */
	@Nonnull
	public <T extends Number> T asNumber(@Nonnull Class<T> numberType) {
		return EvitaDataTypes.toTargetType(asNumber(), numberType);
	}

	public int asInt() {
		if (this.actualValue instanceof final Long longNumber) {
			try {
				return Math.toIntExact(longNumber);
			} catch (ArithmeticException e) {
				throw new EvitaInvalidUsageException(
					"`Long` number was passed when `Integer` desired but `" + this.actualValue + "` value is to big for `Integer`."
				);
			}
		}
		return asNumber().intValue();
	}

	public long asLong() {
		return asNumber().longValue();
	}

	public boolean asBoolean() {
		return asSpecificType(this.actualValue, Boolean.class);
	}

	@Nonnull
	public BigDecimal asBigDecimal() {
		return asSpecificType(this.actualValue, BigDecimal.class);
	}

	@Nonnull
	public OffsetDateTime asOffsetDateTime() {
		return asSpecificType(this.actualValue, OffsetDateTime.class);
	}

	@Nonnull
	public LocalDateTime asLocalDateTime() {
		return asSpecificType(this.actualValue, LocalDateTime.class);
	}

	@Nonnull
	public LocalDate asLocalDate() {
		return asSpecificType(this.actualValue, LocalDate.class);
	}

	@Nonnull
	public LocalTime asLocalTime() {
		return asSpecificType(this.actualValue, LocalTime.class);
	}

	@Nonnull
	public DateTimeRange asDateTimeRange() {
		return asSpecificType(this.actualValue, DateTimeRange.class);
	}

	@Nonnull
	public BigDecimalNumberRange asBigDecimalNumberRange() {
		return asSpecificType(this.actualValue, BigDecimalNumberRange.class);
	}

	@Nonnull
	public LongNumberRange asLongNumberRange() {
		return asSpecificType(this.actualValue, LongNumberRange.class);
	}

	@Nonnull
	public <T extends Enum<T>> T asEnum(@Nonnull Class<T> enumType) {
		return valueAsEnum(this.actualValue, enumType);
	}

	@Nonnull
	public <T extends Enum<T>> T[] asEnumArray(@Nonnull Class<T> enumType) {
		try {
			return asArray(
				v -> valueAsEnum(v, enumType),
				enumType
			);
		} catch (ClassCastException e) {
			throw new EvitaInvalidUsageException("Unexpected type of value array `" + this.actualValue.getClass().getName() + "`.");
		}
	}

	@Nonnull
	public Locale asLocale() {
		if (this.actualValue instanceof Locale) {
			return asSpecificType(this.actualValue, Locale.class);
		} else if (this.actualValue instanceof String) {
			return EvitaDataTypes.toTargetType(asSpecificType(this.actualValue, String.class), Locale.class);
		} else {
			// correct passed type from client should be checked at visitor level, here should be should correct checked type
			// if everything is correct on parser side
			throw new EvitaInvalidUsageException("Expected locale or string value but got `" + this.actualValue.getClass().getName() + "`.");
		}
	}

	@Nonnull
	public Currency asCurrency() {
		if (this.actualValue instanceof Currency) {
			return asSpecificType(this.actualValue, Currency.class);
		} else if (this.actualValue instanceof String) {
			return EvitaDataTypes.toTargetType(asSpecificType(this.actualValue, String.class), Currency.class);
		} else {
			throw new EvitaInvalidUsageException("Expected currency or string value but got `" + this.actualValue.getClass().getName() + "`.");
		}
	}

	@Nonnull
	public UUID asUuid() {
		if (this.actualValue instanceof UUID) {
			return asSpecificType(this.actualValue, UUID.class);
		} else if (this.actualValue instanceof String) {
			return EvitaDataTypes.toTargetType(asSpecificType(this.actualValue, String.class), UUID.class);
		} else {
			throw new EvitaInvalidUsageException("Expected UUID or string value but got `" + this.actualValue.getClass().getName() + "`.");
		}
	}

	@Nonnull
	public String[] asStringArray() {
		try {
			return asArray(
				v -> variadicValueItemAsSpecificType(v, String.class),
				String.class
			);
		} catch (ClassCastException e) {
			throw new EvitaInvalidUsageException("Unexpected type of value array `" + this.actualValue.getClass().getName() + "`.");
		}
	}

	@Nonnull
	public Serializable[] asSerializableArray() {
		try {
			return asArray(Serializable.class::cast, Serializable.class);
		} catch (ClassCastException e) {
			throw new EvitaInvalidUsageException("Unexpected type of value array `" + this.actualValue.getClass().getName() + "`.");
		}
	}

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
								"`Long` number was passed when `Integer` desired but `" + this.actualValue + "` value is to big for `Integer`."
							);
						}
					}
					return variadicValueItemAsSpecificType(v, Number.class).intValue();
				},
				Integer.class
			);
		} catch (ClassCastException e) {
			// correct passed type from client should be checked at visitor level, here should be should correct checked type
			// if everything is correct on parser side
			throw new EvitaInvalidUsageException("Unexpected type of value array `" + this.actualValue.getClass().getName() + "`.");
		}
	}

	@Nonnull
	public Locale[] asLocaleArray() {
		try {
			return asArray(
				v -> {
					if (v instanceof Locale) {
						return variadicValueItemAsSpecificType(v, Locale.class);
					} else if (v instanceof String) {
						return EvitaDataTypes.toTargetType(variadicValueItemAsSpecificType(v, String.class), Locale.class);
					} else {
						throw new EvitaInvalidUsageException("Expected variadic value items of type locale or string value but got `" + v.getClass().getName() + "`.");
					}
				},
				Locale.class
			);
		} catch (ClassCastException e) {
			throw new EvitaInvalidUsageException("Unexpected type of value array `" + this.actualValue.getClass().getName() + "`.");
		}
	}

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
			throw new EvitaInvalidUsageException("Expected value of iterable type or array but got `" + this.actualValue.getClass().getName() + "`.");
		}
	}
}
