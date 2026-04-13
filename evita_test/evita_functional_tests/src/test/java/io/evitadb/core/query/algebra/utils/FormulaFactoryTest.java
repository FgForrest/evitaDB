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

package io.evitadb.core.query.algebra.utils;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.index.bitmap.ArrayBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FormulaFactory} verifying static factory methods produce correct formula types
 * and handle edge cases like empty arrays, single formulas, and nested formula merging.
 *
 * @author evitaDB
 */
@DisplayName("FormulaFactory")
class FormulaFactoryTest {

	@Nested
	@DisplayName("Or factory method")
	class OrFactoryTest {

		@Test
		@DisplayName("should return EmptyFormula for empty array")
		void shouldReturnEmptyFormulaForEmptyArray() {
			final Formula result = FormulaFactory.or();

			assertSame(EmptyFormula.INSTANCE, result);
		}

		@Test
		@DisplayName("should return single formula when only one provided")
		void shouldReturnSingleFormulaWhenOnlyOneProvided() {
			final ConstantFormula single = new ConstantFormula(new ArrayBitmap(1, 2, 3));

			final Formula result = FormulaFactory.or(single);

			assertSame(single, result);
		}

		@Test
		@DisplayName("should create OrFormula for multiple formulas")
		void shouldCreateOrFormulaForMultipleFormulas() {
			final ConstantFormula a = new ConstantFormula(new ArrayBitmap(1, 2));
			final ConstantFormula b = new ConstantFormula(new ArrayBitmap(3, 4));

			final Formula result = FormulaFactory.or(a, b);

			assertInstanceOf(OrFormula.class, result);
			assertArrayEquals(new int[]{1, 2, 3, 4}, result.compute().getArray());
		}

		@Test
		@DisplayName("should merge nested OrFormulas into flat structure")
		void shouldMergeNestedOrFormulas() {
			final ConstantFormula a = new ConstantFormula(new ArrayBitmap(1, 2));
			final ConstantFormula b = new ConstantFormula(new ArrayBitmap(3, 4));
			final OrFormula nested = new OrFormula(a, b);
			final ConstantFormula c = new ConstantFormula(new ArrayBitmap(5, 6));

			final Formula result = FormulaFactory.or(nested, c);

			assertInstanceOf(OrFormula.class, result);
			// the nested OR should be flattened, so inner formulas should include a, b, and c
			final Formula[] innerFormulas = result.getInnerFormulas();
			assertTrue(
				innerFormulas.length >= 3,
				"Nested OR should be merged, expected >= 3 inner formulas but got " + innerFormulas.length
			);
			assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, result.compute().getArray());
		}
	}

	@Nested
	@DisplayName("And factory method")
	class AndFactoryTest {

		@Test
		@DisplayName("should return EmptyFormula for empty array")
		void shouldReturnEmptyFormulaForEmptyArray() {
			final Formula result = FormulaFactory.and();

			assertSame(EmptyFormula.INSTANCE, result);
		}

		@Test
		@DisplayName("should return single formula when only one provided")
		void shouldReturnSingleFormulaWhenOnlyOneProvided() {
			final ConstantFormula single = new ConstantFormula(new ArrayBitmap(1, 2, 3));

			final Formula result = FormulaFactory.and(single);

			assertSame(single, result);
		}

		@Test
		@DisplayName("should create AndFormula for multiple formulas")
		void shouldCreateAndFormulaForMultipleFormulas() {
			final ConstantFormula a = new ConstantFormula(new ArrayBitmap(1, 2, 3, 4));
			final ConstantFormula b = new ConstantFormula(new ArrayBitmap(2, 3, 4, 5));

			final Formula result = FormulaFactory.and(a, b);

			assertInstanceOf(AndFormula.class, result);
			assertArrayEquals(new int[]{2, 3, 4}, result.compute().getArray());
		}

		@Test
		@DisplayName("should merge nested AndFormulas into flat structure")
		void shouldMergeNestedAndFormulas() {
			final ConstantFormula a = new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5));
			final ConstantFormula b = new ConstantFormula(new ArrayBitmap(2, 3, 4, 5, 6));
			final AndFormula nested = new AndFormula(a, b);
			final ConstantFormula c = new ConstantFormula(new ArrayBitmap(3, 4, 5, 6, 7));

			final Formula result = FormulaFactory.and(nested, c);

			assertInstanceOf(AndFormula.class, result);
			// the nested AND should be flattened
			final Formula[] innerFormulas = result.getInnerFormulas();
			assertTrue(
				innerFormulas.length >= 3,
				"Nested AND should be merged, expected >= 3 inner formulas but got " + innerFormulas.length
			);
			assertArrayEquals(new int[]{3, 4, 5}, result.compute().getArray());
		}
	}

	@Nested
	@DisplayName("Not factory method")
	class NotFactoryTest {

		@Test
		@DisplayName("should create NotFormula")
		void shouldCreateNotFormula() {
			final ConstantFormula subtracted = new ConstantFormula(new ArrayBitmap(2, 4));
			final ConstantFormula superset = new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5));

			final Formula result = FormulaFactory.not(subtracted, superset);

			assertInstanceOf(NotFormula.class, result);
			assertArrayEquals(new int[]{1, 3, 5}, result.compute().getArray());
		}
	}
}
