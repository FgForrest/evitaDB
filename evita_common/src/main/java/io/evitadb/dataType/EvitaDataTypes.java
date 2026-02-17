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

import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.exception.InconvertibleDataTypeException;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.GenericEvitaInternalError;
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
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static io.evitadb.utils.MemoryMeasuringConstants.*;

/**
 * Central utility class for evitaDB's type system. This class defines and enforces the set of
 * supported data types for attribute values, query parameters, and entity references, and provides
 * comprehensive type conversion, validation, and formatting capabilities.
 *
 * **Supported Data Types:**
 *
 * - **Primitives and wrappers:** byte, Byte, short, Short, int, Integer, long, Long, boolean,
 * Boolean, char, Character
 * - **Numeric types:** BigDecimal (Float and Double are automatically normalized to BigDecimal)
 * - **Date/time types:** OffsetDateTime, LocalDateTime, LocalDate, LocalTime
 * - **Range types:** DateTimeRange, BigDecimalNumberRange, LongNumberRange, IntegerNumberRange,
 * ShortNumberRange, ByteNumberRange
 * - **Locale and currency:** Locale, Currency
 * - **Identifiers:** UUID, String
 * - **evitaDB-specific:** Predecessor, ReferencedEntityPredecessor, Expression
 * - **Enums:** Types annotated with `@SupportedEnum` are supported; others are converted to
 * string names
 *
 * **Primary Functions:**
 *
 * 1. **Type Validation:** {@link #isSupportedType(Class)}, {@link #isSupportedTypeOrItsArray},
 * {@link #isSupportedTypeOrItsArrayOrEnum} - Check if a type is supported by evitaDB
 * 2. **Type Conversion:** {@link #toTargetType(Serializable, Class)} - Convert values between
 * supported types with automatic normalization (e.g., Float → BigDecimal, LocalDateTime →
 * OffsetDateTime at UTC, string parsing for dates/ranges)
 * 3. **Type Normalization:** {@link #toSupportedType(Serializable)} - Validate and normalize input
 * values for query processing
 * 4. **Value Formatting:** {@link #formatValue(Serializable)} - Format values for EvitaQL query
 * syntax with appropriate quoting and escaping
 * 5. **Memory Estimation:** {@link #estimateSize(Serializable)} - Estimate in-memory size for
 * cache sizing and performance optimization
 *
 * **Design Philosophy:**
 *
 * - **Precision over performance:** Uses BigDecimal for floating-point to avoid precision loss
 * - **UTC normalization:** Converts LocalDateTime to OffsetDateTime at UTC to avoid timezone
 * ambiguities
 * - **Null-safe:** Returns null unchanged rather than throwing exceptions
 * - **Flexible parsing:** Date/time converters try multiple ISO-8601 formats before failing
 * - **Array support:** All single-valued types also support array forms (e.g., Integer[])
 *
 * **Thread Safety:** This class is stateless and thread-safe. All methods are static.
 *
 * **Usage Examples:**
 *
 * ```java
 * // Validate type support
 * boolean supported = EvitaDataTypes.isSupportedType(BigDecimal.class); // true
 *
 * // Convert string to typed value
 * Integer value = EvitaDataTypes.toTargetType("42", Integer.class); // 42
 *
 * // Parse date from string
 * OffsetDateTime date = EvitaDataTypes.toTargetType(
 * "2021-01-30T14:45:16+01:00", OffsetDateTime.class
 * );
 *
 * // Format value for EvitaQL
 * String formatted = EvitaDataTypes.formatValue("it's"); // 'it\'s'
 *
 * // Estimate memory size
 * int size = EvitaDataTypes.estimateSize(BigDecimal.valueOf(123.45));
 * ```
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class EvitaDataTypes {
	/**
	 * Unmodifiable set of all data types directly supported by evitaDB. This includes primitive
	 * types and their wrappers, date/time types, ranges, locales, currencies, UUIDs, and
	 * evitaDB-specific types like `Predecessor` and `Expression`. Float and Double are NOT
	 * included as they are normalized to `BigDecimal` for precision consistency.
	 */
	private static final Set<Class<?>> SUPPORTED_QUERY_DATA_TYPES;
	/**
	 * Unmodifiable map from Java primitive types (byte, short, int, long, float, double, boolean,
	 * char) to their corresponding wrapper classes (Byte, Short, Integer, Long, Float, Double,
	 * Boolean, Character). Used by {@link #toWrappedForm(Class)} and
	 * {@link #getWrappingPrimitiveClass(Class)} to convert primitive array types and references
	 * to their object equivalents for consistent type handling.
	 */
	private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPING_TYPES;
	/**
	 * Character used to delimit string and character literals in EvitaQL syntax. Set to single
	 * quote (`'`). This delimiter is escaped when appearing within string values via
	 * {@link #formatValue(Serializable)}.
	 */
	private static final char CHAR_STRING_DELIMITER = '\'';
	/**
	 * String representation of the single-quote delimiter used in EvitaQL. Used in conjunction
	 * with `STRING_DELIMITER_PATTERN` for escaping embedded quotes in string values.
	 */
	private static final String STRING_DELIMITER ="" + CHAR_STRING_DELIMITER;
	/**
	 * Compiled regex pattern for matching and escaping single-quote delimiters in string values.
	 * Used by `formatValue()` to escape embedded quotes (e.g., `it's` becomes `it\'s`) when
	 * formatting strings for EvitaQL query syntax.
	 */
	private static final Pattern STRING_DELIMITER_PATTERN = Pattern.compile(STRING_DELIMITER);
	/**
	 * Parser function that attempts to parse a string to `OffsetDateTime` using ISO-8601 format
	 * (`DateTimeFormatter.ISO_OFFSET_DATE_TIME`). Returns `null` on parse failure rather than
	 * throwing an exception, enabling graceful fallback to alternative date/time parsers in
	 * conversion functions. Used by `OFFSET_DATE_TIME_FUNCTION`, `LOCAL_DATE_TIME_FUNCTION`,
	 * `LOCAL_DATE_FUNCTION`, and `LOCAL_TIME_FUNCTION` for multi-format date/time string parsing.
	 */
	private static final Function<String, OffsetDateTime>
		PARSE_TO_OFFSET_DATE_TIME = string -> {
		try {
			return OffsetDateTime.parse(string, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	};
	/**
	 * Parser function that attempts to parse a string to `LocalDateTime` using ISO-8601 local
	 * date-time format (`DateTimeFormatter.ISO_LOCAL_DATE_TIME`). Returns `null` on parse failure
	 * rather than throwing an exception, enabling graceful fallback to alternative date/time
	 * parsers. Used by conversion functions to support flexible date-time input formats.
	 */
	private static final Function<String, LocalDateTime>
		PARSE_TO_LOCAL_DATE_TIME = string -> {
		try {
			return LocalDateTime.parse(string, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	};
	/**
	 * Parser function that attempts to parse a string to `LocalDate` using ISO-8601 date format
	 * (`DateTimeFormatter.ISO_LOCAL_DATE`). Returns `null` on parse failure rather than throwing
	 * an exception, enabling graceful fallback to alternative date parsers. Used by conversion
	 * functions to support flexible date input formats.
	 */
	private static final Function<String, LocalDate>
		PARSE_TO_LOCAL_DATE = string -> {
		try {
			return LocalDate.parse(string, DateTimeFormatter.ISO_LOCAL_DATE);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	};
	/**
	 * Parser function that attempts to parse a string to `LocalTime` using ISO-8601 time format
	 * (`DateTimeFormatter.ISO_LOCAL_TIME`). Returns `null` on parse failure rather than throwing
	 * an exception, enabling graceful fallback to alternative time parsers. Used by conversion
	 * functions to support flexible time input formats.
	 */
	private static final Function<String, LocalTime>
		PARSE_TO_LOCAL_TIME = string -> {
		try {
			return LocalTime.parse(string, DateTimeFormatter.ISO_LOCAL_TIME);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	};
	/**
	 * Conversion function that coerces arbitrary serializable objects to `Number` instances.
	 * Returns `Number` inputs unchanged; parses non-Number objects via `toString()` followed by
	 * `BigDecimal` constructor. Used as the first stage in numeric conversions to normalize input
	 * before applying narrowing conversions (to Byte, Short, Integer, Long, or BigDecimal).
	 *
	 * Throws `InconvertibleDataTypeException` if the string representation cannot be parsed as a
	 * numeric value (e.g., non-numeric strings, invalid number format).
	 */
	private static final BiFunction<Class<?>, Serializable, Number>
		BIG_DECIMAL_FUNCTION =
		(requestedType, unknownObject) -> {
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
	/**
	 * Utility function that wraps numeric conversion operations to provide consistent exception
	 * handling. Invokes the `number` supplier and returns the result; if `ArithmeticException` is
	 * thrown (indicating overflow, underflow, or precision loss), converts it to the provided
	 * `InconvertibleDataTypeException` supplier result. Used throughout numeric conversions
	 * (Byte, Short, Integer, Long) to transform low-level arithmetic failures into domain-specific
	 * conversion errors with context about the requested type and input value.
	 */
	private static final BiFunction<Supplier<Number>, Supplier<InconvertibleDataTypeException>, Number>
		WRAPPING_FUNCTION = (number, exception) -> {
		try {
			return number.get();
		} catch (ArithmeticException ex) {
			throw exception.get();
		}
	};
	/**
	 * Conversion function for coercing arbitrary objects to `Boolean`. Returns `Boolean` inputs
	 * unchanged. For `Number` inputs, returns `true` if the long value equals `1`, otherwise
	 * `false` (supports numeric true/false convention). For all other types, delegates to
	 * `Boolean.parseBoolean()` on the string representation. Used by `convertSingleObject` when
	 * target type is `Boolean` or `boolean`.
	 */
	private static final BiFunction<Class<?>, Serializable, Boolean>
		BOOLEAN_FUNCTION =
		(requestedType, unknownObject) -> {
			if (unknownObject instanceof Boolean) {
				return (Boolean) unknownObject;
			} else if (unknownObject instanceof Number) {
				return Objects.equals(1L, ((Number) unknownObject).longValue());
			} else {
				return Boolean.parseBoolean(unknownObject.toString());
			}
		};
	/**
	 * Conversion function for coercing arbitrary objects to `Character`. Returns `Character`
	 * inputs unchanged. For `Number` inputs, converts to an integer and validates range
	 * (0-65535, the valid Unicode code point range for char); throws if out of bounds. For string
	 * inputs, accepts single-character strings, or three-character single-quoted literals like
	 * `'A'` (strips quotes). Throws `InconvertibleDataTypeException` for multi-character strings
	 * or out-of-range numeric values. Used by `convertSingleObject` when target type is
	 * `Character` or `char`.
	 */
	private static final BiFunction<Class<?>, Serializable, Character>
		CHAR_FUNCTION =
		(requestedType, unknownObject) -> {
			if (unknownObject instanceof Character) {
				return (Character) unknownObject;
			} else if (unknownObject instanceof Number) {
				final int intValue =
					(int) WRAPPING_FUNCTION.apply(
						() -> NumberUtils.convertToInt(BIG_DECIMAL_FUNCTION.apply(requestedType, unknownObject)),
						() -> new InconvertibleDataTypeException(requestedType, unknownObject)
					);
				if (intValue < 0 || intValue > 65535) {
					throw new InconvertibleDataTypeException(requestedType, unknownObject);
				}
				return (char) intValue;
			} else {
				final String str = unknownObject.toString();
				if (str.length() == 1) {
					return str.charAt(0);
					// handle single-quoted character literal from EvitaQL, e.g., 'A'
				} else if (str.length() == 3 && str.charAt(0) == '\'' && str.charAt(2) == '\'') {
					return str.charAt(1);
				} else {
					throw new InconvertibleDataTypeException(requestedType, unknownObject);
				}
			}
		};
	/**
	 * Conversion function for coercing arbitrary objects to `Currency`. Parses the string
	 * representation via `Currency.getInstance()`, which validates ISO 4217 currency codes
	 * (e.g., "USD", "EUR", "CZK"). Throws `InconvertibleDataTypeException` if the currency code is
	 * invalid or unrecognized. Used by `convertSingleObject` when target type is `Currency`.
	 */
	private static final BiFunction<Class<?>, Serializable, Currency>
		CURRENCY_FUNCTION =
		(requestedType, unknownObject) -> {
			try {
				return Currency.getInstance(unknownObject.toString());
			} catch (IllegalArgumentException ignored) {
				throw new InconvertibleDataTypeException(requestedType, unknownObject);
			}
		};
	/**
	 * Conversion function for coercing arbitrary objects to `UUID`. Parses the string
	 * representation via `UUID.fromString()`, which validates standard UUID format
	 * (e.g., "550e8400-e29b-41d4-a716-446655440000"). Throws `InconvertibleDataTypeException` if
	 * the string is not a valid UUID. Used by `convertSingleObject` when target type is `UUID`.
	 */
	private static final BiFunction<Class<?>, Serializable, UUID>
		UUID_FUNCTION =
		(requestedType, unknownObject) -> {
			try {
				return UUID.fromString(unknownObject.toString());
			} catch (IllegalArgumentException ignored) {
				throw new InconvertibleDataTypeException(requestedType, unknownObject);
			}
		};
	/**
	 * Conversion function for coercing arbitrary objects to `Locale`. Returns `Locale` inputs
	 * unchanged. For other types, parses the string representation via `Locale.forLanguageTag()`,
	 * which supports IETF BCP 47 language tags (e.g., "en", "en-US", "cs-CZ"). Strips surrounding
	 * single quotes if present (to handle EvitaQL string literals like `'en-US'`). Validates that
	 * the resulting locale has a non-empty language code and a valid ISO-639-3 language
	 * representation. Throws `InconvertibleDataTypeException` for empty strings, invalid language
	 * tags, or unrecognized ISO-639-3 codes. Used by `convertSingleObject` when target type is
	 * `Locale`.
	 */
	private static final BiFunction<Class<?>, Serializable, Locale>
		LOCALE_FUNCTION =
		(requestedType, unknownObject) -> {
			if (unknownObject instanceof Locale) {
				return (Locale) unknownObject;
			} else {
				String localeString = unknownObject.toString();
				Assert.isTrue(
					!localeString.isEmpty(),
					() -> new InconvertibleDataTypeException(requestedType, unknownObject)
				);

				// strip surrounding single quotes from EvitaQL string literals
				if (localeString.charAt(0) == '\'' && localeString.charAt(localeString.length() - 1) == '\'') {
					localeString = localeString.substring(1, localeString.length() - 1);
				}

				final Locale locale = Locale.forLanguageTag(localeString);
				Assert.isTrue(
					!locale.getLanguage().isEmpty(),
					() -> new InconvertibleDataTypeException(requestedType, unknownObject)
				);

				try {
					Assert.notNull(
						locale.getISO3Language(),
						() -> new InconvertibleDataTypeException(requestedType, unknownObject)
					);
				} catch (MissingResourceException ex) {
					throw new InconvertibleDataTypeException(requestedType, unknownObject);
				}
				return locale;
			}
		};
	/**
	 * Conversion function for coercing arbitrary objects to `OffsetDateTime`. Returns
	 * `OffsetDateTime` inputs unchanged. Converts `LocalDateTime` to `OffsetDateTime` at UTC.
	 * Converts `LocalDate` to `OffsetDateTime` at start-of-day UTC. For string inputs, attempts
	 * parsing in order: ISO-8601 offset date-time, ISO-8601 local date-time (interpreted as UTC),
	 * ISO-8601 date (interpreted as start-of-day UTC). Throws `InconvertibleDataTypeException` if
	 * none of these formats match. Used by `convertSingleObject` when target type is
	 * `OffsetDateTime`, and by `DATE_TIME_RANGE_FUNCTION` for range boundary parsing.
	 */
	private static final BiFunction<Class<?>, Serializable, OffsetDateTime>
		OFFSET_DATE_TIME_FUNCTION =
		(requestedType, unknownObject) -> {
			try {
				if (unknownObject instanceof OffsetDateTime) {
					return (OffsetDateTime) unknownObject;
				} else if (unknownObject instanceof LocalDateTime) {
					return ((LocalDateTime) unknownObject).atOffset(ZoneOffset.UTC);
				} else if (unknownObject instanceof LocalDate) {
					return ((LocalDate) unknownObject)
						.atStartOfDay(ZoneOffset.UTC)
						.toOffsetDateTime();
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
						return parsedLocalDate
							.atTime(LocalTime.MIDNIGHT)
							.atOffset(ZoneOffset.UTC);
					}
					throw new InconvertibleDataTypeException(requestedType, unknownObject);
				}
			} catch (IllegalArgumentException ignored) {
				throw new InconvertibleDataTypeException(requestedType, unknownObject);
			}
		};
	/**
	 * Conversion function for coercing arbitrary objects to `DateTimeRange`. Returns
	 * `DateTimeRange` inputs unchanged. For `OffsetDateTime`, creates a zero-width range at that
	 * instant (both bounds set to the same moment). For `LocalDateTime`, creates a zero-width
	 * range at that moment interpreted as UTC. For `LocalDate`, creates a range spanning the
	 * entire day (start-of-day UTC to 23:59:59.999999999 UTC). For string inputs, parses via
	 * `DateTimeRange.PARSE_FCT` to extract from/to bounds (format: `[from,to]`, `[from,]`,
	 * `[,to]`), then recursively converts each boundary via `OFFSET_DATE_TIME_FUNCTION`. Throws
	 * `InconvertibleDataTypeException` if the string format is invalid or boundaries cannot be
	 * parsed. Used by `convertSingleObject` when target type is `DateTimeRange`.
	 */
	private static final BiFunction<Class<?>, Serializable, DateTimeRange>
		DATE_TIME_RANGE_FUNCTION =
		(requestedType, unknownObject) -> {
			if (unknownObject instanceof DateTimeRange) {
				return (DateTimeRange) unknownObject;
			} else if (unknownObject instanceof OffsetDateTime offsetDateTime) {
				return DateTimeRange.between(offsetDateTime, offsetDateTime);
			} else if (unknownObject instanceof LocalDateTime localDateTime) {
				return DateTimeRange.between(
					localDateTime.atOffset(ZoneOffset.UTC),
					localDateTime.atOffset(ZoneOffset.UTC)
				);
			} else if (unknownObject instanceof LocalDate localDate) {
				final OffsetDateTime startOfDay = localDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
				return DateTimeRange.between(
					startOfDay,
					startOfDay
						.plusHours(23)
						.plusMinutes(59)
						.plusSeconds(59)
						.plusNanos(999999999)
				);
			} else {
				final String value = unknownObject.toString();
				final String[] parsedResult = DateTimeRange.PARSE_FCT.apply(value);
				if (parsedResult != null) {
					if (parsedResult[0] == null && parsedResult[1] != null) {
						final OffsetDateTime to =
							OFFSET_DATE_TIME_FUNCTION.apply(DateTimeRange.class, parsedResult[1]);
						return DateTimeRange.until(to);
					} else if (parsedResult[0] != null && parsedResult[1] == null) {
						final OffsetDateTime from =
							OFFSET_DATE_TIME_FUNCTION.apply(DateTimeRange.class, parsedResult[0]);
						return DateTimeRange.since(from);
					} else if (parsedResult[0] != null) {
						final OffsetDateTime from =
							OFFSET_DATE_TIME_FUNCTION.apply(DateTimeRange.class, parsedResult[0]);
						final OffsetDateTime to =
							OFFSET_DATE_TIME_FUNCTION.apply(DateTimeRange.class, parsedResult[1]);
						return DateTimeRange.between(
							from, to
						);
					}
				}
				throw new InconvertibleDataTypeException(requestedType, unknownObject);
			}
		};
	/**
	 * Conversion function for coercing arbitrary objects to `LocalDateTime`. Returns
	 * `LocalDateTime` inputs unchanged. Converts `OffsetDateTime` by discarding the zone offset.
	 * Converts `LocalDate` to start-of-day local date-time. For string inputs, attempts parsing
	 * in order: ISO-8601 local date-time, ISO-8601 offset date-time (strips offset), ISO-8601
	 * date (interprets as start-of-day). Throws `InconvertibleDataTypeException` if none of these
	 * formats match. Used by `convertSingleObject` when target type is `LocalDateTime`.
	 */
	private static final BiFunction<Class<?>, Serializable, LocalDateTime>
		LOCAL_DATE_TIME_FUNCTION =
		(requestedType, unknownObject) -> {
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
	/**
	 * Conversion function for coercing arbitrary objects to `LocalDate`. Returns `LocalDate`
	 * inputs unchanged. Extracts the date component from `OffsetDateTime` or `LocalDateTime`. For
	 * string inputs, attempts parsing in order: ISO-8601 date, ISO-8601 offset date-time
	 * (extracts date), ISO-8601 local date-time (extracts date). Throws
	 * `InconvertibleDataTypeException` if none of these formats match. Used by
	 * `convertSingleObject` when target type is `LocalDate`.
	 */
	private static final BiFunction<Class<?>, Serializable, LocalDate>
		LOCAL_DATE_FUNCTION =
		(requestedType, unknownObject) -> {
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
	/**
	 * Conversion function for coercing arbitrary objects to `LocalTime`. Returns `LocalTime`
	 * inputs unchanged. Extracts the time component from `OffsetDateTime` or `LocalDateTime`. For
	 * string inputs, attempts parsing in order: ISO-8601 time, ISO-8601 offset date-time
	 * (extracts time), ISO-8601 local date-time (extracts time). Throws
	 * `InconvertibleDataTypeException` if none of these formats match. Used by
	 * `convertSingleObject` when target type is `LocalTime`.
	 */
	private static final BiFunction<
		Class<?>, Serializable, LocalTime>
		LOCAL_TIME_FUNCTION =
		(requestedType, unknownObject) -> {
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
	/**
	 * Conversion function for coercing arbitrary objects to `NumberRange` subclasses
	 * (BigDecimalNumberRange, LongNumberRange, IntegerNumberRange, ShortNumberRange,
	 * ByteNumberRange). Returns `NumberRange` inputs unchanged. For other types, parses the string
	 * representation via `NumberRange.PARSE_FCT` to extract from/to bounds (formats: `[from,to]`,
	 * `[from,]`, `[,to]`), then delegates to `parseFromToRange()`, `parseFromOnlyRange()`, or
	 * `parseToOnlyRange()` based on which boundaries are present. The `TypeWithPrecision` argument
	 * specifies the concrete number range type and decimal precision for BigDecimalNumberRange.
	 * Throws `InconvertibleDataTypeException` if the string format is invalid or boundaries cannot
	 * be converted to the target numeric type. Used by `convertSingleObject` when target type is a
	 * `NumberRange` subclass.
	 */
	private static final BiFunction<TypeWithPrecision, Serializable, NumberRange<?>>
		NUMBER_RANGE_FUNCTION =
		(typeWithPrecision, unknownObject) -> {
			try {
				if (unknownObject instanceof NumberRange) {
					return (NumberRange<?>) unknownObject;
				} else {
					final String value = unknownObject.toString();
					final String[] parsedResult = NumberRange.PARSE_FCT.apply(value);
					if (parsedResult != null) {
						// parse "to-only" range (e.g., "[,42]")
						if (parsedResult[0] == null && parsedResult[1] != null) {
							return parseToOnlyRange(typeWithPrecision, parsedResult[1]);
							// parse "from-only" range (e.g., "[42,]")
						} else if (parsedResult[0] != null && parsedResult[1] == null) {
							return parseFromOnlyRange(typeWithPrecision, parsedResult[0]);
							// parse "from-and-to" range (e.g., "[1,42]")
						} else if (parsedResult[0] != null) {
							return parseFromToRange(typeWithPrecision, parsedResult[0], parsedResult[1]);
						}
					}
					throw new InconvertibleDataTypeException(typeWithPrecision.requestedType(), unknownObject);
				}
			} catch (IllegalArgumentException ignored) {
				throw new InconvertibleDataTypeException(typeWithPrecision.requestedType(), unknownObject);
			}
		};

	static {
		final LinkedHashSet<Class<?>> queryDataTypes = new LinkedHashSet<>(64);
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
		queryDataTypes.add(UUID.class);
		queryDataTypes.add(Predecessor.class);
		queryDataTypes.add(ReferencedEntityPredecessor.class);
		queryDataTypes.add(Expression.class);
		SUPPORTED_QUERY_DATA_TYPES = Collections.unmodifiableSet(queryDataTypes);

		final LinkedHashMap<Class<?>, Class<?>> primitiveWrappers = new LinkedHashMap<>(32);
		primitiveWrappers.put(boolean.class, Boolean.class);
		primitiveWrappers.put(byte.class, Byte.class);
		primitiveWrappers.put(short.class, Short.class);
		primitiveWrappers.put(int.class, Integer.class);
		primitiveWrappers.put(long.class, Long.class);
		primitiveWrappers.put(float.class, Float.class);
		primitiveWrappers.put(double.class, Double.class);
		primitiveWrappers.put(char.class, Character.class);
		PRIMITIVE_WRAPPING_TYPES = Collections.unmodifiableMap(primitiveWrappers);
	}

	/**
	 * Returns an unmodifiable set of all data types directly supported by evitaDB for attribute
	 * values, query parameters, and entity references. This includes:
	 *
	 * - Primitive types and wrappers: byte, Byte, short, Short, int, Integer, long, Long, boolean,
	 * Boolean, char, Character
	 * - Numeric types: BigDecimal (Float and Double are normalized to BigDecimal)
	 * - Date/time types: OffsetDateTime, LocalDateTime, LocalDate, LocalTime
	 * - Range types: DateTimeRange, BigDecimalNumberRange, LongNumberRange, IntegerNumberRange,
	 * ShortNumberRange, ByteNumberRange
	 * - Locale and currency: Locale, Currency
	 * - Identifiers: UUID, String
	 * - evitaDB-specific: Predecessor, ReferencedEntityPredecessor, Expression
	 *
	 * Types annotated with `@SupportedEnum` or `@SupportedClass` are also accepted but not
	 * included in this set. Use {@link #isSupportedType(Class)} to check if a specific type is
	 * supported.
	 *
	 * @return unmodifiable set of all directly supported data types
	 */
	@Nonnull
	public static Set<Class<?>> getSupportedDataTypes() {
		return SUPPORTED_QUERY_DATA_TYPES;
	}

	/**
	 * Checks whether the specified type is directly supported by evitaDB as an attribute value,
	 * query parameter, or entity reference type. Returns `true` if the type is present in
	 * {@link #SUPPORTED_QUERY_DATA_TYPES}, which includes primitives, wrappers, numeric types,
	 * date/time types, ranges, locales, currencies, UUIDs, strings, and evitaDB-specific types.
	 *
	 * This method does NOT check for `@SupportedEnum` or `@SupportedClass` annotations. Use
	 * {@link #isSupportedTypeOrItsArrayOrEnum(Class)} to include enum support.
	 *
	 * @param type the type to check (must not be null)
	 * @return `true` if the type is directly supported, `false` otherwise
	 */
	public static boolean isSupportedType(@Nonnull Class<?> type) {
		return SUPPORTED_QUERY_DATA_TYPES.contains(type);
	}

	/**
	 * Checks whether the specified type is directly supported by evitaDB, including array types.
	 * For array types, extracts the component type and checks if it is supported. For non-array
	 * types, delegates to {@link #isSupportedType(Class)}. This method is commonly used to
	 * validate attribute value types that may be single-valued or multi-valued (arrays).
	 *
	 * This method does NOT check for `@SupportedEnum` or `@SupportedClass` annotations. Use
	 * {@link #isSupportedTypeOrItsArrayOrEnum(Class)} to include enum support.
	 *
	 * @param type the type or array type to check (must not be null)
	 * @return `true` if the type or its component type is directly supported, `false` otherwise
	 */
	@SuppressWarnings("unchecked")
	public static boolean isSupportedTypeOrItsArray(@Nonnull Class<?> type) {
		final Class<? extends Serializable> typeToCheck;
		if (type.isArray()) {
			typeToCheck = (Class<? extends Serializable>)type.getComponentType();
		} else {
			typeToCheck = (Class<? extends Serializable>) type;
		}
		return EvitaDataTypes.isSupportedType(typeToCheck);
	}

	/**
	 * Checks whether the specified type is directly supported by evitaDB or is an enum annotated
	 * with `@SupportedEnum`, including array types. For array types, extracts the component type
	 * and checks if it is supported or a supported enum. For non-array types, checks both
	 * {@link #isSupportedType(Class)} and whether the type is an enum with the `@SupportedEnum`
	 * annotation. This is the most permissive type check, used for validating schema attribute
	 * types where enums are explicitly allowed.
	 *
	 * @param type the type, array type, or enum type to check (must not be null)
	 * @return `true` if the type or its component type is supported or is a `@SupportedEnum`,
	 * `false` otherwise
	 */
	@SuppressWarnings("unchecked")
	public static boolean isSupportedTypeOrItsArrayOrEnum(@Nonnull Class<?> type) {
		final Class<? extends Serializable> typeToCheck;
		if (type.isArray()) {
			typeToCheck = (Class<? extends Serializable>) type.getComponentType();
		} else {
			typeToCheck = (Class<? extends Serializable>) type;
		}
		return EvitaDataTypes.isSupportedType(typeToCheck) ||
			(typeToCheck.isEnum() && typeToCheck.isAnnotationPresent(SupportedEnum.class));
	}

	/**
	 * Checks whether the specified class represents an enum type or an array of enum types. This
	 * method does NOT verify the `@SupportedEnum` annotation; it simply checks the Java type
	 * system. Used for detecting enum arguments in query parsing and constraint validation, where
	 * enum types require special handling (e.g., converting to string names for non-supported
	 * enums).
	 *
	 * @param argType the class to check (must not be null)
	 * @return `true` if the class is an enum or an array of enums, `false` otherwise
	 */
	public static boolean isEnumOrArrayOfEnums(@Nonnull Class<?> argType) {
		return argType.isEnum() ||
			(argType.isArray() && argType.getComponentType().isEnum());
	}

	/**
	 * Converts primitive types and primitive array types to their corresponding wrapper types and
	 * wrapper array types. For non-primitive types, returns the original type unchanged. This
	 * method is used internally by {@link #toTargetType(Serializable, Class, int)} to normalize
	 * type references before conversion, ensuring consistent handling of primitives and their
	 * wrappers (e.g., `int` and `Integer` are treated equivalently). For array types, constructs
	 * the wrapper array type by creating a zero-length array instance and extracting its class.
	 *
	 * Special case: `void.class` is returned unchanged (not a valid data type but checked for
	 * safety).
	 *
	 * Examples:
	 * - `int.class` → `Integer.class`
	 * - `int[].class` → `Integer[].class`
	 * - `Integer.class` → `Integer.class` (unchanged)
	 * - `String.class` → `String.class` (unchanged)
	 *
	 * @param type the type to wrap (must not be null)
	 * @return the wrapper type if primitive, or the wrapper array type if primitive array;
	 * otherwise the original type
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	public static Class<? extends Serializable> toWrappedForm(@Nonnull Class<?> type) {
		if (!void.class.equals(type)) {
			final Class<? extends Serializable> typeToCheck;
			if (type.isArray()) {
				typeToCheck = (Class<? extends Serializable>) type.getComponentType();
			} else {
				typeToCheck = (Class<? extends Serializable>) type;
			}
			if (typeToCheck.isPrimitive()) {
				// create a zero-length array to obtain
				// the wrapper array class type
				//noinspection unchecked
				return type.isArray() ?
					(Class<? extends Serializable>) Array.newInstance(
						getWrappingPrimitiveClass(typeToCheck), 0
					).getClass() :
					getWrappingPrimitiveClass(typeToCheck);
			}
		}

		//noinspection unchecked
		return (Class<? extends Serializable>) type;
	}

	/**
	 * Validates and normalizes input values for use in evitaDB queries. Performs type checking and
	 * automatic normalization for certain types to ensure consistency across the database:
	 *
	 * - Returns `null` unchanged (nulls are allowed)
	 * - Normalizes `Float` and `Double` to `BigDecimal` (throws if NaN or Infinite)
	 * - Normalizes `LocalDateTime` to `OffsetDateTime` at UTC
	 * - Passes through supported types unchanged (String, primitives, wrappers, BigDecimal,
	 * OffsetDateTime, LocalDate, LocalTime, ranges, Locale, Currency, UUID, Predecessor,
	 * Expression, etc.)
	 * - Converts enums: returns `@SupportedEnum` instances unchanged, converts others to their
	 * string name
	 * - Passes through `@SupportedClass` annotated types unchanged
	 *
	 * This method is used at query entry points to ensure only valid, normalized types enter the
	 * query processing pipeline. Use {@link #toTargetType(Serializable, Class)} for explicit type
	 * conversion.
	 *
	 * @param unknownObject the value to validate and normalize
	 * @return normalized value, or `null` if input is `null`
	 * @throws UnsupportedDataTypeException if the type is not supported by evitaDB (includes Float
	 *                                      and Double with NaN/Infinite values)
	 */
	@Nullable
	public static Serializable toSupportedType(@Nullable Serializable unknownObject) throws UnsupportedDataTypeException {
		if (unknownObject == null) {
			// nulls are allowed
			return null;
		} else if (unknownObject instanceof Float f) {
			if (f.isNaN() || f.isInfinite()) {
				throw new UnsupportedDataTypeException(unknownObject.getClass(), SUPPORTED_QUERY_DATA_TYPES);
			}
			// normalize floats to big decimal
			return new BigDecimal(unknownObject.toString());
		} else if (unknownObject instanceof Double d) {
			if (d.isNaN() || d.isInfinite()) {
				throw new UnsupportedDataTypeException(unknownObject.getClass(), SUPPORTED_QUERY_DATA_TYPES);
			}
			// normalize doubles to big decimal
			return new BigDecimal(unknownObject.toString());
		} else if (unknownObject instanceof LocalDateTime) {
			// always convert local date time to zoned
			return ((LocalDateTime) unknownObject).atOffset(ZoneOffset.UTC);
		} else if (unknownObject.getClass().isEnum()) {
			return unknownObject.getClass().isAnnotationPresent(SupportedEnum.class) ?
				unknownObject : ((Enum<?>) unknownObject).name();
		} else if (SUPPORTED_QUERY_DATA_TYPES.contains(unknownObject.getClass())) {
			return unknownObject;
		} else if (unknownObject.getClass().isAnnotationPresent(SupportedClass.class)) {
			return unknownObject;
		} else {
			throw new UnsupportedDataTypeException(
				unknownObject.getClass(), SUPPORTED_QUERY_DATA_TYPES
			);
		}
	}

	/**
	 * Converts an arbitrary serializable object to the specified target type. This is the primary
	 * type conversion method in evitaDB, supporting conversions between all supported types
	 * including numeric narrowing/widening, date/time parsing and conversion, locale and currency
	 * parsing, enum resolution, and range parsing. Delegates to
	 * {@link #toTargetType(Serializable, Class, int)} with zero decimal places for number ranges.
	 *
	 * Returns `null` unchanged. Returns the input unchanged if it already matches the requested
	 * type. Handles both single-valued and array-valued conversions (automatically wrapping scalar
	 * results in arrays when requested type is an array). Normalizes primitive types to wrappers
	 * internally for consistent handling.
	 *
	 * Common use cases:
	 * - Query parameter type coercion (e.g., string to integer, string to date)
	 * - Attribute value normalization (e.g., Float to BigDecimal)
	 * - Cross-API type bridging (e.g., REST string inputs to typed values)
	 *
	 * @param unknownObject the object to convert (may be null)
	 * @param requestedType the target type (must be a supported evitaDB type; may be primitive or
	 *                      array type)
	 * @param <T>           the target serializable type parameter
	 * @return the converted object of type `T`, or `null` if input is `null`
	 * @throws InconvertibleDataTypeException if `unknownObject` cannot be converted to
	 *                                        `requestedType` (e.g., invalid format, out of range)
	 * @throws UnsupportedDataTypeException   if `requestedType` is not supported by evitaDB
	 * @throws IllegalArgumentException       if `requestedType` is not a supported evitaDB type
	 */
	@Nullable
	public static <T extends Serializable> T toTargetType(
		@Nullable Serializable unknownObject,
		@Nonnull Class<T> requestedType
	) throws UnsupportedDataTypeException {
		return toTargetType(unknownObject, requestedType, 0);
	}

	/**
	 * Converts an arbitrary serializable object to the specified target type, with explicit
	 * decimal precision control for `BigDecimalNumberRange` conversions. This overload allows
	 * callers to specify the number of decimal places to retain when parsing or converting to
	 * `BigDecimalNumberRange`, enabling precise control over numeric precision in range queries.
	 *
	 * Behavior is identical to {@link #toTargetType(Serializable, Class)} except for the
	 * `allowedDecimalPlaces` parameter:
	 * - For `BigDecimalNumberRange` conversions, applies the specified decimal precision
	 * - For other types, the parameter is ignored
	 * - Zero decimal places indicates integer precision (no fractional part)
	 *
	 * Implementation details:
	 * - Returns `null` unchanged
	 * - Returns input unchanged if already matching requested type
	 * - Unwraps array component types and resolves primitives to wrappers
	 * - Validates that requested type is supported
	 * - Delegates to {@link #convertSingleObject(Serializable, Class, int)} for actual conversion
	 * - Handles array conversions element-wise, preserving null elements
	 * - Wraps scalar results in single-element arrays if requested type is an array
	 *
	 * @param unknownObject        the object to convert (may be null)
	 * @param requestedType        the target type (must be a supported evitaDB type; may be primitive or
	 *                             array type)
	 * @param allowedDecimalPlaces number of decimal places for `BigDecimalNumberRange` conversions
	 *                             (0 = integer precision; ignored for non-range types)
	 * @param <T>                  the target serializable type parameter
	 * @return the converted object of type `T`, or `null` if input is `null`
	 * @throws InconvertibleDataTypeException if `unknownObject` cannot be converted to
	 *                                        `requestedType`
	 * @throws UnsupportedDataTypeException   if `requestedType` is not supported by evitaDB
	 * @throws IllegalArgumentException       if `requestedType` is not a supported evitaDB type
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public static <T extends Serializable> T toTargetType(
		@Nullable Serializable unknownObject,
		@Nonnull Class<T> requestedType,
		int allowedDecimalPlaces
	) throws UnsupportedDataTypeException {
		if (requestedType.isInstance(unknownObject)) {
			return (T) unknownObject;
		}

		// unwrap array component type and resolve
		// primitives to wrapper classes
		final Class<?> baseRequestedType;
		if (requestedType.isArray()) {
			baseRequestedType =
				requestedType.getComponentType().isPrimitive() ?
					getWrappingPrimitiveClass(requestedType.getComponentType()) :
					requestedType.getComponentType();
		} else {
			baseRequestedType =
				requestedType.isPrimitive() ?
					getWrappingPrimitiveClass(requestedType) :
					requestedType;
		}
		Assert.isTrue(
			isSupportedType(baseRequestedType) || baseRequestedType.isEnum(),
			"Requested type `" + requestedType + "` is not supported by Evita!"
		);
		if (requestedType.isInstance(unknownObject) || unknownObject == null) {
			return (T) unknownObject;
		}

		final Object result;
		if (unknownObject.getClass().isArray()) {
			final int inputArrayLength = Array.getLength(unknownObject);
			result = Array.newInstance(baseRequestedType, inputArrayLength);
			for (int i = 0; i < inputArrayLength; i++) {
				final Object element = Array.get(unknownObject, i);
				if (element == null) {
					Array.set(result, i, null);
				} else {
					Array.set(
						result, i,
						convertSingleObject(
							(Serializable) element,
							baseRequestedType,
							allowedDecimalPlaces
						)
					);
				}
			}
		} else {
			result = convertSingleObject(
				unknownObject,
				baseRequestedType,
				allowedDecimalPlaces
			);
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
	 * Formats a serializable value for printing in EvitaQL query syntax. Produces string
	 * representations suitable for embedding in queries, with appropriate quoting and escaping.
	 * This method is used by query builders, logging, and debugging tools to generate readable
	 * EvitaQL syntax from programmatic values.
	 *
	 * Formatting rules:
	 * - String, Character: single-quoted, with embedded quotes escaped as `\'`
	 * - BigDecimal: normalized to lowercase exponential notation (e.g., `2.5e8` not `2.5E+8`)
	 * - Number: `toString()` representation
	 * - Boolean: `true` or `false`
	 * - Range: bracket notation (e.g., `[1,42]`, `[42,]`, `[,42]`)
	 * - OffsetDateTime, LocalDateTime, LocalDate, LocalTime: ISO-8601 format, truncated to
	 * millisecond precision
	 * - Locale, Currency, UUID: single-quoted string representation
	 * - Enum: unquoted name
	 * - Predecessor, ReferencedEntityPredecessor, ExpressionNode: `toString()` representation
	 * - `@SupportedClass` types: `toString()` representation
	 *
	 * @param value the value to format (must not be null; null inputs throw
	 *              `GenericEvitaInternalError`)
	 * @return formatted string suitable for embedding in EvitaQL queries
	 * @throws UnsupportedDataTypeException if the value type is not supported by evitaDB
	 * @throws GenericEvitaInternalError    if `value` is null (indicates programming error)
	 */
	@Nonnull
	public static String formatValue(
		@Nullable Serializable value
	) {
		if (value instanceof String) {
			return CHAR_STRING_DELIMITER
				+ STRING_DELIMITER_PATTERN.matcher(
				((String) value)
			).replaceAll("\\\\'")
				+ STRING_DELIMITER;
		} else if (value instanceof Character) {
			return CHAR_STRING_DELIMITER
				+ STRING_DELIMITER_PATTERN.matcher(
				((Character) value).toString()
			).replaceAll("\\\\'")
				+ STRING_DELIMITER;
		} else if (
			value instanceof BigDecimal bigDecimalValue
		) {
			// Value normalizations were taken from
			// https://github.com/googleapis/googleapis/blob/master/google/type/decimal.proto
			// docs from Google.
			// All other validation parts are done
			// automatically by Java's BigDecimal
			return bigDecimalValue.toString()
				.replace("E", "e")
				.replace("e+", "e");
		} else if (value instanceof Number) {
			return value.toString();
		} else if (value instanceof Boolean) {
			return value.toString();
		} else if (value instanceof Range) {
			return value.toString();
		} else if (value instanceof OffsetDateTime offsetDateTime) {
			return DateTimeFormatter
				.ISO_OFFSET_DATE_TIME
				.format(offsetDateTime.truncatedTo(ChronoUnit.MILLIS));
		} else if (value instanceof LocalDateTime localDateTime) {
			return DateTimeFormatter
				.ISO_LOCAL_DATE_TIME
				.format(localDateTime.truncatedTo(ChronoUnit.MILLIS));
		} else if (value instanceof LocalDate) {
			return DateTimeFormatter.ISO_LOCAL_DATE.format((TemporalAccessor) value);
		} else if (value instanceof LocalTime localTime) {
			return DateTimeFormatter.ISO_LOCAL_TIME
				.format(localTime.truncatedTo(ChronoUnit.MILLIS));
		} else if (value instanceof Locale) {
			return CHAR_STRING_DELIMITER
				+ ((Locale) value).toLanguageTag()
				+ CHAR_STRING_DELIMITER;
		} else if (value instanceof Currency) {
			return CHAR_STRING_DELIMITER
				+ value.toString()
				+ CHAR_STRING_DELIMITER;
		} else if (value instanceof Enum) {
			return value.toString();
		} else if (value instanceof UUID) {
			return CHAR_STRING_DELIMITER
				+ value.toString()
				+ CHAR_STRING_DELIMITER;
		} else if (value instanceof Predecessor || value instanceof ReferencedEntityPredecessor) {
			return value.toString();
		} else if (value instanceof ExpressionNode expressionNode) {
			return expressionNode.toString();
		} else if (value == null) {
			throw new GenericEvitaInternalError(
				"Null argument value should never ever happen. Null values are excluded in "
					+ "constructor of the class!"
			);
		} else if (value.getClass().isAnnotationPresent(SupportedClass.class)) {
			return value.toString();
		}
		throw new UnsupportedDataTypeException(
			value.getClass(),
			EvitaDataTypes.getSupportedDataTypes()
		);
	}

	/**
	 * Returns the wrapper class for a given Java primitive type. Maps primitive types (boolean,
	 * byte, short, int, long, float, double, char) to their corresponding wrapper classes
	 * (Boolean, Byte, Short, Integer, Long, Float, Double, Character). This method is used
	 * internally by {@link #toWrappedForm(Class)} and throughout the type system to normalize
	 * primitive types to their object equivalents, ensuring consistent type handling in generics
	 * and reflection-based code.
	 *
	 * @param type the primitive type to wrap (must be one of the 8 Java primitive types)
	 * @param <T>  the type parameter
	 * @return the corresponding wrapper class (e.g., `Integer.class` for `int.class`)
	 * @throws IllegalArgumentException if `type` is not a primitive type (via `Assert.notNull`)
	 */
	@Nonnull
	public static <T> Class<T> getWrappingPrimitiveClass(@Nonnull Class<T> type) {
		final Class<?> wrappingClass = PRIMITIVE_WRAPPING_TYPES.get(type);
		Assert.notNull(
			wrappingClass,
			"Class " + type + " is not a primitive class!"
		);
		//noinspection unchecked
		return (Class<T>) wrappingClass;
	}

	/**
	 * Returns a gross estimation of the in-memory size of the specified object in bytes. This
	 * method provides approximate heap memory usage for evitaDB-supported types, used for memory
	 * tracking, cache sizing, and performance optimization. Estimates include object header
	 * overhead, field sizes, and recursively estimated sizes for nested structures (arrays,
	 * ranges). The estimation is NOT precise and should not be relied upon for exact memory
	 * accounting.
	 *
	 * Size estimation rules (using {@link MemoryMeasuringConstants}):
	 * - null: 0 bytes
	 * - Arrays: base size + element reference sizes + recursive size of each element
	 * - String: computed via `MemoryMeasuringConstants.computeStringSize()`
	 * - Primitive wrappers: object header + primitive size
	 * - BigDecimal: object header + constant size
	 * - Date/time types: object header + component sizes
	 * - DateTimeRange: object header + 2 OffsetDateTime sizes + 2 longs
	 * - NumberRange: object header + 2 number sizes + reference + int + 2 longs
	 * - Locale, Currency, Enum: 0 (assumed flyweight/singleton)
	 * - UUID: object header + 2 longs
	 * - Predecessor, ReferencedEntityPredecessor: object header + int
	 * - ComplexDataObject, DataItem: delegates to instance's `estimateSize()` method
	 *
	 * @param unknownObject the object to estimate size of (may be null)
	 * @return estimated in-memory size in bytes (0 for null)
	 * @throws UnsupportedDataTypeException if the object type is not supported by evitaDB
	 */
	public static int estimateSize(@Nullable Serializable unknownObject) {
		if (unknownObject == null) {
			return 0;
		} else if (unknownObject.getClass().isArray()) {
			final int elementSize = getElementSize(unknownObject.getClass().getComponentType());
			int size = ARRAY_BASE_SIZE + Array.getLength(unknownObject) * elementSize;
			for (int i = 0; i < Array.getLength(unknownObject); i++) {
				size += EvitaDataTypes.estimateSize((Serializable) Array.get(unknownObject, i));
			}
			return size;
		} else if (unknownObject instanceof String s) {
			return computeStringSize(s);
		} else if (unknownObject instanceof Byte) {
			return OBJECT_HEADER_SIZE + BYTE_SIZE;
		} else if (unknownObject instanceof Short) {
			return OBJECT_HEADER_SIZE + SMALL_SIZE;
		} else if (unknownObject instanceof Integer) {
			return OBJECT_HEADER_SIZE + INT_SIZE;
		} else if (unknownObject instanceof Long) {
			return OBJECT_HEADER_SIZE + LONG_SIZE;
		} else if (unknownObject instanceof Boolean) {
			return OBJECT_HEADER_SIZE + BYTE_SIZE;
		} else if (unknownObject instanceof Character) {
			return OBJECT_HEADER_SIZE + CHAR_SIZE;
		} else if (unknownObject instanceof BigDecimal) {
			return OBJECT_HEADER_SIZE + BIG_DECIMAL_SIZE;
		} else if (unknownObject instanceof OffsetDateTime) {
			return OBJECT_HEADER_SIZE + LOCAL_DATE_TIME_SIZE + REFERENCE_SIZE;
		} else if (unknownObject instanceof LocalDateTime) {
			return OBJECT_HEADER_SIZE+ LOCAL_DATE_TIME_SIZE;
		} else if (unknownObject instanceof LocalDate) {
			return OBJECT_HEADER_SIZE+ LOCAL_DATE_SIZE;
		} else if (unknownObject instanceof LocalTime) {
			return OBJECT_HEADER_SIZE + LOCAL_TIME_SIZE;
		} else if (unknownObject instanceof DateTimeRange) {
			return OBJECT_HEADER_SIZE
				+ 2 * (OBJECT_HEADER_SIZE + LOCAL_DATE_TIME_SIZE + REFERENCE_SIZE)
				+ 2 * (LONG_SIZE);
		} else if (unknownObject instanceof final NumberRange<?> numberRange) {
			final Number innerDataType;
			if (numberRange.getPreciseFrom() != null) {
				innerDataType = numberRange.getPreciseFrom();
			} else if (numberRange.getPreciseTo() != null) {
				innerDataType = numberRange.getPreciseTo();
			} else {
				innerDataType = null;
			}
			return OBJECT_HEADER_SIZE
				+ 2 * (innerDataType == null ? 0 : estimateSize(innerDataType))
				+ REFERENCE_SIZE + INT_SIZE + 2 * (LONG_SIZE);
		} else if (unknownObject instanceof Locale) {
			return 0;
		} else if (unknownObject instanceof Enum) {
			return 0;
		} else if (unknownObject instanceof Currency) {
			return 0;
		} else if (unknownObject instanceof UUID) {
			return OBJECT_HEADER_SIZE + 2 * LONG_SIZE;
		} else if (unknownObject instanceof Predecessor || unknownObject instanceof ReferencedEntityPredecessor) {
			return OBJECT_HEADER_SIZE + INT_SIZE;
		} else if (unknownObject instanceof final ComplexDataObject complexDataObject) {
			return REFERENCE_SIZE + complexDataObject.estimateSize();
		} else if (unknownObject instanceof final DataItem dataItem) {
			return REFERENCE_SIZE + dataItem.estimateSize();
		} else {
			throw new UnsupportedDataTypeException(
				unknownObject.getClass(),
				SUPPORTED_QUERY_DATA_TYPES
			);
		}
	}

	/**
	 * Converts a single (non-array) `unknownObject` to the specified `requestedType`, applying
	 * `allowedDecimalPlaces` precision for `NumberRange` conversions. This is the central dispatch
	 * method for type conversion, delegating to specialized conversion functions
	 * (BIG_DECIMAL_FUNCTION, BOOLEAN_FUNCTION, CHAR_FUNCTION, CURRENCY_FUNCTION, etc.) based on
	 * the requested type. Handles numeric narrowing conversions, date/time conversions, locale and
	 * currency parsing, enum value resolution, and range parsing. Returns the converted object or
	 * throws `InconvertibleDataTypeException` if conversion is not possible. Used internally by
	 * {@link #toTargetType(Serializable, Class, int)} after array unwrapping and primitive type
	 * resolution.
	 *
	 * @param unknownObject        the non-null object to convert
	 * @param requestedType        the non-null target type (must be a supported evitaDB type)
	 * @param allowedDecimalPlaces precision for `BigDecimalNumberRange` conversions (number of
	 *                             decimal places to retain)
	 * @return the converted object of type compatible with `requestedType`
	 * @throws InconvertibleDataTypeException if `unknownObject` cannot be converted to
	 *                                        `requestedType`
	 * @throws UnsupportedDataTypeException   if `requestedType` is not a supported evitaDB type
	 */
	@Nonnull
	private static Object convertSingleObject(
		@Nonnull Serializable unknownObject,
		@Nonnull Class<?> requestedType,
		int allowedDecimalPlaces
	) {
		final Object result;
		if (String.class.equals(requestedType)) {
			result = unknownObject.toString();
		} else if (Byte.class.equals(requestedType) || byte.class.equals(requestedType)) {
			result = WRAPPING_FUNCTION.apply(
				() -> NumberUtils.convertToByte(BIG_DECIMAL_FUNCTION.apply(requestedType, unknownObject)),
				() -> new InconvertibleDataTypeException(requestedType, unknownObject)
			);
		} else if (Short.class.equals(requestedType) || short.class.equals(requestedType)) {
			result = WRAPPING_FUNCTION.apply(
				() -> NumberUtils.convertToShort(BIG_DECIMAL_FUNCTION.apply(requestedType, unknownObject)),
				() -> new InconvertibleDataTypeException(requestedType, unknownObject)
			);
		} else if (Integer.class.equals(requestedType) || int.class.equals(requestedType)) {
			result = WRAPPING_FUNCTION.apply(
				() -> NumberUtils.convertToInt(BIG_DECIMAL_FUNCTION.apply(requestedType, unknownObject)),
				() -> new InconvertibleDataTypeException(requestedType, unknownObject)
			);
		} else if (Long.class.equals(requestedType) || long.class.equals(requestedType)) {
			result = WRAPPING_FUNCTION.apply(
				() -> NumberUtils.convertToLong(BIG_DECIMAL_FUNCTION.apply(requestedType, unknownObject)),
				() -> new InconvertibleDataTypeException(requestedType, unknownObject)
			);
		} else if (BigDecimal.class.equals(requestedType)) {
			result = NumberUtils.convertToBigDecimal(BIG_DECIMAL_FUNCTION.apply(requestedType, unknownObject));
		} else if (Boolean.class.equals(requestedType) || boolean.class.equals(requestedType)) {
			result = BOOLEAN_FUNCTION.apply(requestedType, unknownObject);
		} else if (Character.class.equals(requestedType) || char.class.equals(requestedType)) {
			result = CHAR_FUNCTION.apply(requestedType, unknownObject);
		} else if (OffsetDateTime.class.equals(requestedType)) {
			result = OFFSET_DATE_TIME_FUNCTION.apply(requestedType, unknownObject);
		} else if (LocalDateTime.class.equals(requestedType)) {
			result = LOCAL_DATE_TIME_FUNCTION.apply(requestedType, unknownObject);
		} else if (LocalDate.class.equals(requestedType)) {
			result = LOCAL_DATE_FUNCTION.apply(requestedType, unknownObject);
		} else if (LocalTime.class.equals(requestedType)) {
			result = LOCAL_TIME_FUNCTION.apply(requestedType, unknownObject);
		} else if (DateTimeRange.class.equals(requestedType)) {
			result = DATE_TIME_RANGE_FUNCTION.apply(requestedType, unknownObject);
		} else if (NumberRange.class.isAssignableFrom(requestedType)) {
			result = NUMBER_RANGE_FUNCTION.apply(
				new TypeWithPrecision(requestedType, allowedDecimalPlaces), unknownObject
			);
		} else if (Locale.class.equals(requestedType)) {
			result = LOCALE_FUNCTION.apply(requestedType, unknownObject);
		} else if (Currency.class.equals(requestedType)) {
			result = CURRENCY_FUNCTION.apply(requestedType, unknownObject);
		} else if (Predecessor.class.equals(requestedType) || ReferencedEntityPredecessor.class.equals(requestedType)) {
			throw new InconvertibleDataTypeException(requestedType, unknownObject);
		} else if (UUID.class.equals(requestedType)) {
			result = UUID_FUNCTION.apply(requestedType, unknownObject);
		} else if (requestedType.isEnum()) {
			try {
				//noinspection unchecked,rawtypes
				result = Enum.valueOf((Class<? extends Enum>) requestedType, unknownObject.toString());
			} catch (IllegalArgumentException ex) {
				throw new InconvertibleDataTypeException(requestedType, unknownObject);
			}
		} else {
			throw new UnsupportedDataTypeException(unknownObject.getClass(),SUPPORTED_QUERY_DATA_TYPES);
		}
		return result;
	}

	/**
	 * Resolves the effective concrete `NumberRange` subclass from the given type with precision.
	 * If the requested type is already a concrete subclass (`BigDecimalNumberRange`,
	 * `LongNumberRange`, `IntegerNumberRange`, `ShortNumberRange`, or `ByteNumberRange`),
	 * returns it unchanged. Otherwise (e.g., abstract `NumberRange.class`), infers the type
	 * from precision: zero precision maps to `LongNumberRange`, non-zero to
	 * `BigDecimalNumberRange`.
	 *
	 * @param typeWithPrecision specifies the target `NumberRange` subclass and decimal precision
	 * @return the concrete `NumberRange` subclass to use for parsing
	 */
	@Nonnull
	private static Class<?> resolveEffectiveRangeType(
		@Nonnull TypeWithPrecision typeWithPrecision
	) {
		final Class<?> type = typeWithPrecision.requestedType();
		if (type.equals(BigDecimalNumberRange.class)
			|| type.equals(LongNumberRange.class)
			|| type.equals(IntegerNumberRange.class)
			|| type.equals(ShortNumberRange.class)
			|| type.equals(ByteNumberRange.class)) {
			return type;
		}
		// for abstract NumberRange or unknown types, infer from decimal precision
		return typeWithPrecision.precision() == 0
			? LongNumberRange.class
			: BigDecimalNumberRange.class;
	}

	/**
	 * Parses a "to-only" number range (e.g., `[,42]`) from the string representation of the upper
	 * bound. Converts `toValue` to the appropriate numeric type based on
	 * `typeWithPrecision.requestedType()`, then constructs the corresponding `NumberRange`
	 * subclass with an open lower bound. For `BigDecimalNumberRange`, applies
	 * `typeWithPrecision.precision()` decimal places. Throws `InconvertibleDataTypeException` if
	 * `toValue` cannot be converted to the target numeric type (e.g., overflow, non-numeric
	 * string). Used by `NUMBER_RANGE_FUNCTION` for parsing half-open ranges.
	 *
	 * @param typeWithPrecision specifies the target `NumberRange` subclass and decimal precision
	 * @param toValue           string representation of the upper bound value
	 * @return a `NumberRange` instance with an open lower bound and the specified upper bound
	 * @throws InconvertibleDataTypeException if conversion fails
	 */
	@Nonnull
	private static NumberRange<?> parseToOnlyRange(
		@Nonnull TypeWithPrecision typeWithPrecision,
		@Nonnull String toValue
	) {
		final Number to = BIG_DECIMAL_FUNCTION.apply(BigDecimal.class, toValue);
		final Class<?> effectiveType = resolveEffectiveRangeType(typeWithPrecision);
		if (effectiveType.equals(BigDecimalNumberRange.class)) {
			final BigDecimal bigDecimalTo =
				(BigDecimal) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToBigDecimal(to),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
				);
			return BigDecimalNumberRange.to(bigDecimalTo, typeWithPrecision.precision());
		} else if (effectiveType.equals(LongNumberRange.class)) {
			final Long longTo =
				(Long) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToLong(to),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
				);
			return LongNumberRange.to(longTo);
		} else if (effectiveType.equals(IntegerNumberRange.class)) {
			final Integer integerTo =
				(Integer) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToInt(to),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
				);
			return IntegerNumberRange.to(integerTo);
		} else if (effectiveType.equals(ShortNumberRange.class)) {
			final Short shortTo =
				(Short) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToShort(to),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
				);
			return ShortNumberRange.to(shortTo);
		} else {
			final Byte byteTo =
				(Byte) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToByte(to),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
				);
			return ByteNumberRange.to(byteTo);
		}
	}

	/**
	 * Parses a "from-only" number range (e.g., `[42,]`) from the string representation of the
	 * lower bound. Converts `fromValue` to the appropriate numeric type based on
	 * `typeWithPrecision.requestedType()`, then constructs the corresponding `NumberRange`
	 * subclass with an open upper bound. For `BigDecimalNumberRange`, applies
	 * `typeWithPrecision.precision()` decimal places. Throws `InconvertibleDataTypeException` if
	 * `fromValue` cannot be converted to the target numeric type (e.g., overflow, non-numeric
	 * string). Used by `NUMBER_RANGE_FUNCTION` for parsing half-open ranges.
	 *
	 * @param typeWithPrecision specifies the target `NumberRange` subclass and decimal precision
	 * @param fromValue         string representation of the lower bound value
	 * @return a `NumberRange` instance with the specified lower bound and an open upper bound
	 * @throws InconvertibleDataTypeException if conversion fails
	 */
	@Nonnull
	private static NumberRange<?> parseFromOnlyRange(
		@Nonnull TypeWithPrecision typeWithPrecision,
		@Nonnull String fromValue
	) {
		final Number from = BIG_DECIMAL_FUNCTION.apply(
			BigDecimal.class, fromValue
		);
		final Class<?> effectiveType = resolveEffectiveRangeType(typeWithPrecision);
		if (effectiveType.equals(BigDecimalNumberRange.class)) {
			final BigDecimal bigDecimalFrom =
				(BigDecimal) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToBigDecimal(from),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
				);
			return BigDecimalNumberRange.from(bigDecimalFrom, typeWithPrecision.precision());
		} else if (effectiveType.equals(LongNumberRange.class)) {
			final Long longFrom =
				(Long) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToLong(from),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
				);
			return LongNumberRange.from(longFrom);
		} else if (effectiveType.equals(IntegerNumberRange.class)) {
			final Integer integerFrom =
				(Integer) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToInt(from),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
				);
			return IntegerNumberRange.from(integerFrom);
		} else if (effectiveType.equals(ShortNumberRange.class)) {
			final Short shortFrom =
				(Short) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToShort(from),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
				);
			return ShortNumberRange.from(shortFrom);
		} else {
			final Byte byteFrom =
				(Byte) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToByte(from),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
				);
			return ByteNumberRange.from(byteFrom);
		}
	}

	/**
	 * Parses a "from-and-to" number range (e.g., `[1,42]`) from the string representations of both
	 * lower and upper bounds. Converts `fromValue` and `toValue` to the appropriate numeric type
	 * based on `typeWithPrecision.requestedType()`, then constructs the corresponding
	 * `NumberRange` subclass with both bounds specified. For `BigDecimalNumberRange`, applies
	 * `typeWithPrecision.precision()` decimal places to both bounds. Throws
	 * `InconvertibleDataTypeException` if either value cannot be converted to the target numeric
	 * type (e.g., overflow, non-numeric string). Used by `NUMBER_RANGE_FUNCTION` for parsing
	 * closed ranges.
	 *
	 * @param typeWithPrecision specifies the target `NumberRange` subclass and decimal precision
	 * @param fromValue         string representation of the lower bound value
	 * @param toValue           string representation of the upper bound value
	 * @return a `NumberRange` instance with both bounds specified
	 * @throws InconvertibleDataTypeException if conversion fails for either bound
	 */
	@Nonnull
	private static NumberRange<?> parseFromToRange(
		@Nonnull TypeWithPrecision typeWithPrecision,
		@Nonnull String fromValue,
		@Nonnull String toValue
	) {
		final Number from = BIG_DECIMAL_FUNCTION.apply(NumberRange.class, fromValue);
		final Number to = BIG_DECIMAL_FUNCTION.apply(NumberRange.class, toValue);
		final Class<?> effectiveType = resolveEffectiveRangeType(typeWithPrecision);
		if (effectiveType.equals(BigDecimalNumberRange.class)) {
			final BigDecimal bigDecimalFrom =
				(BigDecimal) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToBigDecimal(from),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
				);
			final BigDecimal bigDecimalTo =
				(BigDecimal) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToBigDecimal(to),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
				);
			return BigDecimalNumberRange.between(bigDecimalFrom, bigDecimalTo, typeWithPrecision.precision());
		} else if (effectiveType.equals(LongNumberRange.class)) {
			final Long longFrom =
				(Long) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToLong(from),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
				);
			final Long longTo =
				(Long) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToLong(to),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
				);
			return LongNumberRange.between(longFrom, longTo);
		} else if (effectiveType.equals(IntegerNumberRange.class)) {
			final Integer integerFrom =
				(Integer) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToInt(from),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
				);
			final Integer integerTo =
				(Integer) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToInt(to),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
				);
			return IntegerNumberRange.between(integerFrom, integerTo);
		} else if (effectiveType.equals(ShortNumberRange.class)) {
			final Short shortFrom =
				(Short) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToShort(from),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
				);
			final Short shortTo =
				(Short) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToShort(to),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
				);
			return ShortNumberRange.between(shortFrom, shortTo);
		} else {
			final Byte byteFrom =
				(Byte) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToByte(from),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), from)
				);
			final Byte byteTo =
				(Byte) WRAPPING_FUNCTION.apply(
					() -> NumberUtils.convertToByte(to),
					() -> new InconvertibleDataTypeException(typeWithPrecision.requestedType(), to)
				);
			return ByteNumberRange.between(byteFrom, byteTo);
		}
	}

	/**
	 * Private constructor to prevent instantiation
	 * of this utility class.
	 */
	private EvitaDataTypes() {
	}

	/**
	 * Holder for a requested type and its decimal precision,
	 * used in number range conversion.
	 *
	 * @param requestedType the target number range type
	 * @param precision     the number of decimal places
	 */
	private record TypeWithPrecision(
		@Nonnull Class<?> requestedType,
		int precision
	) {
	}

}
