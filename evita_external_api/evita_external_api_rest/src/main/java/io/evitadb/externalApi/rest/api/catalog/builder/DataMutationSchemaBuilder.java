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
import io.evitadb.externalApi.api.catalog.dataApi.model.UpsertEntityMutationHeaderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.LocalMutationAggregateDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.constraint.RequireSchemaBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiSchemaTransformer.Property;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Optional;

import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createArraySchemaOf;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createBooleanSchema;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createIntegerSchema;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createObjectSchema;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaPropertyUtils.addProperty;
import static io.evitadb.externalApi.rest.api.catalog.builder.transformer.Transformers.OBJECT_TRANSFORMER;

/**
 * Build OpenAPI schemas for entity mutations - upsert and delete. It also registers appropriate handlers for processing
 * such requests.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class DataMutationSchemaBuilder {

	private final OpenApiEntitySchemaBuildingContext entitySchemaBuildingContext;

	private final PathItemBuilder pathItemBuilder = new PathItemBuilder();

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
	private Schema<Object> buildUpsertEntitySchema(boolean withPrimaryKey) {
		final Schema<Object> upsertEntitySchema = createObjectSchema();

		if (withPrimaryKey) {
			addProperty(upsertEntitySchema, UpsertEntityMutationHeaderDescriptor.PRIMARY_KEY, createIntegerSchema(), withPrimaryKey);
		}

		addProperty(upsertEntitySchema, UpsertEntityMutationHeaderDescriptor.ENTITY_EXISTENCE);

		final Optional<Schema<Object>> localMutationSchema = buildLocalMutationSchema();

		localMutationSchema.ifPresent(objectSchema -> {
			final ArraySchema mutations = createArraySchemaOf(localMutationSchema.get());
			mutations
				.name(UpsertEntityMutationHeaderDescriptor.MUTATIONS.name())
				.description(UpsertEntityMutationHeaderDescriptor.MUTATIONS.description());
			addProperty(upsertEntitySchema, new Property(mutations, true));
		});

		final RequireSchemaBuilder requireSchemaBuilder = new RequireSchemaBuilder(
			entitySchemaBuildingContext.getConstraintSchemaBuildingCtx(),
			entitySchemaBuildingContext.getSchema().getName(),
			RequireSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_UPSERT
		);
		addProperty(upsertEntitySchema, UpsertEntityMutationHeaderDescriptor.REQUIRE, requireSchemaBuilder.build(), true);

		return upsertEntitySchema;
	}

	@Nonnull
	private Optional<Schema<Object>> buildLocalMutationSchema() {
		final EntitySchemaContract entitySchema = entitySchemaBuildingContext.getSchema();
		final String schemaName = LocalMutationAggregateDescriptor.THIS.name(entitySchema);
		final Optional<Schema<Object>> existingSchema = entitySchemaBuildingContext.getCatalogCtx().getRegisteredType(schemaName);
		if (existingSchema.isPresent()) {
			return existingSchema;
		}

		final Schema<Object> localMutationSchema = LocalMutationAggregateDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		localMutationSchema
			.name(schemaName)
			.description(LocalMutationAggregateDescriptor.THIS.description(entitySchema.getName()));

		boolean hasAnyMutations = false;

		if (!entitySchema.getAssociatedData().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_ASSOCIATED_DATA)) {
			hasAnyMutations = true;
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.REMOVE_ASSOCIATED_DATA_MUTATION);
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.UPSERT_ASSOCIATED_DATA_MUTATION);
		}

		if (!entitySchema.getAttributes().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_ATTRIBUTES)) {
			hasAnyMutations = true;
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.APPLY_DELTA_ATTRIBUTE_MUTATION);
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION);
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.UPSERT_ATTRIBUTE_MUTATION);
		}

		if (entitySchema.isWithHierarchy() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_HIERARCHY)) {
			hasAnyMutations = true;
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.REMOVE_HIERARCHICAL_PLACEMENT_MUTATION, createBooleanSchema(), false);
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.SET_HIERARCHICAL_PLACEMENT_MUTATION);
		}

		if (entitySchema.isWithPrice() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_PRICES)) {
			hasAnyMutations = true;
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.SET_PRICE_INNER_RECORD_HANDLING_MUTATION);
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.REMOVE_PRICE_MUTATION);
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.UPSERT_PRICE_MUTATION);
		}

		if (!entitySchema.getReferences().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_REFERENCES)) {
			hasAnyMutations = true;
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.INSERT_REFERENCE_MUTATION);
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.REMOVE_REFERENCE_MUTATION);
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.SET_REFERENCE_GROUP_MUTATION);
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.REMOVE_REFERENCE_GROUP_MUTATION);
			addProperty(localMutationSchema, LocalMutationAggregateDescriptor.REFERENCE_ATTRIBUTE_MUTATION);
		}

		if (!hasAnyMutations) {
			return Optional.empty();
		}
		return Optional.of(entitySchemaBuildingContext.getCatalogCtx().registerType(localMutationSchema));
	}
}
