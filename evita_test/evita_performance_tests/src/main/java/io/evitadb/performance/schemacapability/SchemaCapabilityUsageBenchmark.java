/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.performance.schemacapability;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.performance.schemacapability.state.SchemaCapabilityUsageState;
import io.evitadb.performance.schemacapability.state.SchemaCapabilityUsageState.ThreadState;
import io.evitadb.performance.setup.BenchmarkForkArgs;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeBetween;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.performance.schemacapability.state.SchemaCapabilityUsageState.ATTRIBUTE_CODE;
import static io.evitadb.performance.schemacapability.state.SchemaCapabilityUsageState.ATTRIBUTE_NAME;
import static io.evitadb.performance.schemacapability.state.SchemaCapabilityUsageState.ATTRIBUTE_QUANTITY;
import static io.evitadb.performance.schemacapability.state.SchemaCapabilityUsageState.PRODUCT;

/**
 * The before/after gate of the schema-capability usage counters: two throughput measurements run once on the commit
 * preceding the instrumentation and once on the instrumented branch, whose comparison decides whether the counters
 * may stay as designed. See {@link SchemaCapabilityUsageState} for why the two source files reference nothing the
 * baseline commit lacks, and why the fixture is deliberately small and cache-less.
 *
 * Both measurements run on every hardware thread, because the design's only conceivable regression is *contention* -
 * the counting itself is a handful of nanoseconds, but all threads recording into shared holders could serialise on
 * them if the holders were built wrong. A single-threaded run could not show that failure at all.
 *
 * - {@link #representativeAttributeFiltering} is the workload-shaped control: a between-filter over one attribute
 *   ordered by another, every invocation asking for a different value window. Threads spread over a hundred windows
 *   and two elements' counters.
 * - {@link #contendedSingleAttributeLookup} is the adversarial case the design must survive: every thread, every
 *   invocation, one unique attribute - so every recording in the whole run lands on the **same counter holder**,
 *   the maximum contention the schema-capability granularity can produce. The lookup itself is the cheapest query
 *   the engine can serve, which keeps the physical work from masking a contention stall.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(
	value = 2,
	jvmArgsAppend = {
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_LANG,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_LANG_INVOKE,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_MATH,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_UTIL
	}
)
public class SchemaCapabilityUsageBenchmark {

	/**
	 * Representative filter-and-sort query: a ten-wide window over the filterable quantity attribute, ordered by the
	 * sortable name attribute, first page of twenty. Each invocation records a `FILTERABLE` request on one attribute
	 * and a `SORTABLE` request on another.
	 *
	 * @param state     the shared fixture
	 * @param thread    the invoking thread's session and cursor
	 * @param blackhole sink preventing the response from being optimised away
	 */
	@Benchmark
	@Threads(Threads.MAX)
	public void representativeAttributeFiltering(
		@Nonnull SchemaCapabilityUsageState state,
		@Nonnull ThreadState thread,
		@Nonnull Blackhole blackhole
	) {
		final int quantityFloor = thread.nextQuantityFloor();
		blackhole.consume(
			thread.getSession().query(
				query(
					collection(PRODUCT),
					filterBy(attributeBetween(ATTRIBUTE_QUANTITY, quantityFloor, quantityFloor + 9)),
					orderBy(attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)),
					require(page(1, 20))
				),
				EntityReference.class
			)
		);
	}

	/**
	 * The adversarial case: every thread filters by the same unique attribute, so every invocation in the whole run
	 * records into one shared counter holder. The query is a single-entity unique-index lookup - the least physical
	 * work per recording the engine can do, and therefore the highest sensitivity to a contention stall.
	 *
	 * @param state     the shared fixture
	 * @param thread    the invoking thread's session and cursor
	 * @param blackhole sink preventing the response from being optimised away
	 */
	@Benchmark
	@Threads(Threads.MAX)
	public void contendedSingleAttributeLookup(
		@Nonnull SchemaCapabilityUsageState state,
		@Nonnull ThreadState thread,
		@Nonnull Blackhole blackhole
	) {
		blackhole.consume(
			thread.getSession().query(
				query(
					collection(PRODUCT),
					filterBy(attributeEquals(ATTRIBUTE_CODE, thread.nextCode(state))),
					require(page(1, 1))
				),
				EntityReference.class
			)
		);
	}

}
