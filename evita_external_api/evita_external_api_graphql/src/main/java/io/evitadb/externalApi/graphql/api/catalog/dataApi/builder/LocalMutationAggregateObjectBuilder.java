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
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.LocalMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.RemoveAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.UpsertAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ApplyDeltaAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ReferenceAttributeMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.RemoveAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.UpsertAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.SetEntityScopeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.SetParentMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.RemovePriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.SetPriceInnerRecordHandlingMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.UpsertPriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.InsertReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.ReferenceAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.RemoveReferenceGroupMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.RemoveReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.SetReferenceGroupMutationDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLInputObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLInputFieldTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static graphql.schema.GraphQLInputObjectType.newInputObject;
import static io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars.BOOLEAN;

/**
 * Builds object representing specific {@link LocalMutationAggregateDescriptor} of specific collection.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class LocalMutationAggregateObjectBuilder {

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final ObjectDescriptorToGraphQLInputObjectTransformer inputObjectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLInputFieldTransformer inputFieldBuilderTransformer;

	public void buildCommonTypes() {
		this.buildingContext.registerType(SetEntityScopeMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(RemoveAssociatedDataMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(UpsertAssociatedDataMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(ApplyDeltaAttributeMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(RemoveAttributeMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(UpsertAttributeMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(SetParentMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(SetPriceInnerRecordHandlingMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(RemovePriceMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(UpsertPriceMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(InsertReferenceMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(RemoveReferenceMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(SetReferenceGroupMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(RemoveReferenceGroupMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(ReferenceAttributeMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(ReferenceAttributeMutationAggregateDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
	}

	@Nullable
	public GraphQLInputObjectType build(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLInputObjectType.Builder localMutationAggregateObjectBuilder = newInputObject()
			.name(LocalMutationAggregateDescriptor.THIS.name(entitySchema))
			.description(LocalMutationAggregateDescriptor.THIS.description(entitySchema.getName()));

		boolean hasAnyMutations = false;

		localMutationAggregateObjectBuilder
			.field(LocalMutationAggregateDescriptor.SET_ENTITY_SCOPE_MUTATION.to(this.inputFieldBuilderTransformer));

		if (!entitySchema.getAssociatedData().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_ASSOCIATED_DATA)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationAggregateDescriptor.REMOVE_ASSOCIATED_DATA_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.UPSERT_ASSOCIATED_DATA_MUTATION.to(this.inputFieldBuilderTransformer));
		}

		if (!entitySchema.getAttributes().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_ATTRIBUTES)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationAggregateDescriptor.APPLY_DELTA_ATTRIBUTE_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.UPSERT_ATTRIBUTE_MUTATION.to(this.inputFieldBuilderTransformer));
		}

		if (entitySchema.isWithHierarchy() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_HIERARCHY)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationAggregateDescriptor.REMOVE_PARENT_MUTATION.to(this.inputFieldBuilderTransformer)
					.type(BOOLEAN))
				.field(LocalMutationAggregateDescriptor.SET_PARENT_MUTATION.to(this.inputFieldBuilderTransformer));
		}

		if (entitySchema.isWithPrice() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_PRICES)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationAggregateDescriptor.SET_PRICE_INNER_RECORD_HANDLING_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.REMOVE_PRICE_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.UPSERT_PRICE_MUTATION.to(this.inputFieldBuilderTransformer));
		}

		if (!entitySchema.getReferences().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_REFERENCES)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationAggregateDescriptor.INSERT_REFERENCE_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.REMOVE_REFERENCE_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.SET_REFERENCE_GROUP_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.REMOVE_REFERENCE_GROUP_MUTATION.to(this.inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.REFERENCE_ATTRIBUTE_MUTATION.to(this.inputFieldBuilderTransformer));
		}

		if (!hasAnyMutations) {
			return null;
		}
		return localMutationAggregateObjectBuilder.build();
	}
}
