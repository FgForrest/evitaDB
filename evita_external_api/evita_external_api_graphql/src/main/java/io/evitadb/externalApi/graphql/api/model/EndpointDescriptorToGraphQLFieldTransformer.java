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
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.model.EndpointDescriptor;
import io.evitadb.externalApi.api.model.EndpointDescriptorTransformer;
import io.evitadb.externalApi.api.model.PropertyDataTypeDescriptorTransformer;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Transforms API-independent {@link EndpointDescriptor} to GraphQL field definition.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class EndpointDescriptorToGraphQLFieldTransformer implements EndpointDescriptorTransformer<GraphQLFieldDefinition.Builder> {

	@Nonnull
	private final PropertyDataTypeDescriptorTransformer<GraphQLType> propertyDataTypeTransformer;
	@Nullable
	private final EntitySchemaContract entitySchema;

	public EndpointDescriptorToGraphQLFieldTransformer(@Nonnull PropertyDataTypeDescriptorTransformer<GraphQLType> propertyDataTypeTransformer) {
		this(propertyDataTypeTransformer, null);
	}

	@Override
	public GraphQLFieldDefinition.Builder apply(EndpointDescriptor endpointDescriptor) {
		final String fieldName;
		if (endpointDescriptor.hasClassifier()) {
			Assert.isPremiseValid(
				this.entitySchema == null,
				() -> new GraphQLSchemaBuildingError("Classifier in endpoint `" + endpointDescriptor + "` has static classifier but dynamic one was provided.")
			);
			fieldName = endpointDescriptor.operation();
		} else if (this.entitySchema != null) {
			fieldName = endpointDescriptor.operation(this.entitySchema);
		} else {
			fieldName = endpointDescriptor.operation();
		}

		final GraphQLFieldDefinition.Builder fieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
			.name(fieldName)
			.description(endpointDescriptor.description());

		if (this.entitySchema != null) {
			fieldBuilder.deprecate(this.entitySchema.getDeprecationNotice());
		}

		if (endpointDescriptor.type() != null) {
			final GraphQLOutputType graphQLType = (GraphQLOutputType) this.propertyDataTypeTransformer.apply(endpointDescriptor.type());
			fieldBuilder.type(graphQLType);
		}

		return fieldBuilder;
	}
}
