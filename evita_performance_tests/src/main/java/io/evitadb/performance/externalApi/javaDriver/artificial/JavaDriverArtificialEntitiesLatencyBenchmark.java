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

package io.evitadb.performance.externalApi.javaDriver.artificial;

import io.evitadb.performance.externalApi.javaDriver.artificial.state.*;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * This class runs all tests in {@link JavaDriverArtificialEntitiesBenchmark} in latency mode measurement.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@BenchmarkMode({Mode.AverageTime})
@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
@Threads(Threads.MAX)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class JavaDriverArtificialEntitiesLatencyBenchmark extends JavaDriverArtificialEntitiesBenchmark {

	@Benchmark
	@Override
	public void singleEntityRead(JavaDriverArtificialFullDatabaseBenchmarkState benchmarkState, JavaDriverArtificialSingleReadState state, Blackhole blackhole) {
		super.singleEntityRead(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Override
	public void paginatedEntityRead(JavaDriverArtificialFullDatabaseBenchmarkState benchmarkState, JavaDriverArtificialPageReadState state, Blackhole blackhole) {
		super.paginatedEntityRead(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Override
	public void attributeFiltering(JavaDriverArtificialAttributeBenchmarkState benchmarkState, JavaDriverArtificialAttributeFilteringState state, Blackhole blackhole) {
		super.attributeFiltering(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Override
	public void attributeAndHierarchyFiltering(JavaDriverArtificialAttributeBenchmarkState benchmarkState, JavaDriverArtificialAttributeAndHierarchyFilteringState state, Blackhole blackhole) {
		super.attributeAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Override
	public void attributeHistogramComputation(JavaDriverArtificialAttributeBenchmarkState benchmarkState, JavaDriverArtificialAttributeHistogramState state, Blackhole blackhole) {
		super.attributeHistogramComputation(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Override
	public void priceFiltering(JavaDriverArtificialPriceBenchmarkState benchmarkState, JavaDriverArtificialPriceFilteringState state, Blackhole blackhole) {
		super.priceFiltering(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Override
	public void priceAndHierarchyFiltering(JavaDriverArtificialPriceBenchmarkState benchmarkState, JavaDriverArtificialPriceAndHierarchyFilteringState state, Blackhole blackhole) {
		super.priceAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Override
	public void priceHistogramComputation(JavaDriverArtificialPriceBenchmarkState benchmarkState, JavaDriverArtificialPriceHistogramState state, Blackhole blackhole) {
		super.priceHistogramComputation(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Override
	public void facetFiltering(JavaDriverArtificialFacetBenchmarkState benchmarkState, JavaDriverArtificialFacetFilteringState state, Blackhole blackhole) {
		super.facetFiltering(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Override
	public void facetAndHierarchyFiltering(JavaDriverArtificialFacetBenchmarkState benchmarkState, JavaDriverArtificialFacetAndHierarchyFilteringState state, Blackhole blackhole) {
		super.facetAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Override
	public void facetFilteringAndSummarizingCount(JavaDriverArtificialFacetBenchmarkState benchmarkState, JavaDriverArtificialFacetFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetFilteringAndSummarizingCount(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Override
	public void facetAndHierarchyFilteringAndSummarizingCount(JavaDriverArtificialFacetBenchmarkState benchmarkState, JavaDriverArtificialFacetAndHierarchyFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingCount(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Override
	public void facetAndHierarchyFilteringAndSummarizingImpact(JavaDriverArtificialFacetBenchmarkState benchmarkState, JavaDriverArtificialFacetAndHierarchyFilteringAndSummarizingImpactState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingImpact(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Override
	public void hierarchyStatisticsComputation(JavaDriverArtificialHierarchyBenchmarkState benchmarkState, JavaDriverArtificialHierarchyStatisticsComputationState state, Blackhole blackhole) {
		super.hierarchyStatisticsComputation(benchmarkState, state, blackhole);
	}

}
