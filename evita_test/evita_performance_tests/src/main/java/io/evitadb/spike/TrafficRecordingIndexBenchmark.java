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
 * Baseline benchmark (issue #1282): cost of rebuilding the in-memory {@code TrafficRecordingIndex} from a
 * pre-filled disk buffer - i.e. the window during which reads throw {@code IndexNotReady}. The whole
 * 32 MiB (default) buffer gets Kryo-deserialized to build the index; this benchmark
 * quantifies that cost as a function of how many sessions the buffer holds.
 *
 * <p>Reaches the private, no-arg {@code OffHeapTrafficRecorder#index()} (which just wraps
 * {@code DiskRingBuffer#indexData}) via reflection - it fully rebuilds the index from
 * {@code sessionLocations} on every call, so unlike the flush benchmark no per-invocation fixture is needed:
 * the buffer is filled once per trial and re-indexed repeatedly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class TrafficRecordingIndexBenchmark {

	@Param({"100", "1000", "5000"})
	private int sessionCount;

	@Param({"1", "20"})
	private int recordsPerSession;

	private Path workDirectory;
	private OffHeapTrafficRecorder recorder;
	private Method indexMethod;

	@Setup(Level.Trial)
	public void setUpTrial() throws NoSuchMethodException, IOException {
		this.workDirectory = Files.createTempDirectory("traffic-index-bench");
		// disk buffer sized so every session fits without the ring wrapping mid-fill; a realistic client
		// session holds many records, so the recordsPerSession sweep exercises the per-record index-build cost
		// (the per-record identity re-read + Kryo walk) that dominates a full 32 MiB buffer re-index
		final long diskBufferSizeInBytes = Math.max(
			8L * 1024 * 1024,
			(long) this.sessionCount * this.recordsPerSession * 2_048
		);
		this.recorder = TrafficRecordingBenchSupport.newRecorder(
			this.workDirectory,
			TrafficRecordingBenchSupport.immediateScheduler(),
			16_384,
			32L * 1024 * 1024,
			diskBufferSizeInBytes,
			0L,
			100
		);

		final Query query = TrafficRecordingBenchSupport.sampleQuery(128);
		for (int s = 0; s < this.sessionCount; s++) {
			final UUID sessionId = UUID.randomUUID();
			this.recorder.createSession(sessionId, 1L, OffsetDateTime.now());
			for (int r = 0; r < this.recordsPerSession; r++) {
				this.recorder.recordQuery(
					sessionId, "index-bench query", query,
					TrafficRecordingBenchSupport.SAMPLE_LABELS, OffsetDateTime.now(),
					1, 1, 128, new int[]{1}, null
				);
			}
			// synchronous drain (flushIntervalMs = 0 + immediate scheduler) - by the time createSession
			// returns from closeSession, the record is already on disk and in `sessionLocations`
			this.recorder.closeSession(sessionId, null);
		}

		this.indexMethod = OffHeapTrafficRecorder.class.getDeclaredMethod("index");
		this.indexMethod.setAccessible(true);
	}

	@TearDown(Level.Trial)
	public void tearDownTrial() throws IOException {
		this.recorder.close();
		FileUtils.deleteDirectory(this.workDirectory);
	}

	@Benchmark
	public void rebuildIndex(Blackhole bh) throws InvocationTargetException, IllegalAccessException {
		bh.consume((long) this.indexMethod.invoke(this.recorder));
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}
}
