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
import io.evitadb.performance.substring.state.SubstringCacheRepeatState;
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
 * **Does the formula cache earn the eager fold?**
 *
 * The translator folds the matched buckets eagerly rather than deferring them behind a supplier, and the reason on
 * record is that an eagerly folded result is a `CacheableFormula` while a deferred one is not. That reason is only
 * worth what evitaDB's formula cache actually delivers for these results - an assumption which, until this benchmark,
 * had never been measured.
 *
 * The measured operation is one identical `attributeContains` query, repeated. The steady-state latency with the cache
 * enabled, divided by the same figure with it disabled, is the payoff the eager choice buys.
 *
 * # The ratio alone is not an answer
 *
 * A ratio of 1.0 has two readings - *the cache does not help here* and *the formula never entered the cache* - and
 * they lead to opposite conclusions about the eager fold. `SubstringCacheRepeatState` therefore primes the cache
 * before measurement begins and prints `CacheAdmissionProbe`'s counters twice, so every score comes with the record
 * count, the initialised-record count and the hit count that produced it. A cell whose warning line says nothing was
 * admitted must not be read as evidence about the cache's usefulness.
 *
 * Note the shipped complexity floor (`minimalComplexityThreshold`, 10 000) applies unchanged: a narrow pattern's
 * folded result is expected to be too cheap to be worth caching, and that is an answer about the eager choice rather
 * than a defect in the measurement.
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
public class SubstringCacheRepeatBenchmark {

	/**
	 * The same `attributeContains` query, over and over - the only workload a formula cache can possibly help.
	 *
	 * @param state     the cell being measured
	 * @param blackhole sink preventing the response from being optimised away
	 */
	@Benchmark
	@Threads(1)
	public void repeatedAttributeContains(
		@Nonnull SubstringCacheRepeatState state,
		@Nonnull Blackhole blackhole
	) {
		blackhole.consume(
			state.getSession().query(state.getQuery(), EntityReference.class)
		);
	}

}
