/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.lab.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.lab.LabProvider;
import io.evitadb.externalApi.lab.api.dto.SchemaDiffRequestBodyDto;
import io.evitadb.externalApi.lab.tools.diff.SchemaDifferTest;
import io.evitadb.externalApi.rest.api.system.model.LivenessDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.RestEndpointFunctionalTest;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.LabApiTester;
import io.evitadb.test.tester.RestTester.Request;
import io.evitadb.utils.NamingConvention;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.CatalogRestDataEndpointFunctionalTest.createEntityDtos;
import static io.evitadb.externalApi.rest.api.catalog.schemaApi.CatalogRestSchemaEndpointFunctionalTest.createCatalogSchemaDto;
import static io.evitadb.externalApi.rest.api.catalog.schemaApi.CatalogRestSchemaEndpointFunctionalTest.getCatalogSchemaFromTestData;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.CZECH_LOCALE;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for REST system management endpoints.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class LabApiEndpointFunctionalTest extends RestEndpointFunctionalTest implements ExternalApiFunctionTestsSupport {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final String LAB_API_URL = "api";
	public static final String LAB_API_THOUSAND_PRODUCTS = "LabApiThousandProducts";

	@Override
	@DataSet(value = LAB_API_THOUSAND_PRODUCTS, openWebApi = LabProvider.CODE)
	protected DataCarrier setUp(Evita evita, EvitaServer evitaServer) {
		return super.setUp(evita, evitaServer);
	}

	@Test
	@UseDataSet(LAB_API_THOUSAND_PRODUCTS)
	@DisplayName("Should return OpenAPI specs")
	void shouldReturnOpenApiSpecs(Evita evita, LabApiTester tester) {
		tester.test(LAB_API_URL)
			.httpMethod(Request.METHOD_GET)
			.acceptHeader("application/yaml")
			.executeAndThen()
			.statusCode(200)
			.body(notNullValue());
	}

	@Test
	@UseDataSet(LAB_API_THOUSAND_PRODUCTS)
	@DisplayName("Should be alive")
	void shouldBeAlive(Evita evita, LabApiTester tester) {
		tester.test(LAB_API_URL)
			.urlPathSuffix("/system/liveness")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					map()
						.e(LivenessDescriptor.LIVENESS.name(), true)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(LAB_API_THOUSAND_PRODUCTS)
	@DisplayName("Should return all catalogs")
	void shouldReturnAllCatalogs(Evita evita, LabApiTester tester) {
		tester.test(LAB_API_URL)
			.urlPathSuffix("/data/catalogs")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					evita.getCatalogs()
						.stream()
						.map(LabApiEndpointFunctionalTest::createCatalogDto)
						.toList()
				)
			);
	}

	@Test
	@UseDataSet(LAB_API_THOUSAND_PRODUCTS)
	@DisplayName("Should return full catalog schema")
	void shouldReturnFullCatalogSchema(Evita evita, LabApiTester tester) {
		tester.test(LAB_API_URL)
			.urlPathSuffix("/schema/catalogs/" + TEST_CATALOG)
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					// todo lho: maybe move these builders to some common place
					createCatalogSchemaDto(evita, getCatalogSchemaFromTestData(evita))
				)
			);
	}

	@Test
	@UseDataSet(LAB_API_THOUSAND_PRODUCTS)
	@DisplayName("Should return full entity response by query")
	void shouldReturnFullEntityResponseByQuery(Evita evita, LabApiTester tester, List<SealedEntity> originalProductEntities) {
		final var pks = findEntityPks(
			originalProductEntities,
			it -> it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null &&
				it.getAllLocales().contains(CZECH_LOCALE) &&
				it.getAllLocales().contains(Locale.ENGLISH)
		);

		final List<String> codes = getAttributesByPks(evita, pks, ATTRIBUTE_CODE);

		final List<EntityClassifier> entities = getEntities(
			evita,
			query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeInSet(ATTRIBUTE_CODE, codes.toArray(String[]::new)),
					entityLocaleEquals(Locale.ENGLISH)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME)
					)
				)
			)
		);

		tester.test(LAB_API_URL)
			.urlPathSuffix("/data/catalogs/" + TEST_CATALOG + "/collections/query")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
                    {
                        "query": "query(collection('PRODUCT'), filterBy(attributeInSet('code', %s), entityLocaleEquals('en')), require(entityFetch(attributeContent('code', 'name'))))"
					}
					""",
				codes.stream().map(it -> "'" + it + "'").collect(Collectors.joining(", ")))
			.executeAndThen()
			.statusCode(200)
			// todo lho: maybe move these builders to some common place
			.body("recordPage.data", equalTo(createEntityDtos(entities)));
	}

	@Test
	@UseDataSet(LAB_API_THOUSAND_PRODUCTS)
	@DisplayName("Should return diff of two GraphQL schemas")
	void shouldReturnDiffOfTwoGraphQLSchemas(Evita evita, LabApiTester tester) throws IOException {
		final SchemaDiffRequestBodyDto requestBody = new SchemaDiffRequestBodyDto(
			readFromClasspath("GraphQLSchemaDifferTest_baseSchema.graphql"),
			readFromClasspath("GraphQLSchemaDifferTest_addedTypeAndQuery.graphql")
		);

		tester.test(LAB_API_URL)
			.urlPathSuffix("/tools/api-schema-diff/graphql")
			.httpMethod(Request.METHOD_POST)
			.requestBody(OBJECT_MAPPER.writeValueAsString(requestBody))
			.executeAndExpectOkAndThen()
			.body("breakingChanges", hasSize(equalTo(0)))
			.body("nonBreakingChanges", hasSize(equalTo(2)))
			.body("unclassifiedChanges", hasSize(equalTo(0)));
	}

	@Test
	@UseDataSet(LAB_API_THOUSAND_PRODUCTS)
	@DisplayName("Should return diff of two OpenApi schemas")
	void shouldReturnDiffOfTwoOpenApiSchemas(Evita evita, LabApiTester tester) throws IOException {
		final SchemaDiffRequestBodyDto requestBody = new SchemaDiffRequestBodyDto(
			readFromClasspath("OpenApiSchemaDifferTest_baseSchema.json"),
			readFromClasspath("OpenApiSchemaDifferTest_addedEndpoint.json")
		);

		tester.test(LAB_API_URL)
			.urlPathSuffix("/tools/api-schema-diff/openapi")
			.httpMethod(Request.METHOD_POST)
			.requestBody(OBJECT_MAPPER.writeValueAsString(requestBody))
			.executeAndExpectOkAndThen()
			.body("breakingChanges", hasSize(equalTo(0)))
			.body("nonBreakingChanges", hasSize(equalTo(1)));
	}

	@Nonnull
	private static Map<String, Object> createCatalogDto(@Nonnull CatalogContract catalog) {
		return map()
			.e(CatalogDescriptor.NAME.name(), catalog.getName())
			.e(CatalogDescriptor.NAME_VARIANTS.name(), map()
				.e(NameVariantsDescriptor.CAMEL_CASE.name(), catalog.getSchema().getNameVariants().get(NamingConvention.CAMEL_CASE))
				.e(NameVariantsDescriptor.PASCAL_CASE.name(), catalog.getSchema().getNameVariants().get(NamingConvention.PASCAL_CASE))
				.e(NameVariantsDescriptor.SNAKE_CASE.name(), catalog.getSchema().getNameVariants().get(NamingConvention.SNAKE_CASE))
				.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), catalog.getSchema().getNameVariants().get(NamingConvention.UPPER_SNAKE_CASE))
				.e(NameVariantsDescriptor.KEBAB_CASE.name(), catalog.getSchema().getNameVariants().get(NamingConvention.KEBAB_CASE)))
			.e(CatalogDescriptor.VERSION.name(), String.valueOf(catalog.getVersion()))
			.e(CatalogDescriptor.CATALOG_STATE.name(), catalog.getCatalogState().name())
			.e(CatalogDescriptor.SUPPORTS_TRANSACTION.name(), catalog.supportsTransaction())
			.e(CatalogDescriptor.ENTITY_TYPES.name(), new ArrayList<>(catalog.getEntityTypes()))
			.e(CatalogDescriptor.CORRUPTED.name(), false)
			.build();
	}

	@Nonnull
	protected static String readFromClasspath(@Nonnull String path) throws IOException {
		return IOUtils.toString(
			Objects.requireNonNull(SchemaDifferTest.class.getClassLoader().getResourceAsStream("testData/" + path)),
			StandardCharsets.UTF_8
		);
	}
}
