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

package io.evitadb.dataType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PlainChunk} verifying construction,
 * getData behavior, DataChunk interface compliance, and
 * edge cases.
 *
 * @author evitaDB
 */
@DisplayName("PlainChunk functionality")
class PlainChunkTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName(
			"should construct from List"
		)
		void shouldConstructFromList() {
			final List<String> data =
				Arrays.asList("a", "b", "c");
			final PlainChunk<String> chunk =
				new PlainChunk<>(data);

			assertEquals(3, chunk.getTotalRecordCount());
		}

		@Test
		@DisplayName(
			"should construct from Set"
		)
		void shouldConstructFromSet() {
			final Set<String> data = new LinkedHashSet<>();
			data.add("a");
			data.add("b");
			final PlainChunk<String> chunk =
				new PlainChunk<>(data);

			assertEquals(2, chunk.getTotalRecordCount());
		}

		@Test
		@DisplayName(
			"should construct from empty collection"
		)
		void shouldConstructFromEmptyCollection() {
			final PlainChunk<String> chunk =
				new PlainChunk<>(Collections.emptyList());

			assertEquals(0, chunk.getTotalRecordCount());
			assertTrue(chunk.isEmpty());
		}
	}

	@Nested
	@DisplayName("GetData")
	class GetDataTest {

		@Test
		@DisplayName(
			"should convert Set to List via getData"
		)
		void shouldConvertSetToList() {
			final Set<String> data = new LinkedHashSet<>();
			data.add("x");
			data.add("y");
			final PlainChunk<String> chunk =
				new PlainChunk<>(data);

			final List<String> result = chunk.getData();
			assertEquals(
				List.of("x", "y"),
				result
			);
		}

		@Test
		@DisplayName(
			"should return same List if constructed " +
				"with List"
		)
		void shouldReturnSameListIfConstructedWithList() {
			final List<String> data =
				Arrays.asList("a", "b");
			final PlainChunk<String> chunk =
				new PlainChunk<>(data);

			assertSame(data, chunk.getData());
		}

		@Test
		@DisplayName(
			"should cache List result on second call"
		)
		void shouldCacheListOnSecondCall() {
			final Set<String> data = new LinkedHashSet<>();
			data.add("m");
			data.add("n");
			final PlainChunk<String> chunk =
				new PlainChunk<>(data);

			final List<String> first = chunk.getData();
			final List<String> second = chunk.getData();

			assertSame(first, second);
		}
	}

	@Nested
	@DisplayName("DataChunk interface")
	class DataChunkInterfaceTest {

		@Test
		@DisplayName("should always report isFirst true")
		void shouldAlwaysReportIsFirstTrue() {
			final PlainChunk<String> chunk =
				new PlainChunk<>(List.of("a"));

			assertTrue(chunk.isFirst());
		}

		@Test
		@DisplayName("should always report isLast true")
		void shouldAlwaysReportIsLastTrue() {
			final PlainChunk<String> chunk =
				new PlainChunk<>(List.of("a"));

			assertTrue(chunk.isLast());
		}

		@Test
		@DisplayName(
			"should always report isSinglePage true"
		)
		void shouldAlwaysReportIsSinglePageTrue() {
			final PlainChunk<String> chunk =
				new PlainChunk<>(List.of("a"));

			assertTrue(chunk.isSinglePage());
		}

		@Test
		@DisplayName(
			"should always report hasPrevious false"
		)
		void shouldAlwaysReportHasPreviousFalse() {
			final PlainChunk<String> chunk =
				new PlainChunk<>(List.of("a"));

			assertFalse(chunk.hasPrevious());
		}

		@Test
		@DisplayName(
			"should always report hasNext false"
		)
		void shouldAlwaysReportHasNextFalse() {
			final PlainChunk<String> chunk =
				new PlainChunk<>(List.of("a"));

			assertFalse(chunk.hasNext());
		}

		@Test
		@DisplayName(
			"should report isEmpty true for empty " +
				"collection"
		)
		void shouldReportIsEmptyForEmptyCollection() {
			final PlainChunk<String> chunk =
				new PlainChunk<>(Collections.emptyList());

			assertTrue(chunk.isEmpty());
		}

		@Test
		@DisplayName(
			"should report isEmpty false for non-empty " +
				"collection"
		)
		void shouldReportNotEmptyForNonEmpty() {
			final PlainChunk<String> chunk =
				new PlainChunk<>(List.of("a"));

			assertFalse(chunk.isEmpty());
		}

		@Test
		@DisplayName(
			"should return correct total record count"
		)
		void shouldReturnCorrectTotalRecordCount() {
			final PlainChunk<Integer> chunk =
				new PlainChunk<>(
					Arrays.asList(1, 2, 3, 4, 5)
				);

			assertEquals(5, chunk.getTotalRecordCount());
		}

		@Test
		@DisplayName(
			"should iterate over all elements"
		)
		void shouldIterateOverAllElements() {
			final PlainChunk<Integer> chunk =
				new PlainChunk<>(
					Arrays.asList(10, 20, 30)
				);

			final List<Integer> collected =
				new ArrayList<>();
			for (final Integer item : chunk) {
				collected.add(item);
			}

			assertEquals(
				Arrays.asList(10, 20, 30),
				collected
			);
		}

		@Test
		@DisplayName("should support stream operation")
		void shouldSupportStreamOperation() {
			final PlainChunk<String> chunk =
				new PlainChunk<>(
					Arrays.asList("a", "b", "c")
				);

			final List<String> result = chunk.stream()
				.collect(Collectors.toList());

			assertEquals(
				Arrays.asList("a", "b", "c"),
				result
			);
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTest {

		@Test
		@DisplayName(
			"should work with single element"
		)
		void shouldWorkWithSingleElement() {
			final PlainChunk<String> chunk =
				new PlainChunk<>(
					Collections.singletonList("only")
				);

			assertEquals(1, chunk.getTotalRecordCount());
			assertFalse(chunk.isEmpty());
			assertTrue(chunk.isFirst());
			assertTrue(chunk.isLast());
			assertTrue(chunk.isSinglePage());

			final Iterator<String> it = chunk.iterator();
			assertTrue(it.hasNext());
			assertEquals("only", it.next());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName(
			"should handle empty DataChunk interface " +
				"for empty chunk"
		)
		void shouldHandleEmptyChunkFullInterface() {
			final PlainChunk<String> chunk =
				new PlainChunk<>(Collections.emptyList());

			assertTrue(chunk.isEmpty());
			assertTrue(chunk.isFirst());
			assertTrue(chunk.isLast());
			assertTrue(chunk.isSinglePage());
			assertFalse(chunk.hasPrevious());
			assertFalse(chunk.hasNext());
			assertEquals(0, chunk.getTotalRecordCount());
			assertTrue(chunk.getData().isEmpty());
			assertFalse(chunk.iterator().hasNext());
		}

		@Test
		@DisplayName(
			"should eagerly initialize dataList at " +
				"construction time for thread safety"
		)
		void shouldEagerlyInitializeDataList()
			throws Exception {
			// DataChunk is @ThreadSafe @Immutable, so
			// PlainChunk must not have mutable state.
			// The dataList field must be initialized
			// eagerly in the constructor, not lazily
			// in getData().
			final Set<String> data = new LinkedHashSet<>();
			data.add("a");
			data.add("b");
			final PlainChunk<String> chunk =
				new PlainChunk<>(data);

			// Verify that the internal list field is
			// initialized immediately after construction,
			// BEFORE any call to getData().
			final Field dataListField =
				PlainChunk.class.getDeclaredField(
					"dataList"
				);
			dataListField.setAccessible(true);
			final Object dataList =
				dataListField.get(chunk);
			assertNotNull(
				dataList,
				"dataList field should be eagerly " +
					"initialized at construction time, " +
					"not lazily in getData()"
			);
		}

		@Test
		@DisplayName(
			"should return same List reference from all " +
				"threads when constructed from Set"
		)
		void shouldReturnSameReferenceFromAllThreads()
			throws InterruptedException {
			final Set<String> data = new LinkedHashSet<>();
			for (int i = 0; i < 1000; i++) {
				data.add("item" + i);
			}
			final PlainChunk<String> chunk =
				new PlainChunk<>(data);

			final List<List<String>> results =
				Collections.synchronizedList(
					new ArrayList<>()
				);
			final int threadCount = 10;
			final Thread[] threads = new Thread[threadCount];
			for (int i = 0; i < threadCount; i++) {
				threads[i] = new Thread(
					() -> results.add(chunk.getData())
				);
			}

			for (final Thread thread : threads) {
				thread.start();
			}
			for (final Thread thread : threads) {
				thread.join();
			}

			// all threads must get the exact same
			// List instance (identity, not just equality)
			final List<String> first = results.get(0);
			for (final List<String> result : results) {
				assertSame(
					first, result,
					"all threads should receive the " +
						"exact same List instance"
				);
			}
		}
	}
}
