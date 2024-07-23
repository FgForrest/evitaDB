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

package io.evitadb.performance.externalApi.graphql.artificial;

import io.evitadb.performance.externalApi.graphql.artificial.state.*;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * This class runs all tests in {@link GraphQLArtificialEntitiesBenchmark} in latency mode measurement.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@BenchmarkMode({Mode.AverageTime})
@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
@Threads(Threads.MAX)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class GraphQLArtificialEntitiesLatencyBenchmark extends GraphQLArtificialEntitiesBenchmark {

	@Override
	@Benchmark
	public void singleEntityRead(GraphQLArtificialFullDatabaseBenchmarkState benchmarkState, GraphQLArtificialSingleReadState state, Blackhole blackhole) {
		super.singleEntityRead(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void paginatedEntityRead(GraphQLArtificialFullDatabaseBenchmarkState benchmarkState, GraphQLArtificialPageReadState state, Blackhole blackhole) {
		super.paginatedEntityRead(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void attributeFiltering(GraphQLArtificialAttributeBenchmarkState benchmarkState, GraphQLArtificialAttributeFilteringState state, Blackhole blackhole) {
		super.attributeFiltering(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void attributeAndHierarchyFiltering(GraphQLArtificialAttributeBenchmarkState benchmarkState, GraphQLArtificialAttributeAndHierarchyFilteringState state, Blackhole blackhole) {
		super.attributeAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void attributeHistogramComputation(GraphQLArtificialAttributeBenchmarkState benchmarkState, GraphQLArtificialAttributeHistogramState state, Blackhole blackhole) {
		super.attributeHistogramComputation(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void priceFiltering(GraphQLArtificialPriceBenchmarkState benchmarkState, GraphQLArtificialPriceFilteringState state, Blackhole blackhole) {
		super.priceFiltering(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void priceAndHierarchyFiltering(GraphQLArtificialPriceBenchmarkState benchmarkState, GraphQLArtificialPriceAndHierarchyFilteringState state, Blackhole blackhole) {
		super.priceAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void priceHistogramComputation(GraphQLArtificialPriceBenchmarkState benchmarkState, GraphQLArtificialPriceHistogramState state, Blackhole blackhole) {
		super.priceHistogramComputation(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void facetFiltering(GraphQLArtificialFacetBenchmarkState benchmarkState, GraphQLArtificialFacetFilteringState state, Blackhole blackhole) {
		super.facetFiltering(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void facetAndHierarchyFiltering(GraphQLArtificialFacetBenchmarkState benchmarkState, GraphQLArtificialFacetAndHierarchyFilteringState state, Blackhole blackhole) {
		super.facetAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void facetFilteringAndSummarizingCount(GraphQLArtificialFacetBenchmarkState benchmarkState, GraphQLArtificialFacetFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetFilteringAndSummarizingCount(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void facetAndHierarchyFilteringAndSummarizingCount(GraphQLArtificialFacetBenchmarkState benchmarkState, GraphQLArtificialFacetAndHierarchyFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingCount(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void facetAndHierarchyFilteringAndSummarizingImpact(GraphQLArtificialFacetBenchmarkState benchmarkState, GraphQLArtificialFacetAndHierarchyFilteringAndSummarizingImpactState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingImpact(benchmarkState, state, blackhole);
	}


	@Override
	@Benchmark
	public void hierarchyStatisticsComputation(GraphQLArtificialHierarchyBenchmarkState benchmarkState, GraphQLArtificialHierarchyStatisticsComputationState state, Blackhole blackhole) {
		super.hierarchyStatisticsComputation(benchmarkState, state, blackhole);
	}

}
