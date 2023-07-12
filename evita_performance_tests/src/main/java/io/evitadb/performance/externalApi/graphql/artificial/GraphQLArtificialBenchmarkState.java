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

package io.evitadb.performance.externalApi.graphql.artificial;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.performance.artificial.AbstractArtificialBenchmarkState;
import io.evitadb.test.client.GraphQLClient;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.Supplier;

/**
 * Base state class for all artifical based benchmarks.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class GraphQLArtificialBenchmarkState extends AbstractArtificialBenchmarkState<GraphQLClient> {

	/**
	 * Returns an existing session unique for the thread or creates new one.
	 */
	public GraphQLClient getSession() {
		return getSession(() -> {
			try {
				return new GraphQLClient(
					"https://" + InetAddress.getByName("localhost").getHostAddress() + ":5555/gql/test-catalog",
					false
				);
			} catch (UnknownHostException e) {
				throw new EvitaInternalError("Unknown host.", e);
			}
		});
	}

	/**
	 * Returns an existing session unique for the thread or creates new one.
	 */
	public GraphQLClient getSession(Supplier<GraphQLClient> creatorFct) {
		final GraphQLClient session = this.session.get();
		if (session == null) {
			final GraphQLClient createdSession = creatorFct.get();
			this.session.set(createdSession);
			return createdSession;
		} else {
			return session;
		}
	}

}
