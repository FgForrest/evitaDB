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

package io.evitadb.externalApi.rest.api.catalog.schemaApi;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.externalApi.dataType.DataTypeSerializer;
import io.evitadb.externalApi.rest.api.testSuite.RestEndpointFunctionalTest;
import io.evitadb.utils.MapBuilder;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;
import static io.evitadb.utils.MapBuilder.map;

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
					createAttributeSchemaDto(attributeSchema)
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
			.e(EntitySchemaDescriptor.HIERARCHY_INDEXED.name(),createFlagInScopesDto(entitySchema::isHierarchyIndexedInScope))
			.e(EntitySchemaDescriptor.WITH_PRICE.name(), entitySchema.isWithPrice())
			.e(EntitySchemaDescriptor.PRICE_INDEXED.name(), createFlagInScopesDto(entitySchema::isPriceIndexedInScope))
			.e(EntitySchemaDescriptor.INDEXED_PRICE_PLACES.name(), entitySchema.getIndexedPricePlaces())
			.e(EntitySchemaDescriptor.LOCALES.name(), entitySchema.getLocales().stream().map(Locale::toLanguageTag).collect(Collectors.toList()))
			.e(EntitySchemaDescriptor.CURRENCIES.name(), entitySchema.getCurrencies().stream().map(Currency::toString).collect(Collectors.toList()))
			.e(EntitySchemaDescriptor.EVOLUTION_MODE.name(), entitySchema.getEvolutionMode().stream().map(Enum::toString).collect(Collectors.toList()))
			.e(EntitySchemaDescriptor.ATTRIBUTES.name(), createLinkedHashMap(entitySchema.getAttributes().size()))
			.e(SortableAttributeCompoundsSchemaProviderDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name(), createLinkedHashMap(entitySchema.getSortableAttributeCompounds().size()))
			.e(EntitySchemaDescriptor.ASSOCIATED_DATA.name(), createLinkedHashMap(entitySchema.getAssociatedData().size()))
			.e(EntitySchemaDescriptor.REFERENCES.name(), createLinkedHashMap(entitySchema.getReferences().size()));

		entitySchema.getAttributes()
			.values()
			.forEach(attributeSchema -> {
				//noinspection unchecked
				final Map<String, Object> attributes = (Map<String, Object>) entitySchemaDto.get(EntitySchemaDescriptor.ATTRIBUTES.name());
				attributes.put(
					attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
					createAttributeSchemaDto(attributeSchema)
				);
			});
		entitySchema.getSortableAttributeCompounds()
			.values()
			.forEach(sortableAttributeCompoundSchema -> {
				//noinspection unchecked
				final Map<String, Object> sortableAttributeCompounds = (Map<String, Object>) entitySchemaDto.get(SortableAttributeCompoundsSchemaProviderDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name());
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
		final MapBuilder dtoBuilder = map()
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
			.e(AttributeSchemaDescriptor.UNIQUENESS_TYPE.name(), Arrays.stream(Scope.values())
				.map(scope -> map()
					.e(ScopedDataDescriptor.SCOPE.name(), scope.name())
					.e(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), attributeSchema.getUniquenessType(scope).name())
					.build())
				.toList());
		if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema) {
			dtoBuilder.e(GlobalAttributeSchemaDescriptor.GLOBAL_UNIQUENESS_TYPE.name(), Arrays.stream(Scope.values())
				.map(scope -> map()
					.e(ScopedDataDescriptor.SCOPE.name(), scope.name())
					.e(ScopedGlobalAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), globalAttributeSchema.getGlobalUniquenessType(scope).name())
					.build())
				.toList());
		}
		dtoBuilder
			.e(AttributeSchemaDescriptor.FILTERABLE.name(), createFlagInScopesDto(attributeSchema::isFilterableInScope))
			.e(AttributeSchemaDescriptor.SORTABLE.name(), createFlagInScopesDto(attributeSchema::isSortableInScope))
			.e(AttributeSchemaDescriptor.LOCALIZED.name(), attributeSchema.isLocalized())
			.e(AttributeSchemaDescriptor.NULLABLE.name(), attributeSchema.isNullable());
		if (attributeSchema instanceof EntityAttributeSchemaContract entityAttributeSchema) {
			dtoBuilder.e(EntityAttributeSchemaDescriptor.REPRESENTATIVE.name(), entityAttributeSchema.isRepresentative());
		}
		dtoBuilder
			.e(AttributeSchemaDescriptor.TYPE.name(), DataTypeSerializer.serialize(attributeSchema.getType()))
			.e(AttributeSchemaDescriptor.DEFAULT_VALUE.name(), serializeDefaultValue(attributeSchema.getDefaultValue()))
			.e(AttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), attributeSchema.getIndexedDecimalPlaces());


		return dtoBuilder
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
			.e(SortableAttributeCompoundSchemaDescriptor.INDEXED.name(), createFlagInScopesDto(sortableAttributeCompoundSchema::isIndexedInScope))
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
			return session.getEntitySchemaOrThrowException(s);
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
			.e(ReferenceSchemaDescriptor.INDEXED.name(), createReferenceIndexTypeDto(referenceSchema))
			.e(ReferenceSchemaDescriptor.FACETED.name(), createFlagInScopesDto(referenceSchema::isFacetedInScope))
			.e(ReferenceSchemaDescriptor.ATTRIBUTES.name(), createLinkedHashMap(referenceSchema.getAttributes().size()))
			.e(SortableAttributeCompoundsSchemaProviderDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name(), createLinkedHashMap(referenceSchema.getSortableAttributeCompounds().size()));

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
				final Map<String, Object> sortableAttributeCompounds = (Map<String, Object>) referenceSchemaBuilder.get(SortableAttributeCompoundsSchemaProviderDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name());
				sortableAttributeCompounds.put(
					sortableAttributeCompoundSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION),
					createSortableAttributeCompoundSchemaDto(sortableAttributeCompoundSchema)
				);
			});

		return referenceSchemaBuilder.build();
	}

	/**
	 * Creates a list of string representations of scope names based on the provided filtering predicate.
	 * It filters the available {@link Scope} enum values using the given predicate and maps the filtered
	 * results to their string names.
	 *
	 * @param flagPredicate a predicate defining the filter criteria for the {@link Scope} enum values
	 * @return a list of strings containing the names of the scopes that match the filter criteria
	 */
	@Nonnull
	private static List<String> createFlagInScopesDto(@Nonnull Predicate<Scope> flagPredicate) {
		return Arrays.stream(Scope.values()).filter(flagPredicate).map(Enum::name).toList();
	}

	/**
	 * Creates a list of maps representing the reference index type DTOs based on the provided {@link ReferenceSchemaContract}.
	 * It iterates through all {@link Scope} enumeration values, filters them according to whether they are indexed
	 * in the provided reference schema, and maps them to a structure containing the scope name
	 * and its corresponding reference index type.
	 *
	 * @param referenceSchema the reference schema contract containing details about the reference indexing
	 * @return a list of maps, where each map represents a reference index type with fields for scope and index type
	 */
	@Nonnull
	protected static List<Map<String, Object>> createReferenceIndexTypeDto(@Nonnull ReferenceSchemaContract referenceSchema) {
		return Arrays.stream(Scope.values())
			.filter(referenceSchema::isIndexedInScope)
			.map(scope -> map()
				.e(ScopedReferenceIndexTypeDescriptor.SCOPE.name(), scope.name())
				.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), referenceSchema.getReferenceIndexType(scope).name())
				.build())
			.toList();
	}

	/**
	 * Creates a list of maps that represent the attribute uniqueness type for different scopes.
	 * For each scope, it associates the scope name with the corresponding uniqueness type.
	 * If the scope is `LIVE`, the provided uniqueness type is used. For other scopes,
	 * the uniqueness type is set to `NOT_UNIQUE`.
	 *
	 * @param uniquenessType the attribute uniqueness type to be used for the `LIVE` scope
	 * @return a list of maps, where each map contains the scope name and its corresponding uniqueness type
	 */
	@Nonnull
	protected static List<Map<String, Object>> createAttributeUniquenessTypeDto(@Nonnull AttributeUniquenessType uniquenessType) {
		return Arrays.stream(Scope.values())
			.map(scope -> {
				final AttributeUniquenessType finalUniquenessType = (scope == Scope.LIVE) ? uniquenessType : AttributeUniquenessType.NOT_UNIQUE;
				return map()
					.e(ScopedDataDescriptor.SCOPE.name(), scope.name())
					.e(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), finalUniquenessType.name())
					.build();
			})
			.toList();
	}

	/**
	 * Creates a list of maps representing the global attribute uniqueness type for each scope.
	 * For each {@link Scope}, it associates the scope name with the corresponding uniqueness type.
	 * If the scope is `LIVE`, the provided uniqueness type is used. For other scopes,
	 * the uniqueness type is set to `NOT_UNIQUE`.
	 *
	 * @param uniquenessType the global attribute uniqueness type to be used for the `LIVE` scope
	 * @return a list of maps, where each map contains the scope name and its corresponding global uniqueness type
	 */
	@Nonnull
	protected static List<Map<String, Object>> createGlobalAttributeUniquenessTypeDto(@Nonnull GlobalAttributeUniquenessType uniquenessType) {
		return Arrays.stream(Scope.values())
			.map(scope -> {
				final GlobalAttributeUniquenessType finalUniquenessType = (scope == Scope.LIVE) ? uniquenessType : GlobalAttributeUniquenessType.NOT_UNIQUE;
				return map()
					.e(ScopedDataDescriptor.SCOPE.name(), scope.name())
					.e(ScopedGlobalAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), finalUniquenessType.name())
					.build();
			})
			.toList();
	}

	/**
	 * Serializes a given default value into a string representation if the object is of
	 * specific types (BigDecimal, Long, Locale, or Currency). If the object is not one of
	 * these types, it returns the object itself. If the input is null, null is returned.
	 *
	 * @param object the object to serialize, which can be null
	 * @return the serialized representation of the object if it is of type BigDecimal, Long, Locale, or Currency;
	 *         otherwise, the original object is returned; returns null if the input object is null
	 */
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
