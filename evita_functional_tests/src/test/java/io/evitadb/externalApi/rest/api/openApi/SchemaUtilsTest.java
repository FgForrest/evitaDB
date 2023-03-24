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

package io.evitadb.externalApi.rest.api.openApi;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.rest.api.catalog.CatalogRestBuilder;
import io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator;
import io.evitadb.externalApi.rest.configuration.RestConfig;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.DbInstanceParameterResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiScalar.scalarFrom;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Description
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Tag(FUNCTIONAL_TEST)
@ExtendWith(DbInstanceParameterResolver.class)
@Slf4j
class SchemaUtilsTest {
	private static final String urlPathToProductList = "/product/list";
	public static final String REST_THOUSAND_PRODUCTS_OPEN_API = REST_THOUSAND_PRODUCTS + "openApi";

	@DataSet(value = REST_THOUSAND_PRODUCTS_OPEN_API, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		TestDataGenerator.generateMockCatalogs(evita);
		final List<SealedEntity> sealedEntities = TestDataGenerator.generateMainCatalogEntities(evita,20);

		final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final OpenAPI openApi = new CatalogRestBuilder(new RestConfig(true, "localhost:5555", "rest", null), evita, catalog).build().openApi();

		return new DataCarrier(
			"entities", sealedEntities,
			"openApi", openApi
		);
	}

	@SuppressWarnings("rawtypes")
	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_OPEN_API)
	@DisplayName("Should get and schema from filter by")
	void shouldGetAndSchemaFromFilterBy(Evita evita, OpenAPI openAPI) {
		final PathItem pathItem = openAPI.getPaths().get(urlPathToProductList);

		final String andConstraintName = "and";
		final Schema andSchema = SchemaUtils.getSchemaFromFilterBy(openAPI, pathItem.getPost(), andConstraintName);
		assertNotNull(andSchema);
		assertEquals(andConstraintName, andSchema.getName());
	}

	@SuppressWarnings("rawtypes")
	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_OPEN_API)
	@DisplayName("Should get lessThan schema from filter by")
	void shouldGetLessThanSchemaFromFilterBy(Evita evita, OpenAPI openAPI) {
		final PathItem pathItem = openAPI.getPaths().get(urlPathToProductList);

		final String quantityLessThanConstraintName = "attribute_quantity_lessThan";
		final Schema lessThanSchema = SchemaUtils.getSchemaFromFilterBy(openAPI, pathItem.getPost(), quantityLessThanConstraintName);
		assertNotNull(lessThanSchema);
		assertEquals(quantityLessThanConstraintName, lessThanSchema.getName());
	}

	@SuppressWarnings("rawtypes")
	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_OPEN_API)
	@DisplayName("Should get dataInLocales schema from require")
	void shouldGetDataInLocalesSchemaFromRequire(Evita evita, OpenAPI openAPI) {
		final PathItem pathItem = openAPI.getPaths().get(urlPathToProductList);

		final String dataInLocalesConstraintName = "dataInLocales";
		final Schema dataInLocalesSchema = SchemaUtils.getSchemaFromRequire(openAPI, pathItem.getPost(), dataInLocalesConstraintName);
		assertNotNull(dataInLocalesSchema);
		assertEquals(dataInLocalesConstraintName, dataInLocalesSchema.getName());
	}

	@SuppressWarnings("rawtypes")
	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_OPEN_API)
	@DisplayName("Should get priorityNatural schema from orderBy")
	void shouldGetPriorityNaturalSchemaFromOrderBy(Evita evita, OpenAPI openAPI) {
		final PathItem pathItem = openAPI.getPaths().get(urlPathToProductList);

		final String priorityNaturalConstraintName = "attribute_priority_natural";
		final Schema priorityNaturalSchema = SchemaUtils.getSchemaFromOrderBy(openAPI, pathItem.getPost(), priorityNaturalConstraintName);
		assertNotNull(priorityNaturalSchema);
		assertEquals(priorityNaturalConstraintName, priorityNaturalSchema.getName());
	}

	@Test
	void shouldGetTargetSchema() {
		final var openAPI = new OpenAPI();
		final Components components = new Components();
		openAPI.setComponents(components);

		final Schema<?> integerSchema = scalarFrom(Integer.class).toSchema();
		integerSchema.setName("MyValue");
		components.addSchemas(integerSchema.getName(), integerSchema);

		final Schema<?> referenceToInt = typeRefTo(integerSchema.getName()).toSchema();
		referenceToInt.setName("MyReference");
		components.addSchemas(referenceToInt.getName(), referenceToInt);

		final Schema<?> topObject = typeRefTo(integerSchema.getName()).toSchema();
		topObject.setName("TopObject");
		components.addSchemas(topObject.getName(), topObject);

		assertEquals(integerSchema, SchemaUtils.getTargetSchema(referenceToInt, openAPI));
		assertEquals(integerSchema, SchemaUtils.getTargetSchema(topObject, openAPI));
	}

	@Test
	void shouldGetTargetSchemaFromRefOrOneOf() {
		final var openAPI = new OpenAPI();
		final Components components = new Components();
		openAPI.setComponents(components);

		final Schema<?> integerSchema = scalarFrom(Integer.class).toSchema();
		integerSchema.setName("MyValue");
		components.addSchemas(integerSchema.getName(), integerSchema);

		final Schema<Object> myObject = new ObjectSchema();
		myObject.addOneOfItem(typeRefTo(integerSchema.getName()).toSchema());
		components.addSchemas("myObject", integerSchema);

		assertEquals(integerSchema, SchemaUtils.getTargetSchemaFromRefOrOneOf(myObject, openAPI));
	}
}