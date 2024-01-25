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

package io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntityAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaWithDeprecationDescriptor;
import io.evitadb.externalApi.rest.api.resolver.serializer.DataTypeSerializer;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.io.RestHandlingContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;

/**
 * Handles serializing of {@link CatalogSchemaContract} into JSON structure
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class CatalogSchemaJsonSerializer extends SchemaJsonSerializer {

	@Nonnull
	private final EntitySchemaJsonSerializer entitySchemaJsonSerializer;

	public CatalogSchemaJsonSerializer(@Nonnull RestHandlingContext restHandlingContext) {
		super(new ObjectJsonSerializer(restHandlingContext.getObjectMapper()));
		this.entitySchemaJsonSerializer = new EntitySchemaJsonSerializer(restHandlingContext);
	}

	/**
	 * Performs serialization and returns serialized entity in form of JsonNode
	 *
	 * @return serialized entity or list of entities
	 */
	public JsonNode serialize(@Nonnull CatalogSchemaContract catalogSchema,
							  @Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher,
	                          @Nonnull Set<String> entityTypes) {
		final ObjectNode rootNode = objectJsonSerializer.objectNode();

		rootNode.put(VersionedDescriptor.VERSION.name(), catalogSchema.getVersion());
		rootNode.put(NamedSchemaDescriptor.NAME.name(), catalogSchema.getName());
		rootNode.set(NamedSchemaDescriptor.NAME_VARIANTS.name(), serializeNameVariants(catalogSchema.getNameVariants()));
		rootNode.put(NamedSchemaDescriptor.DESCRIPTION.name(), catalogSchema.getDescription());

		rootNode.set(CatalogSchemaDescriptor.ATTRIBUTES.name(), serializeAttributeSchemas(catalogSchema));
		rootNode.set(CatalogSchemaDescriptor.ENTITY_SCHEMAS.name(), serializeEntitySchemas(entitySchemaFetcher, entityTypes));

		return rootNode;
	}

	@Nonnull
	protected ObjectNode serializeAttributeSchemas(@Nonnull AttributeSchemaProvider<GlobalAttributeSchemaContract> globalAttributeSchemaProvider) {
		final Collection<GlobalAttributeSchemaContract> globalAttributeSchemas = globalAttributeSchemaProvider.getAttributes().values();

		final ObjectNode attributeSchemasMap = objectJsonSerializer.objectNode();
		if (!globalAttributeSchemas.isEmpty()) {
			globalAttributeSchemas.forEach(globalAttributeSchema -> attributeSchemasMap.set(
				globalAttributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
				serializeAttributeSchema(globalAttributeSchema)
			));
		}

		return attributeSchemasMap;
	}

	@Nonnull
	private ObjectNode serializeAttributeSchema(@Nonnull GlobalAttributeSchemaContract globalAttributeSchema) {
		final ObjectNode attributeSchemaNode = objectJsonSerializer.objectNode();
		attributeSchemaNode.put(NamedSchemaDescriptor.NAME.name(), globalAttributeSchema.getName());
		attributeSchemaNode.set(NamedSchemaDescriptor.NAME_VARIANTS.name(), serializeNameVariants(globalAttributeSchema.getNameVariants()));
		attributeSchemaNode.put(NamedSchemaDescriptor.DESCRIPTION.name(), globalAttributeSchema.getDescription());
		attributeSchemaNode.put(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), globalAttributeSchema.getDeprecationNotice());
		attributeSchemaNode.put(AttributeSchemaDescriptor.UNIQUENESS_TYPE.name(), globalAttributeSchema.getUniquenessType().toString());
		attributeSchemaNode.put(GlobalAttributeSchemaDescriptor.GLOBAL_UNIQUENESS_TYPE.name(), globalAttributeSchema.getGlobalUniquenessType().toString());
		attributeSchemaNode.put(AttributeSchemaDescriptor.FILTERABLE.name(), globalAttributeSchema.isFilterable());
		attributeSchemaNode.put(AttributeSchemaDescriptor.SORTABLE.name(), globalAttributeSchema.isSortable());
		attributeSchemaNode.put(AttributeSchemaDescriptor.LOCALIZED.name(), globalAttributeSchema.isLocalized());
		attributeSchemaNode.put(AttributeSchemaDescriptor.NULLABLE.name(), globalAttributeSchema.isNullable());
		attributeSchemaNode.put(EntityAttributeSchemaDescriptor.REPRESENTATIVE.name(), globalAttributeSchema.isRepresentative());
		attributeSchemaNode.put(AttributeSchemaDescriptor.TYPE.name(), DataTypeSerializer.serialize(globalAttributeSchema.getType()));
		attributeSchemaNode.set(
			AttributeSchemaDescriptor.DEFAULT_VALUE.name(),
			Optional.ofNullable(globalAttributeSchema.getDefaultValue())
				.map(objectJsonSerializer::serializeObject)
				.orElse(null)
		);
		attributeSchemaNode.put(AttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), globalAttributeSchema.getIndexedDecimalPlaces());

		return attributeSchemaNode;
	}

	@Nonnull
	private ObjectNode serializeEntitySchemas(@Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher,
	                                          @Nonnull Set<String> entityTypes) {
		final ObjectNode entitySchemasMap = objectJsonSerializer.objectNode();
		if (!entityTypes.isEmpty()) {
			entityTypes.stream()
				.map(entitySchemaFetcher)
				.forEach(entitySchema -> entitySchemasMap.set(
					entitySchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
					entitySchemaJsonSerializer.serialize(
						entitySchema,
						entitySchemaFetcher
					)
				));
		}

		return entitySchemasMap;
	}
}
