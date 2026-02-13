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

package io.evitadb.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test verifies contract of {@link CollectorUtils} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("CollectorUtils contract tests")
class CollectorUtilsTest {

	@Nested
	@DisplayName("UnmodifiableLinkedHashSet collector tests")
	class UnmodifiableLinkedHashSetCollectorTests {

		@Test
		@DisplayName("Should collect to empty set")
		void shouldCollectToEmptySet() {
			final Set<String> result = Stream.<String>empty()
				.collect(CollectorUtils.toUnmodifiableLinkedHashSet());

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("Should collect elements")
		void shouldCollectElements() {
			final Set<String> result = Stream.of("a", "b", "c")
				.collect(CollectorUtils.toUnmodifiableLinkedHashSet());

			assertEquals(3, result.size());
			assertTrue(result.contains("a"));
			assertTrue(result.contains("b"));
			assertTrue(result.contains("c"));
		}

		@Test
		@DisplayName("Should remove duplicates")
		void shouldRemoveDuplicates() {
			final Set<String> result = Stream.of("a", "b", "a", "c", "b")
				.collect(CollectorUtils.toUnmodifiableLinkedHashSet());

			assertEquals(3, result.size());
			assertTrue(result.contains("a"));
			assertTrue(result.contains("b"));
			assertTrue(result.contains("c"));
		}

		@Test
		@DisplayName("Should return unmodifiable set")
		void shouldReturnUnmodifiableSet() {
			final Set<String> result = Stream.of("a", "b", "c")
				.collect(CollectorUtils.toUnmodifiableLinkedHashSet());

			assertThrows(UnsupportedOperationException.class, () -> result.add("d"));
			assertThrows(UnsupportedOperationException.class, () -> result.remove("a"));
			assertThrows(UnsupportedOperationException.class, () -> result.clear());
		}

		@Test
		@DisplayName("Should preserve insertion order")
		void shouldPreserveInsertionOrder() {
			final Set<String> result = Stream.of("c", "a", "b")
				.collect(CollectorUtils.toUnmodifiableLinkedHashSet());

			final List<String> asList = new ArrayList<>(result);
			assertEquals("c", asList.get(0));
			assertEquals("a", asList.get(1));
			assertEquals("b", asList.get(2));
		}

		@Test
		@DisplayName("Should work with parallel streams")
		void shouldWorkWithParallelStreams() {
			final Set<Integer> result = IntStream.range(0, 1000)
				.boxed()
				.parallel()
				.collect(CollectorUtils.toUnmodifiableLinkedHashSet());

			assertEquals(1000, result.size());
			for (int i = 0; i < 1000; i++) {
				assertTrue(result.contains(i), "Should contain " + i);
			}
		}

		@Test
		@DisplayName("Should handle single element")
		void shouldHandleSingleElement() {
			final Set<String> result = Stream.of("single")
				.collect(CollectorUtils.toUnmodifiableLinkedHashSet());

			assertEquals(1, result.size());
			assertTrue(result.contains("single"));
		}

		@Test
		@DisplayName("Should handle different types")
		void shouldHandleDifferentTypes() {
			final Set<Integer> intResult = Stream.of(1, 2, 3)
				.collect(CollectorUtils.toUnmodifiableLinkedHashSet());
			assertEquals(3, intResult.size());

			final Set<Double> doubleResult = Stream.of(1.0, 2.0, 3.0)
				.collect(CollectorUtils.toUnmodifiableLinkedHashSet());
			assertEquals(3, doubleResult.size());

			final Set<Object> mixedResult = Stream.of("a", 1, 2.0)
				.collect(CollectorUtils.toUnmodifiableLinkedHashSet());
			assertEquals(3, mixedResult.size());
		}
	}
}
