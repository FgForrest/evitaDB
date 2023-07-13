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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.dataType;

import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.exception.InconvertibleDataTypeException;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.MemoryMeasuringConstants;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * This class contains validation logic for evitaDB data types.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class EvitaDataTypes {
	private static final Set<Class<?>> SUPPORTED_QUERY_DATA_TYPES;
	private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPING_TYPES;
	private static final char CHAR_STRING_DELIMITER = '\'';
	private static final String STRING_DELIMITER = "" + CHAR_STRING_DELIMITER;
	private static final Function<String, OffsetDateTime> PARSE_TO_OFFSET_DATE_TIME = string -> {
		try {
			return OffsetDateTime.parse(string, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	};
	private static final Function<String, LocalDateTime> PARSE_TO_LOCAL_DATE_TIME = string -> {
		try {
			return LocalDateTime.parse(string, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	};
	private static final Function<String, LocalDate> PARSE_TO_LOCAL_DATE = string -> {
		try {
			return LocalDate.parse(string, DateTimeFormatter.ISO_LOCAL_DATE);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	};
	private static final Function<String, LocalTime> PARSE_TO_LOCAL_TIME = string -> {
		try {
			return LocalTime.parse(string, DateTimeFormatter.ISO_LOCAL_TIME);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	};
	private static final BiFunction<Class<?>, Serializable, Number> BIG_DECIMAL_FUNCTION = (requestedType, unknownObject) -> {
		try {
			if (unknownObject instanceof Number) {
				return (Number) unknownObject;
			} else {
				return new BigDecimal(unknownObject.toString());
			}
		} catch (ArithmeticException | NumberFormatException ex) {
			throw new InconvertibleDataTypeException(requestedType, unknownObject);
		}
	};
	private static final BiFunction<Supplier<Number>, Supplier<InconvertibleDataTypeException>, Number> WRAPPING_FUNCTION = (number, exception) -> {
		try {
			return number.get();
		} catch (ArithmeticException ex) {
			throw exception.get();
		}
	};
	private static final BiFunction<Class<?>, Serializable, Boolean> BOOLEAN_FUNCTION = (requestedType, unknownObject) -> {
		if (unknownObject instanceof Boolean) {
			return (Boolean) unknownObject;
		} else if (unknownObject instanceof Number) {
			return Objects.equals(1L, ((Number) unknownObject).longValue());
		} else {
			return Boolean.parseBoolean(unknownObject.toString());
		}
	};
	private static final BiFunction<Class<?>, Serializable, Character> CHAR_FUNCTION = (requestedType, unknownObject) -> {
		if (unknownObject instanceof Character) {
			return (Character) unknownObject;
		} else if (unknownObject instanceof Number) {
			return (char) ((byte) WRAPPING_FUNCTION.apply(() -> NumberUtils.convertToByte(BIG_DECIMAL_FUNCTION.apply(requestedType, unknownObject)), () -> new InconvertibleDataTypeException(requestedType, unknownObject)));
		} else {
			final String str = unknownObject.toString();
			if (str.length() == 1) {
				return str.charAt(0);
			} else {
				throw new InconvertibleDataTypeException(requestedType, unknownObject);
			}
		}
	};
	private static final BiFunction<Class<?>, Serializable, Currency> CURRENCY_FUNCTION = (requestedType, unknownObject) -> {
		try {
			return Currency.getInstance(unknownObject.toString());
		} catch (IllegalArgumentException ignored) {
			throw new InconvertibleDataTypeException(requestedType, unknownObject);
		}
	};
	private static final BiFunction<Class<?>, Serializable, Locale> LOCALE_FUNCTION = (requestedType, unknownObject) -> {
		if (unknownObject instanceof Locale) {
			return (Locale) unknownObject;
		} else {
			final String localeString = unknownObject.toString();
			Assert.isTrue(!localeString.isEmpty(), () -> new InconvertibleDataTypeException(requestedType, unknownObject));

			final Locale locale = Locale.forLanguageTag(localeString);
			Assert.isTrue(!locale.getLanguage().isEmpty(), () -> new InconvertibleDataTypeException(requestedType, unknownObject));

			try {
				Assert.notNull(locale.getISO3Language(), () -> new InconvertibleDataTypeException(requestedType, unknownObject));
			} catch (MissingResourceException ex) {
				throw new InconvertibleDataTypeException(requestedType, unknownObject);
			}
			return locale;
		}
	};
	private static final BiFunction<Class<?>, Serializable, OffsetDateTime> OFFSET_DATE_TIME_FUNCTION = (requestedType, unknownObject) -> {
		try {
			if (unknownObject instanceof OffsetDateTime) {
				return (OffsetDateTime) unknownObject;
			} else if (unknownObject instanceof LocalDateTime) {
				return ((LocalDateTime) unknownObject).atOffset(ZoneOffset.UTC);
			} else if (unknownObject instanceof LocalDate) {
				return ((LocalDate) unknownObject).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
			} else {
				final String value = unknownObject.toString();
				final OffsetDateTime parsedZoneDateTime = PARSE_TO_OFFSET_DATE_TIME.apply(value);
				if (parsedZoneDateTime != null) {
					return parsedZoneDateTime;
				}
				final LocalDateTime parsedLocalDateTime = PARSE_TO_LOCAL_DATE_TIME.apply(value);
				if (parsedLocalDateTime != null) {
					return parsedLocalDateTime.atOffset(ZoneOffset.UTC);
				}
				final LocalDate parsedLocalDate = PARSE_TO_LOCAL_DATE.apply(value);
				if (parsedLocalDate != null) {
					return parsedLocalDate.atTime(LocalTime.MIDNIGHT).atOffset(ZoneOffset.UTC);
				}
				throw new InconvertibleDataTypeException(requestedType, unknownObject);
			}
		} catch (IllegalArgumentException ignored) {
			throw new InconvertibleDataTypeException(requestedType, unknownObject);
		}
	};
	private static final BiFunction<Class<?>, Serializable, DateTimeRange> DATE_TIME_RANGE_FUNCTION = (requestedType, unknownObject) -> {
		if (unknownObject instanceof DateTimeRange) {
			return (DateTimeRange) unknownObject;
		} else {
			final String value = unknownObject.toString();
			final String[] parsedResult = DateTimeRange.PARSE_FCT.apply(value);
			if (parsedResult != null) {
				if (parsedResult[0] == null && parsedResult[1] != null) {
					final OffsetDateTime to = OFFSET_DATE_TIME_FUNCTION.apply(DateTimeRange.class, parsedResult[1]);
					return DateTimeRange.until(to);
				} else if (parsedResult[0] != null && parsedResult[1] == null) {
					final OffsetDateTime from = OFFSET_DATE_TIME_FUNCTION.apply(DateTimeRange.class, parsedResult[0]);
					return DateTimeRange.since(from);
				} else {
					final OffsetDateTime from = OFFSET_DATE_TIME_FUNCTION.apply(DateTimeRange.class, parsedResult[0]);
					final OffsetDateTime to = OFFSET_DATE_TIME_FUNCTION.apply(DateTimeRange.class, parsedResult[1]);
					return DateTimeRange.between(from, to);
				}
			}
			throw new InconvertibleDataTypeException(requestedType, unknownObject);
		}
	};
	private static final BiFunction<Class<?>, Serializable, LocalDateTime> LOCAL_DATE_TIME_FUNCTION = (requestedType, unknownObject) -> {
		try {
			if (unknownObject instanceof LocalDateTime) {
				return (LocalDateTime) unknownObject;
			} else if (unknownObject instanceof OffsetDateTime) {
				return ((OffsetDateTime) unknownObject).toLocalDateTime();
			} else if (unknownObject instanceof LocalDate) {
				return ((LocalDate) unknownObject).atStartOfDay();
			} else {
				final String value = unknownObject.toString();
				final LocalDateTime parsedLocalDateTime = PARSE_TO_LOCAL_DATE_TIME.apply(value);
				if (parsedLocalDateTime != null) {
					return parsedLocalDateTime;
				}
				final OffsetDateTime parsedZoneDateTime = PARSE_TO_OFFSET_DATE_TIME.apply(value);
				if (parsedZoneDateTime != null) {
					return parsedZoneDateTime.toLocalDateTime();
				}
				final LocalDate parsedLocalDate = PARSE_TO_LOCAL_DATE.apply(value);
				if (parsedLocalDate != null) {
					return parsedLocalDate.atStartOfDay();
				}
				throw new InconvertibleDataTypeException(requestedType, unknownObject);
			}
		} catch (IllegalArgumentException ignored) {
			throw new InconvertibleDataTypeException(requestedType, unknownObject);
		}
	};
	private static final BiFunction<Class<?>, Serializable, LocalDate> LOCAL_DATE_FUNCTION = (requestedType, unknownObject) -> {
		try {
			if (unknownObject instanceof LocalDate) {
				return (LocalDate) unknownObject;
			} else if (unknownObject instanceof OffsetDateTime) {
				return ((OffsetDateTime) unknownObject).toLocalDate();
			} else if (unknownObject instanceof LocalDateTime) {
				return ((LocalDateTime) unknownObject).toLocalDate();
			} else {
				final String value = unknownObject.toString();
				final LocalDate parsedLocalDate = PARSE_TO_LOCAL_DATE.apply(value);
				if (parsedLocalDate != null) {
					return parsedLocalDate;
				}
				final OffsetDateTime parsedZoneDateTime = PARSE_TO_OFFSET_DATE_TIME.apply(value);
				if (parsedZoneDateTime != null) {
					return parsedZoneDateTime.toLocalDate();
				}
				final LocalDateTime parsedLocalDateTime = PARSE_TO_LOCAL_DATE_TIME.apply(value);
				if (parsedLocalDateTime != null) {
					return parsedLocalDateTime.toLocalDate();
				}
				throw new InconvertibleDataTypeException(requestedType, unknownObject);
			}
		} catch (IllegalArgumentException ignored) {
			throw new InconvertibleDataTypeException(requestedType, unknownObject);
		}
	};
	private static final BiFunction<Class<?>, Serializable, LocalTime> LOCAL_TIME_FUNCTION = (requestedType, unknownObject) -> {
		try {
			if (unknownObject instanceof LocalTime) {
				return (LocalTime) unknownObject;
			} else if (unknownObject instanceof OffsetDateTime) {
				return ((OffsetDateTime) unknownObject).toLocalTime();
			} else if (unknownObject instanceof LocalDateTime) {
				return ((LocalDateTime) unknownObject).toLocalTime();
			} else {
				final String value = unknownObject.toString();
				final LocalTime parsedLocalTime = PARSE_TO_LOCAL_TIME.apply(value);
				if (parsedLocalTime != null) {
					return parsedLocalTime;
				}
				final OffsetDateTime parsedZoneDateTime = PARSE_TO_OFFSET_DATE_TIME.apply(value);
				if (parsedZoneDateTime != null) {
					return parsedZoneDateTime.toLocalTime();
				}
				final LocalDateTime parsedLocalDateTime = PARSE_TO_LOCAL_DATE_TIME.apply(value);
				if (parsedLocalDateTime != null) {
					return parsedLocalDateTime.toLocalTime();
				}
				throw new InconvertibleDataTypeException(requestedType, unknownObject);
			}
		} catch (IllegalArgumentException ignored) {
			throw new InconvertibleDataTypeException(requestedType, unknownObject);
		}
	};
	private static final BiFunction<TypeWithPrecision, Serializable, NumberRange<?>> NUMBER_RANGE_FUNCTION = (typeWithPrecision, unknownObject) -> {
		try {
			if (unknownObject instanceof NumberRange) {
				return (NumberRange<?>) unknownObject;
			} else {
				final String value = unknownObject.toString();
				final String[] parsedResult = NumberRange.PARSE_FCT.apply(value);
				if (parsedResult != null) {
					if (parsedResult[0] == null && parsedResult[1] != null) {
						final Number to = BIG_DECIMAL_FUNCTION.apply(BigDecimal.class, parsedResult[1]);
						if (typeWithPrecision.requestedType().equals(BigDecimalNumberRange.class)) {
							final BigDecimal bigDecimalTo = (BigDecimal) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToBigDecimal(to),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
							);
							return BigDecimalNumberRange.to(bigDecimalTo, typeWithPrecision.precision());
						} else if (typeWithPrecision.requestedType.equals(LongNumberRange.class)) {
							final Long longTo = (Long) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToLong(to),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
							);
							return LongNumberRange.to(longTo);
						} else if (typeWithPrecision.requestedType.equals(IntegerNumberRange.class)) {
							final Integer integerTo = (Integer) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToInt(to),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
							);
							return IntegerNumberRange.to(integerTo);
						} else if (typeWithPrecision.requestedType.equals(ShortNumberRange.class)) {
							final Short shortTo = (Short) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToShort(to),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
							);
							return ShortNumberRange.to(shortTo);
						} else if (typeWithPrecision.requestedType.equals(ByteNumberRange.class)) {
							final Byte byteTo = (Byte) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToByte(to),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
							);
							return ByteNumberRange.to(byteTo);
						} else {
							if (typeWithPrecision.precision() == 0) {
								final Long longTo = (Long) WRAPPING_FUNCTION.apply(
									() -> NumberUtils.convertToLong(to),
									() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
								);
								return LongNumberRange.to(longTo);
							} else {
								final BigDecimal bigDecimalTo = (BigDecimal) WRAPPING_FUNCTION.apply(
									() -> NumberUtils.convertToBigDecimal(to),
									() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
								);
								return BigDecimalNumberRange.to(bigDecimalTo, typeWithPrecision.precision());
							}
						}
					} else if (parsedResult[0] != null && parsedResult[1] == null) {
						final Number from = BIG_DECIMAL_FUNCTION.apply(BigDecimal.class, parsedResult[0]);
						if (typeWithPrecision.requestedType().equals(BigDecimalNumberRange.class)) {
							final BigDecimal bigDecimalFrom = (BigDecimal) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToBigDecimal(from),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
							);
							return BigDecimalNumberRange.from(bigDecimalFrom, typeWithPrecision.precision());
						} else if (typeWithPrecision.requestedType.equals(LongNumberRange.class)) {
							final Long longFrom = (Long) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToLong(from),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
							);
							return LongNumberRange.from(longFrom);
						} else if (typeWithPrecision.requestedType.equals(IntegerNumberRange.class)) {
							final Integer integerFrom = (Integer) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToInt(from),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
							);
							return IntegerNumberRange.from(integerFrom);
						} else if (typeWithPrecision.requestedType.equals(ShortNumberRange.class)) {
							final Short shortFrom = (Short) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToShort(from),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
							);
							return ShortNumberRange.from(shortFrom);
						} else if (typeWithPrecision.requestedType.equals(ByteNumberRange.class)) {
							final Byte byteFrom = (Byte) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToByte(from),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
							);
							return ByteNumberRange.from(byteFrom);
						} else {
							if (typeWithPrecision.precision() == 0) {
								final Long longFrom = (Long) WRAPPING_FUNCTION.apply(
									() -> NumberUtils.convertToLong(from),
									() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
								);
								return LongNumberRange.from(longFrom);
							} else {
								final BigDecimal bigDecimalFrom = (BigDecimal) WRAPPING_FUNCTION.apply(
									() -> NumberUtils.convertToBigDecimal(from),
									() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
								);
								return BigDecimalNumberRange.from(bigDecimalFrom, typeWithPrecision.precision());
							}
						}
					} else {
						final Number from = BIG_DECIMAL_FUNCTION.apply(NumberRange.class, parsedResult[0]);
						final Number to = BIG_DECIMAL_FUNCTION.apply(NumberRange.class, parsedResult[1]);
						if (typeWithPrecision.requestedType().equals(BigDecimalNumberRange.class)) {
							final BigDecimal bigDecimalFrom = (BigDecimal) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToBigDecimal(from),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
							);
							final BigDecimal bigDecimalTo = (BigDecimal) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToBigDecimal(to),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
							);
							return BigDecimalNumberRange.between(bigDecimalFrom, bigDecimalTo, typeWithPrecision.precision());
						} else if (typeWithPrecision.requestedType.equals(LongNumberRange.class)) {
							final Long longFrom = (Long) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToLong(from),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
							);
							final Long longTo = (Long) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToLong(to),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
							);
							return LongNumberRange.between(longFrom, longTo);
						} else if (typeWithPrecision.requestedType.equals(IntegerNumberRange.class)) {
							final Integer integerFrom = (Integer) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToInt(from),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
							);
							final Integer integerTo = (Integer) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToInt(to),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
							);
							return IntegerNumberRange.between(integerFrom, integerTo);
						} else if (typeWithPrecision.requestedType.equals(ShortNumberRange.class)) {
							final Short shortFrom = (Short) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToShort(from),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
							);
							final Short shortTo = (Short) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToShort(to),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
							);
							return ShortNumberRange.between(shortFrom, shortTo);
						} else if (typeWithPrecision.requestedType.equals(ByteNumberRange.class)) {
							final Byte byteFrom = (Byte) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToByte(from),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
							);
							final Byte byteTo = (Byte) WRAPPING_FUNCTION.apply(
								() -> NumberUtils.convertToByte(to),
								() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
							);
							return ByteNumberRange.between(byteFrom, byteTo);
						} else {
							if (typeWithPrecision.precision() == 0) {
								final Long longFrom = (Long) WRAPPING_FUNCTION.apply(
									() -> NumberUtils.convertToLong(from),
									() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
								);
								final Long longTo = (Long) WRAPPING_FUNCTION.apply(
									() -> NumberUtils.convertToLong(to),
									() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
								);
								return LongNumberRange.between(longFrom, longTo);
							} else {
								final BigDecimal bigDecimalFrom = (BigDecimal) WRAPPING_FUNCTION.apply(
									() -> NumberUtils.convertToBigDecimal(from),
									() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
								);
								final BigDecimal bigDecimalTo = (BigDecimal) WRAPPING_FUNCTION.apply(
									() -> NumberUtils.convertToBigDecimal(to),
									() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
								);
								return BigDecimalNumberRange.between(bigDecimalFrom, bigDecimalTo, typeWithPrecision.precision());
							}
						}
					}
				}
				throw new InconvertibleDataTypeException(typeWithPrecision.requestedType(), unknownObject);
			}
		} catch (IllegalArgumentException ignored) {
			throw new InconvertibleDataTypeException(typeWithPrecision.requestedType(), unknownObject);
		}
	};

	static {
		final LinkedHashSet<Class<?>> queryDataTypes = new LinkedHashSet<>();
		queryDataTypes.add(String.class);
		queryDataTypes.add(byte.class);
		queryDataTypes.add(Byte.class);
		queryDataTypes.add(short.class);
		queryDataTypes.add(Short.class);
		queryDataTypes.add(int.class);
		queryDataTypes.add(Integer.class);
		queryDataTypes.add(long.class);
		queryDataTypes.add(Long.class);
		queryDataTypes.add(boolean.class);
		queryDataTypes.add(Boolean.class);
		queryDataTypes.add(char.class);
		queryDataTypes.add(Character.class);
		queryDataTypes.add(BigDecimal.class);
		queryDataTypes.add(OffsetDateTime.class);
		queryDataTypes.add(LocalDateTime.class);
		queryDataTypes.add(LocalDate.class);
		queryDataTypes.add(LocalTime.class);
		queryDataTypes.add(DateTimeRange.class);
		queryDataTypes.add(BigDecimalNumberRange.class);
		queryDataTypes.add(LongNumberRange.class);
		queryDataTypes.add(IntegerNumberRange.class);
		queryDataTypes.add(ShortNumberRange.class);
		queryDataTypes.add(ByteNumberRange.class);
		queryDataTypes.add(Locale.class);
		queryDataTypes.add(Currency.class);
		SUPPORTED_QUERY_DATA_TYPES = Collections.unmodifiableSet(queryDataTypes);

		final LinkedHashMap<Class<?>, Class<?>> primitiveWrappers = new LinkedHashMap<>();
		primitiveWrappers.put(boolean.class, Boolean.class);
		primitiveWrappers.put(byte.class, Byte.class);
		primitiveWrappers.put(short.class, Short.class);
		primitiveWrappers.put(int.class, Integer.class);
		primitiveWrappers.put(long.class, Long.class);
		primitiveWrappers.put(char.class, Character.class);
		PRIMITIVE_WRAPPING_TYPES = Collections.unmodifiableMap(primitiveWrappers);
	}

	/**
	 * Returns set of all supported data types in evitaDB.
	 */
	public static Set<Class<?>> getSupportedDataTypes() {
		return SUPPORTED_QUERY_DATA_TYPES;
	}

	/**
	 * Returns true if type is directly supported by evitaDB.
	 */
	public static boolean isSupportedType(@Nonnull Class<?> type) {
		return SUPPORTED_QUERY_DATA_TYPES.contains(type);
	}

	/**
	 * Returns true if type (may be array type) is directly supported by evitaDB.
	 */
	public static boolean isSupportedTypeOrItsArray(@Nonnull Class<?> type) {
		@SuppressWarnings("unchecked") final Class<? extends Serializable> typeToCheck = type.isArray() ? (Class<? extends Serializable>) type.getComponentType() : (Class<? extends Serializable>) type;
		return EvitaDataTypes.isSupportedType(typeToCheck);
	}

	/**
	 * If passed type is a primitive type or array of primitive types, the wrapper type or array of wrapper types
	 * is returned in response.
	 */
	public static Class<? extends Serializable> toWrappedForm(@Nonnull Class<?> type) {
		@SuppressWarnings("unchecked") final Class<? extends Serializable> typeToCheck = type.isArray() ? (Class<? extends Serializable>) type.getComponentType() : (Class<? extends Serializable>) type;
		if (typeToCheck.isPrimitive()) {
			//noinspection unchecked
			return type.isArray() ?
				(Class<? extends Serializable>) Array.newInstance(getWrappingPrimitiveClass(typeToCheck), 0).getClass() :
				getWrappingPrimitiveClass(typeToCheck);
		} else {
			//noinspection unchecked
			return (Class<? extends Serializable>) type;
		}
	}

	/**
	 * Method validates input value for use in Evita query.
	 *
	 * @return possible converted object to known type
	 * @throws UnsupportedDataTypeException if non supported type is used
	 */
	public static Serializable toSupportedType(@Nullable Serializable unknownObject) throws UnsupportedDataTypeException {
		if (unknownObject == null) {
			// nulls are allowed
			return null;
		} else if (unknownObject instanceof Float || unknownObject instanceof Double) {
			// normalize floats and doubles to big decimal
			return new BigDecimal(unknownObject.toString());
		} else if (unknownObject instanceof LocalDateTime) {
			// always convert local date time to zoned date time
			return ((LocalDateTime) unknownObject).atOffset(ZoneOffset.UTC);
		} else if (unknownObject.getClass().isEnum()) {
			return unknownObject;
		} else if (SUPPORTED_QUERY_DATA_TYPES.contains(unknownObject.getClass())) {
			return unknownObject;
		} else {
			throw new UnsupportedDataTypeException(unknownObject.getClass(), SUPPORTED_QUERY_DATA_TYPES);
		}
	}

	/**
	 * Method converts unknown object to the requested type supported by by Evita.
	 *
	 * @return unknownObject converted to requested type
	 * @throws UnsupportedDataTypeException when unknownObject cannot be converted to any of Evita supported types
	 */
	@Nullable
	public static <T extends Serializable> T toTargetType(@Nullable Serializable unknownObject, @Nonnull Class<T> requestedType) throws UnsupportedDataTypeException {
		return toTargetType(unknownObject, requestedType, 0);
	}

	/**
	 * Method converts unknown object to the requested type supported by Evita.
	 *
	 * @return unknownObject converted to requested type
	 * @throws UnsupportedDataTypeException when unknownObject cannot be converted to any of Evita supported types
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public static <T extends Serializable> T toTargetType(@Nullable Serializable unknownObject, @Nonnull Class<T> requestedType, int allowedDecimalPlaces) throws UnsupportedDataTypeException {
		if (requestedType.isInstance(unknownObject)) {
			return (T) unknownObject;
		}

		final Class<?> baseRequestedType;
		if (requestedType.isArray()) {
			baseRequestedType = requestedType.getComponentType().isPrimitive() ? getWrappingPrimitiveClass(requestedType.getComponentType()) : requestedType.getComponentType();
		} else {
			baseRequestedType = requestedType.isPrimitive() ? getWrappingPrimitiveClass(requestedType) : requestedType;
		}
		Assert.isTrue(isSupportedType(baseRequestedType) || baseRequestedType.isEnum(), "Requested type `" + requestedType + "` is not supported by Evita!");
		if (requestedType.isInstance(unknownObject) || unknownObject == null) {
			return (T) unknownObject;
		}

		final Object result;
		if (unknownObject.getClass().isArray()) {
			final int inputArrayLength = Array.getLength(unknownObject);
			result = Array.newInstance(baseRequestedType, inputArrayLength);
			for (int i = 0; i < inputArrayLength; i++) {
				Array.set(
					result, i,
					convertSingleObject(
						(Serializable) Array.get(unknownObject, i),
						baseRequestedType,
						allowedDecimalPlaces
					)
				);
			}
		} else {
			result = convertSingleObject(unknownObject, baseRequestedType, allowedDecimalPlaces);
		}

		if (requestedType.isArray()) {
			if (result.getClass().isArray()) {
				return (T) result;
			} else {
				final Object wrappedResult = Array.newInstance(baseRequestedType, 1);
				Array.set(wrappedResult, 0, result);
				return (T) wrappedResult;
			}
		} else {
			return (T) result;
		}
	}

	/**
	 * Method formats the value for printing in the Evita query.
	 */
	@Nonnull
	public static String formatValue(@Nullable Serializable value) {
		if (value instanceof String) {
			return CHAR_STRING_DELIMITER + ((String) value).replaceAll(STRING_DELIMITER, "\\\\'") + STRING_DELIMITER;
		} else if (value instanceof Character) {
			return CHAR_STRING_DELIMITER + ((Character) value).toString().replaceAll(STRING_DELIMITER, "\\\\'") + STRING_DELIMITER;
		} else if (value instanceof Number) {
			return value.toString();
		} else if (value instanceof Boolean) {
			return value.toString();
		} else if (value instanceof Range) {
			return value.toString();
		} else if (value instanceof OffsetDateTime) {
			return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format((TemporalAccessor) value);
		} else if (value instanceof LocalDateTime) {
			return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format((TemporalAccessor) value);
		} else if (value instanceof LocalDate) {
			return DateTimeFormatter.ISO_LOCAL_DATE.format((TemporalAccessor) value);
		} else if (value instanceof LocalTime) {
			return DateTimeFormatter.ISO_LOCAL_TIME.format((TemporalAccessor) value);
		} else if (value instanceof Locale) {
			return CHAR_STRING_DELIMITER + ((Locale) value).toLanguageTag() + CHAR_STRING_DELIMITER;
		} else if (value instanceof Currency) {
			return CHAR_STRING_DELIMITER + value.toString() + CHAR_STRING_DELIMITER;
		} else if (value instanceof Enum) {
			return value.toString();
		} else if (value == null) {
			throw new EvitaInternalError(
				"Null argument value should never ever happen. Null values are excluded in constructor of the class!"
			);
		}
		throw new UnsupportedDataTypeException(value.getClass(), EvitaDataTypes.getSupportedDataTypes());
	}

	/**
	 * Method returns wrapping class for primitive type.
	 */
	public static <T> Class<T> getWrappingPrimitiveClass(Class<T> type) {
		final Class<?> wrappingClass = PRIMITIVE_WRAPPING_TYPES.get(type);
		Assert.notNull(wrappingClass, "Class " + type + " is not a primitive class!");
		//noinspection unchecked
		return (Class<T>) wrappingClass;
	}

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	public static int estimateSize(@Nullable Serializable unknownObject) {
		if (unknownObject == null) {
			return 0;
		} else if (unknownObject.getClass().isArray()) {
			int size = MemoryMeasuringConstants.ARRAY_BASE_SIZE +
				Array.getLength(unknownObject) * MemoryMeasuringConstants.getElementSize(unknownObject.getClass().getComponentType());
			for (int i = 0; i < Array.getLength(unknownObject); i++) {
				size += EvitaDataTypes.estimateSize((Serializable) Array.get(unknownObject, i));
			}
			return size;
		} else if (unknownObject instanceof String s) {
			return MemoryMeasuringConstants.computeStringSize(s);
		} else if (unknownObject instanceof Byte) {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE + MemoryMeasuringConstants.BYTE_SIZE;
		} else if (unknownObject instanceof Short) {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE + MemoryMeasuringConstants.SMALL_SIZE;
		} else if (unknownObject instanceof Integer) {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE + MemoryMeasuringConstants.INT_SIZE;
		} else if (unknownObject instanceof Long) {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE + MemoryMeasuringConstants.LONG_SIZE;
		} else if (unknownObject instanceof Boolean) {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE + MemoryMeasuringConstants.BYTE_SIZE;
		} else if (unknownObject instanceof Character) {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE + MemoryMeasuringConstants.CHAR_SIZE;
		} else if (unknownObject instanceof BigDecimal) {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE + MemoryMeasuringConstants.BIG_DECIMAL_SIZE;
		} else if (unknownObject instanceof OffsetDateTime) {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE + MemoryMeasuringConstants.LOCAL_DATE_TIME_SIZE + MemoryMeasuringConstants.REFERENCE_SIZE;
		} else if (unknownObject instanceof LocalDateTime) {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE + MemoryMeasuringConstants.LOCAL_DATE_TIME_SIZE;
		} else if (unknownObject instanceof LocalDate) {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE + MemoryMeasuringConstants.LOCAL_DATE_SIZE;
		} else if (unknownObject instanceof LocalTime) {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE + MemoryMeasuringConstants.LOCAL_TIME_SIZE;
		} else if (unknownObject instanceof DateTimeRange) {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
				2 * (MemoryMeasuringConstants.OBJECT_HEADER_SIZE + MemoryMeasuringConstants.LOCAL_DATE_TIME_SIZE + MemoryMeasuringConstants.REFERENCE_SIZE) +
				2 * (MemoryMeasuringConstants.LONG_SIZE);
		} else if (unknownObject instanceof final NumberRange numberRange) {
			final Number innerDataType = Optional.ofNullable(numberRange.getPreciseFrom())
				.orElseGet(numberRange::getPreciseTo);
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE
				+ 2 * (innerDataType == null ? 0 : estimateSize(innerDataType)) +
				MemoryMeasuringConstants.REFERENCE_SIZE + MemoryMeasuringConstants.INT_SIZE +
				2 * (MemoryMeasuringConstants.LONG_SIZE);
		} else if (unknownObject instanceof Locale) {
			return 0;
		} else if (unknownObject instanceof Enum) {
			return 0;
		} else if (unknownObject instanceof Currency) {
			return 0;
		} else if (unknownObject instanceof final ComplexDataObject complexDataObject) {
			return MemoryMeasuringConstants.REFERENCE_SIZE + complexDataObject.estimateSize();
		} else if (unknownObject instanceof final DataItem dataItem) {
			return MemoryMeasuringConstants.REFERENCE_SIZE + dataItem.estimateSize();
		} else {
			throw new UnsupportedDataTypeException(unknownObject.getClass(), SUPPORTED_QUERY_DATA_TYPES);
		}
	}

	/**
	 * Converts single (non-array) `unknownObject` to `requestedType` using `allowedDecimalPlaces` precision or
	 * throws an exception.
	 */
	private static Object convertSingleObject(@Nonnull Serializable unknownObject, Class<?> requestedType, int allowedDecimalPlaces) {
		final Object result;
		if (String.class.isAssignableFrom(requestedType)) {
			result = unknownObject.toString();
		} else if (Byte.class.isAssignableFrom(requestedType)) {
			result = WRAPPING_FUNCTION.apply(() -> NumberUtils.convertToByte(BIG_DECIMAL_FUNCTION.apply(requestedType, unknownObject)), () -> new InconvertibleDataTypeException(requestedType, unknownObject));
		} else if (Short.class.isAssignableFrom(requestedType)) {
			result = WRAPPING_FUNCTION.apply(() -> NumberUtils.convertToShort(BIG_DECIMAL_FUNCTION.apply(requestedType, unknownObject)), () -> new InconvertibleDataTypeException(requestedType, unknownObject));
		} else if (Integer.class.isAssignableFrom(requestedType)) {
			result = WRAPPING_FUNCTION.apply(() -> NumberUtils.convertToInt(BIG_DECIMAL_FUNCTION.apply(requestedType, unknownObject)), () -> new InconvertibleDataTypeException(requestedType, unknownObject));
		} else if (Long.class.isAssignableFrom(requestedType)) {
			result = WRAPPING_FUNCTION.apply(() -> NumberUtils.convertToLong(BIG_DECIMAL_FUNCTION.apply(requestedType, unknownObject)), () -> new InconvertibleDataTypeException(requestedType, unknownObject));
		} else if (BigDecimal.class.isAssignableFrom(requestedType)) {
			result = NumberUtils.convertToBigDecimal(BIG_DECIMAL_FUNCTION.apply(requestedType, unknownObject));
		} else if (Boolean.class.isAssignableFrom(requestedType)) {
			result = BOOLEAN_FUNCTION.apply(requestedType, unknownObject);
		} else if (Character.class.isAssignableFrom(requestedType)) {
			result = CHAR_FUNCTION.apply(requestedType, unknownObject);
		} else if (OffsetDateTime.class.isAssignableFrom(requestedType)) {
			result = OFFSET_DATE_TIME_FUNCTION.apply(requestedType, unknownObject);
		} else if (LocalDateTime.class.isAssignableFrom(requestedType)) {
			result = LOCAL_DATE_TIME_FUNCTION.apply(requestedType, unknownObject);
		} else if (LocalDate.class.isAssignableFrom(requestedType)) {
			result = LOCAL_DATE_FUNCTION.apply(requestedType, unknownObject);
		} else if (LocalTime.class.isAssignableFrom(requestedType)) {
			result = LOCAL_TIME_FUNCTION.apply(requestedType, unknownObject);
		} else if (DateTimeRange.class.isAssignableFrom(requestedType)) {
			result = DATE_TIME_RANGE_FUNCTION.apply(requestedType, unknownObject);
		} else if (NumberRange.class.isAssignableFrom(requestedType)) {
			result = NUMBER_RANGE_FUNCTION.apply(new TypeWithPrecision(requestedType, allowedDecimalPlaces), unknownObject);
		} else if (Locale.class.isAssignableFrom(requestedType)) {
			result = LOCALE_FUNCTION.apply(requestedType, unknownObject);
		} else if (Currency.class.isAssignableFrom(requestedType)) {
			result = CURRENCY_FUNCTION.apply(requestedType, unknownObject);
		} else if (requestedType.isEnum()) {
			//noinspection unchecked,rawtypes
			result = Enum.valueOf((Class<? extends Enum>) requestedType, unknownObject.toString());
		} else {
			throw new UnsupportedDataTypeException(unknownObject.getClass(), SUPPORTED_QUERY_DATA_TYPES);
		}
		return result;
	}

	private EvitaDataTypes() {
	}

	private record TypeWithPrecision(Class<?> requestedType, int precision) {
	}

}
