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

package io.evitadb.performance.signal;

import io.evitadb.performance.signal.state.*;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * This class runs all tests in {@link SignalBenchmark} in throughput mode measurement.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@BenchmarkMode({Mode.Throughput})
@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
public class SignalThroughputBenchmark extends SignalBenchmark {

	@Override
	@Threads(1)
	@Benchmark
	public void bulkInsertThroughput_InMemory(SignalBulkWriteState state) {
		super.bulkInsertThroughput_InMemory(state);
	}

	@Override
	@Threads(1)
	@Benchmark
	public void transactionalUpsertThroughput_InMemory(SignalTransactionalWriteState state) {
		super.transactionalUpsertThroughput_InMemory(state);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void singleEntityRead_InMemory(SignalSingleReadState state, Blackhole blackhole) {
		super.singleEntityRead_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void paginatedEntityRead_InMemory(SignalPageReadState state, Blackhole blackhole) {
		super.paginatedEntityRead_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void attributeFiltering_InMemory(SignalAttributeFilteringState state, Blackhole blackhole) {
		super.attributeFiltering_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void attributeAndHierarchyFiltering_InMemory(SignalAttributeAndHierarchyFilteringState state, Blackhole blackhole) {
		super.attributeAndHierarchyFiltering_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void attributeHistogramComputation_InMemory(SignalAttributeHistogramState state, Blackhole blackhole) {
		super.attributeHistogramComputation_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void priceFiltering_InMemory(SignalPriceFilteringState state, Blackhole blackhole) {
		super.priceFiltering_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void priceAndHierarchyFiltering_InMemory(SignalPriceAndHierarchyFilteringState state, Blackhole blackhole) {
		super.priceAndHierarchyFiltering_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void priceHistogramComputation_InMemory(SignalPriceHistogramState state, Blackhole blackhole) {
		super.priceHistogramComputation_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void facetFiltering_InMemory(SignalFacetFilteringState state, Blackhole blackhole) {
		super.facetFiltering_InMemory(state, blackhole);
	}

	@Override
	@Benchmark
	public void facetAndHierarchyFiltering_InMemory(SignalFacetAndHierarchyFilteringState state, Blackhole blackhole) {
		super.facetAndHierarchyFiltering_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void facetFilteringAndSummarizingCount_InMemory(SignalFacetFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetFilteringAndSummarizingCount_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void facetAndHierarchyFilteringAndSummarizingCount_InMemory(SignalFacetAndHierarchyFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingCount_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void facetAndHierarchyFilteringAndSummarizingImpact_InMemory(SignalFacetAndHierarchyFilteringAndSummarizingImpactState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingImpact_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void hierarchyStatisticsComputation_InMemory(SignalHierarchyStatisticsComputationState state, Blackhole blackhole) {
		super.hierarchyStatisticsComputation_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void syntheticTest_InMemory(SignalSyntheticTestState state, Blackhole blackhole) {
		super.syntheticTest_InMemory(state, blackhole);
	}

}
