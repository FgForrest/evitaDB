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

package io.evitadb.index.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the two properties {@link SchemaCapabilityUsage} exists to provide and that nothing else in the suite can
 * see: **no recording is lost under concurrency**, and **a stamp costs at most one store per second** however hard the
 * holder is hammered.
 *
 * The second one is the reason the write is guarded rather than unconditional. A `filterable()` flag on a popular
 * attribute is requested by every query thread at once; an unconditional volatile store would push one cache line
 * around the machine at query rate for a reading whose whole purpose is to say *"last used three weeks ago"*. The
 * coarsening tests below pin that the guard both suppresses the redundant store and still lets the stamp advance -
 * a guard that only ever suppressed would freeze the stamp at its first value forever.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see SchemaCapabilityUsage
 */
@DisplayName("Schema capability usage")
@Tag(ENGINE)
@Tag(INDEXING)
@Tag(MANAGEMENT)
class SchemaCapabilityUsageTest {

	/** An arbitrary but recognisable instant, landing exactly on a second boundary so the coarsening is legible. */
	private static final long FIRST_MILLIS = 1_800_000_000_000L;
	/** The last millisecond of {@link #FIRST_MILLIS}' second - the extreme case the guard still has to suppress. */
	private static final long SAME_SECOND_MILLIS = 1_800_000_000_999L;
	/** The first millisecond of the next second - one millisecond later, and the guard has to let it through. */
	private static final long NEXT_SECOND_MILLIS = 1_800_000_001_000L;
	/** A minute later, for the cases where only "strictly a different second" matters. */
	private static final long LATER_MILLIS = 1_800_000_060_000L;
	/** Daemon threads, so a recording thread that stalled cannot keep the surefire JVM alive after the test ends. */
	private static final ThreadFactory DAEMON_THREADS = runnable -> {
		final Thread thread = new Thread(runnable, "schema-capability-recorder");
		thread.setDaemon(true);
		return thread;
	};

	@Nested
	@DisplayName("Fresh holder")
	class FreshHolderTest {

		@Test
		@DisplayName("Has counted nothing and stamps nothing")
		void shouldStartAtZeroWithoutStamps() {
			final SchemaCapabilityUsage usage = new SchemaCapabilityUsage();

			assertEquals(0L, usage.getRequestedCount());
			assertEquals(0L, usage.getUpdatedCount());
			// zero is the "never since the catalog was loaded" sentinel rather than an instant - the management
			// surface turns it into an absent timestamp instead of a date in 1970
			assertEquals(0L, usage.getLastRequestedAtMillis());
			assertEquals(0L, usage.getLastUpdatedAtMillis());
		}

		@Test
		@DisplayName("Stamps the moment observation of its capability began")
		void shouldRecordWhenObservationBegan() {
			final long before = System.currentTimeMillis();
			final SchemaCapabilityUsage usage = new SchemaCapabilityUsage();
			final long after = System.currentTimeMillis();

			// unlike the two "last at" stamps there is no "never" sentinel here - the reading is always set, and it is
			// what lets a client qualify a zero count ("never requested in the N minutes observed") honestly
			assertTrue(
				usage.getObservedSinceMillis() >= before,
				"Observation cannot have begun before the holder was constructed"
			);
			assertTrue(
				usage.getObservedSinceMillis() <= after,
				"Observation cannot have begun after the holder was constructed"
			);
		}

	}

	@Nested
	@DisplayName("Recording")
	class RecordingTest {

		@Test
		@DisplayName("Requesting a capability advances only the query side")
		void shouldAdvanceOnlyTheRequestedSideOnRecordRequested() {
			final SchemaCapabilityUsage usage = new SchemaCapabilityUsage();

			usage.recordRequested(FIRST_MILLIS);
			usage.recordRequested(LATER_MILLIS);

			assertEquals(2L, usage.getRequestedCount());
			assertEquals(0L, usage.getUpdatedCount(), "A request must not be counted as maintenance");
			assertEquals(LATER_MILLIS, usage.getLastRequestedAtMillis(), "The stamp is the last one");
			assertEquals(0L, usage.getLastUpdatedAtMillis());
		}

		@Test
		@DisplayName("Touching a capability advances only the mutation side")
		void shouldAdvanceOnlyTheUpdatedSideOnRecordUpdated() {
			final SchemaCapabilityUsage usage = new SchemaCapabilityUsage();

			usage.recordUpdated(FIRST_MILLIS);
			usage.recordUpdated(NEXT_SECOND_MILLIS);
			usage.recordUpdated(LATER_MILLIS);

			assertEquals(3L, usage.getUpdatedCount());
			assertEquals(0L, usage.getRequestedCount(), "Maintenance must not be counted as a request");
			assertEquals(LATER_MILLIS, usage.getLastUpdatedAtMillis(), "The stamp is the last one");
			assertEquals(0L, usage.getLastRequestedAtMillis());
		}

	}

	@Nested
	@DisplayName("Stamp coarsening")
	class StampCoarseningTest {

		@Test
		@DisplayName("A second recording within the same second leaves the first instant resident")
		void shouldKeepTheFirstStampWithinOneSecond() {
			final SchemaCapabilityUsage usage = new SchemaCapabilityUsage();

			usage.recordRequested(FIRST_MILLIS);
			usage.recordRequested(SAME_SECOND_MILLIS);

			assertEquals(2L, usage.getRequestedCount(), "Coarsening applies to the stamp, never to the count");
			assertEquals(
				FIRST_MILLIS, usage.getLastRequestedAtMillis(),
				"The redundant store was not suppressed - a hot capability then writes its stamp at query rate"
			);
		}

		@Test
		@DisplayName("A recording in a later second moves the stamp")
		void shouldMoveTheStampIntoALaterSecond() {
			final SchemaCapabilityUsage usage = new SchemaCapabilityUsage();

			usage.recordRequested(FIRST_MILLIS);
			usage.recordRequested(SAME_SECOND_MILLIS);
			usage.recordRequested(NEXT_SECOND_MILLIS);

			assertEquals(
				NEXT_SECOND_MILLIS, usage.getLastRequestedAtMillis(),
				"One millisecond past the boundary is a different second, and the guard has to let it through"
			);
		}

		@Test
		@DisplayName("The mutation stamp coarsens the same way")
		void shouldCoarsenTheUpdatedStampToo() {
			final SchemaCapabilityUsage usage = new SchemaCapabilityUsage();

			usage.recordUpdated(FIRST_MILLIS);
			usage.recordUpdated(SAME_SECOND_MILLIS);
			assertEquals(FIRST_MILLIS, usage.getLastUpdatedAtMillis());

			usage.recordUpdated(NEXT_SECOND_MILLIS);
			assertEquals(NEXT_SECOND_MILLIS, usage.getLastUpdatedAtMillis());
		}

		@Test
		@DisplayName("The two stamps coarsen independently of each other")
		void shouldCoarsenEachStampAgainstItsOwnValue() {
			// a shared "last recorded anything" guard would let the query side suppress the mutation side's very first
			// stamp whenever a query and a mutation land in the same second - which is the common case, not a corner
			final SchemaCapabilityUsage usage = new SchemaCapabilityUsage();

			usage.recordRequested(FIRST_MILLIS);
			usage.recordUpdated(SAME_SECOND_MILLIS);

			assertEquals(FIRST_MILLIS, usage.getLastRequestedAtMillis());
			assertEquals(SAME_SECOND_MILLIS, usage.getLastUpdatedAtMillis());
		}

	}

	@Nested
	@DisplayName("Concurrency")
	class ConcurrencyTest {

		@Test
		@DisplayName("Concurrent recordings all arrive - none is lost to a read-modify-write race")
		void shouldLoseNoRecordingUnderConcurrency() throws InterruptedException {
			// the calibration, and the reason the two counters are `LongAdder`s rather than plain `long` fields: swap
			// either increment for `this.requestedCount++` and this test fails, while nothing else in the suite
			// notices. The assertion is exact and interleaving-independent - every call has to show up in the total
			// however the threads were scheduled - so it belongs in the fast loop rather than among the timing sweeps
			final int threads = 8;
			final int recordingsPerThread = 2_000;
			final SchemaCapabilityUsage usage = new SchemaCapabilityUsage();
			final CountDownLatch start = new CountDownLatch(1);
			final CountDownLatch finished = new CountDownLatch(threads);
			final ExecutorService pool = Executors.newFixedThreadPool(threads, DAEMON_THREADS);
			try {
				for (int thread = 0; thread < threads; thread++) {
					pool.submit(
						() -> {
							try {
								// released together, so the increments genuinely overlap instead of running one pool
								// thread's whole batch before the next one starts
								start.await();
								for (int recording = 0; recording < recordingsPerThread; recording++) {
									usage.recordRequested(FIRST_MILLIS);
									usage.recordUpdated(LATER_MILLIS);
								}
							} catch (InterruptedException ex) {
								Thread.currentThread().interrupt();
							} finally {
								finished.countDown();
							}
						}
					);
				}
				start.countDown();
				assertTrue(
					finished.await(30, TimeUnit.SECONDS),
					"The recording threads did not finish within the budget - a counter is blocking rather than losing"
				);
			} finally {
				pool.shutdownNow();
			}

			final long expected = (long) threads * recordingsPerThread;
			assertEquals(expected, usage.getRequestedCount(), "A concurrent request recording was lost");
			assertEquals(expected, usage.getUpdatedCount(), "A concurrent mutation recording was lost");
			// the stamps are asserted only for the instant every thread passed in: which recording left it resident is
			// not a property this design claims, but the value can only ever be one of the two that were recorded
			assertEquals(FIRST_MILLIS, usage.getLastRequestedAtMillis());
			assertEquals(LATER_MILLIS, usage.getLastUpdatedAtMillis());
		}

	}

}
