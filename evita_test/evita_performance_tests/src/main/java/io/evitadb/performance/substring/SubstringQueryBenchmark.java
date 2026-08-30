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

package io.evitadb.performance.substring;

import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.performance.setup.BenchmarkForkArgs;
import io.evitadb.performance.substring.state.SubstringQueryState;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;

import java.util.concurrent.TimeUnit;

/**
 * **The end-to-end A/B of the trigram substring index.** One `attributeContains` query, executed through the public
 * query API against a real embedded evitaDB, measured once with the attribute declaring
 * `FilterIndexCapability.SUBSTRING` and once without it - same corpus, same primary keys, same query, two schemas.
 *
 * # What the matrix is for
 *
 * `TrigramSubstringSearch` carries two constants derived from a spike rather than from the engine:
 * `MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT` (256) and `REQUIRED_NARROWING_FACTOR` (4). This benchmark exists so
 * they can be checked against real end-to-end numbers, which is why the axes deliberately bracket them rather than
 * staying in the comfortable region:
 *
 * - `entityCount` runs `100, 256, 1000, 10000, 100000`; the first two sit either side of the distinct-value floor, and
 *   because the corpus is all-distinct, entity count and distinct value count are the same number;
 * - `patternClass` runs from a marker planted in 15% of the values, through one planted in exactly 25% - the widest
 *   candidate set the selectivity gate admits at all - down to a handful and to none.
 *
 * A cell where the accelerated path *declines* is not a hole in the matrix but a measurement of the decline: the two
 * arms then execute the same scan and the ratio is the price of asking. Which cells those are is printed by the
 * fixture and carried in `SubstringCatalogFixture.PatternProfile#accelerated()`.
 *
 * # Reading a result
 *
 * The score of a cell is only meaningful next to the score of the same cell on the other arm; the interesting
 * quantity is always `SCAN / TRIGRAM`. The formula cache is disabled, so both arms plan and compute every invocation
 * in full - see `SubstringCacheRepeatBenchmark` for the question the cache answers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(
	value = 1,
	jvmArgsAppend = {
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_LANG,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_LANG_INVOKE,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_MATH,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_UTIL
	}
)
public class SubstringQueryBenchmark {

	/**
	 * One `attributeContains` filter, returning primary keys only, through a read-only session on the public API.
	 *
	 * Single-threaded on purpose: the question is what one query costs on each arm, and a contended run would fold
	 * the engine's concurrency behaviour into a number that is meant to be a ratio of two execution costs.
	 *
	 * @param state     the cell being measured
	 * @param blackhole sink preventing the response from being optimised away
	 */
	@Benchmark
	@Threads(1)
	public void attributeContains(@Nonnull SubstringQueryState state, @Nonnull Blackhole blackhole) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityReference.class)
		);
	}

}
