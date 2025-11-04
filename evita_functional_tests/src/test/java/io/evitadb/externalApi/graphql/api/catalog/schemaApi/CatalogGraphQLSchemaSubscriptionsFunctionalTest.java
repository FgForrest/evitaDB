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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi;

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.ExternalApiWebSocketFunctionTestsSupport;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaMutationDescriptor;
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

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

/**
 * Tests for GraphQL catalog schema subscriptions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class CatalogGraphQLSchemaSubscriptionsFunctionalTest
	extends GraphQLEndpointFunctionalTest
	implements ExternalApiFunctionTestsSupport, ExternalApiWebSocketFunctionTestsSupport {

	private static final String SCHEMA_PATH_SUFFIX = "/schema";
	private static final String ON_SCHEMA_CHANGE_PATH = "payload.data.onSchemaChange";
	private static final String ON_SCHEMA_CHANGE_UNTYPED_PATH = "payload.data.onSchemaChangeUntyped";

	public static final String GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API = "GraphQLEmptySystemForCatalogSchemaApi";

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
			SCHEMA_PATH_SUFFIX,
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
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive catalog schema change without body")
	void shouldReceiveCatalogCaptureWithoutBody(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			SCHEMA_PATH_SUFFIX,
			writer -> {
				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onSchemaChange(sinceVersion: \\\"" + startVersion + "\\\") { version index operation }"
				));

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath(ON_SCHEMA_CHANGE_PATH, ChangeSystemCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive catalog schema change without body (untyped)")
	void shouldReceiveCatalogCaptureWithoutBodyUntyped(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			SCHEMA_PATH_SUFFIX,
			writer -> {
				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onSchemaChangeUntyped(sinceVersion: \\\"" + startVersion + "\\\") { version index operation }"
				));

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath(ON_SCHEMA_CHANGE_UNTYPED_PATH, ChangeSystemCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive catalog schema change with body")
	void shouldReceiveCatalogCaptureWithBody(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			SCHEMA_PATH_SUFFIX,
			writer -> {
				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onSchemaChange(sinceVersion: \\\"" + startVersion + "\\\") { version index operation body { ... on CreateEntitySchemaMutation { mutationType } } }"
				));

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_SCHEMA_CHANGE_PATH, ChangeSystemCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath(ON_SCHEMA_CHANGE_PATH, ChangeSystemCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(CreateEntitySchemaMutation.class.getSimpleName())
					);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive catalog schema change with body (untyped)")
	void shouldReceiveCatalogCaptureWithBodyUntyped(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			SCHEMA_PATH_SUFFIX,
			writer -> {
				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onSchemaChangeUntyped(sinceVersion: \\\"" + startVersion + "\\\") { version index operation body }"
				));

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath(ON_SCHEMA_CHANGE_UNTYPED_PATH, ChangeSystemCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath(ON_SCHEMA_CHANGE_UNTYPED_PATH, ChangeSystemCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(CreateEntitySchemaMutation.class.getSimpleName())
					);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive collection schema change without body")
	void shouldReceiveCollectionCaptureWithoutBody(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			SCHEMA_PATH_SUFFIX,
			writer -> {
				// prepare data
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onMyEntityType" + subscriptionId + "SchemaChange(sinceVersion: \\\"" + startVersion + "\\\") { version index operation }"
				));

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchema(newEntityType)
							.orElseThrow()
							.openForWrite()
							.withAttribute("code", String.class)
							.updateVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "SchemaChange", ChangeSystemCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive collection schema change without body (untyped)")
	void shouldReceiveCollectionCaptureWithoutBodyUntyped(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			SCHEMA_PATH_SUFFIX,
			writer -> {
				// prepare data
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onMyEntityType" + subscriptionId + "SchemaChangeUntyped(sinceVersion: \\\"" + startVersion + "\\\") { version index operation }"
				));

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchema(newEntityType)
							.orElseThrow()
							.openForWrite()
							.withAttribute("code", String.class)
							.updateVia(session);
					}
				);
			},
			2, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "SchemaChangeUntyped", ChangeSystemCaptureDescriptor.OPERATION))
					.isEqualTo(Operation.UPSERT);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive collection schema change with body")
	void shouldReceiveCollectionCaptureWithBody(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			SCHEMA_PATH_SUFFIX,
			writer -> {
				// prepare data
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onMyEntityType" + subscriptionId + "SchemaChange(sinceVersion: \\\"" + startVersion + "\\\") " +
						"{ version index operation " +
						"body { ... on ModifyEntitySchemaMutation { mutationType schemaMutations { ... on CreateAttributeSchemaMutation { mutationType } } } ... on CreateAttributeSchemaMutation { mutationType } } " +
						"}"
				));

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchema(newEntityType)
							.orElseThrow()
							.openForWrite()
							.withAttribute("code", String.class)
							.updateVia(session);
					}
				);
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "SchemaChange", ChangeSystemCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "SchemaChange", ChangeSystemCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(ModifyEntitySchemaMutation.class.getSimpleName()),
						it -> it.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "SchemaChange", ChangeSystemCaptureDescriptor.BODY, ModifyEntitySchemaMutationDescriptor.SCHEMA_MUTATIONS.name() + "[0]", MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(CreateAttributeSchemaMutation.class.getSimpleName())
					);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.and(
						it -> it.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "SchemaChange", ChangeSystemCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "SchemaChange", ChangeSystemCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(CreateAttributeSchemaMutation.class.getSimpleName())
					);
			}
		);
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM_FOR_CATALOG_API)
	@DisplayName("Should receive collection schema change with body (untyped)")
	void shouldReceiveCollectionCaptureWithBodyUntyped(Evita evita, GraphQLTester tester) {
		final String subscriptionId = createSubscriptionId();
		final String newEntityType = "myEntityType" + subscriptionId;

		tester.testWebSocket(
			TEST_CATALOG,
			SCHEMA_PATH_SUFFIX,
			writer -> {
				// prepare data
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(newEntityType).updateVia(session);
					}
				);

				final long startVersion = getStartVersionForCatalogCDC(evita, TEST_CATALOG);

				// open subscription
				writer.write(createConnectionInitMessage());
				writer.write(createSubscriptionQueryMessage(
					subscriptionId,
					"onMyEntityType" + subscriptionId + "SchemaChangeUntyped(sinceVersion: \\\"" + startVersion + "\\\") { version index operation body }"
				));

				// apply operation to trigger a new event
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchema(newEntityType)
							.orElseThrow()
							.openForWrite()
							.withAttribute("code", String.class)
							.updateVia(session);
					}
				);
			},
			3, receivedEvents -> {
				assertConnectionAckEvent(receivedEvents.get(0));
				assertNextEvent(receivedEvents.get(1), subscriptionId)
					.and(
						it -> it.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "SchemaChangeUntyped", ChangeSystemCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "SchemaChangeUntyped", ChangeSystemCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(ModifyEntitySchemaMutation.class.getSimpleName()),
						it -> it.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "SchemaChangeUntyped", ChangeSystemCaptureDescriptor.BODY, ModifyEntitySchemaMutationDescriptor.SCHEMA_MUTATIONS.name() + "[0]", MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(CreateAttributeSchemaMutation.class.getSimpleName())
					);
				assertNextEvent(receivedEvents.get(2), subscriptionId)
					.and(
						it -> it.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "SchemaChangeUntyped", ChangeSystemCaptureDescriptor.OPERATION))
							.isEqualTo(Operation.UPSERT),
						it -> it.node(resultPath("payload", "data", "onMyEntityType" + subscriptionId + "SchemaChangeUntyped", ChangeSystemCaptureDescriptor.BODY, MutationDescriptor.MUTATION_TYPE))
							.isEqualTo(CreateAttributeSchemaMutation.class.getSimpleName())
					);
			}
		);
	}

	@Nonnull
	private static String createSubscriptionQueryMessage(@Nonnull String subscriptionId, @Nonnull String subscriptionQuery) {
		return "{\"id\":\"" + subscriptionId + "\",\"type\":\"subscribe\",\"payload\":{\"query\":\"subscription { " + subscriptionQuery + " }\"}}";
	}
}
