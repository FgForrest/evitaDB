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

package io.evitadb.performance.artificial;

import io.evitadb.performance.artificial.state.*;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * This class runs all tests in {@link ArtificialEntitiesBenchmark} in throughput mode measurement.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@BenchmarkMode({Mode.Throughput})
@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
public class ArtificialEntitiesThroughputBenchmark extends ArtificialEntitiesBenchmark {

	@Benchmark
	@Threads(1)
	@Override
	public void bulkInsertThroughput(ArtificialBulkWriteBenchmarkState benchmarkState, ArtificialBulkWriteState state) {
		super.bulkInsertThroughput(benchmarkState, state);
	}

	@Benchmark
	@Threads(1)
	@Override
	public void transactionalUpsertThroughput(ArtificialTransactionalWriteBenchmarkState benchmarkState, ArtificialTransactionalWriteState state) {
		super.transactionalUpsertThroughput(benchmarkState, state);
	}

	@Benchmark
	@Threads(Threads.MAX)
	@Override
	public void singleEntityRead(ArtificialFullDatabaseBenchmarkState benchmarkState, ArtificialSingleReadState state, Blackhole blackhole) {
		super.singleEntityRead(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Threads(Threads.MAX)
	@Override
	public void paginatedEntityRead(ArtificialFullDatabaseBenchmarkState benchmarkState, ArtificialPageReadState state, Blackhole blackhole) {
		super.paginatedEntityRead(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Threads(Threads.MAX)
	@Override
	public void attributeFiltering(ArtificialAttributeBenchmarkState benchmarkState, ArtificialAttributeFilteringState state, Blackhole blackhole) {
		super.attributeFiltering(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Threads(Threads.MAX)
	@Override
	public void attributeAndHierarchyFiltering(ArtificialAttributeBenchmarkState benchmarkState, ArtificialAttributeAndHierarchyFilteringState state, Blackhole blackhole) {
		super.attributeAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Threads(Threads.MAX)
	@Override
	public void attributeHistogramComputation(ArtificialAttributeBenchmarkState benchmarkState, ArtificialAttributeHistogramState state, Blackhole blackhole) {
		super.attributeHistogramComputation(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Threads(Threads.MAX)
	@Override
	public void priceFiltering(ArtificialPriceBenchmarkState benchmarkState, ArtificialPriceFilteringState state, Blackhole blackhole) {
		super.priceFiltering(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Threads(Threads.MAX)
	@Override
	public void priceAndHierarchyFiltering(ArtificialPriceBenchmarkState benchmarkState, ArtificialPriceAndHierarchyFilteringState state, Blackhole blackhole) {
		super.priceAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Threads(Threads.MAX)
	@Override
	public void priceHistogramComputation(ArtificialPriceBenchmarkState benchmarkState, ArtificialPriceHistogramState state, Blackhole blackhole) {
		super.priceHistogramComputation(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Threads(Threads.MAX)
	@Override
	public void facetFiltering(ArtificialFacetBenchmarkState benchmarkState, ArtificialFacetFilteringState state, Blackhole blackhole) {
		super.facetFiltering(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Threads(Threads.MAX)
	@Override
	public void facetAndHierarchyFiltering(ArtificialFacetBenchmarkState benchmarkState, ArtificialFacetAndHierarchyFilteringState state, Blackhole blackhole) {
		super.facetAndHierarchyFiltering(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Threads(Threads.MAX)
	@Override
	public void facetFilteringAndSummarizingCount(ArtificialFacetBenchmarkState benchmarkState, ArtificialFacetFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetFilteringAndSummarizingCount(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Threads(Threads.MAX)
	@Override
	public void facetAndHierarchyFilteringAndSummarizingCount(ArtificialFacetBenchmarkState benchmarkState, ArtificialFacetAndHierarchyFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingCount(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Threads(Threads.MAX)
	@Override
	public void facetAndHierarchyFilteringAndSummarizingImpact(ArtificialFacetBenchmarkState benchmarkState, ArtificialFacetAndHierarchyFilteringAndSummarizingImpactState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingImpact(benchmarkState, state, blackhole);
	}

	@Benchmark
	@Threads(Threads.MAX)
	@Override
	public void hierarchyStatisticsComputation(ArtificialHierarchyBenchmarkState benchmarkState, ArtificialHierarchyStatisticsComputationState state, Blackhole blackhole) {
		super.hierarchyStatisticsComputation(benchmarkState, state, blackhole);
	}

}
