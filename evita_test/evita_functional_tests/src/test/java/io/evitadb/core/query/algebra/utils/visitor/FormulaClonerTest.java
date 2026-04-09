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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FormulaCloner} verifying deep cloning, mutation, and structural
 * transformations of formula trees.
 *
 * @author evitaDB
 */
@DisplayName("FormulaCloner - formula tree cloning and mutation")
class FormulaClonerTest {
	private Formula formula;

	@BeforeEach
	void setUp() {
		this.formula = new AndFormula(
			new OrFormula(
				EmptyFormula.INSTANCE,
				new ConstantFormula(new ArrayBitmap(3)),
				new NotFormula(
					new ConstantFormula(new ArrayBitmap(5)),
					new ConstantFormula(new ArrayBitmap(5, 8, 10))
				)
			),
			new UserFilterFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				new ConstantFormula(new ArrayBitmap(2))
			)
		);
	}

	@Nested
	@DisplayName("Identity and replacement cloning")
	class IdentityAndReplacementTest {

		@Test
		@DisplayName("should return same instance when mutator is identity")
		void shouldLeaveFormulaUntouched() {
			final Formula cloneResult = FormulaCloner.clone(formula, UnaryOperator.identity());

			assertSame(formula, cloneResult);
		}

		@Test
		@DisplayName("should replace entire tree when mutator returns constant")
		void shouldReplaceEntireFormula() {
			final ConstantFormula replacedFormula = new ConstantFormula(new ArrayBitmap(7));

			final Formula cloneResult = FormulaCloner.clone(formula, examinedFormula -> replacedFormula);

			assertSame(replacedFormula, cloneResult);
		}

		@Test
		@DisplayName("should clone single leaf formula with identity mutator")
		void shouldCloneSingleLeafFormulaWithIdentityMutator() {
			final ConstantFormula leaf = new ConstantFormula(new ArrayBitmap(42));

			final Formula cloneResult = FormulaCloner.clone(leaf, UnaryOperator.identity());

			assertSame(leaf, cloneResult);
		}

		@Test
		@DisplayName("should replace single leaf formula")
		void shouldReplaceSingleLeafFormula() {
			final ConstantFormula leaf = new ConstantFormula(new ArrayBitmap(42));
			final ConstantFormula replacement = new ConstantFormula(new ArrayBitmap(99));

			final Formula cloneResult = FormulaCloner.clone(leaf, f -> replacement);

			assertSame(replacement, cloneResult);
		}

		@Test
		@DisplayName("should return null when mutator returns null for root")
		void shouldReturnNullWhenMutatorReturnsNullForRoot() {
			final ConstantFormula leaf = new ConstantFormula(new ArrayBitmap(42));

			final Formula cloneResult = FormulaCloner.clone(leaf, f -> null);

			assertNull(cloneResult);
		}
	}

	@Nested
	@DisplayName("Child removal and tree reconstruction")
	class ChildRemovalTest {

		@Test
		@DisplayName("should remove EmptyFormula and preserve other children")
		void shouldGetRidOfEmptyFormulas() {
			final Formula cloneResult = FormulaCloner.clone(
				formula,
				examinedFormula -> examinedFormula instanceof EmptyFormula ? null : examinedFormula
			);

			assertNotSame(formula, cloneResult);
			assertTrue(FormulaLocator.contains(formula, EmptyFormula.class));
			assertFalse(FormulaLocator.contains(cloneResult, EmptyFormula.class));

			assertNotSame(formula.getInnerFormulas()[0], cloneResult.getInnerFormulas()[0]);
			assertSame(
				formula.getInnerFormulas()[0].getInnerFormulas()[1],
				cloneResult.getInnerFormulas()[0].getInnerFormulas()[0]
			);
			assertSame(
				formula.getInnerFormulas()[0].getInnerFormulas()[2],
				cloneResult.getInnerFormulas()[0].getInnerFormulas()[1]
			);
			assertSame(formula.getInnerFormulas()[1], cloneResult.getInnerFormulas()[1]);
		}

		@Test
		@DisplayName("should collapse parent to single child when sibling removed")
		void shouldHandleUnnecessaryFormulas() {
			final Formula cloneResult = FormulaCloner.clone(
				formula,
				examinedFormula -> examinedFormula instanceof UserFilterFormula ? null : examinedFormula
			);

			assertNotSame(formula, cloneResult);
			// When AndFormula loses one of two children, getCloneWithInnerFormulas returns the surviving child
			assertSame(formula.getInnerFormulas()[0], cloneResult);
		}

		@Test
		@DisplayName("should replace specific inner formula with a different one")
		void shouldReplaceSpecificInnerFormula() {
			final ConstantFormula replacement = new ConstantFormula(new ArrayBitmap(77));
			final Formula cloneResult = FormulaCloner.clone(
				formula,
				f -> f instanceof EmptyFormula ? replacement : f
			);

			assertNotSame(formula, cloneResult);
			// EmptyFormula should be gone, replaced by ConstantFormula(77)
			assertFalse(FormulaLocator.contains(cloneResult, EmptyFormula.class));
			// OrFormula should have replacement as first child now
			final Formula orClone = cloneResult.getInnerFormulas()[0];
			assertSame(replacement, orClone.getInnerFormulas()[0]);
		}
	}

	@Nested
	@DisplayName("NotFormula special handling")
	class NotFormulaHandlingTest {

		@Test
		@DisplayName("should return superset when subtracted child is removed from NotFormula")
		void shouldReturnSupersetWhenSubtractedChildRemoved() {
			final ConstantFormula subtracted = new ConstantFormula(new ArrayBitmap(5));
			final ConstantFormula superset = new ConstantFormula(new ArrayBitmap(5, 8, 10));
			final Formula notFormula = new NotFormula(subtracted, superset);

			// Remove the subtracted formula -> S \ nothing = S
			final Formula cloneResult = FormulaCloner.clone(
				notFormula,
				f -> f == subtracted ? null : f
			);

			// Result should be the superset
			assertSame(superset, cloneResult);
		}

		@Test
		@DisplayName("should drop NotFormula when superset child is removed")
		void shouldDropNotFormulaWhenSupersetChildRemoved() {
			final ConstantFormula subtracted = new ConstantFormula(new ArrayBitmap(5));
			final ConstantFormula superset = new ConstantFormula(new ArrayBitmap(5, 8, 10));
			final Formula notFormula = new NotFormula(subtracted, superset);

			// Remove the superset formula -> nothing to subtract from -> drop
			final Formula cloneResult = FormulaCloner.clone(
				notFormula,
				f -> f == superset ? null : f
			);

			assertNull(cloneResult);
		}

		@Test
		@DisplayName("should handle NotFormula in nested tree when subtracted removed")
		void shouldHandleNotFormulaInNestedTreeWhenSubtractedRemoved() {
			final ConstantFormula subtracted = new ConstantFormula(new ArrayBitmap(5));
			final ConstantFormula superset = new ConstantFormula(new ArrayBitmap(5, 8, 10));
			final Formula tree = new OrFormula(
				new NotFormula(subtracted, superset),
				new ConstantFormula(new ArrayBitmap(1))
			);

			final Formula cloneResult = FormulaCloner.clone(
				tree,
				f -> f == subtracted ? null : f
			);

			// OrFormula should still exist, but NotFormula should be replaced by superset
			assertNotNull(cloneResult);
			assertInstanceOf(OrFormula.class, cloneResult);
			// The NotFormula child should have been replaced with the superset
			final Formula firstChild = cloneResult.getInnerFormulas()[0];
			assertSame(superset, firstChild);
		}
	}

	@Nested
	@DisplayName("BiFunction mutator and parent context")
	class ParentContextTest {

		@Test
		@DisplayName("should resolve isWithin with class parameter")
		void shouldResolveIsWithin() {
			final Formula cloneResult = FormulaCloner.clone(
				formula, (formulaCloner, currentFormula) -> {
					if (formulaCloner.isWithin(UserFilterFormula.class)) {
						return null;
					} else {
						return currentFormula;
					}
				}
			);

			assertNotSame(formula, cloneResult);
			assertTrue(FormulaLocator.contains(formula, UserFilterFormula.class));

			assertSame(formula.getInnerFormulas()[0], cloneResult.getInnerFormulas()[0]);
			assertNotSame(formula.getInnerFormulas()[1], cloneResult.getInnerFormulas()[1]);
			assertEquals(0, cloneResult.getInnerFormulas()[1].getInnerFormulas().length);
		}

		@Test
		@DisplayName("should resolve isWithin with predicate parameter")
		void shouldResolveIsWithinWithPredicate() {
			final Formula cloneResult = FormulaCloner.clone(
				formula, (formulaCloner, currentFormula) -> {
					if (formulaCloner.isWithin(f -> f instanceof OrFormula)) {
						return null;
					} else {
						return currentFormula;
					}
				}
			);

			assertNotNull(cloneResult);
			// All children of OrFormula should be removed
			final Formula orClone = cloneResult.getInnerFormulas()[0];
			assertEquals(0, orClone.getInnerFormulas().length);
		}

		@Test
		@DisplayName("should report allParentsMatch correctly")
		void shouldReportAllParentsMatchCorrectly() {
			// Track which formulas see all parents matching
			final Formula cloneResult = FormulaCloner.clone(
				formula, (formulaCloner, currentFormula) -> {
					// At root level (no parents), allParentsMatch should be true vacuously
					if (currentFormula == formula) {
						assertTrue(
							formulaCloner.allParentsMatch(f -> f instanceof AndFormula),
							"allParentsMatch should be vacuously true at root"
						);
					}
					return currentFormula;
				}
			);

			assertSame(formula, cloneResult);
		}

		@Test
		@DisplayName("should report allParentsMatch false when a parent does not match")
		void shouldReportAllParentsMatchFalseWhenParentDoesNotMatch() {
			final ConstantFormula targetLeaf = new ConstantFormula(new ArrayBitmap(1));
			final Formula tree = new AndFormula(
				new OrFormula(
					targetLeaf,
					new ConstantFormula(new ArrayBitmap(2))
				),
				new ConstantFormula(new ArrayBitmap(3, 4))
			);

			FormulaCloner.clone(
				tree, (formulaCloner, currentFormula) -> {
					if (currentFormula == targetLeaf) {
						// parents are OrFormula + AndFormula, not all are OrFormula
						assertFalse(formulaCloner.allParentsMatch(f -> f instanceof OrFormula));
						// but all parents are Formula
						assertTrue(formulaCloner.allParentsMatch(f -> f instanceof Formula));
					}
					return currentFormula;
				}
			);
		}
	}

	@Nested
	@DisplayName("Duplicate instance handling")
	class DuplicateInstanceTest {

		@Test
		@DisplayName("should reuse processed result for duplicate formula instance")
		void shouldReuseProcessedResultForDuplicateFormulaInstance() {
			final ConstantFormula shared = new ConstantFormula(new ArrayBitmap(42));
			final Formula tree = new AndFormula(
				new OrFormula(shared, new ConstantFormula(new ArrayBitmap(1))),
				new OrFormula(shared, new ConstantFormula(new ArrayBitmap(2)))
			);

			final ConstantFormula replacement = new ConstantFormula(new ArrayBitmap(99));
			final Formula cloneResult = FormulaCloner.clone(
				tree,
				f -> f == shared ? replacement : f
			);

			// Both references to 'shared' should be replaced with the same 'replacement'
			assertNotNull(cloneResult);
			final Formula firstOr = cloneResult.getInnerFormulas()[0];
			final Formula secondOr = cloneResult.getInnerFormulas()[1];
			assertSame(replacement, firstOr.getInnerFormulas()[0]);
			assertSame(replacement, secondOr.getInnerFormulas()[0]);
		}
	}

	@Nested
	@DisplayName("Deep tree cloning")
	class DeepTreeTest {

		@Test
		@DisplayName("should clone four-level deep tree")
		void shouldCloneFourLevelDeepTree() {
			final ConstantFormula deepLeaf = new ConstantFormula(new ArrayBitmap(100));
			final Formula tree = new OrFormula(
				new AndFormula(
					new UserFilterFormula(deepLeaf),
					new ConstantFormula(new ArrayBitmap(2, 3))
				),
				new ConstantFormula(new ArrayBitmap(4))
			);

			// Replace the deep leaf
			final ConstantFormula replacement = new ConstantFormula(new ArrayBitmap(200));
			final Formula cloneResult = FormulaCloner.clone(
				tree,
				f -> f == deepLeaf ? replacement : f
			);

			assertNotNull(cloneResult);
			// Navigate: OrFormula -> AndFormula -> UserFilterFormula -> replacement
			final Formula andClone = cloneResult.getInnerFormulas()[0];
			final Formula userFilterClone = andClone.getInnerFormulas()[0];
			assertSame(replacement, userFilterClone.getInnerFormulas()[0]);
			// Unchanged branch should be preserved
			assertSame(tree.getInnerFormulas()[1], cloneResult.getInnerFormulas()[1]);
		}
	}

	@Nested
	@DisplayName("Static clone methods")
	class StaticMethodTest {

		@Test
		@DisplayName("should clone with UnaryOperator static method")
		void shouldCloneWithUnaryOperatorStaticMethod() {
			final ConstantFormula leaf = new ConstantFormula(new ArrayBitmap(1));
			final ConstantFormula replacement = new ConstantFormula(new ArrayBitmap(2));

			final Formula result = FormulaCloner.clone(leaf, (UnaryOperator<Formula>) f -> replacement);

			assertSame(replacement, result);
		}

		@Test
		@DisplayName("should clone with BiFunction static method")
		void shouldCloneWithBiFunctionStaticMethod() {
			final ConstantFormula leaf = new ConstantFormula(new ArrayBitmap(1));

			final Formula result = FormulaCloner.clone(
				leaf, (cloner, f) -> f
			);

			assertSame(leaf, result);
		}
	}
}
