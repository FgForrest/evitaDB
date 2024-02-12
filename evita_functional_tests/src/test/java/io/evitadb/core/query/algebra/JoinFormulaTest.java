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

package io.evitadb.core.query.algebra;

import io.evitadb.core.query.algebra.base.JoinFormula;
import io.evitadb.index.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * This test verifies contract of {@link JoinFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class JoinFormulaTest {

	@Test
	void shouldApplyBitmapJoin() {
		assertArrayEquals(
			new int[]{1, 1, 2, 2, 3, 4, 4, 5, 5},
			new JoinFormula(
				1L,
				new BaseBitmap(1, 2, 3, 4, 5),
				new BaseBitmap(2, 4),
				new BaseBitmap(1, 5)
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldApplyBitmapJoinWithFormula() {
		assertArrayEquals(
			new int[]{1, 1, 2, 2, 3, 4, 4, 5, 5},
			new JoinFormula(
				1L,
				new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5)),
				new ArrayBitmap(new CompositeIntArray(2, 4)),
				new ArrayBitmap(new CompositeIntArray(1, 5))
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldReturnNothingForEmptyBitmaps() {
		assertArrayEquals(
			new int[0],
			new JoinFormula(
				1L,
				new BaseBitmap(),
				new BaseBitmap()
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldReturnFullBitmapForEmptyAndFullBitmap() {
		assertArrayEquals(
			new int[]{1, 3, 4, 5, 8},
			new JoinFormula(
				1L,
				new BaseBitmap(1, 3, 4, 5, 8),
				new BaseBitmap()
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldHandleOneBitmapBeingSubsetOfOther() {
		assertArrayEquals(
			new int[]{1, 1, 2, 2, 3, 3, 4, 5, 5},
			new JoinFormula(
				1L,
				new BaseBitmap(1, 2, 3, 4, 5),
				new BaseBitmap(1, 2, 3, 5)
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldHandleOverlappingBitmaps() {
		assertArrayEquals(
			new int[]{1, 1, 2, 3, 4, 5, 5, 7, 8, 8},
			new JoinFormula(
				1L,
				new BaseBitmap(1, 2, 5, 7, 8),
				new BaseBitmap(1, 3, 4, 5, 8)
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldHandleFirstBitmapConsiderablySmaller() {
		assertArrayEquals(
			new int[]{1, 2, 2, 3, 4, 5},
			new JoinFormula(
				1L,
				new BaseBitmap(2),
				new BaseBitmap(1, 2, 3, 4, 5)
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldHandleSecondBitmapConsiderablySmaller() {
		assertArrayEquals(
			new int[]{1, 2, 2, 3, 4, 5},
			new JoinFormula(
				1L,
				new BaseBitmap(1, 2, 3, 4, 5),
				new BaseBitmap(2)
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldHandleBitmapsHavingNoCommonElements() {
		assertArrayEquals(
			new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
			new JoinFormula(
				1L,
				new BaseBitmap(1, 2, 3, 4, 5),
				new BaseBitmap(6, 7, 8, 9, 10)
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldHandleOneBitmapBeingEmpty() {
		assertArrayEquals(
			new int[]{1, 2, 3, 4, 5},
			new JoinFormula(
				1L,
				new BaseBitmap(1, 2, 3, 4, 5),
				new BaseBitmap()
			)
				.compute().getArray()
		);
	}

}
