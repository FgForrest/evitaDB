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

package io.evitadb.externalApi.http.routing;

import com.linecorp.armeria.common.*;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Sweeps readers across prefix publications without a server or a production test hook. The neighbouring
 * `PrefixRoutingServiceTest` covers deterministic isolation and retention of an already selected handler.
 *
 * Defaults: `routing.publication.seconds=25`, `routing.publication.readers=4`,
 * `routing.publication.minChanges=10`. Each reader must see that many B identity changes. Visibility is
 * probabilistic by nature: removing `volatile` need not fail on every JVM; this is no Java memory model proof.
 * Worker failures are retained and asserted on the test thread, after all workers have stopped.
 *
 * Calibration mutations (the expected timings are estimates). In `PrefixRoutingService#change`, immediately
 * before `this.table = new PrefixRoutingTable(exactPaths, prefixes);`, insert exactly
 * `this.table = new PrefixRoutingTable(Map.of(), Map.of());`. Expect A/B 404 within the default 25-second
 * sweep, usually in its first seconds. Restore it, then independently insert `removePrefixPath(path);`
 * immediately before `return change(path, handler, false);` in `replacePrefixPath`. Expect B 404 within
 * the same sweep, usually in its first seconds. Neither mutation needs an artificial pause.
 *
 * On an otherwise idle machine, run each mutation separately with:
 * `mvn -P longRunning -pl evita_test/evita_long_running_tests -am test -DreleaseFixtures.skip=true
 * -DroaringBitmap.skipTests=true -Dtest=LongRunningPrefixRoutingPublicationTest
 * -Dsurefire.failIfNoSpecifiedTests=false`. Restore the source and touch it between builds, then run the
 * restored test. If either mutation survives, increase duration/readers and recalibrate before trusting green.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
@DisplayName("Long-running prefix routing publication")
class LongRunningPrefixRoutingPublicationTest {
	private static final AttributeKey<HttpService> REACHED =
		AttributeKey.valueOf(LongRunningPrefixRoutingPublicationTest.class, "reached");

	/** Checks identity, visibility and actual read/write overlap throughout the configured duration. */
	@Test
	@DisplayName("Readers see whole routing tables and new publications during concurrent churn")
	void shouldPublishCompleteVisibleTablesDuringChurn() throws Exception {
		final int seconds = Integer.getInteger("routing.publication.seconds", 25);
		final int readers = Integer.getInteger("routing.publication.readers", 4);
		final int minChanges = Integer.getInteger("routing.publication.minChanges", 10);
		assertTrue(seconds > 0 && readers > 0 && minChanges > 0, "Positive stress settings required");
		final PrefixRoutingService router = new PrefixRoutingService();
		final HttpService stable = handler();
		final HttpService[] versions = {handler(), handler()};
		final String[] others = new String[9];
		final HttpService[] otherHandlers = new HttpService[9];
		router.addPrefixPath("/A", stable).addExactPath("/A/exact", stable)
			.addPrefixPath("/BBBB", versions[0]);
		for (int i = 0; i < others.length; i++) {
			others[i] = "/" + String.valueOf((char) ('C' + i)).repeat(i + 2);
			otherHandlers[i] = handler();
			router.addExactPath(others[i] + "/exact", otherHandlers[i]);
		}
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		final AtomicLong publications = new AtomicLong();
		final AtomicLong unrelatedWrites = new AtomicLong();
		final AtomicLong between = new AtomicLong();
		final CountDownLatch ready = new CountDownLatch(readers + 2);
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(readers + 2);
		final long[] reads = new long[readers];
		final long[] changes = new long[readers];
		final long[] overlapping = new long[readers];
		final long[] deadline = new long[1];
		final ExecutorService pool = Executors.newFixedThreadPool(readers + 2, task -> {
			final Thread thread = new Thread(task, "prefix-publication");
			thread.setDaemon(true);
			return thread;
		});
		try {
			for (int i = 0; i < readers; i++) {
				final int reader = i;
				pool.execute(() -> worker(ready, start, done, failure, () -> {
					HttpService last = null;
					while (System.nanoTime() < deadline[0]) {
						final long interval = between.get();
						check(router, "/A/tail", stable, null, false);
						check(router, "/A/exact", stable, null, false);
						final HttpService reached = check(router, "/BBBB/tail", versions[0], versions[1], false);
						if (last != null && last != reached) {
							changes[reader]++;
						}
						last = reached;
						final int index = (int) (reads[reader] % others.length);
						check(router, others[index] + "/tail", otherHandlers[index], null, true);
						check(router, others[index] + "/exact", otherHandlers[index], null, false);
						reads[reader]++;
						if ((interval & 1) != 0 && between.get() == interval) {
							overlapping[reader]++;
						}
					}
					return null;
				}));
			}
			pool.execute(() -> worker(ready, start, done, failure, () -> {
				while (System.nanoTime() < deadline[0]) {
					router.replacePrefixPath("/BBBB", versions[0]);
					publications.incrementAndGet();
					// Odd identifies an interval strictly after one publication and before the next.
					between.incrementAndGet();
					// Detection widener, not polling: slower scheduling only strengthens overlap evidence.
					LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
					between.incrementAndGet();
					router.replacePrefixPath("/BBBB", versions[1]);
					publications.incrementAndGet();
				}
				return null;
			}));
			pool.execute(() -> worker(ready, start, done, failure, () -> {
				while (System.nanoTime() < deadline[0]) {
					for (int i = 0; i < others.length; i++) {
						router.addPrefixPath(others[i], otherHandlers[i]);
						router.addExactPath(others[i] + "/exact", otherHandlers[i]);
						router.removePrefixPath(others[i]);
						unrelatedWrites.addAndGet(3);
					}
				}
				return null;
			}));
			assertTrue(ready.await(30, TimeUnit.SECONDS), "Workers did not start");
			deadline[0] = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
			start.countDown();
			assertTrue(done.await(seconds + 30L, TimeUnit.SECONDS), "Workers did not stop");
			System.out.printf("B publications=%d unrelated writes=%d%n", publications.get(), unrelatedWrites.get());
			for (int i = 0; i < readers; i++) {
				System.out.printf("reader=%d reads=%d changes=%d between-publications=%d%n",
					i, reads[i], changes[i], overlapping[i]);
			}

			assertNull(failure.get(), () -> "First publication violation: " + failure.get());
			assertTrue(publications.get() >= 100 && unrelatedWrites.get() >= 100, "Insufficient writer activity");
			for (int i = 0; i < readers; i++) {
				assertTrue(reads[i] >= 100, "Reader " + i + " completed too few reads");
				assertTrue(changes[i] >= minChanges, "Reader " + i + " did not observe new B handlers");
				assertTrue(overlapping[i] >= 10, "Reader " + i + " did not overlap publications");
			}
		} finally {
			start.countDown();
			pool.shutdownNow();
			assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "Publication pool leaked");
		}
	}

	/** Records the actual service object on the real request context; every binding owns a distinct instance. */
	@Nonnull
	private static HttpService handler() {
		return new HttpService() {
			/** Marks this exact handler instance and completes an ordinary Armeria response. */
			@Nonnull
			@Override
			public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) {
				ctx.setAttr(REACHED, this);
				return HttpResponse.of(HttpStatus.OK);
			}
		};
	}

	/** Checks every result, including the status of a miss; errors propagate to the worker's failure collector. */
	@Nullable
	private static HttpService check(
		@Nonnull PrefixRoutingService router, @Nonnull String path, @Nonnull HttpService first,
		@Nullable HttpService second, boolean missingAllowed
	) throws Exception {
		final HttpRequest request = HttpRequest.of(
			com.linecorp.armeria.common.RequestHeaders.builder(HttpMethod.GET, path)
			.scheme("http").authority("localhost").build());
		final ServiceRequestContext context = ServiceRequestContext.builder(request).build();
		final AggregatedHttpResponse response = router.serve(context, request).aggregate().get(30, TimeUnit.SECONDS);
		final HttpService reached = context.attr(REACHED);
		if (response.status().equals(HttpStatus.NOT_FOUND) && missingAllowed && reached == null) {
			return null;
		}
		if (!response.status().equals(HttpStatus.OK) || (reached != first && reached != second) || reached == null) {
			throw new AssertionError(path + " reached " + reached + " with " + response.status());
		}
		return reached;
	}

	/** Starts all contenders together and preserves every uncaught worker failure on the test thread. */
	private static void worker(
		@Nonnull CountDownLatch ready, @Nonnull CountDownLatch start, @Nonnull CountDownLatch done,
		@Nonnull AtomicReference<Throwable> failure, @Nonnull Callable<?> task
	) {
		ready.countDown();
		try {
			if (!start.await(30, TimeUnit.SECONDS)) {
				throw new AssertionError("Start gate timed out");
			}
			task.call();
		} catch (Throwable ex) {
			failure.compareAndSet(null, ex);
		} finally {
			done.countDown();
		}
	}
}
