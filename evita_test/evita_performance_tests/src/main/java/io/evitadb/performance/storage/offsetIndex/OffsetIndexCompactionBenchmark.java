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

package io.evitadb.performance.storage.offsetIndex;

import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for {@link OffsetIndex#compact} — the operation that issue #1157 identified as a
 * source of multi-second G1 Old Gen stop-the-world pauses on large entity collections.
 *
 * The benchmark is designed to surface two regressions if the proposed fixes (scratch-buffer
 * reuse, page-cache hygiene) are reverted or weakened:
 *
 * 1. **Allocation rate** — run with `-prof gc` and watch `gc.alloc.rate.norm`. Per the issue, the
 *    new code path should hold allocation roughly constant across `payloadSizeProfile = LARGE`
 *    parameters; the old code path scales the byte[] allocation linearly with record size.
 * 2. **Wall-clock time** — `BenchmarkMode.AverageTime` reports ms-per-compaction. Acceptance
 *    criterion in the issue is ≤ ±5% versus baseline.
 *
 * The benchmark intentionally runs on a single thread (`@Threads(1)`) — compaction in production
 * is a serial operation that holds the write-handle on the source `OffsetIndex` for its full
 * duration.
 *
 * ## Suggested invocation
 *
 * The uberjar's default `Main-Class` is `ArtificialTestRunner`, which hard-codes
 * `include("io.evitadb.performance.externalApi.*")` and **ignores command-line arguments**.
 * Bypass it via the generic `BenchmarkRunner` entrypoint:
 *
 * ```bash
 * java -cp evita_test/evita_performance_tests/target/benchmarks.jar \
 *     io.evitadb.performance.BenchmarkRunner OffsetIndexCompactionBenchmark \
 *     -prof gc
 * ```
 *
 * Per-allocation hot-path attribution (requires async-profiler installed):
 *
 * ```bash
 * java -cp evita_test/evita_performance_tests/target/benchmarks.jar \
 *     io.evitadb.performance.BenchmarkRunner OffsetIndexCompactionBenchmark \
 *     -prof "async:libPath=/path/to/libasyncProfiler.so;event=alloc;output=flamegraph"
 * ```
 *
 * Page-cache impact is hard to reproduce in JMH itself; for that, run the benchmark with
 * `vmtouch` or `/proc/$$/status` sampling around it externally.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {
	"-Xmx4g",
	"-XX:+UseG1GC",
	"-XX:+AlwaysPreTouch"
})
@Threads(1)
public class OffsetIndexCompactionBenchmark {

	/**
	 * Compacts the source `OffsetIndex` to a fresh file. This is the same code path that
	 * production runs when a catalog reaches the compaction threshold — covers the entire
	 * `copySnapshotTo` loop with `FileOutputStream` open/close included.
	 *
	 * The destination file is created on a fresh path per invocation (see
	 * {@link OffsetIndexCompactionBenchmarkState#setUpInvocation}) so each measurement sees a
	 * cold-output OS state, matching the production scenario.
	 *
	 * The returned descriptor is consumed by a `Blackhole` so JIT does not eliminate the
	 * compaction call as dead.
	 */
	@Benchmark
	public void compactOffsetIndex(OffsetIndexCompactionBenchmarkState state, Blackhole blackhole) {
		final OffsetIndexDescriptor descriptor = state.getSourceOffsetIndex().compact(state.getTargetFile());
		blackhole.consume(descriptor);
	}

}
