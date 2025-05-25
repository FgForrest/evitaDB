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

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder;

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
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint.RequireConstraintSchemaBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.EntityUpsertRequestDescriptor;
import io.evitadb.externalApi.rest.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;

import javax.annotation.Nonnull;
import java.util.Optional;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;

/**
 * Build OpenAPI schemas for entity mutations - upsert and delete. It also registers appropriate handlers for processing
 * such requests.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class DataMutationBuilder {

	@Nonnull private final CatalogRestBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	@Nonnull private final RequireConstraintSchemaBuilder requireConstraintSchemaBuilder;

	public DataMutationBuilder(@Nonnull CatalogRestBuildingContext buildingContext,
	                           @Nonnull PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer,
	                           @Nonnull ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer,
	                           @Nonnull RequireConstraintSchemaBuilder requireConstraintSchemaBuilder) {
		this.buildingContext = buildingContext;
		this.propertyBuilderTransformer = propertyBuilderTransformer;
		this.objectBuilderTransformer = objectBuilderTransformer;
		this.requireConstraintSchemaBuilder = requireConstraintSchemaBuilder;
	}

	public void buildCommonTypes() {
		this.buildingContext.registerType(SetEntityScopeMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(RemoveAssociatedDataMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(UpsertAssociatedDataMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ApplyDeltaAttributeMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(RemoveAttributeMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(UpsertAttributeMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(SetParentMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(SetPriceInnerRecordHandlingMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(RemovePriceMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(UpsertPriceMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(InsertReferenceMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(RemoveReferenceMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(SetReferenceGroupMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(RemoveReferenceGroupMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ReferenceAttributeMutationDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ReferenceAttributeMutationAggregateDescriptor.THIS.to(this.objectBuilderTransformer).build());
	}

	@Nonnull
	public OpenApiTypeReference buildEntityUpsertRequestObject(@Nonnull EntitySchemaContract entitySchema) {
		final OpenApiObject.Builder upsertEntityObjectBuilder = EntityUpsertRequestDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(EntityUpsertRequestDescriptor.THIS.name(entitySchema));

		final Optional<OpenApiTypeReference> localMutationSchema = buildLocalMutationSchema(entitySchema);

		localMutationSchema.ifPresent(objectSchema ->
			upsertEntityObjectBuilder.property(EntityUpsertRequestDescriptor.MUTATIONS
				.to(this.propertyBuilderTransformer)
				.type(nonNull(arrayOf(localMutationSchema.get())))));

		upsertEntityObjectBuilder.property(EntityUpsertRequestDescriptor.REQUIRE
			.to(this.propertyBuilderTransformer)
			.type(nonNull(this.requireConstraintSchemaBuilder.build(entitySchema.getName()))));

		return this.buildingContext.registerType(upsertEntityObjectBuilder.build());
	}

	@Nonnull
	private Optional<OpenApiTypeReference> buildLocalMutationSchema(@Nonnull EntitySchemaContract entitySchema) {
		final String schemaName = LocalMutationAggregateDescriptor.THIS.name(entitySchema);

		final OpenApiObject.Builder localMutationObjectBuilder = LocalMutationAggregateDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(schemaName)
			.description(LocalMutationAggregateDescriptor.THIS.description(entitySchema.getName()));

		boolean hasAnyMutations = false;

		if (!entitySchema.getAssociatedData().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_ASSOCIATED_DATA)) {
			hasAnyMutations = true;
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.REMOVE_ASSOCIATED_DATA_MUTATION.to(this.propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.UPSERT_ASSOCIATED_DATA_MUTATION.to(this.propertyBuilderTransformer));
		}

		if (!entitySchema.getAttributes().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_ATTRIBUTES)) {
			hasAnyMutations = true;
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.APPLY_DELTA_ATTRIBUTE_MUTATION.to(this.propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.to(this.propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.UPSERT_ATTRIBUTE_MUTATION.to(this.propertyBuilderTransformer));
		}

		if (entitySchema.isWithHierarchy() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_HIERARCHY)) {
			hasAnyMutations = true;
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.REMOVE_PARENT_MUTATION.to(this.propertyBuilderTransformer)
				.type(DataTypesConverter.getOpenApiScalar(Boolean.class)));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.SET_PARENT_MUTATION.to(this.propertyBuilderTransformer));
		}

		if (entitySchema.isWithPrice() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_PRICES)) {
			hasAnyMutations = true;
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.SET_PRICE_INNER_RECORD_HANDLING_MUTATION.to(this.propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.REMOVE_PRICE_MUTATION.to(this.propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.UPSERT_PRICE_MUTATION.to(this.propertyBuilderTransformer));
		}

		if (!entitySchema.getReferences().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_REFERENCES)) {
			hasAnyMutations = true;
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.INSERT_REFERENCE_MUTATION.to(this.propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.REMOVE_REFERENCE_MUTATION.to(this.propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.SET_REFERENCE_GROUP_MUTATION.to(this.propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.REMOVE_REFERENCE_GROUP_MUTATION.to(this.propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.REFERENCE_ATTRIBUTE_MUTATION.to(this.propertyBuilderTransformer));
		}

		if (!hasAnyMutations) {
			return Optional.empty();
		}
		return Optional.of(this.buildingContext.registerType(localMutationObjectBuilder.build()));
	}
}
