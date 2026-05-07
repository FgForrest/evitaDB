/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.graphql.api.system;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.ExternalApiWebSocketFunctionTestsSupport;
import io.evitadb.externalApi.api.model.cdc.ChangeCaptureDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.CatalogInstalledIntoLiveViewDescriptor;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.QUERY;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for GraphQL catalog collections query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(QUERY)
public class SystemGraphQLSubscriptionsFunctionalTest extends SystemGraphQLEndpointFunctionalTest implements ExternalApiFunctionTestsSupport,
	ExternalApiWebSocketFunctionTestsSupport {

	private static final String ON_SYSTEM_CHANGE_PATH = "payload.data.onSystemChange";
	private static final String ON_SYSTEM_CHANGE_UNTYPED_PATH = "payload.data.onSystemChangeUntyped";
	private static final String ON_CATALOG_CHANGE_PATH = "payload.data.onCatalogChange";
	private static final String ON_CATALOG_CHANGE_UNTYPED_PATH = "payload.data.onCatalogChangeUntyped";
	public static final String GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API = "GraphQLEmptySystemForSystemApi";

	@Override
	@DataSet(value = GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita) {
		return new DataCarrier();
	}

	/**
	 * Issue #1151: verifies that a GraphQL `onSystemChange` subscriber that explicitly opts into
	 * BOTH `ENGINE` and `HOST` areas via the new `criteria` argument receives — over
	 * the same websocket — engine-mutation envelopes (`TransactionMutation`,
	 * `CreateCatalogSchemaMutation`, `MakeCatalogAliveMutation`) AND a host-local
	 * `CatalogInstalledIntoLiveView` event emitted when the freshly-created catalog settles into
	 * the `ALIVE` state on this host.
	 *
	 * The body fragment selector mixes engine-mutation fragments with the two new host-event
	 * variant fragments under the flat `ChangeSystemCaptureBodyUnion`. Per the host-event
	 * contract these envelopes are live-tail only — no replay — so the subscription must be
	 * established **before** the catalog activation is triggered.
	 *
	 * End-to-end semantics for the `HOST` area are also covered at the gRPC layer by
	 * `EvitaClientReadWriteTest#shouldNotifyHostEventsOverGrpcWithInfrastructureCriteria` and at
	 * the engine layer by `SystemChangeObserverTest`.
	 */
	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should deliver host event when subscribed with infrastructure criteria")
	void shouldDeliverHostEventWhenSubscribedWithInfrastructureCriteria(
		@Nonnull Evita evita,
		@Nonnull GraphQLTester tester
	) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "hostEventGqlCatalog" + subscriptionId;

		tester.testWebSocket(
			SYSTEM_URL,
			writer -> {
				// open the subscription FIRST — host events are live-tail only and cannot be
				// replayed, so the subscriber must be wired before the catalog state transition
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onSystemChange(criteria: [{area: ENGINE}, {area: HOST}]) " +
						"{ version index operation body { " +
						"__typename " +
						"... on TransactionMutation { mutationType } " +
						"... on CreateCatalogSchemaMutation { mutationType } " +
						"... on MakeCatalogAliveMutation { mutationType } " +
						"... on CatalogInstalledIntoLiveView { catalogName observedState } " +
						"... on CatalogRemovedFromLiveView { catalogName } " +
						"} }"
				));
				// give the server time to register the subscription before triggering events,
				// otherwise the host event may fire before the publisher is wired up
				wait(2000);

				// trigger an engine-area mutation flow + a host-area host event via
				// the catalog-create + go-live sequence
				evita.defineCatalog(newCatalogName);
				evita.updateCatalog(newCatalogName, EvitaSessionContract::goLiveAndClose);
			},
			// 1 connection_ack + at least one engine envelope + the host event itself; the
			// websocket harness uses `>=` semantics so additional mutation envelopes that arrive
			// before the host event do not cause failures
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));

				// scan all received envelopes (skipping the initial ack) for a host event
				// envelope carrying the freshly-installed catalog name; the `__typename`
				// discriminator is the canonical fragment-dispatch field for GraphQL unions
				boolean hostEventSeen = false;
				for (int i = 1; i < receivedEvents.size(); i++) {
					final String envelope = receivedEvents.get(i);
					try {
						assertThatJson(envelope).and(
							it -> it.node(resultPath(
								ON_SYSTEM_CHANGE_PATH,
								ChangeCaptureDescriptor.BODY,
								TYPENAME_FIELD
							)).isEqualTo(CatalogInstalledIntoLiveViewDescriptor.THIS.name()),
							it -> it.node(resultPath(
								ON_SYSTEM_CHANGE_PATH,
								ChangeCaptureDescriptor.BODY,
								CatalogInstalledIntoLiveViewDescriptor.CATALOG_NAME
							)).isEqualTo(newCatalogName),
							it -> it.node(resultPath(
								ON_SYSTEM_CHANGE_PATH,
								ChangeCaptureDescriptor.OPERATION
							)).isEqualTo(Operation.UPSERT)
						);
						hostEventSeen = true;
						break;
					} catch (AssertionError ignored) {
						// not the host-event envelope — keep scanning
					}
				}
				assertTrue(
					hostEventSeen,
					"Expected at least one `" + HostSystemEvent.CatalogInstalledIntoLiveView.class.getSimpleName() +
						"` envelope for catalog `" + newCatalogName + "`, received: " + receivedEvents
				);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should test basic subprotocol operations")
	void shouldTestBasicSubprotocolOperations(GraphQLTester tester) {
		tester.testWebSocket(
			SYSTEM_URL,
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
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive system capture without body")
	void shouldReceiveSystemCaptureWithoutBody(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;

		tester.testWebSocket(
			SYSTEM_URL,
			writer -> {
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(subscriptionId, "onSystemChange { version index operation }"));
				wait(2000);

				// apply operation to trigger a new event
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath(ON_SYSTEM_CHANGE_PATH, ChangeCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.TRANSACTION);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.node(resultPath(ON_SYSTEM_CHANGE_PATH, ChangeCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive system capture without body (untyped)")
	void shouldReceiveSystemCaptureWithoutBodyUntyped(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;

		tester.testWebSocket(
			SYSTEM_URL,
			writer -> {
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(subscriptionId, "onSystemChangeUntyped { version index operation }"));
				wait(2000);

				// apply operation to trigger a new event
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath(ON_SYSTEM_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.OPERATION))
						.isEqualTo(Operation.TRANSACTION);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.node(resultPath(ON_SYSTEM_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.OPERATION))
						.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive system capture without body with entire history")
	void shouldReceiveSystemCaptureWithoutBodyWithEntireHistory(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;

		tester.testWebSocket(
			SYSTEM_URL,
			writer -> {
				final long startVersion = evita.getEngineState().version() + 1;

				// apply operation to trigger a new event
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();

				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onSystemChange(sinceVersion: \\\"" + startVersion + "\\\") { version index operation }"
				));
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath(ON_SYSTEM_CHANGE_PATH, ChangeCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.TRANSACTION);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.node(resultPath(ON_SYSTEM_CHANGE_PATH, ChangeCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive system capture without body with entire history (untyped)")
	void shouldReceiveSystemCaptureWithoutBodyWithEntireHistoryUntyped(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;

		tester.testWebSocket(
			SYSTEM_URL,
			writer -> {
				final long startVersion = evita.getEngineState().version() + 1;

				// apply operation to trigger a new event
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();

				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onSystemChangeUntyped(sinceVersion: \\\"" + startVersion + "\\\") { version index operation }"
				));
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath(ON_SYSTEM_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.TRANSACTION);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.node(resultPath(ON_SYSTEM_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive system capture with body")
	void shouldReceiveSystemCaptureWithBody(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;

		tester.testWebSocket(
			SYSTEM_URL,
			writer -> {
				final long startVersion = evita.getEngineState().version() + 1;

				// apply operation to trigger a new event
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();

				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onSystemChange(sinceVersion: \\\"" + startVersion + "\\\") { version index operation body { ... on CreateCatalogSchemaMutation { mutationType } ... on TransactionMutation { mutationType } } }"
				));
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_SYSTEM_CHANGE_PATH, ChangeCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.TRANSACTION),
						it -> it.node(resultPath(ON_SYSTEM_CHANGE_PATH, ChangeCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(TransactionMutation.class.getSimpleName())
					);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_SYSTEM_CHANGE_PATH, ChangeCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath(ON_SYSTEM_CHANGE_PATH, ChangeCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(CreateCatalogSchemaMutation.class.getSimpleName())
					);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive system capture with body (untyped)")
	void shouldReceiveSystemCaptureWithBodyUntyped(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;

		tester.testWebSocket(
			SYSTEM_URL,
			writer -> {
				final long startVersion = evita.getEngineState().version() + 1;

				// apply operation to trigger a new event
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();

				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onSystemChangeUntyped(sinceVersion: \\\"" + startVersion + "\\\") { version index operation body }"
				));
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_SYSTEM_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.TRANSACTION),
						it -> it.node(resultPath(ON_SYSTEM_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(TransactionMutation.class.getSimpleName())
					);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_SYSTEM_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath(ON_SYSTEM_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(CreateCatalogSchemaMutation.class.getSimpleName())
					);
			}
		);
	}
	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive catalog capture without body")
	void shouldReceiveCatalogCaptureWithoutBody(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;
		final String newEntityType = "myEntityType";

		tester.testWebSocket(
			SYSTEM_URL,
			writer -> {
				// prepare data
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
				evita.updateCatalog(newCatalogName, EvitaSessionContract::goLiveAndClose);

				final long startVersion = getStartVersionForEvitaCDC(evita, newCatalogName);

				// open subscription
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onCatalogChange(sinceVersion: \\\"" + startVersion + "\\\", catalogName: \\\"" + newCatalogName + "\\\") { version index operation }"
				));

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
					.node(resultPath(ON_CATALOG_CHANGE_PATH, ChangeCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.TRANSACTION);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.node(resultPath(ON_CATALOG_CHANGE_PATH, ChangeCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive catalog capture without body (untyped)")
	void shouldReceiveCatalogCaptureWithoutBodyUntyped(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;
		final String newEntityType = "myEntityType";

		tester.testWebSocket(
			SYSTEM_URL,
			writer -> {
				// prepare data
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
				evita.updateCatalog(newCatalogName, EvitaSessionContract::goLiveAndClose);

				final long startVersion = getStartVersionForEvitaCDC(evita, newCatalogName);

				// open subscription
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onCatalogChangeUntyped(sinceVersion: \\\"" + startVersion + "\\\", catalogName: \\\"" + newCatalogName + "\\\") { version index operation }"
				));

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
					.node(resultPath(ON_CATALOG_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.TRANSACTION);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.node(resultPath(ON_CATALOG_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive catalog capture with body")
	void shouldReceiveCatalogCaptureWithBody(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;
		final String newEntityType = "myEntityType";

		tester.testWebSocket(
			SYSTEM_URL,
			writer -> {
				// prepare data
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
				evita.updateCatalog(newCatalogName, EvitaSessionContract::goLiveAndClose);

				final long startVersion = getStartVersionForEvitaCDC(evita, newCatalogName);

				// apply operation to trigger a new event
				evita.updateCatalog(
					newCatalogName,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				// open subscription
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onCatalogChange(sinceVersion: \\\"" + startVersion + "\\\", catalogName: \\\"" + newCatalogName + "\\\") { version index operation body { ... on CreateEntitySchemaMutation { mutationType } ... on TransactionMutation { mutationType } } }"
				));
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_CATALOG_CHANGE_PATH, ChangeCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.TRANSACTION),
						it -> it.node(resultPath(ON_CATALOG_CHANGE_PATH, ChangeCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(TransactionMutation.class.getSimpleName())
					);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_CATALOG_CHANGE_PATH, ChangeCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath(ON_CATALOG_CHANGE_PATH, ChangeCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(CreateEntitySchemaMutation.class.getSimpleName())
					);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive catalog capture with body (untyped)")
	void shouldReceiveCatalogCaptureWithBodyUntyped(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;
		final String newEntityType = "myEntityType";

		tester.testWebSocket(
			SYSTEM_URL,
			writer -> {
				// prepare data
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
				evita.updateCatalog(newCatalogName, EvitaSessionContract::goLiveAndClose);

				final long startVersion = getStartVersionForEvitaCDC(evita, newCatalogName);

				// apply operation to trigger a new event
				evita.updateCatalog(
					newCatalogName,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				// open subscription
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onCatalogChangeUntyped(sinceVersion: \\\"" + startVersion + "\\\", catalogName: \\\"" + newCatalogName + "\\\") { version index operation body }"
				));
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_CATALOG_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.TRANSACTION),
						it -> it.node(resultPath(ON_CATALOG_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(TransactionMutation.class.getSimpleName())
					);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_CATALOG_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath(ON_CATALOG_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(CreateEntitySchemaMutation.class.getSimpleName())
					);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive catalog capture filtered by criteria")
	void shouldReceiveCatalogCaptureFilteredByCriteria(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;
		final String newEntityType = "myEntityType";

		tester.testWebSocket(
			SYSTEM_URL,
			writer -> {
				// prepare data
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
				evita.updateCatalog(newCatalogName, EvitaSessionContract::goLiveAndClose);

				final long startVersion = getStartVersionForEvitaCDC(evita, newCatalogName);

				// apply operation to trigger a new event
				evita.updateCatalog(
					newCatalogName,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				// open subscription
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onCatalogChange(" +
						"sinceVersion: \\\"" + startVersion + "\\\", " +
						"catalogName: \\\"" + newCatalogName + "\\\"," +
						"criteria: { area: SCHEMA, schemaSite: { containerType: ENTITY } }" +
						")" +
						" { version index operation body { ... on CreateEntitySchemaMutation { mutationType } } }"
				));
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_CATALOG_CHANGE_PATH, ChangeCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath(ON_CATALOG_CHANGE_PATH, ChangeCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(CreateEntitySchemaMutation.class.getSimpleName())
					);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_SYSTEM_API)
	@DisplayName("Should receive catalog capture filtered by criteria (untyped)")
	void shouldReceiveCatalogCaptureFilteredByCriteriaUntyped(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newCatalogName = "myCatalog" + subscriptionId;
		final String newEntityType = "myEntityType";

		tester.testWebSocket(
			SYSTEM_URL,
			writer -> {
				// prepare data
				evita.applyMutation(new CreateCatalogSchemaMutation(newCatalogName)).onCompletion().toCompletableFuture().join();
				evita.updateCatalog(newCatalogName, EvitaSessionContract::goLiveAndClose);

				final long startVersion = getStartVersionForEvitaCDC(evita, newCatalogName);

				// apply operation to trigger a new event
				evita.updateCatalog(
					newCatalogName,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				// open subscription
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onCatalogChangeUntyped(" +
						"sinceVersion: \\\"" + startVersion + "\\\", " +
						"catalogName: \\\"" + newCatalogName + "\\\"," +
						"criteria: { area: SCHEMA, schemaSite: { containerType: ENTITY } }" +
						")" +
						" { version index operation body }"
				));
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_CATALOG_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath(ON_CATALOG_CHANGE_UNTYPED_PATH, ChangeCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(CreateEntitySchemaMutation.class.getSimpleName())
					);
			}
		);
	}

	@Nonnull
	private static String createSubscriptionQueryMessage(@Nonnull String subscriptionId, @Nonnull String subscriptionQuery) {
		return "{\"id\":\"" + subscriptionId + "\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { " + subscriptionQuery + " }\"}}";
	}
}
