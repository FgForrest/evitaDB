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

package io.evitadb.externalApi.graphql.api.catalog.dataApi;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.ExternalApiWebSocketFunctionTestsSupport;
import io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.ChangeSystemCaptureDescriptor;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLEndpointFunctionalTest;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Tests for GraphQL catalog data subscriptions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(QUERY)
public class CatalogGraphQLDataSubscriptionsFunctionalTest
	extends GraphQLEndpointFunctionalTest
	implements ExternalApiFunctionTestsSupport, ExternalApiWebSocketFunctionTestsSupport {

	private static final String ON_DATA_CHANGE_PATH = "payload.data.onDataChange";
	private static final String ON_DATA_CHANGE_UNTYPED_PATH = "payload.data.onDataChangeUntyped";

	private static final String GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API = "GraphQLEmptySystemForCatalogDataApi";

	@Override
	@DataSet(value = GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita) {
		return new DataCarrier();
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should test basic subprotocol operations")
	void shouldTestBasicSubprotocolOperations(GraphQLTester tester) {
		tester.testWebSocket(
			TEST_CATALOG,
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
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive catalog data change without body")
	void shouldReceiveCatalogCaptureWithoutBody(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			ctx -> {
				// prepare collection
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(
					subscriptionId,
					"onDataChange(sinceVersion: \\\"" + startVersion + "\\\") { version index operation }"
				));

				// wait for connection_ack before triggering the data change — gives the server
				// time to finish registering the CDC subscription so the upsert is not raced
				ctx.awaitEvents(1);

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.createNewEntity(newEntityType, 1).upsertVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath(ON_DATA_CHANGE_PATH, ChangeSystemCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive catalog data change without body (untyped)")
	void shouldReceiveCatalogCaptureWithoutBodyUntyped(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			ctx -> {
				// prepare collection
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(
					subscriptionId,
					"onDataChangeUntyped(sinceVersion: \\\"" + startVersion + "\\\") { version index operation }"
				));

				// wait for connection_ack before triggering the data change — gives the server
				// time to finish registering the CDC subscription so the upsert is not raced
				ctx.awaitEvents(1);

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.createNewEntity(newEntityType, 1).upsertVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath(ON_DATA_CHANGE_UNTYPED_PATH, ChangeSystemCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive catalog data change with body")
	void shouldReceiveCatalogCaptureWithBody(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			ctx -> {
				// prepare collection
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(
					subscriptionId,
					"onDataChange(sinceVersion: \\\"" + startVersion + "\\\") { version index operation body { ... on EntityUpsertMutation { mutationType } } }"
				));

				// wait for connection_ack before triggering the data change — gives the server
				// time to finish registering the CDC subscription so the upsert is not raced
				ctx.awaitEvents(1);

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.createNewEntity(newEntityType, 1).upsertVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_DATA_CHANGE_PATH, ChangeSystemCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath(ON_DATA_CHANGE_PATH, ChangeSystemCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(EntityUpsertMutation.class.getSimpleName())
					);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive catalog data change with body (untyped)")
	void shouldReceiveCatalogCaptureWithBodyUntyped(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			ctx -> {
				// prepare collection
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(
					subscriptionId,
					"onDataChangeUntyped(sinceVersion: \\\"" + startVersion + "\\\") { version index operation body }"
				));

				// wait for connection_ack before triggering the data change — gives the server
				// time to finish registering the CDC subscription so the upsert is not raced
				ctx.awaitEvents(1);

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.createNewEntity(newEntityType, 1).upsertVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_DATA_CHANGE_UNTYPED_PATH, ChangeSystemCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath(ON_DATA_CHANGE_UNTYPED_PATH, ChangeSystemCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo("EntityUpsertMutation")
					);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive collection data change without body")
	void shouldReceiveCollectionCaptureWithoutBody(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			ctx -> {
				// prepare collection
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(
					subscriptionId,
					"onMyEntityType" + subscriptionId + "DataChange(sinceVersion: \\\"" + startVersion + "\\\") { version index operation }"
				));

				// wait for connection_ack before triggering the data change — gives the server
				// time to finish registering the CDC subscription so the upsert is not raced
				ctx.awaitEvents(1);

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.createNewEntity(newEntityType, 1).upsertVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "DataChange", ChangeSystemCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive collection data change without body (untyped)")
	void shouldReceiveCollectionCaptureWithoutBodyUntyped(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			ctx -> {
				// prepare collection
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(
					subscriptionId,
					"onMyEntityType" + subscriptionId + "DataChangeUntyped(sinceVersion: \\\"" + startVersion + "\\\") { version index operation }"
				));

				// wait for connection_ack before triggering the data change — gives the server
				// time to finish registering the CDC subscription so the upsert is not raced
				ctx.awaitEvents(1);

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.createNewEntity(newEntityType, 1).upsertVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "DataChangeUntyped", ChangeSystemCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive collection data change with body")
	void shouldReceiveCollectionCaptureWithBody(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			ctx -> {
				// prepare collection
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(
					subscriptionId,
					"onMyEntityType" + subscriptionId + "DataChange(sinceVersion: \\\"" + startVersion + "\\\") { version index operation body { ... on EntityUpsertMutation { mutationType } } }"
				));

				// wait for connection_ack before triggering the data change — gives the server
				// time to finish registering the CDC subscription so the upsert is not raced
				ctx.awaitEvents(1);

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.createNewEntity(newEntityType, 1).upsertVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "DataChange", ChangeSystemCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "DataChange", ChangeSystemCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(EntityUpsertMutation.class.getSimpleName())
					);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive collection data change with body (untyped)")
	void shouldReceiveCollectionCaptureWithBodyUntyped(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			ctx -> {
				// prepare collection
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(
					subscriptionId,
					"onMyEntityType" + subscriptionId + "DataChangeUntyped(sinceVersion: \\\"" + startVersion + "\\\") { version index operation body }"
				));

				// wait for connection_ack before triggering the data change — gives the server
				// time to finish registering the CDC subscription so the upsert is not raced
				ctx.awaitEvents(1);

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.createNewEntity(newEntityType, 1).upsertVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "DataChangeUntyped", ChangeSystemCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "DataChangeUntyped", ChangeSystemCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo("EntityUpsertMutation")
					);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should accept a resume position of the current catalog incarnation and stamp catalog data changes with it")
	void shouldAcceptCurrentCatalogIdAndReturnItInCatalogDataChanges(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;
		final UUID catalogId = getCatalogIdForCatalogCDC(evita, TEST_CATALOG);

		tester.testWebSocket(
			TEST_CATALOG,
			ctx -> {
				// prepare collection
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(
					subscriptionId,
					"onDataChange(catalogId: \\\"" + catalogId + "\\\", sinceVersion: \\\"" + startVersion + "\\\") { catalogId version index operation }"
				));

				// wait for connection_ack before triggering the data change — gives the server
				// time to finish registering the CDC subscription so the upsert is not raced
				ctx.awaitEvents(1);

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.createNewEntity(newEntityType, 1).upsertVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_DATA_CHANGE_PATH, ChangeCatalogCaptureDescriptor.CATALOG_ID))
							.isString()
							.isEqualTo(catalogId.toString()),
						it -> it.node(resultPath(ON_DATA_CHANGE_PATH, ChangeCatalogCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT)
					);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should refuse catalog data changes from a resume position of a different catalog incarnation")
	void shouldRefuseCatalogDataChangesOfDifferentCatalogIncarnation(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = defineEntityType(evita, "myEntityType" + subscriptionId);

		assertSubscriptionRefused(
			evita, tester, subscriptionId,
			"onDataChange(catalogId: \\\"" + UUID.randomUUID() + "\\\", sinceVersion: \\\"" + getStartVersionForCatalogCDC(evita, TEST_CATALOG) + "\\\") { version index operation }",
			session -> {
				session.createNewEntity(newEntityType, 1).upsertVia(session);
			},
			"was recorded on a different incarnation of the catalog"
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should refuse collection data changes from a resume position of a different catalog incarnation")
	void shouldRefuseCollectionDataChangesOfDifferentCatalogIncarnation(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = defineEntityType(evita, "myEntityType" + subscriptionId);

		assertSubscriptionRefused(
			evita, tester, subscriptionId,
			"onMyEntityType" + subscriptionId + "DataChange(catalogId: \\\"" + UUID.randomUUID() + "\\\", sinceVersion: \\\"" + getStartVersionForCatalogCDC(evita, TEST_CATALOG) + "\\\") { version index operation }",
			session -> {
				session.createNewEntity(newEntityType, 1).upsertVia(session);
			},
			"was recorded on a different incarnation of the catalog"
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should refuse catalog data changes from a resume position that lies ahead of the catalog")
	void shouldRefuseCatalogDataChangesAheadOfCatalog(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = defineEntityType(evita, "myEntityType" + subscriptionId);

		assertSubscriptionRefused(
			evita, tester, subscriptionId,
			"onDataChange(sinceVersion: \\\"" + (getStartVersionForCatalogCDC(evita, TEST_CATALOG) + AHEAD_OF_CATALOG_MARGIN) + "\\\") { version index operation }",
			session -> {
				session.createNewEntity(newEntityType, 1).upsertVia(session);
			},
			"lies ahead of catalog"
		);
	}

	/**
	 * Defines a new entity collection in the test catalog, so that data changes can be made in it.
	 */
	@Nonnull
	private static String defineEntityType(@Nonnull Evita evita, @Nonnull String entityType) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(entityType).updateVia(session);
			}
		);
		return entityType;
	}

	/**
	 * Opens a CDC subscription that is expected to be refused, commits a change afterwards and asserts that the
	 * subscription ends with a single error event carrying the expected message and that no capture follows it.
	 * The probe change must be one the subscription would deliver if it were accepted, so that a subscription
	 * accepted by mistake fails the test on the delivered capture rather than on a timeout.
	 */
	private void assertSubscriptionRefused(
		@Nonnull Evita evita,
		@Nonnull GraphQLTester tester,
		@Nonnull String subscriptionId,
		@Nonnull String subscriptionQuery,
		@Nonnull Consumer<EvitaSessionContract> probeChange,
		@Nonnull String expectedMessageFragment
	) {
		tester.testWebSocket(
			TEST_CATALOG,
			ctx -> {
				// open subscription
				ctx.writer().write(createConnectionInitMessage());
				ctx.writer().write(createSubscriptionQueryMessage(subscriptionId, subscriptionQuery));
				ctx.awaitEvents(1);

				// a change that an accepted subscription would deliver
				evita.updateCatalog(TEST_CATALOG, probeChange);
				ctx.tryAwaitEvents(3, REFUSED_SUBSCRIPTION_QUIET_WINDOW);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertErrorEvent(receivedEvents.get(1), subscriptionId)
					.node("payload[0].message")
					.isString()
					.contains(expectedMessageFragment);
				assertErrorEvent(receivedEvents.get(1), subscriptionId)
					.node("payload[0].extensions.errorCode")
					.isString()
					.isNotBlank();
				assertEquals(2, receivedEvents.size(), "No event may follow the refusal: " + receivedEvents);
			}
		);
	}

	@Nonnull
	private static String createSubscriptionQueryMessage(@Nonnull String subscriptionId, @Nonnull String subscriptionQuery) {
		return "{\"id\":\"" + subscriptionId + "\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { " + subscriptionQuery + " }\"}}";
	}
}
