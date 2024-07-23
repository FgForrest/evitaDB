/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.performance.externalApi.graphql.artificial.GraphQLArtificialEntitiesBenchmark;
import io.evitadb.performance.generators.RandomQueryGenerator;
import io.evitadb.test.Entities;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Base state class for {@link GraphQLArtificialEntitiesBenchmark#attributeAndHierarchyFiltering(GraphQLArtificialAttributeBenchmarkState, GraphQLArtificialAttributeAndHierarchyFilteringState, Blackhole)}.
 * See benchmark description on the method.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class GraphQLArtificialAttributeAndHierarchyFilteringState extends AbstractGraphQLArtificialState implements RandomQueryGenerator {

	/**
	 * Prepares artificial product for the next operation that is measured in the benchmark.
	 */
	@Setup(Level.Invocation)
	public void prepareCall(GraphQLArtificialAttributeBenchmarkState benchmarkState) {
		setRequestBody(
			benchmarkState,
			generateRandomHierarchyQuery(
				generateRandomAttributeQuery(benchmarkState.getRandom(), benchmarkState.getProductSchema(), benchmarkState.getFilterableAttributes(), benchmarkState.getSortableAttributes()),
				benchmarkState.getRandom(), benchmarkState.getCategoryIds(), Entities.CATEGORY
			)
		);
	}
}
