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

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AssociatedDataSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NamedSchemaWithDeprecationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ReferenceSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.SchemaNameVariantsDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.RestTester.Request;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ENTITY_EMPTY;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
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

	@Nonnull
	@Override
	protected String getEndpointPath() {
		return "/test-catalog";
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return full product schema")
	void shouldReturnFullProductSchema(Evita evita) {
		final EntitySchemaContract productSchema = getEntitySchemaFromTestData(evita, Entities.PRODUCT);
		final Map<String, Object> expectedBody = createEntitySchemaDto(evita, productSchema);

		testRestCall()
			.urlPathSuffix("/product/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error for missing mutations when updating entity schema")
	void shouldReturnErrorForMissingMutationsWhenUpdatingEntitySchema(Evita evita) {
		testRestCall()
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("{}")
			.executeAndThen()
			.statusCode(400);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should not update entity schema when no mutations")
	void shouldNotUpdateCatalogSchemaWhenNoMutations(Evita evita) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(ENTITY_EMPTY);

		testRestCall()
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should change entity schema itself")
	void shouldChangeEntitySchemaItself(Evita evita) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(ENTITY_EMPTY);

		// allow new locales
		testRestCall()
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
		testRestCall()
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should change attribute schema")
	void shouldChangeAttributeSchema(Evita evita) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(ENTITY_EMPTY);

		// add new attribute
		testRestCall()
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody("""
				{
					"mutations": [
						{
							"createAttributeSchemaMutation": {
								"name": "mySpecialCode",
								"unique": true,
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
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 1))
			.body(
				"",
				equalTo(
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// verify new attribute schema
		testRestCall()
			.urlPathSuffix("/empty/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body(
				EntitySchemaDescriptor.ATTRIBUTES.name() + ".mySpecialCode",
				equalTo(
					map()
						.e(NamedSchemaDescriptor.NAME.name(), "mySpecialCode")
						.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
							.e(SchemaNameVariantsDescriptor.CAMEL_CASE.name(), "mySpecialCode")
							.e(SchemaNameVariantsDescriptor.PASCAL_CASE.name(), "MySpecialCode")
							.e(SchemaNameVariantsDescriptor.SNAKE_CASE.name(), "my_special_code")
							.e(SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE.name(), "MY_SPECIAL_CODE")
							.e(SchemaNameVariantsDescriptor.KEBAB_CASE.name(), "my-special-code")
							.build())
						.e(NamedSchemaDescriptor.DESCRIPTION.name(), null)
						.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
						.e(AttributeSchemaDescriptor.UNIQUE.name(), true)
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
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// update attribute schema
		testRestCall()
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
			.executeAndThen()
			.statusCode(200)
			.body(EntitySchemaDescriptor.VERSION.name(), equalTo(initialEntitySchemaVersion + 2))
			.body(
				EntitySchemaDescriptor.ATTRIBUTES.name() + ".mySpecialCode",
				equalTo(
					map()
						.e(NamedSchemaDescriptor.NAME.name(), "mySpecialCode")
						.e(NamedSchemaDescriptor.NAME_VARIANTS.name(), map()
							.e(SchemaNameVariantsDescriptor.CAMEL_CASE.name(), "mySpecialCode")
							.e(SchemaNameVariantsDescriptor.PASCAL_CASE.name(), "MySpecialCode")
							.e(SchemaNameVariantsDescriptor.SNAKE_CASE.name(), "my_special_code")
							.e(SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE.name(), "MY_SPECIAL_CODE")
							.e(SchemaNameVariantsDescriptor.KEBAB_CASE.name(), "my-special-code")
							.build())
						.e(NamedSchemaDescriptor.DESCRIPTION.name(), "desc")
						.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
						.e(AttributeSchemaDescriptor.UNIQUE.name(), true)
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
					createEntitySchemaDto(evita, getEntitySchemaFromTestData(evita, ENTITY_EMPTY))
				)
			);

		// remove attribute
		testRestCall()
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
			.executeAndThen()
			.statusCode(200)
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should change associated data schema")
	void shouldChangeAssociatedDataSchema(Evita evita) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(ENTITY_EMPTY);

		// add new associated data
		testRestCall()
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
		testRestCall()
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
							.e(SchemaNameVariantsDescriptor.CAMEL_CASE.name(), "mySpecialLabel")
							.e(SchemaNameVariantsDescriptor.PASCAL_CASE.name(), "MySpecialLabel")
							.e(SchemaNameVariantsDescriptor.SNAKE_CASE.name(), "my_special_label")
							.e(SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE.name(), "MY_SPECIAL_LABEL")
							.e(SchemaNameVariantsDescriptor.KEBAB_CASE.name(), "my-special-label")
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
		testRestCall()
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
							.e(SchemaNameVariantsDescriptor.CAMEL_CASE.name(), "mySpecialLabel")
							.e(SchemaNameVariantsDescriptor.PASCAL_CASE.name(), "MySpecialLabel")
							.e(SchemaNameVariantsDescriptor.SNAKE_CASE.name(), "my_special_label")
							.e(SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE.name(), "MY_SPECIAL_LABEL")
							.e(SchemaNameVariantsDescriptor.KEBAB_CASE.name(), "my-special-label")
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
		testRestCall()
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should change reference schema")
	void shouldChangeReferenceSchema(Evita evita) {
		final int initialEntitySchemaVersion = getEntitySchemaVersion(ENTITY_EMPTY);

		// add new reference
		testRestCall()
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
								"filterable": true,
								"faceted": true
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
		testRestCall()
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
							.e(SchemaNameVariantsDescriptor.CAMEL_CASE.name(), "mySpecialTags")
							.e(SchemaNameVariantsDescriptor.PASCAL_CASE.name(), "MySpecialTags")
							.e(SchemaNameVariantsDescriptor.SNAKE_CASE.name(), "my_special_tags")
							.e(SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE.name(), "MY_SPECIAL_TAGS")
							.e(SchemaNameVariantsDescriptor.KEBAB_CASE.name(), "my-special-tags"))
						.e(NamedSchemaDescriptor.DESCRIPTION.name(), null)
						.e(NamedSchemaWithDeprecationDescriptor.DEPRECATION_NOTICE.name(), null)
						.e(ReferenceSchemaDescriptor.CARDINALITY.name(), Cardinality.ZERO_OR_MORE.name())
						.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
						.e(ReferenceSchemaDescriptor.ENTITY_TYPE_NAME_VARIANTS.name(), map()
							.e(SchemaNameVariantsDescriptor.CAMEL_CASE.name(), "tag")
							.e(SchemaNameVariantsDescriptor.PASCAL_CASE.name(), "Tag")
							.e(SchemaNameVariantsDescriptor.SNAKE_CASE.name(), "tag")
							.e(SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE.name(), "TAG")
							.e(SchemaNameVariantsDescriptor.KEBAB_CASE.name(), "tag"))
						.e(ReferenceSchemaDescriptor.REFERENCED_ENTITY_TYPE_MANAGED.name(), false)
						.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE.name(), null)
						.e(ReferenceSchemaDescriptor.GROUP_TYPE_NAME_VARIANTS.name(), map()
							.e(SchemaNameVariantsDescriptor.CAMEL_CASE.name(), null)
							.e(SchemaNameVariantsDescriptor.PASCAL_CASE.name(), null)
							.e(SchemaNameVariantsDescriptor.SNAKE_CASE.name(), null)
							.e(SchemaNameVariantsDescriptor.UPPER_SNAKE_CASE.name(), null)
							.e(SchemaNameVariantsDescriptor.KEBAB_CASE.name(), null))
						.e(ReferenceSchemaDescriptor.REFERENCED_GROUP_TYPE_MANAGED.name(), false)
						.e(ReferenceSchemaDescriptor.FILTERABLE.name(), true)
						.e(ReferenceSchemaDescriptor.FACETED.name(), true)
						.e(ReferenceSchemaDescriptor.ATTRIBUTES.name(), map())
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
		testRestCall()
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
										"filterable": true,
										"sortable": false,
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
		testRestCall()
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


	private int getEntitySchemaVersion(@Nonnull String entityType) {
		return testRestCall()
			.urlPathSuffix("/" + entityType + "/schema")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.extract()
			.jsonPath()
			.get(EntitySchemaDescriptor.VERSION.name());
	}
}
