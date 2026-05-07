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

package io.evitadb.core.cdc;

import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.cdc.SystemCaptureArea;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted reproducible ordering test for {@link HostSystemEvent} delivery under contention.
 *
 * Spawns multiple concurrent emitter threads that interleave host-event emissions through the
 * shared {@link SystemChangeObserver}, while a single subscriber records the arrival order. The
 * publisher serializes host-event dispatch internally (via per-subscriber lock in
 * {@link io.evitadb.core.cdc.DefaultChangeCaptureSubscription#deliverImmediate}), so even with
 * heavy concurrent emission the subscriber must observe events in some total linear order — and
 * the per-emitter-thread sub-sequence (events emitted by the same thread, identified by encoding
 * thread id into the catalog name) must arrive in the same relative order they were emitted.
 *
 * Note: due to the live-tail / no-replay semantics of host events, events emitted while the
 * subscriber's demand counter is exhausted are silently dropped. We compensate by submitting at a
 * controlled rate and asserting only on the events the subscriber actually receives — the
 * relative-ordering invariant we test is observable regardless of how many events were dropped.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("HostSystemEvent ordering should")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(ENGINE)
@Tag(CDC)
class HostSystemEventOrderingTest implements EvitaTestSupport {

	private static final String DATA_SET_NAME = "hostSystemEventOrdering";

	@DataSet(value = DATA_SET_NAME, destroyAfterClass = true)
	protected void setUp(@Nonnull Evita evita) {
		// no setup necessary - the test constructs its own host events
	}

	/**
	 * Each emitter thread emits events for a distinct catalog; per-emitter the events are
	 * monotonically numbered through a stamped engine version. Asserts that for any single emitter
	 * thread, the subscribed-events for that thread appear in monotonically non-decreasing
	 * sequence order (the publisher must not reorder events produced by the same emitter).
	 */
	@UseDataSet(value = DATA_SET_NAME, destroyAfterTest = true)
	@Test
	@DisplayName("preserve per-emitter order under concurrent load")
	void shouldPreservePerEmitterOrderUnderLoad(@Nonnull Evita evita) throws Exception {
		final int emitterCount = 4;
		final int eventsPerEmitter = 50;

		final ChangeSystemCaptureRequest infraRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(evita.getEngineState().version() + 1)
			.content(ChangeCaptureContent.BODY)
			.criteria(new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST))
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(infraRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			final ExecutorService executor = Executors.newFixedThreadPool(emitterCount);
			try {
				final CountDownLatch start = new CountDownLatch(1);
				final CountDownLatch done = new CountDownLatch(emitterCount);

				for (int t = 0; t < emitterCount; t++) {
					final int threadIndex = t;
					executor.submit(() -> {
						try {
							start.await();
							for (int i = 0; i < eventsPerEmitter; i++) {
								// catalogName encodes both the emitter id and sequence number,
								// while currentEngineVersion encodes the sequence number for ordering checks
								evita.getChangeObserver().processHostEvent(
									new HostSystemEvent.CatalogInstalledIntoLiveView(
										"emitter_" + threadIndex + "_" + i,
										CatalogState.ALIVE,
										i
									)
								);
							}
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						} finally {
							done.countDown();
						}
					});
				}

				start.countDown();
				assertTrue(done.await(15, TimeUnit.SECONDS), "Emitters should finish within 15s");

				// All emitters have returned, but `processHostEvent` submits delivery tasks to
				// `cdcExecutor` (see ChangeSystemCaptureSharedPublisher#processHostEvent). Poll the
				// subscriber for a short quiescence window — the executor typically drains within
				// tens of milliseconds, so the happy path finishes well before the safety cap.
				final long quiescenceWindowMs = 100L;
				final long maxWaitMs = 5_000L;
				final long deadline = System.currentTimeMillis() + maxWaitMs;
				int previousSize = -1;
				long lastChangeTime = System.currentTimeMillis();
				while (System.currentTimeMillis() < deadline) {
					final int currentSize = subscriber.getItems().size();
					if (currentSize != previousSize) {
						previousSize = currentSize;
						lastChangeTime = System.currentTimeMillis();
					} else if (System.currentTimeMillis() - lastChangeTime >= quiescenceWindowMs) {
						break;
					}
					Thread.sleep(10L);
				}

				// per emitter, collect the sequence numbers we saw and assert monotonic order
				final List<List<Integer>> perEmitter = new ArrayList<>();
				for (int t = 0; t < emitterCount; t++) {
					perEmitter.add(new ArrayList<>());
				}
				for (final ChangeSystemCapture capture : subscriber.getItems()) {
					if (capture.body() instanceof HostSystemEvent.CatalogInstalledIntoLiveView event) {
						final String name = event.catalogName();
						if (!name.startsWith("emitter_")) {
							continue;
						}
						final String[] parts = name.split("_");
						final int emitterId = Integer.parseInt(parts[1]);
						final int sequence = Integer.parseInt(parts[2]);
						perEmitter.get(emitterId).add(sequence);
					}
				}
				// assert per-emitter monotonic order
				for (int t = 0; t < emitterCount; t++) {
					final List<Integer> sequences = perEmitter.get(t);
					for (int i = 1; i < sequences.size(); i++) {
						assertTrue(
							sequences.get(i) > sequences.get(i - 1),
							"Emitter " + t + " events delivered out of order at index " + i
								+ ": " + sequences.get(i - 1) + " -> " + sequences.get(i)
						);
					}
				}
			} finally {
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate");
			}
		}
	}
}
