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

package io.evitadb.externalApi.rest.api.catalog.builder;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.externalApi.api.ExternalApiNamingConventions;
import io.evitadb.externalApi.api.catalog.dataApi.model.UpsertEntityMutationHeaderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.LocalMutationAggregateDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.constraint.RequireSchemaBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.dto.OpenApiObject;
import io.evitadb.externalApi.rest.api.dto.OpenApiTypeReference;
import io.evitadb.externalApi.rest.dataType.DataTypesConverter;
import io.swagger.v3.oas.models.PathItem;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Optional;

import static io.evitadb.externalApi.rest.api.dto.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.dto.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.dto.OpenApiObject.newObject;

/**
 * Build OpenAPI schemas for entity mutations - upsert and delete. It also registers appropriate handlers for processing
 * such requests.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class DataMutationSchemaBuilder {

	@Nonnull private final OpenApiEntitySchemaBuildingContext entitySchemaBuildingContext;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	@Nonnull private final PathItemBuilder pathItemBuilder;

	public void buildAndAddEntitiesAndPathItems() {
		// Delete and upsert mutations use same URL but different HTTP method. In this case one PathItem must be used.
		final PathItem pathItemWithPrimaryKeyInPath = pathItemBuilder.buildAndAddDeleteSingleEntityPathItem(entitySchemaBuildingContext);
		pathItemBuilder.buildAndAddUpsertMutationOperationIntoPathItem(entitySchemaBuildingContext,
			buildUpsertEntitySchema(false),
			pathItemWithPrimaryKeyInPath,
			true);

		final PathItem pathItem = pathItemBuilder.buildAndAddDeleteEntitiesByQueryPathItem(entitySchemaBuildingContext);
		if (entitySchemaBuildingContext.getSchema().isWithGeneratedPrimaryKey()) {
			pathItemBuilder.buildAndAddUpsertMutationOperationIntoPathItem(entitySchemaBuildingContext,
				buildUpsertEntitySchema(true),
				pathItem,
				false);
		}
	}

	@Nonnull
	private OpenApiTypeReference buildUpsertEntitySchema(boolean withPrimaryKey) {
		final OpenApiObject.Builder upsertEntityObjectBuilder = newObject()
			.name(entitySchemaBuildingContext.getSchema().getNameVariant(ExternalApiNamingConventions.TYPE_NAME_NAMING_CONVENTION) + "_UpsertEntity");//todo lho descriptor

		if (withPrimaryKey) {
			upsertEntityObjectBuilder.property(UpsertEntityMutationHeaderDescriptor.PRIMARY_KEY
				.to(propertyBuilderTransformer)
				.type(DataTypesConverter.getOpenApiScalar(Integer.class, true)));
		}

		upsertEntityObjectBuilder.property(UpsertEntityMutationHeaderDescriptor.ENTITY_EXISTENCE.to(propertyBuilderTransformer));

		final Optional<OpenApiTypeReference> localMutationSchema = buildLocalMutationSchema();

		localMutationSchema.ifPresent(objectSchema ->
			upsertEntityObjectBuilder.property(UpsertEntityMutationHeaderDescriptor.MUTATIONS
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(localMutationSchema.get())))));

		final RequireSchemaBuilder requireSchemaBuilder = new RequireSchemaBuilder(
			entitySchemaBuildingContext.getConstraintSchemaBuildingCtx(),
			entitySchemaBuildingContext.getSchema().getName(),
			RequireSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_UPSERT
		);
		upsertEntityObjectBuilder.property(UpsertEntityMutationHeaderDescriptor.REQUIRE
			.to(propertyBuilderTransformer)
			.type(nonNull(requireSchemaBuilder.build())));

		return entitySchemaBuildingContext.getCatalogCtx().registerType(upsertEntityObjectBuilder.build());
	}

	@Nonnull
	private Optional<OpenApiTypeReference> buildLocalMutationSchema() {
		final EntitySchemaContract entitySchema = entitySchemaBuildingContext.getSchema();

		final String schemaName = LocalMutationAggregateDescriptor.THIS.name(entitySchema);
		final Optional<OpenApiTypeReference> existingSchema = entitySchemaBuildingContext.getCatalogCtx().getRegisteredType(schemaName);
		if (existingSchema.isPresent()) {
			return existingSchema;
		}

		final OpenApiObject.Builder localMutationObjectBuilder = LocalMutationAggregateDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(schemaName)
			.description(LocalMutationAggregateDescriptor.THIS.description(entitySchema.getName()));

		boolean hasAnyMutations = false;

		if (!entitySchema.getAssociatedData().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_ASSOCIATED_DATA)) {
			hasAnyMutations = true;
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.REMOVE_ASSOCIATED_DATA_MUTATION.to(propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.UPSERT_ASSOCIATED_DATA_MUTATION.to(propertyBuilderTransformer));
		}

		if (!entitySchema.getAttributes().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_ATTRIBUTES)) {
			hasAnyMutations = true;
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.APPLY_DELTA_ATTRIBUTE_MUTATION.to(propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.to(propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.UPSERT_ATTRIBUTE_MUTATION.to(propertyBuilderTransformer));
		}

		if (entitySchema.isWithHierarchy() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_HIERARCHY)) {
			hasAnyMutations = true;
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.REMOVE_HIERARCHICAL_PLACEMENT_MUTATION.to(propertyBuilderTransformer)
				.type(DataTypesConverter.getOpenApiScalar(Boolean.class)));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.SET_HIERARCHICAL_PLACEMENT_MUTATION.to(propertyBuilderTransformer));
		}

		if (entitySchema.isWithPrice() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_PRICES)) {
			hasAnyMutations = true;
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.SET_PRICE_INNER_RECORD_HANDLING_MUTATION.to(propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.REMOVE_PRICE_MUTATION.to(propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.UPSERT_PRICE_MUTATION.to(propertyBuilderTransformer));
		}

		if (!entitySchema.getReferences().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_REFERENCES)) {
			hasAnyMutations = true;
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.INSERT_REFERENCE_MUTATION.to(propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.REMOVE_REFERENCE_MUTATION.to(propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.SET_REFERENCE_GROUP_MUTATION.to(propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.REMOVE_REFERENCE_GROUP_MUTATION.to(propertyBuilderTransformer));
			localMutationObjectBuilder.property(LocalMutationAggregateDescriptor.REFERENCE_ATTRIBUTE_MUTATION.to(propertyBuilderTransformer));
		}

		if (!hasAnyMutations) {
			return Optional.empty();
		}
		return Optional.of(entitySchemaBuildingContext.getCatalogCtx().registerType(localMutationObjectBuilder.build()));
	}
}
