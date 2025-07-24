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

package io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.requestResponse.schema.*;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.externalApi.dataType.DataTypeSerializer;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.io.RestHandlingContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;

/**
 * Handles serializing of {@link io.evitadb.api.requestResponse.schema.EntitySchemaContract} into JSON structure
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class EntitySchemaJsonSerializer extends SchemaJsonSerializer {

	public EntitySchemaJsonSerializer(@Nonnull RestHandlingContext restHandlingContext) {
		super(new ObjectJsonSerializer(restHandlingContext.getObjectMapper()));
	}

	/**
	 * Performs serialization and returns serialized entity in form of JsonNode
	 *
	 * @return serialized entity or list of entities
	 */
	public JsonNode serialize(@Nonnull EntitySchemaContract entitySchema,
	                          @Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher) {
		final ObjectNode rootNode = this.objectJsonSerializer.objectNode();

		rootNode.put(VersionedDescriptor.VERSION.name(), entitySchema.version());
		rootNode.put(NamedSchemaDescriptor.NAME.name(), entitySchema.getName());
		rootNode.set(NamedSchemaDescriptor.NAME_VARIANTS.name(), serializeNameVariants(entitySchema.getNameVariants()));
		rootNode.put(NamedSchemaDescriptor.DESCRIPTION.name(), entitySchema.getDescription());
		rootNode.put(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), entitySchema.getDeprecationNotice());
		rootNode.put(EntitySchemaDescriptor.WITH_GENERATED_PRIMARY_KEY.name(), entitySchema.isWithGeneratedPrimaryKey());
		rootNode.put(EntitySchemaDescriptor.WITH_HIERARCHY.name(), entitySchema.isWithHierarchy());
		rootNode.set(EntitySchemaDescriptor.HIERARCHY_INDEXED.name(), serializeFlagInScopes(entitySchema::isHierarchyIndexedInScope));
		rootNode.put(EntitySchemaDescriptor.WITH_PRICE.name(), entitySchema.isWithPrice());
		rootNode.set(EntitySchemaDescriptor.PRICE_INDEXED.name(), serializeFlagInScopes(entitySchema::isPriceIndexedInScope));
		rootNode.put(EntitySchemaDescriptor.INDEXED_PRICE_PLACES.name(), entitySchema.getIndexedPricePlaces());
		rootNode.set(EntitySchemaDescriptor.LOCALES.name(), this.objectJsonSerializer.serializeCollection(entitySchema.getLocales().stream().map(Locale::toLanguageTag).toList()));
		rootNode.set(EntitySchemaDescriptor.CURRENCIES.name(), this.objectJsonSerializer.serializeCollection(entitySchema.getCurrencies().stream().map(Currency::toString).toList()));
		rootNode.set(EntitySchemaDescriptor.EVOLUTION_MODE.name(), this.objectJsonSerializer.serializeCollection(entitySchema.getEvolutionMode().stream().map(EvolutionMode::name).toList()));

		rootNode.set(EntitySchemaDescriptor.ATTRIBUTES.name(), serializeAttributeSchemas(entitySchema));
		rootNode.set(SortableAttributeCompoundsSchemaProviderDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name(), serializeSortableAttributeCompoundSchemas(entitySchema));
		rootNode.set(EntitySchemaDescriptor.ASSOCIATED_DATA.name(), serializeAssociatedDataSchemas(entitySchema));
		rootNode.set(EntitySchemaDescriptor.REFERENCES.name(), serializeReferenceSchemas(entitySchemaFetcher, entitySchema));

		return rootNode;
	}

	@Nonnull
	private ObjectNode serializeAttributeSchemas(@Nonnull AttributeSchemaProvider<? extends AttributeSchemaContract> attributeSchemaProvider) {
		final Collection<? extends AttributeSchemaContract> attributeSchemas = attributeSchemaProvider.getAttributes().values();

		final ObjectNode attributeSchemasMap = this.objectJsonSerializer.objectNode();
		if (!attributeSchemas.isEmpty()) {
			attributeSchemas.forEach(attributeSchema -> attributeSchemasMap.set(
				attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
				serializeAttributeSchema(attributeSchema)
			));
		}

		return attributeSchemasMap;
	}

	@Nonnull
	private ObjectNode serializeAttributeSchema(@Nonnull AttributeSchemaContract attributeSchema) {
		final ObjectNode attributeSchemaNode = this.objectJsonSerializer.objectNode();
		attributeSchemaNode.put(NamedSchemaDescriptor.NAME.name(), attributeSchema.getName());
		attributeSchemaNode.set(NamedSchemaDescriptor.NAME_VARIANTS.name(), serializeNameVariants(attributeSchema.getNameVariants()));
		attributeSchemaNode.put(NamedSchemaDescriptor.DESCRIPTION.name(), attributeSchema.getDescription());
		attributeSchemaNode.put(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), attributeSchema.getDeprecationNotice());
		attributeSchemaNode.putIfAbsent(AttributeSchemaDescriptor.UNIQUENESS_TYPE.name(), serializeUniquenessType(attributeSchema::getUniquenessType));
		if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema) {
			attributeSchemaNode.putIfAbsent(GlobalAttributeSchemaDescriptor.GLOBAL_UNIQUENESS_TYPE.name(), serializeGlobalUniquenessType(globalAttributeSchema::getGlobalUniquenessType));
		}
		attributeSchemaNode.putIfAbsent(AttributeSchemaDescriptor.FILTERABLE.name(), serializeFlagInScopes(attributeSchema::isFilterableInScope));
		attributeSchemaNode.putIfAbsent(AttributeSchemaDescriptor.SORTABLE.name(), serializeFlagInScopes(attributeSchema::isSortableInScope));
		attributeSchemaNode.put(AttributeSchemaDescriptor.LOCALIZED.name(), attributeSchema.isLocalized());
		attributeSchemaNode.put(AttributeSchemaDescriptor.NULLABLE.name(), attributeSchema.isNullable());
		if (attributeSchema instanceof EntityAttributeSchemaContract entityAttributeSchema) {
			attributeSchemaNode.put(EntityAttributeSchemaDescriptor.REPRESENTATIVE.name(), entityAttributeSchema.isRepresentative());
		}
		attributeSchemaNode.put(AttributeSchemaDescriptor.TYPE.name(), DataTypeSerializer.serialize(attributeSchema.getType()));
		attributeSchemaNode.set(
			AttributeSchemaDescriptor.DEFAULT_VALUE.name(),
			Optional.ofNullable(attributeSchema.getDefaultValue())
				.map(this.objectJsonSerializer::serializeObject)
				.orElse(null)
		);
		attributeSchemaNode.put(AttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), attributeSchema.getIndexedDecimalPlaces());

		return attributeSchemaNode;
	}

	@Nonnull
	private ObjectNode serializeSortableAttributeCompoundSchemas(@Nonnull SortableAttributeCompoundSchemaProvider provider) {
		final Collection<SortableAttributeCompoundSchemaContract> schemas = provider.getSortableAttributeCompounds().values();

		final ObjectNode attributeSchemasMap = this.objectJsonSerializer.objectNode();
		if (!schemas.isEmpty()) {
			schemas.forEach(attributeSchema -> attributeSchemasMap.set(
				attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
				serializeSortableAttributeCompoundSchema(attributeSchema)
			));
		}

		return attributeSchemasMap;
	}

	@Nonnull
	private ObjectNode serializeSortableAttributeCompoundSchema(@Nonnull SortableAttributeCompoundSchemaContract sortableAttributeCompoundSchema) {
		final ObjectNode schemaNode = this.objectJsonSerializer.objectNode();
		schemaNode.put(NamedSchemaDescriptor.NAME.name(), sortableAttributeCompoundSchema.getName());
		schemaNode.set(NamedSchemaDescriptor.NAME_VARIANTS.name(), serializeNameVariants(sortableAttributeCompoundSchema.getNameVariants()));
		schemaNode.put(NamedSchemaDescriptor.DESCRIPTION.name(), sortableAttributeCompoundSchema.getDescription());
		schemaNode.put(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), sortableAttributeCompoundSchema.getDeprecationNotice());

		final ArrayNode sortableAttributeCompoundArray = this.objectJsonSerializer.arrayNode();
		sortableAttributeCompoundSchema.getAttributeElements()
			.forEach(it -> sortableAttributeCompoundArray.add(serializeAttributeElement(it)));
		schemaNode.putIfAbsent(SortableAttributeCompoundSchemaDescriptor.ATTRIBUTE_ELEMENTS.name(), sortableAttributeCompoundArray);

		schemaNode.set(SortableAttributeCompoundSchemaDescriptor.INDEXED.name(), serializeFlagInScopes(sortableAttributeCompoundSchema::isIndexedInScope));

		return schemaNode;
	}

	@Nonnull
	private ObjectNode serializeAttributeElement(@Nonnull AttributeElement attributeElement) {
		final ObjectNode attributeElementNode = this.objectJsonSerializer.objectNode();
		attributeElementNode.put(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), attributeElement.attributeName());
		attributeElementNode.put(AttributeElementDescriptor.DIRECTION.name(), attributeElement.direction().name());
		attributeElementNode.put(AttributeElementDescriptor.BEHAVIOUR.name(), attributeElement.behaviour().name());
		return attributeElementNode;
	}

	@Nonnull
	private ObjectNode serializeAssociatedDataSchemas(@Nonnull EntitySchemaContract entitySchema) {
		final Collection<AssociatedDataSchemaContract> associatedDataSchemas = entitySchema.getAssociatedData().values();

		final ObjectNode associatedDataSchemasMap = this.objectJsonSerializer.objectNode();
		if (!associatedDataSchemas.isEmpty()) {
			associatedDataSchemas.forEach(associatedDataSchema -> associatedDataSchemasMap.set(
				associatedDataSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
				serializeAssociatedDataSchema(associatedDataSchema))
			);
		}

		return associatedDataSchemasMap;
	}

	@Nonnull
	private ObjectNode serializeAssociatedDataSchema(@Nonnull AssociatedDataSchemaContract associatedDataSchema) {
		final ObjectNode associatedDataSchemaNode = this.objectJsonSerializer.objectNode();
		associatedDataSchemaNode.put(NamedSchemaDescriptor.NAME.name(), associatedDataSchema.getName());
		associatedDataSchemaNode.set(NamedSchemaDescriptor.NAME_VARIANTS.name(), serializeNameVariants(associatedDataSchema.getNameVariants()));
		associatedDataSchemaNode.put(NamedSchemaDescriptor.DESCRIPTION.name(), associatedDataSchema.getDescription());
		associatedDataSchemaNode.put(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), associatedDataSchema.getDeprecationNotice());
		associatedDataSchemaNode.put(AssociatedDataSchemaDescriptor.TYPE.name(), DataTypeSerializer.serialize(associatedDataSchema.getType()));
		associatedDataSchemaNode.put(AssociatedDataSchemaDescriptor.LOCALIZED.name(), associatedDataSchema.isLocalized());
		associatedDataSchemaNode.put(AssociatedDataSchemaDescriptor.NULLABLE.name(), associatedDataSchema.isNullable());

		return associatedDataSchemaNode;
	}

	@Nonnull
	private ObjectNode serializeReferenceSchemas(@Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher,
	                                             @Nonnull EntitySchemaContract entitySchema) {
		final Collection<ReferenceSchemaContract> referenceSchemas = entitySchema.getReferences().values();

		final ObjectNode referenceSchemasMap = this.objectJsonSerializer.objectNode();
		if (!referenceSchemas.isEmpty()) {
			referenceSchemas.forEach(referenceSchema -> referenceSchemasMap.set(
				referenceSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
				serializeReferenceSchema(entitySchemaFetcher, referenceSchema)
			));
		}

		return referenceSchemasMap;
	}

	@Nonnull
	private ObjectNode serializeReferenceSchema(
		@Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher,
        @Nonnull ReferenceSchemaContract referenceSchema
	) {
		final ObjectNode referenceSchemaNode = this.objectJsonSerializer.objectNode();
		referenceSchemaNode.put(NamedSchemaDescriptor.NAME.name(), referenceSchema.getName());
		referenceSchemaNode.set(NamedSchemaDescriptor.NAME_VARIANTS.name(), serializeNameVariants(referenceSchema.getNameVariants()));
		referenceSchemaNode.put(NamedSchemaDescriptor.DESCRIPTION.name(), referenceSchema.getDescription());
		referenceSchemaNode.put(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), referenceSchema.getDeprecationNotice());
		referenceSchemaNode.put(ReferenceSchemaDescriptor.CARDINALITY.name(), referenceSchema.getCardinality().name());
		referenceSchemaNode.put(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE.name(), referenceSchema.getReferencedEntityType());
		referenceSchemaNode.set(ReferenceSchemaDescriptor.ENTITY_TYPE_NAME_VARIANTS.name(), serializeNameVariants(referenceSchema.getEntityTypeNameVariants(entitySchemaFetcher)));
		referenceSchemaNode.put(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), referenceSchema.isReferencedEntityTypeManaged());
		referenceSchemaNode.put(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE.name(), referenceSchema.getReferencedGroupType());
		referenceSchemaNode.set(ReferenceSchemaDescriptor.GROUP_TYPE_NAME_VARIANTS.name(), serializeNameVariants(referenceSchema.getGroupTypeNameVariants(entitySchemaFetcher)));
		referenceSchemaNode.put(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), referenceSchema.isReferencedGroupTypeManaged());
		referenceSchemaNode.set(ReferenceSchemaDescriptor.INDEXED.name(), serializeReferenceIndexTypes(referenceSchema));
		referenceSchemaNode.set(ReferenceSchemaDescriptor.FACETED.name(), serializeFlagInScopes(referenceSchema::isFacetedInScope));

		referenceSchemaNode.set(ReferenceSchemaDescriptor.ATTRIBUTES.name(), serializeAttributeSchemas(referenceSchema));
		referenceSchemaNode.set(SortableAttributeCompoundsSchemaProviderDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name(), serializeSortableAttributeCompoundSchemas(referenceSchema));

		return referenceSchemaNode;
	}

}
