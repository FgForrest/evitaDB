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

package io.evitadb.documentation.evitaql;

import io.evitadb.api.EvitaContract;
import io.evitadb.documentation.Environment;
import io.evitadb.documentation.TestContext;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.test.client.query.graphql.GraphQLQueryConverter;
import io.evitadb.test.client.query.rest.RestQueryConverter;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Context creates new {@link EvitaClient} instance that is connected to the demo server.
 * The {@link EvitaClient} instance is reused between tests to speed them up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class EvitaTestContext implements TestContext {
	/**
	 * Initialized EvitaContract instance.
	 */
	@Getter
	private final EvitaContract evitaContract;

	/**
	 * Query converter for GraphQL.
	 */
	@Nonnull @Getter private final GraphQLQueryConverter graphQLQueryConverter;
	/**
	 * Query converter for REST.
	 */
	@Nonnull @Getter private final RestQueryConverter restQueryConverter;

	public EvitaTestContext(@Nonnull Environment profile) {
		this.evitaContract = new EvitaClient(
			profile == Environment.LOCALHOST ?
				EvitaClientConfiguration.builder()
					.host("localhost")
					.port(5555)
					.useGeneratedCertificate(true)
					.mtlsEnabled(false)
					.build()
				:
				EvitaClientConfiguration.builder()
					.host("demo.evitadb.io")
					.port(5555)
					// demo server provides Let's encrypt trusted certificate
					.useGeneratedCertificate(false)
					// the client will not be mutually verified by the server side
					.mtlsEnabled(false)
					.build()
		);

		this.graphQLQueryConverter = new GraphQLQueryConverter(this.evitaContract);
		this.restQueryConverter = new RestQueryConverter(this.evitaContract);
	}
}
