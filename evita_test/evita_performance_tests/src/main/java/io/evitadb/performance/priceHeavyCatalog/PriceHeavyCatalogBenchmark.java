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

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.performance.client.state.ClientSyntheticTestState.QueryWithExpectedType;
import io.evitadb.performance.priceHeavyCatalog.state.*;
import org.openjdk.jmh.annotations.Timeout;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * This benchmark contains tests that use real anonymized data from a production B2B e-commerce catalog for
 * performance measurements. It is the largest corpus in the suite: ~117,000 products carrying ~3.4 million
 * prices (28 per entity on average), 32 attributes and 8 references per entity, plus a substantial amount of
 * associated data. It exercises the price-heavy end of the workload range.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Timeout(time = 1, timeUnit = TimeUnit.HOURS)
public abstract class PriceHeavyCatalogBenchmark {

	/*
		BULK INSERT
	*/

	/**
	 * This test spins an empty DB and starts inserting new products into it. During setup bunch of brands, categories,
	 * price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures bulk write speed on random data.
	 * Each iteration starts with empty data
	 */
	public void bulkInsertThroughput(PriceHeavyCatalogBulkWriteState state) {
		state.getSession().upsertEntity(state.getProduct());
	}

	/*
		TRANSACTIONAL UPSERT
	 */

	/**
	 * This test spins an empty DB inserts there a few thousands products, switches it to the transactional mode
	 * and starts to insert or update existing products in/to it. During setup bunch of brands, categories,
	 * price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures transactional write / overwrite speed on random data.
	 * Each iteration starts with database that already contains few thousands records.
	 */
	public void transactionalUpsertThroughput(PriceHeavyCatalogTransactionalWriteState state) {
		state.getSession().upsertEntity(state.getProduct());
	}

	/*
		RANDOM SINGLE ENTITY READ
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the PriceHeavyCatalog database, switches it to the transactional
	 * mode and starts to randomly read one entity with different requirements. During setup bunch of brands, categories,
	 * price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures random read on single entity data.
	 */
	public void singleEntityRead(PriceHeavyCatalogSingleReadState state, Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityContract.class)
		);
	}

	/*
		RANDOM PAGE ENTITY READ
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the PriceHeavyCatalog database, switches it to the transactional mode
	 * and starts to randomly read page of entities with different requirements. During setup bunch of brands, categories,
	 * price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures random read on page of entity data.
	 */
	public void paginatedEntityRead(PriceHeavyCatalogPageReadState state, Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityContract.class)
		);
	}

	/*
		ATTRIBUTE FILTERING / SORTING
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the PriceHeavyCatalog database, switches it to the transactional mode
	 * and starts to randomly read page of entities with simple multiple attribute filter and sort. During setup bunch
	 * of brands, categories, price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures filtering and ordering by various attributes in the dataset.
	 */
	public void attributeFiltering(PriceHeavyCatalogAttributeFilteringState state, Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityReferenceContract.class)
		);
	}

	/*
		ATTRIBUTE AND HIERARCHY FILTERING / SORTING
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the PriceHeavyCatalog database, switches it to the transactional mode
	 * and starts to randomly read page of entities with simple multiple attribute filter and hierarchy placement and sort.
	 * During setup bunch of brands, categories, price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures filtering and ordering by various attributes and hierarchy placement in the dataset.
	 */
	public void attributeAndHierarchyFiltering(PriceHeavyCatalogAttributeAndHierarchyFilteringState state, Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityReferenceContract.class)
		);
	}

	/*
		ATTRIBUTE HISTOGRAM COMPUTATION
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the client database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized attribute filter and hierarchy placement data and attribute
	 * histogram computation. During setup bunch of brands, categories, attribute lists and stores are created so that
	 * they could be referenced in products.
	 *
	 * Test measures attribute histogram DTO computation in the dataset.
	 */
	public void attributeHistogramComputation(PriceHeavyCatalogAttributeHistogramState state, Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityReferenceContract.class)
		);
	}

	/*
		PRICE FILTERING / SORTING
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the PriceHeavyCatalog database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized price filter and sort. During setup bunch
	 * of brands, categories, price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures filtering and ordering by price data in the dataset.
	 */
	public void priceFiltering(PriceHeavyCatalogPriceFilteringState state, Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityReferenceContract.class)
		);
	}

	/*
		PRICE AND HIERARCHY FILTERING / SORTING
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the PriceHeavyCatalog database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized price filter and hierarchy placement and sort. During
	 * setup bunch of brands, categories, price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures filtering and ordering by price and hierarchy placement data in the dataset.
	 */
	public void priceAndHierarchyFiltering(PriceHeavyCatalogPriceAndHierarchyFilteringState state, Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityReferenceContract.class)
		);
	}

	/*
		PRICE HISTOGRAM COMPUTATION
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the client database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized price filter and hierarchy placement data and price
	 * histogram computation. During setup bunch of brands, categories, price lists and stores are created so that
	 * they could be referenced in products.
	 *
	 * Test measures price histogram DTO computation in the dataset.
	 */
	public void priceHistogramComputation(PriceHeavyCatalogPriceHistogramState state, Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityReferenceContract.class)
		);
	}

	/*
		FACET FILTERING
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the price-heavy catalog, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized facet filter. During setup bunch
	 * of brands, categories, price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures filtering by facet references in the dataset.
	 */
	public void facetFiltering(PriceHeavyCatalogFacetFilteringState state, Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityReferenceContract.class)
		);
	}

	/*
		FACET AND HIERARCHY FILTERING
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the price-heavy catalog, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized facet filter. During setup bunch
	 * of brands, categories, price lists and stores are created so that they could be referenced in products.
	 *
	 * Test measures filtering by facet references and hierarchy placement in the dataset.
	 */
	public void facetAndHierarchyFiltering(PriceHeavyCatalogFacetAndHierarchyFilteringState state, Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityReferenceContract.class)
		);
	}

	/*
		FACET FILTERING AND SUMMARIZING
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the price-heavy catalog, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized facet filter and facet summary (count) computation.
	 * During setup bunch of brands, categories, price lists and stores are created so that they could be referenced
	 * in products.
	 *
	 * Test measures filtering by facet references and computing summary for the rest in the dataset. It also randomizes
	 * the relation among the facet groups of the facets.
	 */
	public void facetFilteringAndSummarizingCount(PriceHeavyCatalogFacetFilteringAndSummarizingCountState state, Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityReferenceContract.class)
		);
	}

	/*
		FACET FILTERING AND SUMMARIZING IN HIERARCHY
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the client database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized facet filter  and hierarchy placement data and facet
	 * summary (count) computation. During setup bunch of brands, categories, price lists and stores are created so that
	 * they could be referenced in products.
	 *
	 * Test measures filtering by facet references and hierarchy placement data and computing summary for the rest
	 * in the dataset. It also randomizes the relation among the facet groups of the facets.
	 */
	public void facetAndHierarchyFilteringAndSummarizingCount(PriceHeavyCatalogFacetAndHierarchyFilteringAndSummarizingCountState state, Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityReferenceContract.class)
		);
	}

	/*
		FACET FILTERING AND SUMMARIZING IMPACT IN HIERARCHY
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the client database, switches it to the transactional mode
	 * and starts to randomly read page of entities with randomized facet filter  and hierarchy placement data and facet
	 * summary (impact) computation. During setup bunch of brands, categories, price lists and stores are created so that
	 * they could be referenced in products.
	 *
	 * Test measures filtering by facet references and hierarchy placement data and computing summary for the rest
	 * in the dataset. It also randomizes the relation among the facet groups of the facets.
	 */
	public void facetAndHierarchyFilteringAndSummarizingImpact(PriceHeavyCatalogFacetAndHierarchyFilteringAndSummarizingImpactState state, Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityReferenceContract.class)
		);
	}

	/*
		HIERARCHY STATISTICS COMPUTATION
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the price-heavy catalog, switches it to the
	 * transactional mode and starts to randomly read page of entities with hierarchy placement data and hierarchy
	 * statistics DTO computation. During setup bunch of brands, categories, price lists and stores are created so that
	 * they could be referenced in products.
	 *
	 * Test measures hierarchy statistics DTO computation in the dataset.
	 */
	public void hierarchyStatisticsComputation(PriceHeavyCatalogHierarchyStatisticsComputationState state, Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityReferenceContract.class)
		);
	}

	/*
		SYNTHETIC TEST
	 */

	/**
	 * This test spins an empty DB inserts there full contents of the PriceHeavyCatalog database, switches it to the
	 * transactional mode and starts to execute queries recorded in production system.
	 *
	 * Test measures real-world traffic on the real-world dataset.
	 */
	public void syntheticTest(PriceHeavyCatalogSyntheticTestState state, Blackhole blackhole) {
		final QueryWithExpectedType queryWithExpectedType = state.getQueryWithExpectedType();
		blackhole.consume(
			state.getSession().query(queryWithExpectedType.getQuery(), queryWithExpectedType.getExpectedResult())
		);
	}

}
