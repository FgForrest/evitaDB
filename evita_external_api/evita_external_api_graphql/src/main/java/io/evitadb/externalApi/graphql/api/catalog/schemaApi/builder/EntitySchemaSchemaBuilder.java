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
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLUnionType;
import graphql.schema.TypeResolver;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.EntitySchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.AttributeSchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalEntitySchemaMutationUnionDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.CreateAssociatedDataSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.ModifyAssociatedDataSchemaTypeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.RemoveAssociatedDataSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.SetAssociatedDataSchemaLocalizedMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.SetAssociatedDataSchemaNullableMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.CreateEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.RemoveEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.SortableAttributeCompoundSchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutationDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.builder.PartialGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.GraphQLCatalogSchemaApiRootDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.OnCollectionSchemaChangeHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.UpdateEntitySchemaQueryHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.*;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.mutatingDataFetcher.UpdateEntitySchemaMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.subscribingDataFetcher.OnCollectionSchemaChangeCaptureSubscribingDataFetcher;
import io.evitadb.externalApi.graphql.api.model.EndpointDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.AsyncDataFetcher;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;

import javax.annotation.Nonnull;

import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLObjectType.newObject;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;

/**
 * Implementation of {@link PartialGraphQLSchemaBuilder} for building schema for fetching and updating {@link EntitySchemaContract}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class EntitySchemaSchemaBuilder extends PartialGraphQLSchemaBuilder<CatalogGraphQLSchemaBuildingContext> {

	public EntitySchemaSchemaBuilder(@Nonnull CatalogGraphQLSchemaBuildingContext catalogGraphQLSchemaBuildingContext) {
		super(catalogGraphQLSchemaBuildingContext);
	}

	@Override
	public void build() {
		// build common reusable types
		this.buildingContext.registerType(ScopedAttributeUniquenessTypeDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedAttributeUniquenessTypeDescriptor.THIS_INPUT.to(
			this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedGlobalAttributeUniquenessTypeDescriptor.THIS.to(
			this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedGlobalAttributeUniquenessTypeDescriptor.THIS_INPUT.to(
			this.inputObjectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedReferenceIndexTypeDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedReferenceIndexTypeDescriptor.THIS_INPUT.to(
			this.inputObjectBuilderTransformer).build());
		final GraphQLObjectType attributeSchemaObject = buildAttributeSchemaObject();
		this.buildingContext.registerType(attributeSchemaObject);
		final GraphQLObjectType entityAttributeSchemaObject= buildEntityAttributeSchemaObject();
		this.buildingContext.registerType(entityAttributeSchemaObject);
		final GraphQLObjectType globalAttributeSchemaObject = buildGlobalAttributeSchemaObject();
		this.buildingContext.registerType(globalAttributeSchemaObject);
		this.buildingContext.registerType(buildAttributeSchemaUnion(attributeSchemaObject, entityAttributeSchemaObject, globalAttributeSchemaObject));
		this.buildingContext.registerType(AttributeElementDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(buildSortableAttributeCompoundSchemaObject());
		this.buildingContext.registerType(buildAssociatedDataSchemaObject());
		this.buildingContext.registerType(buildGenericReferenceSchemaObject());

		buildInputMutations();
		buildOutputMutations();

		// build collection-specific field and objects
		this.buildingContext.getEntitySchemas().forEach(entitySchema -> {
			this.buildingContext.registerType(buildEntitySchemaObject(entitySchema));
			this.buildingContext.registerQueryField(buildEntitySchemaField(entitySchema));
			this.buildingContext.registerMutationField(buildUpdateEntitySchemaField(entitySchema));
			this.buildingContext.registerSubscriptionField(buildOnEntitySchemaChangeField(entitySchema));
		});
	}

	private void buildInputMutations() {
		registerInputMutations(
			// entity schema mutations
			AllowCurrencyInEntitySchemaMutationDescriptor.THIS_INPUT,
			AllowEvolutionModeInEntitySchemaMutationDescriptor.THIS_INPUT,
			AllowLocaleInEntitySchemaMutationDescriptor.THIS_INPUT,
			CreateEntitySchemaMutationDescriptor.THIS_INPUT,
			DisallowCurrencyInEntitySchemaMutationDescriptor.THIS_INPUT,
			DisallowEvolutionModeInEntitySchemaMutationDescriptor.THIS_INPUT,
			DisallowLocaleInEntitySchemaMutationDescriptor.THIS_INPUT,
			ModifyEntitySchemaDeprecationNoticeMutationDescriptor.THIS_INPUT,
			ModifyEntitySchemaDescriptionMutationDescriptor.THIS_INPUT,
			ModifyEntitySchemaNameMutationDescriptor.THIS_INPUT,
			RemoveEntitySchemaMutationDescriptor.THIS_INPUT,
			SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.THIS_INPUT,
			SetEntitySchemaWithHierarchyMutationDescriptor.THIS_INPUT,
			SetEntitySchemaWithPriceMutationDescriptor.THIS_INPUT,

			// associated data schema mutations
			CreateAssociatedDataSchemaMutationDescriptor.THIS_INPUT,
			ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor.THIS_INPUT,
			ModifyAssociatedDataSchemaDescriptionMutationDescriptor.THIS_INPUT,
			ModifyAssociatedDataSchemaNameMutationDescriptor.THIS_INPUT,
			ModifyAssociatedDataSchemaTypeMutationDescriptor.THIS_INPUT,
			RemoveAssociatedDataSchemaMutationDescriptor.THIS_INPUT,
			SetAssociatedDataSchemaLocalizedMutationDescriptor.THIS_INPUT,
			SetAssociatedDataSchemaNullableMutationDescriptor.THIS_INPUT,

			// attribute schema mutations
			CreateAttributeSchemaMutationDescriptor.THIS_INPUT,
			ModifyAttributeSchemaDefaultValueMutationDescriptor.THIS_INPUT,
			ModifyAttributeSchemaDeprecationNoticeMutationDescriptor.THIS_INPUT,
			ModifyAttributeSchemaDescriptionMutationDescriptor.THIS_INPUT,
			ModifyAttributeSchemaNameMutationDescriptor.THIS_INPUT,
			ModifyAttributeSchemaTypeMutationDescriptor.THIS_INPUT,
			RemoveAttributeSchemaMutationDescriptor.THIS_INPUT,
			SetAttributeSchemaFilterableMutationDescriptor.THIS_INPUT,
			SetAttributeSchemaLocalizedMutationDescriptor.THIS_INPUT,
			SetAttributeSchemaNullableMutationDescriptor.THIS_INPUT,
			SetAttributeSchemaRepresentativeMutationDescriptor.THIS_INPUT,
			SetAttributeSchemaSortableMutationDescriptor.THIS_INPUT,
			UseGlobalAttributeSchemaMutationDescriptor.THIS_INPUT,
			AttributeSchemaMutationInputAggregateDescriptor.THIS_INPUT,
			SetAttributeSchemaUniqueMutationDescriptor.THIS,

			// sortable attribute compound schema mutations
			AttributeElementDescriptor.THIS_INPUT,
			CreateSortableAttributeCompoundSchemaMutationDescriptor.THIS_INPUT,
			ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor.THIS_INPUT,
			ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.THIS_INPUT,
			ModifySortableAttributeCompoundSchemaNameMutationDescriptor.THIS_INPUT,
			SetSortableAttributeCompoundIndexedMutationDescriptor.THIS_INPUT,
			RemoveSortableAttributeCompoundSchemaMutationDescriptor.THIS_INPUT,
			SortableAttributeCompoundSchemaMutationInputAggregateDescriptor.THIS_INPUT,

			// reference schema mutations
			CreateReferenceSchemaMutationDescriptor.THIS_INPUT,
			CreateReflectedReferenceSchemaMutationDescriptor.THIS_INPUT,
			ModifyReferenceAttributeSchemaMutationDescriptor.THIS_INPUT,
			ModifyReferenceSchemaCardinalityMutationDescriptor.THIS_INPUT,
			ModifyReferenceSchemaDeprecationNoticeMutationDescriptor.THIS_INPUT,
			ModifyReferenceSchemaDescriptionMutationDescriptor.THIS_INPUT,
			ModifyReferenceSchemaNameMutationDescriptor.THIS_INPUT,
			ModifyReferenceSchemaRelatedEntityGroupMutationDescriptor.THIS_INPUT,
			ModifyReferenceSchemaRelatedEntityMutationDescriptor.THIS_INPUT,
			ModifyReflectedReferenceAttributeInheritanceSchemaMutationDescriptor.THIS_INPUT,
			RemoveReferenceSchemaMutationDescriptor.THIS_INPUT,
			SetReferenceSchemaFacetedMutationDescriptor.THIS_INPUT,
			SetReferenceSchemaIndexedMutationDescriptor.THIS_INPUT,

			EntitySchemaMutationInputAggregateDescriptor.THIS_INPUT
		);
	}

	private void buildOutputMutations() {
		this.buildingContext.registerType(LocalEntitySchemaMutationUnionDescriptor.THIS.to(this.unionBuilderTransformer).build());
		this.buildingContext.registerType(ReferenceAttributeSchemaMutationUnionDescriptor.THIS.to(this.unionBuilderTransformer).build());

		registerOutputMutations(
			// entity schema mutations
			AllowCurrencyInEntitySchemaMutationDescriptor.THIS,
			AllowEvolutionModeInEntitySchemaMutationDescriptor.THIS,
			AllowLocaleInEntitySchemaMutationDescriptor.THIS,
			CreateEntitySchemaMutationDescriptor.THIS,
			DisallowCurrencyInEntitySchemaMutationDescriptor.THIS,
			DisallowEvolutionModeInEntitySchemaMutationDescriptor.THIS,
			DisallowLocaleInEntitySchemaMutationDescriptor.THIS,
			ModifyEntitySchemaDeprecationNoticeMutationDescriptor.THIS,
			ModifyEntitySchemaDescriptionMutationDescriptor.THIS,
			ModifyEntitySchemaNameMutationDescriptor.THIS,
			RemoveEntitySchemaMutationDescriptor.THIS,
			SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.THIS,
			SetEntitySchemaWithHierarchyMutationDescriptor.THIS,
			SetEntitySchemaWithPriceMutationDescriptor.THIS,

			// associated data schema mutations
			CreateAssociatedDataSchemaMutationDescriptor.THIS,
			ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor.THIS,
			ModifyAssociatedDataSchemaDescriptionMutationDescriptor.THIS,
			ModifyAssociatedDataSchemaNameMutationDescriptor.THIS,
			ModifyAssociatedDataSchemaTypeMutationDescriptor.THIS,
			RemoveAssociatedDataSchemaMutationDescriptor.THIS,
			SetAssociatedDataSchemaLocalizedMutationDescriptor.THIS,
			SetAssociatedDataSchemaNullableMutationDescriptor.THIS,

			// attribute schema mutations
			CreateAttributeSchemaMutationDescriptor.THIS,
			ModifyAttributeSchemaDefaultValueMutationDescriptor.THIS,
			ModifyAttributeSchemaDeprecationNoticeMutationDescriptor.THIS,
			ModifyAttributeSchemaDescriptionMutationDescriptor.THIS,
			ModifyAttributeSchemaNameMutationDescriptor.THIS,
			ModifyAttributeSchemaTypeMutationDescriptor.THIS,
			RemoveAttributeSchemaMutationDescriptor.THIS,
			SetAttributeSchemaFilterableMutationDescriptor.THIS,
			SetAttributeSchemaLocalizedMutationDescriptor.THIS,
			SetAttributeSchemaNullableMutationDescriptor.THIS,
			SetAttributeSchemaRepresentativeMutationDescriptor.THIS,
			SetAttributeSchemaSortableMutationDescriptor.THIS,
			UseGlobalAttributeSchemaMutationDescriptor.THIS,
			SetAttributeSchemaUniqueMutationDescriptor.THIS,

			// sortable attribute compound schema mutations
			AttributeElementDescriptor.THIS_INPUT,
			CreateSortableAttributeCompoundSchemaMutationDescriptor.THIS,
			ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor.THIS,
			ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.THIS,
			ModifySortableAttributeCompoundSchemaNameMutationDescriptor.THIS,
			SetSortableAttributeCompoundIndexedMutationDescriptor.THIS,
			RemoveSortableAttributeCompoundSchemaMutationDescriptor.THIS,

			// reference schema mutations
			CreateReferenceSchemaMutationDescriptor.THIS,
			CreateReflectedReferenceSchemaMutationDescriptor.THIS,
			ModifyReferenceAttributeSchemaMutationDescriptor.THIS,
			ModifyReferenceSchemaCardinalityMutationDescriptor.THIS,
			ModifyReferenceSchemaDeprecationNoticeMutationDescriptor.THIS,
			ModifyReferenceSchemaDescriptionMutationDescriptor.THIS,
			ModifyReferenceSchemaNameMutationDescriptor.THIS,
			ModifyReferenceSchemaRelatedEntityGroupMutationDescriptor.THIS,
			ModifyReferenceSchemaRelatedEntityMutationDescriptor.THIS,
			ModifyReflectedReferenceAttributeInheritanceSchemaMutationDescriptor.THIS,
			RemoveReferenceSchemaMutationDescriptor.THIS,
			SetReferenceSchemaFacetedMutationDescriptor.THIS,
			SetReferenceSchemaIndexedMutationDescriptor.THIS
		);

		// todo lho unions?

	}

	/*
		Entity schema
	 */

	@Nonnull
	private BuiltFieldDescriptor buildEntitySchemaField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLFieldDefinition entitySchemaField = CatalogSchemaApiRootDescriptor.GET_ENTITY_SCHEMA
			.to(new EndpointDescriptorToGraphQLFieldTransformer(this.propertyDataTypeBuilderTransformer, entitySchema))
			.description(CatalogSchemaApiRootDescriptor.GET_ENTITY_SCHEMA.description(entitySchema.getName()))
			.type(nonNull(typeRef(EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema))))
			.build();

		return new BuiltFieldDescriptor(
			entitySchemaField,
			new AsyncDataFetcher(
				new EntitySchemaDataFetcher(entitySchema.getName()),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private GraphQLObjectType buildEntitySchemaObject(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema);

		final GraphQLObjectType.Builder schemaObjectBuilder = EntitySchemaDescriptor.THIS_SPECIFIC
			.to(this.objectBuilderTransformer)
			.name(objectName);

		if (!entitySchema.getAttributes().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				objectName,
				schemaObjectBuilder,
				buildAttributeSchemasField(entitySchema)
			);
		}

		this.buildingContext.registerDataFetcher(
			objectName,
			EntitySchemaDescriptor.HIERARCHY_INDEXED,
			EntitySchemaHierarchyIndexedDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			objectName,
			EntitySchemaDescriptor.PRICE_INDEXED,
			EntitySchemaPriceIndexedDataFetcher.getInstance()
		);

		schemaObjectBuilder.field(EntitySchemaDescriptor.ALL_ATTRIBUTES.to(this.fieldBuilderTransformer));
		this.buildingContext.registerDataFetcher(
			objectName,
			EntitySchemaDescriptor.ALL_ATTRIBUTES,
			AllAttributeSchemasDataFetcher.getInstance()
		);

		if (!entitySchema.getSortableAttributeCompounds().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				objectName,
				schemaObjectBuilder,
				buildSortableAttributeCompoundSchemasField(entitySchema)
			);
		}

		schemaObjectBuilder.field(SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS.to(this.fieldBuilderTransformer));
		this.buildingContext.registerDataFetcher(
			objectName,
			SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS,
			AllSortableAttributeCompoundSchemasDataFetcher.getInstance()
		);

		if (!entitySchema.getAssociatedData().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				objectName,
				schemaObjectBuilder,
				buildAssociatedDataSchemasField(entitySchema)
			);
		}

		schemaObjectBuilder.field(EntitySchemaDescriptor.ALL_ASSOCIATED_DATA.to(this.fieldBuilderTransformer));
		this.buildingContext.registerDataFetcher(
			objectName,
			EntitySchemaDescriptor.ALL_ASSOCIATED_DATA,
			AllAssociatedDataSchemasDataFetcher.getInstance()
		);

		if (!entitySchema.getReferences().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				objectName,
				schemaObjectBuilder,
				buildReferenceSchemasField(entitySchema)
			);
		}

		schemaObjectBuilder.field(EntitySchemaDescriptor.ALL_REFERENCES.to(this.fieldBuilderTransformer));
		this.buildingContext.registerDataFetcher(
			objectName,
			EntitySchemaDescriptor.ALL_REFERENCES,
			AllReferenceSchemasDataFetcher.getInstance()
		);

		return schemaObjectBuilder.build();
	}

	/*
		Attributes
	 */

	@Nonnull
	private GraphQLObjectType buildAttributeSchemaObject() {
		this.buildingContext.registerDataFetcher(
			AttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.TYPE,
			AttributeSchemaTypeDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			AttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.UNIQUENESS_TYPE,
			AttributeSchemaUniquenessTypeDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			AttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.FILTERABLE,
			AttributeSchemaFilterableDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			AttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.SORTABLE,
			AttributeSchemaSortableDataFetcher.getInstance()
		);

		return AttributeSchemaDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildEntityAttributeSchemaObject() {
		this.buildingContext.registerDataFetcher(
			EntityAttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.TYPE,
			AttributeSchemaTypeDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			EntityAttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.UNIQUENESS_TYPE,
			AttributeSchemaUniquenessTypeDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			EntityAttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.FILTERABLE,
			AttributeSchemaFilterableDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			EntityAttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.SORTABLE,
			AttributeSchemaSortableDataFetcher.getInstance()
		);

		return EntityAttributeSchemaDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildGlobalAttributeSchemaObject() {
		this.buildingContext.registerDataFetcher(
			GlobalAttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.TYPE,
			AttributeSchemaTypeDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			GlobalAttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.UNIQUENESS_TYPE,
			AttributeSchemaUniquenessTypeDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			GlobalAttributeSchemaDescriptor.THIS,
			GlobalAttributeSchemaDescriptor.GLOBAL_UNIQUENESS_TYPE,
			AttributeSchemaGlobalUniquenessTypeDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			GlobalAttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.FILTERABLE,
			AttributeSchemaFilterableDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			GlobalAttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.SORTABLE,
			AttributeSchemaSortableDataFetcher.getInstance()
		);

		return GlobalAttributeSchemaDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private GraphQLUnionType buildAttributeSchemaUnion(@Nonnull GraphQLObjectType attributeSchemaObject,
													   @Nonnull GraphQLObjectType entityAttributeSchemaObject,
	                                                   @Nonnull GraphQLObjectType globalAttributeSchemaObject) {
		final GraphQLUnionType attributeSchemaUnion = AttributeSchemaUnionDescriptor.THIS
			.to(this.unionBuilderTransformer)
			.possibleType(attributeSchemaObject)
			.possibleType(entityAttributeSchemaObject)
			.possibleType(globalAttributeSchemaObject)
			.build();

		final TypeResolver attributeSchemaUnionResolver = env -> {
			if (env.getObject() instanceof GlobalAttributeSchemaContract) {
				return globalAttributeSchemaObject;
			} else if (env.getObject() instanceof EntityAttributeSchemaContract) {
				return entityAttributeSchemaObject;
			} else {
				return attributeSchemaObject;
			}
		};
		this.buildingContext.registerTypeResolver(attributeSchemaUnion, attributeSchemaUnionResolver);

		return attributeSchemaUnion;
	}

	@Nonnull
	private BuiltFieldDescriptor buildAttributeSchemasField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLObjectType attributeSchemasObject = buildAttributeSchemasObject(entitySchema);

		final GraphQLFieldDefinition attributeSchemasField = EntitySchemaDescriptor.ATTRIBUTES
			.to(this.fieldBuilderTransformer)
			.type(nonNull(attributeSchemasObject))
			.build();

		return new BuiltFieldDescriptor(
			attributeSchemasField,
			AttributeSchemasDataFetcher.getInstance()
		);
	}

	@Nonnull
	private GraphQLObjectType buildAttributeSchemasObject(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = AttributeSchemasDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder attributeSchemasObjectBuilder = newObject()
			.name(objectName)
			.description(AttributeSchemasDescriptor.THIS.description());

		entitySchema.getAttributes()
			.values()
			.forEach(attributeSchema ->
				this.buildingContext.registerFieldToObject(
					objectName,
					attributeSchemasObjectBuilder,
					buildAttributeSchemaField(attributeSchema)
				)
			);

		return attributeSchemasObjectBuilder.build();
	}

	@Nonnull
	private static BuiltFieldDescriptor buildAttributeSchemaField(@Nonnull AttributeSchemaContract attributeSchema) {
		final GraphQLOutputType attributeSchemaType;
		if (attributeSchema instanceof GlobalAttributeSchemaContract) {
			attributeSchemaType = nonNull(typeRef(GlobalAttributeSchemaDescriptor.THIS.name()));
		} else if (attributeSchema instanceof EntityAttributeSchemaContract) {
			attributeSchemaType = nonNull(typeRef(EntityAttributeSchemaDescriptor.THIS.name()));
		} else {
			attributeSchemaType = nonNull(typeRef(AttributeSchemaDescriptor.THIS.name()));
		}

		final GraphQLFieldDefinition attributeSchemaField = newFieldDefinition()
			.name(attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(attributeSchema.getDescription())
			.deprecate(attributeSchema.getDeprecationNotice())
			.type(attributeSchemaType)
			.build();

		return new BuiltFieldDescriptor(
			attributeSchemaField,
			new AttributeSchemaDataFetcher(attributeSchema.getName())
		);
	}

	/*
	    Sortable attribute compounds
	 */

	@Nonnull
	private BuiltFieldDescriptor buildSortableAttributeCompoundSchemasField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLObjectType object = buildSortableAttributeCompoundSchemasObject(entitySchema);

		final GraphQLFieldDefinition field = SortableAttributeCompoundsSchemaProviderDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS
			.to(this.fieldBuilderTransformer)
			.type(nonNull(object))
			.build();

		return new BuiltFieldDescriptor(field, SortableAttributeCompoundSchemasDataFetcher.getInstance());
	}

	@Nonnull
	private GraphQLObjectType buildSortableAttributeCompoundSchemasObject(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = SortableAttributeCompoundSchemasDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder objectBuilder = newObject()
			.name(objectName)
			.description(SortableAttributeCompoundSchemasDescriptor.THIS.description());

		entitySchema.getSortableAttributeCompounds()
			.values()
			.forEach(sortableAttributeCompoundSchema ->
				this.buildingContext.registerFieldToObject(
					objectName,
					objectBuilder,
					buildSortableAttributeCompoundSchemaField(sortableAttributeCompoundSchema)
				)
			);

		return objectBuilder.build();
	}

	@Nonnull
	private static BuiltFieldDescriptor buildSortableAttributeCompoundSchemaField(@Nonnull SortableAttributeCompoundSchemaContract sortableAttributeCompoundSchema) {
		final GraphQLFieldDefinition field = newFieldDefinition()
			.name(sortableAttributeCompoundSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(sortableAttributeCompoundSchema.getDescription())
			.deprecate(sortableAttributeCompoundSchema.getDeprecationNotice())
			.type(nonNull(typeRef(SortableAttributeCompoundSchemaDescriptor.THIS.name())))
			.build();

		return new BuiltFieldDescriptor(
			field,
			new SortableAttributeCompoundSchemaDataFetcher(sortableAttributeCompoundSchema.getName())
		);
	}

	/*
		Associated data
	 */

	@Nonnull
	private GraphQLObjectType buildAssociatedDataSchemaObject() {
		this.buildingContext.registerDataFetcher(
			AssociatedDataSchemaDescriptor.THIS,
			AssociatedDataSchemaDescriptor.TYPE,
			AssociatedDataSchemaTypeDataFetcher.getInstance()
		);

		return AssociatedDataSchemaDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildAssociatedDataSchemasField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLObjectType associatedDataSchemasObject = buildAssociatedDataSchemasObject(entitySchema);

		final GraphQLFieldDefinition associatedDataSchemasField = EntitySchemaDescriptor.ASSOCIATED_DATA
			.to(this.fieldBuilderTransformer)
			.type(nonNull(associatedDataSchemasObject))
			.build();

		return new BuiltFieldDescriptor(
			associatedDataSchemasField,
			AssociatedDataSchemasDataFetcher.getInstance()
		);
	}

	@Nonnull
	private GraphQLObjectType buildAssociatedDataSchemasObject(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = AssociatedDataSchemasDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder associatedDataSchemasObjectBuilder = newObject()
			.name(objectName)
			.description(AssociatedDataSchemasDescriptor.THIS.description());

		entitySchema.getAssociatedData()
			.values()
			.forEach(associatedDataSchema ->
				this.buildingContext.registerFieldToObject(
					objectName,
					associatedDataSchemasObjectBuilder,
					buildAssociatedDataSchemaField(associatedDataSchema)
				)
			);

		return associatedDataSchemasObjectBuilder.build();
	}

	@Nonnull
	private static BuiltFieldDescriptor buildAssociatedDataSchemaField(@Nonnull AssociatedDataSchemaContract associatedDataSchema) {
		final GraphQLFieldDefinition associatedDataSchemaField = newFieldDefinition()
			.name(associatedDataSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(associatedDataSchema.getDescription())
			.deprecate(associatedDataSchema.getDeprecationNotice())
			.type(nonNull(typeRef(AssociatedDataSchemaDescriptor.THIS.name())))
			.build();

		return new BuiltFieldDescriptor(
			associatedDataSchemaField,
			new AssociatedDataSchemaDataFetcher(associatedDataSchema.getName())
		);
	}

	/*
		References
	 */

	@Nonnull
	private GraphQLObjectType buildGenericReferenceSchemaObject() {
		this.buildingContext.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			ReferenceSchemaDescriptor.ENTITY_TYPE_NAME_VARIANTS,
			new AsyncDataFetcher(
				ReferenceSchemaEntityTypeNameVariantsDataFetcher.getInstance(),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
		this.buildingContext.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			ReferenceSchemaDescriptor.GROUP_TYPE_NAME_VARIANTS,
			new AsyncDataFetcher(
				ReferenceSchemaGroupTypeNameVariantsDataFetcher.getInstance(),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
		this.buildingContext.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			ReferenceSchemaDescriptor.ALL_ATTRIBUTES,
			AllAttributeSchemasDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			ReferenceSchemaDescriptor.INDEXED,
			ReferenceSchemaIndexedDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			ReferenceSchemaDescriptor.FACETED,
			ReferenceSchemaFacetedDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS,
			AllSortableAttributeCompoundSchemasDataFetcher.getInstance()
		);

		return ReferenceSchemaDescriptor.THIS_GENERIC
			.to(this.objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceSchemasField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLObjectType referenceSchemasObject = buildReferenceSchemasObject(entitySchema);

		final GraphQLFieldDefinition referenceSchemasField = EntitySchemaDescriptor.REFERENCES
			.to(this.fieldBuilderTransformer)
			.type(nonNull(referenceSchemasObject))
			.build();

		return new BuiltFieldDescriptor(
			referenceSchemasField,
			ReferenceSchemasDataFetcher.getInstance()
		);
	}

	@Nonnull
	private GraphQLObjectType buildReferenceSchemasObject(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = ReferenceSchemasDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder referenceSchemasObjectBuilder = newObject()
			.name(objectName)
			.description(ReferenceSchemasDescriptor.THIS.description());

		entitySchema.getReferences()
			.values()
			.forEach(referenceSchema ->
				this.buildingContext.registerFieldToObject(
					objectName,
					referenceSchemasObjectBuilder,
					buildReferenceSchemaField(entitySchema, referenceSchema)
				)
			);

		return referenceSchemasObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceSchemaField(@Nonnull EntitySchemaContract entitySchema,
	                                                       @Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLObjectType referenceSchemaObject = buildReferenceSchemaObject(entitySchema, referenceSchema);

		final GraphQLFieldDefinition referenceSchemaField = newFieldDefinition()
			.name(referenceSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(referenceSchema.getDescription())
			.deprecate(referenceSchema.getDeprecationNotice())
			.type(nonNull(referenceSchemaObject))
			.build();

		return new BuiltFieldDescriptor(
			referenceSchemaField,
			new ReferenceSchemaDataFetcher(referenceSchema.getName())
		);
	}

	@Nonnull
	private GraphQLObjectType buildReferenceSchemaObject(@Nonnull EntitySchemaContract entitySchema,
	                                                     @Nonnull ReferenceSchemaContract referenceSchema) {
		final String objectName = ReferenceSchemaDescriptor.THIS_SPECIFIC.name(entitySchema, referenceSchema);

		final GraphQLObjectType.Builder referenceSchemaObjectBuilder = ReferenceSchemaDescriptor.THIS_SPECIFIC
			.to(this.objectBuilderTransformer)
			.name(objectName)
			.field(ReferenceSchemaDescriptor.ALL_ATTRIBUTES.to(this.fieldBuilderTransformer))
			.field(SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS.to(this.fieldBuilderTransformer));

		this.buildingContext.registerDataFetcher(
			objectName,
			ReferenceSchemaDescriptor.ENTITY_TYPE_NAME_VARIANTS,
			new AsyncDataFetcher(
				ReferenceSchemaEntityTypeNameVariantsDataFetcher.getInstance(),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
		this.buildingContext.registerDataFetcher(
			objectName,
			ReferenceSchemaDescriptor.GROUP_TYPE_NAME_VARIANTS,
			new AsyncDataFetcher(
				ReferenceSchemaGroupTypeNameVariantsDataFetcher.getInstance(),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);

		if (!referenceSchema.getAttributes().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				objectName,
				referenceSchemaObjectBuilder,
				buildReferenceAttributeSchemasField(entitySchema, referenceSchema)
			);
		}
		this.buildingContext.registerDataFetcher(
			objectName,
			ReferenceSchemaDescriptor.ALL_ATTRIBUTES,
			AllAttributeSchemasDataFetcher.getInstance()
		);

		if (!referenceSchema.getSortableAttributeCompounds().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				objectName,
				referenceSchemaObjectBuilder,
				buildReferenceSortableAttributeCompoundSchemasField(entitySchema, referenceSchema)
			);
		}
		this.buildingContext.registerDataFetcher(
			objectName,
			ReferenceSchemaDescriptor.INDEXED,
			ReferenceSchemaIndexedDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			objectName,
			ReferenceSchemaDescriptor.FACETED,
			ReferenceSchemaFacetedDataFetcher.getInstance()
		);
		this.buildingContext.registerDataFetcher(
			objectName,
			SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS,
			AllSortableAttributeCompoundSchemasDataFetcher.getInstance()
		);

		return referenceSchemaObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceAttributeSchemasField(@Nonnull EntitySchemaContract entitySchema,
	                                                                 @Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLObjectType attributeSchemasObject = buildReferenceAttributeSchemasObject(
			entitySchema,
			referenceSchema
		);

		final GraphQLFieldDefinition attributeSchemasField = ReferenceSchemaDescriptor.ATTRIBUTES
			.to(this.fieldBuilderTransformer)
			.type(nonNull(attributeSchemasObject))
			.build();

		return new BuiltFieldDescriptor(
			attributeSchemasField,
			AttributeSchemasDataFetcher.getInstance()
		);
	}

	@Nonnull
	private GraphQLObjectType buildReferenceAttributeSchemasObject(@Nonnull EntitySchemaContract entitySchema,
	                                                               @Nonnull ReferenceSchemaContract referenceSchema) {
		final String objectName = AttributeSchemasDescriptor.THIS.name(entitySchema, referenceSchema);

		final GraphQLObjectType.Builder attributeSchemasObjectBuilder =  AttributeSchemasDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(objectName);

		referenceSchema.getAttributes()
			.values()
			.forEach(attributeSchema ->
				this.buildingContext.registerFieldToObject(
					objectName,
					attributeSchemasObjectBuilder,
					buildAttributeSchemaField(attributeSchema)
				)
			);

		return attributeSchemasObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceSortableAttributeCompoundSchemasField(@Nonnull EntitySchemaContract entitySchema,
	                                                                                 @Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLObjectType object = buildReferenceSortableAttributeCompoundSchemasObject(
			entitySchema,
			referenceSchema
		);

		final GraphQLFieldDefinition field = SortableAttributeCompoundsSchemaProviderDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS
			.to(this.fieldBuilderTransformer)
			.type(nonNull(object))
			.build();

		return new BuiltFieldDescriptor(field, SortableAttributeCompoundSchemasDataFetcher.getInstance());
	}

	@Nonnull
	private GraphQLObjectType buildReferenceSortableAttributeCompoundSchemasObject(@Nonnull EntitySchemaContract entitySchema,
	                                                                               @Nonnull ReferenceSchemaContract referenceSchema) {
		final String objectName = SortableAttributeCompoundSchemasDescriptor.THIS.name(entitySchema, referenceSchema);

		final GraphQLObjectType.Builder objectBuilder =  SortableAttributeCompoundSchemasDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(objectName);

		referenceSchema.getSortableAttributeCompounds()
			.values()
			.forEach(sortableAttributeCompoundSchema ->
				this.buildingContext.registerFieldToObject(
					objectName,
					objectBuilder,
					buildSortableAttributeCompoundSchemaField(sortableAttributeCompoundSchema)
				)
			);

		return objectBuilder.build();
	}

	/*
		Sortable compounds
	 */

	@Nonnull
	private GraphQLObjectType buildSortableAttributeCompoundSchemaObject() {
		this.buildingContext.registerDataFetcher(
			SortableAttributeCompoundSchemaDescriptor.THIS,
			SortableAttributeCompoundSchemaDescriptor.INDEXED,
			SortableAttributeCompoundSchemaIndexedDataFetcher.getInstance()
		);

		return SortableAttributeCompoundSchemaDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.build();
	}

	/*
		Mutations
	 */

	@Nonnull
	private BuiltFieldDescriptor buildUpdateEntitySchemaField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLFieldDefinition catalogSchemaField = CatalogSchemaApiRootDescriptor.UPDATE_ENTITY_SCHEMA
			.to(new EndpointDescriptorToGraphQLFieldTransformer(this.propertyDataTypeBuilderTransformer, entitySchema))
			.type(nonNull(typeRef(EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema))))
			.argument(UpdateEntitySchemaQueryHeaderDescriptor.MUTATIONS.to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			catalogSchemaField,
			new AsyncDataFetcher(
				new UpdateEntitySchemaMutatingDataFetcher(entitySchema),
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
	private BuiltFieldDescriptor buildOnEntitySchemaChangeField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLFieldDefinition onEntitySchemaChangeField = GraphQLCatalogSchemaApiRootDescriptor.ON_COLLECTION_SCHEMA_CHANGE
			.to(new EndpointDescriptorToGraphQLFieldTransformer(this.propertyDataTypeBuilderTransformer, entitySchema))
			.argument(OnCollectionSchemaChangeHeaderDescriptor.SINCE_VERSION.to(this.argumentBuilderTransformer))
			.argument(OnCollectionSchemaChangeHeaderDescriptor.SINCE_INDEX.to(this.argumentBuilderTransformer))
			.argument(OnCollectionSchemaChangeHeaderDescriptor.OPERATION.to(this.argumentBuilderTransformer))
			.argument(OnCollectionSchemaChangeHeaderDescriptor.CONTAINER_TYPE.to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			onEntitySchemaChangeField,
			new OnCollectionSchemaChangeCaptureSubscribingDataFetcher(this.buildingContext.getEvita(), entitySchema)
		);
	}
}
