/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.store.traffic;


import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.query.Query;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.exception.NotMonitored;
import io.evitadb.store.traffic.OffHeapTrafficRecorder.MemoryNotAvailableException;
import io.evitadb.store.traffic.event.TrafficRecorderMissReason;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.FileUtils;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRAFFIC_ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@link OffHeapTrafficRecorder} observability behaviour: that the periodic metric event
 * emits DELTAS (not cumulative totals), that skips/drops are attributed to the correct
 * {@link TrafficRecorderMissReason}, that {@link MemoryNotAvailableException} is opted out of error
 * monitoring, and that the sampling rate no longer folds in involuntary failure drops.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(STORAGE)
@Tag(TRAFFIC_ENGINE)
public class OffHeapTrafficRecorderMetricsTest implements EvitaTestSupport {
	private static final String STATISTICS_EVENT = "io.evitadb.store.traffic.Statistics";
	private static final String SKIPPED_EVENT = "io.evitadb.store.traffic.SkippedRecords";

	private final Path workDirectory = getPathInTargetDirectory(UUID.randomUUID() + "/work");
	private OffHeapTrafficRecorder trafficRecorder;

	@Nonnull
	private static Query simpleQuery() {
		return query(
			collection(Entities.PRODUCT),
			filterBy(entityPrimaryKeyInSet(1)),
			require(entityFetchAll())
		);
	}

	@BeforeEach
	void setUp() {
		// 2 048 B blocks over a 32 768 B buffer => exactly 16 off-heap memory blocks
		this.trafficRecorder = new OffHeapTrafficRecorder(2_048);
		this.workDirectory.toFile().mkdirs();
		final StorageOptions storageOptions = StorageOptions.builder()
			.outputBufferSize(2_048)
			.workDirectory(this.workDirectory)
			.build();
		final Scheduler scheduler = new Scheduler(new ImmediateScheduledThreadPoolExecutor());
		this.trafficRecorder.init(
			TEST_CATALOG,
			new FileManagementService(storageOptions),
			scheduler,
			storageOptions,
			TrafficRecordingOptions.builder()
				.enabled(true)
				.trafficSamplingPercentage(100)
				.trafficMemoryBufferSizeInBytes(32768L)
				.trafficDiskBufferSizeInBytes(65536L)
				.build(),
			0
		);
	}

	@AfterEach
	void tearDown() throws IOException {
		this.trafficRecorder.close();
		FileUtils.deleteDirectory(this.workDirectory);
	}

	@Test
	@DisplayName("MemoryNotAvailableException is opted out of error monitoring via @NotMonitored")
	void shouldOptMemoryNotAvailableOutOfErrorMonitoring() {
		assertTrue(
			MemoryNotAvailableException.class.isAnnotationPresent(NotMonitored.class),
			"MemoryNotAvailableException must carry @NotMonitored so the error-monitoring agent skips it"
		);
		// the marker must be readable by the byte-buddy agent at premain and applicable to types
		assertEquals(RetentionPolicy.RUNTIME, NotMonitored.class.getAnnotation(Retention.class).value());
		assertArrayEquals(new ElementType[]{ElementType.TYPE}, NotMonitored.class.getAnnotation(Target.class).value());
	}

	@Test
	@DisplayName("Statistics counters are emitted as deltas, never cumulative")
	void shouldEmitDeltaNotCumulativeCounters() throws IOException {
		final UUID sessionId = UUID.randomUUID();
		// known workload BEFORE the recording window: 1 created session + 3 recorded records, no close
		// (createSession / record* never trigger an automatic flush, so nothing is emitted yet)
		this.trafficRecorder.createSession(sessionId, 1L, OffsetDateTime.now());
		for (int i = 0; i < 3; i++) {
			this.trafficRecorder.recordFetch(sessionId, simpleQuery(), OffsetDateTime.now(), 1, 1, i + 1, null);
		}

		final List<RecordedEvent> events = recordAndCapture(
			() -> {
				this.trafficRecorder.publishStatisticsEvents(0);
				this.trafficRecorder.publishStatisticsEvents(0);
			},
			STATISTICS_EVENT
		);

		final List<RecordedEvent> stats = eventsNamed(events, STATISTICS_EVENT);
		assertEquals(2, stats.size(), "expected exactly two statistics emissions");
		// first emission carries the accumulated-so-far deltas ...
		assertEquals(1L, stats.get(0).getLong("createdSessions"));
		assertEquals(3L, stats.get(0).getLong("recordedRecords"));
		// ... the second, with no new activity, must be zero - it would repeat the totals if cumulative
		assertEquals(0L, stats.get(1).getLong("createdSessions"));
		assertEquals(0L, stats.get(1).getLong("recordedRecords"));
		// gauges are instantaneous, not deltas: the session still holds at least one block on both emissions
		assertEquals(16L, stats.get(0).getLong("totalMemoryBlocks"));
		assertTrue(stats.get(1).getLong("usedMemoryBlocks") >= 1L);
		// no counter delta may ever be negative
		for (final RecordedEvent event : stats) {
			assertTrue(event.getLong("recordedRecords") >= 0L);
			assertTrue(event.getLong("createdSessions") >= 0L);
			assertTrue(event.getLong("finishedSessions") >= 0L);
			assertTrue(event.getLong("blocksAllocated") >= 0L);
		}
	}

	@Test
	@DisplayName("Sampling skips are attributed to SAMPLING, emitted once as a delta, with no repeat on an idle publish")
	void shouldClassifySampledOutRecordsUnderSamplingReason() throws IOException {
		final List<RecordedEvent> events = recordAndCapture(
			() -> {
				// record into sessions that were never created -> the deliberate sampling-miss path
				for (int i = 0; i < 4; i++) {
					this.trafficRecorder.recordFetch(UUID.randomUUID(), simpleQuery(), OffsetDateTime.now(), 1, 1, i, null);
				}
				this.trafficRecorder.publishStatisticsEvents(0);
				// a second publish with no new activity: the SAMPLING delta is now zero, so the idle-reason
				// skip must suppress any further SkippedRecords event (deltas are emitted, never cumulative)
				this.trafficRecorder.publishStatisticsEvents(0);
			},
			SKIPPED_EVENT
		);

		final List<RecordedEvent> sampling = eventsNamed(events, SKIPPED_EVENT).stream()
			.filter(event -> "SAMPLING".equals(event.getString("reason")))
			.toList();
		assertEquals(
			1, sampling.size(),
			"exactly one SAMPLING skip event expected - the first publish emits the delta, the second idle " +
				"publish must emit nothing"
		);
		assertEquals(4L, sampling.get(0).getLong("missedRecords"));
		assertEquals(0L, sampling.get(0).getLong("droppedSessions"), "sampling never drops whole sessions");
	}

	@Test
	@DisplayName("Sessions dropped because the off-heap buffer is exhausted are attributed to MEMORY_SHORTAGE")
	void shouldClassifyMemoryShortageDropsUnderReason() {
		// open far more sessions than there are memory blocks, without closing, to exhaust the buffer
		for (int i = 0; i < 200; i++) {
			this.trafficRecorder.createSession(UUID.randomUUID(), 1L, OffsetDateTime.now());
		}

		final Map<TrafficRecorderMissReason, AtomicLong> dropped = reasonCounters("droppedSessionsByReason");
		assertTrue(
			dropped.get(TrafficRecorderMissReason.MEMORY_SHORTAGE).get() > 0L,
			"expected sessions dropped due to memory shortage once the 16 blocks are exhausted"
		);
		assertEquals(0L, dropped.get(TrafficRecorderMissReason.SAMPLING).get(), "sampling never drops whole sessions");
	}

	@Test
	@DisplayName("Sampling rate excludes involuntary failure drops (positive-feedback fix)")
	void shouldExcludeFailureDropsFromSamplingRate() throws Exception {
		final UUID sessionId = UUID.randomUUID();
		this.trafficRecorder.createSession(sessionId, 1L, OffsetDateTime.now());
		for (int i = 0; i < 3; i++) {
			this.trafficRecorder.recordFetch(sessionId, simpleQuery(), OffsetDateTime.now(), 1, 1, i, null);
		}
		// 3 recorded, 0 sampled out -> 100 %
		assertEquals(100, currentSamplingRate());

		// inject heavy failure drops of every non-sampling reason: they must NOT drag the rate down
		// (folding them in was the positive-feedback bug that made the recorder admit MORE under pressure)
		final Map<TrafficRecorderMissReason, AtomicLong> missed = reasonCounters("missedRecordsByReason");
		missed.get(TrafficRecorderMissReason.MEMORY_SHORTAGE).addAndGet(1_000);
		missed.get(TrafficRecorderMissReason.DISK_SHORTAGE).addAndGet(500);
		missed.get(TrafficRecorderMissReason.IO_ERROR).addAndGet(250);
		missed.get(TrafficRecorderMissReason.SERIALIZATION_ERROR).addAndGet(250);
		assertEquals(100, currentSamplingRate(), "failure drops must be excluded from the sampling ratio");

		// deliberate sampling skips DO count: 3 sampled out against 3 recorded -> 50 %
		missed.get(TrafficRecorderMissReason.SAMPLING).addAndGet(3);
		assertEquals(50, currentSamplingRate());
	}

	@Test
	@DisplayName("setSamplingPercentage rebaselines the sampling ratio without resetting the delta counters")
	void shouldRebaselineSamplingRateWithoutResettingDeltaCounters() throws Exception {
		final UUID sessionId = UUID.randomUUID();
		this.trafficRecorder.createSession(sessionId, 1L, OffsetDateTime.now());
		for (int i = 0; i < 3; i++) {
			this.trafficRecorder.recordFetch(sessionId, simpleQuery(), OffsetDateTime.now(), 1, 1, i, null);
		}
		// 3 recorded, 0 sampled out -> 100 %
		assertEquals(100, currentSamplingRate());
		// advance the delta bookkeeping so lastRecordedRecordsEmitted == recordedRecords (both 3)
		this.trafficRecorder.publishStatisticsEvents(0);

		// re-target the sampler: the ratio must restart fresh (recorded / sampled-out baselines := current)
		this.trafficRecorder.setSamplingPercentage(50);

		// (a) fresh rebaseline: recorded-baseline == recorded and sampledOut-baseline == sampledOut, so the
		// ratio reads 0 immediately, which lets the next session pass the gate under any non-zero target
		assertEquals(0, currentSamplingRate(), "the sampling ratio must restart from zero after a rebaseline");

		// (b) the rebaseline must NOT reset the monotonic recordedRecords counter: an idle publish must emit a
		// Statistics event whose recordedRecords delta is exactly 0 and never negative (the old counter-reset
		// behaviour would leave lastRecordedRecordsEmitted above the counter and drive the delta below zero)
		final List<RecordedEvent> events = recordAndCapture(
			() -> this.trafficRecorder.publishStatisticsEvents(0),
			STATISTICS_EVENT
		);
		final List<RecordedEvent> stats = eventsNamed(events, STATISTICS_EVENT);
		assertEquals(1, stats.size(), "exactly one statistics emission expected");
		assertEquals(
			0L, stats.get(0).getLong("recordedRecords"),
			"no records were recorded since the last emission, so the delta must be zero"
		);
		assertTrue(
			stats.get(0).getLong("recordedRecords") >= 0L,
			"a rebaseline must never drive the recordedRecords delta negative"
		);

		// (c) the new ratio is computed against the NEW baseline: 3 freshly recorded records + 3 freshly
		// sampled-out records -> 50 %, proving the 3 pre-rebaseline records no longer count towards the ratio
		for (int i = 0; i < 3; i++) {
			this.trafficRecorder.recordFetch(sessionId, simpleQuery(), OffsetDateTime.now(), 1, 1, i, null);
		}
		reasonCounters("missedRecordsByReason").get(TrafficRecorderMissReason.SAMPLING).addAndGet(3);
		assertEquals(50, currentSamplingRate());
	}

	/**
	 * Runs {@code actions} inside a JFR recording that has the given event types enabled and returns every
	 * captured event. The recording is dumped to a temporary file that is deleted before returning.
	 */
	@Nonnull
	private static List<RecordedEvent> recordAndCapture(@Nonnull Runnable actions, @Nonnull String... eventNames) throws IOException {
		try (final Recording recording = new Recording()) {
			for (final String eventName : eventNames) {
				recording.enable(eventName);
			}
			recording.start();
			actions.run();
			recording.stop();
			final Path dump = Files.createTempFile("traffic-metrics", ".jfr");
			try {
				recording.dump(dump);
				return RecordingFile.readAllEvents(dump);
			} finally {
				Files.deleteIfExists(dump);
			}
		}
	}

	@Nonnull
	private static List<RecordedEvent> eventsNamed(@Nonnull List<RecordedEvent> events, @Nonnull String eventName) {
		return events.stream()
			.filter(event -> eventName.equals(event.getEventType().getName()))
			.toList();
	}

	/**
	 * Reflectively reads one of the recorder's per-reason counter maps so a test can assert on the exact
	 * classification without depending on the (asynchronously flushed) emitted metrics.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private Map<TrafficRecorderMissReason, AtomicLong> reasonCounters(@Nonnull String fieldName) {
		try {
			final Field field = OffHeapTrafficRecorder.class.getDeclaredField(fieldName);
			field.setAccessible(true);
			return (Map<TrafficRecorderMissReason, AtomicLong>) field.get(this.trafficRecorder);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Reflectively invokes the private {@code computeCurrentSamplingRate()} so the sampling-rate computation
	 * can be asserted directly and deterministically.
	 */
	private int currentSamplingRate() throws ReflectiveOperationException {
		final Method method = OffHeapTrafficRecorder.class.getDeclaredMethod("computeCurrentSamplingRate");
		method.setAccessible(true);
		return (int) method.invoke(this.trafficRecorder);
	}

}
