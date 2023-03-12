/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.testSuite;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificateSettings;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.GraphQLProviderRegistrar;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLTester.Request;
import io.evitadb.externalApi.graphql.configuration.GraphQLConfig;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.extension.DbInstanceParameterResolver;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;

/**
 * Common ancestor for functional testing of GraphQL API server. It sets up Evita instance, GraphQL API server and
 * API tester.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Tag(FUNCTIONAL_TEST)
@ExtendWith(DbInstanceParameterResolver.class)
@Slf4j
public abstract class GraphQLEndpointFunctionalTest {

	public static final String TYPENAME_FIELD = "__typename";

	private static ExternalApiServer server;
	private static GraphQLTester tester;

	@SneakyThrows
	@BeforeEach
	void setUp() {
		 tester = new GraphQLTester("https://" + InetAddress.getByName("localhost").getHostAddress() + ":5555/gql" + getEndpointPath());
	}

	@Nonnull
	protected abstract String getEndpointPath();

	@DataSet(TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS)
	List<SealedEntity> setUp(Evita evita) {
		TestDataGenerator.generateMockCatalogs(evita);
		final List<SealedEntity> entities = TestDataGenerator.generateMainCatalogEntities(evita);

		server = new ExternalApiServer(
			evita,
			new ApiOptions(null, new CertificateSettings.Builder().build(), Map.of(GraphQLProvider.CODE, new GraphQLConfig())),
			Collections.singleton(new GraphQLProviderRegistrar())
		);
		server.start();

		return entities;
	}

	@AfterAll
	static void tearDown() {
		if (server != null) {
			server.close();
			server = null;
		}
	}

	/**
	 * Test single request to GraphQL API.
	 */
	protected Request testGraphQLCall() {
		return tester.test();
	}

	@Nonnull
	protected static EntitySchema createEmptyEntitySchema(@Nonnull String entityType) {
		return EntitySchema._internalBuild(entityType);
	}
}
