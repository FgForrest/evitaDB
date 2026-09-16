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

package io.evitadb.performance.priceHeavyCatalog;

import io.evitadb.performance.priceHeavyCatalog.state.*;
import io.evitadb.performance.setup.BenchmarkForkArgs;
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
 * This class runs all tests in {@link PriceHeavyCatalogBenchmark} in latency mode measurement.
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
public class PriceHeavyCatalogLatencyBenchmark extends PriceHeavyCatalogBenchmark {

	@Override
	@Threads(1)
	@Benchmark
	public void bulkInsertThroughput(PriceHeavyCatalogBulkWriteState state) {
		super.bulkInsertThroughput(state);
	}

	@Override
	@Threads(1)
	@Benchmark
	public void transactionalUpsertThroughput(PriceHeavyCatalogTransactionalWriteState state) {
		super.transactionalUpsertThroughput(state);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void singleEntityRead(PriceHeavyCatalogSingleReadState state, Blackhole blackhole) {
		super.singleEntityRead(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void paginatedEntityRead(PriceHeavyCatalogPageReadState state, Blackhole blackhole) {
		super.paginatedEntityRead(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void attributeFiltering(PriceHeavyCatalogAttributeFilteringState state, Blackhole blackhole) {
		super.attributeFiltering(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void attributeAndHierarchyFiltering(PriceHeavyCatalogAttributeAndHierarchyFilteringState state, Blackhole blackhole) {
		super.attributeAndHierarchyFiltering(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void attributeHistogramComputation(PriceHeavyCatalogAttributeHistogramState state, Blackhole blackhole) {
		super.attributeHistogramComputation(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void priceFiltering(PriceHeavyCatalogPriceFilteringState state, Blackhole blackhole) {
		super.priceFiltering(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void priceAndHierarchyFiltering(PriceHeavyCatalogPriceAndHierarchyFilteringState state, Blackhole blackhole) {
		super.priceAndHierarchyFiltering(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void priceHistogramComputation(PriceHeavyCatalogPriceHistogramState state, Blackhole blackhole) {
		super.priceHistogramComputation(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void facetFiltering(PriceHeavyCatalogFacetFilteringState state, Blackhole blackhole) {
		super.facetFiltering(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void facetAndHierarchyFiltering(PriceHeavyCatalogFacetAndHierarchyFilteringState state, Blackhole blackhole) {
		super.facetAndHierarchyFiltering(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void facetFilteringAndSummarizingCount(PriceHeavyCatalogFacetFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetFilteringAndSummarizingCount(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void facetAndHierarchyFilteringAndSummarizingCount(PriceHeavyCatalogFacetAndHierarchyFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingCount(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void facetAndHierarchyFilteringAndSummarizingImpact(PriceHeavyCatalogFacetAndHierarchyFilteringAndSummarizingImpactState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingImpact(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void hierarchyStatisticsComputation(PriceHeavyCatalogHierarchyStatisticsComputationState state, Blackhole blackhole) {
		super.hierarchyStatisticsComputation(state, blackhole);
	}

	@Override
	@Threads(Threads.MAX)
	@Benchmark
	public void syntheticTest(PriceHeavyCatalogSyntheticTestState state, Blackhole blackhole) {
		super.syntheticTest(state, blackhole);
	}

}
