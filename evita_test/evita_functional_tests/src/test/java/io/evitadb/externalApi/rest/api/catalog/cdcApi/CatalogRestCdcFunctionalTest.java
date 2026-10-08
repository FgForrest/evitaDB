/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.rest.api.catalog.cdcApi;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.ExternalApiWebSocketFunctionTestsSupport;
import io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.ChangeSystemCaptureDescriptor;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.api.catalog.cdcApi.model.CatalogCdcApiRootDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.RestEndpointFunctionalTest;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.RestTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.UUID;
import org.junit.jupiter.api.Tag;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.CDC;

/**
 * Tests for REST CDC API/
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(CDC)
public class CatalogRestCdcFunctionalTest extends RestEndpointFunctionalTest
	implements ExternalApiFunctionTestsSupport, ExternalApiWebSocketFunctionTestsSupport {

	private static final String REST_EMPTY_SYSTEM_FOR_CATALOG_API = "RESTEmptySystemForCatalogApi";

	private static final String CATALOG_CHANGE_CAPTURE_URL_PATH = "/" + CatalogCdcApiRootDescriptor.CHANGE_CATALOG_CAPTURE.urlPathItem();

	private static final String CATALOG_CHANGE_CAPTURE_PATH = "payload.data";

	@Override
	@DataSet(value = REST_EMPTY_SYSTEM_FOR_CATALOG_API, openWebApi = RestProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita, EvitaServer evitaServer) {
		return new DataCarrier();
	}

	@Test
	@UseDataSet(REST_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should test basic subprotocol operations")
	void shouldTestBasicSubprotocolOperations(RestTester tester) {
		tester.testWebSocket(
			TEST_CATALOG,
			CATALOG_CHANGE_CAPTURE_URL_PATH,
			ctx -> {
				ctx.writer().write(createPingMessage());
				ctx.writer().write(createConnectionInitMessage());
			},
			2, receivedEvents -> {
				assertThatJson(receivedEvents.get(0)).node("type").isEqualTo("pong");
				assertConnectionAckEvent(receivedEvents.get(1));
			}
		);
	}

	@Test
	@UseDataSet(REST_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive catalog capture without body")
	void shouldReceiveCatalogCaptureWithoutBody(Evita evita, RestTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;
		final String newEntityType = "myEntityType";

		// prepare data
		evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
		evita.updateCatalog(newCatalogName, EvitaSessionContract::goLiveAndClose);

		tester.testWebSocket(
			newCatalogName,
			CATALOG_CHANGE_CAPTURE_URL_PATH,
			ctx -> {
				final long startVersion = getStartVersionForEvitaCDC(evita, newCatalogName);

				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(
					subscriptionId,
					"{ \"sinceVersion\": \"" + startVersion + "\" }"
				));

				// wait for connection_ack before triggering the data change — gives the server
				// time to finish registering the CDC subscription so the upsert is not raced
				ctx.awaitEvents(1);

				// apply operation to trigger a new event
				evita.updateCatalog(
					newCatalogName,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath(CATALOG_CHANGE_CAPTURE_PATH, ChangeSystemCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.TRANSACTION);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.node(resultPath(CATALOG_CHANGE_CAPTURE_PATH, ChangeSystemCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(REST_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive catalog capture with body")
	void shouldReceiveCatalogCaptureWithBody(Evita evita, RestTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;
		final String newEntityType = "myEntityType";

		// prepare data
		evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
		evita.updateCatalog(newCatalogName, EvitaSessionContract::goLiveAndClose);

		tester.testWebSocket(
			newCatalogName,
			CATALOG_CHANGE_CAPTURE_URL_PATH,
			ctx -> {
				final long startVersion = getStartVersionForEvitaCDC(evita, newCatalogName);

				// apply operation to trigger a new event
				evita.updateCatalog(
					newCatalogName,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(
					subscriptionId,
					"{ \"sinceVersion\": \"" + startVersion + "\", \"content\": \"BODY\" }"
				));
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(CATALOG_CHANGE_CAPTURE_PATH, ChangeSystemCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.TRANSACTION),
						it -> it.node(resultPath(CATALOG_CHANGE_CAPTURE_PATH, ChangeSystemCaptureDescriptor.BODY))
							.isNotNull()
					);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.and(
						it -> it.node(resultPath(CATALOG_CHANGE_CAPTURE_PATH, ChangeSystemCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath(CATALOG_CHANGE_CAPTURE_PATH, ChangeSystemCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo("CreateEntitySchemaMutation")
					);
			}
		);
	}

	@Test
	@UseDataSet(REST_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive catalog capture filtered by criteria")
	void shouldReceiveCatalogCaptureFilteredByCriteria(Evita evita, RestTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;
		final String newEntityType = "myEntityType";

		// prepare data
		evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
		evita.updateCatalog(newCatalogName, EvitaSessionContract::goLiveAndClose);

		tester.testWebSocket(
			newCatalogName,
			CATALOG_CHANGE_CAPTURE_URL_PATH,
			ctx -> {
				final long startVersion = getStartVersionForEvitaCDC(evita, newCatalogName);

				// apply operation to trigger a new event
				evita.updateCatalog(
					newCatalogName,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(
					subscriptionId,
					"{ " +
						"\"sinceVersion\": \"" + startVersion + "\", " +
						"\"criteria\": [{ \"area\": \"SCHEMA\", \"site\": { \"type\": \"SCHEMA\", \"containerType\": [\"ENTITY\"] } }], " +
						"\"content\": \"BODY\" " +
						"}"
				));
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(CATALOG_CHANGE_CAPTURE_PATH, ChangeSystemCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath(CATALOG_CHANGE_CAPTURE_PATH, ChangeSystemCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo("CreateEntitySchemaMutation")
					);
			}
		);
	}

	@Test
	@UseDataSet(REST_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should accept a resume position of the current catalog incarnation and stamp captures with it")
	void shouldAcceptCurrentCatalogIdAndReturnItInCaptures(Evita evita, RestTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;
		final String newEntityType = "myEntityType";

		// prepare data
		evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
		evita.updateCatalog(newCatalogName, EvitaSessionContract::goLiveAndClose);
		final UUID catalogId = getCatalogIdForCatalogCDC(evita, newCatalogName);

		tester.testWebSocket(
			newCatalogName,
			CATALOG_CHANGE_CAPTURE_URL_PATH,
			ctx -> {
				final long startVersion = getStartVersionForEvitaCDC(evita, newCatalogName);

				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(
					subscriptionId,
					"{ " +
						"\"catalogId\": \"" + catalogId + "\", " +
						"\"sinceVersion\": \"" + startVersion + "\", " +
						"\"criteria\": [{ \"area\": \"DATA\", \"site\": { \"type\": \"DATA\", \"containerType\": [\"ENTITY\"] } }] " +
						"}"
				));

				// wait for connection_ack before triggering the data change — gives the server
				// time to finish registering the CDC subscription so the upsert is not raced
				ctx.awaitEvents(1);

				// apply operation to trigger a new event
				evita.updateCatalog(
					newCatalogName,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
						session.createNewEntity(newEntityType, 1).upsertVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(CATALOG_CHANGE_CAPTURE_PATH, ChangeCatalogCaptureDescriptor.CATALOG_ID))
							.isString()
							.isEqualTo(catalogId.toString()),
						it -> it.node(resultPath(CATALOG_CHANGE_CAPTURE_PATH, ChangeCatalogCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath(CATALOG_CHANGE_CAPTURE_PATH, ChangeCatalogCaptureDescriptor.ENTITY_TYPE))
							.isString()
							.isEqualTo(newEntityType),
						it -> it.node(resultPath(CATALOG_CHANGE_CAPTURE_PATH, ChangeCatalogCaptureDescriptor.ENTITY_PRIMARY_KEY))
							.isEqualTo(1)
					);
			}
		);
	}

	@Test
	@UseDataSet(REST_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should refuse a resume position recorded on a different catalog incarnation")
	void shouldRefuseResumePositionOfDifferentCatalogIncarnation(Evita evita, RestTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;

		// prepare data
		evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
		evita.updateCatalog(newCatalogName, EvitaSessionContract::goLiveAndClose);

		assertSubscriptionRefused(
			evita, tester, newCatalogName, subscriptionId,
			"{ " +
				"\"catalogId\": \"" + UUID.randomUUID() + "\", " +
				"\"sinceVersion\": \"" + getStartVersionForEvitaCDC(evita, newCatalogName) + "\" " +
				"}",
			"was recorded on a different incarnation of the catalog"
		);
	}

	@Test
	@UseDataSet(REST_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should refuse a resume position that lies ahead of the catalog")
	void shouldRefuseResumePositionAheadOfCatalog(Evita evita, RestTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;

		// prepare data
		evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
		evita.updateCatalog(newCatalogName, EvitaSessionContract::goLiveAndClose);

		assertSubscriptionRefused(
			evita, tester, newCatalogName, subscriptionId,
			"{ \"sinceVersion\": \"" + (getStartVersionForEvitaCDC(evita, newCatalogName) + 1) + "\" }",
			"lies ahead of catalog"
		);
	}

	/**
	 * Opens a CDC subscription that is expected to be refused, commits a change afterwards and asserts that the
	 * subscription ends with a single error event carrying the expected message and that no capture follows it.
	 */
	private void assertSubscriptionRefused(
		@Nonnull Evita evita,
		@Nonnull RestTester tester,
		@Nonnull String catalogName,
		@Nonnull String subscriptionId,
		@Nonnull String subscriptionPayload,
		@Nonnull String expectedMessageFragment
	) {
		tester.testWebSocket(
			catalogName,
			CATALOG_CHANGE_CAPTURE_URL_PATH,
			ctx -> {
				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(subscriptionId, subscriptionPayload));
				ctx.awaitEvents(1);

				// a change that an accepted subscription would deliver
				evita.updateCatalog(
					catalogName,
					session -> {
						session.defineEntitySchema("refusedSubscriptionProbe").updateVia(session);
					}
				);
				ctx.tryAwaitEvents(3, REFUSED_SUBSCRIPTION_QUIET_WINDOW);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertErrorEvent(receivedEvents.get(1), subscriptionId)
					.node("payload.error.message")
					.isString()
					.contains(expectedMessageFragment);
				assertEquals(2, receivedEvents.size(), "No event may follow the refusal: " + receivedEvents);
			}
		);
	}

	@Nonnull
	private static String createSubscriptionQueryMessage(@Nonnull String subscriptionId, @Nonnull String payload) {
		return "{\"id\":\"" + subscriptionId + "\",\"type\":\"subscribe\",\"payload\":" + payload + "}";
	}

}
