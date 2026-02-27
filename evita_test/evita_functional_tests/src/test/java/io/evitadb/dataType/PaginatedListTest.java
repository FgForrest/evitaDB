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

import static io.evitadb.dataType.PaginatedList.getFirstItemNumberForPage;
import static io.evitadb.dataType.PaginatedList.getLastPageNumber;
import static io.evitadb.dataType.PaginatedList.isRequestedResultBehindLimit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests verifying {@link PaginatedList} contract including static
 * utility methods, pagination navigation, data iteration, equality,
 * and edge cases.
 *
 * @author Jan Novotn&#253; (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PaginatedList")
class PaginatedListTest {

	@Nested
	@DisplayName("Static utility methods")
	class StaticMethodsTest {

		@Test
		@DisplayName(
			"should compute first item offset for various pages"
		)
		void shouldComputeFirstRowProperly() {
			assertEquals(
				0, getFirstItemNumberForPage(1, 20)
			);
			assertEquals(
				20, getFirstItemNumberForPage(2, 20)
			);
			assertEquals(
				40, getFirstItemNumberForPage(3, 20)
			);
		}

		@Test
		@DisplayName(
			"should detect when requested page exceeds record limit"
		)
		void shouldComputeOverflowProperly() {
			assertFalse(
				isRequestedResultBehindLimit(1, 20, 24)
			);
			assertFalse(
				isRequestedResultBehindLimit(2, 20, 21)
			);
			assertTrue(
				isRequestedResultBehindLimit(2, 20, 20)
			);
			assertTrue(
				isRequestedResultBehindLimit(3, 20, 24)
			);
		}

		@Test
		@DisplayName(
			"should compute last page number for various sizes"
		)
		void shouldComputeLastPageNumberForVariousPageSizes() {
			// exact fit: 20 records, 20 per page = 1 page
			assertEquals(1, getLastPageNumber(20, 20));
			// 21 records, 20 per page = 2 pages
			assertEquals(2, getLastPageNumber(20, 21));
			// 0 records = 0 pages
			assertEquals(0, getLastPageNumber(20, 0));
			// 1 record, 20 per page = 1 page
			assertEquals(1, getLastPageNumber(20, 1));
			// 1884 records, 20 per page = 95 pages
			assertEquals(95, getLastPageNumber(20, 1884));
			// pageSize=0 edge case returns 0
			assertEquals(0, getLastPageNumber(0, 100));
		}
	}

	@Nested
	@DisplayName("Construction and basic accessors")
	class ConstructionTest {

		@Test
		@DisplayName(
			"should return correct page size and number"
		)
		void shouldReturnCorrectPageSizeAndNumber() {
			final PaginatedList<String> list =
				new PaginatedList<>(3, 10, 50);

			assertEquals(10, list.getPageSize());
			assertEquals(3, list.getPageNumber());
		}

		@Test
		@DisplayName(
			"should return correct total record count"
		)
		void shouldReturnCorrectTotalRecordCount() {
			final PaginatedList<String> list =
				new PaginatedList<>(1, 20, 42);

			assertEquals(42, list.getTotalRecordCount());
		}

		@Test
		@DisplayName(
			"should compute last page number from constructor"
		)
		void shouldComputeLastPageNumber() {
			assertEquals(
				2,
				new PaginatedList<>(1, 20, 24)
					.getLastPageNumber()
			);
			assertEquals(
				1,
				new PaginatedList<>(1, 20, 14)
					.getLastPageNumber()
			);
			assertEquals(
				1,
				new PaginatedList<>(1, 20, 20)
					.getLastPageNumber()
			);
			assertEquals(
				95,
				new PaginatedList<>(1, 20, 1884)
					.getLastPageNumber()
			);
		}
	}

	@Nested
	@DisplayName("Page navigation")
	class NavigationTest {

		@Test
		@DisplayName(
			"should compute first item number of page"
		)
		void shouldComputeFirstNumberOfPage() {
			assertEquals(
				0,
				new PaginatedList<>(1, 20, 24)
					.getFirstPageItemNumber()
			);
			assertEquals(
				20,
				new PaginatedList<>(2, 20, 44)
					.getFirstPageItemNumber()
			);
			// page beyond limit resets to 0
			assertEquals(
				0,
				new PaginatedList<>(2, 20, 18)
					.getFirstPageItemNumber()
			);
		}

		@Test
		@DisplayName(
			"should compute last item number of page"
		)
		void shouldComputeLastNumberOfPage() {
			assertEquals(
				19,
				new PaginatedList<>(1, 20, 24)
					.getLastPageItemNumber()
			);
			assertEquals(
				39,
				new PaginatedList<>(2, 20, 44)
					.getLastPageItemNumber()
			);
			assertEquals(
				18,
				new PaginatedList<>(2, 20, 18)
					.getLastPageItemNumber()
			);
		}

		@Test
		@DisplayName("should recognize first page")
		void shouldRecognizeFirstPage() {
			assertTrue(
				new PaginatedList<>(1, 20, 19).isFirst()
			);
			assertFalse(
				new PaginatedList<>(2, 20, 45).isFirst()
			);
		}

		@Test
		@DisplayName("should recognize last page")
		void shouldRecognizeLastPage() {
			assertTrue(
				new PaginatedList<>(1, 20, 19).isLast()
			);
			assertTrue(
				new PaginatedList<>(2, 20, 35).isLast()
			);
			assertFalse(
				new PaginatedList<>(2, 20, 45).isLast()
			);
		}

		@Test
		@DisplayName(
			"should detect hasPrevious and hasNext"
		)
		void shouldDetectHasPreviousAndHasNext() {
			// first page: no previous, has next
			final PaginatedList<String> first =
				new PaginatedList<>(1, 20, 45);
			assertFalse(first.hasPrevious());
			assertTrue(first.hasNext());

			// middle page: has both
			final PaginatedList<String> middle =
				new PaginatedList<>(2, 20, 45);
			assertTrue(middle.hasPrevious());
			assertTrue(middle.hasNext());

			// last page: has previous, no next
			final PaginatedList<String> last =
				new PaginatedList<>(3, 20, 45);
			assertTrue(last.hasPrevious());
			assertFalse(last.hasNext());
		}

		@Test
		@DisplayName(
			"should recognize single page result"
		)
		void shouldRecognizeSinglePage() {
			assertTrue(
				new PaginatedList<>(1, 20, 19)
					.isSinglePage()
			);
			assertFalse(
				new PaginatedList<>(1, 20, 45)
					.isSinglePage()
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
				new PaginatedList<>(
					1, 5, 34,
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
			final PaginatedList<Integer> page =
				new PaginatedList<>(
					1, 5, 34,
					Arrays.asList(1, 2, 3, 4, 5)
				);

			final List<Integer> collected =
				page.stream().collect(Collectors.toList());
			assertEquals(
				Arrays.asList(1, 2, 3, 4, 5), collected
			);
		}

		@Test
		@DisplayName(
			"should return empty data when no data provided"
		)
		void shouldReturnEmptyDataWhenNoDataProvided() {
			final PaginatedList<String> page =
				new PaginatedList<>(1, 20, 0);

			assertTrue(page.getData().isEmpty());
			assertTrue(page.isEmpty());
		}
	}

	@Nested
	@DisplayName("Empty list factory")
	class EmptyListTest {

		@Test
		@DisplayName(
			"should return proper empty state from factory"
		)
		void shouldReturnEmptyListFromFactory() {
			final PaginatedList<String> empty =
				PaginatedList.emptyList();

			assertTrue(empty.isEmpty());
			assertEquals(0, empty.getTotalRecordCount());
			assertTrue(empty.getData().isEmpty());
			assertTrue(empty.isFirst());
			assertTrue(empty.isLast());
			assertTrue(empty.isSinglePage());
			assertFalse(empty.hasPrevious());
			assertFalse(empty.hasNext());
		}

		@Test
		@DisplayName(
			"should return same singleton instance"
		)
		void shouldReturnSameSingletonInstance() {
			final PaginatedList<String> a =
				PaginatedList.emptyList();
			final PaginatedList<Integer> b =
				PaginatedList.emptyList();

			assertSame(a, b);
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTest {

		@Test
		@DisplayName(
			"should return empty data when page behind limit"
		)
		void shouldReturnEmptyDataWhenPageBehindLimit() {
			// page 5 of a 24-record set with 20/page
			// is behind limit
			final PaginatedList<String> page =
				new PaginatedList<>(5, 20, 24);

			// getFirstPageItemNumber resets to 0
			// when behind limit
			assertEquals(0, page.getFirstPageItemNumber());
			assertTrue(page.getData().isEmpty());
		}

		@Test
		@DisplayName(
			"should handle page size of zero"
		)
		void shouldHandlePageSizeZero() {
			final PaginatedList<String> page =
				new PaginatedList<>(1, 0, 100);

			assertEquals(0, page.getLastPageNumber());
			assertEquals(0, page.getPageSize());
		}

		@Test
		@DisplayName(
			"returns -1 for getLastPageItemNumber " +
				"when pageSize is zero (known edge case)"
		)
		void shouldReturnNegativeOneWhenPageSizeIsZero() {
			final PaginatedList<String> list =
				new PaginatedList<>(1, 0, 10);

			// Documents current behavior: when pageSize=0,
			// result = (1 * 0) - 1 = -1, and
			// Math.min(-1, 10) = -1.
			// This is a known edge case but not fixed to
			// avoid changing the API contract.
			assertEquals(
				-1, list.getLastPageItemNumber()
			);
		}

		@Test
		@DisplayName(
			"should handle single record per page"
		)
		void shouldHandleSingleRecordPerPage() {
			final PaginatedList<Integer> page =
				new PaginatedList<>(
					3, 1, 5,
					Collections.singletonList(3)
				);

			assertEquals(1, page.getPageSize());
			assertEquals(3, page.getPageNumber());
			assertEquals(5, page.getLastPageNumber());
			assertEquals(
				2, page.getFirstPageItemNumber()
			);
			assertEquals(
				2, page.getLastPageItemNumber()
			);
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
			final PaginatedList<Integer> a =
				new PaginatedList<>(
					1, 20, 34,
					Arrays.asList(1, 2, 3)
				);
			final PaginatedList<Integer> b =
				new PaginatedList<>(
					1, 20, 34,
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
			final PaginatedList<Integer> a =
				new PaginatedList<>(
					1, 20, 34,
					Arrays.asList(1, 2, 3)
				);
			final PaginatedList<Integer> b =
				new PaginatedList<>(
					2, 20, 34,
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
			final PaginatedList<Integer> page =
				new PaginatedList<>(
					2, 20, 34,
					Arrays.asList(21, 22)
				);

			final String result = page.toString();
			assertTrue(result.contains("Page 2 of 2"));
			assertTrue(result.contains("34 recs. found"));
			assertTrue(result.contains("21"));
			assertTrue(result.contains("22"));
		}
	}

}
