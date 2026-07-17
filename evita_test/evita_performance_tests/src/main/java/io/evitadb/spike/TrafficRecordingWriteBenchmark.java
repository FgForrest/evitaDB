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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Baseline benchmark (issue #1282): write throughput/allocation of {@link OffHeapTrafficRecorder#recordQuery},
 * {@link OffHeapTrafficRecorder#recordFetch} and {@link OffHeapTrafficRecorder#recordMutation} against an
 * initialized recorder at 100% sampling - the query hot path is the primary target for
 * `-prof gc` allocation-rate measurement.
 *
 * <p>The `recordQuery` scenario carries the {@code @Threads(1/4/16)} sweep since it is the
 * dominant call in production traffic; `recordFetch`/`recordMutation` are covered single-threaded only - the
 * exact thread fan-out is a local micro-decision left to the implementer.
 *
 * <p>Each JMH thread keeps one open session and calls `recordQuery`/`recordFetch`/`recordMutation` against it
 * `ROTATION_PERIOD` times before closing and re-opening it. Session close is configured to drain synchronously
 * (`trafficFlushIntervalInMilliseconds = 0` + an immediate-execution scheduler), so free memory blocks are
 * reclaimed periodically without ever growing unbounded - the same mechanism production uses, just paced by call
 * count instead of a wall-clock timer. The occasional drain is a real (if rare) part of the measured average,
 * matching how the recorder actually behaves in production; it is not filtered out.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class TrafficRecordingWriteBenchmark {

	/**
	 * Number of `record*` calls a single JMH thread issues against one session before rotating it.
	 */
	private static final int ROTATION_PERIOD = 1_000;
	/**
	 * Upper bound on concurrently exercised JMH threads across all benchmark methods in this class - used to
	 * size the memory buffer so no thread combination can exhaust free blocks between rotations.
	 */
	private static final int MAX_THREADS = 16;

	/**
	 * Approximate per-record payload size in bytes; drives both the query primary-key-set size and the
	 * mutation attribute value length.
	 */
	@Param({"64", "1024", "4096"})
	private int payloadBytes;

	private Path workDirectory;
	private OffHeapTrafficRecorder recorder;
	private Query query;

	@Setup(Level.Trial)
	public void setUpTrial() throws IOException {
		this.workDirectory = Files.createTempDirectory("traffic-write-bench");
		// size the buffers so ROTATION_PERIOD records per thread, for every thread this class ever spawns,
		// comfortably fit before any single thread's rotation forces a drain
		final long perThreadBudget = (long) ROTATION_PERIOD * (this.payloadBytes + 512);
		final long bufferSizeInBytes = Math.max(4L * 1024 * 1024, perThreadBudget * MAX_THREADS * 2);
		this.recorder = TrafficRecordingBenchSupport.newRecorder(
			this.workDirectory,
			TrafficRecordingBenchSupport.immediateScheduler(),
			16_384,
			bufferSizeInBytes,
			bufferSizeInBytes,
			0L,
			100
		);
		this.query = TrafficRecordingBenchSupport.sampleQuery(this.payloadBytes);
	}

	@TearDown(Level.Trial)
	public void tearDownTrial() throws IOException {
		this.recorder.close();
		FileUtils.deleteDirectory(this.workDirectory);
	}

	@Benchmark
	@Threads(1)
	public void recordQuery_threads1(ThreadSession session, Blackhole bh) {
		recordQueryOp(session, bh);
	}

	@Benchmark
	@Threads(4)
	public void recordQuery_threads4(ThreadSession session, Blackhole bh) {
		recordQueryOp(session, bh);
	}

	@Benchmark
	@Threads(16)
	public void recordQuery_threads16(ThreadSession session, Blackhole bh) {
		recordQueryOp(session, bh);
	}

	@Benchmark
	@Threads(1)
	public void recordFetch(ThreadSession session, Blackhole bh) {
		session.rotateIfNeeded(this);
		this.recorder.recordFetch(
			session.sessionId, this.query, OffsetDateTime.now(),
			1, this.payloadBytes, 1, null
		);
		bh.consume(session.sessionId);
	}

	@Benchmark
	@Threads(1)
	public void recordMutation(ThreadSession session, Blackhole bh) {
		session.rotateIfNeeded(this);
		this.recorder.recordMutation(
			session.sessionId, OffsetDateTime.now(),
			TrafficRecordingBenchSupport.sampleMutation(session.nextPrimaryKey(), this.payloadBytes),
			null
		);
		bh.consume(session.sessionId);
	}

	private void recordQueryOp(ThreadSession session, Blackhole bh) {
		session.rotateIfNeeded(this);
		this.recorder.recordQuery(
			session.sessionId,
			"benchmark query",
			this.query,
			TrafficRecordingBenchSupport.SAMPLE_LABELS,
			OffsetDateTime.now(),
			10, 1, this.payloadBytes,
			ThreadSession.SINGLE_PK,
			null
		);
		bh.consume(session.sessionId);
	}

	/**
	 * Per-thread open session, rotated every {@link #ROTATION_PERIOD} calls so memory blocks are reclaimed
	 * periodically instead of exhausting the pool.
	 */
	@State(Scope.Thread)
	public static class ThreadSession {

		private static final int[] SINGLE_PK = new int[]{1};

		private UUID sessionId;
		private int callsUntilRotation;
		private int primaryKeySequence;

		@Setup(Level.Trial)
		public void openSession(TrafficRecordingWriteBenchmark bench) {
			this.sessionId = UUID.randomUUID();
			bench.recorder.createSession(this.sessionId, 1L, OffsetDateTime.now());
			this.callsUntilRotation = ROTATION_PERIOD;
		}

		@TearDown(Level.Trial)
		public void closeSession(TrafficRecordingWriteBenchmark bench) {
			bench.recorder.closeSession(this.sessionId, null);
		}

		void rotateIfNeeded(TrafficRecordingWriteBenchmark bench) {
			if (--this.callsUntilRotation <= 0) {
				bench.recorder.closeSession(this.sessionId, null);
				this.sessionId = UUID.randomUUID();
				bench.recorder.createSession(this.sessionId, 1L, OffsetDateTime.now());
				this.callsUntilRotation = ROTATION_PERIOD;
			}
		}

		int nextPrimaryKey() {
			return ++this.primaryKeySequence;
		}
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}
}
