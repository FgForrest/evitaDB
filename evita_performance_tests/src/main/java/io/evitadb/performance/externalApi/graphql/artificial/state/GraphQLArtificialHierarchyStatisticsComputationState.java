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

import io.evitadb.performance.externalApi.graphql.artificial.GraphQLArtificialEntitiesBenchmark;
import io.evitadb.performance.generators.RandomQueryGenerator;
import io.evitadb.utils.Assert;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Base state class for {@link GraphQLArtificialEntitiesBenchmark#hierarchyStatisticsComputation(GraphQLArtificialHierarchyBenchmarkState, GraphQLArtificialHierarchyStatisticsComputationState, Blackhole)}.
 * See benchmark description on the method.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class GraphQLArtificialHierarchyStatisticsComputationState extends AbstractGraphQLArtificialState implements RandomQueryGenerator {

	/**
	 * Set contains all `entityTypes` that are hierarchical and are referenced from product entity.
	 */
	private final Set<String> referencedHierarchicalEntities = new LinkedHashSet<>();

	/**
	 * Prepares artificial product for the next operation that is measured in the benchmark.
	 */
	@Setup(Level.Invocation)
	public void prepareCall(GraphQLArtificialHierarchyBenchmarkState benchmarkState) {
		if (this.referencedHierarchicalEntities.isEmpty()) {
			benchmarkState.getProductSchema().getReferences()
				.values()
				.forEach(it -> {
					if (it.isReferencedEntityTypeManaged() && benchmarkState.getHierarchicalEntities().contains(it.getReferencedEntityType())) {
						this.referencedHierarchicalEntities.add(it.getReferencedEntityType());
					}
				});
			Assert.isTrue(!this.referencedHierarchicalEntities.isEmpty(), "No referenced entity is hierarchical!");
		}
		setRequestBody(
			benchmarkState,
			generateRandomParentSummaryQuery(
				benchmarkState.getRandom(), benchmarkState.getProductSchema(), this.referencedHierarchicalEntities
			)
		);
	}


}
