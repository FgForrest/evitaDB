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
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Timeout;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * This benchmarks contains test that use "artificial" (i.e. random, non-real) data for performance measurements.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Timeout(time = 1, timeUnit = TimeUnit.HOURS)
public abstract class RestArtificialEntitiesBenchmark {

	/*
		RANDOM SINGLE ENTITY READ
	 */

	/**
	 * This test spins an empty DB inserts there a one hundred thousands products, switches it to the transactional mode
	 * and starts to randomly read one entity with different requirements. During setup bunch of brands, categories,
	 * price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures random read on single entity data.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void singleEntityRead(RestArtificialFullDatabaseBenchmarkState benchmarkState, RestArtificialSingleReadState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

	/*
		RANDOM PAGE ENTITY READ
	 */

	/**
	 * This test spins an empty DB inserts there a one hundred thousands products, switches it to the transactional mode
	 * and starts to randomly read page of entities with different requirements. During setup bunch of brands, categories,
	 * price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures random read on page of entity data.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void paginatedEntityRead(RestArtificialFullDatabaseBenchmarkState benchmarkState, RestArtificialPageReadState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

	/*
		ATTRIBUTE FILTERING / SORTING
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the artificial database, switches it to the transactional mode
	 * and starts to randomly read page of entities with simple multiple attribute filter and sort. During setup bunch
	 * of brands, categories, price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures filtering and ordering by various attributes in the dataset.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void attributeFiltering(RestArtificialAttributeBenchmarkState benchmarkState, RestArtificialAttributeFilteringState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

	/*
		ATTRIBUTE AND HIERARCHY FILTERING / SORTING
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the artificial database, switches it to the transactional mode
	 * and starts to randomly read page of entities with simple multiple attribute filter and sort as well as hierarchy
	 * filter that targets single category (optionally with subtree). During setup bunch of brands, categories, price
	 * lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures filtering and ordering by various attributes and hierarchy placement in the dataset.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void attributeAndHierarchyFiltering(RestArtificialAttributeBenchmarkState benchmarkState, RestArtificialAttributeAndHierarchyFilteringState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

	/*
		ATTRIBUTE HISTOGRAM COMPUTATION
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the artificial database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized attribute filter and hierarchy placement data and attribute
	 * histogram computation. During setup bunch of brands, categories, attribute lists and stores are created so that
	 * they could be referenced in products.
	 *
	 * Test measures attribute histogram DTO computation in the dataset.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void attributeHistogramComputation(RestArtificialAttributeBenchmarkState benchmarkState, RestArtificialAttributeHistogramState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

	/*
		PRICE FILTERING / SORTING
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the artificial database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized price filter and sort. During setup bunch
	 * of brands, categories, price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures filtering and ordering by price data in the dataset.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void priceFiltering(RestArtificialPriceBenchmarkState benchmarkState, RestArtificialPriceFilteringState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

	/*
		PRICE AND HIERARCHY FILTERING / SORTING
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the artificial database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized price and hierarchy filter and sort. During setup bunch
	 * of brands, categories, price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures filtering and ordering by price and hierarchy placement data in the dataset.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void priceAndHierarchyFiltering(RestArtificialPriceBenchmarkState benchmarkState, RestArtificialPriceAndHierarchyFilteringState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

	/*
		PRICE HISTOGRAM COMPUTATION
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the artificial database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized price filter and hierarchy placement data and price
	 * histogram computation. During setup bunch of brands, categories, price lists and stores are created so that
	 * they could be referenced in products.
	 *
	 * Test measures price histogram DTO computation in the dataset.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void priceHistogramComputation(RestArtificialPriceBenchmarkState benchmarkState, RestArtificialPriceHistogramState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

	/*
		FACET FILTERING
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the artificial database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized facet filter. During setup bunch
	 * of brands, categories, price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures filtering by facet references in the dataset.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void facetFiltering(RestArtificialFacetBenchmarkState benchmarkState, RestArtificialFacetFilteringState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

	/*
		FACET AND HIERARCHY FILTERING
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the artificial database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized facet filter. During setup bunch
	 * of brands, categories, price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures filtering by facet references and hierarchical placement in the dataset.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void facetAndHierarchyFiltering(RestArtificialFacetBenchmarkState benchmarkState, RestArtificialFacetAndHierarchyFilteringState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

	/*
		FACET FILTERING AND SUMMARIZING
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the artificial database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized facet filter and facet summary (count) computation.
	 * During setup bunch of brands, categories, price lists and stores are created so that they could be referenced
	 * in products.
	 *
	 * Test measures filtering by facet references and computing summary for the rest in the dataset. It also randomizes
	 * the relation among the facet groups of the facets.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void facetFilteringAndSummarizingCount(RestArtificialFacetBenchmarkState benchmarkState, RestArtificialFacetFilteringAndSummarizingCountState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

	/*
		FACET FILTERING AND SUMMARIZING IN HIERARCHY
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the artificial database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized facet filter  and hierarchy placement data and facet
	 * summary (count) computation. During setup bunch of brands, categories, price lists and stores are created so that
	 * they could be referenced in products.
	 *
	 * Test measures filtering by facet references and hierarchy placement data and computing summary for the rest
	 * in the dataset. It also randomizes the relation among the facet groups of the facets.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void facetAndHierarchyFilteringAndSummarizingCount(RestArtificialFacetBenchmarkState benchmarkState, RestArtificialFacetAndHierarchyFilteringAndSummarizingCountState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

	/*
		FACET FILTERING AND SUMMARIZING IMPACT IN HIERARCHY
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the artificial database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized facet filter  and hierarchy placement data and facet
	 * summary (impact) computation. During setup bunch of brands, categories, price lists and stores are created so that
	 * they could be referenced in products.
	 *
	 * Test measures filtering by facet references and hierarchy placement data and computing summary for the rest
	 * in the dataset. It also randomizes the relation among the facet groups of the facets.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void facetAndHierarchyFilteringAndSummarizingImpact(RestArtificialFacetBenchmarkState benchmarkState, RestArtificialFacetAndHierarchyFilteringAndSummarizingImpactState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

	/*
		PARENTS COMPUTATION
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the artificial database, switches it to the
	 * transactional mode and starts to randomly read page of entities with hierarchy placement data and parents DTO
	 * computation. During setup bunch of brands, categories, price lists and stores are created so that
	 * they could be referenced in products.
	 *
	 * Test measures parents DTO computation in the dataset.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void parentsComputation(RestArtificialHierarchyBenchmarkState benchmarkState, RestArtificialParentsComputationState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

	/*
		HIERARCHY STATISTICS COMPUTATION
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the artificial database, switches it to the
	 * transactional mode and starts to randomly read page of entities with hierarchy placement data and hierarchy
	 * statistics DTO computation. During setup bunch of brands, categories, price lists and stores are created so that
	 * they could be referenced in products.
	 *
	 * Test measures hierarchy statistics DTO computation in the dataset.
	 */
	@Benchmark
	@Measurement(time = 1, timeUnit = TimeUnit.MINUTES)
	@Threads(Threads.MAX)
	public void hierarchyStatisticsComputation(RestArtificialHierarchyBenchmarkState benchmarkState, RestArtificialHierarchyStatisticsComputationState state, Blackhole blackhole) {
		blackhole.consume(
			benchmarkState.getSession().call(state.getResource(), state.getRequestBody())
		);
	}

}
