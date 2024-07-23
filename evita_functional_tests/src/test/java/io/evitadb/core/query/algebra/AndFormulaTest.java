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

import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * This test verifies contract of {@link AndFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AndFormulaTest {

	public static final long[] INDEX_TRANSACTION_ID = {1L};

	@Test
	void shouldApplyBooleanAnd() {
		assertArrayEquals(
			new int[]{1, 4},
			new AndFormula(
				INDEX_TRANSACTION_ID,
				new ArrayBitmap(1, 3, 4, 5, 8),
				new ArrayBitmap(1, 2, 4, 8),
				new ArrayBitmap(1, 2, 3, 4, 5)
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldApplyBooleanAndWithFormula() {
		final AndFormula andFormula = new AndFormula(
			new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 3, 4, 5, 8))),
			new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 4, 8))),
			new ConstantFormula(new ArrayBitmap(new CompositeIntArray(1, 2, 3, 4, 5)))
		);
		assertArrayEquals(
			new int[]{1, 4},
			andFormula.compute().getArray()
		);
	}

	@Test
	void shouldApplyBooleanAndOnNonOverlappingCollections() {
		assertArrayEquals(
			new int[0],
			new AndFormula(
				INDEX_TRANSACTION_ID,
				new ArrayBitmap(1, 3, 5, 7, 9, 11),
				new ArrayBitmap(3, 4, 5, 6, 7),
				new ArrayBitmap(10, 11, 1)
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldReturnNothingForEmptyBitmaps() {
		assertArrayEquals(
			new int[0],
			new AndFormula(
				INDEX_TRANSACTION_ID,
				new ArrayBitmap(),
				new ArrayBitmap()
			)
				.compute().getArray()
		);
	}

	@Test
	void shouldReturnNothingForEmptyAndFullBitmap() {
		assertArrayEquals(
			new int[0],
			new AndFormula(
				INDEX_TRANSACTION_ID,
				new ArrayBitmap(1, 3, 4, 5, 8),
				new ArrayBitmap(),
				new ArrayBitmap(1, 2, 3, 4, 5)
			)
				.compute().getArray()
		);
	}

}
