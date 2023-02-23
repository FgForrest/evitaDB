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

package io.evitadb.index.bitmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.PrimitiveIterator.OfInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies contract of {@link BaseBitmap}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class BaseBitmapTest {
	private final BaseBitmap tested = new BaseBitmap();

	@BeforeEach
	void setUp() {
		tested.addAll(5, 8, 10, 3);
	}

	@Test
	void shouldReadAllData() {
		assertEquals(4, tested.size());
		assertArrayEquals(new int[]{3, 5, 8, 10}, tested.getArray());
		assertEquals(1, tested.indexOf(5));
		assertEquals(0, tested.indexOf(3));
		assertEquals(-1, tested.indexOf(0));
		assertEquals(-2, tested.indexOf(4));
		assertEquals(-3, tested.indexOf(6));
		assertEquals(-4, tested.indexOf(9));
		assertEquals(-5, tested.indexOf(11));
	}

	@Test
	void shouldIterateOverAllData() {
		final OfInt it = tested.iterator();
		assertArrayEquals(new int[] {3, 5, 8, 10}, toArray(it, tested.size()));
	}

	@Test
	void shouldAddAndSeeData() {
		tested.addAll(1, 15);
		assertEquals(6, tested.size());
		assertArrayEquals(new int[] {1, 3, 5, 8, 10, 15}, tested.getArray());
		assertArrayEquals(new int[] {1, 3, 5, 8, 10, 15}, toArray(tested.iterator(), tested.size()));
	}

	@Test
	void shouldRemoveAndMissData() {
		tested.removeAll(3, 8);
		assertEquals(2, tested.size());
		assertArrayEquals(new int[] {5, 10}, tested.getArray());
		assertArrayEquals(new int[] {5, 10}, toArray(tested.iterator(), tested.size()));
	}

	@Test
	void shouldReturnRange() {
		tested.addAll(12, 15, 20, 23);
		assertArrayEquals(new int[] {8, 10, 12}, tested.getRange(2, 5));
		assertArrayEquals(new int[] {15, 20, 23}, tested.getRange(5, 8));
		assertArrayEquals(new int[] {3, 5, 8, 10, 12}, tested.getRange(0, 5));
	}

	@Test
	void shouldFailReturnRangeWhenSizeIsExceeded() {
		tested.addAll(12, 15, 20, 23);
		assertThrows(IndexOutOfBoundsException.class, () -> tested.getRange(7, 10));
	}

	private static int[] toArray(OfInt iterator, int size) {
		final int[] result = new int[size];
		int index = 0;
		while (iterator.hasNext() && index < size) {
			result[index++] = iterator.next();
		}
		assertEquals(size, index);
		assertFalse(iterator.hasNext());
		return result;
	}

}