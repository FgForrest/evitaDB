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

import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaWithDeprecationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.VersionedDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.RestTester.Request;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
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

	@Nonnull
	@Override
	protected String getEndpointPath() {
		return "/test-catalog";
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return full catalog schema")
	void shouldReturnFullCatalogSchema(Evita evita) {
		testRestCall()
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing mutations when updating catalog schema")
	void shouldReturnErrorForMissingMutationsWhenUpdatingCatalogSchema(Evita evita) {
		testRestCall()
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("{}")
			.executeAndThen()
			.statusCode(400);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should not update catalog schema when no mutations")
	void shouldNotUpdateCatalogSchemaWhenNoMutations(Evita evita) {
		final int initialCatalogSchemVersion = getCatalogSchemaVersion();

		testRestCall()
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should change description of catalog schema")
	void shouldChangeDescriptionOfCatalogSchema(Evita evita) {
		final int initialCatalogSchemVersion = getCatalogSchemaVersion();

		testRestCall()
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should create new catalog attribute schema")
	void shouldCreateNewCatalogAttributeSchema(Evita evita) {
		final int initialCatalogSchemVersion = getCatalogSchemaVersion();

		testRestCall()
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
                {
                    "mutations": [
                        {
                            "createGlobalAttributeSchemaMutation": {
								"name": "mySpecialCode",
								"unique": true,
								"uniqueGlobally": true,
								"filterable": true,
								"sortable": true,
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
		testRestCall()
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
						.e(AttributeSchemaDescriptor.UNIQUE.name(), true)
						.e(GlobalAttributeSchemaDescriptor.UNIQUE_GLOBALLY.name(), true)
						.e(AttributeSchemaDescriptor.FILTERABLE.name(), true)
						.e(AttributeSchemaDescriptor.SORTABLE.name(), true)
						.e(AttributeSchemaDescriptor.LOCALIZED.name(), false)
						.e(AttributeSchemaDescriptor.NULLABLE.name(), false)
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
		testRestCall()
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should create and remove new empty entity schema")
	void shouldCreateAndRemoveNewEmptyEntitySchema(Evita evita) {
		final int initialCatalogSchemaVersion = getCatalogSchemaVersion();

		// create collection
		testRestCall()
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
		testRestCall()
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
						.e(EntitySchemaDescriptor.WITH_PRICE.name(), false)
						.e(EntitySchemaDescriptor.INDEXED_PRICE_PLACES.name(), 2)
						.e(EntitySchemaDescriptor.LOCALES.name(), List.of())
						.e(EntitySchemaDescriptor.CURRENCIES.name(), List.of())
						.e(EntitySchemaDescriptor.EVOLUTION_MODE.name(), Arrays.stream(EvolutionMode.values()).map(Enum::toString).collect(Collectors.toList()))
						.e(EntitySchemaDescriptor.ATTRIBUTES.name(), map())
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
		removeCollection("myNewCollection", initialCatalogSchemaVersion + 2);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should create and remove new filled entity schema")
	void shouldCreateAndRemoveNewFilledEntitySchema(Evita evita) {
		final int initialCatalogSchemaVersion = getCatalogSchemaVersion();

		// create collection
		testRestCall()
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
											"filterable": true,
											"sortable": true,
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
											"filterable": true,
											"faceted": true
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
			.body(CatalogSchemaDescriptor.VERSION.name(), equalTo(initialCatalogSchemaVersion + 1))
			.body(
				"",
				equalTo(
					createCatalogSchemaDto(evita, getCatalogSchemaFromTestData(evita))
				)
			);

		// verify new collection schema
		testRestCall()
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
		removeCollection("myNewCollection", initialCatalogSchemaVersion + 2);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should rename entity schema")
	void shouldRenameEntitySchema(Evita evita) {
		final int initialCatalogSchemaVersion = getCatalogSchemaVersion();

		// rename existing collection
		testRestCall()
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
		testRestCall()
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

	private int getCatalogSchemaVersion() {
		return testRestCall()
			.urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.extract()
			.jsonPath()
			.get(CatalogSchemaDescriptor.VERSION.name());
	}

	private void removeCollection(@Nonnull String entityType, int expectedCatalogVersion) {
		testRestCall()
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
