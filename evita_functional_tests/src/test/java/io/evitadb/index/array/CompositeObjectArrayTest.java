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


import io.evitadb.dataType.array.CompositeObjectArray;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link CompositeObjectArray} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class CompositeObjectArrayTest {

	@Test
	void shouldIteratorCorrectlyBehaveWithEmptyArray() {
		final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
		assertFalse(array.iterator().hasNext());
	}

	@Test
	void shouldReverseIteratorCorrectlyBehaveWithEmptyArray() {
		final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
		assertFalse(array.reverseIterator().hasNext());
	}

	@Test
	void shouldReverseIteratorCorrectlyBehaveWithArrayOfSizeOne() {
		final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
		array.add(1);
		final Iterator<Integer> it = array.reverseIterator();
		assertTrue(it.hasNext());
		assertEquals(1, it.next());
		assertFalse(it.hasNext());
	}

	@Test
	void shouldCorrectlyIterateCompositeArray() {
		final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

		final int limit = 2_048;
		for (int i = 0; i < limit; i++) {
			array.add(i);
		}

		int cnt = 0;
		final Iterator<Integer> it = array.iterator();
		while (it.hasNext()) {
			Integer next = it.next();
			assertEquals(cnt++, next);
		}
	}

	@Test
	void shouldCorrectlyIterateCompositeArrayFromRandomIndex() {
		final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

		final int limit = 2_048;
		for (int i = 0; i < limit; i++) {
			array.add(i);
		}

		final Random rnd = new Random();
		for (int j = 0; j < 5; j++) {
			int startIndex = rnd.nextInt(limit);
			int cnt = startIndex;
			final Iterator<Integer> it = array.iterator(startIndex);
			while (it.hasNext()) {
				Integer next = it.next();
				assertEquals(cnt++, next);
			}
		}
	}

	@Test
	void shouldCorrectlyIterateCompositeArrayInReverseOrder() {
		final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

		final int limit = 2_048;
		for (int i = 0; i < limit; i++) {
			array.add(i);
		}

		int cnt = 2_047;
		final Iterator<Integer> it = array.reverseIterator();
		while (it.hasNext()) {
			Integer next = it.next();
			assertEquals(cnt--, next);
		}
	}

	@Test
	void shouldCorrectlyIterateCompositeArrayInReverseOrderFromRandomIndex() {
		final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

		final int limit = 2_048;
		for (int i = 0; i < limit; i++) {
			array.add(i);
		}

		final Random rnd = new Random();
		for (int j = 0; j < 5; j++) {
			int startIndex = rnd.nextInt(limit);
			int cnt = startIndex - 1;
			final Iterator<Integer> it = array.reverseIterator(startIndex);
			while (it.hasNext()) {
				Integer next = it.next();
				assertEquals(cnt--, next);
			}
		}
	}

	@Test
	void shouldMakeCompositeArrayByOne() {
		final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

		final int limit = 2_048;
		for (int i = 0; i < limit; i++) {
			array.add(i);
		}

		final Integer[] result = array.toArray();
		assertEquals(limit, result.length);

		int last = -1;
		for (int i : result) {
			assertEquals(last + 1, i);
			last = i;
		}
	}

	@Test
	void shouldMakeCompositeArrayByMultiple() {
		final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

		final int repetitions = 4;
		final int limit = 700;

		for (int j = 0; j < repetitions; j++) {
			final Integer[] arr = new Integer[limit];
			for (int i = 0; i < limit; i++) {
				arr[i] = j * limit + i;
			}
			array.addAll(arr, 0, limit);
		}

		final Integer[] result = array.toArray();
		assertEquals(repetitions * limit, result.length);

		int last = -1;
		for (int i : result) {
			assertEquals(last + 1, i);
			last = i;
		}
	}

}
