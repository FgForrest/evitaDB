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

package io.evitadb.externalApi.rest.api.dataType;

import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.externalApi.rest.api.openApi.OpenApiArray;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEnum;
import io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull;
import io.evitadb.externalApi.rest.api.openApi.OpenApiScalar;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEnum.enumFrom;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiScalar.scalarFrom;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Utilities for converting Evita's internal data types ({@link EvitaDataTypes}) to
 * equivalent OpenAPI scalars.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DataTypesConverter {

    /**
     * Converts Java scalar data type (supported by {@link EvitaDataTypes}) into OpenAPI equivalent.
     *
     * @param javaDataType Java scalar data type or its array
     * @return result OpenAPI type, may be wrapped inside {@link OpenApiArray}
     */
    public static OpenApiSimpleType getOpenApiScalar(@Nonnull Class<?> javaDataType) {
        return getOpenApiScalar(javaDataType, false);
    }

    /**
     * Converts Java scalar data type (supported by {@link EvitaDataTypes}) into OpenAPI equivalent.
     *
     * @param javaDataType Java scalar data type or its array
     * @param nonNull if should be non-null, thus should be wrapped inside {@link OpenApiNonNull}
     * @return result OpenAPI type, may be wrapped inside {@link OpenApiNonNull} or {@link OpenApiArray}
     */
    public static OpenApiSimpleType getOpenApiScalar(@Nonnull Class<?> javaDataType,
                                                     boolean nonNull) {
        final Class<?> componentType = javaDataType.isArray() ? javaDataType.getComponentType() : javaDataType;

        final OpenApiScalar openApiComponentType = scalarFrom(componentType);
        return wrapOpenApiComponentType(openApiComponentType, javaDataType, nonNull);
    }

    /**
     * Converts Java scalar data type (supported by {@link EvitaDataTypes}) into OpenAPI equivalent.
     * Item type will be replaced with {@code replacementComponentType}, the {@code valueType} is used for array
     * recognition and so on.
     *
     * @param javaDataType Java scalar data type or its array
     * @param replacementComponentType Java component type that will be used as type of items instead of {@code javaDataType}
     * @param nonNull if should be non-null, thus should be wrapped inside {@link OpenApiNonNull}
     * @return result OpenAPI type, may be wrapped inside {@link OpenApiNonNull} or {@link OpenApiArray}
     */
    public static OpenApiSimpleType getOpenApiScalar(@Nonnull Class<?> javaDataType,
                                                     @Nonnull Class<?> replacementComponentType,
                                                     boolean nonNull) {
        final OpenApiScalar openApiComponentType = scalarFrom(replacementComponentType);
        return wrapOpenApiComponentType(openApiComponentType, javaDataType, nonNull);
    }

    /**
     * Converts Java enum data type into OpenAPI equivalent.
     *
     * @param javaEnumType Java enum data type or its array
     * @return result reference to OpenAPI enum type, may be wrapped inside {@link OpenApiArray}.
     *         Also, contains original enum type for registering purposes, so that it can be reused.
     */
    public static ConvertedEnum getOpenApiEnum(@Nonnull Class<?> javaEnumType) {
        return getOpenApiEnum(javaEnumType, false);
    }

    /**
     * Converts Java enum data type into OpenAPI equivalent.
     *
     * @param javaEnumType Java enum data type or its array
     * @param required is required, thus should be wrapped inside {@link OpenApiNonNull}
     * @return result reference to OpenAPI enum type, may be wrapped inside {@link OpenApiNonNull} or {@link OpenApiArray}.
     *          Also, contains original enum type for registering purposes, so that it can be reused.
     */
    public static ConvertedEnum getOpenApiEnum(@Nonnull Class<?> javaEnumType,
                                               boolean required) {
        //noinspection unchecked
        final Class<? extends Enum<?>> componentType = (Class<? extends Enum<?>>) (javaEnumType.isArray() ? javaEnumType.getComponentType() : javaEnumType);

        final OpenApiEnum openApiEnum = enumFrom(componentType);

        // enum is custom type that must be registered and used only once, thus it cannot be wrapped directly into result type
        final OpenApiSimpleType openApiType = wrapOpenApiComponentType(typeRefTo(openApiEnum.getName()), javaEnumType, required);
        return new ConvertedEnum(openApiType, openApiEnum);
    }

    /**
     * Converts Java enum data type into OpenAPI equivalent.
     * Item type will be replaced with {@code replacementComponentType}, the {@code valueType} is used for array
     * recognition and so on.
     *
     * @param javaEnumType Java enum data type or its array
     * @param replacementComponentType Java component type that will be used as type of items instead of {@code javaEnumType}
     * @param required is required, thus should be wrapped inside {@link OpenApiNonNull}
     * @return result reference to OpenAPI enum type, may be wrapped inside {@link OpenApiNonNull} or {@link OpenApiArray}.
     *          Also, contains original enum type for registering purposes, so that it can be reused.
     */
    public static ConvertedEnum getOpenApiEnum(@Nonnull Class<?> javaEnumType,
                                               @Nonnull Class<?> replacementComponentType,
                                               boolean required) {
        //noinspection unchecked
        final Class<? extends Enum<?>> componentType = (Class<? extends Enum<?>>) replacementComponentType;

        final OpenApiEnum openApiEnum = enumFrom(componentType);


        // enum is custom type that must be registered and used only once, thus it cannot be wrapped directly into result type
        final OpenApiSimpleType openApiType = wrapOpenApiComponentType(typeRefTo(openApiEnum.getName()), javaEnumType, required);
        return new ConvertedEnum(openApiType, openApiEnum);
    }

    /**
     * Wraps converted OpenAPI component type with OpenAPI wrapper types by original Java type and required flag.
     *
     * @param convertedOpenApiComponentType converted OpenAPI component type to wrap
     * @param javaDataType original Java type, to determine e.g. if it was an array
     * @param nonNull if should be non-null, thus should be wrapped inside {@link OpenApiNonNull}
     * @return result OpenAPI type
     */
    public static OpenApiSimpleType wrapOpenApiComponentType(@Nonnull OpenApiSimpleType convertedOpenApiComponentType,
                                                             @Nonnull Class<?> javaDataType,
                                                             boolean nonNull) {
        OpenApiSimpleType openApiType;
        if (javaDataType.isArray()) {
            openApiType = arrayOf(convertedOpenApiComponentType);
        } else {
            openApiType = convertedOpenApiComponentType;
        }
        if (nonNull) {
            openApiType = nonNull(openApiType);
        }
        return openApiType;
    }

    /**
     * DTO for carrying converted Java custom enums into OpenAPI enum type.
     *
     * @param resultType result OpenAPI enum type, may be wrapped inside nonNull or list wrappers
     * @param enumType original non-wrapped OpenAPI enum type used as origin in resulted {@link #resultType()}
     */
    public record ConvertedEnum(@Nonnull OpenApiSimpleType resultType, @Nonnull OpenApiEnum enumType) {}
}
