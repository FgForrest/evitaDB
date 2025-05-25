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

package io.evitadb.performance.externalApi.graphql.artificial.state;

import io.evitadb.api.query.Query;
import io.evitadb.performance.artificial.AbstractArtificialBenchmarkState;
import io.evitadb.test.client.query.graphql.GraphQLQueryConverter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestConstants.TEST_CATALOG;

/**
 * Common ancestor for thread-scoped state objects.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@State(Scope.Thread)
@Slf4j
public abstract class AbstractGraphQLArtificialState {

	@Nonnull private final GraphQLQueryConverter queryConverter = new GraphQLQueryConverter();

	@Getter private String instancePath = "/gql/testCatalog";

	/**
	 * Request body prepared for the measured invocation.
	 */
	@Getter private String requestBody;

	protected void setRequestBody(@Nonnull String requestBody) {
		this.requestBody = requestBody;
	}

	protected void setRequestBody(@Nonnull AbstractArtificialBenchmarkState<?> benchmarkState, @Nonnull Query query) {
		this.requestBody = this.queryConverter.convert(
			benchmarkState.getEvita(),
			TEST_CATALOG,
			query
		);
	}
}
