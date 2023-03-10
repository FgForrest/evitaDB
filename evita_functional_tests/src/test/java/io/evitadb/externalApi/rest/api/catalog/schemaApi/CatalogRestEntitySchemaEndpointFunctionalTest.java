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

import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AssociatedDataSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaWithDeprecationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ReferenceSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.SchemaNameVariantsDescriptor;
import io.evitadb.externalApi.rest.api.resolver.serializer.DataTypeSerializer;
import io.evitadb.externalApi.rest.api.testSuite.RestTester.Request;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.builder.MapBuilder;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Functional tests for REST endpoints managing internal evitaDB entity schemas.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CatalogRestEntitySchemaEndpointFunctionalTest extends CatalogRestSchemaEndpointFunctionalTest {

	@Nonnull
	@Override
	protected String getEndpointPath() {
		return "/test-catalog";
	}

	private static final String ERRORS_PATH = "errors";

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return full product schema")
	void shouldReturnFullProductSchema(Evita evita) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();

		final Map<String, Object> expectedBody = createEntitySchemaDto(evita, productSchema);

		testRestCall()
			.urlPathSuffix("/product/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body("", equalTo(expectedBody));
	}

	@Nonnull
	private static Map<String, Object> createEntitySchemaDto(@Nonnull Evita evita, @Nonnull EntitySchemaContract productSchema) {
		final MapBuilder entitySchemaDto = map()
			.e(EntitySchemaDescriptor.VERSION.name(), productSchema.getVersion())
			.e(EntitySchemaDescriptor.NAME.name(), productSchema.getName())
			.e(EntitySchemaDescriptor.NAME_VARIANTS.name(), map()
				.e(SchemaNameVariantsDescriptor.CAMEL_CASE.name(), productSchema.getNameVariant(NamingConvention.CAMEL_CASE))
				.e(SchemaNameVariantsDescriptor.PASCAL_CASE.name(), productSchema.getNameVariant(NamingConvention.PASCAL_CASE))
				.e(SchemaNameVariantsDescriptor.SNAKE_CASE.name(), productSchema.getNameVariant(NamingConvention.SNAKE_CASE))
				.e(SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE.name(), productSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
				.e(SchemaNameVariantsDescriptor.KEBAB_CASE.name(), productSchema.getNameVariant(NamingConvention.KEBAB_CASE))
				.build())
			.e(EntitySchemaDescriptor.DESCRIPTION.name(), productSchema.getDescription())
			.e(EntitySchemaDescriptor.DEPRECATION_NOTICE.name(), productSchema.getDeprecationNotice())
			.e(EntitySchemaDescriptor.WITH_GENERATED_PRIMARY_KEY.name(), productSchema.isWithGeneratedPrimaryKey())
			.e(EntitySchemaDescriptor.WITH_HIERARCHY.name(), productSchema.isWithHierarchy())
			.e(EntitySchemaDescriptor.WITH_PRICE.name(), productSchema.isWithPrice())
			.e(EntitySchemaDescriptor.INDEXED_PRICE_PLACES.name(), productSchema.getIndexedPricePlaces())
			.e(EntitySchemaDescriptor.LOCALES.name(), productSchema.getLocales().stream().map(Locale::toLanguageTag).collect(Collectors.toList()))
			.e(EntitySchemaDescriptor.CURRENCIES.name(), productSchema.getCurrencies().stream().map(Currency::toString).collect(Collectors.toList()))
			.e(EntitySchemaDescriptor.EVOLUTION_MODE.name(), productSchema.getEvolutionMode().stream().map(Enum::toString).collect(Collectors.toList()))
			.e(EntitySchemaDescriptor.ATTRIBUTES.name(), createLinkedHashMap(productSchema.getAttributes().size()))
			.e(EntitySchemaDescriptor.ASSOCIATED_DATA.name(), createLinkedHashMap(productSchema.getAssociatedData().size()))
			.e(EntitySchemaDescriptor.REFERENCES.name(), createLinkedHashMap(productSchema.getReferences().size()));

		productSchema.getAttributes()
			.values()
			.forEach(attributeSchema -> {
				//noinspection unchecked
				final Map<String, Object> attributes = (Map<String, Object>) entitySchemaDto.get(EntitySchemaDescriptor.ATTRIBUTES.name());
				attributes.put(
					attributeSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION),
					attributeSchema instanceof GlobalAttributeSchemaContract ? createGlobalAttributeSchemaDto((GlobalAttributeSchemaContract) attributeSchema) : createAttributeSchemaDto(attributeSchema)
				);
			});
		productSchema.getAssociatedData()
			.values()
			.forEach(associatedDataSchema -> {
				//noinspection unchecked
				final Map<String, Object> associatedData = (Map<String, Object>) entitySchemaDto.get(EntitySchemaDescriptor.ASSOCIATED_DATA.name());
				associatedData.put(
					associatedDataSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION),
					createAssociatedDataSchemaDto(associatedDataSchema)
				);
			});
		productSchema.getReferences()
			.values()
			.forEach(referenceSchema -> {
				//noinspection unchecked
				final Map<String, Object> references = (Map<String, Object>) entitySchemaDto.get(EntitySchemaDescriptor.REFERENCES.name());
				references.put(
					referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION),
					createReferenceSchemaDto(evita, referenceSchema)
				);
			});

		return entitySchemaDto.build();
	}

	@Nonnull
	private static Map<String, Object> createAttributeSchemaDto(@Nonnull AttributeSchemaContract attributeSchema) {
		return map()
			.e(NamedSchemaDescriptor.NAME.name(), attributeSchema.getName())
			.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
				.e(SchemaNameVariantsDescriptor.CAMEL_CASE.name(), attributeSchema.getNameVariant(NamingConvention.CAMEL_CASE))
				.e(SchemaNameVariantsDescriptor.PASCAL_CASE.name(), attributeSchema.getNameVariant(NamingConvention.PASCAL_CASE))
				.e(SchemaNameVariantsDescriptor.SNAKE_CASE.name(), attributeSchema.getNameVariant(NamingConvention.SNAKE_CASE))
				.e(SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE.name(), attributeSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
				.e(SchemaNameVariantsDescriptor.KEBAB_CASE.name(), attributeSchema.getNameVariant(NamingConvention.KEBAB_CASE))
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
	private static Map<String, Object> createGlobalAttributeSchemaDto(@Nonnull GlobalAttributeSchemaContract attributeSchema) {
		return map()
			.e(NamedSchemaDescriptor.NAME.name(), attributeSchema.getName())
			.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
				.e(SchemaNameVariantsDescriptor.CAMEL_CASE.name(), attributeSchema.getNameVariant(NamingConvention.CAMEL_CASE))
				.e(SchemaNameVariantsDescriptor.PASCAL_CASE.name(), attributeSchema.getNameVariant(NamingConvention.PASCAL_CASE))
				.e(SchemaNameVariantsDescriptor.SNAKE_CASE.name(), attributeSchema.getNameVariant(NamingConvention.SNAKE_CASE))
				.e(SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE.name(), attributeSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
				.e(SchemaNameVariantsDescriptor.KEBAB_CASE.name(), attributeSchema.getNameVariant(NamingConvention.KEBAB_CASE))
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
	private static Map<String, Object> createAssociatedDataSchemaDto(@Nonnull AssociatedDataSchemaContract associatedDataSchema) {
		return map()
			.e(NamedSchemaDescriptor.NAME.name(), associatedDataSchema.getName())
			.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
				.e(SchemaNameVariantsDescriptor.CAMEL_CASE.name(), associatedDataSchema.getNameVariant(NamingConvention.CAMEL_CASE))
				.e(SchemaNameVariantsDescriptor.PASCAL_CASE.name(), associatedDataSchema.getNameVariant(NamingConvention.PASCAL_CASE))
				.e(SchemaNameVariantsDescriptor.SNAKE_CASE.name(), associatedDataSchema.getNameVariant(NamingConvention.SNAKE_CASE))
				.e(SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE.name(), associatedDataSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
				.e(SchemaNameVariantsDescriptor.KEBAB_CASE.name(), associatedDataSchema.getNameVariant(NamingConvention.KEBAB_CASE))
				.build())
			.e(NamedSchemaDescriptor.DESCRIPTION.name(), associatedDataSchema.getDescription())
			.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), associatedDataSchema.getDeprecationNotice())
			.e(AssociatedDataSchemaDescriptor.TYPE.name(), DataTypeSerializer.serialize(associatedDataSchema.getType()))
			.e(AssociatedDataSchemaDescriptor.LOCALIZED.name(), associatedDataSchema.isLocalized())
			.e(AssociatedDataSchemaDescriptor.NULLABLE.name(), associatedDataSchema.isNullable())
			.build();
	}

	@Nonnull
	private static Map<String, Object> createReferenceSchemaDto(@Nonnull Evita evita, @Nonnull ReferenceSchemaContract referenceSchema) {
		final Function<String, EntitySchemaContract> ENTITY_SCHEMA_FETCHER = s -> evita.queryCatalog(TEST_CATALOG, session -> {
			return session.getEntitySchemaOrThrow(s);
		});

		final MapBuilder referenceSchemaBuilder = map()
			.e(NamedSchemaDescriptor.NAME.name(), referenceSchema.getName())
			.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
				.e(SchemaNameVariantsDescriptor.CAMEL_CASE.name(), referenceSchema.getNameVariant(NamingConvention.CAMEL_CASE))
				.e(SchemaNameVariantsDescriptor.PASCAL_CASE.name(), referenceSchema.getNameVariant(NamingConvention.PASCAL_CASE))
				.e(SchemaNameVariantsDescriptor.SNAKE_CASE.name(), referenceSchema.getNameVariant(NamingConvention.SNAKE_CASE))
				.e(SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE.name(), referenceSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
				.e(SchemaNameVariantsDescriptor.KEBAB_CASE.name(), referenceSchema.getNameVariant(NamingConvention.KEBAB_CASE))
				.build())
			.e(NamedSchemaDescriptor.DESCRIPTION.name(), referenceSchema.getDescription())
			.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), referenceSchema.getDeprecationNotice())
			.e(ReferenceSchemaDescriptor.CARDINALITY.name(), referenceSchema.getCardinality().toString())
			.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE.name(), referenceSchema.getReferencedEntityType())
			.e(ReferenceSchemaDescriptor.ENTITY_TYPE_NAME_VARIANTS.name(), map()
				.e(SchemaNameVariantsDescriptor.CAMEL_CASE.name(), referenceSchema.getEntityTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.CAMEL_CASE))
				.e(SchemaNameVariantsDescriptor.PASCAL_CASE.name(), referenceSchema.getEntityTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.PASCAL_CASE))
				.e(SchemaNameVariantsDescriptor.SNAKE_CASE.name(), referenceSchema.getEntityTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.SNAKE_CASE))
				.e(SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE.name(), referenceSchema.getEntityTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.UPPER_SNAKE_CASE))
				.e(SchemaNameVariantsDescriptor.KEBAB_CASE.name(), referenceSchema.getEntityTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.KEBAB_CASE))
				.build())
			.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), referenceSchema.isReferencedEntityTypeManaged())
			.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE.name(), referenceSchema.getReferencedGroupType())
			.e(ReferenceSchemaDescriptor.GROUP_TYPE_NAME_VARIANTS.name(), map()
				.e(SchemaNameVariantsDescriptor.CAMEL_CASE.name(), referenceSchema.getGroupTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.CAMEL_CASE))
				.e(SchemaNameVariantsDescriptor.PASCAL_CASE.name(), referenceSchema.getGroupTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.PASCAL_CASE))
				.e(SchemaNameVariantsDescriptor.SNAKE_CASE.name(), referenceSchema.getGroupTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.SNAKE_CASE))
				.e(SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE.name(), referenceSchema.getGroupTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.UPPER_SNAKE_CASE))
				.e(SchemaNameVariantsDescriptor.KEBAB_CASE.name(), referenceSchema.getGroupTypeNameVariants(ENTITY_SCHEMA_FETCHER).get(NamingConvention.KEBAB_CASE))
				.build())
			.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), referenceSchema.isReferencedGroupTypeManaged())
			.e(ReferenceSchemaDescriptor.FILTERABLE.name(), referenceSchema.isFilterable())
			.e(ReferenceSchemaDescriptor.FACETED.name(), referenceSchema.isFaceted())
			.e(ReferenceSchemaDescriptor.ATTRIBUTES.name(), createLinkedHashMap(referenceSchema.getAttributes().size()));

		referenceSchema.getAttributes()
			.values()
			.forEach(attributeSchema -> {
				//noinspection unchecked
				final Map<String, Object> attributes = (Map<String, Object>) referenceSchemaBuilder.get(EntitySchemaDescriptor.ATTRIBUTES.name());
				attributes.put(
					attributeSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION),
					createAttributeSchemaDto(attributeSchema)
				);
			});

		return referenceSchemaBuilder.build();
	}


	@Nullable
	private static Object serializeDefaultValue(@Nullable Object object) {
		if (object == null) {
			return null;
		}
		if (object instanceof BigDecimal || object instanceof Long || object instanceof Locale || object instanceof Currency) {
			return object.toString();
		}
		return object;
	}
}
