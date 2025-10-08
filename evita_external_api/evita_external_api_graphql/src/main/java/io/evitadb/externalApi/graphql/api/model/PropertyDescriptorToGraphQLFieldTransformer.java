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

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLType;
import io.evitadb.externalApi.api.model.PropertyDataTypeDescriptorTransformer;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptorTransformer;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.FieldDecorator;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Transforms API-independent {@link PropertyDescriptor} to GraphQL field definition with optional {@link FieldDecorator}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class PropertyDescriptorToGraphQLFieldTransformer implements PropertyDescriptorTransformer<GraphQLFieldDefinition.Builder> {

	@Nonnull
	private final PropertyDataTypeDescriptorTransformer<GraphQLType> propertyDataTypeTransformer;
	@Nullable
	private final FieldDecorator fieldDecorator;

	public PropertyDescriptorToGraphQLFieldTransformer(@Nonnull PropertyDataTypeDescriptorTransformer<GraphQLType> propertyDataTypeTransformer) {
		this(propertyDataTypeTransformer, null);
	}

	@Override
	public GraphQLFieldDefinition.Builder apply(PropertyDescriptor propertyDescriptor) {
		final GraphQLFieldDefinition.Builder fieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
			.description(propertyDescriptor.description());
		Assert.isPremiseValid(
			propertyDescriptor.defaultValue() == null,
			() -> new GraphQLSchemaBuildingError("GraphQL fields do not support default values but property `" +  propertyDescriptor.name() + "` has one.")
		);

		if (propertyDescriptor.isNameStatic()) {
			fieldBuilder.name(propertyDescriptor.name());
		}
		if (propertyDescriptor.deprecate() != null) {
			fieldBuilder.deprecate(propertyDescriptor.deprecate());
		}

		if (propertyDescriptor.type() != null) {
			final GraphQLOutputType graphQLType = (GraphQLOutputType) this.propertyDataTypeTransformer.apply(propertyDescriptor.type());
			fieldBuilder.type(graphQLType);
		}

		if (this.fieldDecorator != null) {
			this.fieldDecorator.accept(fieldBuilder);
		}

		return fieldBuilder;
	}

	@Nonnull
	public PropertyDescriptorToGraphQLFieldTransformer with(@Nonnull FieldDecorator decorator) {
		return new PropertyDescriptorToGraphQLFieldTransformer(this.propertyDataTypeTransformer, decorator);
	}
}
