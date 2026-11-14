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

package io.evitadb.externalApi.graphql.api.catalog.dataApi;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.Scalars;
import graphql.execution.preparsed.NoOpPreparsedDocumentProvider;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.ExternalApiWebSocketFunctionTestsSupport;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.api.GraphQLDocumentCache;
import io.evitadb.externalApi.graphql.api.catalog.CatalogGraphQLBuilder;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLEndpointFunctionalTest;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.ENTITY_EMPTY;
import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.QUERY;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that repeated GraphQL requests served from {@link GraphQLDocumentCache} behave exactly like the first,
 * freshly parsed and validated one - over HTTP and WebSocket, for valid and invalid documents, and across a schema
 * change that rebuilds the GraphQL instance (and thus discards its cache).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@DisplayName("GraphQL parsed document cache - catalog API end to end")
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(CACHE)
public class CatalogGraphQLDocumentCacheFunctionalTest
	extends GraphQLEndpointFunctionalTest
	implements ExternalApiWebSocketFunctionTestsSupport {

	private static final String GRAPHQL_PRODUCTS_FOR_DOCUMENT_CACHE_SCHEMA_CHANGE =
		GRAPHQL_THOUSAND_PRODUCTS + "forDocumentCacheSchemaChange";
	private static final String PRODUCT_LIST_PATH = "data.listProduct";
	private static final String PROBE_ATTRIBUTE = "documentCacheProbe";
	private static final int REPETITIONS = 3;

	/**
	 * Separate writable data set for the schema change scenario so that the shared read-only one is not modified.
	 */
	@DataSet(
		value = GRAPHQL_PRODUCTS_FOR_DOCUMENT_CACHE_SCHEMA_CHANGE,
		openWebApi = GraphQLProvider.CODE,
		readOnly = false,
		destroyAfterClass = true
	)
	protected DataCarrier setUpForSchemaChange(Evita evita) {
		return super.setUpData(evita, 10, false);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return identical response for repeatedly sent query")
	void shouldReturnIdenticalResponseForRepeatedQuery(
		GraphQLTester tester,
		List<SealedEntity> originalProductEntities
	) {
		final int primaryKey = originalProductEntities.get(0).getPrimaryKeyOrThrowException();
		// the query text is unique to this test, so the first request is a guaranteed miss and the others hits
		for (int i = 0; i < REPETITIONS; i++) {
			tester.test(TEST_CATALOG)
				.document(
					"""
					query repeatedQueryForDocumentCache {
						listProduct(filterBy: { entityPrimaryKeyInSet: [%d] }) {
							primaryKey
						}
					}
					""",
					primaryKey
				)
				.executeAndThen()
				.statusCode(200)
				.body(ERRORS_PATH, nullValue())
				.body(PRODUCT_LIST_PATH + ".primaryKey", contains(primaryKey));
		}
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should apply variables of each request to a cached document")
	void shouldApplyVariablesOfEachRequestToCachedDocument(
		GraphQLTester tester,
		List<SealedEntity> originalProductEntities
	) {
		for (int i = 0; i < REPETITIONS; i++) {
			final int primaryKey = originalProductEntities.get(i).getPrimaryKeyOrThrowException();
			tester.test(TEST_CATALOG)
				.document(
					"""
					query variablesForDocumentCache($pks: [Int!]!) {
						listProduct(filterBy: { entityPrimaryKeyInSet: $pks }) {
							primaryKey
						}
					}
					"""
				)
				.variable("pks", List.of(primaryKey))
				.executeAndThen()
				.statusCode(200)
				.body(ERRORS_PATH, nullValue())
				.body(PRODUCT_LIST_PATH + ".primaryKey", contains(primaryKey));
		}
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should report the same syntax error for repeatedly sent invalid query")
	void shouldReportSameSyntaxErrorForRepeatedInvalidQuery(GraphQLTester tester) {
		assertSameErrorsOnEveryRepetition(
			tester,
			"""
			query syntaxErrorForDocumentCache {
				listProduct(limit: 1) {
					primaryKey
			"""
		);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should report the same validation error for repeatedly sent invalid query")
	void shouldReportSameValidationErrorForRepeatedInvalidQuery(GraphQLTester tester) {
		assertSameErrorsOnEveryRepetition(
			tester,
			"""
			query validationErrorForDocumentCache {
				listProduct(limit: 1) {
					nonExistingFieldForDocumentCache
				}
			}
			"""
		);
	}

	@Test
	@UseDataSet(GRAPHQL_PRODUCTS_FOR_DOCUMENT_CACHE_SCHEMA_CHANGE)
	@DisplayName("Should accept repeatedly sent subscription over WebSocket")
	void shouldAcceptRepeatedSubscriptionOverWebSocket(Evita evita, GraphQLTester tester) {
		// the WebSocket sub-protocol accepts subscriptions only, so the repeated document is a subscription
		final String firstId = createSubscriptionId();
		final String secondId = firstId + "1";
		final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);
		final String subscription = "onDataChange(sinceVersion: \\\"" + startVersion + "\\\") { version operation }";

		tester.testWebSocket(
			TEST_CATALOG,
			ctx -> {
				ctx.writer().write(createConnectionInitMessage());
				ctx.awaitEvents(1);
				// both subscriptions carry the identical document - the server handles the frames of one connection
				// in order and parses the first document synchronously, so the second one is served from the cache
				ctx.writer().write(createSubscriptionMessage(firstId, subscription));
				ctx.writer().write(createSubscriptionMessage(secondId, subscription));

				// a subscription may register after the first data change was committed - the data change is
				// therefore repeated until both subscriptions delivered one (the pattern of the other CDC tests)
				final List<String> seen = new CopyOnWriteArrayList<>();
				boolean delivered = false;
				for (int attempt = 0; attempt < 5 && !delivered; attempt++) {
					evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							// a data change only - a schema change would rebuild the GraphQL instance and its cache
							session.createNewEntity(ENTITY_EMPTY).upsertVia(session);
						}
					);
					delivered = ctx.tryAwaitEvents(
						events -> {
							seen.clear();
							seen.addAll(events);
							return containsEventOf(events, firstId) && containsEventOf(events, secondId);
						},
						Duration.ofSeconds(10)
					);
				}
				assertTrue(delivered, () -> "Both subscriptions are expected to deliver a data change: " + seen);
			},
			3,
			receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				for (String event : receivedEvents.subList(1, receivedEvents.size())) {
					assertThatJson(event).node("type").isEqualTo("next");
				}
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_PRODUCTS_FOR_DOCUMENT_CACHE_SCHEMA_CHANGE)
	@DisplayName("Should discard cached documents when catalog schema changes")
	void shouldDiscardCachedDocumentsWhenCatalogSchemaChanges(Evita evita, GraphQLTester tester) {
		final String query = """
			query schemaChangeForDocumentCache {
				listProduct(limit: 1) {
					attributes {
						%s
					}
				}
			}
			""".formatted(PROBE_ATTRIBUTE);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.openForWrite()
					.withAttribute(PROBE_ATTRIBUTE, String.class, whichIs -> whichIs.nullable())
					.updateVia(session);
			}
		);
		// the GraphQL instance is rebuilt asynchronously after the schema change; once the query is valid, it is
		// cached - send it more than once to be sure
		await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertNull(executeAndReadErrors(tester, query)));
		assertNull(executeAndReadErrors(tester, query));

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.openForWrite()
					.withoutAttribute(PROBE_ATTRIBUTE)
					.updateVia(session);
			}
		);
		// a stale cached document would keep the query valid forever
		await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
			final List<Object> errors = executeAndReadErrors(tester, query);
			assertNotNull(errors);
			assertTrue(errors.toString().contains("Validation error"), () -> "Expected validation error: " + errors);
		});
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should attach the document cache to catalog GraphQL instances")
	void shouldAttachDocumentCacheToCatalogInstances(Evita evita) {
		final GraphQLSchema schema = GraphQLSchema.newSchema()
			.query(GraphQLObjectType.newObject()
				.name("Query")
				.field(field -> field.name("probe").type(Scalars.GraphQLString)))
			.build();
		final CatalogGraphQLBuilder builder = new CatalogGraphQLBuilder(
			evita, evita.getCatalogInstanceOrThrowException(TEST_CATALOG), schema, new ObjectMapper()
		);

		assertInstanceOf(GraphQLDocumentCache.class, builder.build(new GraphQLOptions()).getPreparsedDocumentProvider());
		assertSame(
			NoOpPreparsedDocumentProvider.INSTANCE,
			builder.build(new GraphQLOptions(null, ":5555", null, null, null, null, null, 0L, null))
				.getPreparsedDocumentProvider()
		);
	}

	/**
	 * Returns true if any of the received WebSocket events belongs to the subscription with the given id.
	 */
	private static boolean containsEventOf(@Nonnull List<String> events, @Nonnull String subscriptionId) {
		for (String event : events) {
			if (event.contains("\"id\":\"" + subscriptionId + "\"")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Sends the same invalid document repeatedly and asserts that every response carries exactly the same errors.
	 */
	private static void assertSameErrorsOnEveryRepetition(@Nonnull GraphQLTester tester, @Nonnull String document) {
		final List<Object> firstErrors = executeAndReadErrors(tester, document);
		assertNotNull(firstErrors, "The document is expected to be invalid.");
		assertThat(firstErrors, hasSize(1));
		for (int i = 1; i < REPETITIONS; i++) {
			assertThat(executeAndReadErrors(tester, document), equalTo(firstErrors));
		}
	}

	/**
	 * Executes the document against the catalog data API and returns the `errors` of the response or `null` if there
	 * were none.
	 */
	@Nullable
	private static List<Object> executeAndReadErrors(@Nonnull GraphQLTester tester, @Nonnull String document) {
		final JsonPath response = tester.test(TEST_CATALOG)
			.document(document)
			.executeAndThen()
			.statusCode(200)
			.extract()
			.jsonPath();
		return response.getList(ERRORS_PATH);
	}

	/**
	 * Creates a `subscribe` message of the `graphql-transport-ws` protocol carrying the given subscription field.
	 */
	@Nonnull
	private static String createSubscriptionMessage(@Nonnull String id, @Nonnull String subscription) {
		return "{\"id\":\"" + id + "\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { " +
			subscription + " }\"}}";
	}
}
