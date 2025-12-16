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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder;

import graphql.schema.GraphQLInputObjectType;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.LocalMutationInputAggregateDescriptor;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLInputFieldTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static graphql.schema.GraphQLInputObjectType.newInputObject;
import static io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars.BOOLEAN;

/**
 * Builds object representing specific {@link LocalMutationInputAggregateDescriptor} of specific collection.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class LocalMutationAggregateObjectBuilder {

	@Nonnull private final PropertyDescriptorToGraphQLInputFieldTransformer inputFieldBuilderTransformer;

	@Nullable
	public GraphQLInputObjectType build(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLInputObjectType.Builder localMutationAggregateObjectBuilder = newInputObject()
			.name(LocalMutationInputAggregateDescriptor.THIS.name(entitySchema))
			.description(LocalMutationInputAggregateDescriptor.THIS.description(entitySchema.getName()));

		boolean hasAnyMutations = false;

		localMutationAggregateObjectBuilder
			.field(LocalMutationInputAggregateDescriptor.SET_ENTITY_SCOPE_MUTATION.to(this.inputFieldBuilderTransformer));

		if (!entitySchema.getAssociatedData().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_ASSOCIATED_DATA)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationInputAggregateDescriptor.REMOVE_ASSOCIATED_DATA_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationInputAggregateDescriptor.UPSERT_ASSOCIATED_DATA_MUTATION.to(this.inputFieldBuilderTransformer));
		}

		if (!entitySchema.getAttributes().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_ATTRIBUTES)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationInputAggregateDescriptor.APPLY_DELTA_ATTRIBUTE_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationInputAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationInputAggregateDescriptor.UPSERT_ATTRIBUTE_MUTATION.to(this.inputFieldBuilderTransformer));
		}

		if (entitySchema.isWithHierarchy() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_HIERARCHY)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationInputAggregateDescriptor.REMOVE_PARENT_MUTATION.to(this.inputFieldBuilderTransformer)
					.type(BOOLEAN))
				.field(LocalMutationInputAggregateDescriptor.SET_PARENT_MUTATION.to(this.inputFieldBuilderTransformer));
		}

		if (entitySchema.isWithPrice() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_PRICES)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationInputAggregateDescriptor.SET_PRICE_INNER_RECORD_HANDLING_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationInputAggregateDescriptor.REMOVE_PRICE_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationInputAggregateDescriptor.UPSERT_PRICE_MUTATION.to(this.inputFieldBuilderTransformer));
		}

		if (!entitySchema.getReferences().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_REFERENCES)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationInputAggregateDescriptor.INSERT_REFERENCE_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationInputAggregateDescriptor.REMOVE_REFERENCE_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationInputAggregateDescriptor.SET_REFERENCE_GROUP_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationInputAggregateDescriptor.REMOVE_REFERENCE_GROUP_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationInputAggregateDescriptor.REFERENCE_ATTRIBUTE_MUTATION.to(this.inputFieldBuilderTransformer));
		}

		if (!hasAnyMutations) {
			return null;
		}
		return localMutationAggregateObjectBuilder.build();
	}
}
