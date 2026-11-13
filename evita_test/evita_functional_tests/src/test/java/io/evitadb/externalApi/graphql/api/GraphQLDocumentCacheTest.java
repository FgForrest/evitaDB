/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.graphql.api;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.execution.preparsed.NoOpPreparsedDocumentProvider;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import graphql.language.AstPrinter;
import graphql.language.Document;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.validation.ValidationError;
import graphql.validation.ValidationErrorType;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.system.SystemGraphQLBuilder;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.io.GraphQLInstanceType;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent;
import io.evitadb.externalApi.graphql.metric.event.request.RequestMetricInstrumentation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link GraphQLDocumentCache} - caching of valid documents only, its memory bound, its behaviour under
 * concurrent cold start, its wiring into {@link GraphQL} instances and the metrics of requests served from it.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@DisplayName("GraphQL parsed document cache - provider, wiring and metrics")
@Tag(GRAPHQL)
@Tag(CACHE)
class GraphQLDocumentCacheTest {

	private static final String SDL = "type Query { hello(name: String): String }";

	/**
	 * Creates an execution input for the given query text.
	 */
	@Nonnull
	private static ExecutionInput input(@Nonnull String query) {
		return ExecutionInput.newExecutionInput(query).build();
	}

	/**
	 * Creates a parse function that parses the query (without validation) and counts its invocations.
	 */
	@Nonnull
	private static Function<ExecutionInput, PreparsedDocumentEntry> countingParser(@Nonnull AtomicInteger counter) {
		return executionInput -> {
			counter.incrementAndGet();
			return new PreparsedDocumentEntry(Parser.parse(executionInput.getQuery()));
		};
	}

	/**
	 * Builds a minimal schema used for the end-to-end scenarios.
	 */
	@Nonnull
	private static GraphQLSchema createSchema() {
		return new SchemaGenerator().makeExecutableSchema(
			new SchemaParser().parse(SDL),
			RuntimeWiring.newRuntimeWiring()
				.type("Query", builder -> builder.dataFetcher("hello", env -> "hello " + env.getArgument("name")))
				.build()
		);
	}

	/**
	 * Creates GraphQL options with the given document cache budget.
	 */
	@Nonnull
	private static GraphQLOptions optionsWithDocumentCache(long documentCacheSizeInBytes) {
		return new GraphQLOptions(
			null, ":5555", null, null, null, null, null, documentCacheSizeInBytes, null
		);
	}

	@Nested
	@DisplayName("Provider")
	class ProviderTest {

		@Test
		@DisplayName("serves a repeated valid document from cache")
		void shouldServeRepeatedValidDocumentFromCache() {
			final GraphQLDocumentCache cache = new GraphQLDocumentCache(1024 * 1024);
			final AtomicInteger parsed = new AtomicInteger();

			final PreparsedDocumentEntry first = cache.getDocumentAsync(input("{ hello }"), countingParser(parsed))
				.join();
			final PreparsedDocumentEntry second = cache.getDocumentAsync(input("{ hello }"), countingParser(parsed))
				.join();

			assertEquals(1, parsed.get());
			assertSame(first, second);
		}

		@Test
		@DisplayName("never caches an entry with errors")
		void shouldNotCacheEntryWithErrors() {
			final GraphQLDocumentCache cache = new GraphQLDocumentCache(1024 * 1024);
			final AtomicInteger computed = new AtomicInteger();
			final Function<ExecutionInput, PreparsedDocumentEntry> failingValidation = executionInput -> {
				computed.incrementAndGet();
				return new PreparsedDocumentEntry(
					Parser.parse(executionInput.getQuery()),
					List.of(ValidationError.newValidationError()
						.validationErrorType(ValidationErrorType.FieldUndefined)
						.description("unknown field")
						.build())
				);
			};

			for (int i = 0; i < 3; i++) {
				final PreparsedDocumentEntry entry = cache.getDocumentAsync(input("{ unknown }"), failingValidation)
					.join();
				assertTrue(entry.hasErrors());
			}
			assertEquals(3, computed.get());
		}

		@Test
		@DisplayName("keeps the estimated weight of cached documents within the budget")
		void shouldKeepCachedDocumentsWithinBudget() {
			final int retainedDocuments = 10;
			final String queryPrefix = "{ hello(name: \"";
			final int queryLength = (queryPrefix + "000\") }").length();
			final GraphQLDocumentCache cache = new GraphQLDocumentCache(
				(long) retainedDocuments * GraphQLDocumentCache.estimateWeight("x".repeat(queryLength))
			);
			final AtomicInteger parsed = new AtomicInteger();

			// documents fitting the budget are all retained
			final int fittingCount = retainedDocuments / 2;
			for (int round = 0; round < 2; round++) {
				parsed.set(0);
				for (int i = 0; i < fittingCount; i++) {
					cache.getDocumentAsync(input(queryPrefix + "%03d\") }".formatted(i)), countingParser(parsed))
						.join();
				}
				assertEquals(round == 0 ? fittingCount : 0, parsed.get());
			}

			final int documentCount = 100;
			for (int i = 0; i < documentCount; i++) {
				cache.getDocumentAsync(input(queryPrefix + "%03d\") }".formatted(i)), countingParser(parsed)).join();
			}

			// at most `retainedDocuments` fit the budget - every other document must be parsed again
			parsed.set(0);
			for (int i = 0; i < documentCount; i++) {
				cache.getDocumentAsync(input(queryPrefix + "%03d\") }".formatted(i)), countingParser(parsed)).join();
			}
			assertTrue(
				parsed.get() >= documentCount - retainedDocuments,
				"Expected at least " + (documentCount - retainedDocuments) + " documents to be parsed again, " +
					"but only " + parsed.get() + " were."
			);
		}

		@Test
		@DisplayName("returns correct documents under concurrent cold start")
		void shouldReturnCorrectDocumentsUnderConcurrentColdStart() throws Exception {
			final GraphQLDocumentCache cache = new GraphQLDocumentCache(1024 * 1024);
			final int threadCount = 16;
			final int requestsPerThread = 200;
			final int hotQueries = 4;
			final AtomicInteger parsed = new AtomicInteger();
			final CountDownLatch start = new CountDownLatch(1);
			final ExecutorService executor = Executors.newFixedThreadPool(threadCount, runnable -> {
				final Thread thread = new Thread(runnable);
				thread.setDaemon(true);
				return thread;
			});
			try {
				final List<Future<Integer>> results = new ArrayList<>(threadCount);
				for (int t = 0; t < threadCount; t++) {
					final int threadIndex = t;
					results.add(executor.submit(() -> {
						start.await();
						int mismatches = 0;
						for (int i = 0; i < requestsPerThread; i++) {
							// mix of shared hot queries and queries unique to this thread
							final String name = i % 2 == 0
								? "hot" + (i / 2 % hotQueries)
								: "t" + threadIndex + "_" + i;
							final String query = "{ hello(name: \"" + name + "\") }";
							final Document document = cache.getDocumentAsync(input(query), countingParser(parsed))
								.join()
								.getDocument();
							// the document must be the one parsed from this very query text
							if (!AstPrinter.printAstCompact(document).contains("\"" + name + "\"")) {
								mismatches++;
							}
						}
						return mismatches;
					}));
				}
				start.countDown();
				for (Future<Integer> result : results) {
					assertEquals(0, result.get(30, TimeUnit.SECONDS));
				}
			} finally {
				executor.shutdownNow();
			}

			// once settled, the hot queries are served from cache
			parsed.set(0);
			for (int i = 0; i < hotQueries; i++) {
				cache.getDocumentAsync(input("{ hello(name: \"hot" + i + "\") }"), countingParser(parsed)).join();
			}
			assertEquals(0, parsed.get());
		}

		@Test
		@DisplayName("rejects non-positive budget")
		void shouldRejectNonPositiveBudget() {
			assertThrows(GenericEvitaInternalError.class, () -> new GraphQLDocumentCache(0));
		}
	}

	@Nested
	@DisplayName("Configuration and wiring")
	class WiringTest {

		@Test
		@DisplayName("attaches the cache to a built GraphQL instance by default")
		void shouldAttachCacheByDefault() {
			final GraphQL graphQL = new SystemGraphQLBuilder(createSchema()).build(new GraphQLOptions());
			assertInstanceOf(GraphQLDocumentCache.class, graphQL.getPreparsedDocumentProvider());
		}

		@Test
		@DisplayName("uses default budget when not configured")
		void shouldUseDefaultBudgetWhenNotConfigured() {
			assertEquals(
				GraphQLOptions.DEFAULT_DOCUMENT_CACHE_SIZE_IN_BYTES,
				new GraphQLOptions(null, ":5555", null, null, null, null, null, null, null)
					.getDocumentCacheSizeInBytes()
			);
		}

		@Test
		@DisplayName("does not attach any cache when disabled")
		void shouldNotAttachCacheWhenDisabled() {
			final GraphQL graphQL = new SystemGraphQLBuilder(createSchema()).build(optionsWithDocumentCache(0));
			assertSame(NoOpPreparsedDocumentProvider.INSTANCE, graphQL.getPreparsedDocumentProvider());
		}

		@Test
		@DisplayName("rejects negative budget")
		void shouldRejectNegativeBudget() {
			assertThrows(EvitaInvalidUsageException.class, () -> optionsWithDocumentCache(-1));
		}
	}

	@Nested
	@DisplayName("Request metrics")
	@Tag(OBSERVABILITY)
	class MetricsTest {

		/**
		 * Executes the query against the instance with a fresh metric event in the GraphQL context, the same way
		 * the HTTP and WebSocket handlers do.
		 */
		@Nonnull
		private static ExecutedEvent execute(@Nonnull GraphQL graphQL, @Nonnull String query) {
			final ExecutedEvent event = new ExecutedEvent(GraphQLInstanceType.DATA);
			event.finishInputDeserialization();
			final ExecutionInput executionInput = ExecutionInput.newExecutionInput(query)
				.graphQLContext(Map.of(GraphQLContextKey.METRIC_EXECUTED_EVENT, event))
				.build();
			final CompletableFuture<ExecutionResult> future = graphQL.executeAsync(executionInput);
			final ExecutionResult result = future.join();
			assertTrue(result.getErrors().isEmpty(), () -> "Unexpected errors: " + result.getErrors());
			assertEquals(Map.of("hello", "hello world"), result.getData());
			event.finish();
			return event;
		}

		@Test
		@DisplayName("measures parse and validation on miss and skips them on hit")
		void shouldMeasureParseOnMissAndSkipItOnHit() {
			final GraphQL graphQL = GraphQL.newGraphQL(createSchema())
				.instrumentation(new RequestMetricInstrumentation("testCatalog"))
				.preparsedDocumentProvider(new GraphQLDocumentCache(1024 * 1024))
				.build();
			final String query = "query metricsProbe { hello(name: \"world\") }";

			final ExecutedEvent miss = execute(graphQL, query);
			assertFalse(miss.isDocumentCached());
			assertTrue(miss.getParseStarted() > 0);
			assertEquals("metricsProbe", miss.getOperationName());

			// before the fix the hit failed in `finishValidation` because parse and validation never started
			final ExecutedEvent hit = execute(graphQL, query);
			assertTrue(hit.isDocumentCached());
			assertEquals(0L, hit.getParseStarted());
			assertEquals(0L, hit.getParseDurationMilliseconds());
			assertEquals(0L, hit.getValidationDurationMilliseconds());
			assertTrue(hit.getOperationExecutionStarted() > 0);
			assertEquals("metricsProbe", hit.getOperationName());
			assertEquals("QUERY", hit.getGraphQLOperationType());
			assertEquals("testCatalog", hit.getCatalogName());
		}

		@Test
		@DisplayName("refuses to skip parse and validation once parsing started")
		void shouldRefuseToSkipParseAndValidationOnceParsingStarted() {
			final ExecutedEvent event = new ExecutedEvent(GraphQLInstanceType.DATA);
			event.finishInputDeserialization();
			event.finishPreparation();
			assertThrows(GraphQLInternalError.class, event::skipParseAndValidation);
		}
	}
}
