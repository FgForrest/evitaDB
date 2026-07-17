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

import io.evitadb.api.exception.IndexNotReady;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingContent;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Baseline benchmark (issue #1282): forward ({@code getRecordings}) vs reverse ({@code getRecordingsReversed})
 * read cost over an already-indexed disk buffer. Reverse reads buffer every
 * session's records into an {@code ArrayList} and {@code Collections.reverse} them
 * (`DiskRingBuffer.java:499-507`); this benchmark quantifies that extra cost against the forward, purely
 * streamed path.
 *
 * The {@code recordsPerSession} parameter exists because with a single record per session the reverse
 * buffering is nearly free (reversing a one-element list), which
 * structurally hides the very cost this benchmark exists to measure. A multi-record variant is required
 * before drawing any conclusion about the reverse-read buffering optimization.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class TrafficRecordingReadBenchmark {

	@Param({"100", "1000", "5000"})
	private int sessionCount;

	@Param({"1", "20"})
	private int recordsPerSession;

	private Path workDirectory;
	private OffHeapTrafficRecorder recorder;
	private TrafficRecordingCaptureRequest request;

	@Setup(Level.Trial)
	public void setUpTrial() throws IOException {
		this.workDirectory = Files.createTempDirectory("traffic-read-bench");
		// size the disk buffer so nothing is evicted for any parameter combination - otherwise the number of
		// streamed sessions would differ across params and the forward/reverse comparison would be muddied
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
			// multiple records per session make the reverse path's per-session ArrayList + Collections.reverse
			// buffering actually observable (a single-record session reverses a one-element list for free)
			for (int r = 0; r < this.recordsPerSession; r++) {
				this.recorder.recordQuery(
					sessionId, "read-bench query", query,
					TrafficRecordingBenchSupport.SAMPLE_LABELS, OffsetDateTime.now(),
					1, 1, 128, new int[]{1}, null
				);
			}
			this.recorder.closeSession(sessionId, null);
		}

		this.request = TrafficRecordingCaptureRequest.builder()
			.content(TrafficRecordingContent.BODY)
			.build();

		// force the index build once outside the timed methods; the first call always throws
		// IndexNotReady even though it synchronously triggers the build (immediate scheduler + delay 0)
		try (final Stream<TrafficRecording> warmup = this.recorder.getRecordings(this.request)) {
			warmup.forEach(recording -> { });
		} catch (IndexNotReady ex) {
			try (final Stream<TrafficRecording> warmup = this.recorder.getRecordings(this.request)) {
				warmup.forEach(recording -> { });
			}
		}
	}

	@TearDown(Level.Trial)
	public void tearDownTrial() throws IOException {
		this.recorder.close();
		FileUtils.deleteDirectory(this.workDirectory);
	}

	@Benchmark
	public void forwardRead(Blackhole bh) {
		try (final Stream<TrafficRecording> stream = this.recorder.getRecordings(this.request)) {
			stream.forEach(bh::consume);
		}
	}

	@Benchmark
	public void reverseRead(Blackhole bh) {
		try (final Stream<TrafficRecording> stream = this.recorder.getRecordingsReversed(this.request)) {
			stream.forEach(bh::consume);
		}
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}
}
