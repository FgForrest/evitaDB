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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.array;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies contract of {@link UnorderedLookup}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class UnorderedLookupTest {
	private UnorderedLookup tested;

	@Test
	void shouldCreateLookupAndFindPositionsProperly() {
		tested = new UnorderedLookup(new int[] {4, 2, 3, 1});
		assertEquals(0, tested.findPosition(4));
		assertEquals(1, tested.findPosition(2));
		assertEquals(2, tested.findPosition(3));
		assertEquals(3, tested.findPosition(1));

		assertEquals(4, tested.getRecordAt(0));
		assertEquals(2, tested.getRecordAt(1));
		assertEquals(3, tested.getRecordAt(2));
		assertEquals(1, tested.getRecordAt(3));
	}

	@Test
	void shouldCreateLookupAndReturnArrayUntouched() {
		final int[] inputArray = {4, 2, 3, 1};
		tested = new UnorderedLookup(inputArray);
		assertArrayEquals(inputArray, tested.getArray());
	}

	@Test
	void shouldRemoveRecordAndStillMaintainCorrectPositions() {
		tested = new UnorderedLookup(new int[] {4, 2, 3, 1, 6, 5});
		tested.removeRecord(1);
		assertArrayEquals(new int[] {4, 2, 3, 6, 5}, tested.getArray());
		tested.removeRecord(4);
		assertArrayEquals(new int[] {2, 3, 6, 5}, tested.getArray());
		tested.removeRecord(5);
		assertArrayEquals(new int[] {2, 3, 6}, tested.getArray());
		tested.removeRecord(3);
		assertArrayEquals(new int[] {2, 6}, tested.getArray());
		tested.removeRecord(2);
		assertArrayEquals(new int[] {6}, tested.getArray());
		tested.removeRecord(6);
		assertArrayEquals(new int[0], tested.getArray());
	}

	@Test
	void shouldAddRecordAndStillMaintainCorrectPositions() {
		tested = new UnorderedLookup(new int[0]);
		tested.addRecord(Integer.MIN_VALUE, 3);
		assertArrayEquals(new int[] {3}, tested.getArray());
		tested.addRecord(Integer.MIN_VALUE, 5);
		assertArrayEquals(new int[] {5, 3}, tested.getArray());
		tested.addRecord(5, 1);
		assertArrayEquals(new int[] {5, 1, 3}, tested.getArray());
		tested.addRecord(3, 2);
		assertArrayEquals(new int[] {5, 1, 3, 2}, tested.getArray());
		tested.addRecord(Integer.MIN_VALUE, 0);
		assertArrayEquals(new int[] {0, 5, 1, 3, 2}, tested.getArray());
		tested.addRecord(2, 10);
		assertArrayEquals(new int[] {0, 5, 1, 3, 2, 10}, tested.getArray());
	}

	@Test
	void shouldAppendRecordsAtTheEnd() {
		tested = new UnorderedLookup(new int[] {9, 1, 5});
		tested.appendRecords(new int[] {4, 3, 8});

		assertArrayEquals(new int[] {9, 1, 5, 4, 3, 8}, tested.getArray());

		tested.appendRecords(new int[] {2, 10});

		assertArrayEquals(new int[] {9, 1, 5, 4, 3, 8, 2, 10}, tested.getArray());
	}


}
