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
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * This test verifies contract of {@link OrFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class OrFormulaTest {
	private static final long[] INDEX_TRANSACTION_ID = {1L};

	@Test
	void shouldApplyBooleanOr() {
		assertArrayEquals(
			new int[]{1, 2, 3, 4, 5, 8},
			new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(1, 3, 4, 5, 8),
				new BaseBitmap(1, 2, 4, 8),
				new BaseBitmap(1, 2, 3, 4, 5)
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldApplyBooleanOrWithFormula() {
		assertArrayEquals(
			new int[]{1, 2, 3, 4, 5, 8},
			new OrFormula(
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 3, 4, 5, 8))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 4, 8))),
				new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5)))
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldConjugateCollectionsOfDuplicateInt() {
		assertArrayEquals(
			new int[]{1, 3, 4, 5, 7, 8},
			new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(1, 3, 5),
				new BaseBitmap(3, 5, 8),
				new BaseBitmap(3, 4, 5, 7)
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldReturnNothingForEmptyBitmaps() {
		assertArrayEquals(
			new int[0],
			new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(),
				new BaseBitmap()
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldReturnJoinForEmptyAndFullBitmap() {
		assertArrayEquals(
			new int[]{1, 3, 4, 5},
			new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(3, 4),
				new BaseBitmap(),
				new BaseBitmap(1, 5)
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldConjugateNonOverlappingCollections() {
		assertArrayEquals(
			new int[]{1, 3, 4, 5, 6, 7, 9, 10, 11, 12},
			new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(1, 3, 5, 7, 9, 11),
				new BaseBitmap(3, 4, 5, 6, 7),
				new BaseBitmap(10, 11, 12)
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldConjugateOverlappingCollections() {
		assertArrayEquals(
			new int[]{1, 3, 5, 7, 9, 11},
			new OrFormula(
				INDEX_TRANSACTION_ID,
				new BaseBitmap(1, 3, 5, 7, 9, 11),
				new BaseBitmap(1, 3, 5, 7, 9, 11),
				new BaseBitmap(1, 3, 5, 7, 9, 11)
			)
				.compute().getArray()
		);
	}

}
