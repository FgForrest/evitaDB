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

package io.evitadb.externalApi.graphql.api.model;

import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLType;
import io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor;
import io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor;
import io.evitadb.externalApi.api.model.PropertyDataTypeDescriptor;
import io.evitadb.externalApi.api.model.PropertyDataTypeDescriptorTransformer;
import io.evitadb.externalApi.graphql.api.builder.GraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter.ConvertedEnum;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;

/**
 * Transforms {@link PropertyDataTypeDescriptor} to concrete {@link GraphQLType}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class PropertyDataTypeDescriptorToGraphQLTypeTransformer implements PropertyDataTypeDescriptorTransformer<GraphQLType> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final GraphQLSchemaBuildingContext graphQLSchemaBuildingContext;

	@Override
	public GraphQLType apply(PropertyDataTypeDescriptor typeDescriptor) {
		if (typeDescriptor instanceof PrimitivePropertyDataTypeDescriptor primitiveType) {
			if (primitiveType.javaType().isEnum() ||
				(primitiveType.javaType().isArray() && primitiveType.javaType().componentType().isEnum())) {
				final ConvertedEnum<? extends GraphQLInputType> enumType = DataTypesConverter.getGraphQLEnumType(
					primitiveType.javaType(),
					primitiveType.nonNull()
				);
				this.graphQLSchemaBuildingContext.registerCustomEnumIfAbsent(enumType.enumType());
				return enumType.resultType();
			} else {
				return DataTypesConverter.getGraphQLScalarType(
					primitiveType.javaType(),
					primitiveType.nonNull()
				);
			}
		} else if (typeDescriptor instanceof TypePropertyDataTypeDescriptor objectType) {
			GraphQLInputType graphQLType = typeRef(objectType.typeReference().name());
			if (objectType.list()) {
				graphQLType = list(nonNull(graphQLType));
			}
			if (objectType.nonNull()) {
				graphQLType = nonNull(graphQLType);
			}
			return graphQLType;
		} else {
			throw new GraphQLSchemaBuildingError("Unsupported property data type `" + typeDescriptor.getClass().getName() + "`.");
		}
	}
}
