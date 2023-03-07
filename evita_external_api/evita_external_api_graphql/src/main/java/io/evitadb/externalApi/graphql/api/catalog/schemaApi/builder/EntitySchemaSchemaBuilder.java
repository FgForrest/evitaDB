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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.builder;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLUnionType;
import graphql.schema.TypeResolver;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
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
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.builder.PartialGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher.*;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.mutatingDataFetcher.UpdateEntitySchemaMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.model.EndpointDescriptorToGraphQLFieldTransformer;

import javax.annotation.Nonnull;

import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLObjectType.newObject;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static graphql.schema.GraphQLUnionType.newUnionType;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;

/**
 * Implementation of {@link PartialGraphQLSchemaBuilder} for building schema for fetching and updating {@link EntitySchemaContract}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class EntitySchemaSchemaBuilder extends PartialGraphQLSchemaBuilder<CatalogGraphQLSchemaBuildingContext> {

	public static final String ATTRIBUTE_SCHEMA_UNION_NAME = "AttributeSchemaUnion";

	public EntitySchemaSchemaBuilder(@Nonnull CatalogGraphQLSchemaBuildingContext catalogGraphQLSchemaBuildingContext) {
		super(catalogGraphQLSchemaBuildingContext);
	}

	@Override
	public void build() {
		// build common reusable types
		final GraphQLObjectType attributeSchemaObject = buildAttributeSchemaObject();
		context.registerType(attributeSchemaObject);
		final GraphQLObjectType globalAttributeSchemaObject = buildGlobalAttributeSchemaObject();
		context.registerType(globalAttributeSchemaObject);
		context.registerType(buildAttributeSchemaUnion(attributeSchemaObject, globalAttributeSchemaObject));
		context.registerType(buildAssociatedDataSchemaObject());
		context.registerType(buildGenericReferenceSchemaObject());

		// entity schema mutations
		context.registerType(AllowCurrencyInEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(AllowEvolutionModeInEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(AllowLocaleInEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(CreateEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(DisallowCurrencyInEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(DisallowEvolutionModeInEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(DisallowLocaleInEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyEntitySchemaDeprecationNoticeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyEntitySchemaDescriptionMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyEntitySchemaNameMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(RemoveEntitySchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetEntitySchemaWithHierarchyMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetEntitySchemaWithPriceMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());

		// associated data schema mutations
		context.registerType(CreateAssociatedDataSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyAssociatedDataSchemaDeprecationNoticeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyAssociatedDataSchemaDescriptionMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyAssociatedDataSchemaNameMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyAssociatedDataSchemaTypeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(RemoveAssociatedDataSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetAssociatedDataSchemaLocalizedMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetAssociatedDataSchemaNullableMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());

		// attribute schema mutations
		context.registerType(CreateAttributeSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyAttributeSchemaDefaultValueMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyAttributeSchemaDeprecationNoticeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyAttributeSchemaDescriptionMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyAttributeSchemaNameMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyAttributeSchemaTypeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(RemoveAttributeSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetAttributeSchemaFilterableMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetAttributeSchemaLocalizedMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetAttributeSchemaNullableMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetAttributeSchemaSortableMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetAttributeSchemaUniqueMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(UseGlobalAttributeSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ReferenceAttributeSchemaMutationAggregateDescriptor.THIS.to(inputObjectBuilderTransformer).build());

		// reference schema mutations
		context.registerType(CreateReferenceSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyReferenceAttributeSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyReferenceSchemaCardinalityMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyReferenceSchemaDeprecationNoticeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyReferenceSchemaDescriptionMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyReferenceSchemaNameMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyReferenceSchemaRelatedEntityGroupMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ModifyReferenceSchemaRelatedEntityMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(RemoveReferenceSchemaMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetReferenceSchemaFacetedMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetReferenceSchemaFilterableMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());

		context.registerType(EntitySchemaMutationAggregateDescriptor.THIS.to(inputObjectBuilderTransformer).build());

		// build collection-specific field and objects
		context.getEntitySchemas().forEach(entitySchema -> {
			context.registerType(buildEntitySchemaObject(entitySchema));
			context.registerQueryField(buildEntitySchemaField(entitySchema));
			context.registerMutationField(buildUpdateEntitySchemaField(entitySchema));
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
			new EntitySchemaDataFetcher(entitySchema.getName())
		);
	}

	@Nonnull
	private GraphQLObjectType buildEntitySchemaObject(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema);

		final GraphQLObjectType.Builder schemaObjectBuilder = EntitySchemaDescriptor.THIS_SPECIFIC
			.to(objectBuilderTransformer)
			.name(objectName);

		if (!entitySchema.getAttributes().isEmpty()) {
			context.registerFieldToObject(
				objectName,
				schemaObjectBuilder,
				buildAttributeSchemasField(entitySchema)
			);
		}

		schemaObjectBuilder.field(EntitySchemaDescriptor.ALL_ATTRIBUTES
			.to(fieldBuilderTransformer)
			.type(nonNull(list(nonNull(typeRef(ATTRIBUTE_SCHEMA_UNION_NAME))))));
		context.registerDataFetcher(
			objectName,
			EntitySchemaDescriptor.ALL_ATTRIBUTES,
			new AllAttributeSchemasDataFetcher()
		);

		if (!entitySchema.getAssociatedData().isEmpty()) {
			context.registerFieldToObject(
				objectName,
				schemaObjectBuilder,
				buildAssociatedDataSchemasField(entitySchema)
			);
		}

		schemaObjectBuilder.field(EntitySchemaDescriptor.ALL_ASSOCIATED_DATA.to(fieldBuilderTransformer));
		context.registerDataFetcher(
			objectName,
			EntitySchemaDescriptor.ALL_ASSOCIATED_DATA,
			new AllAssociatedDataSchemasDataFetcher()
		);

		if (!entitySchema.getReferences().isEmpty()) {
			context.registerFieldToObject(
				objectName,
				schemaObjectBuilder,
				buildReferenceSchemasField(entitySchema)
			);
		}

		schemaObjectBuilder.field(EntitySchemaDescriptor.ALL_REFERENCES.to(fieldBuilderTransformer));
		context.registerDataFetcher(
			objectName,
			EntitySchemaDescriptor.ALL_REFERENCES,
			new AllReferenceSchemasDataFetcher()
		);

		return schemaObjectBuilder.build();
	}

	/*
		Attributes
	 */

	@Nonnull
	private GraphQLObjectType buildAttributeSchemaObject() {
		context.registerDataFetcher(
			AttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.TYPE,
			new AttributeSchemaTypeDataFetcher()
		);

		return AttributeSchemaDescriptor.THIS
			.to(objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildGlobalAttributeSchemaObject() {
		context.registerDataFetcher(
			GlobalAttributeSchemaDescriptor.THIS,
			AttributeSchemaDescriptor.TYPE,
			new AttributeSchemaTypeDataFetcher()
		);

		return GlobalAttributeSchemaDescriptor.THIS
			.to(objectBuilderTransformer)
			.build();
	}

	@Nonnull
	private GraphQLUnionType buildAttributeSchemaUnion(@Nonnull GraphQLObjectType attributeSchemaObject,
	                                                    @Nonnull GraphQLObjectType globalAttributeSchemaObject) {
		final GraphQLUnionType attributeSchemaUnion = newUnionType()
			.name(ATTRIBUTE_SCHEMA_UNION_NAME)
			.possibleType(attributeSchemaObject)
			.possibleType(globalAttributeSchemaObject)
			.build();

		final TypeResolver attributeSchemaUnionResolver = env -> {
			if (env.getObject() instanceof GlobalAttributeSchemaContract) {
				return globalAttributeSchemaObject;
			} else {
				return attributeSchemaObject;
			}
		};
		context.registerTypeResolver(attributeSchemaUnion, attributeSchemaUnionResolver);

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
			new AttributeSchemasDataFetcher()
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
				context.registerFieldToObject(
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
		} else {
			attributeSchemaType = nonNull(typeRef(AttributeSchemaDescriptor.THIS.name()));
		}

		final GraphQLFieldDefinition attributeSchemaField = newFieldDefinition()
			.name(attributeSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
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
		Associated data
	 */

	@Nonnull
	private GraphQLObjectType buildAssociatedDataSchemaObject() {
		context.registerDataFetcher(
			AssociatedDataSchemaDescriptor.THIS,
			AssociatedDataSchemaDescriptor.TYPE,
			new AssociatedDataSchemaTypeDataFetcher()
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
			new AssociatedDataSchemasDataFetcher()
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
				context.registerFieldToObject(
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
			.name(associatedDataSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
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
		context.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			ReferenceSchemaDescriptor.ENTITY_TYPE_NAME_VARIANTS,
			new ReferenceSchemaEntityTypeNameVariantsDataFetcher()
		);
		context.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			ReferenceSchemaDescriptor.GROUP_TYPE_NAME_VARIANTS,
			new ReferenceSchemaGroupTypeNameVariantsDataFetcher()
		);
		context.registerDataFetcher(
			ReferenceSchemaDescriptor.THIS_GENERIC,
			ReferenceSchemaDescriptor.ALL_ATTRIBUTES,
			new AllAttributeSchemasDataFetcher()
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
			new ReferenceSchemasDataFetcher()
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
				context.registerFieldToObject(
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
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
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
			.name(objectName);

		context.registerDataFetcher(
			objectName,
			ReferenceSchemaDescriptor.ENTITY_TYPE_NAME_VARIANTS,
			new ReferenceSchemaEntityTypeNameVariantsDataFetcher()
		);
		context.registerDataFetcher(
			objectName,
			ReferenceSchemaDescriptor.GROUP_TYPE_NAME_VARIANTS,
			new ReferenceSchemaGroupTypeNameVariantsDataFetcher()
		);

		if (!referenceSchema.getAttributes().isEmpty()) {
			context.registerFieldToObject(
				objectName,
				referenceSchemaObjectBuilder,
				buildReferenceAttributeSchemasField(entitySchema, referenceSchema)
			);
		}
		context.registerDataFetcher(
			objectName,
			ReferenceSchemaDescriptor.ALL_ATTRIBUTES,
			new AllAttributeSchemasDataFetcher()
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
			new AttributeSchemasDataFetcher()
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
				context.registerFieldToObject(
					objectName,
					attributeSchemasObjectBuilder,
					buildAttributeSchemaField(attributeSchema)
				)
			);

		return attributeSchemasObjectBuilder.build();
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
			new UpdateEntitySchemaMutatingDataFetcher(entitySchema)
		);
	}
}
