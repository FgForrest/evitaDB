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
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.ExternalApiWebSocketFunctionTestsSupport;
import io.evitadb.externalApi.api.model.cdc.ChangeCaptureDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.CatalogInstalledIntoLiveViewDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.CatalogSchemaUpdatedDescriptor;
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
import java.util.List;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.SCHEMA;
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
		@Nonnull List<String> receivedEvents,
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

	/**
	 * Verifies that a REST websocket subscription opted into the `HOST` area
	 * receives a polymorphic `CatalogSchemaUpdated` envelope when a WARMING_UP catalog session
	 * actually advances the catalog schema version (entity definition + a couple of attributes)
	 * and is then closed. Coalescing semantics: exactly one event per closing session that
	 * advanced the schema version, even though the underlying mutation flow contains multiple
	 * schema-mutating engine mutations.
	 *
	 * Companion coverage:
	 * - gRPC: `ChangeCaptureConverterHostEventTest#shouldRoundTripCatalogSchemaUpdated`
	 * - engine: `SystemChangeObserverTest` host-event coverage
	 */
	@Test
	@UseDataSet(REST_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@Tag(CDC)
	@Tag(SCHEMA)
	@DisplayName("Should deliver CatalogSchemaUpdated host event when WARMING_UP session bumps schema version")
	void shouldReceiveCatalogSchemaUpdatedFromHostSubscription(
		@Nonnull Evita evita,
		@Nonnull RestTester tester
	) {
		final String subscriptionId = createSubscriptionId();
		final String schemaUpdatedCatalog = "schemaUpdatedCatalog" + subscriptionId;

		// catalog must exist BEFORE the subscription opens — host events are live-tail only and
		// the schema bump must occur while the subscriber is wired
		evita.defineCatalog(schemaUpdatedCatalog);

		tester.testWebSocket(
			SYSTEM_URL,
			SYSTEM_CHANGE_CAPTURE_URL_PATH,
			writer -> {
				final long startVersion = evita.getEngineState().version() + 1;

				// subscribe to the HOST area only — the ENGINE area would deliver the underlying
				// `ModifyEntitySchemaMutation` envelopes, whose nested array-typed mutation payload
				// is not yet exercised by the REST `ObjectJsonSerializer` and would surface as a
				// `Serialization of value of class ArrayNode is not implemented yet` error frame
				// before the host event arrives. Coalescing semantics for `CatalogSchemaUpdated`
				// are independent of the engine-mutation surface, so HOST-only is the focused
				// scope for this test.
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"{ " +
						"\"sinceVersion\": \"" + startVersion + "\", " +
						"\"criteria\": [" +
							"{ \"area\": \"HOST\" }" +
						"], " +
						"\"content\": \"BODY\" " +
						"}"
				));
				wait(2000);

				// trigger a schema-version bump on the WARMING_UP catalog: define an entity with
				// two attributes and close the session. The session-close path emits a single
				// coalesced `CatalogSchemaUpdated` host event because the schema version
				// advanced. Block-bodied lambda disambiguates between the `Function`- and
				// `Consumer`-returning `updateCatalog` overloads.
				evita.updateCatalog(
					schemaUpdatedCatalog,
					session -> {
						session.defineEntitySchema("schemaBumpEntity")
							.withAttribute("attrA", String.class, AttributeSchemaEditor::filterable)
							.withAttribute("attrB", Integer.class, AttributeSchemaEditor::sortable)
							.updateVia(session);
					}
				);
			},
			// 1 connection_ack + the coalesced `CatalogSchemaUpdated` host event itself; HOST-only
			// subscription means no engine-mutation envelopes are delivered. The harness uses `>=`
			// semantics so additional defensive events (none expected here) would not fail.
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertCatalogSchemaUpdatedEventPresent(receivedEvents, schemaUpdatedCatalog);
			}
		);
	}

	/**
	 * Asserts that AT LEAST ONE of the received subscription events carries a polymorphic body
	 * with the `CatalogSchemaUpdated` discriminator and the expected catalog name. Also verifies
	 * the coalescing payload: `newSchemaVersion >= 1` (a schema bump occurred) and
	 * `currentEngineVersion > 0` (correlation snapshot of the engine version is present).
	 *
	 * The first event is always the connection-acknowledgement frame and is skipped.
	 */
	private void assertCatalogSchemaUpdatedEventPresent(
		@Nonnull List<String> receivedEvents,
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
			if (!CatalogSchemaUpdatedDescriptor.THIS.name().equals(typeNode.asText())) {
				continue;
			}

			// catalog name: leverage assertThatJson so the failure message follows the project's
			// existing JSON-assertion style for string-valued fields
			assertThatJson(event)
				.node(resultPath(
					SYSTEM_CHANGE_CAPTURE_PATH,
					ChangeCaptureDescriptor.BODY,
					CatalogSchemaUpdatedDescriptor.CATALOG_NAME
				))
				.isEqualTo(expectedCatalogName);

			// the two numeric fields are asserted directly on the parsed `JsonNode` body — the
			// jsonunit numeric assertions on top of `node(...)` cannot be chained into a custom
			// lower-bound check without unsupported `asNumber()` calls on `BigIntegerAssert`,
			// and using the already-parsed tree is clearer and allocation-free.
			//
			// Note on numeric encoding: REST `ObjectJsonSerializer` emits `Integer` as a JSON
			// number but `Long` as a JSON STRING (for safe JS-interop with values that exceed
			// `Number.MAX_SAFE_INTEGER`). We accept both shapes for `currentEngineVersion`.
			final JsonNode newSchemaVersionNode =
				body.path(CatalogSchemaUpdatedDescriptor.NEW_SCHEMA_VERSION.name());
			if (!newSchemaVersionNode.isIntegralNumber() || newSchemaVersionNode.asInt() < 1) {
				fail(
					"Expected `" + CatalogSchemaUpdatedDescriptor.NEW_SCHEMA_VERSION.name() +
						"` to be an integer >= 1, got: " + newSchemaVersionNode
				);
			}
			final JsonNode currentEngineVersionNode =
				body.path(CatalogSchemaUpdatedDescriptor.CURRENT_ENGINE_VERSION.name());
			final long currentEngineVersion = parseLongLenient(currentEngineVersionNode);
			if (currentEngineVersion <= 0L) {
				fail(
					"Expected `" + CatalogSchemaUpdatedDescriptor.CURRENT_ENGINE_VERSION.name() +
						"` to be a positive long, got: " + currentEngineVersionNode
				);
			}
			return;
		}
		fail(
			"Expected to receive at least one `" + CatalogSchemaUpdatedDescriptor.THIS.name() +
				"` host event for catalog `" + expectedCatalogName + "`, but received: " + receivedEvents
		);
	}

	/**
	 * Parses a `long` from a JSON node that may be either an integral number or a string-encoded
	 * decimal — the REST `ObjectJsonSerializer` emits `Long` values as JSON strings to preserve
	 * precision for clients (e.g. JavaScript) that cannot safely represent the full `long` range.
	 * Returns `Long.MIN_VALUE` on any unparsable shape so callers can drive a custom failure
	 * message with the offending node.
	 */
	private static long parseLongLenient(@Nonnull JsonNode node) {
		if (node.isIntegralNumber()) {
			return node.asLong();
		}
		if (node.isTextual()) {
			try {
				return Long.parseLong(node.asText());
			} catch (NumberFormatException ignored) {
				return Long.MIN_VALUE;
			}
		}
		return Long.MIN_VALUE;
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
