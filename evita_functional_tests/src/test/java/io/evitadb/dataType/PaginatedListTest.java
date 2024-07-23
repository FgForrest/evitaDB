/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static io.evitadb.dataType.PaginatedList.getFirstItemNumberForPage;
import static io.evitadb.dataType.PaginatedList.isRequestedResultBehindLimit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link PaginatedList} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class PaginatedListTest {

	@Test
	void shouldComputeFirstRowProperly() {
		assertEquals(0, getFirstItemNumberForPage(1, 20));
		assertEquals(20, getFirstItemNumberForPage(2, 20));
		assertEquals(40, getFirstItemNumberForPage(3, 20));
	}

	@Test
	void shouldComputeOverflowProperly() {
		assertFalse(isRequestedResultBehindLimit(1, 20, 24));
		assertFalse(isRequestedResultBehindLimit(2, 20, 21));
		assertTrue(isRequestedResultBehindLimit(2, 20, 20));
		assertTrue(isRequestedResultBehindLimit(3, 20, 24));
	}

	@Test
	void shouldComputeFirstNumberOfPage() {
		assertEquals(0, new PaginatedList<>(1, 20, 24).getFirstPageItemNumber());
		assertEquals(20, new PaginatedList<>(2, 20, 44).getFirstPageItemNumber());
		assertEquals(0, new PaginatedList<>(2, 20, 18).getFirstPageItemNumber());
	}

	@Test
	void shouldComputeLastNumberOfPage() {
		assertEquals(19, new PaginatedList<>(1, 20, 24).getLastPageItemNumber());
		assertEquals(39, new PaginatedList<>(2, 20, 44).getLastPageItemNumber());
		assertEquals(18, new PaginatedList<>(2, 20, 18).getLastPageItemNumber());
	}

	@Test
	void shouldComputeLastPageNumber() {
		assertEquals(2, new PaginatedList<>(1, 20, 24).getLastPageNumber());
		assertEquals(1, new PaginatedList<>(1, 20, 14).getLastPageNumber());
		assertEquals(1, new PaginatedList<>(1, 20, 20).getLastPageNumber());
		assertEquals(95, new PaginatedList<>(1, 20, 1884).getLastPageNumber());
	}

	@Test
	void shouldRecognizeFirstPage() {
		assertTrue(new PaginatedList<>(1, 20, 19).isFirst());
		assertTrue(new PaginatedList<>(1, 20, 19).isSinglePage());

		assertFalse(new PaginatedList<>(2, 20, 45).isFirst());
		assertFalse(new PaginatedList<>(1, 20, 45).isSinglePage());
	}

	@Test
	void shouldRecognizeLastPage() {
		assertTrue(new PaginatedList<>(1, 20, 19).isLast());
		assertTrue(new PaginatedList<>(2, 20, 35).isLast());
		assertFalse(new PaginatedList<>(2, 20, 45).isLast());
	}

	@Test
	void shouldInitializeWithDataAndIterateOver() {
		final DataChunk<Integer> page = new PaginatedList<>(1, 5, 34, Arrays.asList(1, 2, 3, 4, 5));
		assertFalse(page.isEmpty());
		int i = 0;
		for (Integer recId : page) {
			assertEquals(++i, recId);
		}
	}

}
