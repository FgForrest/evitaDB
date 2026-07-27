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

package io.evitadb.performance.committhroughput;

import io.evitadb.performance.committhroughput.state.CommitThroughputBenchmarkState;
import io.evitadb.performance.setup.BenchmarkForkArgs;
import io.evitadb.performance.setup.EvitaCatalogSetup;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Measures **commit** throughput: how many small transactions the write pipeline can carry per second.
 *
 * Each invocation opens a session, writes one generated product and commits, so every invocation walks
 * the full pipeline - conflict resolution, WAL append (one device sync per transaction) and trunk
 * incorporation. That is deliberately different from the artificial write benchmarks, which keep one
 * session open for an entire iteration and therefore commit once per iteration; those measure how fast
 * mutations enter the transactional memory layer, not what a commit costs.
 *
 * Two knobs make this benchmark useful for tuning rather than just reporting:
 *
 * - `-Devita.benchmark.flushFrequencyInMillis=N` bounds how long trunk incorporation greedily drains
 *   the WAL before cutting a catalog version. Sweeping it maps the trade between commit throughput
 *   (fewer, larger merges) and how long a client waits for its changes to become visible.
 * - `-t N` (JMH) sets the number of concurrent writers. This matters more than it looks: the greedy
 *   batching budget can only bite once writers outrun trunk incorporation and a backlog forms, so a
 *   single-writer run will show the flush-frequency sweep as a flat line no matter how it is set.
 *
 * Example sweep:
 *
 * ```
 * java -jar target/evita-performance-tests.jar CommitThroughputBenchmark -t 8 \
 *   -Devita.benchmark.flushFrequencyInMillis=1000
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 3, time = 10)
@Fork(
	value = 1,
	// see BenchmarkForkArgs for why a benchmark booting Evita has to declare these itself
	jvmArgsAppend = {
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_LANG,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_LANG_INVOKE,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_MATH,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_UTIL
	}
)
public class CommitThroughputBenchmark implements EvitaCatalogSetup {

	/**
	 * Commits one single-entity transaction per invocation.
	 *
	 * `updateCatalog` opens the session, runs the body and commits it, waiting for the configured
	 * commit behaviour before returning - so the measured operation is a whole durable commit rather
	 * than an enqueue. The body is a block lambda on purpose: a void-bodied expression lambda is
	 * ambiguous against the value-returning `updateCatalog` overload and fails to compile.
	 *
	 * @param state the shared transactional catalog and product source
	 */
	@Benchmark
	public void commitSingleEntityTransaction(CommitThroughputBenchmarkState state) {
		state.getEvita().updateCatalog(
			state.getCatalogNameForBenchmark(),
			session -> {
				session.upsertEntity(state.nextProduct());
			}
		);
		state.recordCommit();
	}

}
