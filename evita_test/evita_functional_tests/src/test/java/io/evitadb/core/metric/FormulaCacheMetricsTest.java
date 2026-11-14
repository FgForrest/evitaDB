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
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.FormulaCacheSupervisor.Statistics;
import io.evitadb.core.cache.FormulaCacheTestSessions;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.core.metric.event.cache.FormulaCacheMismatchEvent;
import io.evitadb.core.metric.event.cache.FormulaCacheStatisticsEvent;
import io.evitadb.core.metric.event.system.EvitaStatisticsEvent;
import io.evitadb.externalApi.observability.configuration.ObservabilityOptions;
import io.evitadb.externalApi.observability.metric.EvitaJfrEventRegistry;
import io.evitadb.externalApi.observability.metric.MetricHandler;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.CounterSnapshot.CounterDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Label;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.ValueDescriptor;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises cache metrics through the real engine, JFR recordings and the Prometheus metric handler. The engine's
 * own periodic hook is driven by JFR with a short period, so no test reaches into the engine. Isolation prevents
 * other engines in this JVM from contributing to the unlabelled engine-wide statistics series.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula cache metrics")
@Tag(ENGINE)
@Tag(CACHE)
@Tag(OBSERVABILITY)
@Tag(OBSERVABILITY_API)
@Isolated
class FormulaCacheMetricsTest implements EvitaTestSupport {
	/** JFR name of the periodic statistics event. */
	private static final String STATISTICS_EVENT = "io.evitadb.cache.FormulaCacheStatistics";
	/** JFR name of the per-mismatch event. */
	private static final String MISMATCH_EVENT = "io.evitadb.cache.FormulaCacheMismatch";
	/** Catalog every test engine creates and queries. */
	private static final String CATALOG = "cacheMetrics";
	/** Period short enough for JFR to invoke the registered periodic hooks many times within one test. */
	private static final Duration EMISSION_PERIOD = Duration.ofMillis(100);
	/** Fields JFR adds to every event next to the declared ones. */
	private static final Set<String> JFR_FIELDS = Set.of("startTime", "duration", "eventThread", "stackTrace");
	/** Counters, exported as the difference from the previous emission. */
	private static final Map<String, ToLongFunction<Statistics>> COUNTERS = counters();
	/** Gauges, exported as the value at the emission. */
	private static final Map<String, ToLongFunction<Statistics>> GAUGES = gauges();
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

	/**
	 * The metric handler adds every event's counter value to its Prometheus counter, so the emitted values must sum
	 * to the cumulative totals: an emission without work in between exports nothing, and a gauge is set, not added.
	 */
	@Test
	@DisplayName("Exports deltas summing to the cumulative totals, current gauges and the mismatch labels")
	void shouldExportSupervisorValuesAsDeltas() throws Exception {
		final Evita engine = startEngine(true);
		final String hitsName = MetricHandler.composeMetricName(FormulaCacheStatisticsEvent.class, "hits");
		final double baseline = counterValue(hitsName);
		final Emissions emissions = new Emissions();
		try (final RecordingStream stream = new RecordingStream()) {
			stream.setReuse(false);
			stream.enable(FormulaCacheStatisticsEvent.class).withPeriod(EMISSION_PERIOD);
			stream.onEvent(STATISTICS_EVENT, emissions::add);
			stream.startAsync();
			// the handler registers the engine's hooks; reads start only once its stream receives events
			startMetricHandler(engine);
			final Statistics first;
			final Statistics second;
			try (final EvitaSessionContract reader = FormulaCacheTestSessions.readOnly(engine, CATALOG)) {
				read(reader);
				read(reader);
				first = cacheStatistics(engine);
				assertTrue(first.hits() > 0);
				assertTrue(first.misses() > 0);
				assertTrue(first.admissions() > 0);
				assertTrue(first.verifiedHits() > 0);
				final List<RecordedEvent> fenced = emissions.await(
					events -> sum(events, "hits") >= first.hits(), "An emission must follow the first reads");
				assertEquals(first.hits(), sum(fenced, "hits"), "The emitted hits must sum to the cumulative total");
				final List<RecordedEvent> idle = emissions.await(
					events -> events.size() > fenced.size(), "JFR must keep invoking the hook");
				for (final RecordedEvent event : idle.subList(fenced.size(), idle.size())) {
					assertEquals(0L, event.getLong("hits"), "An emission must not repeat the running total");
				}
				for (int i = 0; i < 3; i++) read(reader);
				second = cacheStatistics(engine);
				assertEquals(3 * first.hits(), second.hits() - first.hits(), "Three identical warm queries");
			}
			final List<RecordedEvent> all = emissions.await(
				events -> COUNTERS.entrySet().stream()
					.allMatch(counter -> sum(events, counter.getKey()) >= counter.getValue().applyAsLong(second)),
				"An emission must follow the last reads");
			for (final Entry<String, ToLongFunction<Statistics>> counter : COUNTERS.entrySet()) {
				assertEquals(counter.getValue().applyAsLong(second), sum(all, counter.getKey()), counter.getKey());
			}
			final List<RecordedEvent> settled = emissions.await(
				events -> events.size() > all.size(), "JFR must keep invoking the hook");
			assertStatistics(settled.getLast(), second, second);

			emissions.await(events -> counterValue(hitsName) >= baseline + second.hits(),
				"The handler must add every emission to its counter");
			assertEquals(second.hits(), counterValue(hitsName) - baseline, "The counter must add deltas only");
			for (final String counter : COUNTERS.keySet()) {
				assertInstanceOf(CounterSnapshot.class, metric(FormulaCacheStatisticsEvent.class, counter), counter);
			}
			for (final String gauge : GAUGES.keySet()) {
				assertInstanceOf(GaugeSnapshot.class, metric(FormulaCacheStatisticsEvent.class, gauge), gauge);
			}
			assertEquals(second.entries(), ((GaugeSnapshot) metric(FormulaCacheStatisticsEvent.class, "entries"))
				.getDataPoints().getFirst().getValue(), "A gauge must hold the current value, not a sum");

			final String formulaClass = FormulaCacheMetricsTest.class.getName();
			final CounterDataPointSnapshot earlier = mismatchPoint(formulaClass);
			final double mismatchBaseline = earlier == null ? 0 : earlier.getValue();
			new FormulaCacheMismatchEvent(CATALOG, null, formulaClass).commit();
			emissions.await(events -> {
				final CounterDataPointSnapshot point = mismatchPoint(formulaClass);
				return point != null && point.getValue() > mismatchBaseline;
			}, "The handler must export the mismatch counter");
			final CounterDataPointSnapshot mismatch = mismatchPoint(formulaClass);
			assertNotNull(mismatch);
			final Set<String> labels = CollectionUtils.createHashSet(3);
			for (final Label label : mismatch.getLabels()) labels.add(label.getName());
			assertEquals(Set.of("catalogName", "entityType", "formulaClass"), labels);
			assertEquals(CATALOG, mismatch.getLabels().get("catalogName"));
			assertEquals("N/A", mismatch.getLabels().get("entityType"));
			assertEquals(mismatchBaseline + 1, mismatch.getValue(), "One event must count once");
		}
	}

	/** Work done before any recording enables the event must reach the first enabled emission whole. */
	@Test
	@DisplayName("Keeps totals until the first enabled emission")
	void shouldKeepTotalsUntilFirstEnabledEmission() throws Exception {
		final Evita engine = startEngine(true);
		assertFalse(EventType.getEventType(FormulaCacheStatisticsEvent.class).isEnabled(),
			"Another recording enables the statistics event, so the first emission is not under test control");
		engine.emitStartObservabilityEvents();
		// reinitializing observability must keep one emitter and its engine-lifetime delta baseline
		engine.emitStartObservabilityEvents();
		try (final EvitaSessionContract reader = FormulaCacheTestSessions.readOnly(engine, CATALOG)) {
			read(reader);
			read(reader);
		}
		final Statistics totals = cacheStatistics(engine);
		assertTrue(totals.hits() > 0, "The reads must hit the cache");
		assertTrue(totals.admissions() > 0, "The reads must admit entries");
		final Emissions emissions = new Emissions();
		try (final RecordingStream stream = new RecordingStream()) {
			stream.setReuse(false);
			stream.enable(FormulaCacheStatisticsEvent.class).withPeriod(EMISSION_PERIOD);
			stream.onEvent(STATISTICS_EVENT, emissions::add);
			stream.startAsync();
			final List<RecordedEvent> events = emissions.await(
				recorded -> recorded.size() >= 3, "JFR must invoke the hook once the event is enabled");
			assertStatistics(events.getFirst(), totals, null);
			for (final RecordedEvent later : events.subList(1, events.size())) {
				assertStatistics(later, totals, totals);
			}
		}
	}

	/** Disabled configuration creates neither the emitter nor a cache, and ordinary queries emit neither event. */
	@Test
	@DisplayName("Emits no cache events with the cache disabled")
	void shouldEmitNothingWhenCacheIsDisabled() throws Exception {
		final Evita engine = startEngine(false);
		final List<RecordedEvent> cacheEvents = new CopyOnWriteArrayList<>();
		final Emissions controls = new Emissions();
		try (final RecordingStream stream = new RecordingStream()) {
			stream.setReuse(false);
			stream.enable(FormulaCacheStatisticsEvent.class).withPeriod(EMISSION_PERIOD);
			stream.enable(FormulaCacheMismatchEvent.class);
			stream.enable(EvitaStatisticsEvent.class).withPeriod(EMISSION_PERIOD);
			stream.onEvent(STATISTICS_EVENT, cacheEvents::add);
			stream.onEvent(MISMATCH_EVENT, cacheEvents::add);
			stream.onEvent(EventType.getEventType(EvitaStatisticsEvent.class).getName(), controls::add);
			stream.startAsync();
			engine.emitStartObservabilityEvents();
			try (final EvitaSessionContract reader = FormulaCacheTestSessions.readOnly(engine, CATALOG)) {
				for (int i = 0; i < 4; i++) read(reader);
			}
			assertNull(engine.getFormulaCacheStatistics());
			// the engine's own periodic hooks keep firing, so the silence of the cache events is not vacuous
			controls.await(events -> events.size() >= 2, "JFR must invoke the engine's periodic hooks");
		}
		assertTrue(cacheEvents.isEmpty(), () -> "Unexpected cache events: " + cacheEvents);
	}

	/** Distinct nonzero fields detect wiring errors even for counters not exercised by the small query fixture. */
	@Test
	@DisplayName("Maps every counter and gauge and clamps regressing counters")
	void shouldMapAllFieldsAndClampRegressingCounters() throws Exception {
		final Statistics first = fabricated(0, true);
		final Statistics second = fabricated(100, false);
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
				.filter(event -> event.getEventType().getName().equals(STATISTICS_EVENT))
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
	@DisplayName("Registers fresh event names, the cache group, the period and conventional metric names")
	void shouldRegisterEventsAndMetricNames() {
		final EventType statistics = EventType.getEventType(FormulaCacheStatisticsEvent.class);
		// JFR exposes the declared period as the default of the period setting once a periodic hook exists
		final Runnable probe = () -> { };
		FlightRecorder.addPeriodicEvent(FormulaCacheStatisticsEvent.class, probe);
		try {
			assertEquals("1m", statistics.getSettingDescriptors().stream()
				.filter(setting -> setting.getName().equals("period"))
				.findFirst().orElseThrow().getDefaultValue());
		} finally {
			FlightRecorder.removePeriodicEvent(probe);
		}
		for (final Class<? extends CustomMetricsExecutionEvent> event :
			List.of(FormulaCacheStatisticsEvent.class, FormulaCacheMismatchEvent.class)) {
			assertSame(event, EvitaJfrEventRegistry.getEventClass(event.getName()));
			assertTrue(EvitaJfrEventRegistry.getEventClasses().contains(event));
			assertEquals("io.evitadb.cache", EvitaJfrEventRegistry.getMetricsGroup(event));
			assertTrue(List.of(EvitaJfrEventRegistry.getEventClassesFromPackage("io.evitadb.cache")
				.orElseThrow()).contains(event));
		}
		assertEquals(STATISTICS_EVENT, statistics.getName());
		assertEquals(MISMATCH_EVENT, EventType.getEventType(FormulaCacheMismatchEvent.class).getName());
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
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(CATALOG, writer -> {
			writer.defineEntitySchema("product").withoutGeneratedPrimaryKey()
				.withAttribute("code", Integer.class, attribute -> attribute.filterable()).updateVia(writer);
			for (int id = 1; id <= 8; id++) {
				writer.upsertEntity(writer.createNewEntity("product", id).setAttribute("code", id));
			}
			writer.goLiveAndClose();
		});
		return this.evita;
	}

	/**
	 * Starts a Prometheus metric handler for both cache events and waits until its recording stream runs, so no
	 * emission carrying work can precede it. The handler registers the engine's periodic hooks itself.
	 */
	private static void startMetricHandler(@Nonnull Evita engine) throws InterruptedException {
		final CountDownLatch handlerStarted = new CountDownLatch(1);
		final FlightRecorderListener listener = new FlightRecorderListener() {
			/** Fences the handler's stream startup before the test produces any work. */
			@Override
			public void recordingStateChanged(@Nonnull Recording recording) {
				if (recording.getState() == RecordingState.RUNNING) handlerStarted.countDown();
			}
		};
		FlightRecorder.addListener(listener);
		try {
			new MetricHandler(new ObservabilityOptions(
				null, "localhost:5555", null, null, null, null, null,
				List.of(FormulaCacheStatisticsEvent.class.getName(), FormulaCacheMismatchEvent.class.getName()),
				null, null, null
			)).registerHandlers(engine);
			assertTrue(handlerStarted.await(30, SECONDS), "The handler's recording stream must be running");
		} finally {
			FlightRecorder.removeListener(listener);
		}
	}

	/** Executes a fresh query plan rather than reusing a formula's local memo. */
	private static void read(@Nonnull EvitaSessionContract session) {
		assertEquals(2, session.query(Query.query(collection("product"),
			filterBy(or(attributeEquals("code", 1), attributeEquals("code", 2))),
			require(debug(DebugMode.PREFER_INDEX_SCAN))), EntityReference.class).getTotalRecordCount());
	}

	/** Returns the statistics of an engine configured with the cache enabled. */
	@Nonnull
	private static Statistics cacheStatistics(@Nonnull Evita engine) {
		final Statistics statistics = engine.getFormulaCacheStatistics();
		assertNotNull(statistics);
		return statistics;
	}

	/** Sums one exported field over the emissions. */
	private static long sum(@Nonnull List<RecordedEvent> events, @Nonnull String field) {
		long sum = 0;
		for (final RecordedEvent event : events) sum += event.getLong(field);
		return sum;
	}

	/**
	 * Compares every exported value with its supervisor accessor, including duration and weight units, and requires
	 * the event to export nothing beyond the values compared here.
	 */
	private static void assertStatistics(
		@Nonnull RecordedEvent event, @Nonnull Statistics current, @Nullable Statistics previous
	) {
		final Set<String> exported = CollectionUtils.createHashSet(COUNTERS.size() + GAUGES.size());
		for (final ValueDescriptor field : event.getFields()) {
			if (!JFR_FIELDS.contains(field.getName())) exported.add(field.getName());
		}
		final Set<String> compared = CollectionUtils.createHashSet(COUNTERS.size() + GAUGES.size());
		compared.addAll(COUNTERS.keySet());
		compared.addAll(GAUGES.keySet());
		assertEquals(compared, exported);
		for (final Entry<String, ToLongFunction<Statistics>> counter : COUNTERS.entrySet()) {
			final long base = previous == null ? 0 : counter.getValue().applyAsLong(previous);
			assertEquals(Math.max(0, counter.getValue().applyAsLong(current) - base),
				event.getLong(counter.getKey()), counter.getKey());
		}
		for (final Entry<String, ToLongFunction<Statistics>> gauge : GAUGES.entrySet()) {
			assertEquals(gauge.getValue().applyAsLong(current), event.getLong(gauge.getKey()), gauge.getKey());
		}
	}

	/** Lists the counters by exported field name. */
	@Nonnull
	private static Map<String, ToLongFunction<Statistics>> counters() {
		final Map<String, ToLongFunction<Statistics>> counters = CollectionUtils.createLinkedHashMap(15);
		counters.put("hits", Statistics::hits);
		counters.put("misses", Statistics::misses);
		counters.put("admissions", Statistics::admissions);
		counters.put("duplicateAdmissions", Statistics::duplicateAdmissions);
		counters.put("duplicateComputeMillis", Statistics::duplicateComputeMillis);
		counters.put("duplicateBytes", Statistics::duplicateBytes);
		counters.put("refusedOversized", Statistics::refusedOversized);
		counters.put("refusedUnsized", Statistics::refusedUnsized);
		counters.put("prefetchBypasses", Statistics::prefetchBypasses);
		counters.put("transactionBypasses", Statistics::transactionBypasses);
		counters.put("evictions", Statistics::evictions);
		counters.put("evictedWeightBytes", Statistics::evictedWeightBytes);
		counters.put("verifiedHits", Statistics::verifiedHits);
		counters.put("verificationMillis", Statistics::verificationMillis);
		counters.put("mismatches", Statistics::mismatches);
		return Collections.unmodifiableMap(counters);
	}

	/** Lists the gauges by exported field name; the active flag is exported as 1 or 0. */
	@Nonnull
	private static Map<String, ToLongFunction<Statistics>> gauges() {
		final Map<String, ToLongFunction<Statistics>> gauges = CollectionUtils.createLinkedHashMap(4);
		gauges.put("entries", Statistics::entries);
		gauges.put("weightedSizeBytes", Statistics::weightedSizeBytes);
		gauges.put("maximumWeightBytes", Statistics::maximumWeightBytes);
		gauges.put("active", statistics -> statistics.active() ? 1 : 0);
		return Collections.unmodifiableMap(gauges);
	}

	/** Supplies distinct values for every field, including gauges and the unexported witness. */
	@Nonnull
	private static Statistics fabricated(long offset, boolean active) {
		return new Statistics(offset + 1, offset + 2, offset + 3, offset + 4, offset + 5,
			offset + 6, offset + 7, offset + 8, offset + 9, offset + 10, offset + 11, offset + 12,
			offset + 13, offset + 14, offset + 15, offset + 16, offset + 17, offset + 18, active, "diagnostic");
	}

	/** Finds the registered metric of one exported field; a counter's snapshot name may omit the total suffix. */
	@Nullable
	private static MetricSnapshot metric(
		@Nonnull Class<? extends CustomMetricsExecutionEvent> event, @Nonnull String field
	) {
		final String name = MetricHandler.composeMetricName(event, field);
		for (final MetricSnapshot metric : PrometheusRegistry.defaultRegistry.scrape()) {
			final String registered = metric.getMetadata().getName();
			if (name.equals(registered) || name.equals(registered + "_total")) {
				return metric;
			}
		}
		return null;
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

	/** Returns the mismatch counter's data point exported for the formula class, if the handler exported it. */
	@Nullable
	private static CounterDataPointSnapshot mismatchPoint(@Nonnull String formulaClass) {
		if (metric(FormulaCacheMismatchEvent.class, "total") instanceof CounterSnapshot counter) {
			for (final CounterDataPointSnapshot point : counter.getDataPoints()) {
				if (formulaClass.equals(point.getLabels().get("formulaClass"))) {
					return point;
				}
			}
		}
		return null;
	}

	/**
	 * Collects statistics emissions delivered by a recording stream and lets the test wait for a condition over
	 * them. Every delivered emission re-evaluates the condition, so a wait never sleeps for a fixed time.
	 */
	private static final class Emissions {
		/** Guards the collected emissions. */
		private final ReentrantLock lock = new ReentrantLock();
		/** Signalled whenever an emission arrives. */
		private final Condition arrived = this.lock.newCondition();
		/** Emissions in delivery order. */
		private final List<RecordedEvent> events = new ArrayList<>(64);

		/** Records one delivered emission and wakes the waiting test. */
		void add(@Nonnull RecordedEvent event) {
			this.lock.lock();
			try {
				this.events.add(event);
				this.arrived.signalAll();
			} finally {
				this.lock.unlock();
			}
		}

		/**
		 * Waits up to thirty seconds until the condition holds over the emissions delivered so far.
		 *
		 * @return a copy of the emissions the condition held for
		 */
		@Nonnull
		List<RecordedEvent> await(
			@Nonnull Predicate<List<RecordedEvent>> condition, @Nonnull String message
		) throws InterruptedException {
			final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
			this.lock.lock();
			try {
				while (!condition.test(this.events)) {
					final long remaining = deadline - System.nanoTime();
					if (remaining <= 0) {
						fail(message + "; emissions so far: " + this.events.size());
					}
					this.arrived.awaitNanos(remaining);
				}
				return List.copyOf(this.events);
			} finally {
				this.lock.unlock();
			}
		}
	}
}
