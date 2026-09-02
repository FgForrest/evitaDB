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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.invertedIndex.InvertedIndex.MatchedBuckets;
import io.evitadb.index.trigram.TrigramSubstringSearch;
import io.evitadb.performance.setup.BenchmarkForkArgs;
import io.evitadb.performance.substring.state.SubstringEagerFoldState;
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
 * **The price of the eager fold**, which is the maximum a deferred fold could ever have saved.
 *
 * `AbstractAttributeStringSearchTranslator` marks one line as the sole place presupposing eager evaluation: having
 * matched the buckets, it folds them into a `Formula` immediately rather than handing back a supplier that would fold
 * them only if the plan actually asked. The rejected alternative can save exactly one thing - that fold - so the
 * delta between the two benchmarks here bounds it from above:
 *
 * - {@link #matchOnly} runs `TrigramSubstringSearch#match` and stops at the buckets;
 * - {@link #matchAndFold} runs the same call, folds the buckets through `InvertedIndex#toFormula`, and then actually
 *   computes the resulting bitmap.
 *
 * The fold is followed by a `compute()` deliberately. `toFormula` merely assembles an `OrFormula` over the matched
 * record sets, so timing the assembly alone would price a handful of array copies and hide the disjunction the eager
 * choice actually commits to. What a deferred fold defers is the *whole* of it.
 *
 * Both methods build a fresh formula per invocation, so nothing is memoised between them.
 *
 * # What this measurement is, precisely
 *
 * Engine-level, not query-level: the `TrigramIndex` and `InvertedIndex` are the real structures of a catalog that was
 * written through the public API and switched to its transactional state, but the call being timed is the internal one
 * the translator makes rather than a query. That is the only way to see the two halves separately - the query API
 * always runs both.
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
public class SubstringEagerFoldBenchmark {

	/**
	 * Candidate generation and exact verification, stopping at the matched buckets - everything a deferred fold would
	 * still have to do eagerly.
	 *
	 * @param state     the cell being measured
	 * @param blackhole sink preventing the buckets from being optimised away
	 */
	@Benchmark
	@Threads(1)
	public void matchOnly(@Nonnull SubstringEagerFoldState state, @Nonnull Blackhole blackhole) {
		blackhole.consume(
			TrigramSubstringSearch.match(
				state.getTrigramIndex(), state.getSharedValueTree(),
				state.getPattern(), state.getExactPredicate()
			)
		);
	}

	/**
	 * The same work followed by the eager fold the translator performs today, computed through to its bitmap.
	 *
	 * @param state     the cell being measured
	 * @param blackhole sink preventing the folded result from being optimised away
	 */
	@Benchmark
	@Threads(1)
	public void matchAndFold(@Nonnull SubstringEagerFoldState state, @Nonnull Blackhole blackhole) {
		final MatchedBuckets matched = TrigramSubstringSearch.match(
			state.getTrigramIndex(), state.getSharedValueTree(),
			state.getPattern(), state.getExactPredicate()
		);
		// `match` cannot decline here - the state's setup refused the trial otherwise - so the fold is always reached
		final Formula folded = state.getSharedValueTree().toFormula(matched, state.getVersionIds());
		blackhole.consume(folded.compute());
	}

}
