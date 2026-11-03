/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.comparator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.COMPARATOR;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Verifies sweep admission through the real {@link CollationKeyCache#sweepAll()} implementation. An isolated class
 * loader gives this test its own registry and admission state, so other tests' lookups, go-lives and periodic sweeps
 * cannot affect its assertions. Only a per-locale cache's scan is mocked, allowing latches to hold the first scan
 * in flight until all competing calls have returned. No assertion depends on scan duration or shared cache counts.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Collation key cache single-flight sweeps")
@Tag(ENGINE)
@Tag(CACHE)
@Tag(COMPARATOR)
class CollationKeyCacheSingleFlightTest {

	/**
	 * Holds an admitted scan open while competing callers finish, then verifies that later boundaries are still
	 * admitted after both a successful scan and a failed one. Timeouts bound positive waits only; they never decide
	 * whether two scans overlap.
	 *
	 * @throws Exception if class loading, reflection or a worker fails
	 */
	@Test
	@DisplayName("should skip overlapping sweeps and admit subsequent sweeps even after failure")
	void shouldSkipOverlappingSweepsAndAdmitSubsequentSweepsEvenAfterFailure() throws Exception {
		final URL classes = CollationKeyCache.class.getProtectionDomain().getCodeSource().getLocation();
		try (
			URLClassLoader classLoader = new URLClassLoader(new URL[]{classes}, ClassLoader.getPlatformClassLoader());
			ExecutorService executor = Executors.newFixedThreadPool(5, Thread.ofPlatform().daemon().factory())
		) {
			final Class<?> cacheClass = classLoader.loadClass(CollationKeyCache.class.getName());
			assertNotSame(CollationKeyCache.class, cacheClass, "The test must not touch the process-wide registry.");
			final Method sweepAll = cacheClass.getMethod("sweepAll");
			final CountDownLatch scanEntered = new CountDownLatch(1);
			final CountDownLatch releaseScan = new CountDownLatch(1);
			final AtomicInteger scanCount = new AtomicInteger();
			final AtomicBoolean failNextScan = new AtomicBoolean();
			final IllegalStateException scanFailure = new IllegalStateException("Deliberate scan failure");
			final Object cache = mock(cacheClass, invocation -> {
				if (!invocation.getMethod().getName().equals("sweep")) {
					throw new AssertionError("Unexpected cache call: " + invocation.getMethod());
				}
				if (scanCount.incrementAndGet() == 1) {
					scanEntered.countDown();
					assertTrue(releaseScan.await(60, TimeUnit.SECONDS), "The held scan was not released.");
				}
				if (failNextScan.getAndSet(false)) {
					throw scanFailure;
				}
				return 7;
			});
			// Populate only the isolated registry; neither the shared registry nor the admission flag is modified.
			final Field instances = cacheClass.getDeclaredField("INSTANCES");
			instances.setAccessible(true);
			@SuppressWarnings("unchecked")
			final ConcurrentHashMap<Locale, Object> registry =
				(ConcurrentHashMap<Locale, Object>) instances.get(null);
			registry.put(Locale.ENGLISH, cache);

			final Future<Object> firstSweep = executor.submit(() -> sweepAll.invoke(null));
			try {
				assertTrue(scanEntered.await(60, TimeUnit.SECONDS), "The first sweep did not enter its scan.");
				final List<Future<Object>> competingSweeps = new ArrayList<>(4);
				for (int i = 0; i < 4; i++) {
					competingSweeps.add(executor.submit(() -> sweepAll.invoke(null)));
				}
				for (final Future<Object> competingSweep : competingSweeps) {
					assertEquals(
						0, competingSweep.get(60, TimeUnit.SECONDS),
						"An overlapping sweep must return zero without scanning or waiting."
					);
				}
				assertEquals(1, scanCount.get(), "Only the admitted sweep may scan the locale cache.");
			} finally {
				releaseScan.countDown();
			}
			assertEquals(7, firstSweep.get(60, TimeUnit.SECONDS));
			assertEquals(7, sweepAll.invoke(null), "A sequential call must establish another sweep boundary.");
			assertEquals(2, scanCount.get());

			failNextScan.set(true);
			final InvocationTargetException failure = assertThrows(
				InvocationTargetException.class, () -> sweepAll.invoke(null)
			);
			assertSame(scanFailure, failure.getCause());
			assertEquals(7, sweepAll.invoke(null), "A failed sweep must release admission in finally.");
			assertEquals(4, scanCount.get());
		}
	}

}
