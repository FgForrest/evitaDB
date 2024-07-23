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

package io.evitadb.externalApi.graphql.api.model;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLType;
import io.evitadb.externalApi.api.model.PropertyDataTypeDescriptorTransformer;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptorTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Transforms API-independent {@link PropertyDescriptor} to GraphQL argument.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class PropertyDescriptorToGraphQLArgumentTransformer implements PropertyDescriptorTransformer<GraphQLArgument.Builder> {

	@Nonnull
	private final PropertyDataTypeDescriptorTransformer<GraphQLType> propertyDataTypeTransformer;

	@Override
	public GraphQLArgument.Builder apply(@Nonnull PropertyDescriptor propertyDescriptor) {
		final GraphQLArgument.Builder argumentBuilder = GraphQLArgument.newArgument()
			.name(propertyDescriptor.name())
			.description(propertyDescriptor.description());

		if (propertyDescriptor.deprecate() != null) {
			argumentBuilder.deprecate(propertyDescriptor.deprecate());
		}
		if (propertyDescriptor.type() != null) {
			final GraphQLInputType graphQLType = (GraphQLInputType) propertyDataTypeTransformer.apply(propertyDescriptor.type());
			argumentBuilder.type(graphQLType);
		}
		if (propertyDescriptor.defaultValue() != null) {
			argumentBuilder.defaultValueProgrammatic(propertyDescriptor.defaultValue());
		}

		return argumentBuilder;
	}
}
