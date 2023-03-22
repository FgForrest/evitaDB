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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_LABELS;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_URL;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for GraphQL catalog schema query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLCatalogSchemaQueryFunctionalTest extends CatalogGraphQLSchemaEndpointFunctionalTest {

	private static final String ERRORS_PATH = "errors";
	private static final String CATALOG_SCHEMA_PATH = "data.get_catalog_schema";

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return basic properties from catalog schema")
	void shouldReturnBasicPropertiesFromCatalogSchema(Evita evita) {
		final SealedCatalogSchema catalogSchema = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogSchema);

		testGraphQLCall()
			.document(
				"""
					query {
						get_catalog_schema {
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
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				CATALOG_SCHEMA_PATH,
				equalTo(
					map()
						.e(TYPENAME_FIELD, CatalogSchemaDescriptor.THIS.name())
						.e(CatalogSchemaDescriptor.VERSION.name(), catalogSchema.getVersion())
						.e(CatalogSchemaDescriptor.NAME.name(), catalogSchema.getName())
						.e(CatalogSchemaDescriptor.NAME_VARIANTS.name(), map()
							.e(TYPENAME_FIELD, NameVariantsDescriptor.THIS.name())
							.e(NameVariantsDescriptor.CAMEL_CASE.name(), catalogSchema.getNameVariants().get(NamingConvention.CAMEL_CASE))
							.e(NameVariantsDescriptor.PASCAL_CASE.name(), catalogSchema.getNameVariants().get(NamingConvention.PASCAL_CASE))
							.e(NameVariantsDescriptor.SNAKE_CASE.name(), catalogSchema.getNameVariants().get(NamingConvention.SNAKE_CASE))
							.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), catalogSchema.getNameVariants().get(NamingConvention.UPPER_SNAKE_CASE))
							.e(NameVariantsDescriptor.KEBAB_CASE.name(), catalogSchema.getNameVariants().get(NamingConvention.KEBAB_CASE))
							.build())
						.e(CatalogSchemaDescriptor.DESCRIPTION.name(), catalogSchema.getDescription())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid basic property")
	void shouldReturnErrorForInvalidBasicProperty(Evita evita) {
		testGraphQLCall()
			.document(
				"""
					query {
						get_catalog_schema {
							references {
								name
							}
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
	void shouldReturnSpecificAttributeSchema(Evita evita) {
		final SealedCatalogSchema catalogSchema = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogSchema);

		final GlobalAttributeSchemaContract urlSchema = catalogSchema.getAttribute(ATTRIBUTE_URL).orElseThrow();

		testGraphQLCall()
			.document(
				"""
					query {
						get_catalog_schema {
							attributes {
								__typename
								url {
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
				CATALOG_SCHEMA_PATH,
				equalTo(
					map()
						.e(EntitySchemaDescriptor.ATTRIBUTES.name(), map()
							.e(TYPENAME_FIELD, GlobalAttributeSchemasDescriptor.THIS.name())
							.e(ATTRIBUTE_URL, map()
								.e(TYPENAME_FIELD, GlobalAttributeSchemaDescriptor.THIS.name())
								.e(GlobalAttributeSchemaDescriptor.NAME.name(), urlSchema.getName())
								.e(GlobalAttributeSchemaDescriptor.NAME_VARIANTS.name(), map()
									.e(TYPENAME_FIELD, NameVariantsDescriptor.THIS.name())
									.e(NameVariantsDescriptor.CAMEL_CASE.name(), urlSchema.getNameVariant(NamingConvention.CAMEL_CASE))
									.e(NameVariantsDescriptor.PASCAL_CASE.name(), urlSchema.getNameVariant(NamingConvention.PASCAL_CASE))
									.e(NameVariantsDescriptor.SNAKE_CASE.name(), urlSchema.getNameVariant(NamingConvention.SNAKE_CASE))
									.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), urlSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
									.e(NameVariantsDescriptor.KEBAB_CASE.name(), urlSchema.getNameVariant(NamingConvention.KEBAB_CASE))
									.build())
								.e(GlobalAttributeSchemaDescriptor.DESCRIPTION.name(), urlSchema.getDescription())
								.e(GlobalAttributeSchemaDescriptor.DEPRECATION_NOTICE.name(), urlSchema.getDeprecationNotice())
								.e(GlobalAttributeSchemaDescriptor.UNIQUE.name(), urlSchema.isUnique())
								.e(GlobalAttributeSchemaDescriptor.UNIQUE_GLOBALLY.name(), urlSchema.isUniqueGlobally())
								.e(GlobalAttributeSchemaDescriptor.FILTERABLE.name(), urlSchema.isFilterable())
								.e(GlobalAttributeSchemaDescriptor.SORTABLE.name(), urlSchema.isSortable())
								.e(GlobalAttributeSchemaDescriptor.LOCALIZED.name(), urlSchema.isLocalized())
								.e(GlobalAttributeSchemaDescriptor.NULLABLE.name(), urlSchema.isNullable())
								.e(GlobalAttributeSchemaDescriptor.DEFAULT_VALUE.name(), urlSchema.getDefaultValue() == null ? null : urlSchema.getDefaultValue().toString())
								.e(GlobalAttributeSchemaDescriptor.TYPE.name(), urlSchema.getType().getSimpleName())
								.e(GlobalAttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), urlSchema.getIndexedDecimalPlaces())
								.build())
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid specific attribute schema")
	void shouldReturnErrorForInvalidSpecificAttributeSchema(Evita evita) {
		testGraphQLCall()
			.document(
				"""
					query {
						get_catalog_schema {
							attributes {
								reference {
									name
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

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all attribute schemas")
	void shouldReturnAllAttributeSchemas(Evita evita) {
		final SealedCatalogSchema catalogSchema = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogSchema);
		assertFalse(catalogSchema.getAttributes().isEmpty());

		testGraphQLCall()
			.document(
				"""
					query {
						get_catalog_schema {
							allAttributes {
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
				CATALOG_SCHEMA_PATH + "." + CatalogSchemaDescriptor.ALL_ATTRIBUTES.name() + "." + TYPENAME_FIELD,
				containsInRelativeOrder(GlobalAttributeSchemaDescriptor.THIS.name())
			)
			.body(
				CATALOG_SCHEMA_PATH + "." + CatalogSchemaDescriptor.ALL_ATTRIBUTES.name() + "." + AttributeSchemaDescriptor.NAME.name(),
				containsInAnyOrder(catalogSchema.getAttributes().keySet().toArray(String[]::new))
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return specific entity schema")
	void shouldReturnSpecificEntitySchema(Evita evita) {
		final SealedCatalogSchema catalogSchema = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogSchema);

		final EntitySchemaContract productSchema = catalogSchema.getEntitySchemaOrThrowException(Entities.PRODUCT);

		testGraphQLCall()
			.document(
				"""
					query {
						get_catalog_schema {
							entitySchemas {
								__typename
								product {
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
									attributes {
										__typename
										url {
											__typename
											name
										}
									}
									allAttributes {
										... on AttributeSchema {
											__typename
		                                    name
		                                }
		                                ... on GlobalAttributeSchema {
		                                    __typename
		                                    name
		                                }
									}
									associatedData {
										__typename
										labels {
											__typename
											name
										}
									}
									allAssociatedData {
										__typename
										name
									}
									references {
										__typename
										brand {
											__typename
											name
										}
									}
									allReferences {
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
				CATALOG_SCHEMA_PATH,
				equalTo(
					map()
						.e(CatalogSchemaDescriptor.ENTITY_SCHEMAS.name(), map()
							.e(TYPENAME_FIELD, EntitySchemasDescriptor.THIS.name())
							.e("product", map()
								.e(TYPENAME_FIELD, EntitySchemaDescriptor.THIS_SPECIFIC.name(createEmptyEntitySchema("Product")))
								.e(EntitySchemaDescriptor.NAME.name(), productSchema.getName())
								.e(EntitySchemaDescriptor.NAME_VARIANTS.name(), map()
									.e(TYPENAME_FIELD, NameVariantsDescriptor.THIS.name())
									.e(NameVariantsDescriptor.CAMEL_CASE.name(), productSchema.getNameVariant(NamingConvention.CAMEL_CASE))
									.e(NameVariantsDescriptor.PASCAL_CASE.name(), productSchema.getNameVariant(NamingConvention.PASCAL_CASE))
									.e(NameVariantsDescriptor.SNAKE_CASE.name(), productSchema.getNameVariant(NamingConvention.SNAKE_CASE))
									.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), productSchema.getNameVariant(NamingConvention.UPPER_SNAKE_CASE))
									.e(NameVariantsDescriptor.KEBAB_CASE.name(), productSchema.getNameVariant(NamingConvention.KEBAB_CASE))
									.build())
								.e(EntitySchemaDescriptor.ATTRIBUTES.name(), map()
									.e(TYPENAME_FIELD, AttributeSchemasDescriptor.THIS.name(createEmptyEntitySchema("Product")))
									.e(ATTRIBUTE_URL, map()
										.e(TYPENAME_FIELD, GlobalAttributeSchemaDescriptor.THIS.name())
										.e(AttributeSchemaDescriptor.NAME.name(), ATTRIBUTE_URL)
										.build())
									.build())
								.e(EntitySchemaDescriptor.ALL_ATTRIBUTES.name(), productSchema.getAttributes().values()
									.stream()
									.map(attributeSchema -> map()
										.e(TYPENAME_FIELD, (attributeSchema instanceof GlobalAttributeSchemaContract) ? GlobalAttributeSchemaDescriptor.THIS.name() : AttributeSchemaDescriptor.THIS.name())
										.e(AttributeSchemaDescriptor.NAME.name(), attributeSchema.getName())
										.build())
									.toList())
								.e(EntitySchemaDescriptor.ASSOCIATED_DATA.name(), map()
									.e(TYPENAME_FIELD, AssociatedDataSchemasDescriptor.THIS.name(createEmptyEntitySchema("Product")))
									.e(ASSOCIATED_DATA_LABELS, map()
										.e(TYPENAME_FIELD, AssociatedDataSchemaDescriptor.THIS.name())
										.e(AssociatedDataSchemaDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS)
										.build())
									.build())
								.e(EntitySchemaDescriptor.ALL_ASSOCIATED_DATA.name(), productSchema.getAssociatedData().keySet()
									.stream()
									.map(name -> map()
										.e(TYPENAME_FIELD, AssociatedDataSchemaDescriptor.THIS.name())
										.e(AssociatedDataSchemaDescriptor.NAME.name(), name)
										.build())
									.toList())
								.e(EntitySchemaDescriptor.REFERENCES.name(), map()
									.e(TYPENAME_FIELD, ReferenceSchemasDescriptor.THIS.name(createEmptyEntitySchema("Product")))
									.e("brand", map()
										.e(TYPENAME_FIELD, ReferenceSchemaDescriptor.THIS_SPECIFIC.name(createEmptyEntitySchema("Product"), createEmptyEntitySchema("Brand")))
										.e(ReferenceSchemaDescriptor.NAME.name(), Entities.BRAND)
										.build())
									.build())
								.e(EntitySchemaDescriptor.ALL_REFERENCES.name(), productSchema.getReferences().keySet()
									.stream()
									.map(name -> map()
										.e(TYPENAME_FIELD, ReferenceSchemaDescriptor.THIS_GENERIC.name())
										.e(ReferenceSchemaDescriptor.NAME.name(), name)
										.build())
									.toList())
								.build())
							.build())
						.build()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all entity schemas")
	void shouldReturnAllAEntitySchemas(Evita evita) {
		final List<SealedEntitySchema> entitySchemas = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getAllEntityTypes().stream()
					.map(session::getEntitySchema)
					.filter(Optional::isPresent)
					.map(Optional::get)
					.toList();
			}
		);

		testGraphQLCall()
			.document(
				"""
					query {
						get_catalog_schema {
							allEntitySchemas {
								__typename
								name
								allAttributes {
									... on AttributeSchema {
										__typename
										name
									}
									... on GlobalAttributeSchema {
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
				CATALOG_SCHEMA_PATH + "." + CatalogSchemaDescriptor.ALL_ENTITY_SCHEMAS.name(),
				equalTo(
					entitySchemas.stream()
						.map(entitySchema ->
							map()
								.e(TYPENAME_FIELD, EntitySchemaDescriptor.THIS_GENERIC.name())
								.e(EntitySchemaDescriptor.NAME.name(), entitySchema.getName())
								.e(EntitySchemaDescriptor.ALL_ATTRIBUTES.name(), entitySchema.getAttributes()
									.values()
									.stream()
									.map(attributeSchema ->
										map()
											.e(TYPENAME_FIELD, (attributeSchema instanceof GlobalAttributeSchemaContract) ? GlobalAttributeSchemaDescriptor.THIS.name() : AttributeSchemaDescriptor.THIS.name())
											.e(AttributeSchemaDescriptor.NAME.name(), attributeSchema.getName())
											.build()
									)
									.toList())
								.build()
						)
						.toList()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for invalid field in all entity schemas")
	void shouldReturnErrorForInvalidFieldInAllAEntitySchemas(Evita evita) {
		testGraphQLCall()
			.document(
				"""
					query {
						get_catalog_schema {
							allEntitySchemas {
								name
								attributes {
									url {
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
