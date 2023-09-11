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

package io.evitadb.externalApi.graphql.api.dataType;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.externalApi.dataType.Any;
import io.evitadb.externalApi.dataType.GenericObject;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars.*;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Utilities for converting Evita's internal data types ({@link EvitaDataTypes}) to
 * equivalent GraphQL scalars ({@link GraphQLScalars}).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DataTypesConverter {

    @Nonnull
    private static final Map<Class<?>, GraphQLScalarType> SCALAR_MAPPINGS;
    static {
        SCALAR_MAPPINGS = createHashMap(32);
        SCALAR_MAPPINGS.put(String.class, STRING);
        SCALAR_MAPPINGS.put(Byte.class, BYTE);
        SCALAR_MAPPINGS.put(Short.class, SHORT);
        SCALAR_MAPPINGS.put(Integer.class, INT);
        SCALAR_MAPPINGS.put(Long.class, LONG);
        SCALAR_MAPPINGS.put(Boolean.class, BOOLEAN);
        SCALAR_MAPPINGS.put(Character.class, STRING);
        SCALAR_MAPPINGS.put(BigDecimal.class, BIG_DECIMAL);
        SCALAR_MAPPINGS.put(OffsetDateTime.class, OFFSET_DATE_TIME);
        SCALAR_MAPPINGS.put(LocalDateTime.class, LOCAL_DATE_TIME);
        SCALAR_MAPPINGS.put(LocalDate.class, LOCAL_DATE);
        SCALAR_MAPPINGS.put(LocalTime.class, LOCAL_TIME);
        SCALAR_MAPPINGS.put(DateTimeRange.class, DATE_TIME_RANGE);
        SCALAR_MAPPINGS.put(BigDecimalNumberRange.class, BIG_DECIMAL_NUMBER_RANGE);
        SCALAR_MAPPINGS.put(ByteNumberRange.class, BYTE_NUMBER_RANGE);
        SCALAR_MAPPINGS.put(ShortNumberRange.class, SHORT_NUMBER_RANGE);
        SCALAR_MAPPINGS.put(IntegerNumberRange.class, INTEGER_NUMBER_RANGE);
        SCALAR_MAPPINGS.put(LongNumberRange.class, LONG_NUMBER_RANGE);
        SCALAR_MAPPINGS.put(ComplexDataObject.class, COMPLEX_DATA_OBJECT);
        SCALAR_MAPPINGS.put(Locale.class, LOCALE);
        SCALAR_MAPPINGS.put(Currency.class, CURRENCY);
        SCALAR_MAPPINGS.put(UUID.class, UUID);
        SCALAR_MAPPINGS.put(Predecessor.class, PREDECESSOR);
        SCALAR_MAPPINGS.put(Any.class, ANY);
        SCALAR_MAPPINGS.put(GenericObject.class, OBJECT);
    }

    /**
     * Converts Java scalar data type (supported by {@link EvitaDataTypes}) into GraphQL equivalent.
     *
     * @param javaDataType Java scalar data type or its array
     * @return result GraphQL type, may be wrapped inside {@link graphql.schema.GraphQLList}
     */
    public static <T extends GraphQLInputType & GraphQLOutputType> T getGraphQLScalarType(@Nonnull Class<?> javaDataType) {
        return getGraphQLScalarType(javaDataType, false);
    }

    /**
     * Converts Java scalar data type (supported by {@link EvitaDataTypes}) into GraphQL equivalent.
     *
     * @param javaDataType Java scalar data type or its array
     * @param nonNull if should be non-null, thus should be wrapped inside {@link graphql.schema.GraphQLNonNull}
     * @return result GraphQL type, may be wrapped inside {@link graphql.schema.GraphQLNonNull} or {@link graphql.schema.GraphQLList}
     */
    @SuppressWarnings("unchecked")
    public static <T extends GraphQLInputType & GraphQLOutputType> T getGraphQLScalarType(@Nonnull Class<?> javaDataType,
                                                                                          boolean nonNull) {
        final Class<?> componentType = javaDataType.isArray() ? javaDataType.getComponentType() : javaDataType;

        final GraphQLScalarType graphQLComponentType;
        if (componentType.isPrimitive()) {
            graphQLComponentType = SCALAR_MAPPINGS.get(EvitaDataTypes.getWrappingPrimitiveClass(componentType));
        } else {
            graphQLComponentType = SCALAR_MAPPINGS.get(componentType);
        }
        Assert.isPremiseValid(
            graphQLComponentType != null,
            () -> new GraphQLInternalError("Unsupported evitaDB data type in GraphQL API `" + javaDataType.getName() + "`.")
        );

        return (T) wrapGraphQLComponentType(graphQLComponentType, javaDataType, nonNull);
    }

    /**
     * Converts Java scalar data type (supported by {@link EvitaDataTypes}) into GraphQL equivalent.
     * Item type will be replaced with {@code replacementComponentType}, the {@code valueType} is used for array
     * recognition and so on.
     *
     * @param javaDataType Java scalar data type or its array
     * @param replacementComponentType Java component type that will be used as type of items instead of {@code javaDataType}
     * @param nonNull if should be non-null, thus should be wrapped inside {@link graphql.schema.GraphQLNonNull}
     * @return result GraphQL type, may be wrapped inside {@link graphql.schema.GraphQLNonNull} or {@link graphql.schema.GraphQLList}
     */
    @SuppressWarnings("unchecked")
    public static <T extends GraphQLInputType & GraphQLOutputType> T getGraphQLScalarType(@Nonnull Class<?> javaDataType,
                                                                                          @Nonnull Class<?> replacementComponentType,
                                                                                          boolean nonNull) {
        final GraphQLScalarType graphQLComponentType;
        if (replacementComponentType.isPrimitive()) {
            graphQLComponentType = SCALAR_MAPPINGS.get(EvitaDataTypes.getWrappingPrimitiveClass(replacementComponentType));
        } else {
            graphQLComponentType = SCALAR_MAPPINGS.get(replacementComponentType);
        }
        Assert.isPremiseValid(
            graphQLComponentType != null,
            () -> new GraphQLInternalError("Unsupported evitaDB data type in GraphQL API `" + javaDataType.getName() + "`.")
        );

        return (T) wrapGraphQLComponentType(graphQLComponentType, javaDataType, nonNull);
    }

    /**
     * Converts Java enum data type into GraphQL equivalent.
     *
     * @param javaEnumType Java enum data type or its array
     * @return result reference to GraphQL enum type, may be wrapped inside {@link graphql.schema.GraphQLList}.
     *         Also, contains original enum type for registering purposes, so that it can be reused.
     */
    public static <T extends GraphQLInputType & GraphQLOutputType> ConvertedEnum<T> getGraphQLEnumType(@Nonnull Class<?> javaEnumType) {
        return getGraphQLEnumType(javaEnumType, false);
    }

    /**
     * Converts Java enum data type into GraphQL equivalent.
     *
     * @param javaEnumType Java enum data type or its array
     * @param required is required, thus should be wrapped inside {@link graphql.schema.GraphQLNonNull}
     * @return result reference to GraphQL enum type, may be wrapped inside {@link graphql.schema.GraphQLNonNull} or {@link graphql.schema.GraphQLList}.
     *          Also, contains original enum type for registering purposes, so that it can be reused.
     */
    public static <T extends GraphQLInputType & GraphQLOutputType> ConvertedEnum<T> getGraphQLEnumType(@Nonnull Class<?> javaEnumType,
                                                                                                       boolean required) {
        //noinspection unchecked
        final Class<? extends Enum<?>> componentType = (Class<? extends Enum<?>>) (javaEnumType.isArray() ? javaEnumType.getComponentType() : javaEnumType);

        final String enumName = componentType.getSimpleName();

        final GraphQLEnumType.Builder graphQLEnumTypeBuilder = GraphQLEnumType.newEnum().name(enumName);
        for (Enum<?> enumItem : componentType.getEnumConstants()) {
            graphQLEnumTypeBuilder.value(enumItem.name(), enumItem);
        }
        final GraphQLEnumType graphQLEnumType = graphQLEnumTypeBuilder.build();

        // enum is custom type that must be registered and used only once, thus it cannot be wrapped directly into result type
        //noinspection unchecked
        final T graphQLType = wrapGraphQLComponentType((T) typeRef(enumName), javaEnumType, required);
        return new ConvertedEnum<>(graphQLType, graphQLEnumType);
    }

    /**
     * Converts Java enum data type into GraphQL equivalent.
     * Item type will be replaced with {@code replacementComponentType}, the {@code valueType} is used for array
     * recognition and so on.
     *
     * @param javaEnumType Java enum data type or its array
     * @param replacementComponentType Java component type that will be used as type of items instead of {@code javaEnumType}
     * @param required is required, thus should be wrapped inside {@link graphql.schema.GraphQLNonNull}
     * @return result reference to GraphQL enum type, may be wrapped inside {@link graphql.schema.GraphQLNonNull} or {@link graphql.schema.GraphQLList}.
     *          Also, contains original enum type for registering purposes, so that it can be reused.
     */
    public static <T extends GraphQLInputType & GraphQLOutputType> ConvertedEnum<T> getGraphQLEnumType(@Nonnull Class<?> javaEnumType,
                                                                                                       @Nonnull Class<?> replacementComponentType,
                                                                                                       boolean required) {
        //noinspection unchecked
        final Class<? extends Enum<?>> componentType = (Class<? extends Enum<?>>) replacementComponentType;

        final String enumName = componentType.getSimpleName();

        final GraphQLEnumType.Builder graphQLEnumTypeBuilder = GraphQLEnumType.newEnum().name(enumName);
        for (Enum<?> enumItem : componentType.getEnumConstants()) {
            graphQLEnumTypeBuilder.value(enumItem.name(), enumItem);
        }
        final GraphQLEnumType graphQLEnumType = graphQLEnumTypeBuilder.build();

        // enum is custom type that must be registered and used only once, thus it cannot be wrapped directly into result type
        //noinspection unchecked
        final T graphQLType = wrapGraphQLComponentType((T) typeRef(enumName), javaEnumType, required);
        return new ConvertedEnum<>(graphQLType, graphQLEnumType);
    }

    /**
     * Wraps converted GraphQL component type with GraphQL wrapper types by original Java type and required flag.
     *
     * @param convertedGraphQLComponentType converted GraphQL component type to wrap
     * @param javaDataType original Java type, to determine e.g. if it was an array
     * @param nonNull if should be non-null, thus should be wrapped inside {@link graphql.schema.GraphQLNonNull}
     * @return result GraphQL type
     */
    @SuppressWarnings("unchecked")
    public static <T extends GraphQLInputType & GraphQLOutputType> T wrapGraphQLComponentType(@Nonnull T convertedGraphQLComponentType,
                                                                                              @Nonnull Class<?> javaDataType,
                                                                                              boolean nonNull) {
        T graphQLType;
        if (javaDataType.isArray()) {
            graphQLType = (T) list(nonNull(convertedGraphQLComponentType));
        } else {
            graphQLType = convertedGraphQLComponentType;
        }
        if (nonNull) {
            graphQLType = (T) nonNull(graphQLType);
        }
        return graphQLType;
    }

    /**
     * DTO for carrying converted Java custom enums into GraphQL enum type.
     *
     * @param resultType result GraphQL enum type, may be wrapped inside nonNull or list wrappers
     * @param enumType original non-wrapped GraphQL enum type used as origin in resulted {@link #resultType()}
     */
    public record ConvertedEnum<T extends GraphQLInputType & GraphQLOutputType>(@Nonnull T resultType, @Nonnull GraphQLEnumType enumType) {}
}
