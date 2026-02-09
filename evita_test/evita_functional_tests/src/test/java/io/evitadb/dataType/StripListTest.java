/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests verifying {@link StripList} contract including construction,
 * offset/limit-based navigation, data iteration, equality, and
 * edge cases.
 *
 * @author Jan Novotn&#253; (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("StripList")
class StripListTest {

	@Nested
	@DisplayName("Construction and basic accessors")
	class ConstructionTest {

		@Test
		@DisplayName(
			"should return correct offset and limit"
		)
		void shouldReturnCorrectOffsetAndLimit() {
			final StripList<String> list =
				new StripList<>(10, 20, 100);

			assertEquals(10, list.getOffset());
			assertEquals(20, list.getLimit());
		}

		@Test
		@DisplayName(
			"should return correct total record count"
		)
		void shouldReturnCorrectTotalRecordCount() {
			final StripList<String> list =
				new StripList<>(0, 20, 42);

			assertEquals(42, list.getTotalRecordCount());
		}
	}

	@Nested
	@DisplayName("Page navigation")
	class NavigationTest {

		@Test
		@DisplayName(
			"should detect first page when offset is zero"
		)
		void shouldDetectFirstPageWhenOffsetIsZero() {
			assertTrue(
				new StripList<>(0, 20, 100).isFirst()
			);
			assertFalse(
				new StripList<>(1, 20, 100).isFirst()
			);
		}

		@Test
		@DisplayName("should recognize first page")
		void shouldRecognizeFirstPage() {
			assertTrue(
				new StripList<>(0, 20, 19).isFirst()
			);
			assertTrue(
				new StripList<>(0, 20, 19).isSinglePage()
			);

			assertFalse(
				new StripList<>(2, 20, 45).isFirst()
			);
			assertFalse(
				new StripList<>(0, 20, 45).isSinglePage()
			);
		}

		@Test
		@DisplayName("should detect last page correctly")
		void shouldDetectLastPageCorrectly() {
			// offset + limit >= totalRecordCount
			assertTrue(
				new StripList<>(0, 20, 19).isLast()
			);
			assertTrue(
				new StripList<>(20, 20, 35).isLast()
			);
			// exact boundary: 20 + 20 = 40 >= 40
			assertTrue(
				new StripList<>(20, 20, 40).isLast()
			);
			assertFalse(
				new StripList<>(20, 20, 45).isLast()
			);
		}

		@Test
		@DisplayName("should recognize last page")
		void shouldRecognizeLastPage() {
			assertTrue(
				new StripList<>(0, 20, 19).isLast()
			);
			assertTrue(
				new StripList<>(20, 20, 35).isLast()
			);
			assertFalse(
				new StripList<>(20, 20, 45).isLast()
			);
		}

		@Test
		@DisplayName(
			"should detect hasPrevious and hasNext"
		)
		void shouldDetectHasPreviousAndHasNext() {
			// first strip: no previous, has next
			final StripList<String> first =
				new StripList<>(0, 20, 45);
			assertFalse(first.hasPrevious());
			assertTrue(first.hasNext());

			// middle strip: has both
			final StripList<String> middle =
				new StripList<>(20, 20, 60);
			assertTrue(middle.hasPrevious());
			assertTrue(middle.hasNext());

			// last strip: has previous, no next
			final StripList<String> last =
				new StripList<>(40, 20, 45);
			assertTrue(last.hasPrevious());
			assertFalse(last.hasNext());
		}

		@Test
		@DisplayName(
			"should return true for single page when data"
			+ " fits in limit"
		)
		void shouldReturnTrueForSinglePageWhenDataFits() {
			assertTrue(
				new StripList<>(0, 20, 15).isSinglePage()
			);
			assertTrue(
				new StripList<>(0, 20, 20).isSinglePage()
			);
			assertFalse(
				new StripList<>(0, 20, 21).isSinglePage()
			);
		}
	}

	@Nested
	@DisplayName("Data iteration and streaming")
	class DataIterationTest {

		@Test
		@DisplayName(
			"should initialize with data and iterate over it"
		)
		void shouldInitializeWithDataAndIterateOver() {
			final DataChunk<Integer> page =
				new StripList<>(
					0, 5, 34,
					Arrays.asList(1, 2, 3, 4, 5)
				);

			assertFalse(page.isEmpty());
			// use AtomicInteger so the counter variable
			// itself can be final
			final AtomicInteger counter =
				new AtomicInteger(0);
			for (final Integer recId : page) {
				assertEquals(
					counter.incrementAndGet(), recId
				);
			}
		}

		@Test
		@DisplayName(
			"should support stream of data"
		)
		void shouldSupportStreamOfData() {
			final StripList<Integer> strip =
				new StripList<>(
					0, 5, 34,
					Arrays.asList(1, 2, 3, 4, 5)
				);

			final List<Integer> collected =
				strip.stream().collect(Collectors.toList());
			assertEquals(
				Arrays.asList(1, 2, 3, 4, 5), collected
			);
		}

		@Test
		@DisplayName(
			"should return empty data when no records"
		)
		void shouldReturnEmptyDataWhenNoRecords() {
			final StripList<String> strip =
				new StripList<>(0, 20, 0);

			assertTrue(strip.getData().isEmpty());
			assertTrue(strip.isEmpty());
		}
	}

	@Nested
	@DisplayName("Empty list factory")
	class EmptyListTest {

		@Test
		@DisplayName(
			"should return empty list from factory with " +
				"correct state"
		)
		void shouldReturnEmptyListFromFactory() {
			final StripList<String> empty =
				StripList.emptyList();

			assertTrue(empty.isEmpty());
			assertEquals(0, empty.getTotalRecordCount());
			assertTrue(empty.getData().isEmpty());

			// offset should be 0 for an empty list
			assertEquals(0, empty.getOffset());
			assertTrue(
				empty.isFirst(),
				"empty list should be the first page"
			);
			assertTrue(
				empty.isLast(),
				"empty list should be the last page"
			);
			assertTrue(
				empty.isSinglePage(),
				"empty list should be a single page"
			);
			assertFalse(
				empty.hasPrevious(),
				"empty list should have no previous"
			);
			assertFalse(
				empty.hasNext(),
				"empty list should have no next"
			);
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTest {

		@Test
		@DisplayName(
			"should handle zero limit"
		)
		void shouldHandleZeroLimit() {
			final StripList<String> strip =
				new StripList<>(0, 0, 100);

			assertEquals(0, strip.getLimit());
			assertEquals(0, strip.getOffset());
			assertTrue(strip.isFirst());
			// offset(0) + limit(0) = 0 < 100
			assertFalse(strip.isLast());
		}

		@Test
		@DisplayName(
			"should handle offset beyond total records"
		)
		void shouldHandleOffsetBeyondTotal() {
			final StripList<String> strip =
				new StripList<>(100, 20, 50);

			assertFalse(strip.isFirst());
			// offset(100) + limit(20) = 120 >= 50
			assertTrue(strip.isLast());
			assertTrue(strip.getData().isEmpty());
		}

		@Test
		@DisplayName(
			"should handle large offset values"
		)
		void shouldHandleLargeOffset() {
			final StripList<String> strip =
				new StripList<>(
					Integer.MAX_VALUE - 10, 20, 100
				);

			assertFalse(strip.isFirst());
			assertEquals(
				Integer.MAX_VALUE - 10,
				strip.getOffset()
			);
		}

		@Test
		@DisplayName(
			"should handle data with single element"
		)
		void shouldHandleSingleElementData() {
			final StripList<Integer> strip =
				new StripList<>(
					0, 1, 1,
					Collections.singletonList(42)
				);

			assertEquals(1, strip.getLimit());
			assertTrue(strip.isFirst());
			assertTrue(strip.isLast());
			assertTrue(strip.isSinglePage());
			assertFalse(strip.isEmpty());
			assertEquals(1, strip.getData().size());
			assertEquals(42, strip.getData().get(0));
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName(
			"should be equal when same parameters"
		)
		void shouldBeEqualWhenSameParams() {
			final StripList<Integer> a =
				new StripList<>(
					0, 20, 34,
					Arrays.asList(1, 2, 3)
				);
			final StripList<Integer> b =
				new StripList<>(
					0, 20, 34,
					Arrays.asList(1, 2, 3)
				);

			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName(
			"should not be equal when different parameters"
		)
		void shouldNotBeEqualWhenDifferentParams() {
			final StripList<Integer> a =
				new StripList<>(
					0, 20, 34,
					Arrays.asList(1, 2, 3)
				);
			final StripList<Integer> b =
				new StripList<>(
					20, 20, 34,
					Arrays.asList(4, 5, 6)
				);

			assertNotEquals(a, b);
		}
	}

	@Nested
	@DisplayName("toString formatting")
	class ToStringTest {

		@Test
		@DisplayName(
			"should produce readable toString output"
		)
		void shouldProduceReadableToString() {
			final StripList<Integer> strip =
				new StripList<>(
					10, 20, 34,
					Arrays.asList(11, 12)
				);

			final String result = strip.toString();
			assertTrue(result.contains("Strip 10"));
			assertTrue(result.contains("limit 20"));
			assertTrue(result.contains("34"));
			assertTrue(result.contains("11"));
			assertTrue(result.contains("12"));
		}
	}

}
