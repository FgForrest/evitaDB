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

package io.evitadb.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the measurement helper every heap-size assertion in this repository is built on.
 *
 * # The property being defended
 *
 * {@link JolHeapSize#ownedSize} must return the **same number every time**, whatever else the JVM is doing. That is
 * not a nicety: the whole suite runs under surefire `parallel=all`, so a measurement that drifts under concurrent
 * load turns correct production arithmetic into an intermittent failure, and the natural response to such a failure
 * — relaxing the assertion — quietly destroys the only thing that makes these tests worth having.
 *
 * Two hazards were found the hard way and are pinned here:
 *
 * - JOL's own `GraphLayout.subtract` matches objects **by address**, so a GC between two walks leaves shared objects
 *   unsubtracted and inflates the result by a different amount each run. The helper subtracts by identity instead.
 * - Walking into a `Class` reaches its **lazily populated** reflection cache, which materialises the first time
 *   anything reflects on that class. That made a figure depend on which tests had run before it. The helper excludes
 *   `Class` objects and everything below them.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@DisplayName("JOL heap-size measurement")
class JolHeapSizeTest {

	/**
	 * A graph deep enough to be worth walking, holding objects of several shapes.
	 *
	 * @return a freshly built fixture
	 */
	@Nonnull
	private static List<Object> fixture() {
		final List<Object> graph = new ArrayList<>(64);
		for (int i = 0; i < 64; i++) {
			graph.add(new long[]{i, i + 1, i + 2});
			graph.add("element-" + i);
			graph.add(UUID.nameUUIDFromBytes(new byte[]{(byte) i}));
		}
		return graph;
	}

	@Nested
	@DisplayName("returns the same figure under any heap conditions")
	class Deterministic {

		@Test
		void shouldReturnAnIdenticalFigureAcrossRepeatedCalls() {
			final List<Object> graph = fixture();

			final long first = JolHeapSize.ownedSize(graph);
			for (int i = 0; i < 20; i++) {
				assertEquals(first, JolHeapSize.ownedSize(graph), "measurement drifted on repeat " + i);
			}
		}

		@Test
		void shouldReturnAnIdenticalFigureWhileAnotherThreadChurnsTheHeap() throws Exception {
			final List<Object> graph = fixture();
			final long quiet = JolHeapSize.ownedSize(graph);

			// the failure mode this defends against needs a collector that MOVES objects mid-measurement, so the
			// churn has to be real: a second thread allocating hard enough to force repeated young collections
			// while the walk is running
			final AtomicBoolean churning = new AtomicBoolean(true);
			final Thread allocator = new Thread(() -> {
				while (churning.get()) {
					final byte[][] garbage = new byte[256][];
					for (int i = 0; i < garbage.length; i++) {
						garbage[i] = new byte[4096];
					}
				}
			}, "heap-churn");
			allocator.setDaemon(true);
			allocator.start();
			try {
				for (int i = 0; i < 20; i++) {
					assertEquals(
						quiet,
						JolHeapSize.ownedSize(graph),
						"measurement drifted under concurrent allocation on repeat " + i
					);
				}
			} finally {
				churning.set(false);
				allocator.join(5_000);
			}
		}

		@Test
		void shouldReturnAnIdenticalFigureAcrossExplicitCollections() {
			final List<Object> graph = fixture();
			final long before = JolHeapSize.ownedSize(graph);

			for (int i = 0; i < 5; i++) {
				System.gc();
				assertEquals(before, JolHeapSize.ownedSize(graph), "measurement drifted across a collection");
			}
		}
	}

	@Nested
	@DisplayName("never charges for JVM-owned class metadata")
	class ClassMetadataExcluded {

		@Test
		void shouldNotChargeTheReflectionCacheOfAReachableClass() {
			// a Class reference is JVM-owned: the holder pays for its slot, never for the class object, and never
			// for the reflection data that appears behind it the moment anything reflects on that class
			final Object holder = new Object[]{String.class};
			final long beforeReflection = JolHeapSize.ownedSize(holder);

			// materialise the reflection cache the way a real run would, then measure again
			assertTrue(String.class.getDeclaredFields().length > 0);
			final long afterReflection = JolHeapSize.ownedSize(holder);

			assertEquals(
				beforeReflection,
				afterReflection,
				"reflecting on a reachable class must not change what its holder is charged"
			);
		}

		@Test
		void shouldChargeOnlyTheArrayItselfForAnArrayOfClasses() {
			final Object[] classes = {String.class, Integer.class, UUID.class};

			// three reference slots and an array header - nothing else, however heavy those three classes are
			assertEquals(JolHeapSize.shallowSize(classes), JolHeapSize.ownedSize(classes));
		}
	}

	@Nested
	@DisplayName("subtracts borrowed roots by identity")
	class BorrowedRoots {

		@Test
		void shouldSubtractABorrowedSubgraphButNotAnEqualOne() {
			final long[] borrowed = {1L, 2L, 3L};
			final Object[] holder = {borrowed};

			// the borrowed array is subtracted, leaving only the holder
			assertEquals(JolHeapSize.shallowSize(holder), JolHeapSize.ownedSize(holder, borrowed));

			// an EQUAL but distinct array is not the same object, so naming it subtracts nothing - identity is the
			// whole point, and an equals-based implementation would silently under-report here
			final long[] equalButDistinct = {1L, 2L, 3L};
			assertEquals(
				JolHeapSize.ownedSize(holder),
				JolHeapSize.ownedSize(holder, equalButDistinct)
			);
		}
	}
}
