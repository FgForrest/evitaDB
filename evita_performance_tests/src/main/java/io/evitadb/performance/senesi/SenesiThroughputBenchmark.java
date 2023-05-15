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

package io.evitadb.performance.senesi;

import io.evitadb.performance.senesi.state.*;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.Blackhole;

/**
 * This class runs all tests in {@link SenesiBenchmark} in throughput mode measurement.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@BenchmarkMode({Mode.Throughput})
public class SenesiThroughputBenchmark extends SenesiBenchmark {

	@Override
	public void bulkInsertThroughput(SenesiBulkWriteState state) {
		super.bulkInsertThroughput(state);
	}

	@Override
	public void transactionalUpsertThroughput(SenesiTransactionalWriteState state) {
		super.transactionalUpsertThroughput(state);
	}

	@Override
	public void singleEntityRead(SenesiSingleReadState state, Blackhole blackhole) {
		super.singleEntityRead(state, blackhole);
	}

	@Override
	public void paginatedEntityRead(SenesiPageReadState state, Blackhole blackhole) {
		super.paginatedEntityRead(state, blackhole);
	}

	@Override
	public void attributeFiltering(SenesiAttributeFilteringState state, Blackhole blackhole) {
		super.attributeFiltering(state, blackhole);
	}

	@Override
	public void attributeAndHierarchyFiltering(SenesiAttributeAndHierarchyFilteringState state, Blackhole blackhole) {
		super.attributeAndHierarchyFiltering(state, blackhole);
	}

	@Override
	public void attributeHistogramComputation(SenesiAttributeHistogramState state, Blackhole blackhole) {
		super.attributeHistogramComputation(state, blackhole);
	}

	@Override
	public void priceFiltering(SenesiPriceFilteringState state, Blackhole blackhole) {
		super.priceFiltering(state, blackhole);
	}

	@Override
	public void priceAndHierarchyFiltering(SenesiPriceAndHierarchyFilteringState state, Blackhole blackhole) {
		super.priceAndHierarchyFiltering(state, blackhole);
	}

	@Override
	public void priceHistogramComputation(SenesiPriceHistogramState state, Blackhole blackhole) {
		super.priceHistogramComputation(state, blackhole);
	}

	@Override
	public void facetFiltering(SenesiFacetFilteringState state, Blackhole blackhole) {
		super.facetFiltering(state, blackhole);
	}

	@Override
	public void facetAndHierarchyFiltering(SenesiFacetAndHierarchyFilteringState state, Blackhole blackhole) {
		super.facetAndHierarchyFiltering(state, blackhole);
	}

	@Override
	public void facetFilteringAndSummarizingCount(SenesiFacetFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetFilteringAndSummarizingCount(state, blackhole);
	}

	@Override
	public void facetAndHierarchyFilteringAndSummarizingCount(SenesiFacetAndHierarchyFilteringAndSummarizingCountState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingCount(state, blackhole);
	}

	@Override
	public void facetAndHierarchyFilteringAndSummarizingImpact(SenesiFacetAndHierarchyFilteringAndSummarizingImpactState state, Blackhole blackhole) {
		super.facetAndHierarchyFilteringAndSummarizingImpact(state, blackhole);
	}

	@Override
	public void hierarchyStatisticsComputation(SenesiHierarchyStatisticsComputationState state, Blackhole blackhole) {
		super.hierarchyStatisticsComputation(state, blackhole);
	}

	@Override
	public void syntheticTest(SenesiSyntheticTestState state, Blackhole blackhole) {
		super.syntheticTest(state, blackhole);
	}

}
