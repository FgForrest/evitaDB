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
		this.tested.addAll(5, 8, 10, 3);
	}

	@Test
	void shouldReadAllData() {
		assertEquals(4, this.tested.size());
		assertArrayEquals(new int[]{3, 5, 8, 10}, this.tested.getArray());
		assertEquals(1, this.tested.indexOf(5));
		assertEquals(0, this.tested.indexOf(3));
		assertEquals(-1, this.tested.indexOf(0));
		assertEquals(-2, this.tested.indexOf(4));
		assertEquals(-3, this.tested.indexOf(6));
		assertEquals(-4, this.tested.indexOf(9));
		assertEquals(-5, this.tested.indexOf(11));
	}

	@Test
	void shouldIterateOverAllData() {
		final OfInt it = this.tested.iterator();
		assertArrayEquals(new int[] {3, 5, 8, 10}, toArray(it, this.tested.size()));
	}

	@Test
	void shouldAddAndSeeData() {
		this.tested.addAll(1, 15);
		assertEquals(6, this.tested.size());
		assertArrayEquals(new int[] {1, 3, 5, 8, 10, 15}, this.tested.getArray());
		assertArrayEquals(new int[] {1, 3, 5, 8, 10, 15}, toArray(this.tested.iterator(), this.tested.size()));
	}

	@Test
	void shouldRemoveAndMissData() {
		this.tested.removeAll(3, 8);
		assertEquals(2, this.tested.size());
		assertArrayEquals(new int[] {5, 10}, this.tested.getArray());
		assertArrayEquals(new int[] {5, 10}, toArray(this.tested.iterator(), this.tested.size()));
	}

	@Test
	void shouldReturnRange() {
		this.tested.addAll(12, 15, 20, 23);
		assertArrayEquals(new int[] {8, 10, 12}, this.tested.getRange(2, 5));
		assertArrayEquals(new int[] {15, 20, 23}, this.tested.getRange(5, 8));
		assertArrayEquals(new int[] {3, 5, 8, 10, 12}, this.tested.getRange(0, 5));
	}

	@Test
	void shouldFailReturnRangeWhenSizeIsExceeded() {
		this.tested.addAll(12, 15, 20, 23);
		assertThrows(IndexOutOfBoundsException.class, () -> this.tested.getRange(7, 10));
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
