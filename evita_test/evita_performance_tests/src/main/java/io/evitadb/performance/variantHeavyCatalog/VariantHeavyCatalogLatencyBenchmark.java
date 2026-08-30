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

package io.evitadb.performance.variantHeavyCatalog;

import io.evitadb.performance.setup.BenchmarkForkArgs;
import io.evitadb.performance.variantHeavyCatalog.state.*;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * This class runs all tests in {@link VariantHeavyCatalogBenchmark} in latency mode measurement.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@BenchmarkMode({Mode.AverageTime})
@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(
	jvmArgsAppend = {
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_LANG,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_LANG_INVOKE,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_MATH,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_UTIL
	}
)
public class VariantHeavyCatalogLatencyBenchmark extends VariantHeavyCatalogBenchmark {

	@Override
	@Threads(1)
	@Benchmark
	public void bulkInsertThroughput_InMemory(VariantHeavyCatalogBulkWriteState state) {
		super.bulkInsertThroughput_InMemory(state);
	}

	@Override
	@Threads(1)
	@Benchmark
	public void transactionalUpsertThroughput_InMemory(VariantHeavyCatalogTransactionalWriteState state) {
		super.transactionalUpsertThroughput_InMemory(state);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void singleEntityRead_InMemory(VariantHeavyCatalogSingleReadState state, Blackhole blackhole) {
		super.singleEntityRead_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void paginatedEntityRead_InMemory(VariantHeavyCatalogPageReadState state, Blackhole blackhole) {
		super.paginatedEntityRead_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void attributeFiltering_InMemory(VariantHeavyCatalogAttributeFilteringState state, Blackhole blackhole) {
		super.attributeFiltering_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void attributeAndHierarchyFiltering_InMemory(VariantHeavyCatalogAttributeAndHierarchyFilteringState state, Blackhole blackhole) {
		super.attributeAndHierarchyFiltering_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void attributeHistogramComputation_InMemory(VariantHeavyCatalogAttributeHistogramState state, Blackhole blackhole) {
		super.attributeHistogramComputation_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void priceFiltering_InMemory(VariantHeavyCatalogPriceFilteringState state, Blackhole blackhole) {
		super.priceFiltering_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void priceAndHierarchyFiltering_InMemory(VariantHeavyCatalogPriceAndHierarchyFilteringState state, Blackhole blackhole) {
		super.priceAndHierarchyFiltering_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void priceHistogramComputation_InMemory(VariantHeavyCatalogPriceHistogramState state, Blackhole blackhole) {
		super.priceHistogramComputation_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void facetFiltering_InMemory(VariantHeavyCatalogFacetFilteringState state, Blackhole blackhole) {
		super.facetFiltering_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void facetAndHierarchyFiltering_InMemory(VariantHeavyCatalogFacetAndHierarchyFilteringState state, Blackhole blackhole) {
		super.facetAndHierarchyFiltering_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void facetFilteringAndSummarizingCount_InMemory(VariantHeavyCatalogFacetFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetFilteringAndSummarizingCount_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void facetAndHierarchyFilteringAndSummarizingCount_InMemory(VariantHeavyCatalogFacetAndHierarchyFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingCount_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void facetAndHierarchyFilteringAndSummarizingImpact_InMemory(VariantHeavyCatalogFacetAndHierarchyFilteringAndSummarizingImpactState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingImpact_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void hierarchyStatisticsComputation_InMemory(VariantHeavyCatalogHierarchyStatisticsComputationState state, Blackhole blackhole) {
		super.hierarchyStatisticsComputation_InMemory(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void syntheticTest_InMemory(VariantHeavyCatalogSyntheticTestState state, Blackhole blackhole) {
		super.syntheticTest_InMemory(state, blackhole);
	}

}
