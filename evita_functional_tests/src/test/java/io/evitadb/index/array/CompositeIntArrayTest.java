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

package io.evitadb.index.array;

import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.dataType.iterator.BatchArrayIterator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link CompositeIntArray}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class CompositeIntArrayTest {

	@Test
	void shouldMaintainMonotonicRow() {
		final CompositeIntArray array = new CompositeIntArray();
		array.add(1);
		array.add(3);
		array.add(4);
		array.add(5);
		array.add(10);
		assertTrue(array.isMonotonic());
	}

	@Test
	void shouldNotMaintainMonotonicRowOnDuplicateValue() {
		final CompositeIntArray array = new CompositeIntArray();
		array.add(1);
		array.add(3);
		array.add(4);
		array.add(4);
		array.add(10);
		assertFalse(array.isMonotonic());
	}

	@Test
	void shouldNotMaintainMonotonicRowOnLowerValue() {
		final CompositeIntArray array = new CompositeIntArray();
		array.add(1);
		array.add(3);
		array.add(4);
		array.add(3);
		array.add(10);
		assertFalse(array.isMonotonic());
	}

	@Test
	void shouldMaintainMonotonicOnMultipleAdd() {
		final CompositeIntArray array = new CompositeIntArray();
		final int[] addedArray = new int[1050];
		for (int i = 0; i < 1050; i++) {
			addedArray[i] = i;
		}
		array.addAll(addedArray, 0, 1050);
		assertTrue(array.isMonotonic());
	}

	@Test
	void shouldNotMaintainMonotonicOnMultipleAddWhenLoweredValue() {
		final CompositeIntArray array = new CompositeIntArray();
		final int[] addedArray = new int[1050];
		for (int i = 0; i < 1050; i++) {
			addedArray[i] = i;
		}
		addedArray[1000] = 4;
		array.addAll(addedArray, 0, 1050);
		assertFalse(array.isMonotonic());
	}

	@Test
	void shouldMaintainMonotonicOnMultipleAddWhenLoweredValueIsOutsideAddedPart() {
		final CompositeIntArray array = new CompositeIntArray();
		final int[] addedArray = new int[1050];
		for (int i = 0; i < 1050; i++) {
			addedArray[i] = i;
		}
		addedArray[10] = 4;
		array.addAll(addedArray, 100, 950);
		assertTrue(array.isMonotonic());
	}

	@Test
	void shouldContainAndIndexOfWorkOnMonotonicArray() {
		final CompositeIntArray array = new CompositeIntArray();
		final int[] addedArray = new int[1050];
		for (int i = 0; i < 1050; i++) {
			addedArray[i] = i;
		}
		array.addAll(addedArray, 0, 1050);
		assertTrue(array.contains(999));
		assertEquals(999, array.indexOf(999));
	}

	@Test
	void shouldContainAndIndexOfWorkOnNonMonotonicArray() {
		final CompositeIntArray array = new CompositeIntArray();
		final int[] addedArray = new int[1050];
		int index = 0;
		for (int i = 1049; i >= 0; i--) {
			addedArray[index++] = i;
		}
		array.addAll(addedArray, 0, 1050);
		assertTrue(array.contains(50));
		assertEquals(999, array.indexOf(50));
	}

	@Test
	void shouldSetRecordOnSpecificIndex() {
		final CompositeIntArray array = new CompositeIntArray();
		for (int i = 0; i < 1050; i++) {
			array.add(i);
		}
		array.set(999, 280);
		array.set(999, 800);

		final int[] result = array.toArray();
		assertEquals(279, result[279]);
		assertEquals(999, result[280]);
		assertEquals(281, result[281]);
		assertEquals(799, result[799]);
		assertEquals(999, result[800]);
		assertEquals(801, result[801]);
	}

	@Test
	void shouldIteratorCorrectlyBehaveWithEmptyArray() {
		final CompositeIntArray array = new CompositeIntArray();
		assertFalse(array.iterator().hasNext());
	}

	@Test
	void shouldMakeCompositeArrayByOne() {
		final CompositeIntArray array = new CompositeIntArray();

		final int limit = 2_048;
		for (int i = 0; i < limit; i++) {
			array.add(i);
		}

		final int[] result = array.toArray();
		assertEquals(limit, result.length);

		int last = -1;
		for (int i : result) {
			assertEquals(last + 1, i);
			last = i;
		}
	}

	@Test
	void shouldMakeCompositeArrayByMultiple() {
		final CompositeIntArray array = new CompositeIntArray();

		final int repetitions = 4;
		final int limit = 700;

		for (int j = 0; j < repetitions; j++) {
			final int[] arr = new int[limit];
			for (int i = 0; i < limit; i++) {
				arr[i] = j * limit + i;
			}
			array.addAll(arr, 0, limit);
		}

		final int[] result = array.toArray();
		assertEquals(repetitions * limit, result.length);

		int last = -1;
		for (int i : result) {
			assertEquals(last + 1, i);
			last = i;
		}
	}

	@Test
	void shouldCorrectlyFetchItemsByOne() {
		final CompositeIntArray array = new CompositeIntArray();

		final int limit = 2_048;
		for (int i = 0; i < limit; i++) {
			array.add(i);
		}

		assertEquals(limit, array.getSize());

		int last = -1;
		for (int i = 0; i < limit; i++) {
			assertEquals(last + 1, array.get(i));
			last = i;
		}
	}

	@Test
	void shouldCorrectlyFetchItemsByIterator() {
		final CompositeIntArray array = new CompositeIntArray();

		final int limit = 2_048;
		for (int i = 0; i < limit; i++) {
			array.add(i);
		}

		assertEquals(limit, array.getSize());

		final OfInt it = array.iterator();
		int index = -1;
		while (it.hasNext()) {
			int next = it.nextInt();
			assertEquals(++index, next);
		}

		assertEquals(limit - 1, index);
	}

	@Test
	void shouldCorrectlyFetchItemsByIteratorFromIndex() {
		final CompositeIntArray array = new CompositeIntArray();

		final int limit = 2_048;
		for (int i = 0; i < limit; i++) {
			array.add(i);
		}

		assertEquals(limit, array.getSize());

		for (int i = 0; i < 7; i++) {
			final int startIndex = i * (limit / 7);
			final OfInt it = array.iteratorFrom(startIndex);
			int index = startIndex - 1;
			while (it.hasNext()) {
				int next = it.nextInt();
				assertEquals(++index, next);
			}

			assertEquals(limit - 1, index);
		}
	}

	@Test
	void shouldReturnRange() {
		final CompositeIntArray tested = new CompositeIntArray();
		final int limit = 1900;
		for (int i = 0; i < limit; i++) {
			tested.add(i);
		}
		assertArrayEquals(new int[]{2, 3, 4}, tested.getRange(2, 5));
		assertArrayEquals(new int[]{510, 511, 512, 513, 514, 515, 516, 517, 518, 519}, tested.getRange(510, 520));
		assertArrayEquals(tested.toArray(), tested.getRange(0, tested.getSize()));
	}

	@Test
	void shouldFailReturnRangeWhenSizeIsExceeded() {
		final CompositeIntArray tested = new CompositeIntArray();
		tested.addAll(new int[]{5, 8, 10, 3, 12, 15, 20, 23}, 0, 8);
		assertThrows(IndexOutOfBoundsException.class, () -> tested.getRange(7, 10));
	}

	@Test
	void shouldTestEqualsAndHashCode() {
		final CompositeIntArray one = new CompositeIntArray();
		final CompositeIntArray two = new CompositeIntArray();
		final Random rnd = new Random();
		for (int i = 0; i < 10_000; i++) {
			final int someInt = rnd.nextInt();
			one.add(someInt);
			two.add(someInt);

			assertEquals(one.hashCode(), two.hashCode());
			assertEquals(one, two);
		}
	}

	@Test
	void shouldIterateInBatches() {
		final CompositeIntArray tested = new CompositeIntArray();
		for (int i = 0; i < 123; i++) {
			tested.add(i * 3);
		}
		final BatchArrayIterator it = tested.batchIterator();
		final Set<Integer> collectedInts = new HashSet<>();
		while (it.hasNext()) {
			final int[] batch = it.nextBatch();
			for (int i = 0; i < it.getPeek(); i++) {
				collectedInts.add(batch[i]);
			}
		}

		assertEquals(123, collectedInts.size());
		for (int i = 0; i < 123; i++) {
			assertTrue(collectedInts.contains(i * 3));
		}
	}
}
