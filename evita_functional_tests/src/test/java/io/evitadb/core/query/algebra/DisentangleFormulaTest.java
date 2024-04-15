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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.algebra;

import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.DisentangleFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * This test verifies contract of {@link DisentangleFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class DisentangleFormulaTest {

	@Test
	void shouldReturnAllNumbersExceptSingleInControlArray() {
		assertDistinctArray(
			new int[] {1, 2, 4},
			new int[] {1, 2, 3, 4},
			new int[] {3}
		);
	}

	@Test
	void shouldReturnAllNumbersExceptThoseInControlArray() {
		assertDistinctArray(
			new int[] {1, 3, 4},
			new int[] {1, 2, 3, 3, 4},
			new int[] {2, 3}
		);
	}

	@Test
	void shouldReturnMainListWithoutChangeWhenThereIsOnlyGreaterNumbersInControlList() {
		assertDistinctArray(
			new int[] {1, 2, 3, 4, 5},
			new int[] {1, 2, 3, 4, 5},
			new int[] {6, 7, 8}
		);
	}

	@Test
	void shouldReturnMainListWithoutChangeWhenThereIsOnlyLesserNumbersInControlList() {
		assertDistinctArray(
			new int[] {6, 7, 8},
			new int[] {6, 7, 8},
			new int[] {1, 2, 3, 4, 5}
		);
	}

	@Test
	void shouldReturnMainListWhenControlListIsEmpty() {
		assertDistinctArray(
			new int[] {6, 7, 8},
			new int[] {6, 7, 8},
			new int[0]
		);
	}

	@Test
	void shouldReturnEmptyMainListWhenMainListIsEmpty() {
		assertDistinctArray(
			new int[0],
			new int[0],
			new int[] {6, 7, 8}
		);
	}

	@Test
	void shouldReturnMainListWithDistinctNumbersOnlyWhenThereIsMatchInControlList() {
		assertDistinctArray(
			new int[] {1, 7},
			new int[] {1, 4, 6, 7},
			new int[] {2, 4, 6, 8}
		);
	}

	@Test
	void shouldReturnMainListWithDistinctNumbersAndDuplicatesOnlyWhenThereIsMatchInControlList() {
		assertDistinctArray(
			new int[] {1, 4, 7},
			new int[] {1, 4, 4, 6, 6, 7},
			new int[] {2, 4, 6, 6, 6, 8}
		);
	}

	@Test
	void shouldReturnNothingForEmptyBitmaps() {
		assertArrayEquals(
			new int[0],
			new DisentangleFormula(
				EmptyBitmap.INSTANCE,
				EmptyBitmap.INSTANCE
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldReturnFullBitmapForEmptyAndFullBitmap() {
		assertArrayEquals(
			new int[0],
			new DisentangleFormula(
				EmptyBitmap.INSTANCE,
				new ArrayBitmap(new CompositeIntArray(3, 3, 6, 9, 12))
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldReturnEmptyBitmapForEmptyControlBitmapAndFullMainBitmap() {
		assertArrayEquals(
			new int[] {3, 6, 9, 12},
			new DisentangleFormula(
				new ArrayBitmap(new CompositeIntArray(3, 3, 6, 9, 12)),
				EmptyBitmap.INSTANCE
			)
				.compute().getArray()
		);
	}

	private void assertDistinctArray(int[] expectedResult, int[] mainArray, int[] controlArray) {
		assertArrayEquals(
			expectedResult,
			new DisentangleFormula(
				new ArrayBitmap(new CompositeIntArray(mainArray)),
				new ArrayBitmap(new CompositeIntArray(controlArray))
			)
				.compute().getArray()
		);

		assertArrayEquals(
			expectedResult,
			new DisentangleFormula(
				mainArray.length == 0 ? EmptyFormula.INSTANCE : new ConstantFormula(new ArrayBitmap(new CompositeIntArray(mainArray))),
				controlArray.length == 0 ? EmptyFormula.INSTANCE : new ConstantFormula(new ArrayBitmap(new CompositeIntArray(controlArray)))
			)
				.compute().getArray()
		);
	}

}
