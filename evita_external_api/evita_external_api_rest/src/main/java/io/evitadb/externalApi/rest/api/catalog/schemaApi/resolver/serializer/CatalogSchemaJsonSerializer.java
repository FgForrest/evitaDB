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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.VersionedDescriptor;
import io.evitadb.externalApi.rest.api.catalog.resolver.endpoint.CatalogRestHandlingContext;
import io.evitadb.externalApi.rest.api.resolver.serializer.DataTypeSerializer;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;

/**
 * Handles serializing of {@link CatalogSchemaContract} into JSON structure
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class CatalogSchemaJsonSerializer extends SchemaJsonSerializer {

	@Nonnull
	private final EntitySchemaJsonSerializer entitySchemaJsonSerializer;

	public CatalogSchemaJsonSerializer(@Nonnull CatalogRestHandlingContext restHandlingContext) {
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
	private ObjectNode serializeAttributeSchemas(@Nonnull AttributeSchemaProvider<? extends AttributeSchemaContract> attributeSchemaProvider) {
		final Collection<? extends AttributeSchemaContract> attributeSchemas = attributeSchemaProvider.getAttributes().values();

		final ObjectNode attributeSchemasMap = objectJsonSerializer.objectNode();
		if (!attributeSchemas.isEmpty()) {
			attributeSchemas.forEach(attributeSchema -> attributeSchemasMap.set(
				attributeSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION),
				serializeAttributeSchema(attributeSchema)
			));
		}

		return attributeSchemasMap;
	}

	@Nonnull
	private ObjectNode serializeAttributeSchema(@Nonnull AttributeSchemaContract attributeSchema) {
		final ObjectNode attributeSchemaNode = objectJsonSerializer.objectNode();
		attributeSchemaNode.put(AttributeSchemaDescriptor.NAME.name(), attributeSchema.getName());
		attributeSchemaNode.set(AttributeSchemaDescriptor.NAME_VARIANTS.name(), serializeNameVariants(attributeSchema.getNameVariants()));
		attributeSchemaNode.put(AttributeSchemaDescriptor.DESCRIPTION.name(), attributeSchema.getDescription());
		attributeSchemaNode.put(AttributeSchemaDescriptor.DEPRECATION_NOTICE.name(), attributeSchema.getDeprecationNotice());
		attributeSchemaNode.put(AttributeSchemaDescriptor.UNIQUE.name(), attributeSchema.isUnique());
		if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema) {
			attributeSchemaNode.put(GlobalAttributeSchemaDescriptor.UNIQUE_GLOBALLY.name(), globalAttributeSchema.isUniqueGlobally());
		}
		attributeSchemaNode.put(AttributeSchemaDescriptor.FILTERABLE.name(), attributeSchema.isFilterable());
		attributeSchemaNode.put(AttributeSchemaDescriptor.SORTABLE.name(), attributeSchema.isSortable());
		attributeSchemaNode.put(AttributeSchemaDescriptor.LOCALIZED.name(), attributeSchema.isLocalized());
		attributeSchemaNode.put(AttributeSchemaDescriptor.NULLABLE.name(), attributeSchema.isNullable());
		attributeSchemaNode.put(AttributeSchemaDescriptor.TYPE.name(), DataTypeSerializer.serialize(attributeSchema.getType()));
		attributeSchemaNode.set(
			AttributeSchemaDescriptor.DEFAULT_VALUE.name(),
			Optional.ofNullable(attributeSchema.getDefaultValue())
				.map(objectJsonSerializer::serializeObject)
				.orElse(null)
		);
		attributeSchemaNode.put(AttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), attributeSchema.getIndexedDecimalPlaces());

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
					entitySchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION),
					entitySchemaJsonSerializer.serialize(
						entitySchemaFetcher,
						entitySchema
					)
				));
		}

		return entitySchemasMap;
	}
}
