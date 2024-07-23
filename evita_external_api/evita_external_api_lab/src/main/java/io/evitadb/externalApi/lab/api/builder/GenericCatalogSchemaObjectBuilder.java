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

package io.evitadb.externalApi.lab.api.builder;

import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemasDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemasDescriptor;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiDictionaryTransformer;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiDictionary;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiProperty;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Builds OpenAPI entity schema object (schema) based on information provided in building context
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class GenericCatalogSchemaObjectBuilder {

	@Nonnull private final LabApiBuildingContext buildingContext;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiDictionaryTransformer dictionaryBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;

	public void buildCommonTypes() {
		buildingContext.registerType(buildCatalogSchemaObject());
	}

	/**
	 * Builds catalog schema object.
	 *
	 * @return schema for entity schema object
	 */
	@Nonnull
	private OpenApiObject buildCatalogSchemaObject() {
		final OpenApiObject.Builder catalogSchemaObjectBuilder = CatalogSchemaDescriptor.THIS
			.to(objectBuilderTransformer);

		catalogSchemaObjectBuilder.property(buildGlobalAttributeSchemasProperty());
		catalogSchemaObjectBuilder.property(buildEntitySchemasProperty());

		return catalogSchemaObjectBuilder.build();
	}

	@Nonnull
	private OpenApiProperty buildGlobalAttributeSchemasProperty() {
		return EntitySchemaDescriptor.ATTRIBUTES
			.to(propertyBuilderTransformer)
			.type(nonNull(buildGlobalAttributeSchemasObject()))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildGlobalAttributeSchemasObject() {
		final OpenApiDictionary globalAttributeSchemasObjectBuilder = GlobalAttributeSchemasDescriptor.THIS
			.to(dictionaryBuilderTransformer)
			.name(GlobalAttributeSchemasDescriptor.THIS.name())
			.valueType(nonNull(typeRefTo(GlobalAttributeSchemaDescriptor.THIS.name())))
			.build();

		return buildingContext.registerType(globalAttributeSchemasObjectBuilder);
	}

	@Nonnull
	private OpenApiProperty buildEntitySchemasProperty() {
		return CatalogSchemaDescriptor.ENTITY_SCHEMAS
			.to(propertyBuilderTransformer)
			.type(nonNull(buildEntitySchemasObject()))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildEntitySchemasObject() {
		final OpenApiDictionary entitySchemasBuilder = EntitySchemasDescriptor.THIS
			.to(dictionaryBuilderTransformer)
			.valueType(nonNull(typeRefTo(EntitySchemaDescriptor.THIS_GENERIC.name())))
			.build();

		return buildingContext.registerType(entitySchemasBuilder);
	}
}
