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
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.DbInstanceParameterResolver;
import io.evitadb.test.tester.GraphQLTester;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.List;

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

	@Nonnull
	protected static EntitySchema createEmptyEntitySchema(@Nonnull String entityType) {
		return EntitySchema._internalBuild(entityType);
	}

	@DataSet(value = TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS, openWebApi = GraphQLProvider.CODE)
	protected DataCarrier setUp(Evita evita, EvitaServer evitaServer) {
		return setUpData(evita, evitaServer, 1000);
	}

	@Nonnull
	protected DataCarrier setUpData(Evita evita, EvitaServer evitaServer, int productCount) {
		TestDataGenerator.generateMockCatalogs(evita);
		final List<SealedEntity> entities = TestDataGenerator.generateMainCatalogEntities(evita, productCount);
		final HostDefinition[] host = evitaServer.getExternalApiServer().getApiOptions()
			.getEndpointConfiguration(GraphQLProvider.CODE)
			.getHost();

		return new DataCarrier(
			"entities", entities,
			"tester",
			new GraphQLTester(
				"https://" + host[0].hostName() + ":" + host[0].port() + "/gql"
			)
		);
	}
}
