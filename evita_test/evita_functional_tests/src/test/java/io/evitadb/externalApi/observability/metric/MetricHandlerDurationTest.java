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

package io.evitadb.externalApi.observability.metric;

import io.evitadb.core.Evita;
import io.evitadb.externalApi.event.ReadinessEvent;
import io.evitadb.externalApi.event.ReadinessEvent.Prospective;
import io.evitadb.externalApi.event.ReadinessEvent.Result;
import io.evitadb.externalApi.observability.configuration.ObservabilityOptions;
import io.evitadb.test.EvitaTestSupport;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot.HistogramDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the duration histogram of the metric handler registered on an embedded engine with real JFR events far
 * shorter than a millisecond.
 *
 * The events carry an API label unique to the test, so no other engine in the JVM can add observations to their
 * series. Isolation keeps concurrently running server tests from starting another metric stream: it would observe
 * the same events into the same series, and its start would release the fence that waits for the handler's stream.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("MetricHandler duration precision")
@Tag(OBSERVABILITY_API)
@Tag(OBSERVABILITY)
@Isolated
class MetricHandlerDurationTest implements EvitaTestSupport {
	/**
	 * A batch allows a scheduling pause in one event without requiring every event to finish below 1 ms.
	 */
	private static final int EVENT_COUNT = 16;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths(MetricHandlerDurationTest.class.getSimpleName());
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	@AfterEach
	void tearDown() {
		// closing the engine stops the handler's recording stream, so it cannot observe events of later tests
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Verifies the observations the registered handler makes for an event's own JFR duration.
	 */
	@Nested
	@DisplayName("Fractional milliseconds")
	class FractionalMilliseconds {

		/**
		 * Compares the histogram with the exact durations a second recording stream sees for the same events.
		 */
		@Test
		@DisplayName("Preserves sub-millisecond event durations")
		void shouldObserveFractionalMillisecondsWhenEventsAreShort() throws Exception {
			final String eventName = EventType.getEventType(ReadinessEvent.class).getName();
			final String metricName = MetricHandler.composeMetricName(ReadinessEvent.class, "durationMilliseconds");
			final String apiCode = "precision-test-" + UUID.randomUUID();
			final List<Duration> durations = new CopyOnWriteArrayList<>();
			final CountDownLatch handlerStreamStarted = new CountDownLatch(1);
			final CountDownLatch observed = new CountDownLatch(1);
			final FlightRecorderListener startListener = new FlightRecorderListener() {
				@Override
				public void recordingStateChanged(@Nonnull Recording recording) {
					if (recording.getState() == RecordingState.RUNNING) {
						handlerStreamStarted.countDown();
					}
				}
			};
			try (final RecordingStream stream = new RecordingStream()) {
				stream.enable(ReadinessEvent.class);
				stream.onEvent(eventName, event -> {
					if (apiCode.equals(event.getString("api"))) {
						durations.add(event.getDuration());
					}
				});
				// the handler's series changes only after a flush, so each flush re-checks whether it holds the batch
				stream.onFlush(() -> {
					final HistogramDataPointSnapshot histogram = findHistogram(metricName, apiCode);
					if (durations.size() == EVENT_COUNT && histogram != null && histogram.getCount() >= EVENT_COUNT) {
						observed.countDown();
					}
				});
				stream.startAsync();

				// added after this test's stream started, so the next recording to start is the handler's own
				FlightRecorder.addListener(startListener);
				try {
					new MetricHandler(createObservabilityOptions())
						.registerHandlers(MetricHandlerDurationTest.this.evita);
					assertTrue(
						handlerStreamStarted.await(30, SECONDS),
						"Metric handler must start its recording stream before events are emitted"
					);
				} finally {
					FlightRecorder.removeListener(startListener);
				}
				for (int i = 0; i < EVENT_COUNT; i++) {
					new ReadinessEvent(apiCode, Prospective.SERVER).finish(Result.READY);
				}

				assertTrue(observed.await(30, SECONDS), "All duration events must reach the duration histogram");
			}
			assertEquals(EVENT_COUNT, durations.size());
			assertTrue(
				durations.stream().anyMatch(duration -> duration.toNanos() > 0 && duration.toNanos() < 1_000_000),
				"At least one recorded event must have a non-zero sub-millisecond duration"
			);
			final HistogramDataPointSnapshot histogram = findHistogram(metricName, apiCode);
			assertNotNull(histogram, "Duration histogram must contain the emitted events");
			// every metric stream alive in the JVM observes each event into this series, so it holds whole copies of
			// the batch and their mean is the batch's mean however many streams there are
			assertEquals(0, histogram.getCount() % EVENT_COUNT, "Each stream must observe every event exactly once");
			final double expectedMean = durations.stream()
				.mapToDouble(duration -> duration.toNanos() / 1_000_000.0)
				.sum() / EVENT_COUNT;
			assertEquals(
				expectedMean, histogram.getSum() / histogram.getCount(), 0.000000001,
				"Duration histogram must preserve fractional milliseconds"
			);
		}
	}

	/**
	 * Limits the handler to the readiness event, so it registers no JVM metrics and records nothing else.
	 */
	@Nonnull
	private static ObservabilityOptions createObservabilityOptions() {
		return new ObservabilityOptions(
			null, "localhost:5555", null, null, null, null, null,
			List.of(ReadinessEvent.class.getName()), null, null, null
		);
	}

	/**
	 * Finds the histogram data point of the unique API label used by this test.
	 */
	@Nullable
	private static HistogramDataPointSnapshot findHistogram(@Nonnull String name, @Nonnull String apiCode) {
		for (final MetricSnapshot snapshot : PrometheusRegistry.defaultRegistry.scrape()) {
			if (snapshot instanceof final HistogramSnapshot histogram &&
				name.equals(snapshot.getMetadata().getName())) {
				for (final HistogramDataPointSnapshot point : histogram.getDataPoints()) {
					if (apiCode.equals(point.getLabels().get("api"))) {
						return point;
					}
				}
			}
		}
		return null;
	}
}
