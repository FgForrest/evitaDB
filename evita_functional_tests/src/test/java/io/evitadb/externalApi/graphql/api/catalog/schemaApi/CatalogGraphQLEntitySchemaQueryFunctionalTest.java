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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi;

import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AssociatedDataSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AssociatedDataSchemasDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemasDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ReferenceSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ReferenceSchemasDescriptor;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLTester;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_LABELS;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_QUANTITY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_URL;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for GraphQL catalog entity schema query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLEntitySchemaQueryFunctionalTest extends CatalogGraphQLSchemaEndpointFunctionalTest {

	private static final String ERRORS_PATH = "errors";
	private static final String PRODUCT_SCHEMA_PATH = "data.get_product_schema";
	private static final Function<String, EntitySchemaContract> FAIL_ON_CALL = s -> {
		fail("Should not be called!");
		return null;
	};

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return basic properties from product schema")
	void shouldReturnBasicPropertiesFromProductSchema(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						get_product_schema {
							__typename
							version
							name
							nameVariants {
								__typename
								camelCase
								pascalCase
								snakeCase
								upperSnakeCase
								kebabCase
							}
							description
							deprecationNotice
							withGeneratedPrimaryKey
							withHierarchy
							withPrice
							indexedPricePlaces
							locales
							currencies
							evolutionMode
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_SCHEMA_PATH,
				equalTo(
					map()
						.e(TYPENAME_FIELD, EntitySchemaDescriptor.THIS_SPECIFIC.name(createEmptyEntitySchema("Product")))
						.e(EntitySchemaDescriptor.VERSION.name(), productSchema.getVersion())
						.e(EntitySchemaDescriptor.NAME.name(), productSchema.getName())
						.e(EntitySchemaDescriptor.NAME_VARIANTS.name(), map()
							.e(TYPENAME_FIELD, NameVariantsDescriptor.THIS.name())
							.e(NameVariantsDescriptor.CAMEL_CASE.name(), productSchema.getNameVariant(NamingConvention.CAMEL_CASE))
							.e(NameVariantsDescriptor.PASCAL_CASE.name(), productSchema.getNameVariant(NamingConvention.PASCAL_CASE))
							.e(NameVariantsDescriptor.SNAKE_CASE.name(), productSchema.getNameVariant(NamingConvention.SNAKE_CASE))
							.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), productSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
							.e(NameVariantsDescriptor.KEBAB_CASE.name(), productSchema.getNameVariant(NamingConvention.KEBAB_CASE))
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
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid basic property")
	void shouldReturnErrorForInvalidBasicProperty(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						get_product_schema {
							reference
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return specific attribute schema")
	void shouldReturnSpecificAttributeSchema(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();

		final AttributeSchemaContract urlSchema = productSchema.getAttribute(ATTRIBUTE_URL).orElseThrow();
		final AttributeSchemaContract quantitySchema = productSchema.getAttribute(ATTRIBUTE_QUANTITY).orElseThrow();
		final AttributeSchemaContract deprecatedSchema = productSchema.getAttribute(ATTRIBUTE_DEPRECATED).orElseThrow();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						get_product_schema {
							attributes {
								__typename
								url {
									__typename
									unique
									filterable
									localized
									defaultValue
								}
								quantity {
									name
									nameVariants {
										camelCase
										pascalCase
										snakeCase
										upperSnakeCase
										kebabCase
									}
									description
									deprecationNotice
									unique
									filterable
									sortable
									localized
									nullable
									defaultValue
									type
									indexedDecimalPlaces
								}
								deprecated {
									deprecationNotice
								}
							}
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_SCHEMA_PATH,
				equalTo(
					map()
						.e(EntitySchemaDescriptor.ATTRIBUTES.name(), map()
							.e(TYPENAME_FIELD, AttributeSchemasDescriptor.THIS.name(createEmptyEntitySchema("Product")))
							.e(ATTRIBUTE_URL, map()
								.e(TYPENAME_FIELD, GlobalAttributeSchemaDescriptor.THIS.name())
								.e(AttributeSchemaDescriptor.UNIQUE.name(), urlSchema.isUnique())
								.e(AttributeSchemaDescriptor.FILTERABLE.name(), urlSchema.isFilterable())
								.e(AttributeSchemaDescriptor.LOCALIZED.name(), urlSchema.isLocalized())
								.e(AttributeSchemaDescriptor.DEFAULT_VALUE.name(), urlSchema.getDefaultValue())
								.build())
							.e(ATTRIBUTE_QUANTITY, map()
								.e(AttributeSchemaDescriptor.NAME.name(), quantitySchema.getName())
								.e(AttributeSchemaDescriptor.NAME_VARIANTS.name(), map()
									.e(NameVariantsDescriptor.CAMEL_CASE.name(), quantitySchema.getNameVariant(NamingConvention.CAMEL_CASE))
									.e(NameVariantsDescriptor.PASCAL_CASE.name(), quantitySchema.getNameVariant(NamingConvention.PASCAL_CASE))
									.e(NameVariantsDescriptor.SNAKE_CASE.name(), quantitySchema.getNameVariant(NamingConvention.SNAKE_CASE))
									.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), quantitySchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
									.e(NameVariantsDescriptor.KEBAB_CASE.name(), quantitySchema.getNameVariant(NamingConvention.KEBAB_CASE))
									.build())
								.e(AttributeSchemaDescriptor.DESCRIPTION.name(), quantitySchema.getDescription())
								.e(AttributeSchemaDescriptor.DEPRECATION_NOTICE.name(), quantitySchema.getDeprecationNotice())
								.e(AttributeSchemaDescriptor.UNIQUE.name(), quantitySchema.isUnique())
								.e(AttributeSchemaDescriptor.FILTERABLE.name(), quantitySchema.isFilterable())
								.e(AttributeSchemaDescriptor.SORTABLE.name(), quantitySchema.isSortable())
								.e(AttributeSchemaDescriptor.LOCALIZED.name(), quantitySchema.isLocalized())
								.e(AttributeSchemaDescriptor.NULLABLE.name(), quantitySchema.isNullable())
								.e(AttributeSchemaDescriptor.DEFAULT_VALUE.name(), quantitySchema.getDefaultValue().toString())
								.e(AttributeSchemaDescriptor.TYPE.name(), quantitySchema.getType().getSimpleName())
								.e(AttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), quantitySchema.getIndexedDecimalPlaces())
								.build())
							.e(ATTRIBUTE_DEPRECATED, map()
								.e(AttributeSchemaDescriptor.DEPRECATION_NOTICE.name(), deprecatedSchema.getDeprecationNotice())
								.build())
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return correctly global attribute schema inside entity schema")
	void shoudReturnCorrectlyGlobalAttributeSchemaInsideEntitySchema(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();

		final GlobalAttributeSchemaContract codeSchema = (GlobalAttributeSchemaContract) productSchema.getAttribute(ATTRIBUTE_CODE).orElseThrow();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						get_product_schema {
							attributes {
								__typename
								code {
									__typename
									name
									nameVariants {
										camelCase
										pascalCase
										snakeCase
										upperSnakeCase
										kebabCase
									}
									description
									deprecationNotice
									unique
									uniqueGlobally
									filterable
									sortable
									localized
									nullable
									defaultValue
									type
									indexedDecimalPlaces
								}
							}
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_SCHEMA_PATH,
				equalTo(
					map()
						.e(EntitySchemaDescriptor.ATTRIBUTES.name(), map()
							.e(TYPENAME_FIELD, AttributeSchemasDescriptor.THIS.name(createEmptyEntitySchema("Product")))
							.e(ATTRIBUTE_CODE, map()
								.e(TYPENAME_FIELD, GlobalAttributeSchemaDescriptor.THIS.name())
								.e(GlobalAttributeSchemaDescriptor.NAME.name(), codeSchema.getName())
								.e(GlobalAttributeSchemaDescriptor.NAME_VARIANTS.name(), map()
									.e(NameVariantsDescriptor.CAMEL_CASE.name(), codeSchema.getNameVariant(NamingConvention.CAMEL_CASE))
									.e(NameVariantsDescriptor.PASCAL_CASE.name(), codeSchema.getNameVariant(NamingConvention.PASCAL_CASE))
									.e(NameVariantsDescriptor.SNAKE_CASE.name(), codeSchema.getNameVariant(NamingConvention.SNAKE_CASE))
									.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), codeSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
									.e(NameVariantsDescriptor.KEBAB_CASE.name(), codeSchema.getNameVariant(NamingConvention.KEBAB_CASE))
									.build())
								.e(GlobalAttributeSchemaDescriptor.DESCRIPTION.name(), codeSchema.getDescription())
								.e(GlobalAttributeSchemaDescriptor.DEPRECATION_NOTICE.name(), codeSchema.getDeprecationNotice())
								.e(GlobalAttributeSchemaDescriptor.UNIQUE.name(), codeSchema.isUnique())
								.e(GlobalAttributeSchemaDescriptor.UNIQUE_GLOBALLY.name(), codeSchema.isUniqueGlobally())
								.e(GlobalAttributeSchemaDescriptor.FILTERABLE.name(), codeSchema.isFilterable())
								.e(GlobalAttributeSchemaDescriptor.SORTABLE.name(), codeSchema.isSortable())
								.e(GlobalAttributeSchemaDescriptor.LOCALIZED.name(), codeSchema.isLocalized())
								.e(GlobalAttributeSchemaDescriptor.NULLABLE.name(), codeSchema.isNullable())
								.e(GlobalAttributeSchemaDescriptor.DEFAULT_VALUE.name(), Optional.ofNullable(codeSchema.getDefaultValue()).map(Object::toString).orElse(null))
								.e(GlobalAttributeSchemaDescriptor.TYPE.name(), codeSchema.getType().getSimpleName())
								.e(GlobalAttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), codeSchema.getIndexedDecimalPlaces())
								.build())
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all attribute schemas")
	void shouldReturnAllAttributeSchemas(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();
		assertFalse(productSchema.getAttributes().isEmpty());

		final List<Map<String, Object>> expectedBody = productSchema.getAttributes()
			.values()
			.stream()
			.map(it -> {
				if (it instanceof GlobalAttributeSchemaContract globalAttributeSchema) {
					return map()
						.e(TYPENAME_FIELD, GlobalAttributeSchemaDescriptor.THIS.name())
						.e(GlobalAttributeSchemaDescriptor.NAME.name(), globalAttributeSchema.getName())
						.e(GlobalAttributeSchemaDescriptor.UNIQUE_GLOBALLY.name(), globalAttributeSchema.isUniqueGlobally())
						.build();
				} else {
					return map()
						.e(TYPENAME_FIELD, AttributeSchemaDescriptor.THIS.name())
						.e(AttributeSchemaDescriptor.NAME.name(), it.getName())
						.build();
				}
			})
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						get_product_schema {
							allAttributes {
								... on AttributeSchema {
									__typename
									name
								}
								... on GlobalAttributeSchema {
									__typename
									name
									uniqueGlobally
								}
							}
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_ATTRIBUTES.name() + "." + TYPENAME_FIELD,
				containsInRelativeOrder(AttributeSchemaDescriptor.THIS.name())
			)
			.body(
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_ATTRIBUTES.name(),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return specific associated data schema")
	void shouldReturnSpecificAssociatedDataSchema(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();

		final AssociatedDataSchemaContract labelsSchema = productSchema.getAssociatedData(ASSOCIATED_DATA_LABELS).orElseThrow();
		final AssociatedDataSchemaContract localizationSchema = productSchema.getAssociatedData(ASSOCIATED_DATA_LOCALIZATION).orElseThrow();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						get_product_schema {
							associatedData {
								__typename
								labels {
									__typename
									name
									nameVariants {
										__typename
										camelCase
										pascalCase
										snakeCase
										upperSnakeCase
										kebabCase
									}
									description
									deprecationNotice
									type
									localized
									nullable
								}
								localization {
									deprecationNotice
								}
							}
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_SCHEMA_PATH,
				equalTo(
					map()
						.e(EntitySchemaDescriptor.ASSOCIATED_DATA.name(), map()
							.e(TYPENAME_FIELD, AssociatedDataSchemasDescriptor.THIS.name(createEmptyEntitySchema("Product")))
							.e(ASSOCIATED_DATA_LABELS, map()
								.e(TYPENAME_FIELD, AssociatedDataSchemaDescriptor.THIS.name())
								.e(AssociatedDataSchemaDescriptor.NAME.name(), labelsSchema.getName())
								.e(AssociatedDataSchemaDescriptor.NAME_VARIANTS.name(), map()
									.e(TYPENAME_FIELD, NameVariantsDescriptor.THIS.name())
									.e(NameVariantsDescriptor.CAMEL_CASE.name(), labelsSchema.getNameVariant(NamingConvention.CAMEL_CASE))
									.e(NameVariantsDescriptor.PASCAL_CASE.name(), labelsSchema.getNameVariant(NamingConvention.PASCAL_CASE))
									.e(NameVariantsDescriptor.SNAKE_CASE.name(), labelsSchema.getNameVariant(NamingConvention.SNAKE_CASE))
									.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), labelsSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
									.e(NameVariantsDescriptor.KEBAB_CASE.name(), labelsSchema.getNameVariant(NamingConvention.KEBAB_CASE))
									.build())
								.e(AssociatedDataSchemaDescriptor.DESCRIPTION.name(), labelsSchema.getDescription())
								.e(AssociatedDataSchemaDescriptor.DEPRECATION_NOTICE.name(), labelsSchema.getDeprecationNotice())
								.e(AssociatedDataSchemaDescriptor.TYPE.name(), labelsSchema.getType().getSimpleName())
								.e(AssociatedDataSchemaDescriptor.LOCALIZED.name(), labelsSchema.isLocalized())
								.e(AssociatedDataSchemaDescriptor.NULLABLE.name(), labelsSchema.isNullable())
								.build())
							.e(ASSOCIATED_DATA_LOCALIZATION, map()
								.e(AttributeSchemaDescriptor.DEPRECATION_NOTICE.name(), localizationSchema.getDeprecationNotice())
								.build())
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all associated data schemas")
	void shouldReturnAllAssociatedDataSchemas(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();
		assertFalse(productSchema.getAssociatedData().isEmpty());

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						get_product_schema {
							allAssociatedData {
								__typename
								name
							}
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_ASSOCIATED_DATA.name() + "." + TYPENAME_FIELD,
				containsInRelativeOrder(AssociatedDataSchemaDescriptor.THIS.name())
			)
			.body(
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_ASSOCIATED_DATA.name() + "." + AssociatedDataSchemaDescriptor.NAME.name(),
				containsInAnyOrder(productSchema.getAssociatedData().keySet().toArray(String[]::new))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return specific reference schema")
	void shouldReturnSpecificReferenceSchema(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();

		final ReferenceSchemaContract brandReferenceSchema = productSchema.getReference(Entities.BRAND).orElseThrow();
		final ReferenceSchemaContract obsoleteBrandReferenceSchema = productSchema.getReference(REFERENCE_OBSOLETE_BRAND).orElseThrow();

		final EntitySchemaContract brandSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.BRAND);
			}
		).orElseThrow();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						get_product_schema {
							references {
								__typename
								brand {
									__typename
									name
									nameVariants {
										camelCase
										pascalCase
										snakeCase
										upperSnakeCase
										kebabCase
									}
									description
									deprecationNotice
									cardinality
									referencedEntityType
									entityTypeNameVariants {
										camelCase
										pascalCase
										snakeCase
										upperSnakeCase
										kebabCase
									}
									referencedEntityTypeManaged
									referencedGroupType
									groupTypeNameVariants {
										camelCase
										pascalCase
										snakeCase
										upperSnakeCase
										kebabCase
									}
									referencedGroupTypeManaged
									filterable
									faceted
								}
								obsoleteBrand {
									deprecationNotice
									referencedEntityType
									entityTypeNameVariants {
										camelCase
										pascalCase
										snakeCase
										upperSnakeCase
										kebabCase
									}
									referencedEntityTypeManaged
									referencedGroupType
									groupTypeNameVariants {
										camelCase
										pascalCase
										snakeCase
										upperSnakeCase
										kebabCase
									}
									referencedGroupTypeManaged
								}
							}
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_SCHEMA_PATH,
				equalTo(
					map()
						.e(EntitySchemaDescriptor.REFERENCES.name(), map()
							.e(TYPENAME_FIELD, ReferenceSchemasDescriptor.THIS.name(createEmptyEntitySchema("Product")))
							.e("brand", map()
								.e(TYPENAME_FIELD, ReferenceSchemaDescriptor.THIS_SPECIFIC.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Brand")))
								.e(ReferenceSchemaDescriptor.NAME.name(), brandReferenceSchema.getName())
								.e(ReferenceSchemaDescriptor.NAME_VARIANTS.name(), map()
									.e(NameVariantsDescriptor.CAMEL_CASE.name(), brandReferenceSchema.getNameVariant(NamingConvention.CAMEL_CASE))
									.e(NameVariantsDescriptor.PASCAL_CASE.name(), brandReferenceSchema.getNameVariant(NamingConvention.PASCAL_CASE))
									.e(NameVariantsDescriptor.SNAKE_CASE.name(), brandReferenceSchema.getNameVariant(NamingConvention.SNAKE_CASE))
									.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), brandReferenceSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
									.e(NameVariantsDescriptor.KEBAB_CASE.name(), brandReferenceSchema.getNameVariant(NamingConvention.KEBAB_CASE))
									.build())
								.e(ReferenceSchemaDescriptor.DESCRIPTION.name(), brandReferenceSchema.getDescription())
								.e(ReferenceSchemaDescriptor.DEPRECATION_NOTICE.name(), brandReferenceSchema.getDeprecationNotice())
								.e(ReferenceSchemaDescriptor.CARDINALITY.name(), brandReferenceSchema.getCardinality().toString())
								.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE.name(), brandReferenceSchema.getReferencedEntityType())
								.e(ReferenceSchemaDescriptor.ENTITY_TYPE_NAME_VARIANTS.name(), map()
									.e(NameVariantsDescriptor.CAMEL_CASE.name(), brandSchema.getNameVariant(NamingConvention.CAMEL_CASE))
									.e(NameVariantsDescriptor.PASCAL_CASE.name(), brandSchema.getNameVariant(NamingConvention.PASCAL_CASE))
									.e(NameVariantsDescriptor.SNAKE_CASE.name(), brandSchema.getNameVariant(NamingConvention.SNAKE_CASE))
									.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), brandSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
									.e(NameVariantsDescriptor.KEBAB_CASE.name(), brandSchema.getNameVariant(NamingConvention.KEBAB_CASE))
									.build())
								.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), brandReferenceSchema.isReferencedEntityTypeManaged())
								.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE.name(), null)
								.e(ReferenceSchemaDescriptor.GROUP_TYPE_NAME_VARIANTS.name(), null)
								.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), brandReferenceSchema.isReferencedGroupTypeManaged())
								.e(ReferenceSchemaDescriptor.FILTERABLE.name(), brandReferenceSchema.isFilterable())
								.e(ReferenceSchemaDescriptor.FACETED.name(), brandReferenceSchema.isFaceted())
								.build())
							.e(REFERENCE_OBSOLETE_BRAND, map()
								.e(ReferenceSchemaDescriptor.DEPRECATION_NOTICE.name(), obsoleteBrandReferenceSchema.getDeprecationNotice())
								.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE.name(), obsoleteBrandReferenceSchema.getReferencedEntityType())
								.e(ReferenceSchemaDescriptor.ENTITY_TYPE_NAME_VARIANTS.name(), map()
									.e(NameVariantsDescriptor.CAMEL_CASE.name(), obsoleteBrandReferenceSchema.getEntityTypeNameVariants(FAIL_ON_CALL).get(NamingConvention.CAMEL_CASE))
									.e(NameVariantsDescriptor.PASCAL_CASE.name(), obsoleteBrandReferenceSchema.getEntityTypeNameVariants(FAIL_ON_CALL).get(NamingConvention.PASCAL_CASE))
									.e(NameVariantsDescriptor.SNAKE_CASE.name(), obsoleteBrandReferenceSchema.getEntityTypeNameVariants(FAIL_ON_CALL).get(NamingConvention.SNAKE_CASE))
									.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), obsoleteBrandReferenceSchema.getEntityTypeNameVariants(FAIL_ON_CALL).get(NamingConvention.UPPER_SNAKE_CASE))
									.e(NameVariantsDescriptor.KEBAB_CASE.name(), obsoleteBrandReferenceSchema.getEntityTypeNameVariants(FAIL_ON_CALL).get(NamingConvention.KEBAB_CASE))
									.build())
								.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), obsoleteBrandReferenceSchema.isReferencedEntityTypeManaged())
								.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE.name(), obsoleteBrandReferenceSchema.getReferencedGroupType())
								.e(ReferenceSchemaDescriptor.GROUP_TYPE_NAME_VARIANTS.name(), map()
									.e(NameVariantsDescriptor.CAMEL_CASE.name(), obsoleteBrandReferenceSchema.getGroupTypeNameVariants(FAIL_ON_CALL).get(NamingConvention.CAMEL_CASE))
									.e(NameVariantsDescriptor.PASCAL_CASE.name(), obsoleteBrandReferenceSchema.getGroupTypeNameVariants(FAIL_ON_CALL).get(NamingConvention.PASCAL_CASE))
									.e(NameVariantsDescriptor.SNAKE_CASE.name(), obsoleteBrandReferenceSchema.getGroupTypeNameVariants(FAIL_ON_CALL).get(NamingConvention.SNAKE_CASE))
									.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), obsoleteBrandReferenceSchema.getGroupTypeNameVariants(FAIL_ON_CALL).get(NamingConvention.UPPER_SNAKE_CASE))
									.e(NameVariantsDescriptor.KEBAB_CASE.name(), obsoleteBrandReferenceSchema.getGroupTypeNameVariants(FAIL_ON_CALL).get(NamingConvention.KEBAB_CASE))
									.build())
								.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), obsoleteBrandReferenceSchema.isReferencedGroupTypeManaged())
								.build())
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return specific attribute schema for specific reference schema")
	void shouldReturnSpecificAttributeSchemaForSpecificReferenceSchema(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();

		final ReferenceSchemaContract brandReferenceSchema = productSchema.getReference(Entities.BRAND).orElseThrow();
		final AttributeSchemaContract brandVisibleForB2CAttributeSchema = brandReferenceSchema.getAttribute(ATTRIBUTE_BRAND_VISIBLE_FOR_B2C).orElseThrow();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						get_product_schema {
							references {
								brand {
									attributes {
										__typename
										brandVisibleForB2C {
											__typename
											name
											nameVariants {
												camelCase
												pascalCase
												snakeCase
												upperSnakeCase
												kebabCase
											}
											description
											deprecationNotice
											unique
											filterable
											sortable
											localized
											nullable
											defaultValue
											type
											indexedDecimalPlaces
										}
									}
								}
							}
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_SCHEMA_PATH,
				equalTo(
					map()
						.e(EntitySchemaDescriptor.REFERENCES.name(), map()
							.e("brand", map()
								.e(ReferenceSchemaDescriptor.ATTRIBUTES.name(), map()
									.e(TYPENAME_FIELD, AttributeSchemasDescriptor.THIS.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Brand")))
									.e(ATTRIBUTE_BRAND_VISIBLE_FOR_B2C, map()
										.e(TYPENAME_FIELD, AttributeSchemaDescriptor.THIS.name())
										.e(AttributeSchemaDescriptor.NAME.name(), brandVisibleForB2CAttributeSchema.getName())
										.e(AttributeSchemaDescriptor.NAME_VARIANTS.name(), map()
											.e(NameVariantsDescriptor.CAMEL_CASE.name(), brandVisibleForB2CAttributeSchema.getNameVariant(NamingConvention.CAMEL_CASE))
											.e(NameVariantsDescriptor.PASCAL_CASE.name(), brandVisibleForB2CAttributeSchema.getNameVariant(NamingConvention.PASCAL_CASE))
											.e(NameVariantsDescriptor.SNAKE_CASE.name(), brandVisibleForB2CAttributeSchema.getNameVariant(NamingConvention.SNAKE_CASE))
											.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), brandVisibleForB2CAttributeSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
											.e(NameVariantsDescriptor.KEBAB_CASE.name(), brandVisibleForB2CAttributeSchema.getNameVariant(NamingConvention.KEBAB_CASE))
											.build())
										.e(AttributeSchemaDescriptor.DESCRIPTION.name(), brandVisibleForB2CAttributeSchema.getDescription())
										.e(AttributeSchemaDescriptor.DEPRECATION_NOTICE.name(), brandVisibleForB2CAttributeSchema.getDeprecationNotice())
										.e(AttributeSchemaDescriptor.UNIQUE.name(), brandVisibleForB2CAttributeSchema.isUnique())
										.e(AttributeSchemaDescriptor.FILTERABLE.name(), brandVisibleForB2CAttributeSchema.isFilterable())
										.e(AttributeSchemaDescriptor.SORTABLE.name(), brandVisibleForB2CAttributeSchema.isSortable())
										.e(AttributeSchemaDescriptor.LOCALIZED.name(), brandVisibleForB2CAttributeSchema.isLocalized())
										.e(AttributeSchemaDescriptor.NULLABLE.name(), brandVisibleForB2CAttributeSchema.isNullable())
										.e(AttributeSchemaDescriptor.DEFAULT_VALUE.name(), brandVisibleForB2CAttributeSchema.getDefaultValue())
										.e(AttributeSchemaDescriptor.TYPE.name(), brandVisibleForB2CAttributeSchema.getType().getSimpleName())
										.e(AttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), brandVisibleForB2CAttributeSchema.getIndexedDecimalPlaces())
										.build())
									.build())
								.build())
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all attribute schemas for specific reference schema")
	void shouldReturnAllAttributeSchemasForSpecificReferenceSchema(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();

		final ReferenceSchemaContract brandReferenceSchema = productSchema.getReference(Entities.BRAND).orElseThrow();
		assertFalse(brandReferenceSchema.getAttributes().isEmpty());

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						get_product_schema {
							references {
								brand {
									allAttributes {
										__typename
										name
									}
								}
							}
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.REFERENCES.name() + ".brand." + ReferenceSchemaDescriptor.ALL_ATTRIBUTES.name() + "." + TYPENAME_FIELD,
				containsInRelativeOrder(AttributeSchemaDescriptor.THIS.name())
			)
			.body(
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.REFERENCES.name() + ".brand." + ReferenceSchemaDescriptor.ALL_ATTRIBUTES.name() + "." + AttributeSchemaDescriptor.NAME.name(),
				containsInAnyOrder(brandReferenceSchema.getAttributes().keySet().toArray(String[]::new))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all reference schemas")
	void shouldReturnAllReferenceSchemas(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();
		assertFalse(productSchema.getReferences().isEmpty());

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						get_product_schema {
							allReferences {
								__typename
								name
							}
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_REFERENCES.name() + "." + TYPENAME_FIELD,
				containsInRelativeOrder(ReferenceSchemaDescriptor.THIS_GENERIC.name())
			)
			.body(
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_REFERENCES.name() + "." + ReferenceSchemaDescriptor.NAME.name(),
				containsInAnyOrder(productSchema.getReferences().keySet().toArray(String[]::new))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all attribute schemas for all reference schemas")
	void shouldReturnAllAttributeSchemasForAllReferenceSchemas(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();
		assertFalse(productSchema.getReferences().isEmpty());

		final List<List<Map<String, Object>>> referencesWithAttributes = productSchema.getReferences()
			.values()
			.stream()
			.map(r -> r.getAttributes().keySet().stream()
				.map(a -> map()
					.e(TYPENAME_FIELD, AttributeSchemaDescriptor.THIS.name())
					.e(AttributeSchemaDescriptor.NAME.name(), a)
					.build())
				.toList())
			.collect(Collectors.toList());

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						get_product_schema {
							allReferences {
								allAttributes {
									__typename
									name
								}
							}
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_REFERENCES.name() + "." + ReferenceSchemaDescriptor.ALL_ATTRIBUTES.name(),
				equalTo(referencesWithAttributes)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid field in all reference schemas")
	void shouldReturnErrorForInvalidFieldInAllReferenceSchemas(GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						get_product_schema {
							allReferences {
								attributes {
									brandVisibleForB2C {
										name
									}
								}
							}
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}
}
