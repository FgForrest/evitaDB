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

package io.evitadb.performance.externalApi.grpc.artificial;

import io.evitadb.performance.externalApi.grpc.artificial.state.*;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * This class runs all tests in {@link GrpcArtificialEntitiesBenchmark} in throughput mode measurement.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@BenchmarkMode({Mode.Throughput})
@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
@Threads(Threads.MAX)
public class GrpcArtificialEntitiesThroughputBenchmark extends GrpcArtificialEntitiesBenchmark {

	@Override
	@Benchmark
	public void singleEntityRead(GrpcArtificialFullDatabaseBenchmarkState benchmarkState, GrpcArtificialSingleReadState state, Blackhole blackhole) {
		super.singleEntityRead(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void paginatedEntityRead(GrpcArtificialFullDatabaseBenchmarkState benchmarkState, GrpcArtificialPageReadState state, Blackhole blackhole) {
		super.paginatedEntityRead(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void attributeFiltering(GrpcArtificialAttributeBenchmarkState benchmarkState, GrpcArtificialAttributeFilteringState state, Blackhole blackhole) {
		super.attributeFiltering(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void attributeAndHierarchyFiltering(GrpcArtificialAttributeBenchmarkState benchmarkState, GrpcArtificialAttributeAndHierarchyFilteringState state, Blackhole blackhole) {
		super.attributeAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void attributeHistogramComputation(GrpcArtificialAttributeBenchmarkState benchmarkState, GrpcArtificialAttributeHistogramState state, Blackhole blackhole) {
		super.attributeHistogramComputation(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void priceFiltering(GrpcArtificialPriceBenchmarkState benchmarkState, GrpcArtificialPriceFilteringState state, Blackhole blackhole) {
		super.priceFiltering(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void priceAndHierarchyFiltering(GrpcArtificialPriceBenchmarkState benchmarkState, GrpcArtificialPriceAndHierarchyFilteringState state, Blackhole blackhole) {
		super.priceAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void priceHistogramComputation(GrpcArtificialPriceBenchmarkState benchmarkState, GrpcArtificialPriceHistogramState state, Blackhole blackhole) {
		super.priceHistogramComputation(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void facetFiltering(GrpcArtificialFacetBenchmarkState benchmarkState, GrpcArtificialFacetFilteringState state, Blackhole blackhole) {
		super.facetFiltering(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void facetAndHierarchyFiltering(GrpcArtificialFacetBenchmarkState benchmarkState, GrpcArtificialFacetAndHierarchyFilteringState state, Blackhole blackhole) {
		super.facetAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void facetFilteringAndSummarizingCount(GrpcArtificialFacetBenchmarkState benchmarkState, GrpcArtificialFacetFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetFilteringAndSummarizingCount(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void facetAndHierarchyFilteringAndSummarizingCount(GrpcArtificialFacetBenchmarkState benchmarkState, GrpcArtificialFacetAndHierarchyFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingCount(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void facetAndHierarchyFilteringAndSummarizingImpact(GrpcArtificialFacetBenchmarkState benchmarkState, GrpcArtificialFacetAndHierarchyFilteringAndSummarizingImpactState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingImpact(benchmarkState, state, blackhole);
	}

	@Override
	@Benchmark
	public void hierarchyStatisticsComputation(GrpcArtificialHierarchyBenchmarkState benchmarkState, GrpcArtificialHierarchyStatisticsComputationState state, Blackhole blackhole) {
		super.hierarchyStatisticsComputation(benchmarkState, state, blackhole);
	}

}
