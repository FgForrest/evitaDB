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

package io.evitadb.core.query.sort.utils;

import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.test.utils.SortUtils;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * This test verifies contract of {@link SortUtils}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class SortUtilsTest {

	public static final int[] BUFFER = new int[16];

	public static int[] asResult(Function<int[], SortingContext> computer) {
		final int[] result = new int[512];
		final int peak = computer.apply(result).peak();
		return SortUtils.asResult(result, peak);
	}

	@Test
	void shouldReturnsResultsSliceBeginning() {
		final int[] array = initArray();
		final int[] result = new int[10];
		final int written = SortUtils.appendNotFoundResult(result, 0, 0, 30, array, null);
		assertEquals(10, written);
		assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, result);
	}

	@Test
	void shouldReturnsResultsSliceBeginningWithSomeContentAlreadyPresent() {
		final int[] array = initArray();
		final int[] result = new int[10];
		for (int i = 0; i < 5; i++) {
			result[i] = 77 + i;
		}
		final int written = SortUtils.appendNotFoundResult(result, 5, 0, 30, array, null);
		assertEquals(10, written);
		assertArrayEquals(new int[]{77, 78, 79, 80, 81, 1, 2, 3, 4, 5}, result);
	}

	@Test
	void shouldReturnsResultsSliceBeginningOffset() {
		final int[] array = initArray();
		final int[] result = new int[10];
		final int written = SortUtils.appendNotFoundResult(result, 0, 20, 30, array, null);
		assertEquals(10, written);
		assertArrayEquals(new int[]{21, 22, 23, 24, 25, 26, 27, 28, 29, 30}, result);
	}

	@Test
	void shouldReturnsResultsSliceMiddle() {
		final int[] array = initArray();
		final int[] result = new int[10];
		final int written = SortUtils.appendNotFoundResult(result, 0, 510, 520, array, null);
		assertEquals(10, written);
		assertArrayEquals(new int[]{511, 512, 513, 514, 515, 516, 517, 518, 519, 520}, result);
	}

	@Test
	void shouldReturnsResultsSliceEnd() {
		final int[] array = initArray();
		final int[] result = new int[10];
		final int written = SortUtils.appendNotFoundResult(result, 0, 995, 1050, array, null);
		assertEquals(5, written);
		assertArrayEquals(new int[]{996, 997, 998, 999, 1000, 0, 0, 0, 0, 0}, result);
	}

	@Test
	void shouldReturnsResultsSliceBeginningBitmap() {
		final RoaringBitmap bitmap = initRoaringBitmap();
		final int[] result = new int[10];
		final int written = SortUtils.appendNotFoundResult(result, 0, 0, 30, bitmap, BUFFER, null);
		assertEquals(10, written);
		assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, result);
	}

	@Test
	void shouldReturnsResultsSliceBeginningWithSomeContentAlreadyPresentBitmap() {
		final RoaringBitmap bitmap = initRoaringBitmap();
		final int[] result = new int[10];
		for (int i = 0; i < 5; i++) {
			result[i] = 77 + i;
		}
		final int written = SortUtils.appendNotFoundResult(result, 5, 0, 30, bitmap, BUFFER, null);
		assertEquals(10, written);
		assertArrayEquals(new int[]{77, 78, 79, 80, 81, 1, 2, 3, 4, 5}, result);
	}

	@Test
	void shouldReturnsResultsSliceBeginningOffsetBitmap() {
		final RoaringBitmap bitmap = initRoaringBitmap();
		final int[] result = new int[10];
		final int written = SortUtils.appendNotFoundResult(result, 0, 20, 30, bitmap, BUFFER, null);
		assertEquals(10, written);
		assertArrayEquals(new int[]{21, 22, 23, 24, 25, 26, 27, 28, 29, 30}, result);
	}

	@Test
	void shouldReturnsResultsSliceMiddleBitmap() {
		final RoaringBitmap bitmap = initRoaringBitmap();
		final int[] result = new int[10];
		final int written = SortUtils.appendNotFoundResult(result, 0, 510, 520, bitmap, BUFFER, null);
		assertEquals(10, written);
		assertArrayEquals(new int[]{511, 512, 513, 514, 515, 516, 517, 518, 519, 520}, result);
	}

	@Test
	void shouldReturnsResultsSliceEndBitmap() {
		final RoaringBitmap bitmap = initRoaringBitmap();
		final int[] result = new int[10];
		final int written = SortUtils.appendNotFoundResult(result, 0, 995, 1050, bitmap, BUFFER, null);
		assertEquals(5, written);
		assertArrayEquals(new int[]{996, 997, 998, 999, 1000, 0, 0, 0, 0, 0}, result);
	}

	@Test
	void shouldReturnArrayRange() {
		assertArrayEquals(new int[] {1, 2, 3}, SortUtils.asResult(new int[] {1, 2, 3, 4, 5}, 3));
	}

	@Test
	void shouldReturnEntireArray() {
		final int[] sortedEntities = {1, 2, 3, 4, 5};
		assertSame(sortedEntities, SortUtils.asResult(sortedEntities, 5));
	}

	@Nonnull
	private RoaringBitmap initRoaringBitmap() {
		final RoaringBitmap bitmap = new RoaringBitmap();
		for (int i = 1; i <= 1000; i++) {
			bitmap.add(i);
		}
		return bitmap;
	}

	@Nonnull
	private int[] initArray() {
		final int[] array = new int[1000];
		for (int i = 1; i <= 1000; i++) {
			array[i - 1] = i;
		}
		return array;
	}

}
