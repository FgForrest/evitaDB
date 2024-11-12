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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.builder;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaApiRootDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemasDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemasDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.CreateGlobalAttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedGlobalAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.SetAttributeSchemaGloballyUniqueMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyCatalogSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.builder.PartialGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.UpdateCatalogSchemaQueryHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.AllAssociatedDataSchemasDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.AllAttributeSchemasDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.AllEntitySchemasDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.AllReferenceSchemasDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.AttributeSchemaDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.AttributeSchemasDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.CatalogEntitySchemaDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.CatalogEntitySchemasDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.CatalogSchemaDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.mutatingDataFetcher.UpdateCatalogSchemaMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.AsyncDataFetcher;

import javax.annotation.Nonnull;

import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;

/**
 * Implementation of {@link PartialGraphQLSchemaBuilder} for building schema for fetching and updating {@link CatalogSchemaContract}.
 * Note: it depends on {@link EntitySchemaSchemaBuilder} because large portion of mutation objects are same as in {@link EntitySchemaSchemaBuilder}
 * thus they cannot be created again in this builder.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CatalogSchemaSchemaBuilder extends PartialGraphQLSchemaBuilder<CatalogGraphQLSchemaBuildingContext> {

	public CatalogSchemaSchemaBuilder(@Nonnull CatalogGraphQLSchemaBuildingContext catalogGraphQLSchemaBuildingContext) {
		super(catalogGraphQLSchemaBuildingContext);
	}

	@Override
	public void build() {
		// build reusable objects
		buildingContext.registerType(buildEntitySchemaObject());
		buildingContext.registerType(buildCatalogSchemaObject());

		// catalog schema mutations
		buildingContext.registerType(ModifyEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyCatalogSchemaDescriptionMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(AllowEvolutionModeInCatalogSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(DisallowEvolutionModeInCatalogSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());

		// global attribute schema mutations
		buildingContext.registerType(CreateGlobalAttributeSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetAttributeSchemaGloballyUniqueMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());

		// other mutation objects should be already created by EntitySchemaSchemaBuilder
		buildingContext.registerType(LocalCatalogSchemaMutationAggregateDescriptor.THIS.to(inputObjectBuilderTransformer).build());

		// build catalog field
		buildingContext.registerQueryField(buildCatalogSchemaField());
		buildingContext.registerMutationField(buildUpdateCatalogSchemaField());
	}

	/*
		Catalog schema
	 */

	@Nonnull
	private BuiltFieldDescriptor buildCatalogSchemaField() {
		return new BuiltFieldDescriptor(
			CatalogSchemaApiRootDescriptor.GET_CATALOG_SCHEMA.to(staticEndpointBuilderTransformer).build(),
			new AsyncDataFetcher(
				CatalogSchemaDataFetcher.getInstance(),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private GraphQLObjectType buildCatalogSchemaObject() {
		final CatalogSchemaContract catalogSchema = buildingContext.getSchema();

		final GraphQLObjectType.Builder schemaObjectBuilder = CatalogSchemaDescriptor.THIS.to(objectBuilderTransformer)
			.field(CatalogSchemaDescriptor.ALL_ATTRIBUTES.to(fieldBuilderTransformer))
			.field(CatalogSchemaDescriptor.ALL_ENTITY_SCHEMAS.to(fieldBuilderTransformer));

		if (!catalogSchema.getAttributes().isEmpty()) {
			buildingContext.registerFieldToObject(
				CatalogSchemaDescriptor.THIS,
				schemaObjectBuilder,
				buildGlobalAttributeSchemasField()
			);
		}
		buildingContext.registerDataFetcher(
			CatalogSchemaDescriptor.THIS,
			CatalogSchemaDescriptor.ALL_ATTRIBUTES,
			AllAttributeSchemasDataFetcher.getInstance()
		);

		if (!buildingContext.getEntitySchemas().isEmpty()) {
			buildingContext.registerFieldToObject(
				CatalogSchemaDescriptor.THIS,
				schemaObjectBuilder,
				buildEntitySchemasField()
			);
		}
		buildingContext.registerDataFetcher(
			CatalogSchemaDescriptor.THIS,
			CatalogSchemaDescriptor.ALL_ENTITY_SCHEMAS,
			AllEntitySchemasDataFetcher.getInstance()
		);

		return schemaObjectBuilder.build();
	}

	/*
		Global attributes
	 */

	@Nonnull
	private BuiltFieldDescriptor buildGlobalAttributeSchemasField() {
		final GraphQLObjectType attributeSchemasObject = buildGlobalAttributeSchemasObject();

		final GraphQLFieldDefinition attributeSchemasField = CatalogSchemaDescriptor.ATTRIBUTES
			.to(fieldBuilderTransformer)
			.type(nonNull(attributeSchemasObject))
			.build();

		return new BuiltFieldDescriptor(
			attributeSchemasField,
			AttributeSchemasDataFetcher.getInstance()
		);
	}

	@Nonnull
	private GraphQLObjectType buildGlobalAttributeSchemasObject() {
		final GraphQLObjectType.Builder attributeSchemasObjectBuilder = GlobalAttributeSchemasDescriptor.THIS
			.to(objectBuilderTransformer);

		buildingContext.getSchema().getAttributes()
			.values()
			.forEach(attributeSchema ->
				buildingContext.registerFieldToObject(
					GlobalAttributeSchemasDescriptor.THIS,
					attributeSchemasObjectBuilder,
					buildGlobalAttributeSchemaField(attributeSchema)
				)
			);

		return attributeSchemasObjectBuilder.build();
	}

	@Nonnull
	private static BuiltFieldDescriptor buildGlobalAttributeSchemaField(@Nonnull AttributeSchemaContract attributeSchema) {
		final GraphQLFieldDefinition attributeSchemaField = newFieldDefinition()
			.name(attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(attributeSchema.getDescription())
			.deprecate(attributeSchema.getDeprecationNotice())
			.type(nonNull(typeRef(GlobalAttributeSchemaDescriptor.THIS.name())))
			.build();

		return new BuiltFieldDescriptor(
			attributeSchemaField,
			new AttributeSchemaDataFetcher(attributeSchema.getName())
		);
	}

	/*
		Entity schema
	 */

	@Nonnull
	private BuiltFieldDescriptor buildEntitySchemasField() {
		final GraphQLObjectType entitySchemasObject = buildEntitySchemasObject();

		final GraphQLFieldDefinition entitySchemasField = CatalogSchemaDescriptor.ENTITY_SCHEMAS
			.to(fieldBuilderTransformer)
			.type(nonNull(entitySchemasObject))
			.build();

		return new BuiltFieldDescriptor(
			entitySchemasField,
			CatalogEntitySchemasDataFetcher.getInstance()
		);
	}

	@Nonnull
	private GraphQLObjectType buildEntitySchemasObject() {
		final GraphQLObjectType.Builder entitySchemasObjectBuilder = EntitySchemasDescriptor.THIS
			.to(objectBuilderTransformer);

		buildingContext.getEntitySchemas().forEach(entitySchema -> {
			final GraphQLFieldDefinition entitySchemaField = newFieldDefinition()
				.name(entitySchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
				.description(entitySchema.getDescription())
				.deprecate(entitySchema.getDeprecationNotice())
				.type(nonNull(typeRef(EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema))))
				.build();
			final CatalogEntitySchemaDataFetcher dataFetcher = new CatalogEntitySchemaDataFetcher(entitySchema.getName());
			final BuiltFieldDescriptor fieldDescriptor = new BuiltFieldDescriptor(entitySchemaField, dataFetcher);

			buildingContext.registerFieldToObject(
				EntitySchemasDescriptor.THIS,
				entitySchemasObjectBuilder,
				fieldDescriptor
			);
		});

		return entitySchemasObjectBuilder.build();
	}

	@Nonnull
	private GraphQLObjectType buildEntitySchemaObject() {
		buildingContext.registerDataFetcher(
			EntitySchemaDescriptor.THIS_GENERIC,
			EntitySchemaDescriptor.ALL_ATTRIBUTES,
			AllAttributeSchemasDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			EntitySchemaDescriptor.THIS_GENERIC,
			EntitySchemaDescriptor.ALL_ASSOCIATED_DATA,
			AllAssociatedDataSchemasDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			EntitySchemaDescriptor.THIS_GENERIC,
			EntitySchemaDescriptor.ALL_REFERENCES,
			AllReferenceSchemasDataFetcher.getInstance()
		);

		return EntitySchemaDescriptor.THIS_GENERIC
			.to(objectBuilderTransformer)
			.build();
	}

	/*
		Mutations
	 */

	@Nonnull
	private BuiltFieldDescriptor buildUpdateCatalogSchemaField() {
		final GraphQLFieldDefinition catalogSchemaField = CatalogSchemaApiRootDescriptor.UPDATE_CATALOG_SCHEMA
			.to(staticEndpointBuilderTransformer)
			.argument(UpdateCatalogSchemaQueryHeaderDescriptor.MUTATIONS.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			catalogSchemaField,
			new AsyncDataFetcher(
				UpdateCatalogSchemaMutatingDataFetcher.getInstance(),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
	}
}
