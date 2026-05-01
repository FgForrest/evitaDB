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
import io.evitadb.core.query.algebra.facet.ScopeContainerFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.dataType.Scope;
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
			final Formula cloneResult = FormulaCloner.clone(FormulaClonerTest.this.formula, UnaryOperator.identity());

			assertSame(FormulaClonerTest.this.formula, cloneResult);
		}

		@Test
		@DisplayName("should replace entire tree when mutator returns constant")
		void shouldReplaceEntireFormula() {
			final ConstantFormula replacedFormula = new ConstantFormula(new ArrayBitmap(7));

			final Formula cloneResult = FormulaCloner.clone(FormulaClonerTest.this.formula, examinedFormula -> replacedFormula);

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
				FormulaClonerTest.this.formula,
				examinedFormula -> examinedFormula instanceof EmptyFormula ? null : examinedFormula
			);

			assertNotSame(FormulaClonerTest.this.formula, cloneResult);
			assertTrue(FormulaLocator.contains(FormulaClonerTest.this.formula, EmptyFormula.class));
			assertFalse(FormulaLocator.contains(cloneResult, EmptyFormula.class));

			assertNotSame(FormulaClonerTest.this.formula.getInnerFormulas()[0], cloneResult.getInnerFormulas()[0]);
			assertSame(
				FormulaClonerTest.this.formula.getInnerFormulas()[0].getInnerFormulas()[1],
				cloneResult.getInnerFormulas()[0].getInnerFormulas()[0]
			);
			assertSame(
				FormulaClonerTest.this.formula.getInnerFormulas()[0].getInnerFormulas()[2],
				cloneResult.getInnerFormulas()[0].getInnerFormulas()[1]
			);
			assertSame(FormulaClonerTest.this.formula.getInnerFormulas()[1], cloneResult.getInnerFormulas()[1]);
		}

		@Test
		@DisplayName("should collapse parent to single child when sibling removed")
		void shouldHandleUnnecessaryFormulas() {
			final Formula cloneResult = FormulaCloner.clone(
				FormulaClonerTest.this.formula,
				examinedFormula -> examinedFormula instanceof UserFilterFormula ? null : examinedFormula
			);

			assertNotSame(FormulaClonerTest.this.formula, cloneResult);
			// When AndFormula loses one of two children, getCloneWithInnerFormulas returns the surviving child
			assertSame(FormulaClonerTest.this.formula.getInnerFormulas()[0], cloneResult);
		}

		@Test
		@DisplayName("should replace specific inner formula with a different one")
		void shouldReplaceSpecificInnerFormula() {
			final ConstantFormula replacement = new ConstantFormula(new ArrayBitmap(77));
			final Formula cloneResult = FormulaCloner.clone(
				FormulaClonerTest.this.formula,
				f -> f instanceof EmptyFormula ? replacement : f
			);

			assertNotSame(FormulaClonerTest.this.formula, cloneResult);
			// EmptyFormula should be gone, replaced by ConstantFormula(77)
			assertFalse(FormulaLocator.contains(cloneResult, EmptyFormula.class));
			// OrFormula should have replacement as first child now
			final Formula orClone = cloneResult.getInnerFormulas()[0];
			assertSame(replacement, orClone.getInnerFormulas()[0]);
		}

		@Test
		@DisplayName("should drop nested empty container chain instead of propagating EmptyFormula upward")
		void shouldDropNestedEmptyContainerChain() {
			// Reproduces the bug case where strip mutators empty out an *intermediate* container
			// inside UserFilterFormula (e.g. userFilter(and(stripA, stripB))). The inner AND ends up
			// with no children and `AndFormula.getCloneWithInnerFormulas([])` returns EmptyFormula —
			// the cloner used to let that propagate through UserFilter and into the surrounding AND,
			// collapsing the entire result to "no entities". The convention-driven rule now drops
			// any wrapper that returns EmptyFormula on empty children, cascading the cleanup so the
			// surrounding tree is preserved as if the user-filter wasn't there at all.
			final ConstantFormula stripA = new ConstantFormula(new ArrayBitmap(101));
			final ConstantFormula stripB = new ConstantFormula(new ArrayBitmap(102));
			final ConstantFormula keepMe = new ConstantFormula(new ArrayBitmap(7, 8));
			final Formula tree = new AndFormula(
				keepMe,
				new UserFilterFormula(
					new AndFormula(stripA, stripB)
				)
			);

			final Formula cloneResult = FormulaCloner.clone(
				tree,
				examined -> examined == stripA || examined == stripB ? null : examined
			);

			assertNotSame(tree, cloneResult);
			// UserFilter is gone (its only child — the inner AND — was emptied and dropped, so the
			// UserFilter itself ends up empty and is dropped too)
			assertFalse(FormulaLocator.contains(cloneResult, UserFilterFormula.class));
			// EmptyFormula must NOT have leaked into the cloned tree
			assertFalse(FormulaLocator.contains(cloneResult, EmptyFormula.class));
			// outer AND collapses to its single surviving child
			assertSame(keepMe, cloneResult);
		}

		@Test
		@DisplayName("should drop AndFormula when all its children are stripped")
		void shouldDropAndFormulaWhenAllChildrenStripped() {
			// Validates the generalised empty-drop convention — once both children of the inner AND are
			// stripped, AndFormula.getCloneWithInnerFormulas([]) returns EmptyFormula and the cloner must
			// drop the wrapper instead of letting EmptyFormula leak into the surrounding OR.
			final ConstantFormula stripA = new ConstantFormula(new ArrayBitmap(101));
			final ConstantFormula stripB = new ConstantFormula(new ArrayBitmap(102));
			final ConstantFormula keep = new ConstantFormula(new ArrayBitmap(7, 8));
			final Formula tree = new OrFormula(
				keep,
				new AndFormula(stripA, stripB)
			);

			final Formula cloneResult = FormulaCloner.clone(
				tree,
				f -> f == stripA || f == stripB ? null : f
			);

			assertNotNull(cloneResult);
			assertFalse(FormulaLocator.contains(cloneResult, EmptyFormula.class));
			// OR with one surviving child collapses to that child
			assertSame(keep, cloneResult);
		}

		@Test
		@DisplayName("should drop OrFormula when all its children are stripped")
		void shouldDropOrFormulaWhenAllChildrenStripped() {
			// Mirrors the AND case: stripping every child of an OR leaves OrFormula.getCloneWithInnerFormulas([])
			// returning EmptyFormula; the cloner must drop the wrapper, not propagate it through the surrounding AND.
			final ConstantFormula stripA = new ConstantFormula(new ArrayBitmap(201));
			final ConstantFormula stripB = new ConstantFormula(new ArrayBitmap(202));
			final ConstantFormula keep = new ConstantFormula(new ArrayBitmap(11, 12));
			final Formula tree = new AndFormula(
				keep,
				new OrFormula(stripA, stripB)
			);

			final Formula cloneResult = FormulaCloner.clone(
				tree,
				f -> f == stripA || f == stripB ? null : f
			);

			assertNotNull(cloneResult);
			assertFalse(FormulaLocator.contains(cloneResult, EmptyFormula.class));
			// AND with one surviving child collapses to that child
			assertSame(keep, cloneResult);
		}

		@Test
		@DisplayName("should drop ScopeContainerFormula when all its children are stripped")
		void shouldDropScopeContainerFormulaWhenAllChildrenStripped() {
			// ScopeContainerFormula.getCloneWithInnerFormulas([]) returns EmptyFormula too — the cloner must
			// honour the generalised convention and drop the scope container instead of leaking EmptyFormula upwards.
			final ConstantFormula stripA = new ConstantFormula(new ArrayBitmap(301));
			final ConstantFormula stripB = new ConstantFormula(new ArrayBitmap(302));
			final ConstantFormula keep = new ConstantFormula(new ArrayBitmap(21, 22));
			final Formula tree = new AndFormula(
				keep,
				new ScopeContainerFormula(Scope.LIVE, stripA, stripB)
			);

			final Formula cloneResult = FormulaCloner.clone(
				tree,
				f -> f == stripA || f == stripB ? null : f
			);

			assertNotNull(cloneResult);
			assertFalse(FormulaLocator.contains(cloneResult, EmptyFormula.class));
			assertFalse(FormulaLocator.contains(cloneResult, ScopeContainerFormula.class));
			// AND with one surviving child collapses to that child
			assertSame(keep, cloneResult);
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

		@Test
		@DisplayName("should drop NotFormula when both children are stripped")
		void shouldDropNotFormulaWhenBothChildrenStripped() {
			final ConstantFormula stripSubtracted = new ConstantFormula(new ArrayBitmap(5));
			final ConstantFormula stripSuperset = new ConstantFormula(new ArrayBitmap(5, 8, 10));
			final ConstantFormula keep = new ConstantFormula(new ArrayBitmap(1));
			final Formula tree = new OrFormula(
				keep,
				new NotFormula(stripSubtracted, stripSuperset)
			);

			final Formula cloneResult = FormulaCloner.clone(
				tree,
				f -> f == stripSubtracted || f == stripSuperset ? null : f
			);

			assertNotNull(cloneResult);
			assertFalse(FormulaLocator.contains(cloneResult, NotFormula.class));
			// OR with one surviving child collapses to that child
			assertSame(keep, cloneResult);
		}
	}

	@Nested
	@DisplayName("BiFunction mutator and parent context")
	class ParentContextTest {

		@Test
		@DisplayName("should resolve isWithin with class parameter and drop the empty UserFilterFormula")
		void shouldResolveIsWithin() {
			// Strip every formula inside the UserFilterFormula. After the strip the wrapper has no children;
			// the cloner treats an empty UserFilterFormula as AND-identity (drops it from the parent) instead
			// of letting `getCloneWithInnerFormulas([])` short-circuit it to EmptyFormula. The root AND now
			// has a single surviving child — the untouched OrFormula sub-tree — and `AndFormula.getCloneWithInnerFormulas`
			// collapses an AND with one child to that child, so the result is the original OrFormula instance
			// itself (memoised identity preserved).
			final Formula cloneResult = FormulaCloner.clone(
				FormulaClonerTest.this.formula, (formulaCloner, currentFormula) -> {
					if (formulaCloner.isWithin(UserFilterFormula.class)) {
						return null;
					} else {
						return currentFormula;
					}
				}
			);

			assertNotSame(FormulaClonerTest.this.formula, cloneResult);
			assertTrue(FormulaLocator.contains(FormulaClonerTest.this.formula, UserFilterFormula.class));
			assertFalse(FormulaLocator.contains(cloneResult, UserFilterFormula.class));
			// AND with 1 surviving child collapses to that child — the cloned root IS the original OrFormula
			assertSame(FormulaClonerTest.this.formula.getInnerFormulas()[0], cloneResult);
		}

		@Test
		@DisplayName("should resolve isWithin with predicate parameter")
		void shouldResolveIsWithinWithPredicate() {
			final Formula cloneResult = FormulaCloner.clone(
				FormulaClonerTest.this.formula, (formulaCloner, currentFormula) -> {
					if (formulaCloner.isWithin(OrFormula.class::isInstance)) {
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
				FormulaClonerTest.this.formula, (formulaCloner, currentFormula) -> {
					// At root level (no parents), allParentsMatch should be true vacuously
					if (currentFormula == FormulaClonerTest.this.formula) {
						assertTrue(
							formulaCloner.allParentsMatch(AndFormula.class::isInstance),
							"allParentsMatch should be vacuously true at root"
						);
					}
					return currentFormula;
				}
			);

			assertSame(FormulaClonerTest.this.formula, cloneResult);
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
						assertFalse(formulaCloner.allParentsMatch(OrFormula.class::isInstance));
						// but all parents are Formula
						assertTrue(formulaCloner.allParentsMatch(Formula.class::isInstance));
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
