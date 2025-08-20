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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
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
import java.util.List;
import java.util.Map;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ENTITY_EMPTY;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.ListBuilder.list;
import static io.evitadb.test.builder.MapBuilder.map;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Functional tests for REST endpoints managing internal evitaDB entity schemas.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class CatalogRestEntitySchemaEndpointFunctionalTest extends CatalogRestSchemaEndpointFunctionalTest {

	private static final String REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE = REST_THOUSAND_PRODUCTS + "forEntitySchemaUpdate";

	@Override
	@DataSet(value = REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE, openWebApi = RestProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita, EvitaServer evitaServer) {
		return super.setUpData(evita, evitaServer, 20, false);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should return full product schema")
	void shouldReturnFullProductSchema(Evita evita, RestTester tester) {
		final EntitySchemaContract productSchema = getEntitySchemaFromTestData(evita, Entities.PRODUCT);
		final Map<String, Object> expectedBody = createEntitySchemaDto(evita, productSchema);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/PRODUCT/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should return error for missing mutations when updating entity schema")
	void shouldReturnErrorForMissingMutationsWhenUpdatingEntitySchema(RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("{}")
			.executeAndThen()
			.statusCode(400);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should not update entity schema when no mutations")
	void shouldNotUpdateCatalogSchemaWhenNoMutations(Evita evita, RestTester tester) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(tester, ENTITY_EMPTY);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
                {
                    "mutations": []
                }
				""")
			.executeAndThen()
			.statusCode(200)
			.body(CatalogSchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion))
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should change entity schema itself")
	void shouldChangeEntitySchemaItself(Evita evita, RestTester tester) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(tester, ENTITY_EMPTY);

		// allow new locales
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
				{
					"mutations": [
						{
							"allowLocaleInEntitySchemaMutation": {
								"locales": ["fr", "it"]
							}
						}
					]
				}
				""")
			.executeAndThen()
			.statusCode(200)
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 1))
			.body(EntitySchemaDescriptor.LOCALES.name(), equalTo(List.of("fr", "it")))
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);;

		// revert
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
				{
					"mutations": [
						{
							"disallowLocaleInEntitySchemaMutation": {
								"locales": ["fr", "it"]
							}
						}
					]
				}
				""")
			.executeAndThen()
			.statusCode(200)
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 2))
			.body(EntitySchemaDescriptor.LOCALES.name(), equalTo(List.of()))
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should change attribute schema")
	void shouldChangeAttributeSchema(Evita evita, RestTester tester) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(tester, ENTITY_EMPTY);

		// add new attribute
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
				{
					"mutations": [
						{
							"createAttributeSchemaMutation": {
								"name": "mySpecialCode",
								"uniqueInScopes": [
									{
										"scope": "LIVE",
										"uniquenessType": "UNIQUE_WITHIN_COLLECTION"
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
			.executeAndExpectOkAndThen()
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 1))
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// verify new attribute schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndExpectOkAndThen()
			.body(
				EntitySchemaDescriptor.ATTRIBUTES.name() + ".mySpecialCode",
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
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// update attribute schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
				{
					"mutations": [
						{
							"modifyAttributeSchemaDescriptionMutation": {
								"name": "mySpecialCode",
								"description": "desc"
							}
						}
					]
				}
				""")
			.executeAndExpectOkAndThen()
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 2))
			.body(
				EntitySchemaDescriptor.ATTRIBUTES.name() + ".mySpecialCode",
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
						.e(NamedSchemaDescriptor.DESCRIPTION.name(), "desc")
						.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
						.e(AttributeSchemaDescriptor.UNIQUENESS_TYPE.name(), createAttributeUniquenessTypeDto(AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION))
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
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// remove attribute
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
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
			.executeAndExpectOkAndThen()
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 3))
			.body(EntitySchemaDescriptor.ATTRIBUTES.name() + ".mySpecialCode", nullValue())
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should change sortable attribute compound schema")
	void shouldChangeSortableAttributeCompoundSchema(Evita evita, RestTester tester) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(tester, ENTITY_EMPTY);

		// add new sortable attribute compound
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
				{
					"mutations": [
						{
							"createSortableAttributeCompoundSchemaMutation": {
								"name": "mySpecialCompound",
								"attributeElements": [
									{
										"attributeName": "code",
										"direction": "ASC",
										"behaviour": "NULLS_LAST"
									},
									{
										"attributeName": "name",
										"direction": "DESC",
										"behaviour": "NULLS_FIRST"
									}
								],
								"indexedInScopes": ["LIVE"]
							}
						}
					]
				}
				""")
			.executeAndExpectOkAndThen()
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 1))
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// verify that the new sortable attribute compound is present
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndExpectOkAndThen()
			.body(
				EntitySchemaDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name() + ".mySpecialCompound",
				equalTo(
					map()
						.e(SortableAttributeCompoundSchemaDescriptor.NAME.name(), "mySpecialCompound")
						.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
							.e(NameVariantsDescriptor.CAMEL_CASE.name(), "mySpecialCompound")
							.e(NameVariantsDescriptor.PASCAL_CASE.name(), "MySpecialCompound")
							.e(NameVariantsDescriptor.SNAKE_CASE.name(), "my_special_compound")
							.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), "MY_SPECIAL_COMPOUND")
							.e(NameVariantsDescriptor.KEBAB_CASE.name(), "my-special-compound")
							.build())
						.e(NamedSchemaDescriptor.DESCRIPTION.name(), null)
						.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
						.e(SortableAttributeCompoundSchemaDescriptor.ATTRIBUTE_ELEMENTS.name(), List.of(
							map()
								.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), "code")
								.e(AttributeElementDescriptor.DIRECTION.name(), OrderDirection.ASC.name())
								.e(AttributeElementDescriptor.BEHAVIOUR.name(), OrderBehaviour.NULLS_LAST.name())
								.build(),
							map()
								.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), "name")
								.e(AttributeElementDescriptor.DIRECTION.name(), OrderDirection.DESC.name())
								.e(AttributeElementDescriptor.BEHAVIOUR.name(), OrderBehaviour.NULLS_FIRST.name())
								.build()
							)
						)
						.e(SortableAttributeCompoundSchemaDescriptor.INDEXED.name(), list().i(Scope.LIVE.name()))
						.build()
				)
			)
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// update sortable attribute compound
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
				{
					"mutations": [
						{
							"modifySortableAttributeCompoundSchemaDescriptionMutation": {
								"name": "mySpecialCompound",
								"description": "desc"
							}
						}
					]
				}
				""")
			.executeAndExpectOkAndThen()
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 2))
			.body(
				EntitySchemaDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name() + ".mySpecialCompound",
				equalTo(
					map()
						.e(SortableAttributeCompoundSchemaDescriptor.NAME.name(), "mySpecialCompound")
						.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
							.e(NameVariantsDescriptor.CAMEL_CASE.name(), "mySpecialCompound")
							.e(NameVariantsDescriptor.PASCAL_CASE.name(), "MySpecialCompound")
							.e(NameVariantsDescriptor.SNAKE_CASE.name(), "my_special_compound")
							.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), "MY_SPECIAL_COMPOUND")
							.e(NameVariantsDescriptor.KEBAB_CASE.name(), "my-special-compound")
							.build())
						.e(NamedSchemaDescriptor.DESCRIPTION.name(), "desc")
						.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
						.e(SortableAttributeCompoundSchemaDescriptor.ATTRIBUTE_ELEMENTS.name(), List.of(
								map()
									.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), "code")
									.e(AttributeElementDescriptor.DIRECTION.name(), OrderDirection.ASC.name())
									.e(AttributeElementDescriptor.BEHAVIOUR.name(), OrderBehaviour.NULLS_LAST.name())
									.build(),
								map()
									.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), "name")
									.e(AttributeElementDescriptor.DIRECTION.name(), OrderDirection.DESC.name())
									.e(AttributeElementDescriptor.BEHAVIOUR.name(), OrderBehaviour.NULLS_FIRST.name())
									.build()
							)
						)
						.e(SortableAttributeCompoundSchemaDescriptor.INDEXED.name(), list().i(Scope.LIVE.name()))
						.build()
				)
			)
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// remove sortable attribute compound
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
				{
					"mutations": [
						{
							"removeSortableAttributeCompoundSchemaMutation": {
								"name": "mySpecialCompound"
							}
						}
					]
				}
				""")
			.executeAndExpectOkAndThen()
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 3))
			.body(
				EntitySchemaDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name() + ".mySpecialCompound",
				nullValue()
			)
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should change associated data schema")
	void shouldChangeAssociatedDataSchema(Evita evita, RestTester tester) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(tester, ENTITY_EMPTY);

		// add new associated data
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
				{
					"mutations": [
						{
							"createAssociatedDataSchemaMutation": {
								"name": "mySpecialLabel",
								"type": "String",
								"localized": true,
								"nullable": false
							}
						}
					]
				}
				""")
			.executeAndThen()
			.statusCode(200)
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 1))
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// verify new associated data schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body(
				EntitySchemaDescriptor.ASSOCIATED_DATA.name() + ".mySpecialLabel",
				equalTo(
					map()
						.e(NamedSchemaDescriptor.NAME.name(), "mySpecialLabel")
						.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
							.e(NameVariantsDescriptor.CAMEL_CASE.name(), "mySpecialLabel")
							.e(NameVariantsDescriptor.PASCAL_CASE.name(), "MySpecialLabel")
							.e(NameVariantsDescriptor.SNAKE_CASE.name(), "my_special_label")
							.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), "MY_SPECIAL_LABEL")
							.e(NameVariantsDescriptor.KEBAB_CASE.name(), "my-special-label")
							.build())
						.e(NamedSchemaDescriptor.DESCRIPTION.name(), null)
						.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
						.e(AssociatedDataSchemaDescriptor.TYPE.name(), String.class.getSimpleName())
						.e(AssociatedDataSchemaDescriptor.LOCALIZED.name(), true)
						.e(AssociatedDataSchemaDescriptor.NULLABLE.name(), false)
						.build()
				)
			)
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// update associated data schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
				{
					"mutations": [
						{
							"modifyAssociatedDataSchemaDescriptionMutation": {
								"name": "mySpecialLabel",
								"description": "desc"
							}
						}
					]
				}
				""")
			.executeAndThen()
			.statusCode(200)
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 2))
			.body(
				EntitySchemaDescriptor.ASSOCIATED_DATA.name() + ".mySpecialLabel",
				equalTo(
					map()
						.e(NamedSchemaDescriptor.NAME.name(), "mySpecialLabel")
						.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
							.e(NameVariantsDescriptor.CAMEL_CASE.name(), "mySpecialLabel")
							.e(NameVariantsDescriptor.PASCAL_CASE.name(), "MySpecialLabel")
							.e(NameVariantsDescriptor.SNAKE_CASE.name(), "my_special_label")
							.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), "MY_SPECIAL_LABEL")
							.e(NameVariantsDescriptor.KEBAB_CASE.name(), "my-special-label")
							.build())
						.e(NamedSchemaDescriptor.DESCRIPTION.name(), "desc")
						.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
						.e(AssociatedDataSchemaDescriptor.TYPE.name(), String.class.getSimpleName())
						.e(AssociatedDataSchemaDescriptor.LOCALIZED.name(), true)
						.e(AssociatedDataSchemaDescriptor.NULLABLE.name(), false)
						.build()
				)
			)
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// remove associated data
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
				{
					"mutations": [
						{
							"removeAssociatedDataSchemaMutation": {
								"name": "mySpecialLabel"
							}
						}
					]
				}
				""")
			.executeAndThen()
			.statusCode(200)
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 3))
			.body(EntitySchemaDescriptor.ASSOCIATED_DATA.name() + ".mySpecialLabel", nullValue())
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_SCHEMA_UPDATE)
	@DisplayName("Should change reference schema")
	void shouldChangeReferenceSchema(Evita evita, RestTester tester) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(tester, ENTITY_EMPTY);

		// add new reference
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
				{
					"mutations": [
						{
							"createReferenceSchemaMutation": {
								"name": "mySpecialTags",
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
				""")
			.executeAndThen()
			.statusCode(200)
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 1))
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// verify new reference schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 1))
			.body(
				EntitySchemaDescriptor.REFERENCES.name() + ".mySpecialTags",
				equalTo(
					map()
						.e(NamedSchemaDescriptor.NAME.name(), "mySpecialTags")
						.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
							.e(NameVariantsDescriptor.CAMEL_CASE.name(), "mySpecialTags")
							.e(NameVariantsDescriptor.PASCAL_CASE.name(), "MySpecialTags")
							.e(NameVariantsDescriptor.SNAKE_CASE.name(), "my_special_tags")
							.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), "MY_SPECIAL_TAGS")
							.e(NameVariantsDescriptor.KEBAB_CASE.name(), "my-special-tags"))
						.e(NamedSchemaDescriptor.DESCRIPTION.name(), null)
						.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
						.e(ReferenceSchemaDescriptor.CARDINALITY.name(), Cardinality.ZERO_OR_MORE.name())
						.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
						.e(ReferenceSchemaDescriptor.ENTITY_TYPE_NAME_VARIANTS.name(), map()
							.e(NameVariantsDescriptor.CAMEL_CASE.name(), "tag")
							.e(NameVariantsDescriptor.PASCAL_CASE.name(), "Tag")
							.e(NameVariantsDescriptor.SNAKE_CASE.name(), "tag")
							.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), "TAG")
							.e(NameVariantsDescriptor.KEBAB_CASE.name(), "tag"))
						.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), false)
						.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE.name(), null)
						.e(ReferenceSchemaDescriptor.GROUP_TYPE_NAME_VARIANTS.name(), map()
							.e(NameVariantsDescriptor.CAMEL_CASE.name(), null)
							.e(NameVariantsDescriptor.PASCAL_CASE.name(), null)
							.e(NameVariantsDescriptor.SNAKE_CASE.name(), null)
							.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), null)
							.e(NameVariantsDescriptor.KEBAB_CASE.name(), null))
						.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), false)
						.e(
							ReferenceSchemaDescriptor.INDEXED.name(),
							createReferenceIndexTypeDto(getEntitySchemaFromTestData(evita, ENTITY_EMPTY).getReference("mySpecialTags").orElseThrow())
						)
						.e(ReferenceSchemaDescriptor.FACETED.name(), list().i(Scope.LIVE.name()))
						.e(ReferenceSchemaDescriptor.ATTRIBUTES.name(), map())
						.e(ReferenceSchemaDescriptor.SORTABLE_ATTRIBUTE_COMPOUNDS.name(), map())
						.build()
				)
			)
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// update reference schema
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
				{
					"mutations": [
						{
							"modifyReferenceAttributeSchemaMutation": {
								"name": "mySpecialTags",
								"attributeSchemaMutation": {
									"createAttributeSchemaMutation": {
										"name": "mySpecialCode",
										"unique": false,
										"filterableInScopes": ["LIVE"],
										"sortableInScopes": [],
										"localized": false,
										"nullable": false,
										"type": "String",
										"indexedDecimalPlaces": 0
									}
								}
							}
						}
					]
				}
				""")
			.executeAndThen()
			.statusCode(200)
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 2))
			.body(
				EntitySchemaDescriptor.REFERENCES.name() + ".mySpecialTags." + ReferenceSchemaDescriptor.ATTRIBUTES.name() + ".mySpecialCode",
				notNullValue()
			)
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// remove reference
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
				{
					"mutations": [
						{
							"removeReferenceSchemaMutation": {
								"name": "mySpecialTags"
							}
						}
					]
				}
				""")
			.executeAndThen()
			.statusCode(200)
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 3))
			.body(EntitySchemaDescriptor.REFERENCES.name() + ".mySpecialTags", nullValue())
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);
	}


	private int getEntitySchemaVersion(@Nonnull RestTester tester, @Nonnull String entityType) {
		return tester.test(TEST_CATALOG)
			.urlPathSuffix("/" + entityType + "/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.extract()
			.jsonPath()
			.get(EntitySchemaDescriptor.VERSION.name());
	}
}
