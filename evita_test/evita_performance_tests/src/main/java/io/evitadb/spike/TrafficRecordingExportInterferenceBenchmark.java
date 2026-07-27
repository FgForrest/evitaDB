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
import io.evitadb.api.traffic.TrafficRecordingExporter.ExportedSessionConsumer;
import io.evitadb.api.traffic.TrafficRecordingExporter.SessionByteSource;
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

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Export interference benchmark (issue #1282). Measures {@code recordQuery} writer throughput
 * WITH and WITHOUT a background thread continuously exporting the ring buffer, quantifying whether writer
 * throughput degrades under concurrent export. The {@code false}
 * value of {@link #concurrentExport} is the writer-only baseline; {@code true} runs a daemon export loop
 * throughout the measurement. The interference is the ratio of the two.
 *
 * Correctness (no torn `.bin` / no writer exception under rotation-during-export) is already proven
 * deterministically by
 * {@code EvitaOnDemandTrafficRecordingTest.shouldTolerateContinuousRotationDuringConcurrentExport} (the
 * validity-vs-identity fix); this benchmark only quantifies the throughput impact of the shared
 * span-lock and the pre-export drain on the concurrent writers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class TrafficRecordingExportInterferenceBenchmark {

	/**
	 * Number of `recordQuery` calls a single JMH thread issues against one session before rotating it.
	 */
	private static final int ROTATION_PERIOD = 1_000;
	/**
	 * Upper bound on concurrently exercised writer JMH threads - used to size the memory buffer so no thread
	 * combination exhausts free blocks between rotations (isolating export interference from block-starvation).
	 */
	private static final int MAX_THREADS = 16;
	/**
	 * Sessions pre-loaded before measurement so the very first export has a non-empty snapshot to walk.
	 */
	private static final int SEED_SESSIONS = 500;

	@Param({"64", "1024"})
	private int payloadBytes;

	@Param({"false", "true"})
	private boolean concurrentExport;

	private Path workDirectory;
	private OffHeapTrafficRecorder recorder;
	private Query query;

	private volatile boolean exportRunning;
	private Thread exportThread;
	private final AtomicLong exportRuns = new AtomicLong();
	// the last export's summary, used in teardown to CONFIRM the ring actually wrapped during measurement
	// (bounded total + non-zero skipped == rotation under export, not the easy no-wrap case)
	private volatile long lastExportedSessions;
	private volatile long lastSkippedSessions;
	private volatile long lastTotalSessions;

	@Setup(Level.Trial)
	public void setUpTrial() throws IOException {
		this.workDirectory = Files.createTempDirectory("traffic-export-interference-bench");
		// size the buffers so ROTATION_PERIOD records per thread, for every writer thread, comfortably fit
		// before any single thread's rotation forces a block-starvation drain - so the measured delta is the
		// export interference, not free-block exhaustion
		final long perThreadBudget = (long) ROTATION_PERIOD * (this.payloadBytes + 512);
		final long bufferSizeInBytes = Math.max(8L * 1024 * 1024, perThreadBudget * MAX_THREADS * 2);
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

		// pre-load a batch of finalized sessions so the export loop always has real work
		for (int s = 0; s < SEED_SESSIONS; s++) {
			final UUID sessionId = UUID.randomUUID();
			this.recorder.createSession(sessionId, 1L, OffsetDateTime.now());
			this.recorder.recordQuery(
				sessionId, "seed query", this.query, TrafficRecordingBenchSupport.SAMPLE_LABELS,
				OffsetDateTime.now(), 1, 1, this.payloadBytes, new int[]{1}, null
			);
			this.recorder.closeSession(sessionId, null);
		}

		if (this.concurrentExport) {
			this.exportRunning = true;
			this.exportThread = new Thread(this::exportLoop, "export-interference");
			this.exportThread.setDaemon(true);
			this.exportThread.start();
		}
	}

	/**
	 * Continuously exports the whole ring buffer to a discarding sink until the trial ends, so the writers
	 * being measured are contended by a genuinely running export (pre-export drain + shared span acquisition
	 * per session) rather than a one-shot one.
	 */
	private void exportLoop() {
		final ExportedSessionConsumer discardingConsumer =
			(sequenceOrder, byteSource) -> byteSource.copyTo(OutputStream.nullOutputStream());
		while (this.exportRunning) {
			try {
				final io.evitadb.api.traffic.TrafficRecordingExporter.ExportSummary summary =
					this.recorder.exportTrafficRecording(discardingConsumer, (processed, total) -> { });
				this.lastExportedSessions = summary.exportedSessionCount();
				this.lastSkippedSessions = summary.skippedSessionCount();
				this.lastTotalSessions = summary.totalSessionCount();
				this.exportRuns.incrementAndGet();
			} catch (IOException e) {
				// the discarding sink never throws; a real IOException here is a genuine benchmark failure
				throw new IllegalStateException("Export failed during interference benchmark.", e);
			}
		}
	}

	@TearDown(Level.Trial)
	public void tearDownTrial() throws IOException, InterruptedException {
		this.exportRunning = false;
		if (this.exportThread != null) {
			this.exportThread.join(10_000);
			// surface how many full exports ran during the trial, so a degenerate "export never actually ran"
			// setup cannot masquerade as a clean no-interference result
			System.err.println(
				"[export-interference] concurrentExport=" + this.concurrentExport +
					" payloadBytes=" + this.payloadBytes + " exportRuns=" + this.exportRuns.get() +
					// lastTotal bounded (~disk capacity in sessions, not millions) + lastSkipped > 0 proves the
					// ring wrapped and sessions were evicted DURING export = the rotation-under-export scenario
					" lastExport[exported=" + this.lastExportedSessions +
					" skipped=" + this.lastSkippedSessions +
					" total=" + this.lastTotalSessions + "]"
			);
		}
		this.recorder.close();
		FileUtils.deleteDirectory(this.workDirectory);
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
	 * Per-thread open session, rotated every {@link #ROTATION_PERIOD} calls so its records are finalized to
	 * the ring buffer (feeding the concurrent export) and memory blocks are reclaimed periodically.
	 */
	@State(Scope.Thread)
	public static class ThreadSession {

		private static final int[] SINGLE_PK = new int[]{1};

		private UUID sessionId;
		private int callsUntilRotation;

		@Setup(Level.Trial)
		public void openSession(TrafficRecordingExportInterferenceBenchmark bench) {
			this.sessionId = UUID.randomUUID();
			bench.recorder.createSession(this.sessionId, 1L, OffsetDateTime.now());
			this.callsUntilRotation = ROTATION_PERIOD;
		}

		@TearDown(Level.Trial)
		public void closeSession(TrafficRecordingExportInterferenceBenchmark bench) {
			bench.recorder.closeSession(this.sessionId, null);
		}

		void rotateIfNeeded(TrafficRecordingExportInterferenceBenchmark bench) {
			if (--this.callsUntilRotation <= 0) {
				bench.recorder.closeSession(this.sessionId, null);
				this.sessionId = UUID.randomUUID();
				bench.recorder.createSession(this.sessionId, 1L, OffsetDateTime.now());
				this.callsUntilRotation = ROTATION_PERIOD;
			}
		}
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}
}
