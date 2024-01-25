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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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

    public Value(@Nonnull Object actualValue) {
        this.actualValue = actualValue;
        this.type = actualValue.getClass();
    }

    @Nonnull
    public Serializable asSerializable() {
        return asSpecificType(Serializable.class);
    }

    @Nonnull
    public Comparable<?> asComparable() {
        return asSpecificType(Comparable.class);
    }

    @Nonnull
    public <T extends Serializable & Comparable<?>> T asSerializableAndComparable() {
        assertValueIsOfType(Serializable.class);
        assertValueIsOfType(Comparable.class);
        //noinspection unchecked
        return (T) actualValue;
    }

    @Nonnull
    public String asString() {
        if (actualValue instanceof final Character characterValue) {
            return characterValue.toString();
        }
        return asSpecificType(String.class);
    }

    @Nonnull
    public Number asNumber() {
        return asSpecificType(Number.class);
    }

    /**
     * Casts original value to {@link Number} and tries to convert that value into desired type.
     */
    @Nonnull
    public <T extends Number> T asNumber(@Nonnull Class<T> numberType) {
        return EvitaDataTypes.toTargetType(asNumber(), numberType);
    }

    public int asInt() {
        if (actualValue instanceof final Long longNumber) {
            try {
                return Math.toIntExact(longNumber);
            } catch (ArithmeticException e) {
                throw new EvitaInvalidUsageException(
                    "`Long` number was passed when `Integer` desired but `" + actualValue + "` value is to big for `Integer`."
                );
            }
        }
        return asNumber().intValue();
    }

    public long asLong() {
        return asNumber().longValue();
    }

    public boolean asBoolean() {
        return asSpecificType(Boolean.class);
    }

    @Nonnull
    public BigDecimal asBigDecimal() {
        return asSpecificType(BigDecimal.class);
    }

    @Nonnull
    public OffsetDateTime asOffsetDateTime() {
        return asSpecificType(OffsetDateTime.class);
    }

    @Nonnull
    public LocalDateTime asLocalDateTime() {
        return asSpecificType(LocalDateTime.class);
    }

    @Nonnull
    public LocalDate asLocalDate() {
        return asSpecificType(LocalDate.class);
    }

    @Nonnull
    public LocalTime asLocalTime() {
        return asSpecificType(LocalTime.class);
    }

    @Nonnull
    public DateTimeRange asDateTimeRange() {
        return asSpecificType(DateTimeRange.class);
    }

    @Nonnull
    public BigDecimalNumberRange asBigDecimalNumberRange() {
        return asSpecificType(BigDecimalNumberRange.class);
    }

    @Nonnull
    public LongNumberRange asLongNumberRange() {
        return asSpecificType(LongNumberRange.class);
    }

    @Nonnull
    public <T extends Enum<T>> T asEnum(@Nonnull Class<T> enumType) {
        if (actualValue instanceof Enum<?>) {
            return asSpecificType(enumType);
        } else if (actualValue instanceof EnumWrapper) {
            return asSpecificType(EnumWrapper.class).toEnum(enumType);
        } else {
            throw new EvitaInvalidUsageException(
                "Expected enum value but got `" + actualValue.getClass().getName() + "`."
            );
        }
    }

    @Nonnull
    public Locale asLocale() {
        if (actualValue instanceof Locale) {
            return asSpecificType(Locale.class);
        } else if (actualValue instanceof String) {
            return EvitaDataTypes.toTargetType(asSpecificType(String.class), Locale.class);
        } else {
            // correct passed type from client should be checked at visitor level, here should be should correct checked type
            // if everything is correct on parser side
            throw new EvitaInternalError("Expected locale or string value but got `" + actualValue.getClass().getName() + "`.");
        }
    }

    @Nonnull
    public Currency asCurrency() {
        if (actualValue instanceof Currency) {
            return asSpecificType(Currency.class);
        } else if (actualValue instanceof String) {
            return EvitaDataTypes.toTargetType(asSpecificType(String.class), Currency.class);
        } else {
            throw new EvitaInvalidUsageException("Expected currency or string value but got `" + actualValue.getClass().getName() + "`.");
        }
    }

    @Nonnull
    public UUID asUuid() {
        if (actualValue instanceof UUID) {
            return asSpecificType(UUID.class);
        } else if (actualValue instanceof String) {
            return EvitaDataTypes.toTargetType(asSpecificType(String.class), UUID.class);
        } else {
            throw new EvitaInvalidUsageException("Expected UUID or string value but got `" + actualValue.getClass().getName() + "`.");
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
            throw new EvitaInvalidUsageException("Unexpected type of value array `" + actualValue.getClass().getName() + "`.");
        }
    }

    @Nonnull
    public Serializable[] asSerializableArray() {
        try {
            return asArray(v -> (Serializable) v, Serializable.class);
        } catch (ClassCastException e) {
            throw new EvitaInvalidUsageException("Unexpected type of value array `" + actualValue.getClass().getName() + "`.");
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
                                "`Long` number was passed when `Integer` desired but `" + actualValue + "` value is to big for `Integer`."
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
            throw new EvitaInternalError("Unexpected type of value array `" + actualValue.getClass().getName() + "`.");
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
            throw new EvitaInvalidUsageException("Unexpected type of value array `" + actualValue.getClass().getName() + "`.");
        }
    }

    @Nonnull
    public <T extends Enum<T>> T[] asEnumArray(@Nonnull Class<T> enumType) {
        try {
            return asArray(
                v -> {
                    if (v instanceof Enum<?>) {
                        return variadicValueItemAsSpecificType(v, enumType);
                    } else if (v instanceof EnumWrapper) {
                        return variadicValueItemAsSpecificType(v, EnumWrapper.class).toEnum(enumType);
                    } else {
                        throw new EvitaInvalidUsageException(
                            "Expected enum value but got `" + v.getClass().getName() + "`."
                        );
                    }
                },
                enumType
            );
        } catch (ClassCastException e) {
            // correct passed type from client should be checked at visitor level, here should be should correct checked type
            // if everything is correct on parser side
            throw new EvitaInternalError("Unexpected type of value array `" + actualValue.getClass().getName() + "`.");
        }
    }

    private void assertValueIsOfType(@Nonnull Class<?> type) {
        // correct passed type from client should be checked at visitor level, here should be should correct checked type
        // if everything is correct on parser side
        Assert.isPremiseValid(
            type.isInstance(actualValue),
            "Expected value of type `" + type.getName() + "` but got `" + actualValue.getClass().getName() + "`."
        );
    }

    @Nonnull
    private <T> T asSpecificType(@Nonnull Class<T> type) {
        assertValueIsOfType(type);
        return type.cast(actualValue);
    }

    @Nonnull
    private <T> T variadicValueItemAsSpecificType(@Nonnull Object item, @Nonnull Class<T> type) {
        // correct passed type from client should be checked at visitor level, here should be should correct checked type
        // if everything is correct on parser side
        Assert.isPremiseValid(
            type.isInstance(item),
            "Expected variadic value items of type `" + type.getName() + "` but got `" + item.getClass().getName() + "`."
        );
        return type.cast(item);
    }

    @Nonnull
    private <T> T[] asArray(@Nonnull Function<Object, T> itemTransformer, @Nonnull Class<T> expectedItemType) {
        final Object values = actualValue;
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
            throw new EvitaInvalidUsageException("Expected value of iterable type or array but got `" + actualValue.getClass().getName() + "`.");
        }
    }
}
