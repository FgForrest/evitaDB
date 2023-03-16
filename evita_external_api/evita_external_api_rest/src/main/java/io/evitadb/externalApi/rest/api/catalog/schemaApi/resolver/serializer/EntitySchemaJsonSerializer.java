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
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AssociatedDataSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ReferenceSchemaDescriptor;
import io.evitadb.externalApi.rest.api.catalog.resolver.endpoint.CatalogRestHandlingContext;
import io.evitadb.externalApi.rest.api.resolver.serializer.DataTypeSerializer;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;

/**
 * Handles serializing of {@link io.evitadb.api.requestResponse.schema.EntitySchemaContract} into JSON structure
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class EntitySchemaJsonSerializer extends SchemaJsonSerializer {

	public EntitySchemaJsonSerializer(@Nonnull CatalogRestHandlingContext restHandlingContext) {
		super(new ObjectJsonSerializer(restHandlingContext.getObjectMapper()));
	}

	/**
	 * Performs serialization and returns serialized entity in form of JsonNode
	 *
	 * @return serialized entity or list of entities
	 */
	public JsonNode serialize(@Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher,
	                          @Nonnull EntitySchemaContract entitySchema) {
		final ObjectNode rootNode = objectJsonSerializer.objectNode();

		rootNode.put(EntitySchemaDescriptor.VERSION.name(), entitySchema.getVersion());
		rootNode.put(EntitySchemaDescriptor.NAME.name(), entitySchema.getName());
		rootNode.set(EntitySchemaDescriptor.NAME_VARIANTS.name(), serializeNameVariants(entitySchema.getNameVariants()));
		rootNode.put(EntitySchemaDescriptor.DESCRIPTION.name(), entitySchema.getDescription());
		rootNode.put(EntitySchemaDescriptor.DEPRECATION_NOTICE.name(), entitySchema.getDeprecationNotice());
		rootNode.put(EntitySchemaDescriptor.WITH_GENERATED_PRIMARY_KEY.name(), entitySchema.isWithGeneratedPrimaryKey());
		rootNode.put(EntitySchemaDescriptor.WITH_HIERARCHY.name(), entitySchema.isWithHierarchy());
		rootNode.put(EntitySchemaDescriptor.WITH_PRICE.name(), entitySchema.isWithPrice());
		rootNode.put(EntitySchemaDescriptor.INDEXED_PRICE_PLACES.name(), entitySchema.getIndexedPricePlaces());
		rootNode.set(EntitySchemaDescriptor.LOCALES.name(), objectJsonSerializer.serializeCollection(entitySchema.getLocales().stream().map(Locale::toLanguageTag).toList()));
		rootNode.set(EntitySchemaDescriptor.CURRENCIES.name(), objectJsonSerializer.serializeCollection(entitySchema.getCurrencies().stream().map(Currency::toString).toList()));
		rootNode.set(EntitySchemaDescriptor.EVOLUTION_MODE.name(), objectJsonSerializer.serializeCollection(entitySchema.getEvolutionMode().stream().map(EvolutionMode::name).toList()));

		rootNode.set(EntitySchemaDescriptor.ATTRIBUTES.name(), serializeAttributeSchemas(entitySchema));
		rootNode.set(EntitySchemaDescriptor.ASSOCIATED_DATA.name(), serializeAssociatedDataSchemas(entitySchema));
		rootNode.set(EntitySchemaDescriptor.REFERENCES.name(), serializeReferenceSchemas(entitySchemaFetcher, entitySchema));

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
	private ObjectNode serializeAssociatedDataSchemas(@Nonnull EntitySchemaContract entitySchema) {
		final Collection<AssociatedDataSchemaContract> associatedDataSchemas = entitySchema.getAssociatedData().values();

		final ObjectNode associatedDataSchemasMap = objectJsonSerializer.objectNode();
		if (!associatedDataSchemas.isEmpty()) {
			associatedDataSchemas.forEach(associatedDataSchema -> associatedDataSchemasMap.set(
				associatedDataSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION),
				serializeAssociatedDataSchema(associatedDataSchema))
			);
		}

		return associatedDataSchemasMap;
	}

	@Nonnull
	private ObjectNode serializeAssociatedDataSchema(@Nonnull AssociatedDataSchemaContract associatedDataSchema) {
		final ObjectNode associatedDataSchemaNode = objectJsonSerializer.objectNode();
		associatedDataSchemaNode.put(AssociatedDataSchemaDescriptor.NAME.name(), associatedDataSchema.getName());
		associatedDataSchemaNode.set(AssociatedDataSchemaDescriptor.NAME_VARIANTS.name(), serializeNameVariants(associatedDataSchema.getNameVariants()));
		associatedDataSchemaNode.put(AssociatedDataSchemaDescriptor.DESCRIPTION.name(), associatedDataSchema.getDescription());
		associatedDataSchemaNode.put(AssociatedDataSchemaDescriptor.DEPRECATION_NOTICE.name(), associatedDataSchema.getDeprecationNotice());
		associatedDataSchemaNode.put(AssociatedDataSchemaDescriptor.TYPE.name(), DataTypeSerializer.serialize(associatedDataSchema.getType()));
		associatedDataSchemaNode.put(AssociatedDataSchemaDescriptor.LOCALIZED.name(), associatedDataSchema.isLocalized());
		associatedDataSchemaNode.put(AssociatedDataSchemaDescriptor.NULLABLE.name(), associatedDataSchema.isNullable());

		return associatedDataSchemaNode;
	}

	@Nonnull
	private ObjectNode serializeReferenceSchemas(@Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher,
	                                             @Nonnull EntitySchemaContract entitySchema) {
		final Collection<ReferenceSchemaContract> referenceSchemas = entitySchema.getReferences().values();

		final ObjectNode referenceSchemasMap = objectJsonSerializer.objectNode();
		if (!referenceSchemas.isEmpty()) {
			referenceSchemas.forEach(referenceSchema -> referenceSchemasMap.set(
				referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION),
				serializeReferenceSchema(entitySchemaFetcher, referenceSchema)
			));
		}

		return referenceSchemasMap;
	}

	@Nonnull
	private ObjectNode serializeReferenceSchema(@Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher,
	                                            @Nonnull ReferenceSchemaContract referenceSchema) {
		final ObjectNode referenceSchemaNode = objectJsonSerializer.objectNode();
		referenceSchemaNode.put(ReferenceSchemaDescriptor.NAME.name(), referenceSchema.getName());
		referenceSchemaNode.set(ReferenceSchemaDescriptor.NAME_VARIANTS.name(), serializeNameVariants(referenceSchema.getNameVariants()));
		referenceSchemaNode.put(ReferenceSchemaDescriptor.DESCRIPTION.name(), referenceSchema.getDescription());
		referenceSchemaNode.put(ReferenceSchemaDescriptor.DEPRECATION_NOTICE.name(), referenceSchema.getDeprecationNotice());
		referenceSchemaNode.put(ReferenceSchemaDescriptor.CARDINALITY.name(), referenceSchema.getCardinality().name());
		referenceSchemaNode.put(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE.name(), referenceSchema.getReferencedEntityType());
		referenceSchemaNode.set(ReferenceSchemaDescriptor.ENTITY_TYPE_NAME_VARIANTS.name(), serializeNameVariants(referenceSchema.getEntityTypeNameVariants(entitySchemaFetcher)));
		referenceSchemaNode.put(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), referenceSchema.isReferencedEntityTypeManaged());
		referenceSchemaNode.put(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE.name(), referenceSchema.getReferencedGroupType());
		referenceSchemaNode.set(ReferenceSchemaDescriptor.GROUP_TYPE_NAME_VARIANTS.name(), serializeNameVariants(referenceSchema.getGroupTypeNameVariants(entitySchemaFetcher)));
		referenceSchemaNode.put(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), referenceSchema.isReferencedGroupTypeManaged());
		referenceSchemaNode.put(ReferenceSchemaDescriptor.FILTERABLE.name(), referenceSchema.isFilterable());
		referenceSchemaNode.put(ReferenceSchemaDescriptor.FACETED.name(), referenceSchema.isFaceted());

		referenceSchemaNode.set(ReferenceSchemaDescriptor.ATTRIBUTES.name(), serializeAttributeSchemas(referenceSchema));

		return referenceSchemaNode;
	}

}
