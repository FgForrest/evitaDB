/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.performance.walreplay;

import io.evitadb.performance.setup.BenchmarkForkArgs;
import io.evitadb.performance.walreplay.state.WalReplayState;
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
 * Measures the cost of real transactional processing (conflict resolution, WAL append, index
 * propagation) by replaying production transactions captured in a catalog's Write-Ahead Log
 * against an embedded evitaDB instance booted from an earlier snapshot of the same catalog. Which
 * catalog is under investigation is not baked into this class - see {@link WalReplayState} for the
 * system properties that point the fixture at a given dataset.
 *
 * The whole pending WAL slice is a single bounded, non-repeatable body of work (once replayed,
 * a transaction cannot be meaningfully replayed again against the same live catalog), so this is
 * measured with a single-shot benchmark rather than steady-state throughput/latency modes. The
 * fixture is set up once per trial (no iteration-level reset) so a profiler attached to the fork
 * captures one continuous, unpolluted replay pass.
 *
 * Run (after building `benchmarks.jar` with the `full` Maven profile):
 * <pre>
 * java -Xmx24g \
 *   -Devita.replay.catalogName=myCatalog \
 *   -Devita.replay.pristineDataDir=/path/to/pristine \
 *   -Devita.replay.walSourceDir=/path/to/wal-source \
 *   -cp evita_test/evita_performance_tests/target/benchmarks.jar \
 *   io.evitadb.performance.BenchmarkRunner WalReplayBenchmark \
 *   -prof gc
 * </pre>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(
	value = 1,
	jvmArgsAppend = {
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_LANG,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_LANG_INVOKE,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_MATH,
		BenchmarkForkArgs.ADD_OPENS, BenchmarkForkArgs.OPEN_UTIL
	}
)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
public class WalReplayBenchmark {

	@Benchmark
	public long replayPendingWalTransactions(WalReplayState state) throws Exception {
		return state.replayPendingTransactions();
	}

	/**
	 * Dry-run entry point that exercises the fixture directly, bypassing the JMH harness - useful
	 * to sanity-check the replay pipeline (transaction/mutation counts, timings) before running
	 * the actual profiled benchmark.
	 */
	public static void main(String[] args) throws Exception {
		final WalReplayState state = new WalReplayState();
		try {
			state.setUp();
			state.replayPendingTransactions();
			log.info(
				"Dry run finished, starting from catalog version {}.\n{}",
				state.getStartCatalogVersion(), state.getStatistics().format()
			);
			holdOpenForAttachedProfiler();
		} finally {
			state.tearDown();
		}
	}

	/**
	 * Keeps the JVM alive after the result block is printed, for the number of seconds named by the
	 * `evita.replay.holdOpenSeconds` system property (0 = do not hold, the default).
	 *
	 * An externally attached profiler writes its dump only when it is told to stop, and it can only be told
	 * that while the target JVM is still running. The result block is the one marker a driver script can watch
	 * for to know the replay window has closed, but the process tears the fixture down and exits within a few
	 * seconds of printing it - far too fast for a script to notice and for a fresh JVM to start and connect.
	 * The dump is then lost, and it is lost *silently*: the profiler leaves a zero-byte file behind and the
	 * benchmark itself reports success. Holding here closes that race deterministically.
	 *
	 * The hold cannot distort what was measured. Replay is already complete and the main thread only sleeps, so
	 * it contributes no CPU samples and allocates nothing; both profiling modes used here sample work, not time.
	 */
	private static void holdOpenForAttachedProfiler() throws InterruptedException {
		final long holdSeconds = Long.getLong("evita.replay.holdOpenSeconds", 0L);
		if (holdSeconds > 0L) {
			log.info("Holding the JVM open for {} s so an attached profiler can write its dump.", holdSeconds);
			Thread.sleep(holdSeconds * 1000L);
		}
	}

}
