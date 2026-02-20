/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.graphql.api.catalog;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.CatalogGraphQLDataEndpointFunctionalTest;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.GraphQLTester;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Long-running throughput test that fires 100k GraphQL queries in parallel against a shared evitaDB instance.
 * Verifies correctness and stability under sustained high query volume -- the system must handle the load
 * without errors, deadlocks, or resource exhaustion.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2026
 */
@Tag(LONG_RUNNING_TEST)
@Slf4j
@Disabled("Disabled by default due to long execution time. Enable for performance testing or benchmarking purposes.")
public class CatalogGraphQLThroughputTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final int TOTAL_QUERIES = 100_000;
	private static final int NUMBER_OF_THREADS = 20;
	private static final int QUERIES_PER_THREAD = TOTAL_QUERIES / NUMBER_OF_THREADS;
	private static final int PROGRESS_LOG_INTERVAL = 10_000;

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should handle 100,000 queries in parallel without errors")
	@Timeout(value = 10, unit = TimeUnit.MINUTES)
	void shouldHandleHundredThousandQueriesInParallel(
		@Nonnull GraphQLTester tester,
		@Nonnull List<SealedEntity> originalProductEntities
	) throws Exception {
		final String[] codes = originalProductEntities.stream()
			.map(entity -> entity.getAttribute("code", String.class))
			.filter(Objects::nonNull)
			.toArray(String[]::new);

		final int[] productPks = originalProductEntities.stream()
			.mapToInt(SealedEntity::getPrimaryKey)
			.toArray();

		final ExecutorService executor = Executors.newFixedThreadPool(NUMBER_OF_THREADS);
		final CountDownLatch latch = new CountDownLatch(NUMBER_OF_THREADS);
		final AtomicLong completedQueries = new AtomicLong();
		final AtomicInteger errorCount = new AtomicInteger();
		final AtomicReference<Exception> firstFailure = new AtomicReference<>();

		final long startTime = System.nanoTime();

		for (int t = 0; t < NUMBER_OF_THREADS; t++) {
			executor.execute(() -> {
				try {
					final ThreadLocalRandom random = ThreadLocalRandom.current();
					for (int i = 0; i < QUERIES_PER_THREAD; i++) {
						try {
							executeRandomQuery(tester, random, productPks, codes);

							final long completed = completedQueries.incrementAndGet();
							if (completed % PROGRESS_LOG_INTERVAL == 0) {
								final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
								log.info("Progress: {}/{} queries completed in {} ms ({} q/s)",
									completed, TOTAL_QUERIES, elapsed,
									elapsed > 0 ? (completed * 1000 / elapsed) : "N/A");
							}
						} catch (Exception ex) {
							errorCount.incrementAndGet();
							firstFailure.compareAndSet(null, ex);
							completedQueries.incrementAndGet();
						}
					}
				} finally {
					latch.countDown();
				}
			});
		}

		latch.await(10, TimeUnit.MINUTES);
		executor.shutdown();

		final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
		final long completed = completedQueries.get();
		final int errors = errorCount.get();

		log.info("=== Throughput Test Summary ===");
		log.info("Total queries executed: {}", completed);
		log.info("Total time: {} ms", elapsedMs);
		log.info("Throughput: {} queries/second", elapsedMs > 0 ? (completed * 1000 / elapsedMs) : "N/A");
		log.info("Errors: {}", errors);

		if (firstFailure.get() != null) {
			log.error("First failure:", firstFailure.get());
		}

		assertEquals(0, errors, "Expected zero errors but got " + errors +
			(firstFailure.get() != null ? ". First failure: " + firstFailure.get().getMessage() : ""));
	}

	/**
	 * Dispatches a randomly chosen query type to exercise different GraphQL endpoint paths.
	 */
	private void executeRandomQuery(
		@Nonnull GraphQLTester tester,
		@Nonnull ThreadLocalRandom random,
		@Nonnull int[] productPks,
		@Nonnull String[] codes
	) {
		final int queryType = random.nextInt(7);
		switch (queryType) {
			case 0 -> executeListByPk(tester, random, productPks);
			case 1 -> executeListByAttribute(tester, random, codes);
			case 2 -> executeListWithLocale(tester, random, codes);
			case 3 -> executeListWithPrice(tester, random, productPks);
			case 4 -> executeListWithReferences(tester, random, productPks);
			case 5 -> executeListWithOrdering(tester);
			case 6 -> executeListCategory(tester, random);
			default -> throw new IllegalStateException("Unexpected query type: " + queryType);
		}
	}

	private void executeListByPk(
		@Nonnull GraphQLTester tester,
		@Nonnull ThreadLocalRandom random,
		@Nonnull int[] productPks
	) {
		final int pk1 = productPks[random.nextInt(productPks.length)];
		final int pk2 = productPks[random.nextInt(productPks.length)];

		tester.test(TEST_CATALOG)
			.document(
				"""
				query {
				    listProduct(
				        filterBy: {
				            entityPrimaryKeyInSet: [%d, %d]
				        }
				    ) {
				        primaryKey
				        type
				    }
				}
				""",
				pk1, pk2
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue());
	}

	private void executeListByAttribute(
		@Nonnull GraphQLTester tester,
		@Nonnull ThreadLocalRandom random,
		@Nonnull String[] codes
	) {
		final String code = codes[random.nextInt(codes.length)];

		tester.test(TEST_CATALOG)
			.document(
				"""
				query {
				    listProduct(
				        filterBy: {
				            attributeCodeInSet: ["%s"]
				        }
				    ) {
				        primaryKey
				        attributes {
				            code
				        }
				    }
				}
				""",
				code
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue());
	}

	private void executeListWithLocale(
		@Nonnull GraphQLTester tester,
		@Nonnull ThreadLocalRandom random,
		@Nonnull String[] codes
	) {
		final String code = codes[random.nextInt(codes.length)];

		tester.test(TEST_CATALOG)
			.document(
				"""
				query {
				    listProduct(
				        filterBy: {
				            attributeCodeInSet: ["%s"],
				            entityLocaleEquals: en
				        }
				    ) {
				        primaryKey
				        locales
				        allLocales
				        attributes {
				            code
				            name
				        }
				    }
				}
				""",
				code
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue());
	}

	private void executeListWithPrice(
		@Nonnull GraphQLTester tester,
		@Nonnull ThreadLocalRandom random,
		@Nonnull int[] productPks
	) {
		final int pk = productPks[random.nextInt(productPks.length)];

		tester.test(TEST_CATALOG)
			.document(
				"""
				query {
				    listProduct(
				        filterBy: {
				            entityPrimaryKeyInSet: [%d],
				            priceInCurrency: CZK,
				            priceInPriceLists: "basic"
				        }
				    ) {
				        primaryKey
				        priceForSale {
				            currency
				            priceWithTax
				        }
				    }
				}
				""",
				pk
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue());
	}

	private void executeListWithReferences(
		@Nonnull GraphQLTester tester,
		@Nonnull ThreadLocalRandom random,
		@Nonnull int[] productPks
	) {
		final int pk = productPks[random.nextInt(productPks.length)];

		tester.test(TEST_CATALOG)
			.document(
				"""
				query {
				    listProduct(
				        filterBy: {
				            entityPrimaryKeyInSet: [%d]
				        }
				    ) {
				        primaryKey
				        store {
				            referencedEntity {
				                primaryKey
				            }
				        }
				    }
				}
				""",
				pk
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue());
	}

	private void executeListWithOrdering(@Nonnull GraphQLTester tester) {
		tester.test(TEST_CATALOG)
			.document(
				"""
				query {
				    listProduct(
				        filterBy: {
				            attributePriorityLessThan: "35000"
				        }
				        orderBy: {
				            attributeCreatedNatural: DESC
				        }
				        limit: 10
				    ) {
				        primaryKey
				    }
				}
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue());
	}

	private void executeListCategory(@Nonnull GraphQLTester tester, @Nonnull ThreadLocalRandom random) {
		final int pk = random.nextInt(1, 20);

		tester.test(TEST_CATALOG)
			.document(
				"""
				query {
				    listCategory(
				        filterBy: {
				            entityPrimaryKeyInSet: [%d]
				        }
				    ) {
				        primaryKey
				        parentPrimaryKey
				    }
				}
				""",
				pk
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue());
	}
}
