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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemasDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemasDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiProperty;
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
public class CatalogSchemaObjectBuilder {

	@Nonnull private final CatalogRestBuildingContext buildingContext;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;

	/**
	 * Builds catalog schema object.
	 *
	 * @return schema for entity schema object
	 */
	@Nonnull
	public OpenApiTypeReference build() {
		// build specific catalog schema object
		final OpenApiObject.Builder catalogSchemaObjectBuilder = CatalogSchemaDescriptor.THIS
			.to(this.objectBuilderTransformer);

		catalogSchemaObjectBuilder.property(buildGlobalAttributeSchemasProperty());
		catalogSchemaObjectBuilder.property(buildEntitySchemasProperty());

		return this.buildingContext.registerType(catalogSchemaObjectBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildGlobalAttributeSchemasProperty() {
		return EntitySchemaDescriptor.ATTRIBUTES
			.to(this.propertyBuilderTransformer)
			.type(nonNull(buildGlobalAttributeSchemasObject()))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildGlobalAttributeSchemasObject() {
		final CatalogSchemaContract catalogSchema = this.buildingContext.getSchema();

		final OpenApiObject.Builder globalAttributeSchemasObjectBuilder = GlobalAttributeSchemasDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(GlobalAttributeSchemasDescriptor.THIS.name());

		catalogSchema.getAttributes().values().forEach(attributeSchema ->
			globalAttributeSchemasObjectBuilder.property(buildGlobalAttributeSchemaProperty(attributeSchema)));

		return this.buildingContext.registerType(globalAttributeSchemasObjectBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildGlobalAttributeSchemaProperty(@Nonnull AttributeSchemaContract attributeSchema) {
		return newProperty()
			.name(attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(attributeSchema.getDescription())
			.deprecationNotice(attributeSchema.getDeprecationNotice())
			.type(nonNull(typeRefTo(GlobalAttributeSchemaDescriptor.THIS.name())))
			.build();
	}

	@Nonnull
	private OpenApiProperty buildEntitySchemasProperty() {
		return CatalogSchemaDescriptor.ENTITY_SCHEMAS
			.to(this.propertyBuilderTransformer)
			.type(nonNull(buildEntitySchemasObject()))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildEntitySchemasObject() {
		final OpenApiObject.Builder entitySchemasBuilder = EntitySchemasDescriptor.THIS
			.to(this.objectBuilderTransformer);

		this.buildingContext.getEntitySchemas().forEach(entitySchema ->
			entitySchemasBuilder.property(buildEntitySchemaProperty(entitySchema)));

		return this.buildingContext.registerType(entitySchemasBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildEntitySchemaProperty(@Nonnull EntitySchemaContract entitySchema) {
		return newProperty()
			.name(entitySchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(entitySchema.getDescription())
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.type(nonNull(typeRefTo(EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema))))
			.build();
	}
}
