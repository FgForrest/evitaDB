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

package io.evitadb.core.metric;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.metric.MetricType;
import io.evitadb.api.observability.annotation.ExportMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.FormulaCacheSupervisor.Statistics;
import io.evitadb.core.cache.FormulaCacheTestSessions;
import io.evitadb.core.metric.event.cache.FormulaCacheMismatchEvent;
import io.evitadb.core.metric.event.cache.FormulaCacheStatisticsEvent;
import io.evitadb.externalApi.observability.configuration.ObservabilityOptions;
import io.evitadb.externalApi.observability.metric.EvitaJfrEventRegistry;
import io.evitadb.externalApi.observability.metric.MetricHandler;
import io.evitadb.test.EvitaTestSupport;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises cache metrics through the real engine, JFR recordings and the Prometheus metric handler. Isolation
 * prevents other engines in this JVM from contributing to the unlabelled engine-wide statistics series.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula cache metrics")
@Tag(ENGINE)
@Tag(CACHE)
@Tag(OBSERVABILITY)
@Isolated
class FormulaCacheMetricsTest implements EvitaTestSupport {
	/** Storage owned by this test. */
	@Nullable private TestPaths paths;
	/** Real engine, closed to stop its metric stream as well as its cache. */
	@Nullable private Evita evita;

	/** Stops streams and hooks before removing storage. */
	@AfterEach
	void tearDown() {
		if (this.evita != null) this.evita.close();
		if (this.paths != null) cleanupTestPaths(this.paths);
	}

	/** The metric handler must accumulate exactly the work between emissions, rather than cumulative totals twice. */
	@Test
	@DisplayName("Exports supervisor values and hit deltas that sum to the cumulative Prometheus counter")
	void shouldExportSupervisorValuesAsDeltas() throws Exception {
		final Evita engine = startEngine(true);
		final List<RecordedEvent> events = new CopyOnWriteArrayList<>();
		final CountDownLatch observed = new CountDownLatch(1);
		final CountDownLatch handlerStarted = new CountDownLatch(1);
		final AtomicLong expectedHits = new AtomicLong(-1);
		final String hitsName = MetricHandler.composeMetricName(FormulaCacheStatisticsEvent.class, "hits");
		final double baseline = counterValue(hitsName);
		final FlightRecorderListener listener = new FlightRecorderListener() {
			/** Fences the handler's stream startup before the test emits statistics. */
			@Override
			public void recordingStateChanged(@Nonnull Recording recording) {
				if (recording.getState() == RecordingState.RUNNING) handlerStarted.countDown();
			}
		};
		try (final RecordingStream stream = new RecordingStream()) {
			stream.setReuse(false);
			stream.enable(FormulaCacheStatisticsEvent.class);
			stream.onEvent("io.evitadb.cache.FormulaCacheStatistics", event -> {
				// An automatic startup observation of the empty cache has no work and contributes zero counters.
				if (event.getLong("entries") > 0) events.add(event);
			});
			stream.onFlush(() -> {
				if (events.size() == 2 && expectedHits.get() > 0 &&
					counterValue(hitsName) == baseline + expectedHits.get()) {
					observed.countDown();
				}
			});
			stream.startAsync();
			FlightRecorder.addListener(listener);
			try {
				new MetricHandler(new ObservabilityOptions(
					null, "localhost:5555", null, null, null, null, null,
					List.of(FormulaCacheStatisticsEvent.class.getName()), null, null, null
				)).registerHandlers(engine);
				assertTrue(handlerStarted.await(30, SECONDS), "The handler's recording stream must be running");
			} finally {
				FlightRecorder.removeListener(listener);
			}
			final Runnable hook = detachCacheHook(engine);
			final Statistics first;
			final Statistics second;
			try (final EvitaSessionContract reader = FormulaCacheTestSessions.readOnly(engine, "cacheMetrics")) {
				read(reader);
				read(reader);
				first = engine.getFormulaCacheStatistics();
				assertNotNull(first);
				assertTrue(first.hits() > 0);
				assertTrue(first.misses() > 0);
				assertTrue(first.admissions() > 0);
				assertTrue(first.verifiedHits() > 0);
				hook.run();
				for (int i = 0; i < 3; i++) read(reader);
				second = engine.getFormulaCacheStatistics();
				assertNotNull(second);
				assertEquals(3 * first.hits(), second.hits() - first.hits(), "Three identical warm queries");
				expectedHits.set(second.hits());
				hook.run();
			}
			assertTrue(observed.await(30, SECONDS), "Both events must reach JFR and the Prometheus counter");
			assertEquals(2, events.size());
			assertStatistics(events.get(0), first, null);
			assertStatistics(events.get(1), second, first);
			assertEquals(3 * first.hits(), events.get(1).getLong("hits"));
			assertEquals(second.hits(), counterValue(hitsName) - baseline);
		}
	}

	/** An event disabled in JFR must not consume the baseline, so the first enabled emission includes old work. */
	@Test
	@DisplayName("Keeps totals until the first enabled emission")
	void shouldKeepTotalsUntilFirstEnabledEmission() throws Exception {
		final Evita engine = startEngine(true);
		engine.emitStartObservabilityEvents();
		// Reinitializing observability must retain one emitter and its engine-lifetime delta baseline.
		engine.emitStartObservabilityEvents();
		final Runnable hook = detachCacheHook(engine);
		try (final EvitaSessionContract reader = FormulaCacheTestSessions.readOnly(engine, "cacheMetrics")) {
			read(reader);
			read(reader);
		}
		hook.run();
		final Path recordingPath = Files.createTempFile("formula-cache-first-", ".jfr");
		try (final Recording recording = new Recording()) {
			recording.enable(FormulaCacheStatisticsEvent.class);
			recording.start();
			hook.run();
			recording.stop();
			recording.dump(recordingPath);
			final List<RecordedEvent> events = RecordingFile.readAllEvents(recordingPath).stream()
				.filter(event -> event.getEventType().getName().equals("io.evitadb.cache.FormulaCacheStatistics"))
				.toList();
			assertEquals(1, events.size());
			assertStatistics(events.getFirst(), engine.getFormulaCacheStatistics(), null);
		} finally {
			Files.deleteIfExists(recordingPath);
		}
	}

	/** Disabled configuration creates neither the emitter nor a cache, and ordinary queries emit neither event. */
	@Test
	@DisplayName("Emits no cache events with the cache disabled")
	void shouldEmitNothingWhenCacheIsDisabled() throws Exception {
		final Evita engine = startEngine(false);
		final Path recordingPath = Files.createTempFile("formula-cache-disabled-", ".jfr");
		try (final Recording recording = new Recording()) {
			recording.enable(FormulaCacheStatisticsEvent.class);
			recording.enable(FormulaCacheMismatchEvent.class);
			recording.start();
			engine.emitStartObservabilityEvents();
			assertTrue(engineHooks(engine).stream()
				.noneMatch(hook -> hook.getClass().getSimpleName().equals("FormulaCacheStatisticsHook")));
			try (final EvitaSessionContract reader = FormulaCacheTestSessions.readOnly(engine, "cacheMetrics")) {
				for (int i = 0; i < 4; i++) read(reader);
			}
			engineHooks(engine).forEach(Runnable::run);
			assertNull(engine.getFormulaCacheStatistics());
			recording.stop();
			recording.dump(recordingPath);
			assertTrue(RecordingFile.readAllEvents(recordingPath).stream()
				.noneMatch(event -> event.getEventType().getName().startsWith("io.evitadb.cache.")));
		} finally {
			Files.deleteIfExists(recordingPath);
		}
	}

	/** Distinct nonzero fields detect wiring errors even for counters not exercised by the small query fixture. */
	@Test
	@DisplayName("Maps every counter and gauge and clamps regressing counters")
	void shouldMapAllFieldsAndClampRegressingCounters() throws Exception {
		final Statistics first = statistics(0, true);
		final Statistics second = statistics(100, false);
		final Path recordingPath = Files.createTempFile("formula-cache-fields-", ".jfr");
		try (final Recording recording = new Recording()) {
			recording.enable(FormulaCacheStatisticsEvent.class);
			recording.start();
			new FormulaCacheStatisticsEvent(first, null).commit();
			new FormulaCacheStatisticsEvent(second, first).commit();
			new FormulaCacheStatisticsEvent(first, second).commit();
			recording.stop();
			recording.dump(recordingPath);
			final List<RecordedEvent> events = RecordingFile.readAllEvents(recordingPath).stream()
				.filter(event -> event.getEventType().getName().equals("io.evitadb.cache.FormulaCacheStatistics"))
				.toList();
			assertEquals(3, events.size());
			assertStatistics(events.get(0), first, null);
			assertStatistics(events.get(1), second, first);
			assertStatistics(events.get(2), first, second);
			assertFalse(events.getFirst().hasField("witness"));
		} finally {
			Files.deleteIfExists(recordingPath);
		}
	}

	/** Both flat and grouped registration are consumed by the recorder and the metrics configuration. */
	@Test
	@DisplayName("Registers fresh event names, the cache group and conventional metric names")
	void shouldRegisterEventsAndMetricNames() {
		assertEquals("1m", FormulaCacheStatisticsEvent.class.getAnnotation(jdk.jfr.Period.class).value());
		for (final Class<? extends io.evitadb.core.metric.event.CustomMetricsExecutionEvent> event :
			List.of(FormulaCacheStatisticsEvent.class, FormulaCacheMismatchEvent.class)) {
			assertSame(event, EvitaJfrEventRegistry.getEventClass(event.getName()));
			assertTrue(EvitaJfrEventRegistry.getEventClasses().contains(event));
			assertEquals("io.evitadb.cache", EvitaJfrEventRegistry.getMetricsGroup(event));
			assertTrue(List.of(EvitaJfrEventRegistry.getEventClassesFromPackage("io.evitadb.cache")
				.orElseThrow()).contains(event));
		}
		assertEquals("io.evitadb.cache.FormulaCacheStatistics",
			EventType.getEventType(FormulaCacheStatisticsEvent.class).getName());
		assertEquals("io.evitadb.cache.FormulaCacheMismatch",
			EventType.getEventType(FormulaCacheMismatchEvent.class).getName());
		assertEquals(Set.of("catalogName", "entityType", "formulaClass"),
			Arrays.stream(FormulaCacheMismatchEvent.class.getDeclaredFields())
				.filter(field -> field.isAnnotationPresent(ExportMetricLabel.class))
				.map(Field::getName).collect(Collectors.toSet()));
		for (final Field field : FormulaCacheStatisticsEvent.class.getDeclaredFields()) {
			if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
			final ExportMetric metric = field.getAnnotation(ExportMetric.class);
			assertNotNull(metric, field.getName());
			assertEquals(Set.of("entries", "weightedSizeBytes", "maximumWeightBytes", "active")
				.contains(field.getName()) ? MetricType.GAUGE : MetricType.COUNTER, metric.metricType());
		}
		assertEquals("io_evitadb_cache_formula_cache_statistics_hits",
			MetricHandler.composeMetricName(FormulaCacheStatisticsEvent.class, "hits"));
		assertEquals("io_evitadb_cache_formula_cache_mismatch_total",
			MetricHandler.composeMetricName(FormulaCacheMismatchEvent.class, "total"));
	}

	/** Builds one tiny alive catalog whose repeated indexed query has a stable, cacheable formula. */
	@Nonnull
	private Evita startEngine(boolean enabled) {
		this.paths = createTestPaths("FormulaCacheMetricsTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).cache(
			CacheOptions.builder().enabled(enabled).minimalComplexityThreshold(0).verifyOneHitIn(1)
				.cacheSizeInBytes(100_000_000L).dropEntriesAfterSecondsOfInactivity(0).build()
		).build());
		this.evita.defineCatalog("cacheMetrics").updateViaNewSession(this.evita);
		this.evita.updateCatalog("cacheMetrics", writer -> {
			writer.defineEntitySchema("product").withoutGeneratedPrimaryKey()
				.withAttribute("code", Integer.class, attribute -> attribute.filterable()).updateVia(writer);
			for (int id = 1; id <= 8; id++) {
				writer.upsertEntity(writer.createNewEntity("product", id).setAttribute("code", id));
			}
			writer.goLiveAndClose();
		});
		return this.evita;
	}

	/** Executes a fresh query plan rather than reusing a formula's local memo. */
	private static void read(@Nonnull EvitaSessionContract session) {
		assertEquals(2, session.query(Query.query(collection("product"),
			filterBy(or(attributeEquals("code", 1), attributeEquals("code", 2))),
			require(debug(DebugMode.PREFER_INDEX_SCAN))), EntityReference.class).getTotalRecordCount());
	}

	/** Retrieves the registered production hooks without adding a production API used only by tests. */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static List<Runnable> engineHooks(@Nonnull Evita engine) throws Exception {
		final Field field = Evita.class.getDeclaredField("engineStatisticsHooks");
		field.setAccessible(true);
		return (List<Runnable>) field.get(engine);
	}

	/** Detaches only the periodic timer; tests invoke the actual registered emitter at deterministic boundaries. */
	@Nonnull
	private static Runnable detachCacheHook(@Nonnull Evita engine) throws Exception {
		final List<Runnable> hooks = engineHooks(engine).stream()
			.filter(hook -> hook.getClass().getSimpleName().equals("FormulaCacheStatisticsHook")).toList();
		assertEquals(1, hooks.size(), "An enabled engine must register its formula cache statistics hook");
		final Runnable hook = hooks.getFirst();
		assertTrue(FlightRecorder.removePeriodicEvent(hook));
		return hook;
	}

	/** Compares every exported value with its public supervisor accessor, including duration and weight units. */
	private static void assertStatistics(
		@Nonnull RecordedEvent event, @Nonnull Statistics current, @Nullable Statistics previous
	) throws Exception {
		for (final RecordComponent component : Statistics.class.getRecordComponents()) {
			final String name = component.getName();
			if (name.equals("witness")) continue;
			final Object value = component.getAccessor().invoke(current);
			final long expected;
			if (name.equals("active")) {
				expected = (boolean) value ? 1 : 0;
			} else if (List.of("entries", "weightedSizeBytes", "maximumWeightBytes").contains(name)) {
				expected = (long) value;
			} else {
				expected = Math.max(0, (long) value -
					(previous == null ? 0 : (long) component.getAccessor().invoke(previous)));
			}
			assertEquals(expected, event.getLong(name), name);
		}
	}

	/** Supplies distinct values for every field, including gauges and the unexported witness. */
	@Nonnull
	private static Statistics statistics(long offset, boolean active) {
		return new Statistics(offset + 1, offset + 2, offset + 3, offset + 4, offset + 5,
			offset + 6, offset + 7, offset + 8, offset + 9, offset + 10, offset + 11, offset + 12,
			offset + 13, offset + 14, offset + 15, offset + 16, offset + 17, offset + 18, active, "diagnostic");
	}

	/** Reads the unlabelled Prometheus series, allowing existing observations from earlier tests in this fork. */
	private static double counterValue(@Nonnull String name) {
		for (final MetricSnapshot metric : PrometheusRegistry.defaultRegistry.scrape()) {
			if (metric instanceof CounterSnapshot counter && name.equals(metric.getMetadata().getName())) {
				return counter.getDataPoints().stream().mapToDouble(point -> point.getValue()).sum();
			}
		}
		return 0;
	}
}
