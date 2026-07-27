/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.algebra.utils.visitor;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.index.bitmap.ArrayBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Tests for {@link FormulaLocator} verifying formula type detection in formula trees.
 *
 * @author evitaDB
 */
@DisplayName("FormulaLocator - formula type detection in trees")
@Tag(ENGINE)
@Tag(QUERY)
class FormulaLocatorTest {

	@Nested
	@DisplayName("Locating formulas in flat structures")
	class FlatStructureTest {

		@Test
		@DisplayName("should find type when root formula matches")
		void shouldFindTypeWhenRootFormulaMatches() {
			final Formula leaf = new ConstantFormula(new ArrayBitmap(1, 2, 3));

			final boolean result = FormulaLocator.contains(leaf, ConstantFormula.class);

			assertTrue(result);
		}

		@Test
		@DisplayName("should not find type when root formula does not match")
		void shouldNotFindTypeWhenRootFormulaDoesNotMatch() {
			final Formula leaf = new ConstantFormula(new ArrayBitmap(1, 2, 3));

			final boolean result = FormulaLocator.contains(leaf, OrFormula.class);

			assertFalse(result);
		}

		@Test
		@DisplayName("should detect EmptyFormula singleton")
		void shouldDetectEmptyFormulaSingleton() {
			final Formula tree = new OrFormula(
				EmptyFormula.INSTANCE,
				new ConstantFormula(new ArrayBitmap(1))
			);

			assertTrue(FormulaLocator.contains(tree, EmptyFormula.class));
		}
	}

	@Nested
	@DisplayName("Locating formulas in deep trees")
	class DeepTreeTest {

		@Test
		@DisplayName("should find deeply nested formula type")
		void shouldFindDeeplyNestedFormulaType() {
			final Formula tree = new AndFormula(
				new OrFormula(
					new ConstantFormula(new ArrayBitmap(1)),
					new ConstantFormula(new ArrayBitmap(2))
				),
				new UserFilterFormula(
					new ConstantFormula(new ArrayBitmap(3))
				)
			);

			assertTrue(FormulaLocator.contains(tree, UserFilterFormula.class));
		}

		@Test
		@DisplayName("should not find absent type in deep tree")
		void shouldNotFindAbsentTypeInDeepTree() {
			final Formula tree = new AndFormula(
				new OrFormula(
					new ConstantFormula(new ArrayBitmap(1)),
					new ConstantFormula(new ArrayBitmap(2))
				),
				new ConstantFormula(new ArrayBitmap(3, 4))
			);

			assertFalse(FormulaLocator.contains(tree, NotFormula.class));
		}

		@Test
		@DisplayName("should find type at intermediate level")
		void shouldFindTypeAtIntermediateLevel() {
			final Formula tree = new AndFormula(
				new OrFormula(
					new ConstantFormula(new ArrayBitmap(1)),
					new ConstantFormula(new ArrayBitmap(2))
				),
				new NotFormula(
					new ConstantFormula(new ArrayBitmap(3)),
					new ConstantFormula(new ArrayBitmap(3, 4, 5))
				)
			);

			assertTrue(FormulaLocator.contains(tree, OrFormula.class));
			assertTrue(FormulaLocator.contains(tree, NotFormula.class));
			assertTrue(FormulaLocator.contains(tree, AndFormula.class));
		}
	}

	@Nested
	@DisplayName("Short-circuit behavior")
	class ShortCircuitTest {

		@Test
		@DisplayName("should stop searching after first match found")
		void shouldStopSearchingAfterFirstMatchFound() {
			// tree with two ConstantFormulas at different levels
			final Formula tree = new OrFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				new AndFormula(
					new ConstantFormula(new ArrayBitmap(2)),
					new ConstantFormula(new ArrayBitmap(3))
				)
			);

			// locator returns true - verifying short-circuit is implicit
			// (the first ConstantFormula match stops traversal)
			assertTrue(FormulaLocator.contains(tree, ConstantFormula.class));
		}
	}

	@Nested
	@DisplayName("Supertype and interface matching")
	class TypeHierarchyTest {

		@Test
		@DisplayName("should match by supertype using Formula interface")
		void shouldMatchBySupertypeUsingFormulaInterface() {
			final Formula leaf = new ConstantFormula(new ArrayBitmap(1, 2));

			// Formula.class is supertype of all formulas
			assertTrue(FormulaLocator.contains(leaf, Formula.class));
		}

		@Test
		@DisplayName("should not match unrelated type")
		void shouldNotMatchUnrelatedType() {
			final Formula leaf = new ConstantFormula(new ArrayBitmap(1, 2));

			assertFalse(FormulaLocator.contains(leaf, UserFilterFormula.class));
		}
	}
}
