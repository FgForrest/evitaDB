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

package io.evitadb.core.query.algebra;

import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * This test verifies contract of {@link NotFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class NotFormulaTest {

	@Test
	void shouldApplyBooleanNot() {
		assertNegatedArray(new int[]{1, 3}, new int[0], new int[]{1, 3});
		assertNegatedArray(new int[]{6}, new int[]{2, 3, 4}, new int[]{3, 6});
		assertNegatedArray(new int[]{3, 4}, new int[]{1, 2}, new int[]{1, 2, 3, 4});
		assertNegatedArray(new int[]{1, 2}, new int[]{3, 4}, new int[]{1, 2, 3, 4});
		assertNegatedArray(new int[]{1, 4}, new int[]{2, 3}, new int[]{1, 2, 3, 4});
		assertNegatedArray(new int[]{1, 3}, new int[]{2, 4}, new int[]{1, 2, 3, 4});
		assertNegatedArray(new int[0], new int[]{1, 2, 3, 4}, new int[]{1, 2, 3, 4});
		assertNegatedArray(new int[0], new int[]{1, 2, 3, 4, 5, 6}, new int[]{1, 2, 3, 4});
		assertNegatedArray(new int[]{1, 3, 4}, new int[]{2, 7, 9}, new int[]{1, 2, 3, 4});
		assertNegatedArray(new int[]{1, 2, 3, 4}, new int[]{7, 9}, new int[]{1, 2, 3, 4});
		assertNegatedArray(new int[]{3}, new int[]{1, 2, 4, 5, 6, 7}, new int[]{1, 2, 3, 4, 5, 6, 7});
	}

	@Test
	void shouldReturnNothingForEmptyBitmaps() {
		assertArrayEquals(
			new int[0],
			new NotFormula(
				new BaseBitmap(),
				new BaseBitmap()
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldReturnNothingForEmptyAndFullBitmap() {
		assertArrayEquals(
			new int[0],
			new NotFormula(
				new BaseBitmap(1, 3, 4, 5, 8),
				new BaseBitmap()
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldReturnAllForFullAndEmptyBitmap() {
		assertArrayEquals(
			new int[]{1, 3, 4, 5, 8},
			new NotFormula(
				new BaseBitmap(),
				new BaseBitmap(1, 3, 4, 5, 8)
			)
				.compute().getArray()
		);
	}

	private void assertNegatedArray(int[] expectedResult, int[] negatedArray, int[] mainArray) {
		assertArrayEquals(
			expectedResult,
			new NotFormula(
				new BaseBitmap(negatedArray),
				new BaseBitmap(mainArray)
			)
				.compute().getArray()
		);

		assertArrayEquals(
			expectedResult,
			new NotFormula(
				negatedArray.length == 0 ? EmptyFormula.INSTANCE : new ConstantFormula(new ArrayBitmap(new CompositeIntArray(negatedArray))),
				mainArray.length == 0 ? EmptyFormula.INSTANCE : new ConstantFormula(new ArrayBitmap(new CompositeIntArray(mainArray)))
			)
				.compute().getArray()
		);
	}

}
