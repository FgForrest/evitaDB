/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import io.evitadb.api.requestResponse.extraResult.FormulaPlan;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.DisentangleFormula;
import io.evitadb.core.query.algebra.facet.ScopeContainerFormula;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.ArrayBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FormulaPlanVisitor}, whose defining property is a negative one: describing a formula must never
 * execute it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Formula plan rendering")
@Tag(ENGINE)
@Tag(QUERY)
class FormulaPlanVisitorTest {

	/**
	 * Builds a small formula tree whose root has two children, neither of them computed.
	 *
	 * @return the root of the tree
	 */
	@Nonnull
	private static Formula uncomputedTree() {
		return new ScopeContainerFormula(
			Scope.LIVE,
			new ConstantFormula(new ArrayBitmap(1, 2, 3, 4, 5)),
			new ConstantFormula(new ArrayBitmap(2, 3, 4, 6))
		);
	}

	@Nested
	@DisplayName("Computation is never forced")
	class NeverForcesComputation {

		@Test
		@DisplayName("should leave every formula uncomputed after rendering the plan")
		void shouldLeaveEveryFormulaUncomputedAfterRenderingThePlan() {
			final Formula root = uncomputedTree();
			// the precondition the whole feature rests on - nothing has run yet
			assertNull(root.getMemoizedResult());
			for (final Formula innerFormula : root.getInnerFormulas()) {
				assertNull(innerFormula.getMemoizedResult());
			}

			FormulaPlanVisitor.toPlan(root);

			// this is *the* assertion of this class: had the renderer called compute(), these would be populated,
			// and asking for a profile would have made the query do work it had decided to skip
			assertNull(root.getMemoizedResult());
			for (final Formula innerFormula : root.getInnerFormulas()) {
				assertNull(innerFormula.getMemoizedResult());
			}
			// the cost accessors report the "unknown" sentinel precisely because nothing was computed
			assertEquals(Long.MAX_VALUE, root.getCost());
		}

		@Test
		@DisplayName("should report an uncomputed formula as having no outcome numbers")
		void shouldReportAnUncomputedFormulaAsHavingNoOutcomeNumbers() {
			final FormulaPlan plan = FormulaPlanVisitor.toPlan(uncomputedTree());

			// null rather than zero - "not measured" and "measured as none" are different answers, and a client
			// defaulting the former to the latter would report an unexecuted plan as one that matched nothing
			assertNull(plan.actualCost());
			assertNull(plan.resultCount());
			for (final FormulaPlan child : plan.children()) {
				assertNull(child.actualCost());
				assertNull(child.resultCount());
			}
			// the estimate, by contrast, exists without running anything - it is what the planner chose on
			assertNotNull(plan.description());
		}

		@Test
		@DisplayName("should report outcome numbers once the formula really has been computed")
		void shouldReportOutcomeNumbersOnceTheFormulaReallyHasBeenComputed() {
			final Formula root = uncomputedTree();
			root.compute();
			// the engine's own sequence: QueryPlan.recordQueryMetrics prices the tree for the ACTUAL_COST metric
			// before it renders. The renderer reports prices, it never sets them
			root.getCost();

			final FormulaPlan plan = FormulaPlanVisitor.toPlan(root);

			// the same renderer now reports the numbers, which proves the nulls above are a property of the
			// formula's state and not of the renderer being unable to read them
			assertNotNull(plan.actualCost());
			// the intersection of the two child bitmaps, i.e. {2, 3, 4}
			assertEquals(3, plan.resultCount());
		}

		@Test
		@DisplayName("should report a branch the computation short-circuited past as never having run")
		void shouldReportAShortCircuitedBranchAsNeverHavingRun() {
			// AND computes its children cheapest first and stops at the first empty result. These two constants
			// are disjoint, so this branch yields nothing - and it is the cheapest, so it is evaluated first
			final Formula shortCircuitingBranch = new AndFormula(
				new ConstantFormula(new ArrayBitmap(1)),
				new ConstantFormula(new ArrayBitmap(2))
			);
			final int[] manyRecords = new int[100];
			for (int i = 0; i < manyRecords.length; i++) {
				manyRecords[i] = i + 1;
			}
			// deliberately the expensive sibling, so the complexity ordering puts it second and never reaches it
			final Formula skippedBranch = new ConstantFormula(new ArrayBitmap(manyRecords));
			final Formula root = new AndFormula(shortCircuitingBranch, skippedBranch);

			root.compute();
			// as the engine does it - price the tree first, then render
			root.getCost();

			// the premise of this test: the root really did run, and really did skip the expensive branch. Were the
			// short-circuit ever removed, this is what says the scenario no longer exists rather than silently
			// turning the assertions below into a tautology
			assertNotNull(root.getMemoizedResult());
			assertNull(skippedBranch.getMemoizedResult());

			final FormulaPlan plan = FormulaPlanVisitor.toPlan(root);

			// the case neither test above reaches - a *partially* computed tree. Even the cost pass above leaves
			// the skipped branch alone, because AndFormula overrides getCostInternal() to short-circuit at the same
			// point its computation does; and the renderer reads only the free accessors, so it cannot revive it
			// even for a type whose cost path does not short-circuit
			assertNull(skippedBranch.getMemoizedResult());

			assertEquals(0, plan.resultCount());
			assertNotNull(plan.actualCost());
			// the skipped branch reports no outcome at all, which is the honest answer rather than a limitation of
			// the renderer: it belongs to the plan that ran, and still never ran itself
			final FormulaPlan skippedNode = plan.children().get(1);
			assertNull(skippedNode.actualCost());
			assertNull(skippedNode.resultCount());
		}

		@Test
		@DisplayName("should not force a branch whose cost path would compute what its computation skipped")
		void shouldNotForceABranchWhoseCostPathWouldComputeIt() {
			// the case the test above warns about, in a type that really has it: DisentangleFormula's X\X guard
			// returns empty without touching its inner formulas, while its getCostInternal() falls through to
			// AbstractFormula's default - which calls compute() on every one of them. Rendering reads getCost()
			// on any memoized node, so the renderer must not be the thing that triggers that fall-through
			final Formula sharedBranch = new ConstantFormula(new ArrayBitmap(1, 2, 3));
			final Formula root = new DisentangleFormula(sharedBranch, sharedBranch);

			root.compute();

			// the premise: the root ran, and its guard really did skip the branch
			assertNotNull(root.getMemoizedResult());
			assertNull(sharedBranch.getMemoizedResult());

			FormulaPlanVisitor.toPlan(root);

			assertNull(
				sharedBranch.getMemoizedResult(),
				"Rendering forced a branch the computation skipped - the plan is no longer an observation of the query!"
			);
		}

		@Test
		@DisplayName("should report no cost for a node nobody has priced, rather than pricing it")
		void shouldReportNoCostForANodeNobodyHasPriced() {
			// the corollary of the test above, and the reason actualCost and resultCount are read independently:
			// pricing a node is itself work, so a formula that ran but was never priced reports a result count
			// with no cost beside it. That is a third node shape, and it says something different from both
			// "never ran" (no numbers at all) and "ran and cost this much"
			final Formula sharedBranch = new ConstantFormula(new ArrayBitmap(1, 2, 3));
			final Formula root = new DisentangleFormula(sharedBranch, sharedBranch);

			root.compute();

			final FormulaPlan plan = FormulaPlanVisitor.toPlan(root);

			assertEquals(0, plan.resultCount());
			assertNull(plan.actualCost());
			assertNull(sharedBranch.getMemoizedResult());
		}

		@Test
		@DisplayName("should report the costs the engine's own metric pass has already paid for")
		void shouldReportTheCostsTheMetricPassHasAlreadyPaidFor() {
			// QueryPlan.recordQueryMetrics reads the root's cost for the ACTUAL_COST metric before it renders.
			// That is what puts numbers in the plan: the renderer itself never prices anything, it only reports
			// what pricing has already happened. Render before the metric and the plan is numerically emptier
			final Formula root = new AndFormula(
				new ConstantFormula(new ArrayBitmap(1, 2, 3)),
				new ConstantFormula(new ArrayBitmap(2, 3, 4))
			);

			root.compute();
			assertNull(FormulaPlanVisitor.toPlan(root).actualCost());

			// exactly what the engine does, in the order it does it
			root.getCost();

			assertNotNull(FormulaPlanVisitor.toPlan(root).actualCost());
		}
	}

	@Nested
	@DisplayName("DAG identity")
	class DagIdentity {

		@Test
		@DisplayName("should describe a shared sub-formula once and point at it thereafter")
		void shouldDescribeASharedSubFormulaOnceAndPointAtItThereafter() {
			final Formula shared = new ConstantFormula(new ArrayBitmap(1, 2, 3));
			final Formula root = new ScopeContainerFormula(
				Scope.LIVE,
				new ScopeContainerFormula(Scope.LIVE, shared, new ConstantFormula(new ArrayBitmap(2, 3, 4))),
				shared
			);

			final FormulaPlan plan = FormulaPlanVisitor.toPlan(root);

			assertFalse(plan.isReference());
			assertEquals(2, plan.children().size());

			// first occurrence, reached through the nested container, is described in full
			final FormulaPlan firstOccurrence = plan.children().get(0).children().get(0);
			assertFalse(firstOccurrence.isReference());
			assertNotNull(firstOccurrence.description());

			// the second occurrence is the very same object, so it is a bare pointer - describing it again would
			// invite the reader to add its cost in twice, when the engine computes it exactly once
			final FormulaPlan secondOccurrence = plan.children().get(1);
			assertTrue(secondOccurrence.isReference());
			assertEquals(firstOccurrence.id(), secondOccurrence.refTo());
			assertEquals(firstOccurrence.id(), secondOccurrence.id());
			assertTrue(secondOccurrence.children().isEmpty());
			assertNull(secondOccurrence.description());
		}

		@Test
		@DisplayName("should give structurally equal but distinct instances separate identities")
		void shouldGiveStructurallyEqualButDistinctInstancesSeparateIdentities() {
			final Formula root = new ScopeContainerFormula(
				Scope.LIVE,
				new ConstantFormula(new ArrayBitmap(1, 2, 3)),
				new ConstantFormula(new ArrayBitmap(1, 2, 3))
			);

			final FormulaPlan plan = FormulaPlanVisitor.toPlan(root);

			// two separate objects are computed separately, so neither may be collapsed into a reference to the
			// other - identity, not structural equality, is what "computed once" is about
			final FormulaPlan first = plan.children().get(0);
			final FormulaPlan second = plan.children().get(1);
			assertFalse(first.isReference());
			assertFalse(second.isReference());
			assertEquals(first.hash(), second.hash());
		}
	}
}
