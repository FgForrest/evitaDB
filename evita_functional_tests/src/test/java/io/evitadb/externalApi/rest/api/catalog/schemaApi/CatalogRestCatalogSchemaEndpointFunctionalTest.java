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

import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntityAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaWithDeprecationDescriptor;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.ListBuilder.list;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Functional tests for REST endpoints managing internal evitaDB entity schemas.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class CatalogRestCatalogSchemaEndpointFunctionalTest extends CatalogRestSchemaEndpointFunctionalTest {

	private static final String REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE = REST_THOUSAND_PRODUCTS + "forCatalogSchemaUpdate";

	@Override
	@DataSet(value = REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE, openWebApi = RestProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita, EvitaServer evitaServer) {
		return super.setUpData(evita, evitaServer, 20, false);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should return full catalog schema")
	void shouldReturnFullCatalogSchema(Evita evita, RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					createCatalogSchemaDto(evita, getCatalogSchemaFromTestData(evita))
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should return error for missing mutations when updating catalog schema")
	void shouldReturnErrorForMissingMutationsWhenUpdatingCatalogSchema(Evita evita, RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("{}")
			.executeAndThen()
			.statusCode(400);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should not update catalog schema when no mutations")
	void shouldNotUpdateCatalogSchemaWhenNoMutations(RestTester tester) {
		final int initialCatalogSchemVersion = getCatalogSchemaVersion(tester);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
                {
                    "mutations": []
                }
				""")
			.executeAndThen()
			.statusCode(200)
			.body(CatalogSchemaDescriptor.VERSION.name(), equalTo(initialCatalogSchemVersion));

	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should change description of catalog schema")
	void shouldChangeDescriptionOfCatalogSchema(Evita evita, RestTester tester) {
		final int initialCatalogSchemVersion = getCatalogSchemaVersion(tester);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
                {
                    "mutations": [
                        {
                            "modifyCatalogSchemaDescriptionMutation": {
								"description": "desc"
							}
                        }
                    ]
                }
				""")
			.executeAndThen()
			.statusCode(200)
			.body(CatalogSchemaDescriptor.VERSION.name(), equalTo(initialCatalogSchemVersion + 1))
			.body(CatalogSchemaDescriptor.DESCRIPTION.name(), equalTo("desc"))
			.body(
				"",
				equalTo(
					createCatalogSchemaDto(evita, getCatalogSchemaFromTestData(evita))
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should create new catalog attribute schema")
	void shouldCreateNewCatalogAttributeSchema(Evita evita, RestTester tester) {
		final int initialCatalogSchemVersion = getCatalogSchemaVersion(tester);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
                {
                    "mutations": [
                        {
                            "createGlobalAttributeSchemaMutation": {
								"name": "mySpecialCode",
								"uniqueInScopes": [
									{
										"scope": "LIVE",
										"uniquenessType": "UNIQUE_WITHIN_COLLECTION"
									}
								],
								"uniqueGloballyInScopes": [
									{
										"scope": "LIVE",
										"uniquenessType": "UNIQUE_WITHIN_CATALOG"
									}
								],
								"filterableInScopes": ["LIVE"],
								"sortableInScopes": ["LIVE"],
								"localized": false,
								"nullable": false,
								"type": "String",
								"indexedDecimalPlaces": 0
							}
                        }
                    ]
                }
				""")
			.executeAndThen()
			.statusCode(200)
			.body(CatalogSchemaDescriptor.VERSION.name(), equalTo(initialCatalogSchemVersion + 1))
			.body(
				"",
				equalTo(
					createCatalogSchemaDto(evita, getCatalogSchemaFromTestData(evita))
				)
			);

		// verify attribute
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body(
				CatalogSchemaDescriptor.ATTRIBUTES.name() + ".mySpecialCode",
				equalTo(
					map()
						.e(NamedSchemaDescriptor.NAME.name(), "mySpecialCode")
						.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
							.e(NameVariantsDescriptor.CAMEL_CASE.name(), "mySpecialCode")
							.e(NameVariantsDescriptor.PASCAL_CASE.name(), "MySpecialCode")
							.e(NameVariantsDescriptor.SNAKE_CASE.name(), "my_special_code")
							.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), "MY_SPECIAL_CODE")
							.e(NameVariantsDescriptor.KEBAB_CASE.name(), "my-special-code")
							.build())
						.e(NamedSchemaDescriptor.DESCRIPTION.name(), null)
						.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
						.e(AttributeSchemaDescriptor.UNIQUENESS_TYPE.name(), createAttributeUniquenessTypeDto(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION))
						.e(GlobalAttributeSchemaDescriptor.GLOBAL_UNIQUENESS_TYPE.name(), createGlobalAttributeUniquenessTypeDto(GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG))
						.e(AttributeSchemaDescriptor.FILTERABLE.name(), list().i(Scope.LIVE.name()))
						.e(AttributeSchemaDescriptor.SORTABLE.name(), list().i(Scope.LIVE.name()))
						.e(AttributeSchemaDescriptor.LOCALIZED.name(), false)
						.e(AttributeSchemaDescriptor.NULLABLE.name(), false)
						.e(EntityAttributeSchemaDescriptor.REPRESENTATIVE.name(), false)
						.e(AttributeSchemaDescriptor.TYPE.name(), String.class.getSimpleName())
						.e(AttributeSchemaDescriptor.DEFAULT_VALUE.name(), null)
						.e(AttributeSchemaDescriptor.INDEXED_DECIMAL_PLACES.name(), 0)
						.build()
				)
			)
			.body(
				"",
				equalTo(
					createCatalogSchemaDto(evita, getCatalogSchemaFromTestData(evita))
				)
			);

		// revert
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
                {
                    "mutations": [
                        {
                            "removeAttributeSchemaMutation": {
								"name": "mySpecialCode"
							}
                        }
                    ]
                }
				""")
			.executeAndThen()
			.statusCode(200)
			.body(CatalogSchemaDescriptor.VERSION.name(), equalTo(initialCatalogSchemVersion + 2))
			.body(
				CatalogSchemaDescriptor.ATTRIBUTES.name() + ".mySpecialCode",
				nullValue()
			)
			.body(
				"",
				equalTo(
					createCatalogSchemaDto(evita, getCatalogSchemaFromTestData(evita))
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should create and remove new empty entity schema")
	void shouldCreateAndRemoveNewEmptyEntitySchema(Evita evita, RestTester tester) {
		final int initialCatalogSchemaVersion = getCatalogSchemaVersion(tester);

		// create collection
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
                {
                    "mutations": [
                        {
                            "createEntitySchemaMutation": {
								"entityType": "%s"
							}
                        }
                    ]
                }
				""",
				"myNewCollection"
			)
			.executeAndThen()
			.statusCode(200)
			.body(CatalogSchemaDescriptor.VERSION.name(), equalTo(initialCatalogSchemaVersion + 1))
			.body(
				"",
				equalTo(
					createCatalogSchemaDto(evita, getCatalogSchemaFromTestData(evita))
				)
			);

		// verify new collection schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body(
				CatalogSchemaDescriptor.ENTITY_SCHEMAS.name() + ".myNewCollection",
				equalTo(
					map()
						.e(VersionedDescriptor.VERSION.name(), 1)
						.e(NamedSchemaDescriptor.NAME.name(), "myNewCollection")
						.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
							.e(NameVariantsDescriptor.CAMEL_CASE.name(), "myNewCollection")
							.e(NameVariantsDescriptor.PASCAL_CASE.name(), "MyNewCollection")
							.e(NameVariantsDescriptor.SNAKE_CASE.name(), "my_new_collection")
							.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), "MY_NEW_COLLECTION")
							.e(NameVariantsDescriptor.KEBAB_CASE.name(), "my-new-collection")
							.build())
						.e(NamedSchemaDescriptor.DESCRIPTION.name(), null)
						.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
						.e(EntitySchemaDescriptor.WITH_GENERATED_PRIMARY_KEY.name(), false)
						.e(EntitySchemaDescriptor.WITH_HIERARCHY.name(), false)
						.e(EntitySchemaDescriptor.HIERARCHY_INDEXED.name(), list())
						.e(EntitySchemaDescriptor.WITH_PRICE.name(), false)
						.e(EntitySchemaDescriptor.PRICE_INDEXED.name(), list())
						.e(EntitySchemaDescriptor.INDEXED_PRICE_PLACES.name(), 2)
						.e(EntitySchemaDescriptor.LOCALES.name(), List.of())
						.e(EntitySchemaDescriptor.CURRENCIES.name(), List.of())
						.e(EntitySchemaDescriptor.EVOLUTION_MODE.name(), Arrays.stream(EvolutionMode.values()).map(Enum::toString).collect(Collectors.toList()))
						.e(EntitySchemaDescriptor.ATTRIBUTES.name(), map())
						.e(EntitySchemaDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name(), map())
						.e(EntitySchemaDescriptor.ASSOCIATED_DATA.name(), map())
						.e(EntitySchemaDescriptor.REFERENCES.name(), map())
						.build()
				)
			)
			.body(
				"",
				equalTo(
					createCatalogSchemaDto(evita, getCatalogSchemaFromTestData(evita))
				)
			);

		// remove new collection
		removeCollection(tester, "myNewCollection", initialCatalogSchemaVersion + 2);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should create and remove new filled entity schema")
	void shouldCreateAndRemoveNewFilledEntitySchema(Evita evita, RestTester tester) {
		final int initialCatalogSchemaVersion = getCatalogSchemaVersion(tester);

		// create collection
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
                {
                    "mutations": [
                        {
                            "createEntitySchemaMutation": {
								"entityType": "myNewCollection"
							},
							"modifyEntitySchemaMutation": {
								"entityType": "myNewCollection",
								"schemaMutations": [
									{
										"createAttributeSchemaMutation": {
											"name": "code",
											"unique": true,
											"filterableInScopes": ["LIVE"],
											"sortableInScopes": ["LIVE"],
											"localized": false,
											"nullable": false,
											"type": "String",
											"indexedDecimalPlaces": 0
										},
										"createReferenceSchemaMutation": {
											"name": "tags",
											"referencedEntityType": "tag",
											"referencedEntityTypeManaged": false,
											"referencedGroupTypeManaged": false,
											"indexedInScopes": [
												{
													"scope": "LIVE",
													"indexType": "FOR_FILTERING"
												}
											],
											"facetedInScopes": ["LIVE"]
										}
									}
								]
							}
                        }
                    ]
                }
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(CatalogSchemaDescriptor.VERSION.name(), equalTo(initialCatalogSchemaVersion + 2))
			.body(
				"",
				equalTo(
					createCatalogSchemaDto(evita, getCatalogSchemaFromTestData(evita))
				)
			);

		// verify new collection schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body(CatalogSchemaDescriptor.ENTITY_SCHEMAS.name() + ".myNewCollection." + EntitySchemaDescriptor.ATTRIBUTES.name() + "." + ATTRIBUTE_CODE, notNullValue())
			.body(CatalogSchemaDescriptor.ENTITY_SCHEMAS.name() + ".myNewCollection." + EntitySchemaDescriptor.REFERENCES.name() + ".tags", notNullValue())
			.body(
				"",
				equalTo(
					createCatalogSchemaDto(evita, getCatalogSchemaFromTestData(evita))
				)
			);

		// remove new collection
		removeCollection(tester, "myNewCollection", initialCatalogSchemaVersion + 3);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should rename entity schema")
	void shouldRenameEntitySchema(RestTester tester) {
		final int initialCatalogSchemaVersion = getCatalogSchemaVersion(tester);

		// rename existing collection
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
                {
                    "mutations": [
                        {
                            "modifyEntitySchemaNameMutation": {
								"name": "%s",
								"newName": "%s",
								"overwriteTarget": false
							}
                        }
                    ]
                }
				""",
				Entities.PRODUCT,
				"myNewCollection"
			)
			.executeAndThen()
			.statusCode(200)
			.body(CatalogSchemaDescriptor.VERSION.name(), equalTo(initialCatalogSchemaVersion + 1))
			.body(CatalogSchemaDescriptor.ENTITY_SCHEMAS.name() + ".myNewCollection", notNullValue())
			.body(CatalogSchemaDescriptor.ENTITY_SCHEMAS.name() + ".product", nullValue());

		// rename collection back
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
                {
                    "mutations": [
                        {
                            "modifyEntitySchemaNameMutation": {
								"name": "%s",
								"newName": "%s",
								"overwriteTarget": false
							}
                        }
                    ]
                }
				""",
				"myNewCollection",
				Entities.PRODUCT
			)
			.executeAndThen()
			.statusCode(200)
			.body(CatalogSchemaDescriptor.VERSION.name(), equalTo(initialCatalogSchemaVersion + 2))
			.body(CatalogSchemaDescriptor.ENTITY_SCHEMAS.name() + ".product", notNullValue())
			.body(CatalogSchemaDescriptor.ENTITY_SCHEMAS.name() + ".myNewCollection", nullValue());
	}

	private int getCatalogSchemaVersion(@Nonnull RestTester tester) {
		return tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndExpectOkAndThen()
			.extract()
			.jsonPath()
			.get(CatalogSchemaDescriptor.VERSION.name());
	}

	private void removeCollection(@Nonnull RestTester tester, @Nonnull String entityType, int expectedCatalogVersion) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
                {
                    "mutations": [
                        {
                            "removeEntitySchemaMutation": {
                                "name": "%s"
                            }
                        }
                    ]
                }
				""",
				entityType
			)
			.executeAndThen()
			.statusCode(200)
			.body(CatalogSchemaDescriptor.VERSION.name(), equalTo(expectedCatalogVersion));
	}
}
