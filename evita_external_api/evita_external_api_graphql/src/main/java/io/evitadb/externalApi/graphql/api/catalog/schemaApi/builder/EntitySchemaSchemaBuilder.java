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
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLUnionType;
import graphql.schema.TypeResolver;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.EntitySchemaMutationAggregateDescriptor;
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
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ReferenceSortableAttributeCompoundSchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutationDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.builder.PartialGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.UpdateEntitySchemaQueryHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.*;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.mutatingDataFetcher.UpdateEntitySchemaMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.model.EndpointDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.AsyncDataFetcher;

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
		buildingContext.registerType(ScopedAttributeUniquenessTypeDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ScopedAttributeUniquenessTypeDescriptor.THIS_INPUT.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ScopedGlobalAttributeUniquenessTypeDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ScopedGlobalAttributeUniquenessTypeDescriptor.THIS_INPUT.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ScopedReferenceIndexTypeDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ScopedReferenceIndexTypeDescriptor.THIS_INPUT.to(inputObjectBuilderTransformer).build());
		final GraphQLObjectType attributeSchemaObject = buildAttributeSchemaObject();
		buildingContext.registerType(attributeSchemaObject);
		final GraphQLObjectType entityAttributeSchemaObject= buildEntityAttributeSchemaObject();
		buildingContext.registerType(entityAttributeSchemaObject);
		final GraphQLObjectType globalAttributeSchemaObject = buildGlobalAttributeSchemaObject();
		buildingContext.registerType(globalAttributeSchemaObject);
		buildingContext.registerType(buildAttributeSchemaUnion(attributeSchemaObject, entityAttributeSchemaObject, globalAttributeSchemaObject));
		buildingContext.registerType(AttributeElementDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(buildSortableAttributeCompoundSchemaObject());
		buildingContext.registerType(buildAssociatedDataSchemaObject());
		buildingContext.registerType(buildGenericReferenceSchemaObject());

		// entity schema mutations
		buildingContext.registerType(AllowCurrencyInEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(AllowEvolutionModeInEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(AllowLocaleInEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(CreateEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(DisallowCurrencyInEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(DisallowEvolutionModeInEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(DisallowLocaleInEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyEntitySchemaDeprecationNoticeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyEntitySchemaDescriptionMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyEntitySchemaNameMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(RemoveEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetEntitySchemaWithHierarchyMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetEntitySchemaWithPriceMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());

		// associated data schema mutations
		buildingContext.registerType(CreateAssociatedDataSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyAssociatedDataSchemaDescriptionMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyAssociatedDataSchemaNameMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyAssociatedDataSchemaTypeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(RemoveAssociatedDataSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetAssociatedDataSchemaLocalizedMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetAssociatedDataSchemaNullableMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());

		// attribute schema mutations
		buildingContext.registerType(CreateAttributeSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyAttributeSchemaDefaultValueMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyAttributeSchemaDeprecationNoticeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyAttributeSchemaDescriptionMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyAttributeSchemaNameMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyAttributeSchemaTypeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(RemoveAttributeSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetAttributeSchemaFilterableMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetAttributeSchemaLocalizedMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetAttributeSchemaNullableMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetAttributeSchemaRepresentativeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetAttributeSchemaSortableMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(UseGlobalAttributeSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ReferenceAttributeSchemaMutationAggregateDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetAttributeSchemaUniqueMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());

		// sortable attribute compound schema mutations
		buildingContext.registerType(AttributeElementDescriptor.THIS_INPUT.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(CreateSortableAttributeCompoundSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifySortableAttributeCompoundSchemaDeprecationNoticeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifySortableAttributeCompoundSchemaNameMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetSortableAttributeCompoundIndexedMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(RemoveSortableAttributeCompoundSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ReferenceSortableAttributeCompoundSchemaMutationAggregateDescriptor.THIS.to(inputObjectBuilderTransformer).build());

		// reference schema mutations
		buildingContext.registerType(CreateReferenceSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(CreateReflectedReferenceSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyReferenceAttributeSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyReferenceSchemaCardinalityMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyReferenceSchemaDeprecationNoticeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyReferenceSchemaDescriptionMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyReferenceSchemaNameMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyReferenceSchemaRelatedEntityGroupMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyReferenceSchemaRelatedEntityMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(ModifyReflectedReferenceAttributeInheritanceSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(RemoveReferenceSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetReferenceSchemaFacetedMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		buildingContext.registerType(SetReferenceSchemaIndexedMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());

		buildingContext.registerType(EntitySchemaMutationAggregateDescriptor.THIS.to(inputObjectBuilderTransformer).build());

		// build collection-specific field and objects
		buildingContext.getEntitySchemas().forEach(entitySchema -> {
			buildingContext.registerType(buildEntitySchemaObject(entitySchema));
			buildingContext.registerQueryField(buildEntitySchemaField(entitySchema));
			buildingContext.registerMutationField(buildUpdateEntitySchemaField(entitySchema));
		});
	}

	/*
		Entity schema
	 */

	@Nonnull
	private BuiltFieldDescriptor buildEntitySchemaField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLFieldDefinition entitySchemaField = CatalogSchemaApiRootDescriptor.GET_ENTITY_SCHEMA
			.to(new EndpointDescriptorToGraphQLFieldTransformer(propertyDataTypeBuilderTransformer, entitySchema))
			.description(CatalogSchemaApiRootDescriptor.GET_ENTITY_SCHEMA.description(entitySchema.getName()))
			.type(nonNull(typeRef(EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema))))
			.build();

		return new BuiltFieldDescriptor(
			entitySchemaField,
			new AsyncDataFetcher(
				new EntitySchemaDataFetcher(entitySchema.getName()),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private GraphQLObjectType buildEntitySchemaObject(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema);

		final GraphQLObjectType.Builder schemaObjectBuilder = EntitySchemaDescriptor.THIS_SPECIFIC
			.to(objectBuilderTransformer)
			.name(objectName);

		if (!entitySchema.getAttributes().isEmpty()) {
			buildingContext.registerFieldToObject(
				objectName,
				schemaObjectBuilder,
				buildAttributeSchemasField(entitySchema)
			);
		}

		buildingContext.registerDataFetcher(
			objectName,
			EntitySchemaDescriptor.HIERARCHY_INDEXED,
			EntitySchemaHierarchyIndexedDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			objectName,
			EntitySchemaDescriptor.PRICE_INDEXED,
			EntitySchemaPriceIndexedDataFetcher.getInstance()
		);

		schemaObjectBuilder.field(EntitySchemaDescriptor.ALL_ATTRIBUTES.to(fieldBuilderTransformer));
		buildingContext.registerDataFetcher(
			objectName,
			EntitySchemaDescriptor.ALL_ATTRIBUTES,
			AllAttributeSchemasDataFetcher.getInstance()
		);

		if (!entitySchema.getSortableAttributeCompounds().isEmpty()) {
			buildingContext.registerFieldToObject(
				objectName,
				schemaObjectBuilder,
				buildSortableAttributeCompoundSchemasField(entitySchema)
			);
		}

		schemaObjectBuilder.field(SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS.to(fieldBuilderTransformer));
		buildingContext.registerDataFetcher(
			objectName,
			SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS,
			AllSortableAttributeCompoundSchemasDataFetcher.getInstance()
		);

		if (!entitySchema.getAssociatedData().isEmpty()) {
			buildingContext.registerFieldToObject(
				objectName,
				schemaObjectBuilder,
				buildAssociatedDataSchemasField(entitySchema)
			);
		}

		schemaObjectBuilder.field(EntitySchemaDescriptor.ALL_ASSOCIATED_DATA.to(fieldBuilderTransformer));
		buildingContext.registerDataFetcher(
			objectName,
			EntitySchemaDescriptor.ALL_ASSOCIATED_DATA,
			AllAssociatedDataSchemasDataFetcher.getInstance()
		);

		if (!entitySchema.getReferences().isEmpty()) {
			buildingContext.registerFieldToObject(
				objectName,
				schemaObjectBuilder,
				buildReferenceSchemasField(entitySchema)
			);
		}

		schemaObjectBuilder.field(EntitySchemaDescriptor.ALL_REFERENCES.to(fieldBuilderTransformer));
		buildingContext.registerDataFetcher(
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
		buildingContext.registerDataFetcher(
			AttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.TYPE,
			AttributeSchemaTypeDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			AttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.UNIQUENESS_TYPE,
			AttributeSchemaUniquenessTypeDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			AttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.FILTERABLE,
			AttributeSchemaFilterableDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			AttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.SORTABLE,
			AttributeSchemaSortableDataFetcher.getInstance()
		);

		return AttributeSchemaDescriptor.THIS
			.to(objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildEntityAttributeSchemaObject() {
		buildingContext.registerDataFetcher(
			EntityAttributeSchemaDescriptor.THIS,
			EntityAttributeSchemaDescriptor.TYPE,
			AttributeSchemaTypeDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			EntityAttributeSchemaDescriptor.THIS,
			EntityAttributeSchemaDescriptor.UNIQUENESS_TYPE,
			AttributeSchemaUniquenessTypeDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			EntityAttributeSchemaDescriptor.THIS,
			EntityAttributeSchemaDescriptor.FILTERABLE,
			AttributeSchemaFilterableDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			EntityAttributeSchemaDescriptor.THIS,
			EntityAttributeSchemaDescriptor.SORTABLE,
			AttributeSchemaSortableDataFetcher.getInstance()
		);

		return EntityAttributeSchemaDescriptor.THIS
			.to(objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildGlobalAttributeSchemaObject() {
		buildingContext.registerDataFetcher(
			GlobalAttributeSchemaDescriptor.THIS,
			GlobalAttributeSchemaDescriptor.TYPE,
			AttributeSchemaTypeDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			GlobalAttributeSchemaDescriptor.THIS,
			GlobalAttributeSchemaDescriptor.UNIQUENESS_TYPE,
			AttributeSchemaUniquenessTypeDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			GlobalAttributeSchemaDescriptor.THIS,
			GlobalAttributeSchemaDescriptor.GLOBAL_UNIQUENESS_TYPE,
			AttributeSchemaGlobalUniquenessTypeDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			GlobalAttributeSchemaDescriptor.THIS,
			GlobalAttributeSchemaDescriptor.FILTERABLE,
			AttributeSchemaFilterableDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			GlobalAttributeSchemaDescriptor.THIS,
			GlobalAttributeSchemaDescriptor.SORTABLE,
			AttributeSchemaSortableDataFetcher.getInstance()
		);

		return GlobalAttributeSchemaDescriptor.THIS
			.to(objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private GraphQLUnionType buildAttributeSchemaUnion(@Nonnull GraphQLObjectType attributeSchemaObject,
													   @Nonnull GraphQLObjectType entityAttributeSchemaObject,
	                                                   @Nonnull GraphQLObjectType globalAttributeSchemaObject) {
		final GraphQLUnionType attributeSchemaUnion = AttributeSchemaUnionDescriptor.THIS
			.to(unionBuilderTransformer)
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
		buildingContext.registerTypeResolver(attributeSchemaUnion, attributeSchemaUnionResolver);

		return attributeSchemaUnion;
	}

	@Nonnull
	private BuiltFieldDescriptor buildAttributeSchemasField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLObjectType attributeSchemasObject = buildAttributeSchemasObject(entitySchema);

		final GraphQLFieldDefinition attributeSchemasField = EntitySchemaDescriptor.ATTRIBUTES
			.to(fieldBuilderTransformer)
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
				buildingContext.registerFieldToObject(
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
			.to(fieldBuilderTransformer)
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
				buildingContext.registerFieldToObject(
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
		buildingContext.registerDataFetcher(
			AssociatedDataSchemaDescriptor.THIS,
			AssociatedDataSchemaDescriptor.TYPE,
			AssociatedDataSchemaTypeDataFetcher.getInstance()
		);

		return AssociatedDataSchemaDescriptor.THIS
			.to(objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildAssociatedDataSchemasField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLObjectType associatedDataSchemasObject = buildAssociatedDataSchemasObject(entitySchema);

		final GraphQLFieldDefinition associatedDataSchemasField = EntitySchemaDescriptor.ASSOCIATED_DATA
			.to(fieldBuilderTransformer)
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
				buildingContext.registerFieldToObject(
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
		buildingContext.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			ReferenceSchemaDescriptor.ENTITY_TYPE_NAME_VARIANTS,
			new AsyncDataFetcher(
				ReferenceSchemaEntityTypeNameVariantsDataFetcher.getInstance(),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
		buildingContext.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			ReferenceSchemaDescriptor.GROUP_TYPE_NAME_VARIANTS,
			new AsyncDataFetcher(
				ReferenceSchemaGroupTypeNameVariantsDataFetcher.getInstance(),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
		buildingContext.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			ReferenceSchemaDescriptor.ALL_ATTRIBUTES,
			AllAttributeSchemasDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			ReferenceSchemaDescriptor.INDEXED,
			ReferenceSchemaIndexedDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			ReferenceSchemaDescriptor.FACETED,
			ReferenceSchemaFacetedDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS,
			AllSortableAttributeCompoundSchemasDataFetcher.getInstance()
		);

		return ReferenceSchemaDescriptor.THIS_GENERIC
			.to(objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceSchemasField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLObjectType referenceSchemasObject = buildReferenceSchemasObject(entitySchema);

		final GraphQLFieldDefinition referenceSchemasField = EntitySchemaDescriptor.REFERENCES
			.to(fieldBuilderTransformer)
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
				buildingContext.registerFieldToObject(
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
			.to(objectBuilderTransformer)
			.name(objectName)
			.field(ReferenceSchemaDescriptor.ALL_ATTRIBUTES.to(fieldBuilderTransformer))
			.field(SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS.to(fieldBuilderTransformer));

		buildingContext.registerDataFetcher(
			objectName,
			ReferenceSchemaDescriptor.ENTITY_TYPE_NAME_VARIANTS,
			new AsyncDataFetcher(
				ReferenceSchemaEntityTypeNameVariantsDataFetcher.getInstance(),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
		buildingContext.registerDataFetcher(
			objectName,
			ReferenceSchemaDescriptor.GROUP_TYPE_NAME_VARIANTS,
			new AsyncDataFetcher(
				ReferenceSchemaGroupTypeNameVariantsDataFetcher.getInstance(),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);

		if (!referenceSchema.getAttributes().isEmpty()) {
			buildingContext.registerFieldToObject(
				objectName,
				referenceSchemaObjectBuilder,
				buildReferenceAttributeSchemasField(entitySchema, referenceSchema)
			);
		}
		buildingContext.registerDataFetcher(
			objectName,
			ReferenceSchemaDescriptor.ALL_ATTRIBUTES,
			AllAttributeSchemasDataFetcher.getInstance()
		);

		if (!referenceSchema.getSortableAttributeCompounds().isEmpty()) {
			buildingContext.registerFieldToObject(
				objectName,
				referenceSchemaObjectBuilder,
				buildReferenceSortableAttributeCompoundSchemasField(entitySchema, referenceSchema)
			);
		}
		buildingContext.registerDataFetcher(
			objectName,
			ReferenceSchemaDescriptor.INDEXED,
			ReferenceSchemaIndexedDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
			objectName,
			ReferenceSchemaDescriptor.FACETED,
			ReferenceSchemaFacetedDataFetcher.getInstance()
		);
		buildingContext.registerDataFetcher(
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
			.to(fieldBuilderTransformer)
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
			.to(objectBuilderTransformer)
			.name(objectName);

		referenceSchema.getAttributes()
			.values()
			.forEach(attributeSchema ->
				buildingContext.registerFieldToObject(
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
			.to(fieldBuilderTransformer)
			.type(nonNull(object))
			.build();

		return new BuiltFieldDescriptor(field, SortableAttributeCompoundSchemasDataFetcher.getInstance());
	}

	@Nonnull
	private GraphQLObjectType buildReferenceSortableAttributeCompoundSchemasObject(@Nonnull EntitySchemaContract entitySchema,
	                                                                               @Nonnull ReferenceSchemaContract referenceSchema) {
		final String objectName = SortableAttributeCompoundSchemasDescriptor.THIS.name(entitySchema, referenceSchema);

		final GraphQLObjectType.Builder objectBuilder =  SortableAttributeCompoundSchemasDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		referenceSchema.getSortableAttributeCompounds()
			.values()
			.forEach(sortableAttributeCompoundSchema ->
				buildingContext.registerFieldToObject(
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
		buildingContext.registerDataFetcher(
			SortableAttributeCompoundSchemaDescriptor.THIS,
			SortableAttributeCompoundSchemaDescriptor.INDEXED,
			SortableAttributeCompoundSchemaIndexedDataFetcher.getInstance()
		);

		return SortableAttributeCompoundSchemaDescriptor.THIS
			.to(objectBuilderTransformer)
			.build();
	}

	/*
		Mutations
	 */

	@Nonnull
	private BuiltFieldDescriptor buildUpdateEntitySchemaField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLFieldDefinition catalogSchemaField = CatalogSchemaApiRootDescriptor.UPDATE_ENTITY_SCHEMA
			.to(new EndpointDescriptorToGraphQLFieldTransformer(propertyDataTypeBuilderTransformer, entitySchema))
			.type(nonNull(typeRef(EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema))))
			.argument(UpdateEntitySchemaQueryHeaderDescriptor.MUTATIONS.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			catalogSchemaField,
			new AsyncDataFetcher(
				new UpdateEntitySchemaMutatingDataFetcher(entitySchema),
				buildingContext.getConfig(),
				buildingContext.getTracingContext(),
				buildingContext.getEvita()
			)
		);
	}
}
