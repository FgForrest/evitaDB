/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.sort.attribute;

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.utils.MockSortedRecordsSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Verifies the merging logic in the {@link MergedSortedRecordsSupplierTest}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class MergedSortedRecordsSupplierTest {

	@Test
	void shouldMergeMultipleSortedRecordProvidersCorrectlyAvoidingDuplicates() {
		final MergedSortedRecordsSupplier result = new MergedSortedRecordsSupplier(
			new SortedRecordsProvider[]{
				new MockSortedRecordsSupplier(7, 1, 3),
				new MockSortedRecordsSupplier(5, 1, 2),
				new MockSortedRecordsSupplier(1, 7, 4, 6)
			}
		);

		final MockSortedRecordsSupplier secondCheck = new MockSortedRecordsSupplier(7, 1, 3, 5, 2, 4, 6);

		assertArrayEquals(
			new int[]{1, 2, 3, 4, 5, 6, 7},
			result.getAllRecords().stream().toArray()
		);
		assertArrayEquals(
			new int[]{7, 1, 3, 5, 2, 4, 6},
			result.getSortedRecordIds()
		);
		assertArrayEquals(
			new int[]{1, 4, 2, 5, 3, 6, 0},
			result.getRecordPositions()
		);
		assertArrayEquals(
			secondCheck.getRecordPositions(),
			result.getRecordPositions()
		);
	}

}