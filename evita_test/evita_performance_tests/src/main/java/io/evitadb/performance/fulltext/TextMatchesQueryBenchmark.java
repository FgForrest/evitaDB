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

package io.evitadb.performance.fulltext;

import io.evitadb.performance.fulltext.state.TextMatchesQueryState;
import io.evitadb.performance.setup.BenchmarkForkArgs;
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
 * **A `textMatches` query through the real planner**, measured against the phase-1 budget the fulltext ADR records:
 * `2.4 ns × candidates + 32 ns × summed postings + 21 ns × matched documents`, which P1 fitted on structures built
 * outside the engine. This benchmark asks the same question of the engine itself.
 *
 * # The two methods
 *
 * - {@link #textMatches} filters only: the planner analyzes the text, expands the tokens, and computes the strict match
 *   set, then pages it in primary-key order. No score is computed.
 * - {@link #textMatchesByRelevance} adds `orderBy(relevance())`, which runs the phase-1 scorer over the match set and
 *   keeps the top of it.
 *
 * Their difference is the scorer's share, which is what the P1 model prices; the plain method's time is the part P1
 * never measured. Read `FulltextQueryProfile` before comparing either against the model: on this path the scorer's
 * candidates are the match set, not the whole corpus.
 *
 * # The matrix
 *
 * Query length (`tokenCount` 1-3) times the words' frequency class (`RARE`, `MEDIUM`, `HIGH`, the P1 bounds). Each cell
 * runs a pool of up to 32 corpus-derived queries round-robin; the cell's table of per-query medians is printed when
 * its trial ends.
 *
 * With a census `queryFile`, cells instead select one `cohort` (all kinds, first `poolSize` occurrences in file order,
 * default 64). Word count and frequency do not classify these queries: pass `-p tokenCount=1 -p frequencyClass=RARE`
 * to collapse those axes. Other values are refused to prevent duplicate cells. Without a file, the existing 32-query
 * corpus-derived pools and 1-3 × RARE/MEDIUM/HIGH matrix remain in use; `cohort` and `poolSize` are ignored.
 *
 * # Running it
 *
 * Harness check, on the synthetic catalog (numbers not worth quoting):
 *
 * ```shell
 * java -jar evita_test/evita_performance_tests/target/benchmarks.jar TextMatchesQueryBenchmark -wi 1 -i 1 -w 1s -r 1s
 * ```
 *
 * A measurement, on a quiet machine and on a catalog rebuilt with its fields searchable; heap and fork count come from
 * the command line, and a single fork on a shared machine is not evidence:
 *
 * ```shell
 * java -jar benchmarks.jar TextMatchesQueryBenchmark -f 3 -jvmArgsAppend "-Xms24g -Xmx24g" \
 *   -p storageDirectory=&lt;directory holding the catalog folder&gt; -p catalogName=&lt;catalog&gt; \
 *   -p entityType=&lt;collection&gt; -p locale=cs -rf json -rff result.json
 * ```
 *
 * Expanded census queries on a rebuilt e-commerce storage copy, comparing `minLengthTwoTypos` thresholds in one jar:
 *
 * ```shell
 * opens="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED"
 * opens="$opens --add-opens java.base/java.math=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED"
 * java -jar benchmarks.jar 'TextMatchesQueryBenchmark\.textMatches(ByRelevance)?$' \
 *   -f 3 -jvmArgsAppend "-Xms24g -Xmx24g $opens" \
 *   -p storageDirectory="&lt;storage copy&gt;" -p catalogName="&lt;catalog&gt;" -p entityType=Product -p locale=cs \
 *   -p queryFile="&lt;queries.tsv&gt;" -p cohort=by-token,by-vocabulary,name-phrases,wide,fragments \
 *   -p tokenCount=1 -p frequencyClass=RARE -p poolSize=64 -p minLengthTwoTypos=8,9,10 \
 *   -rf json -rff result.json
 * ```
 *
 * Add `head` to `cohort` only when that cohort exists in the census file. `minLengthTwoTypos` defaults to 8 and is
 * applied through the instance's `FulltextOptions`, including when opening stored catalogs. The per-leg cap remains
 * the once-per-JVM system property `evita.fulltext.termsPerLeg` (default 100); to vary it, append
 * `-Devita.fulltext.termsPerLeg=&lt;cap&gt;` inside `-jvmArgsAppend`, so every fork receives it.
 *
 * Query-file setup executes each query once for the selected method: no exact oracle, just `totalRecordCount` and
 * SHA-256 of page 1's primary keys in returned order (20 ranked, or plain order). Compare `queryHash`,
 * `totalRecordCount` and `pageHash` across every fork and build of the same cell before comparing times. Different
 * thresholds may intentionally return different results; compare identities within a threshold. Timing lines retain
 * the existing field order. For compatibility with the night parser, their legacy `text="..."` slot contains only
 * the census SHA-256 identity; an explicit `queryHash` plus cohort/kind and result identity follow it.
 * In this mode `tokens=1` and `RARE` are cell selectors only; `postings=0` and `modelUs=0` mean unavailable, not a
 * measured expansion cost. `matched` is the expanded total. No decoded text is printed. Existing timing parsers
 * remain usable; drivers must additionally compare the explicit query/result identities before comparing builds.
 * Use one cohort/threshold per invocation with older scripts, whose cell keys contain only word count and frequency.
 * In corpus-derived mode posting/match/model fields describe the exact core, which expansion may enlarge.
 *
 * Each invocation also reads `System.nanoTime()` twice for the per-query table, which adds tens of nanoseconds to
 * queries that take tens of microseconds and more.
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
public class TextMatchesQueryBenchmark {

	/**
	 * One `textMatches` filter without ordering, returning a page of entity references through a read-only session.
	 *
	 * Single-threaded: the budget is a statement about one query on one thread.
	 *
	 * @param state     the cell being measured
	 * @param blackhole sink preventing the response from being optimised away
	 */
	@Benchmark
	@Threads(1)
	public void textMatches(@Nonnull TextMatchesQueryState state, @Nonnull Blackhole blackhole) {
		final int index = state.nextQuery();
		final long start = System.nanoTime();
		blackhole.consume(state.executeQuery(index, false));
		state.record(index, System.nanoTime() - start);
	}

	/**
	 * The same filter ordered by `relevance()`, which runs the phase-1 scorer over the match set.
	 *
	 * @param state     the cell being measured
	 * @param blackhole sink preventing the response from being optimised away
	 */
	@Benchmark
	@Threads(1)
	public void textMatchesByRelevance(@Nonnull TextMatchesQueryState state, @Nonnull Blackhole blackhole) {
		final int index = state.nextQuery();
		final long start = System.nanoTime();
		blackhole.consume(state.executeQuery(index, true));
		state.record(index, System.nanoTime() - start);
	}

}
