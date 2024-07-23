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

package io.evitadb.index.array;

import io.evitadb.dataType.array.CompositeLongArray;
import org.junit.jupiter.api.Test;

import java.util.PrimitiveIterator.OfLong;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link CompositeLongArray}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class CompositeLongArrayTest {

	@Test
	void shouldMaintainMonotonicRow() {
		final CompositeLongArray array = new CompositeLongArray();
		array.add(1);
		array.add(3);
		array.add(4);
		array.add(5);
		array.add(10);
		assertTrue(array.isMonotonic());
	}

	@Test
	void shouldNotMaintainMonotonicRowOnDuplicateValue() {
		final CompositeLongArray array = new CompositeLongArray();
		array.add(1);
		array.add(3);
		array.add(4);
		array.add(4);
		array.add(10);
		assertFalse(array.isMonotonic());
	}

	@Test
	void shouldNotMaintainMonotonicRowOnLowerValue() {
		final CompositeLongArray array = new CompositeLongArray();
		array.add(1);
		array.add(3);
		array.add(4);
		array.add(3);
		array.add(10);
		assertFalse(array.isMonotonic());
	}

	@Test
	void shouldMaintainMonotonicOnMultipleAdd() {
		final CompositeLongArray array = new CompositeLongArray();
		final long[] addedArray = new long[1050];
		for (int i = 0; i < 1050; i++) {
			addedArray[i] = i;
		}
		array.addAll(addedArray, 0, 1050);
		assertTrue(array.isMonotonic());
	}

	@Test
	void shouldNotMaintainMonotonicOnMultipleAddWhenLoweredValue() {
		final CompositeLongArray array = new CompositeLongArray();
		final long[] addedArray = new long[1050];
		for (int i = 0; i < 1050; i++) {
			addedArray[i] = i;
		}
		addedArray[1000] = 4;
		array.addAll(addedArray, 0, 1050);
		assertFalse(array.isMonotonic());
	}

	@Test
	void shouldMaintainMonotonicOnMultipleAddWhenLoweredValueIsOutsideAddedPart() {
		final CompositeLongArray array = new CompositeLongArray();
		final long[] addedArray = new long[1050];
		for (int i = 0; i < 1050; i++) {
			addedArray[i] = i;
		}
		addedArray[10] = 4;
		array.addAll(addedArray, 100, 950);
		assertTrue(array.isMonotonic());
	}

	@Test
	void shouldContainAndIndexOfWorkOnMonotonicArray() {
		final CompositeLongArray array = new CompositeLongArray();
		final long[] addedArray = new long[1050];
		for (int i = 0; i < 1050; i++) {
			addedArray[i] = i;
		}
		array.addAll(addedArray, 0, 1050);
		assertTrue(array.contains(999));
		assertEquals(999, array.indexOf(999));
	}

	@Test
	void shouldContainAndIndexOfWorkOnNonMonotonicArray() {
		final CompositeLongArray array = new CompositeLongArray();
		final long[] addedArray = new long[1050];
		int index = 0;
		for (int i = 1049; i >= 0; i--) {
			addedArray[index++] = i;
		}
		array.addAll(addedArray, 0, 1050);
		assertTrue(array.contains(50));
		assertEquals(999, array.indexOf(50));
	}

	@Test
	void shouldIteratorCorrectlyBehaveWithEmptyArray() {
		final CompositeLongArray array = new CompositeLongArray();
		assertFalse(array.iterator().hasNext());
	}

	@Test
	void shouldMakeCompositeArrayByOne() {
		final CompositeLongArray array = new CompositeLongArray();

		final int limit = 2_048;
		for (int i = 0; i < limit; i++) {
			array.add(i);
		}

		final long[] result = array.toArray();
		assertEquals(limit, result.length);

		long last = -1;
		for (long i : result) {
			assertEquals(last + 1, i);
			last = i;
		}
	}

	@Test
	void shouldMakeCompositeArrayByMultiple() {
		final CompositeLongArray array = new CompositeLongArray();

		final int repetitions = 4;
		final int limit = 700;

		for (int j = 0; j < repetitions; j++) {
			final long[] arr = new long[limit];
			for (int i = 0; i < limit; i++) {
				arr[i] = j * limit + i;
			}
			array.addAll(arr, 0, limit);
		}

		final long[] result = array.toArray();
		assertEquals(repetitions * limit, result.length);

		long last = -1;
		for (long i : result) {
			assertEquals(last + 1, i);
			last = i;
		}
	}

	@Test
	void shouldCorrectlyFetchItemsByOne() {
		final CompositeLongArray array = new CompositeLongArray();

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
		final CompositeLongArray array = new CompositeLongArray();

		final int limit = 2_048;
		for (int i = 0; i < limit; i++) {
			array.add(i);
		}

		assertEquals(limit, array.getSize());

		final OfLong it = array.iterator();
		long index = -1;
		while (it.hasNext()) {
			long next = it.nextLong();
			assertEquals(++index, next);
		}

		assertEquals(limit - 1, index);
	}

	@Test
	void shouldReturnRange() {
		final CompositeLongArray tested = new CompositeLongArray();
		final int limit = 1900;
		for (int i = 0; i < limit; i++) {
			tested.add(i);
		}
		assertArrayEquals(new long[]{2, 3, 4}, tested.getRange(2, 5));
		assertArrayEquals(new long[]{510, 511, 512, 513, 514, 515, 516, 517, 518, 519}, tested.getRange(510, 520));
		assertArrayEquals(tested.toArray(), tested.getRange(0, tested.getSize()));
	}

	@Test
	void shouldFailReturnRangeWhenSizeIsExceeded() {
		final CompositeLongArray tested = new CompositeLongArray();
		tested.addAll(new long[]{5, 8, 10, 3, 12, 15, 20, 23}, 0, 8);
		assertThrows(IndexOutOfBoundsException.class, () -> tested.getRange(7, 10));
	}

	@Test
	void shouldTestEqualsAndHashCode() {
		final CompositeLongArray one = new CompositeLongArray();
		final CompositeLongArray two = new CompositeLongArray();
		final Random rnd = new Random();
		for (int i = 0; i < 10_000; i++) {
			final int someInt = rnd.nextInt();
			one.add(someInt);
			two.add(someInt);

			assertEquals(one.hashCode(), two.hashCode());
			assertEquals(one, two);
		}
	}

}
