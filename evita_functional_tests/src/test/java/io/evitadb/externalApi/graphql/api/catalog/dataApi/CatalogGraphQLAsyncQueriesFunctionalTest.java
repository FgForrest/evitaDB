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

package io.evitadb.externalApi.graphql.api.catalog.dataApi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.QueryTelemetryDescriptor;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.GraphQLTester;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GraphQL catalog collections query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLAsyncQueriesFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@SneakyThrows
	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should correctly handle multiple parallel queries")
	@Disabled("Proof-of-concept test, if run in parallel with other tests with other evitaDBs, there is not enough threads available to execute all sub queries in parallel.")
	void shouldCorrectlyHandleMultipleParallelQueries(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String singleQueryTemplate = """
				product%d: queryProduct(
					filterBy: {
						entityPrimaryKeyInSet: %d
					}
				) {
					recordPage {
						data {
							primaryKey
							attributes(locale: cs_CZ) {
								code
								name
							}
						}
					}
					extraResults {
						queryTelemetry
					}
				}
			""";

		final StringBuilder queryBuilder = new StringBuilder("query {\n");
		final var expectedBody = createHashMap(originalProductEntities.size());
		originalProductEntities
			.stream()
			.limit(100)
			.sorted(Comparator.comparing(EntityContract::getPrimaryKey))
			.forEach(product -> {
				queryBuilder
					.append(String.format(
						singleQueryTemplate,
						product.getPrimaryKey(),
						product.getPrimaryKey()))
					.append("\n");
				expectedBody.put(
					"product" + product.getPrimaryKey(),
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), product.getPrimaryKey())
						.build()
				);
			});
		queryBuilder.append("}");

		final String responseBodyJson = tester.test(TEST_CATALOG)
			.document(queryBuilder.toString())
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.extract()
			.body()
			.asString();
		final JsonNode body = OBJECT_MAPPER.readTree(responseBodyJson);
		final JsonNode data = body.get("data");
		final List<QueryExecution> queryExecutions = new ArrayList<>(100);
		data.fieldNames().forEachRemaining(f -> {
			final JsonNode product = data.get(f);
			final long start = product.get(ResponseDescriptor.EXTRA_RESULTS.name()).get(ExtraResultsDescriptor.QUERY_TELEMETRY.name()).get(QueryTelemetryDescriptor.START.name()).asLong();
			final long spentTime = product.get(ResponseDescriptor.EXTRA_RESULTS.name()).get(ExtraResultsDescriptor.QUERY_TELEMETRY.name()).get(QueryTelemetryDescriptor.SPENT_TIME.name()).asLong();
			final long end = start + spentTime;
			queryExecutions.add(new QueryExecution(start, end));
		});

		// verify that queries where executes asynchronously
		for (int i = 1; i < queryExecutions.size(); i++) {
			final QueryExecution previousQueryExecution = queryExecutions.get(i - 1);
			final QueryExecution currentQueryExecution = queryExecutions.get(i);
			assertTrue(previousQueryExecution.end() > currentQueryExecution.start(), "Execution " + i + " doesn't overlap previous one.");
		}
	}

	@SneakyThrows
	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should correctly handle large amount of parallel queries")
	@Disabled
	void shouldCorrectlyHandleLargeAmountOfParallelQueries(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final int numberOfThreads = 10;
		final int iterations = 100;
		final ExecutorService service = Executors.newFixedThreadPool(numberOfThreads);
		final CountDownLatch latch = new CountDownLatch(numberOfThreads);

		final AtomicReference<Exception> thrownException = new AtomicReference<>();
		for (int i = 0; i < numberOfThreads; i++) {
			service.execute(() -> {
				try {
					for (int j = 0; j < iterations; j++) {
						final String singleQueryTemplate = """
							product%d: queryProduct(
								filterBy: {
									entityPrimaryKeyInSet: %d
								}
							) {
								recordPage {
									data {
										primaryKey
										attributes(locale: cs_CZ) {
											code
											name
										}
									}
								}
								extraResults {
									queryTelemetry
								}
							}
						""";

						final StringBuilder queryBuilder = new StringBuilder("query {\n");
						final var expectedBody = createHashMap(originalProductEntities.size());
						originalProductEntities
							.stream()
							.limit(100)
							.sorted(Comparator.comparing(EntityContract::getPrimaryKey))
							.forEach(product -> {
								queryBuilder
									.append(String.format(
										singleQueryTemplate,
										product.getPrimaryKey(),
										product.getPrimaryKey()))
									.append("\n");
								expectedBody.put(
									"product" + product.getPrimaryKey(),
									map()
										.e(EntityDescriptor.PRIMARY_KEY.name(), product.getPrimaryKey())
										.build()
								);
							});
						queryBuilder.append("}");

						tester.test(TEST_CATALOG)
							.document(queryBuilder.toString())
							.executeAndThen()
							.statusCode(200)
							.body(ERRORS_PATH, nullValue());
					}
					latch.countDown();
				} catch (Exception ex) {
					thrownException.set(ex);
					latch.countDown();
				}
			});
		}

		assertTrue(latch.await(45, TimeUnit.SECONDS), "Timeouted!");

		if (thrownException.get() != null) {
			throw thrownException.get();
		}
	}

	private record QueryExecution(long start, long end) {}
}
