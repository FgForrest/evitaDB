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

package io.evitadb.externalApi.rest.api.model;

import io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor;
import io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor;
import io.evitadb.externalApi.api.model.PropertyDataTypeDescriptor;
import io.evitadb.externalApi.api.model.PropertyDataTypeDescriptorTransformer;
import io.evitadb.externalApi.rest.api.builder.RestBuildingContext;
import io.evitadb.externalApi.rest.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.rest.api.dataType.DataTypesConverter.ConvertedEnum;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiType;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Transforms {@link PropertyDataTypeDescriptor} to concrete {@link OpenApiType}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class PropertyDataTypeDescriptorToOpenApiTypeTransformer implements PropertyDataTypeDescriptorTransformer<OpenApiSimpleType> {

	@Nonnull
	private final RestBuildingContext restBuildingContext;

	@Override
	public OpenApiSimpleType apply(PropertyDataTypeDescriptor typeDescriptor) {
		if (typeDescriptor instanceof PrimitivePropertyDataTypeDescriptor primitiveType) {
			if (primitiveType.javaType().isEnum() ||
				(primitiveType.javaType().isArray() && primitiveType.javaType().componentType().isEnum())) {
				final ConvertedEnum enumType = DataTypesConverter.getOpenApiEnum(
					primitiveType.javaType(),
					primitiveType.nonNull()
				);
				this.restBuildingContext.registerCustomEnumIfAbsent(enumType.enumType());
				return enumType.resultType();
			} else {
				return DataTypesConverter.getOpenApiScalar(
					primitiveType.javaType(),
					primitiveType.nonNull()
				);
			}
		} else if (typeDescriptor instanceof ObjectPropertyDataTypeDescriptor objectType) {
			OpenApiSimpleType openApiType = typeRefTo(objectType.objectReference().name());
			if (objectType.list()) {
				openApiType = arrayOf(openApiType);
			}
			if (objectType.nonNull()) {
				openApiType = nonNull(openApiType);
			}
			return openApiType;
		} else {
			throw new OpenApiBuildingError("Unsupported property data type `" + typeDescriptor.getClass().getName() + "`.");
		}
	}
}
