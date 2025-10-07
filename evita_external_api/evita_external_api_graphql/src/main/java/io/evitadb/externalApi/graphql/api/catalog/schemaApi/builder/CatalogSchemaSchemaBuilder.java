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
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.SetAttributeSchemaGloballyUniqueMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyCatalogSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.builder.PartialGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.GraphQLCatalogSchemaApiRootDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.OnCatalogSchemaChangeHeaderDescriptor;
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
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.subscribingDataFetcher.OnCatalogSchemaChangeCaptureSubscribingDataFetcher;
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
		this.buildingContext.registerType(buildEntitySchemaObject());
		this.buildingContext.registerType(buildCatalogSchemaObject());

		// catalog schema mutations
		this.buildingContext.registerType(ModifyEntitySchemaMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(ModifyCatalogSchemaDescriptionMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(AllowEvolutionModeInCatalogSchemaMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(DisallowEvolutionModeInCatalogSchemaMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());

		// global attribute schema mutations
		this.buildingContext.registerType(CreateGlobalAttributeSchemaMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(SetAttributeSchemaGloballyUniqueMutationDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());

		// other mutation objects should be already created by EntitySchemaSchemaBuilder
		this.buildingContext.registerType(LocalCatalogSchemaMutationAggregateDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());

		// build catalog field
		this.buildingContext.registerQueryField(buildCatalogSchemaField());
		this.buildingContext.registerMutationField(buildUpdateCatalogSchemaField());
		this.buildingContext.registerSubscriptionField(buildOnCatalogSchemaChangeField());
	}

	/*
		Catalog schema
	 */

	@Nonnull
	private BuiltFieldDescriptor buildCatalogSchemaField() {
		return new BuiltFieldDescriptor(
			CatalogSchemaApiRootDescriptor.GET_CATALOG_SCHEMA.to(this.staticEndpointBuilderTransformer).build(),
			new AsyncDataFetcher(
				CatalogSchemaDataFetcher.getInstance(),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private GraphQLObjectType buildCatalogSchemaObject() {
		final CatalogSchemaContract catalogSchema = this.buildingContext.getSchema();

		final GraphQLObjectType.Builder schemaObjectBuilder = CatalogSchemaDescriptor.THIS.to(this.objectBuilderTransformer)
			.field(CatalogSchemaDescriptor.ALL_ATTRIBUTES.to(this.fieldBuilderTransformer))
			.field(CatalogSchemaDescriptor.ALL_ENTITY_SCHEMAS.to(this.fieldBuilderTransformer));

		if (!catalogSchema.getAttributes().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				CatalogSchemaDescriptor.THIS,
				schemaObjectBuilder,
				buildGlobalAttributeSchemasField()
			);
		}
		this.buildingContext.registerDataFetcher(
			CatalogSchemaDescriptor.THIS,
			CatalogSchemaDescriptor.ALL_ATTRIBUTES,
			AllAttributeSchemasDataFetcher.getInstance()
		);

		if (!this.buildingContext.getEntitySchemas().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				CatalogSchemaDescriptor.THIS,
				schemaObjectBuilder,
				buildEntitySchemasField()
			);
		}
		this.buildingContext.registerDataFetcher(
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
			.to(this.fieldBuilderTransformer)
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
			.to(this.objectBuilderTransformer);

		this.buildingContext.getSchema().getAttributes()
			.values()
			.forEach(attributeSchema ->
				this.buildingContext.registerFieldToObject(
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
			.to(this.fieldBuilderTransformer)
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
			.to(this.objectBuilderTransformer);

		this.buildingContext.getEntitySchemas().forEach(entitySchema -> {
			final GraphQLFieldDefinition entitySchemaField = newFieldDefinition()
				.name(entitySchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
				.description(entitySchema.getDescription())
				.deprecate(entitySchema.getDeprecationNotice())
				.type(nonNull(typeRef(EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema))))
				.build();
			final CatalogEntitySchemaDataFetcher dataFetcher = new CatalogEntitySchemaDataFetcher(entitySchema.getName());
			final BuiltFieldDescriptor fieldDescriptor = new BuiltFieldDescriptor(entitySchemaField, dataFetcher);

			this.buildingContext.registerFieldToObject(
				EntitySchemasDescriptor.THIS,
				entitySchemasObjectBuilder,
				fieldDescriptor
			);
		});

		return entitySchemasObjectBuilder.build();
	}

	@Nonnull
	private GraphQLObjectType buildEntitySchemaObject() {
		this.buildingContext.registerDataFetcher(
			EntitySchemaDescriptor.THIS_GENERIC,
			EntitySchemaDescriptor.ALL_ATTRIBUTES,
			AllAttributeSchemasDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			EntitySchemaDescriptor.THIS_GENERIC,
			EntitySchemaDescriptor.ALL_ASSOCIATED_DATA,
			AllAssociatedDataSchemasDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			EntitySchemaDescriptor.THIS_GENERIC,
			EntitySchemaDescriptor.ALL_REFERENCES,
			AllReferenceSchemasDataFetcher.getInstance()
		);

		return EntitySchemaDescriptor.THIS_GENERIC
			.to(this.objectBuilderTransformer)
			.build();
	}

	/*
		Mutations
	 */

	@Nonnull
	private BuiltFieldDescriptor buildUpdateCatalogSchemaField() {
		final GraphQLFieldDefinition catalogSchemaField = CatalogSchemaApiRootDescriptor.UPDATE_CATALOG_SCHEMA
			.to(this.staticEndpointBuilderTransformer)
			.argument(UpdateCatalogSchemaQueryHeaderDescriptor.MUTATIONS.to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			catalogSchemaField,
			new AsyncDataFetcher(
				UpdateCatalogSchemaMutatingDataFetcher.getInstance(),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	/**
	 * Subscriptions
	 */

	@Nonnull
	private BuiltFieldDescriptor buildOnCatalogSchemaChangeField() {
		final GraphQLFieldDefinition onCatalogSchemaChangeField = GraphQLCatalogSchemaApiRootDescriptor.ON_CATALOG_SCHEMA_CHANGE
			.to(this.staticEndpointBuilderTransformer)
			.argument(OnCatalogSchemaChangeHeaderDescriptor.SINCE_VERSION.to(this.argumentBuilderTransformer))
			.argument(OnCatalogSchemaChangeHeaderDescriptor.SINCE_INDEX.to(this.argumentBuilderTransformer))
			.argument(OnCatalogSchemaChangeHeaderDescriptor.OPERATION.to(this.argumentBuilderTransformer))
			.argument(OnCatalogSchemaChangeHeaderDescriptor.CONTAINER_TYPE.to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			onCatalogSchemaChangeField,
			new OnCatalogSchemaChangeCaptureSubscribingDataFetcher(this.buildingContext.getEvita())
		);
	}
}
