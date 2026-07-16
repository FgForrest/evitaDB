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

package io.evitadb.spike;

import io.evitadb.api.query.Query;
import io.evitadb.store.traffic.OffHeapTrafficRecorder;
import io.evitadb.utils.FileUtils;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Phase 0 (issue #1282) baseline: cost of {@code OffHeapTrafficRecorder#freeMemory()} - the drain that moves
 * finalized in-memory sessions into the {@code DiskRingBuffer} - as a function of how many sessions are
 * outstanding and how large they are. Quantifies `DiskRingBuffer.append` + eviction scan + file-lock overhead
 * per drained batch (see plan Phase 0, benchmark 2).
 *
 * <p>`freeMemory()` is private (it is only ever invoked internally, on a schedule) so this benchmark reaches it
 * via reflection - the same technique `DiskRingBufferTest`/`OffHeapTrafficRecorderTest` already use to reach
 * other internals of this package from test code. The recorder is configured with
 * `trafficFlushIntervalInMilliseconds = Long.MAX_VALUE`, which makes `DelayedAsyncTask.schedule()` a guaranteed
 * no-op (see `DelayedAsyncTask.schedule()`: `if (this.delay == Long.MAX_VALUE) return;`), so `closeSession` can
 * never trigger a drain on its own - the only drain that ever happens is the one this benchmark times.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class TrafficRecordingFlushBenchmark {

	/**
	 * A rough estimate of how many bytes a single sample query record occupies once serialized, used only to
	 * translate the `sessionSizeBytes` parameter into a record count for pre-population; the plan only requires
	 * an approximate session size sweep, not an exact one.
	 */
	private static final int APPROX_BYTES_PER_RECORD = 300;

	@Param({"1", "10", "100"})
	private int sessionCount;

	@Param({"1024", "16384"})
	private int sessionSizeBytes;

	private Path workDirectory;
	private OffHeapTrafficRecorder recorder;
	private Query query;
	private Method freeMemoryMethod;

	@Setup(Level.Trial)
	public void setUpTrial() throws NoSuchMethodException, IOException {
		this.workDirectory = Files.createTempDirectory("traffic-flush-bench");
		this.recorder = TrafficRecordingBenchSupport.newRecorder(
			this.workDirectory,
			TrafficRecordingBenchSupport.immediateScheduler(),
			16_384,
			64L * 1024 * 1024,
			// deliberately modest so repeated drains wrap the ring and pay a real eviction-scan cost,
			// not just an ever-growing append into empty space
			8L * 1024 * 1024,
			Long.MAX_VALUE,
			100
		);
		this.query = TrafficRecordingBenchSupport.sampleQuery(256);

		this.freeMemoryMethod = OffHeapTrafficRecorder.class.getDeclaredMethod("freeMemory");
		this.freeMemoryMethod.setAccessible(true);
	}

	@TearDown(Level.Trial)
	public void tearDownTrial() throws IOException {
		this.recorder.close();
		FileUtils.deleteDirectory(this.workDirectory);
	}

	/**
	 * Finalizes {@link #sessionCount} sessions of approximately {@link #sessionSizeBytes} each - outside the
	 * timed method (JMH excludes `Level.Invocation` fixtures from the measurement) - so the timed
	 * {@link #drainFinalizedSessions(Blackhole)} call always has real, freshly finalized work to drain.
	 */
	@Setup(Level.Invocation)
	public void populateFinalizedSessions() {
		final int recordsPerSession = Math.max(1, this.sessionSizeBytes / APPROX_BYTES_PER_RECORD);
		for (int s = 0; s < this.sessionCount; s++) {
			final UUID sessionId = UUID.randomUUID();
			this.recorder.createSession(sessionId, 1L, OffsetDateTime.now());
			for (int r = 0; r < recordsPerSession; r++) {
				this.recorder.recordQuery(
					sessionId, "flush-bench query", this.query,
					TrafficRecordingBenchSupport.SAMPLE_LABELS, OffsetDateTime.now(),
					1, 1, 256, new int[]{1}, null
				);
			}
			this.recorder.closeSession(sessionId, null);
		}
	}

	@Benchmark
	public void drainFinalizedSessions(Blackhole bh) throws InvocationTargetException, IllegalAccessException {
		bh.consume((long) this.freeMemoryMethod.invoke(this.recorder));
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}
}
