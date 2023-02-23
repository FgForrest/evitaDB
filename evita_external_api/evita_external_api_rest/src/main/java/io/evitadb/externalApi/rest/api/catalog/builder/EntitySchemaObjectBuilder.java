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
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AssociatedDataSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AssociatedDataSchemasDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemasDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ReferenceSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ReferenceSchemasDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiSchemaTransformer.Property;
import io.swagger.v3.oas.models.media.Schema;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createReferenceSchema;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaPropertyUtils.addProperty;
import static io.evitadb.externalApi.rest.api.catalog.builder.transformer.Transformers.OBJECT_TRANSFORMER;

/**
 * Builds OpenAPI entity schema object (schema) based on information provided in building context
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class EntitySchemaObjectBuilder {
	private final OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx;

	public EntitySchemaObjectBuilder(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx) {
		this.entitySchemaBuildingCtx = entitySchemaBuildingCtx;
	}

	/**
	 * Builds entity schema object.
	 *
	 * @return schema for entity schema object
	 */
	@Nonnull
	public Schema<Object> buildEntitySchemaObject() {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		// build specific entity schema object
		final var objectSchema = EntitySchemaDescriptor.THIS_SPECIFIC
			.to(OBJECT_TRANSFORMER);
		objectSchema.name(EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema));

		addProperty(objectSchema, buildAttributeSchemasProperty());
		addProperty(objectSchema, buildAssociatedDataSchemasProperty());
		addProperty(objectSchema, buildReferenceSchemasProperty());

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(objectSchema);
	}

	@Nonnull
	private Property buildAttributeSchemasProperty() {
		final Schema<Object> attributesSchemasObject = buildAttributeSchemasObject();
		attributesSchemasObject.name(EntitySchemaDescriptor.ATTRIBUTES.name());
		return new Property(
			attributesSchemasObject,
			true
		);
	}

	@Nonnull
	private Schema<Object> buildAttributeSchemasObject() {
		final Schema<Object> attributeSchemasObject = AttributeSchemasDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		attributeSchemasObject.name(AttributeSchemasDescriptor.THIS.name(entitySchemaBuildingCtx.getSchema()));

		entitySchemaBuildingCtx.getSchema().getAttributes().values().forEach(attributeSchema -> {
			final Schema<Object> attributeProperty;
			if (attributeSchema instanceof GlobalAttributeSchemaContract) {
				attributeProperty = createReferenceSchema(GlobalAttributeSchemaDescriptor.THIS);
			} else {
				attributeProperty = createReferenceSchema(AttributeSchemaDescriptor.THIS);
			}
			attributeProperty.name(attributeSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION));
			addProperty(attributeSchemasObject, new Property(attributeProperty, true));
		});

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(attributeSchemasObject);
	}

	@Nonnull
	private Property buildAssociatedDataSchemasProperty() {
		final Schema<Object> associatedDataSchemasObject = buildAssociatedDataSchemasObject();
		associatedDataSchemasObject.name(EntitySchemaDescriptor.ASSOCIATED_DATA.name());
		return new Property(
			associatedDataSchemasObject,
			true
		);
	}

	@Nonnull
	private Schema<Object> buildAssociatedDataSchemasObject() {
		final Schema<Object> associatedDataSchemasSchema = AssociatedDataSchemasDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		associatedDataSchemasSchema.name(AssociatedDataSchemasDescriptor.THIS.name(entitySchemaBuildingCtx.getSchema()));

		entitySchemaBuildingCtx.getSchema().getAssociatedData().values().forEach(associatedDataSchema -> {
			final Schema<Object> associatedDataProperty = createReferenceSchema(AssociatedDataSchemaDescriptor.THIS);
			associatedDataProperty.name(associatedDataSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION));
			addProperty(associatedDataSchemasSchema, new Property(associatedDataProperty, true));
		});

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(associatedDataSchemasSchema);
	}

	@Nonnull
	private Property buildReferenceSchemasProperty() {
		final Schema<Object> referenceSchemasObject = buildReferenceSchemasObject();
		referenceSchemasObject.name(EntitySchemaDescriptor.REFERENCES.name());
		return new Property(
			referenceSchemasObject,
			true
		);
	}

	@Nonnull
	private Schema<Object> buildReferenceSchemasObject() {
		final Schema<Object> referenceSchemasSchema = ReferenceSchemasDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		referenceSchemasSchema.name(ReferenceSchemasDescriptor.THIS.name(entitySchemaBuildingCtx.getSchema()));

		entitySchemaBuildingCtx.getSchema().getReferences().values().forEach(referenceSchema -> {
			final Schema<Object> property = buildReferenceSchemaObject(referenceSchema);
			property.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION));
			addProperty(referenceSchemasSchema, new Property(property, true));
		});

		return referenceSchemasSchema;
	}

	@Nonnull
	private Schema<Object> buildReferenceSchemaObject(@Nonnull ReferenceSchemaContract referenceSchema) {
		final Schema<Object> objectSchema = ReferenceSchemaDescriptor.THIS_SPECIFIC.to(OBJECT_TRANSFORMER);
		objectSchema.name(ReferenceSchemaDescriptor.THIS_SPECIFIC.name(referenceSchema));
		addProperty(objectSchema, buildReferencedAttributeSchemasProperty(referenceSchema));

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(objectSchema);
	}

	@Nonnull
	private Property buildReferencedAttributeSchemasProperty(@Nonnull ReferenceSchemaContract referenceSchema) {
		final Schema<Object> associatedDataSchemasObject = buildReferencedAttributeSchemasObject(referenceSchema);
		associatedDataSchemasObject.name(ReferenceSchemaDescriptor.ATTRIBUTES.name());
		return new Property(
			associatedDataSchemasObject,
			true
		);
	}

	@Nonnull
	private Schema<Object> buildReferencedAttributeSchemasObject(@Nonnull ReferenceSchemaContract referenceSchema) {
		final Schema<Object> attributeSchemasSchema = AttributeSchemasDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		attributeSchemasSchema.name(AttributeSchemasDescriptor.THIS.name(entitySchemaBuildingCtx.getSchema(), referenceSchema));

		entitySchemaBuildingCtx.getSchema().getAttributes().values().forEach(attributeSchema -> {
			final Schema<Object> attributeSchemaProperty = createReferenceSchema(AttributeSchemaDescriptor.THIS);
			attributeSchemaProperty.name(attributeSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION));
			addProperty(attributeSchemasSchema, new Property(attributeSchemaProperty, true));
		});

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(attributeSchemasSchema);
	}
}
