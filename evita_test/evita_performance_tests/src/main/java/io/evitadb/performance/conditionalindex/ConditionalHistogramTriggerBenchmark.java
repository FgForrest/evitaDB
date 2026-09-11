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

package io.evitadb.performance.conditionalindex;

import io.evitadb.performance.conditionalindex.state.ConditionalHistogramTriggerBenchmarkState;
import io.evitadb.performance.setup.BenchmarkForkArgs;
import lombok.extern.slf4j.Slf4j;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Measures how the cost of a cross-entity conditional-histogram trigger scales with the number of
 * contributions one mutation resolves, and what the reference-grained condition evaluation adds on top of the
 * owner-level one.
 *
 * The measured operation applies a prepared {@link io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation}
 * carrying one attribute write on a hierarchy root — prepared, so the score is the mutation-application path the
 * trigger runs in and not an entity fetch in front of it. Every owner beneath it holds
 * `fanOut` references carrying a conditional bucketed histogram, so one write re-evaluates `fanOut`
 * contributions per owner. The `granularity` parameter selects which of two condition shapes the schema
 * declares — see {@link ConditionalHistogramTriggerBenchmarkState} for why one binary can measure both.
 *
 * What the three series answer:
 *
 * - `ownerLevel` — one predicate, one filter evaluation per mutation regardless of fan-out. Its slope against
 *   `fanOut` is the index-write cost alone.
 * - `ownerLevelTwoPredicates` — two predicates, still one evaluation per mutation. Its distance from
 *   `ownerLevel` prices the extra predicate without any change of granularity.
 * - `referenceGrained` — two predicates, one evaluation per resolved contribution. Its distance from
 *   `ownerLevelTwoPredicates` is the price of the granularity alone.
 *
 * The middle series is also a check on the executor rather than only a control: it must stay flat against
 * `fanOut` like `ownerLevel`. If it slopes, the gate is sending owner-level conditions down the
 * per-contribution path, which is a defect the functional suite would not catch.
 *
 * Run (after building `benchmarks.jar` with the `full` Maven profile):
 * <pre>
 * java -Xmx8g \
 *   -cp evita_test/evita_performance_tests/target/benchmarks.jar \
 *   io.evitadb.performance.BenchmarkRunner ConditionalHistogramTriggerBenchmark \
 *   -f 3 -prof gc
 * </pre>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
// the annotation default keeps an exploratory run cheap; the ADR's published numbers come from the
// documented command above, which overrides it with `-f 3`
@Fork(
	value = 1,
	jvmArgsAppend = {
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_LANG,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_LANG_INVOKE,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_MATH,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_UTIL
	}
)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class ConditionalHistogramTriggerBenchmark {

	/**
	 * Writes the hierarchy root's attribute, firing the cross-entity trigger for every contribution beneath it.
	 *
	 * @param state the seeded catalog
	 * @return the written value, so the call cannot be eliminated
	 */
	@Benchmark
	public String reevaluateConditionOnParentAttributeChange(
		ConditionalHistogramTriggerBenchmarkState state
	) {
		return state.flipParentStatus();
	}
}
