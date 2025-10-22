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

package io.evitadb.externalApi.rest.api.catalog.schemaApi.builder;

import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.*;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiProperty;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiProperty.newProperty;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Builds OpenAPI entity schema object (schema) based on information provided in building context
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class EntitySchemaObjectBuilder {

	@Nonnull private final CatalogRestBuildingContext buildingContext;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;

	public void buildCommonTypes() {
		// build common reusable objects
		this.buildingContext.registerType(ScopedAttributeUniquenessTypeDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedAttributeUniquenessTypeDescriptor.THIS_INPUT.to(
			this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedGlobalAttributeUniquenessTypeDescriptor.THIS.to(
			this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedGlobalAttributeUniquenessTypeDescriptor.THIS_INPUT.to(
			this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedReferenceIndexTypeDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(ScopedReferenceIndexTypeDescriptor.THIS_INPUT.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(AttributeSchemaDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(EntityAttributeSchemaDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(GlobalAttributeSchemaDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(AttributeElementDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(AttributeElementDescriptor.THIS_INPUT.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(SortableAttributeCompoundSchemaDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(AssociatedDataSchemaDescriptor.THIS.to(this.objectBuilderTransformer).build());
	}

	/**
	 * Builds entity schema object.
	 *
	 * @return schema for entity schema object
	 */
	@Nonnull
	public OpenApiTypeReference build(@Nonnull EntitySchemaContract entitySchema) {
		// build specific entity schema object
		final OpenApiObject.Builder entitySchemaObjectBuilder = EntitySchemaDescriptor.THIS_SPECIFIC
			.to(this.objectBuilderTransformer)
			.name(EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema));

		entitySchemaObjectBuilder.property(buildAttributeSchemasProperty(entitySchema));
		entitySchemaObjectBuilder.property(buildAssociatedDataSchemasProperty(entitySchema));
		entitySchemaObjectBuilder.property(buildSortableAttributeCompoundSchemasProperty(entitySchema));
		entitySchemaObjectBuilder.property(buildReferenceSchemasProperty(entitySchema));

		return this.buildingContext.registerType(entitySchemaObjectBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildAttributeSchemasProperty(@Nonnull EntitySchemaContract entitySchema) {
		return EntitySchemaDescriptor.ATTRIBUTES
			.to(this.propertyBuilderTransformer)
			.type(nonNull(buildAttributeSchemasObject(entitySchema)))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildAttributeSchemasObject(@Nonnull EntitySchemaContract entitySchema) {
		final OpenApiObject.Builder attributeSchemasObjectBuilder = AttributeSchemasDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(AttributeSchemasDescriptor.THIS.name(entitySchema));

		entitySchema.getAttributes().values().forEach(attributeSchema ->
			attributeSchemasObjectBuilder.property(buildAttributeSchemaProperty(attributeSchema)));

		return this.buildingContext.registerType(attributeSchemasObjectBuilder.build());
	}

	@Nonnull
	private static OpenApiProperty buildAttributeSchemaProperty(@Nonnull AttributeSchemaContract attributeSchema) {
		final OpenApiSimpleType attributeSchemaType;
		if (attributeSchema instanceof GlobalAttributeSchemaContract) {
			attributeSchemaType = nonNull(typeRefTo(GlobalAttributeSchemaDescriptor.THIS.name()));
		} else if (attributeSchema instanceof  EntityAttributeSchemaContract) {
			attributeSchemaType = nonNull(typeRefTo(EntityAttributeSchemaDescriptor.THIS.name()));
		} else {
			attributeSchemaType = nonNull(typeRefTo(AttributeSchemaDescriptor.THIS.name()));
		}

		return newProperty()
			.name(attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(attributeSchema.getDescription())
			.deprecationNotice(attributeSchema.getDeprecationNotice())
			.type(attributeSchemaType)
			.build();
	}

	@Nonnull
	private OpenApiProperty buildSortableAttributeCompoundSchemasProperty(@Nonnull EntitySchemaContract entitySchema) {
		return SortableAttributeCompoundsSchemaProviderDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS
			.to(this.propertyBuilderTransformer)
			.type(nonNull(buildSortableAttributeCompoundSchemasObject(entitySchema)))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildSortableAttributeCompoundSchemasObject(@Nonnull EntitySchemaContract entitySchema) {
		final OpenApiObject.Builder objectBuilder = SortableAttributeCompoundSchemasDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(SortableAttributeCompoundSchemasDescriptor.THIS.name(entitySchema));

		entitySchema.getSortableAttributeCompounds().values().forEach(sortableAttributeCompoundSchema ->
			objectBuilder.property(buildSortableAttributeCompoundSchemaProperty(sortableAttributeCompoundSchema)));

		return this.buildingContext.registerType(objectBuilder.build());
	}

	@Nonnull
	private static OpenApiProperty buildSortableAttributeCompoundSchemaProperty(@Nonnull SortableAttributeCompoundSchemaContract sortableAttributeCompoundSchema) {
		return newProperty()
			.name(sortableAttributeCompoundSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(sortableAttributeCompoundSchema.getDescription())
			.deprecationNotice(sortableAttributeCompoundSchema.getDeprecationNotice())
			.type(nonNull(typeRefTo(SortableAttributeCompoundSchemaDescriptor.THIS.name())))
			.build();
	}

	@Nonnull
	private OpenApiProperty buildAssociatedDataSchemasProperty(@Nonnull EntitySchemaContract entitySchema) {
		return EntitySchemaDescriptor.ASSOCIATED_DATA
			.to(this.propertyBuilderTransformer)
			.type(nonNull(buildAssociatedDataSchemasObject(entitySchema)))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildAssociatedDataSchemasObject(@Nonnull EntitySchemaContract entitySchema) {
		final OpenApiObject.Builder associatedDataSchemasObjectBuilder = AssociatedDataSchemasDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(AssociatedDataSchemasDescriptor.THIS.name(entitySchema));

		entitySchema.getAssociatedData().values().forEach(associatedDataSchema ->
			associatedDataSchemasObjectBuilder.property(buildAssociatedDataSchemaProperty(associatedDataSchema)));

		return this.buildingContext.registerType(associatedDataSchemasObjectBuilder.build());
	}

	@Nonnull
	private static OpenApiProperty buildAssociatedDataSchemaProperty(@Nonnull AssociatedDataSchemaContract associatedDataSchema) {
		return newProperty()
			.name(associatedDataSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(associatedDataSchema.getDescription())
			.deprecationNotice(associatedDataSchema.getDeprecationNotice())
			.type(nonNull(typeRefTo(AssociatedDataSchemaDescriptor.THIS.name())))
			.build();
	}

	@Nonnull
	private OpenApiProperty buildReferenceSchemasProperty(@Nonnull EntitySchemaContract entitySchema) {
		return EntitySchemaDescriptor.REFERENCES
			.to(this.propertyBuilderTransformer)
			.type(nonNull(buildReferenceSchemasObject(entitySchema)))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildReferenceSchemasObject(@Nonnull EntitySchemaContract entitySchema) {
		final OpenApiObject.Builder referenceSchemasObjectBuilder = ReferenceSchemasDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(ReferenceSchemasDescriptor.THIS.name(entitySchema));

		entitySchema.getReferences().values().forEach(referenceSchema ->
			referenceSchemasObjectBuilder.property(buildReferenceSchemaProperty(entitySchema, referenceSchema)));

		return this.buildingContext.registerType(referenceSchemasObjectBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildReferenceSchemaProperty(@Nonnull EntitySchemaContract entitySchema,
	                                                     @Nonnull ReferenceSchemaContract referenceSchema) {
		return newProperty()
			.name(referenceSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(referenceSchema.getDescription())
			.deprecationNotice(referenceSchema.getDeprecationNotice())
			.type(nonNull(buildReferenceSchemaObject(entitySchema, referenceSchema)))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildReferenceSchemaObject(@Nonnull EntitySchemaContract entitySchema,
	                                                        @Nonnull ReferenceSchemaContract referenceSchema) {
		final OpenApiObject.Builder referenceSchemaObjectBuilder = ReferenceSchemaDescriptor.THIS_SPECIFIC
			.to(this.objectBuilderTransformer)
			.name(ReferenceSchemaDescriptor.THIS_SPECIFIC.name(entitySchema, referenceSchema));

		if (!referenceSchema.getAttributes().isEmpty()) {
			referenceSchemaObjectBuilder.property(buildReferencedAttributeSchemasProperty(entitySchema, referenceSchema));
		}
		if (!referenceSchema.getSortableAttributeCompounds().isEmpty()) {
			referenceSchemaObjectBuilder.property(buildReferencedSortableAttributeCompoundSchemasProperty(entitySchema, referenceSchema));
		}

		return this.buildingContext.registerType(referenceSchemaObjectBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildReferencedAttributeSchemasProperty(@Nonnull EntitySchemaContract entitySchema,
	                                                                @Nonnull ReferenceSchemaContract referenceSchema) {
		return ReferenceSchemaDescriptor.ATTRIBUTES
			.to(this.propertyBuilderTransformer)
			.type(nonNull(buildReferencedAttributeSchemasObject(entitySchema, referenceSchema)))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildReferencedAttributeSchemasObject(@Nonnull EntitySchemaContract entitySchema,
	                                                                   @Nonnull ReferenceSchemaContract referenceSchema) {
		final OpenApiObject.Builder attributeSchemasObjectBuilder = AttributeSchemasDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(AttributeSchemasDescriptor.THIS.name(entitySchema, referenceSchema));

		entitySchema.getAttributes().values().forEach(attributeSchema ->
			attributeSchemasObjectBuilder.property(buildAttributeSchemaProperty(attributeSchema)));

		return this.buildingContext.registerType(attributeSchemasObjectBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildReferencedSortableAttributeCompoundSchemasProperty(@Nonnull EntitySchemaContract entitySchema,
	                                                                                @Nonnull ReferenceSchemaContract referenceSchema) {
		return SortableAttributeCompoundsSchemaProviderDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS
			.to(this.propertyBuilderTransformer)
			.type(nonNull(buildReferencedSortableAttributeCompoundSchemasObject(entitySchema, referenceSchema)))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildReferencedSortableAttributeCompoundSchemasObject(@Nonnull EntitySchemaContract entitySchema,
	                                                                                   @Nonnull ReferenceSchemaContract referenceSchema) {
		final OpenApiObject.Builder objectBuilder = SortableAttributeCompoundSchemasDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(SortableAttributeCompoundSchemasDescriptor.THIS.name(entitySchema, referenceSchema));

		entitySchema.getSortableAttributeCompounds().values().forEach(sortableAttributeCompoundSchema ->
			objectBuilder.property(buildSortableAttributeCompoundSchemaProperty(sortableAttributeCompoundSchema)));

		return this.buildingContext.registerType(objectBuilder.build());
	}
}
