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

package io.evitadb.externalApi.rest.api.catalog.schemaApi;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.externalApi.rest.api.resolver.serializer.DataTypeSerializer;
import io.evitadb.externalApi.rest.api.testSuite.RestEndpointFunctionalTest;
import io.evitadb.test.builder.MapBuilder;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;

/**
 * Ancestor for tests for REST catalog endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
public abstract class CatalogRestSchemaEndpointFunctionalTest extends RestEndpointFunctionalTest {

	@Nonnull
	public static CatalogSchemaContract getCatalogSchemaFromTestData(@Nonnull Evita evita) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getCatalogSchema();
			}
		);
	}

	@Nonnull
	protected static EntitySchemaContract getEntitySchemaFromTestData(@Nonnull Evita evita, @Nonnull String entityType) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchemaOrThrow(entityType);
			}
		);
	}

	@Nonnull
	public static Map<String, Object> createCatalogSchemaDto(@Nonnull Evita evita,
	                                                         @Nonnull CatalogSchemaContract catalogSchema) {
		final Set<String> entityTypes = evita.queryCatalog(
			catalogSchema.getName(),
			EvitaSessionContract::getAllEntityTypes
		);

		final MapBuilder catalogSchemaDto = map()
			.e(VersionedDescriptor.VERSION.name(), catalogSchema.version())
			.e(NamedSchemaDescriptor.NAME.name(), catalogSchema.getName())
			.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
				.e(NameVariantsDescriptor.CAMEL_CASE.name(), catalogSchema.getNameVariant(NamingConvention.CAMEL_CASE))
				.e(NameVariantsDescriptor.PASCAL_CASE.name(), catalogSchema.getNameVariant(NamingConvention.PASCAL_CASE))
				.e(NameVariantsDescriptor.SNAKE_CASE.name(), catalogSchema.getNameVariant(NamingConvention.SNAKE_CASE))
				.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), catalogSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
				.e(NameVariantsDescriptor.KEBAB_CASE.name(), catalogSchema.getNameVariant(NamingConvention.KEBAB_CASE))
				.build())
			.e(NamedSchemaDescriptor.DESCRIPTION.name(), catalogSchema.getDescription())
			.e(CatalogSchemaDescriptor.ATTRIBUTES.name(), createLinkedHashMap(catalogSchema.getAttributes().size()))
			.e(CatalogSchemaDescriptor.ENTITY_SCHEMAS.name(), createLinkedHashMap(entityTypes.size()));

		catalogSchema.getAttributes()
			.values()
			.forEach(attributeSchema -> {
				//noinspection unchecked
				final Map<String, Object> attributes = (Map<String, Object>) catalogSchemaDto.get(CatalogSchemaDescriptor.ATTRIBUTES.name());
				attributes.put(
					attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
					createGlobalAttributeSchemaDto(attributeSchema)
				);
			});

		entityTypes
			.stream()
			.map(catalogSchema::getEntitySchemaOrThrowException)
			.forEach(entitySchema -> {
				//noinspection unchecked
				final Map<String, Object> entitySchemas = (Map<String, Object>) catalogSchemaDto.get(CatalogSchemaDescriptor.ENTITY_SCHEMAS.name());
				entitySchemas.put(
					entitySchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
					createEntitySchemaDto(evita, entitySchema)
				);
			});

		return catalogSchemaDto.build();
	}

	@Nonnull
	protected static Map<String, Object> createEntitySchemaDto(@Nonnull Evita evita, @Nonnull EntitySchemaContract entitySchema) {
		final MapBuilder entitySchemaDto = map()
			.e(VersionedDescriptor.VERSION.name(), entitySchema.version())
			.e(NamedSchemaDescriptor.NAME.name(), entitySchema.getName())
			.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
				.e(NameVariantsDescriptor.CAMEL_CASE.name(), entitySchema.getNameVariant(NamingConvention.CAMEL_CASE))
				.e(NameVariantsDescriptor.PASCAL_CASE.name(), entitySchema.getNameVariant(NamingConvention.PASCAL_CASE))
				.e(NameVariantsDescriptor.SNAKE_CASE.name(), entitySchema.getNameVariant(NamingConvention.SNAKE_CASE))
				.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), entitySchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
				.e(NameVariantsDescriptor.KEBAB_CASE.name(), entitySchema.getNameVariant(NamingConvention.KEBAB_CASE))
				.build())
			.e(NamedSchemaDescriptor.DESCRIPTION.name(), entitySchema.getDescription())
			.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), entitySchema.getDeprecationNotice())
			.e(EntitySchemaDescriptor.WITH_GENERATED_PRIMARY_KEY.name(), entitySchema.isWithGeneratedPrimaryKey())
			.e(EntitySchemaDescriptor.WITH_HIERARCHY.name(), entitySchema.isWithHierarchy())
			.e(EntitySchemaDescriptor.WITH_PRICE.name(), entitySchema.isWithPrice())
			.e(EntitySchemaDescriptor.INDEXED_PRICE_PLACES.name(), entitySchema.getIndexedPricePlaces())
			.e(EntitySchemaDescriptor.LOCALES.name(), entitySchema.getLocales().stream().map(Locale::toLanguageTag).collect(Collectors.toList()))
			.e(EntitySchemaDescriptor.CURRENCIES.name(), entitySchema.getCurrencies().stream().map(Currency::toString).collect(Collectors.toList()))
			.e(EntitySchemaDescriptor.EVOLUTION_MODE.name(), entitySchema.getEvolutionMode().stream().map(Enum::toString).collect(Collectors.toList()))
			.e(EntitySchemaDescriptor.ATTRIBUTES.name(), createLinkedHashMap(entitySchema.getAttributes().size()))
			.e(EntitySchemaDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name(), createLinkedHashMap(entitySchema.getSortableAttributeCompounds().size()))
			.e(EntitySchemaDescriptor.ASSOCIATED_DATA.name(), createLinkedHashMap(entitySchema.getAssociatedData().size()))
			.e(EntitySchemaDescriptor.REFERENCES.name(), createLinkedHashMap(entitySchema.getReferences().size()));

		entitySchema.getAttributes()
			.values()
			.forEach(attributeSchema -> {
				//noinspection unchecked
				final Map<String, Object> attributes = (Map<String, Object>) entitySchemaDto.get(EntitySchemaDescriptor.ATTRIBUTES.name());
				attributes.put(
					attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
					attributeSchema instanceof GlobalAttributeSchemaContract ? createGlobalAttributeSchemaDto((GlobalAttributeSchemaContract) attributeSchema) : createAttributeSchemaDto(attributeSchema)
				);
			});
		entitySchema.getSortableAttributeCompounds()
			.values()
			.forEach(sortableAttributeCompoundSchema -> {
				//noinspection unchecked
				final Map<String, Object> sortableAttributeCompounds = (Map<String, Object>) entitySchemaDto.get(EntitySchemaDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name());
				sortableAttributeCompounds.put(
					sortableAttributeCompoundSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
					createSortableAttributeCompoundSchemaDto(sortableAttributeCompoundSchema)
				);
			});
		entitySchema.getAssociatedData()
			.values()
			.forEach(associatedDataSchema -> {
				//noinspection unchecked
				final Map<String, Object> associatedData = (Map<String, Object>) entitySchemaDto.get(EntitySchemaDescriptor.ASSOCIATED_DATA.name());
				associatedData.put(
					associatedDataSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
					createAssociatedDataSchemaDto(associatedDataSchema)
				);
			});
		entitySchema.getReferences()
			.values()
			.forEach(referenceSchema -> {
				//noinspection unchecked
				final Map<String, Object> references = (Map<String, Object>) entitySchemaDto.get(EntitySchemaDescriptor.REFERENCES.name());
				references.put(
					referenceSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
					createReferenceSchemaDto(evita, referenceSchema)
				);
			});

		return entitySchemaDto.build();
	}

	@Nonnull
	protected static Map<String, Object> createAttributeSchemaDto(@Nonnull AttributeSchemaContract attributeSchema) {
		return map()
			.e(NamedSchemaDescriptor.NAME.name(), attributeSchema.getName())
			.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
				.e(NameVariantsDescriptor.CAMEL_CASE.name(), attributeSchema.getNameVariant(NamingConvention.CAMEL_CASE))
				.e(NameVariantsDescriptor.PASCAL_CASE.name(), attributeSchema.getNameVariant(NamingConvention.PASCAL_CASE))
				.e(NameVariantsDescriptor.SNAKE_CASE.name(), attributeSchema.getNameVariant(NamingConvention.SNAKE_CASE))
				.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), attributeSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
				.e(NameVariantsDescriptor.KEBAB_CASE.name(), attributeSchema.getNameVariant(NamingConvention.KEBAB_CASE))
				.build())
			.e(NamedSchemaDescriptor.DESCRIPTION.name(), attributeSchema.getDescription())
			.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), attributeSchema.getDeprecationNotice())
			.e(AttributeSchemaDescriptor.UNIQUE.name(), attributeSchema.isUnique())
			.e(AttributeSchemaDescriptor.FILTERABLE.name(), attributeSchema.isFilterable())
			.e(AttributeSchemaDescriptor.SORTABLE.name(), attributeSchema.isSortable())
			.e(AttributeSchemaDescriptor.LOCALIZED.name(), attributeSchema.isLocalized())
			.e(AttributeSchemaDescriptor.NULLABLE.name(), attributeSchema.isNullable())
			.e(AttributeSchemaDescriptor.TYPE.name(), DataTypeSerializer.serialize(attributeSchema.getType()))
			.e(AttributeSchemaDescriptor.DEFAULT_VALUE.name(), serializeDefaultValue(attributeSchema.getDefaultValue()))
			.e(AttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), attributeSchema.getIndexedDecimalPlaces())
			.build();
	}

	@Nonnull
	protected static Map<String, Object> createGlobalAttributeSchemaDto(@Nonnull GlobalAttributeSchemaContract attributeSchema) {
		return map()
			.e(NamedSchemaDescriptor.NAME.name(), attributeSchema.getName())
			.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
				.e(NameVariantsDescriptor.CAMEL_CASE.name(), attributeSchema.getNameVariant(NamingConvention.CAMEL_CASE))
				.e(NameVariantsDescriptor.PASCAL_CASE.name(), attributeSchema.getNameVariant(NamingConvention.PASCAL_CASE))
				.e(NameVariantsDescriptor.SNAKE_CASE.name(), attributeSchema.getNameVariant(NamingConvention.SNAKE_CASE))
				.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), attributeSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
				.e(NameVariantsDescriptor.KEBAB_CASE.name(), attributeSchema.getNameVariant(NamingConvention.KEBAB_CASE))
				.build())
			.e(NamedSchemaDescriptor.DESCRIPTION.name(), attributeSchema.getDescription())
			.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), attributeSchema.getDeprecationNotice())
			.e(AttributeSchemaDescriptor.UNIQUE.name(), attributeSchema.isUnique())
			.e(GlobalAttributeSchemaDescriptor.UNIQUE_GLOBALLY.name(), attributeSchema.isUniqueGlobally())
			.e(AttributeSchemaDescriptor.FILTERABLE.name(), attributeSchema.isFilterable())
			.e(AttributeSchemaDescriptor.SORTABLE.name(), attributeSchema.isSortable())
			.e(AttributeSchemaDescriptor.LOCALIZED.name(), attributeSchema.isLocalized())
			.e(AttributeSchemaDescriptor.NULLABLE.name(), attributeSchema.isNullable())
			.e(AttributeSchemaDescriptor.TYPE.name(), DataTypeSerializer.serialize(attributeSchema.getType()))
			.e(AttributeSchemaDescriptor.DEFAULT_VALUE.name(), serializeDefaultValue(attributeSchema.getDefaultValue()))
			.e(AttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), attributeSchema.getIndexedDecimalPlaces())
			.build();
	}

	@Nonnull
	protected static Map<String, Object> createSortableAttributeCompoundSchemaDto(@Nonnull SortableAttributeCompoundSchemaContract sortableAttributeCompoundSchema) {
		return map()
			.e(NamedSchemaDescriptor.NAME.name(), sortableAttributeCompoundSchema.getName())
			.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
				.e(NameVariantsDescriptor.CAMEL_CASE.name(), sortableAttributeCompoundSchema.getNameVariant(NamingConvention.CAMEL_CASE))
				.e(NameVariantsDescriptor.PASCAL_CASE.name(), sortableAttributeCompoundSchema.getNameVariant(NamingConvention.PASCAL_CASE))
				.e(NameVariantsDescriptor.SNAKE_CASE.name(), sortableAttributeCompoundSchema.getNameVariant(NamingConvention.SNAKE_CASE))
				.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), sortableAttributeCompoundSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
				.e(NameVariantsDescriptor.KEBAB_CASE.name(), sortableAttributeCompoundSchema.getNameVariant(NamingConvention.KEBAB_CASE))
				.build())
			.e(NamedSchemaDescriptor.DESCRIPTION.name(), sortableAttributeCompoundSchema.getDescription())
			.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), sortableAttributeCompoundSchema.getDeprecationNotice())
			.e(SortableAttributeCompoundSchemaDescriptor.ATTRIBUTE_ELEMENTS.name(), sortableAttributeCompoundSchema.getAttributeElements()
				.stream()
				.map(it -> map()
					.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), it.attributeName())
					.e(AttributeElementDescriptor.DIRECTION.name(), it.direction().name())
					.e(AttributeElementDescriptor.BEHAVIOUR.name(), it.behaviour().name())
					.build())
				.toList())
			.build();
	}

	@Nonnull
	protected static Map<String, Object> createAssociatedDataSchemaDto(@Nonnull AssociatedDataSchemaContract associatedDataSchema) {
		return map()
			.e(NamedSchemaDescriptor.NAME.name(), associatedDataSchema.getName())
			.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
				.e(NameVariantsDescriptor.CAMEL_CASE.name(), associatedDataSchema.getNameVariant(NamingConvention.CAMEL_CASE))
				.e(NameVariantsDescriptor.PASCAL_CASE.name(), associatedDataSchema.getNameVariant(NamingConvention.PASCAL_CASE))
				.e(NameVariantsDescriptor.SNAKE_CASE.name(), associatedDataSchema.getNameVariant(NamingConvention.SNAKE_CASE))
				.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), associatedDataSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
				.e(NameVariantsDescriptor.KEBAB_CASE.name(), associatedDataSchema.getNameVariant(NamingConvention.KEBAB_CASE))
				.build())
			.e(NamedSchemaDescriptor.DESCRIPTION.name(), associatedDataSchema.getDescription())
			.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), associatedDataSchema.getDeprecationNotice())
			.e(AssociatedDataSchemaDescriptor.TYPE.name(), DataTypeSerializer.serialize(associatedDataSchema.getType()))
			.e(AssociatedDataSchemaDescriptor.LOCALIZED.name(), associatedDataSchema.isLocalized())
			.e(AssociatedDataSchemaDescriptor.NULLABLE.name(), associatedDataSchema.isNullable())
			.build();
	}

	@Nonnull
	protected static Map<String, Object> createReferenceSchemaDto(@Nonnull Evita evita, @Nonnull ReferenceSchemaContract referenceSchema) {
		final Function<String, EntitySchemaContract> ENTITY_SCHEMA_FETCHER = s -> evita.queryCatalog(TEST_CATALOG, session -> {
			return session.getEntitySchemaOrThrow(s);
		});

		final MapBuilder referenceSchemaBuilder = map()
			.e(NamedSchemaDescriptor.NAME.name(), referenceSchema.getName())
			.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
				.e(NameVariantsDescriptor.CAMEL_CASE.name(), referenceSchema.getNameVariant(NamingConvention.CAMEL_CASE))
				.e(NameVariantsDescriptor.PASCAL_CASE.name(), referenceSchema.getNameVariant(NamingConvention.PASCAL_CASE))
				.e(NameVariantsDescriptor.SNAKE_CASE.name(), referenceSchema.getNameVariant(NamingConvention.SNAKE_CASE))
				.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), referenceSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
				.e(NameVariantsDescriptor.KEBAB_CASE.name(), referenceSchema.getNameVariant(NamingConvention.KEBAB_CASE))
				.build())
			.e(NamedSchemaDescriptor.DESCRIPTION.name(), referenceSchema.getDescription())
			.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), referenceSchema.getDeprecationNotice())
			.e(ReferenceSchemaDescriptor.CARDINALITY.name(), referenceSchema.getCardinality().toString())
			.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE.name(), referenceSchema.getReferencedEntityType())
			.e(ReferenceSchemaDescriptor.ENTITY_TYPE_NAME_VARIANTS.name(), map()
				.e(NameVariantsDescriptor.CAMEL_CASE.name(), referenceSchema.getEntityTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.CAMEL_CASE))
				.e(NameVariantsDescriptor.PASCAL_CASE.name(), referenceSchema.getEntityTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.PASCAL_CASE))
				.e(NameVariantsDescriptor.SNAKE_CASE.name(), referenceSchema.getEntityTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.SNAKE_CASE))
				.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), referenceSchema.getEntityTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.UPPER_SNAKE_CASE))
				.e(NameVariantsDescriptor.KEBAB_CASE.name(), referenceSchema.getEntityTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.KEBAB_CASE))
				.build())
			.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), referenceSchema.isReferencedEntityTypeManaged())
			.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE.name(), referenceSchema.getReferencedGroupType())
			.e(ReferenceSchemaDescriptor.GROUP_TYPE_NAME_VARIANTS.name(), map()
				.e(NameVariantsDescriptor.CAMEL_CASE.name(), referenceSchema.getGroupTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.CAMEL_CASE))
				.e(NameVariantsDescriptor.PASCAL_CASE.name(), referenceSchema.getGroupTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.PASCAL_CASE))
				.e(NameVariantsDescriptor.SNAKE_CASE.name(), referenceSchema.getGroupTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.SNAKE_CASE))
				.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), referenceSchema.getGroupTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.UPPER_SNAKE_CASE))
				.e(NameVariantsDescriptor.KEBAB_CASE.name(), referenceSchema.getGroupTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.KEBAB_CASE))
				.build())
			.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), referenceSchema.isReferencedGroupTypeManaged())
			.e(ReferenceSchemaDescriptor.INDEXED.name(), referenceSchema.isIndexed())
			.e(ReferenceSchemaDescriptor.FACETED.name(), referenceSchema.isFaceted())
			.e(ReferenceSchemaDescriptor.ATTRIBUTES.name(), createLinkedHashMap(referenceSchema.getAttributes().size()))
			.e(ReferenceSchemaDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name(), createLinkedHashMap(referenceSchema.getSortableAttributeCompounds().size()));

		referenceSchema.getAttributes()
			.values()
			.forEach(attributeSchema -> {
				//noinspection unchecked
				final Map<String, Object> attributes = (Map<String, Object>) referenceSchemaBuilder.get(EntitySchemaDescriptor.ATTRIBUTES.name());
				attributes.put(
					attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
					createAttributeSchemaDto(attributeSchema)
				);
			});

		referenceSchema.getSortableAttributeCompounds()
			.values()
			.forEach(sortableAttributeCompoundSchema -> {
				//noinspection unchecked
				final Map<String, Object> sortableAttributeCompounds = (Map<String, Object>) referenceSchemaBuilder.get(ReferenceSchemaDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name());
				sortableAttributeCompounds.put(
					sortableAttributeCompoundSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
					createSortableAttributeCompoundSchemaDto(sortableAttributeCompoundSchema)
				);
			});

		return referenceSchemaBuilder.build();
	}


	@Nullable
	protected static Object serializeDefaultValue(@Nullable Object object) {
		if (object == null) {
			return null;
		}
		if (object instanceof BigDecimal || object instanceof Long || object instanceof Locale || object instanceof Currency) {
			return object.toString();
		}
		return object;
	}
}
