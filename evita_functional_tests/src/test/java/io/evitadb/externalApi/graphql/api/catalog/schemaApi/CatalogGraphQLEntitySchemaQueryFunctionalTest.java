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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi;

import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.GraphQLTester;
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
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_LABELS;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_QUANTITY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_URL;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for GraphQL catalog entity schema query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLEntitySchemaQueryFunctionalTest extends CatalogGraphQLEvitaSchemaEndpointFunctionalTest {

	private static final String PRODUCT_SCHEMA_PATH = "data.getProductSchema";
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
						getProductSchema {
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
						.e(VersionedDescriptor.VERSION.name(), productSchema.version())
						.e(NamedSchemaDescriptor.NAME.name(), productSchema.getName())
						.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
							.e(TYPENAME_FIELD, NameVariantsDescriptor.THIS.name())
							.e(NameVariantsDescriptor.CAMEL_CASE.name(), productSchema.getNameVariant(NamingConvention.CAMEL_CASE))
							.e(NameVariantsDescriptor.PASCAL_CASE.name(), productSchema.getNameVariant(NamingConvention.PASCAL_CASE))
							.e(NameVariantsDescriptor.SNAKE_CASE.name(), productSchema.getNameVariant(NamingConvention.SNAKE_CASE))
							.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), productSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
							.e(NameVariantsDescriptor.KEBAB_CASE.name(), productSchema.getNameVariant(NamingConvention.KEBAB_CASE))
							.build())
						.e(NamedSchemaDescriptor.DESCRIPTION.name(), productSchema.getDescription())
						.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), productSchema.getDeprecationNotice())
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
						getProductSchema {
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

		final EntityAttributeSchemaContract codeSchema = productSchema.getAttribute(ATTRIBUTE_CODE).orElseThrow();
		final EntityAttributeSchemaContract urlSchema = productSchema.getAttribute(ATTRIBUTE_URL).orElseThrow();
		final EntityAttributeSchemaContract quantitySchema = productSchema.getAttribute(ATTRIBUTE_QUANTITY).orElseThrow();
		final EntityAttributeSchemaContract deprecatedSchema = productSchema.getAttribute(ATTRIBUTE_DEPRECATED).orElseThrow();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						getProductSchema {
							attributes {
								__typename
								code {
									__typename
									representative
								}
								url {
									__typename
									uniquenessType {
										scope
										uniquenessType
									}
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
									uniquenessType {
										scope
										uniquenessType
									}
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
							.e(ATTRIBUTE_CODE, map()
								.e(TYPENAME_FIELD, GlobalAttributeSchemaDescriptor.THIS.name())
								.e(EntityAttributeSchemaDescriptor.REPRESENTATIVE.name(), codeSchema.isRepresentative())
								.build())
							.e(ATTRIBUTE_URL, map()
								.e(TYPENAME_FIELD, GlobalAttributeSchemaDescriptor.THIS.name())
								.e(AttributeSchemaDescriptor.UNIQUENESS_TYPE.name(), createAttributeUniquenessTypeDto(urlSchema))
								.e(AttributeSchemaDescriptor.FILTERABLE.name(), createAttributeFilterableDto(urlSchema))
								.e(AttributeSchemaDescriptor.LOCALIZED.name(), urlSchema.isLocalized())
								.e(AttributeSchemaDescriptor.DEFAULT_VALUE.name(), urlSchema.getDefaultValue())
								.build())
							.e(ATTRIBUTE_QUANTITY, map()
								.e(NamedSchemaDescriptor.NAME.name(), quantitySchema.getName())
								.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
									.e(NameVariantsDescriptor.CAMEL_CASE.name(), quantitySchema.getNameVariant(NamingConvention.CAMEL_CASE))
									.e(NameVariantsDescriptor.PASCAL_CASE.name(), quantitySchema.getNameVariant(NamingConvention.PASCAL_CASE))
									.e(NameVariantsDescriptor.SNAKE_CASE.name(), quantitySchema.getNameVariant(NamingConvention.SNAKE_CASE))
									.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), quantitySchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
									.e(NameVariantsDescriptor.KEBAB_CASE.name(), quantitySchema.getNameVariant(NamingConvention.KEBAB_CASE))
									.build())
								.e(NamedSchemaDescriptor.DESCRIPTION.name(), quantitySchema.getDescription())
								.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), quantitySchema.getDeprecationNotice())
								.e(AttributeSchemaDescriptor.UNIQUENESS_TYPE.name(), createAttributeUniquenessTypeDto(quantitySchema))
								.e(AttributeSchemaDescriptor.FILTERABLE.name(), createAttributeFilterableDto(quantitySchema))
								.e(AttributeSchemaDescriptor.SORTABLE.name(), createAttributeSortableDto(quantitySchema))
								.e(AttributeSchemaDescriptor.LOCALIZED.name(), quantitySchema.isLocalized())
								.e(AttributeSchemaDescriptor.NULLABLE.name(), quantitySchema.isNullable())
								.e(AttributeSchemaDescriptor.DEFAULT_VALUE.name(), quantitySchema.getDefaultValue().toString())
								.e(AttributeSchemaDescriptor.TYPE.name(), quantitySchema.getType().getSimpleName())
								.e(AttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), quantitySchema.getIndexedDecimalPlaces())
								.build())
							.e(ATTRIBUTE_DEPRECATED, map()
								.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), deprecatedSchema.getDeprecationNotice())
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
						getProductSchema {
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
									uniquenessType {
										scope
										uniquenessType
									}
									globalUniquenessType {
										scope
										uniquenessType
									}
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
								.e(NamedSchemaDescriptor.NAME.name(), codeSchema.getName())
								.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
									.e(NameVariantsDescriptor.CAMEL_CASE.name(), codeSchema.getNameVariant(NamingConvention.CAMEL_CASE))
									.e(NameVariantsDescriptor.PASCAL_CASE.name(), codeSchema.getNameVariant(NamingConvention.PASCAL_CASE))
									.e(NameVariantsDescriptor.SNAKE_CASE.name(), codeSchema.getNameVariant(NamingConvention.SNAKE_CASE))
									.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), codeSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
									.e(NameVariantsDescriptor.KEBAB_CASE.name(), codeSchema.getNameVariant(NamingConvention.KEBAB_CASE))
									.build())
								.e(NamedSchemaDescriptor.DESCRIPTION.name(), codeSchema.getDescription())
								.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), codeSchema.getDeprecationNotice())
								.e(AttributeSchemaDescriptor.UNIQUENESS_TYPE.name(), createAttributeUniquenessTypeDto(codeSchema))
								.e(GlobalAttributeSchemaDescriptor.GLOBAL_UNIQUENESS_TYPE.name(), createGlobalAttributeUniquenessTypeDto(codeSchema))
								.e(AttributeSchemaDescriptor.FILTERABLE.name(), createAttributeFilterableDto(codeSchema))
								.e(AttributeSchemaDescriptor.SORTABLE.name(), createAttributeSortableDto(codeSchema))
								.e(AttributeSchemaDescriptor.LOCALIZED.name(), codeSchema.isLocalized())
								.e(AttributeSchemaDescriptor.NULLABLE.name(), codeSchema.isNullable())
								.e(AttributeSchemaDescriptor.DEFAULT_VALUE.name(), Optional.ofNullable(codeSchema.getDefaultValue()).map(Object::toString).orElse(null))
								.e(AttributeSchemaDescriptor.TYPE.name(), codeSchema.getType().getSimpleName())
								.e(AttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), codeSchema.getIndexedDecimalPlaces())
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
						.e(NamedSchemaDescriptor.NAME.name(), globalAttributeSchema.getName())
						.e(GlobalAttributeSchemaDescriptor.GLOBAL_UNIQUENESS_TYPE.name(), createGlobalAttributeUniquenessTypeDto(globalAttributeSchema))
						.build();
				} else {
					return map()
						.e(TYPENAME_FIELD, EntityAttributeSchemaDescriptor.THIS.name())
						.e(NamedSchemaDescriptor.NAME.name(), it.getName())
						.build();
				}
			})
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						getProductSchema {
							allAttributes {
								... on EntityAttributeSchema {
									__typename
									name
								}
								... on GlobalAttributeSchema {
									__typename
									name
									globalUniquenessType {
										scope
										uniquenessType
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
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_ATTRIBUTES.name(),
				equalTo(expectedBody)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return specific sortable attribute compound schema")
	void shouldReturnSpecificSortableAttributeCompoundSchema(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();

		final SortableAttributeCompoundSchemaContract codeNameSchema = productSchema.getSortableAttributeCompound(SORTABLE_ATTRIBUTE_COMPOUND_CODE_NAME).orElseThrow();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						getProductSchema {
							sortableAttributeCompounds {
								__typename
								codeName {
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
									attributeElements {
										attributeName
										direction
										behaviour
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
						.e(SortableAttributeCompoundsSchemaProviderDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name(), map()
							.e(TYPENAME_FIELD, SortableAttributeCompoundSchemasDescriptor.THIS.name(createEmptyEntitySchema("Product")))
							.e(SORTABLE_ATTRIBUTE_COMPOUND_CODE_NAME, map()
								.e(TYPENAME_FIELD, SortableAttributeCompoundSchemaDescriptor.THIS.name())
								.e(NamedSchemaDescriptor.NAME.name(), codeNameSchema.getName())
								.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
									.e(NameVariantsDescriptor.CAMEL_CASE.name(), codeNameSchema.getNameVariant(NamingConvention.CAMEL_CASE))
									.e(NameVariantsDescriptor.PASCAL_CASE.name(), codeNameSchema.getNameVariant(NamingConvention.PASCAL_CASE))
									.e(NameVariantsDescriptor.SNAKE_CASE.name(), codeNameSchema.getNameVariant(NamingConvention.SNAKE_CASE))
									.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), codeNameSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
									.e(NameVariantsDescriptor.KEBAB_CASE.name(), codeNameSchema.getNameVariant(NamingConvention.KEBAB_CASE))
									.build())
								.e(NamedSchemaDescriptor.DESCRIPTION.name(), codeNameSchema.getDescription())
								.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), codeNameSchema.getDeprecationNotice())
								.e(SortableAttributeCompoundSchemaDescriptor.ATTRIBUTE_ELEMENTS.name(), codeNameSchema.getAttributeElements()
									.stream()
									.map(it -> map()
										.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), it.attributeName())
										.e(AttributeElementDescriptor.DIRECTION.name(), it.direction().name())
										.e(AttributeElementDescriptor.BEHAVIOUR.name(), it.behaviour().name())
										.build())
									.toList())
								.build()))
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all sortable attribute compound schemas")
	void shouldReturnAllSortableAttributeCompoundSchemas(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();
		assertFalse(productSchema.getSortableAttributeCompounds().isEmpty());

		final List<Map<String, Object>> expectedBody = productSchema.getSortableAttributeCompounds()
			.values()
			.stream()
			.map(it -> map()
				.e(TYPENAME_FIELD, SortableAttributeCompoundSchemaDescriptor.THIS.name())
				.e(NamedSchemaDescriptor.NAME.name(), it.getName())
				.build())
			.toList();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						getProductSchema {
							allSortableAttributeCompounds {
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
				PRODUCT_SCHEMA_PATH + "." + SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS.name() + "." + TYPENAME_FIELD,
				containsInRelativeOrder(SortableAttributeCompoundSchemaDescriptor.THIS.name())
			)
			.body(
				PRODUCT_SCHEMA_PATH + "." + SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS.name(),
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
						getProductSchema {
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
								.e(NamedSchemaDescriptor.NAME.name(), labelsSchema.getName())
								.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
									.e(TYPENAME_FIELD, NameVariantsDescriptor.THIS.name())
									.e(NameVariantsDescriptor.CAMEL_CASE.name(), labelsSchema.getNameVariant(NamingConvention.CAMEL_CASE))
									.e(NameVariantsDescriptor.PASCAL_CASE.name(), labelsSchema.getNameVariant(NamingConvention.PASCAL_CASE))
									.e(NameVariantsDescriptor.SNAKE_CASE.name(), labelsSchema.getNameVariant(NamingConvention.SNAKE_CASE))
									.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), labelsSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
									.e(NameVariantsDescriptor.KEBAB_CASE.name(), labelsSchema.getNameVariant(NamingConvention.KEBAB_CASE))
									.build())
								.e(NamedSchemaDescriptor.DESCRIPTION.name(), labelsSchema.getDescription())
								.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), labelsSchema.getDeprecationNotice())
								.e(AssociatedDataSchemaDescriptor.TYPE.name(), labelsSchema.getType().getSimpleName())
								.e(AssociatedDataSchemaDescriptor.LOCALIZED.name(), labelsSchema.isLocalized())
								.e(AssociatedDataSchemaDescriptor.NULLABLE.name(), labelsSchema.isNullable())
								.build())
							.e(ASSOCIATED_DATA_LOCALIZATION, map()
								.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), localizationSchema.getDeprecationNotice())
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
						getProductSchema {
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
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_ASSOCIATED_DATA.name() + "." + NamedSchemaDescriptor.NAME.name(),
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
						getProductSchema {
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
									indexed {
										scope
										indexType
									}
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
								.e(NamedSchemaDescriptor.NAME.name(), brandReferenceSchema.getName())
								.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
									.e(NameVariantsDescriptor.CAMEL_CASE.name(), brandReferenceSchema.getNameVariant(NamingConvention.CAMEL_CASE))
									.e(NameVariantsDescriptor.PASCAL_CASE.name(), brandReferenceSchema.getNameVariant(NamingConvention.PASCAL_CASE))
									.e(NameVariantsDescriptor.SNAKE_CASE.name(), brandReferenceSchema.getNameVariant(NamingConvention.SNAKE_CASE))
									.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), brandReferenceSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
									.e(NameVariantsDescriptor.KEBAB_CASE.name(), brandReferenceSchema.getNameVariant(NamingConvention.KEBAB_CASE))
									.build())
								.e(NamedSchemaDescriptor.DESCRIPTION.name(), brandReferenceSchema.getDescription())
								.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), brandReferenceSchema.getDeprecationNotice())
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
								.e(ReferenceSchemaDescriptor.INDEXED.name(), createReferenceIndexedDto(brandReferenceSchema))
								.e(ReferenceSchemaDescriptor.FACETED.name(), createReferencedFacetedDto(brandReferenceSchema))
								.build())
							.e(REFERENCE_OBSOLETE_BRAND, map()
								.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), obsoleteBrandReferenceSchema.getDeprecationNotice())
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
						getProductSchema {
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
											uniquenessType {
												scope
												uniquenessType
											}
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
										.e(NamedSchemaDescriptor.NAME.name(), brandVisibleForB2CAttributeSchema.getName())
										.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
											.e(NameVariantsDescriptor.CAMEL_CASE.name(), brandVisibleForB2CAttributeSchema.getNameVariant(NamingConvention.CAMEL_CASE))
											.e(NameVariantsDescriptor.PASCAL_CASE.name(), brandVisibleForB2CAttributeSchema.getNameVariant(NamingConvention.PASCAL_CASE))
											.e(NameVariantsDescriptor.SNAKE_CASE.name(), brandVisibleForB2CAttributeSchema.getNameVariant(NamingConvention.SNAKE_CASE))
											.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), brandVisibleForB2CAttributeSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
											.e(NameVariantsDescriptor.KEBAB_CASE.name(), brandVisibleForB2CAttributeSchema.getNameVariant(NamingConvention.KEBAB_CASE))
											.build())
										.e(NamedSchemaDescriptor.DESCRIPTION.name(), brandVisibleForB2CAttributeSchema.getDescription())
										.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), brandVisibleForB2CAttributeSchema.getDeprecationNotice())
										.e(AttributeSchemaDescriptor.UNIQUENESS_TYPE.name(), createAttributeUniquenessTypeDto(brandVisibleForB2CAttributeSchema))
										.e(AttributeSchemaDescriptor.FILTERABLE.name(), createAttributeFilterableDto(brandVisibleForB2CAttributeSchema))
										.e(AttributeSchemaDescriptor.SORTABLE.name(), createAttributeSortableDto(brandVisibleForB2CAttributeSchema))
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
	@DisplayName("Should return specific sortable attribute compound schema for specific reference schema")
	void shouldReturnSpecificSortableAttributeCompoundSchemaForSpecificReferenceSchema(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();

		final ReferenceSchemaContract brandReferenceSchema = productSchema.getReference(Entities.BRAND).orElseThrow();
		final SortableAttributeCompoundSchemaContract foundedMarketShareSchema = brandReferenceSchema.getSortableAttributeCompound(SORTABLE_ATTRIBUTE_COMPOUND_FOUNDED_MARKET_SHARE).orElseThrow();

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						getProductSchema {
							references {
								brand {
									sortableAttributeCompounds {
										__typename
										foundedMarketShare {
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
											attributeElements {
												attributeName
												direction
												behaviour
											}
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
								.e(SortableAttributeCompoundsSchemaProviderDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name(), map()
									.e(TYPENAME_FIELD, SortableAttributeCompoundSchemasDescriptor.THIS.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Brand")))
									.e(SORTABLE_ATTRIBUTE_COMPOUND_FOUNDED_MARKET_SHARE, map()
										.e(TYPENAME_FIELD, SortableAttributeCompoundSchemaDescriptor.THIS.name())
										.e(NamedSchemaDescriptor.NAME.name(), foundedMarketShareSchema.getName())
										.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
											.e(NameVariantsDescriptor.CAMEL_CASE.name(), foundedMarketShareSchema.getNameVariant(NamingConvention.CAMEL_CASE))
											.e(NameVariantsDescriptor.PASCAL_CASE.name(), foundedMarketShareSchema.getNameVariant(NamingConvention.PASCAL_CASE))
											.e(NameVariantsDescriptor.SNAKE_CASE.name(), foundedMarketShareSchema.getNameVariant(NamingConvention.SNAKE_CASE))
											.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), foundedMarketShareSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
											.e(NameVariantsDescriptor.KEBAB_CASE.name(), foundedMarketShareSchema.getNameVariant(NamingConvention.KEBAB_CASE))
											.build())
										.e(NamedSchemaDescriptor.DESCRIPTION.name(), foundedMarketShareSchema.getDescription())
										.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), foundedMarketShareSchema.getDeprecationNotice())
										.e(SortableAttributeCompoundSchemaDescriptor.ATTRIBUTE_ELEMENTS.name(), foundedMarketShareSchema.getAttributeElements()
											.stream()
											.map(it -> map()
												.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), it.attributeName())
												.e(AttributeElementDescriptor.DIRECTION.name(), it.direction().name())
												.e(AttributeElementDescriptor.BEHAVIOUR.name(), it.behaviour().name())
												.build())
											.toList())
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
		assertFalse(brandReferenceSchema.getSortableAttributeCompounds().isEmpty());

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						getProductSchema {
							references {
								brand {
									allSortableAttributeCompounds {
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
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.REFERENCES.name() + ".brand." + SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS.name() + "." + TYPENAME_FIELD,
				containsInRelativeOrder(SortableAttributeCompoundSchemaDescriptor.THIS.name())
			)
			.body(
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.REFERENCES.name() + ".brand." + SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS.name() + "." + NamedSchemaDescriptor.NAME.name(),
				containsInAnyOrder(brandReferenceSchema.getSortableAttributeCompounds().keySet().toArray(String[]::new))
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
						getProductSchema {
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
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_REFERENCES.name() + "." + NamedSchemaDescriptor.NAME.name(),
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
					.e(NamedSchemaDescriptor.NAME.name(), a)
					.build())
				.toList())
			.collect(Collectors.toList());

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						getProductSchema {
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
	@DisplayName("Should return all sortable attribute compound schemas for all reference schemas")
	void shouldReturnAllSortableAttributeCompoundSchemasForAllReferenceSchemas(Evita evita, GraphQLTester tester) {
		final EntitySchemaContract productSchema = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT);
			}
		).orElseThrow();
		assertFalse(productSchema.getReferences().isEmpty());

		final List<List<Map<String, Object>>> referencesWithCompounds = productSchema.getReferences()
			.values()
			.stream()
			.map(r -> r.getSortableAttributeCompounds().keySet().stream()
				.map(a -> map()
					.e(TYPENAME_FIELD, SortableAttributeCompoundSchemaDescriptor.THIS.name())
					.e(NamedSchemaDescriptor.NAME.name(), a)
					.build())
				.toList())
			.collect(Collectors.toList());

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.document(
				"""
					query {
						getProductSchema {
							allReferences {
								allSortableAttributeCompounds {
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
				PRODUCT_SCHEMA_PATH + "." + EntitySchemaDescriptor.ALL_REFERENCES.name() + "." + SortableAttributeCompoundsSchemaProviderDescriptor.ALL_SORTABLE_ATTRIBUTE_COMPOUNDS.name(),
				equalTo(referencesWithCompounds)
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
						getProductSchema {
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
