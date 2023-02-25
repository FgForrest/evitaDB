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

package io.evitadb.performance.externalApi.rest.artificial;

import io.evitadb.performance.externalApi.rest.artificial.state.*;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.Blackhole;

/**
 * This class runs all tests in {@link RestArtificialEntitiesBenchmark} in throughput mode measurement.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@BenchmarkMode({Mode.Throughput})
public class RestArtificialEntitiesThroughputBenchmark extends RestArtificialEntitiesBenchmark {

	@Override
	public void singleEntityRead(RestArtificialFullDatabaseBenchmarkState benchmarkState, RestArtificialSingleReadState state, Blackhole blackhole) {
		super.singleEntityRead(benchmarkState, state, blackhole);
	}

	@Override
	public void paginatedEntityRead(RestArtificialFullDatabaseBenchmarkState benchmarkState, RestArtificialPageReadState state, Blackhole blackhole) {
		super.paginatedEntityRead(benchmarkState, state, blackhole);
	}

	@Override
	public void attributeFiltering(RestArtificialAttributeBenchmarkState benchmarkState, RestArtificialAttributeFilteringState state, Blackhole blackhole) {
		super.attributeFiltering(benchmarkState, state, blackhole);
	}

	@Override
	public void attributeAndHierarchyFiltering(RestArtificialAttributeBenchmarkState benchmarkState, RestArtificialAttributeAndHierarchyFilteringState state, Blackhole blackhole) {
		super.attributeAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Override
	public void attributeHistogramComputation(RestArtificialAttributeBenchmarkState benchmarkState, RestArtificialAttributeHistogramState state, Blackhole blackhole) {
		super.attributeHistogramComputation(benchmarkState, state, blackhole);
	}

	@Override
	public void priceFiltering(RestArtificialPriceBenchmarkState benchmarkState, RestArtificialPriceFilteringState state, Blackhole blackhole) {
		super.priceFiltering(benchmarkState, state, blackhole);
	}

	@Override
	public void priceAndHierarchyFiltering(RestArtificialPriceBenchmarkState benchmarkState, RestArtificialPriceAndHierarchyFilteringState state, Blackhole blackhole) {
		super.priceAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Override
	public void priceHistogramComputation(RestArtificialPriceBenchmarkState benchmarkState, RestArtificialPriceHistogramState state, Blackhole blackhole) {
		super.priceHistogramComputation(benchmarkState, state, blackhole);
	}

	@Override
	public void facetFiltering(RestArtificialFacetBenchmarkState benchmarkState, RestArtificialFacetFilteringState state, Blackhole blackhole) {
		super.facetFiltering(benchmarkState, state, blackhole);
	}

	@Override
	public void facetAndHierarchyFiltering(RestArtificialFacetBenchmarkState benchmarkState, RestArtificialFacetAndHierarchyFilteringState state, Blackhole blackhole) {
		super.facetAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Override
	public void facetFilteringAndSummarizingCount(RestArtificialFacetBenchmarkState benchmarkState, RestArtificialFacetFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetFilteringAndSummarizingCount(benchmarkState, state, blackhole);
	}

	@Override
	public void facetAndHierarchyFilteringAndSummarizingCount(RestArtificialFacetBenchmarkState benchmarkState, RestArtificialFacetAndHierarchyFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingCount(benchmarkState, state, blackhole);
	}

	@Override
	public void facetAndHierarchyFilteringAndSummarizingImpact(RestArtificialFacetBenchmarkState benchmarkState, RestArtificialFacetAndHierarchyFilteringAndSummarizingImpactState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingImpact(benchmarkState, state, blackhole);
	}

	@Override
	public void parentsComputation(RestArtificialHierarchyBenchmarkState benchmarkState, RestArtificialParentsComputationState state, Blackhole blackhole) {
		super.parentsComputation(benchmarkState, state, blackhole);
	}

	@Override
	public void hierarchyStatisticsComputation(RestArtificialHierarchyBenchmarkState benchmarkState, RestArtificialHierarchyStatisticsComputationState state, Blackhole blackhole) {
		super.hierarchyStatisticsComputation(benchmarkState, state, blackhole);
	}

}
