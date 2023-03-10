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

package io.evitadb.externalApi.rest.api.catalog.dataApi;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.CollectionDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.ParamDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.RestTester.Request;
import io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests for GraphQL catalog collections query.
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
class CatalogRestCollectionsQueryFunctionalTest extends CatalogRestEndpointFunctionalTest {


	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should returns all collections")
	void shouldReturnAllCollections(Evita evita) {
		final var expectedBody = evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getAllEntityTypes).stream()
			.map(entity ->
				map()
					.e(CollectionDescriptor.ENTITY_TYPE.name(), entity)
					.build()
			)
			.toList();

		testRESTCall()
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));
	}

	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Should returns all collections with counts")
	void shouldReturnAllCollectionsWithCounts(Evita evita) {
		final List<Map<String,Object>> expectedBody;
		try(final EvitaSessionContract readOnlySession = evita.createReadOnlySession(TEST_CATALOG)) {
			expectedBody = readOnlySession.getAllEntityTypes().stream()
				.map(entity ->
					map()
						.e(CollectionDescriptor.ENTITY_TYPE.name(), entity)
						.e(CollectionDescriptor.COUNT.name(), readOnlySession.getEntityCollectionSize(entity))
						.build()
				)
				.toList();
		}

		testRESTCall()
			.httpMethod(Request.METHOD_GET)
			.requestParams(map().e(ParamDescriptor.ENTITY_COUNT.name(), Boolean.TRUE).build())
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(expectedBody));
	}

	@Nonnull
	@Override
	protected String getEndpointPath() {
		return "/test-catalog/collections";
	}
}
