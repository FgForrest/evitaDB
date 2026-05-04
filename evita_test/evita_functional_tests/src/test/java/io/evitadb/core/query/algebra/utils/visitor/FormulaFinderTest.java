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
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.index.bitmap.ArrayBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Tests for {@link FormulaFinder} verifying formula search in trees with SHALLOW and DEEP modes.
 *
 * @author evitaDB
 */
@DisplayName("FormulaFinder - formula search in trees")
@Tag(ENGINE)
@Tag(QUERY)
class FormulaFinderTest {

	@Nested
	@DisplayName("Find with class predicate")
	class FindByClassTest {

		@Test
		@DisplayName("should find root formula by its type")
		void shouldFindRootFormulaByItsType() {
			final ConstantFormula root = new ConstantFormula(new ArrayBitmap(1, 2));

			final Collection<ConstantFormula> result = FormulaFinder.find(root, ConstantFormula.class, LookUp.SHALLOW);

			assertEquals(1, result.size());
			assertTrue(result.contains(root));
		}

		@Test
		@DisplayName("should return empty collection when type not present")
		void shouldReturnEmptyCollectionWhenTypeNotPresent() {
			final Formula tree = new OrFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				new ConstantFormula(new ArrayBitmap(2))
			);

			final Collection<NotFormula> result = FormulaFinder.find(tree, NotFormula.class, LookUp.DEEP);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should find multiple formulas of same type")
		void shouldFindMultipleFormulasOfSameType() {
			final ConstantFormula c1 = new ConstantFormula(new ArrayBitmap(1));
			final ConstantFormula c2 = new ConstantFormula(new ArrayBitmap(2));
			final ConstantFormula c3 = new ConstantFormula(new ArrayBitmap(3));
			final Formula tree = new OrFormula(
				c1,
				new AndFormula(c2, c3)
			);

			final Collection<ConstantFormula> result = FormulaFinder.find(tree, ConstantFormula.class, LookUp.DEEP);

			assertEquals(3, result.size());
			assertTrue(result.contains(c1));
			assertTrue(result.contains(c2));
			assertTrue(result.contains(c3));
		}
	}

	@Nested
	@DisplayName("SHALLOW vs DEEP lookup")
	class LookUpModeTest {

		@Test
		@DisplayName("should stop at matched node in SHALLOW mode")
		void shouldStopAtMatchedNodeInShallowMode() {
			// UserFilterFormula containing another OrFormula with ConstantFormulas inside
			final ConstantFormula innerConstant = new ConstantFormula(new ArrayBitmap(1));
			final Formula tree = new OrFormula(
				new UserFilterFormula(innerConstant),
				new ConstantFormula(new ArrayBitmap(2))
			);

			// SHALLOW should find UserFilterFormula but NOT descend into its children
			final Collection<UserFilterFormula> result = FormulaFinder.find(
				tree, UserFilterFormula.class, LookUp.SHALLOW
			);

			assertEquals(1, result.size());

			// Searching for ConstantFormula in SHALLOW should find only the one NOT inside UserFilter
			// (because tree root is OrFormula, not matching ConstantFormula; it descends into both children)
			final Collection<ConstantFormula> constants = FormulaFinder.find(
				tree, ConstantFormula.class, LookUp.SHALLOW
			);
			// Should find both: the one inside UserFilter and the sibling
			// Because SHALLOW stops on the MATCHED node, not on arbitrary nodes
			assertEquals(2, constants.size());
		}

		@Test
		@DisplayName("should descend into matched node in DEEP mode")
		void shouldDescendIntoMatchedNodeInDeepMode() {
			// Create a nested structure: OrFormula > OrFormula > ConstantFormula
			final Formula tree = new OrFormula(
				new OrFormula(
					new ConstantFormula(new ArrayBitmap(1)),
					new ConstantFormula(new ArrayBitmap(2))
				),
				new ConstantFormula(new ArrayBitmap(3))
			);

			// DEEP should find both OrFormulas (root and nested)
			final Collection<OrFormula> result = FormulaFinder.find(tree, OrFormula.class, LookUp.DEEP);

			assertEquals(2, result.size());
		}

		@Test
		@DisplayName("should not descend into matched node in SHALLOW mode")
		void shouldNotDescendIntoMatchedNodeInShallowMode() {
			final Formula tree = new OrFormula(
				new OrFormula(
					new ConstantFormula(new ArrayBitmap(1)),
					new ConstantFormula(new ArrayBitmap(2))
				),
				new ConstantFormula(new ArrayBitmap(3))
			);

			// SHALLOW: root OrFormula matches, so it stops - does NOT find the inner OrFormula
			final Collection<OrFormula> result = FormulaFinder.find(tree, OrFormula.class, LookUp.SHALLOW);

			assertEquals(1, result.size());
		}
	}

	@Nested
	@DisplayName("findAmongChildren - excluding root")
	class FindAmongChildrenTest {

		@Test
		@DisplayName("should exclude root when root matches type")
		void shouldExcludeRootWhenRootMatchesType() {
			final ConstantFormula c1 = new ConstantFormula(new ArrayBitmap(1));
			final ConstantFormula c2 = new ConstantFormula(new ArrayBitmap(2));
			final OrFormula root = new OrFormula(c1, c2);

			// OrFormula is the root, findAmongChildren should skip it
			final Collection<OrFormula> result = FormulaFinder.findAmongChildren(root, OrFormula.class, LookUp.DEEP);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should find matching children but not root")
		void shouldFindMatchingChildrenButNotRoot() {
			final ConstantFormula c1 = new ConstantFormula(new ArrayBitmap(1));
			final ConstantFormula c2 = new ConstantFormula(new ArrayBitmap(2));
			final OrFormula root = new OrFormula(c1, c2);

			final Collection<ConstantFormula> result = FormulaFinder.findAmongChildren(
				root, ConstantFormula.class, LookUp.SHALLOW
			);

			assertEquals(2, result.size());
			assertTrue(result.contains(c1));
			assertTrue(result.contains(c2));
		}

		@Test
		@DisplayName("should work with predicate variant")
		void shouldWorkWithPredicateVariant() {
			final ConstantFormula c1 = new ConstantFormula(new ArrayBitmap(1));
			final ConstantFormula c2 = new ConstantFormula(new ArrayBitmap(2));
			final OrFormula root = new OrFormula(c1, c2);

			final Collection<ConstantFormula> result = FormulaFinder.findAmongChildren(
				root,
				formula -> formula instanceof ConstantFormula,
				LookUp.SHALLOW
			);

			assertEquals(2, result.size());
		}
	}

	@Nested
	@DisplayName("Find with custom predicate")
	class FindByPredicateTest {

		@Test
		@DisplayName("should find formulas matching custom predicate")
		void shouldFindFormulasMatchingCustomPredicate() {
			final ConstantFormula c1 = new ConstantFormula(new ArrayBitmap(1));
			final ConstantFormula c2 = new ConstantFormula(new ArrayBitmap(2, 3, 4));
			final Formula tree = new OrFormula(c1, c2);

			// predicate: ConstantFormula with delegate size > 1
			final Collection<ConstantFormula> result = FormulaFinder.find(
				tree,
				formula -> formula instanceof ConstantFormula cf && cf.getDelegate().size() > 1,
				LookUp.DEEP
			);

			assertEquals(1, result.size());
			assertTrue(result.contains(c2));
		}

		@Test
		@DisplayName("should find formulas matching predicate among children")
		void shouldFindFormulasMatchingPredicateAmongChildren() {
			final ConstantFormula c1 = new ConstantFormula(new ArrayBitmap(1));
			final ConstantFormula c2 = new ConstantFormula(new ArrayBitmap(2));
			final Formula tree = new OrFormula(c1, c2);

			final Collection<Formula> result = FormulaFinder.findAmongChildren(
				tree,
				formula -> formula instanceof ConstantFormula,
				LookUp.DEEP
			);

			assertEquals(2, result.size());
		}
	}

	@Nested
	@DisplayName("Skip predicate behavior")
	class SkipPredicateTest {

		@Test
		@DisplayName("should skip formula subtree matching skip predicate")
		void shouldSkipFormulaSubtreeMatchingSkipPredicate() {
			final ConstantFormula outsideConstant = new ConstantFormula(new ArrayBitmap(1));
			final ConstantFormula insideConstant = new ConstantFormula(new ArrayBitmap(2));
			final Formula tree = new AndFormula(
				outsideConstant,
				new UserFilterFormula(insideConstant)
			);

			// Skip UserFilterFormula and everything below it
			final Collection<ConstantFormula> result = FormulaFinder.find(
				tree,
				formula -> formula instanceof ConstantFormula,
				formula -> formula instanceof UserFilterFormula,
				LookUp.DEEP
			);

			assertEquals(1, result.size());
			assertTrue(result.contains(outsideConstant));
			assertFalse(result.contains(insideConstant));
		}

		@Test
		@DisplayName("should skip root if root matches skip predicate")
		void shouldSkipRootIfRootMatchesSkipPredicate() {
			final ConstantFormula c1 = new ConstantFormula(new ArrayBitmap(1));
			final ConstantFormula c2 = new ConstantFormula(new ArrayBitmap(2));
			final Formula tree = new OrFormula(c1, c2);

			// Skip OrFormula (root) so nothing should be found
			final Collection<ConstantFormula> result = FormulaFinder.find(
				tree,
				formula -> formula instanceof ConstantFormula,
				formula -> formula instanceof OrFormula,
				LookUp.DEEP
			);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("should work with findAmongChildren and skip predicate")
		void shouldWorkWithFindAmongChildrenAndSkipPredicate() {
			final ConstantFormula c1 = new ConstantFormula(new ArrayBitmap(1));
			final ConstantFormula c2 = new ConstantFormula(new ArrayBitmap(2));
			final Formula tree = new AndFormula(
				new UserFilterFormula(c1),
				new OrFormula(
					c2,
					new ConstantFormula(new ArrayBitmap(3))
				)
			);

			// Skip UserFilterFormula subtrees, find ConstantFormulas among children
			final Collection<ConstantFormula> result = FormulaFinder.findAmongChildren(
				tree,
				formula -> formula instanceof ConstantFormula,
				formula -> formula instanceof UserFilterFormula,
				LookUp.DEEP
			);

			assertEquals(2, result.size());
			assertFalse(result.contains(c1));
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTest {

		@Test
		@DisplayName("should handle leaf formula with no children")
		void shouldHandleLeafFormulaWithNoChildren() {
			final Formula leaf = EmptyFormula.INSTANCE;

			final Collection<EmptyFormula> result = FormulaFinder.find(leaf, EmptyFormula.class, LookUp.DEEP);

			assertEquals(1, result.size());
		}

		@Test
		@DisplayName("should not produce duplicates for same formula reference")
		void shouldNotProduceDuplicatesForSameFormulaReference() {
			final ConstantFormula shared = new ConstantFormula(new ArrayBitmap(1, 2));
			// same instance used twice as children (this is valid in formula trees)
			final Formula tree = new OrFormula(shared, new ConstantFormula(new ArrayBitmap(3)));

			final Collection<ConstantFormula> result = FormulaFinder.find(tree, ConstantFormula.class, LookUp.DEEP);

			assertEquals(2, result.size());
		}

		@Test
		@DisplayName("should handle tree with EmptyFormula leaves")
		void shouldHandleTreeWithEmptyFormulaLeaves() {
			final Formula tree = new OrFormula(
				EmptyFormula.INSTANCE,
				new ConstantFormula(new ArrayBitmap(1))
			);

			final Collection<EmptyFormula> result = FormulaFinder.find(tree, EmptyFormula.class, LookUp.SHALLOW);

			assertEquals(1, result.size());
		}
	}
}
