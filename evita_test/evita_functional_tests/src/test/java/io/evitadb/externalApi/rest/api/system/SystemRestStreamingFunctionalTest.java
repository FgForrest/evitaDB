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

package io.evitadb.externalApi.rest.api.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.ExternalApiWebSocketFunctionTestsSupport;
import io.evitadb.externalApi.api.model.cdc.ChangeCaptureDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.CatalogInstalledIntoLiveViewDescriptor;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.api.system.model.SystemRootDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.RestEndpointFunctionalTest;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.RestTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REST;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for REST WebSocket streaming endpoints.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(QUERY)
public class SystemRestStreamingFunctionalTest extends RestEndpointFunctionalTest implements
	ExternalApiFunctionTestsSupport,
	ExternalApiWebSocketFunctionTestsSupport {

	private static final String SYSTEM_URL = "system";
	private static final String REST_EMPTY_SYSTEM_FOR_SYSTEM_API = "RESTEmptySystemForSystemApi";
	private static final String SYSTEM_CHANGE_CAPTURE_URL_PATH = "/" + SystemRootDescriptor.CHANGE_SYSTEM_CAPTURE.urlPathItem();

	private static final String SYSTEM_CHANGE_CAPTURE_PATH = "payload.data";

	@Override
	@DataSet(value = REST_EMPTY_SYSTEM_FOR_SYSTEM_API, openWebApi = RestProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita, EvitaServer evitaServer) {
		return new DataCarrier();
	}

	/**
	 * Verifies that a REST websocket subscription that opts into BOTH the `ENGINE` and
	 * `HOST` system-capture areas via the `criteria` field on
	 * {@link io.evitadb.externalApi.rest.api.system.dto.ChangeSystemCaptureRequestDto} receives
	 * a polymorphic body envelope carrying a `CatalogInstalledIntoLiveView` host event when a
	 * catalog is created and transitioned to the live state on the server.
	 *
	 * Companion coverage:
	 * - gRPC: `EvitaClientReadWriteTest#shouldNotifyHostEventsOverGrpcWithInfrastructureCriteria`
	 * - engine: `SystemChangeObserverTest#shouldDeliverHostEventsOnlyToInfrastructureSubscribers`
	 */
	@Test
	@UseDataSet(REST_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should deliver host event when subscribed with infrastructure criteria")
	void shouldDeliverHostEventWhenSubscribedWithInfrastructureCriteria(
		@Nonnull Evita evita,
		@Nonnull RestTester tester
	) {
		final String subscriptionId = createSubscriptionId();
		final String hostEventCatalog = "hostEventCatalog" + subscriptionId;

		tester.testWebSocket(
			SYSTEM_URL,
			SYSTEM_CHANGE_CAPTURE_URL_PATH,
			writer -> {
				final long startVersion = evita.getEngineState().version() + 1;

				// open subscription that opts into both ENGINE and HOST areas
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"{ " +
						"\"sinceVersion\": \"" + startVersion + "\", " +
						"\"criteria\": [" +
							"{ \"area\": \"ENGINE\" }, " +
							"{ \"area\": \"HOST\" }" +
						"], " +
						"\"content\": \"BODY\" " +
						"}"
				));
				wait(2000);

				// trigger a catalog state transition that emits a host event when the catalog
				// settles into the ALIVE state on this host
				evita.defineCatalog(hostEventCatalog);
				evita.updateCatalog(hostEventCatalog, EvitaSessionContract::goLiveAndClose);
			},
			6, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertHostEventPresent(receivedEvents, hostEventCatalog);
			}
		);
	}

	/**
	 * Asserts that AT LEAST ONE of the received subscription events carries a polymorphic body
	 * with the `CatalogInstalledIntoLiveView` discriminator and the expected catalog name.
	 *
	 * The first event is always the connection-acknowledgement frame and is skipped.
	 */
	private void assertHostEventPresent(
		@Nonnull java.util.List<String> receivedEvents,
		@Nonnull String expectedCatalogName
	) {
		final ObjectMapper mapper = new ObjectMapper();
		for (int i = 1; i < receivedEvents.size(); i++) {
			final String event = receivedEvents.get(i);
			final JsonNode root;
			try {
				root = mapper.readTree(event);
			} catch (Exception ex) {
				fail("Received event is not a valid JSON document: " + event, ex);
				return;
			}
			final JsonNode body = root
				.path("payload")
				.path("data")
				.path(ChangeCaptureDescriptor.BODY.name());
			if (body.isMissingNode() || body.isNull()) {
				continue;
			}
			final JsonNode typeNode = body.path("type");
			if (typeNode.isMissingNode() || typeNode.isNull()) {
				continue;
			}
			if (!CatalogInstalledIntoLiveViewDescriptor.THIS.name().equals(typeNode.asText())) {
				continue;
			}
			// once we found the discriminator, lock the catalog name with assertThatJson so the
			// failure message matches the project's existing JSON-assertion style
			assertThatJson(event)
				.node(resultPath(
					SYSTEM_CHANGE_CAPTURE_PATH,
					ChangeCaptureDescriptor.BODY,
					CatalogInstalledIntoLiveViewDescriptor.CATALOG_NAME
				))
				.isEqualTo(expectedCatalogName);
			assertThatJson(event)
				.node(resultPath(SYSTEM_CHANGE_CAPTURE_PATH, ChangeCaptureDescriptor.OPERATION))
				.isEqualTo(Operation.UPSERT);
			return;
		}
		fail(
			"Expected to receive at least one `" + CatalogInstalledIntoLiveViewDescriptor.THIS.name() +
				"` host event for catalog `" + expectedCatalogName + "`, but received: " + receivedEvents
		);
	}

	@Test
	@UseDataSet(REST_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should test basic subprotocol operations")
	void shouldTestBasicSubprotocolOperations(RestTester tester) {
		tester.testWebSocket(
			SYSTEM_URL,
			SYSTEM_CHANGE_CAPTURE_URL_PATH,
			writer -> {
				writer.write(createPingMessage());
				writer.write(createConnectionInitMessage());
			},
			2, receivedEvents -> {
				assertThatJson(receivedEvents.get(0)).node("type").isEqualTo("pong");
				assertConnectionAckEvent(receivedEvents.get(1));
			}
		);
	}

	@Test
	@UseDataSet(REST_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive system capture without body")
	void shouldReceiveSystemCaptureWithoutBody(Evita evita, RestTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;

		tester.testWebSocket(
			SYSTEM_URL,
			SYSTEM_CHANGE_CAPTURE_URL_PATH,
			writer -> {
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(subscriptionId, "{}"));
				wait(2000);

				// apply operation to trigger a new event
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath(SYSTEM_CHANGE_CAPTURE_PATH, ChangeCaptureDescriptor.OPERATION))
						.isEqualTo(Operation.TRANSACTION);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.node(resultPath(SYSTEM_CHANGE_CAPTURE_PATH, ChangeCaptureDescriptor.OPERATION))
						.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(REST_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive system capture without body with entire history")
	void shouldReceiveSystemCaptureWithoutBodyWithEntireHistory(Evita evita, RestTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;

		tester.testWebSocket(
			SYSTEM_URL,
			SYSTEM_CHANGE_CAPTURE_URL_PATH,
			writer -> {
				final long startVersion = evita.getEngineState().version() + 1;

				// apply operation to trigger a new event
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();

				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"{ \"sinceVersion\": \"" + startVersion + "\" }"
				));
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath(SYSTEM_CHANGE_CAPTURE_PATH, ChangeCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.TRANSACTION);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.node(resultPath(SYSTEM_CHANGE_CAPTURE_PATH, ChangeCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(REST_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive system capture with body")
	void shouldReceiveSystemCaptureWithBody(Evita evita, RestTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;

		tester.testWebSocket(
			SYSTEM_URL,
			SYSTEM_CHANGE_CAPTURE_URL_PATH,
			writer -> {
				final long startVersion = evita.getEngineState().version() + 1;

				// apply operation to trigger a new event
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();

				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"{ \"sinceVersion\": \"" + startVersion + "\", \"content\": \"BODY\" }"
				));
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(SYSTEM_CHANGE_CAPTURE_PATH, ChangeCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.TRANSACTION),
						it -> it.node(resultPath(SYSTEM_CHANGE_CAPTURE_PATH, ChangeCaptureDescriptor.BODY))
							.isNotNull()
					);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.and(
						it -> it.node(resultPath(SYSTEM_CHANGE_CAPTURE_PATH, ChangeCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath(SYSTEM_CHANGE_CAPTURE_PATH, ChangeCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo("CreateCatalogSchemaMutation")
					);
			}
		);
	}

	@Nonnull
	private static String createSubscriptionQueryMessage(@Nonnull String subscriptionId, @Nonnull String payload) {
		return "{\"id\":\"" + subscriptionId + "\",\"type\":\"subscribe\",\"payload\":" + payload + "}";
	}
}
