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
import io.evitadb.core.query.algebra.FormulaVisitor;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Renders a {@link Formula} tree into the structured {@link FormulaPlan} published through query telemetry.
 *
 * It is the structured counterpart of {@link PrettyPrintingFormulaVisitor}, and differs from it in the two ways
 * that matter to a client:
 *
 * 1. **It computes nothing, by construction.** `PrettyPrintingFormulaVisitor` calls {@link Formula#compute()}
 *    unconditionally for every formula with inner formulas - acceptable for a debugging `toString()`, unacceptable
 *    here. The planner builds one formula per candidate index and computes only the winner, so rendering a rejected
 *    alternative through a forcing renderer would execute a plan the engine had deliberately decided not to run:
 *    telemetry would stop observing the query and start changing it. This visitor therefore reads **only** the
 *    free-of-charge accessors - {@link Formula#getMemoizedResult()} and {@link Formula#getMemoizedCost()} - and
 *    reports "not computed" as `null` where the other prints `?`. Note that {@link Formula#getCost()} is *not*
 *    one of them and must never be used here: on a memoized node whose cost is still unpriced it falls through to
 *    a cost path that computes inner formulas, including ones the query itself skipped.
 * 2. **Instance identity is a field, not ASCII art.** Where the pretty printer emits `[Ref to #3]` inside a string
 *    and then re-descends into the repeated subtree, this emits a childless node carrying
 *    {@link FormulaPlan#refTo()} and stops. A consumer draws the link; nobody has to regex it back out, and the
 *    shared subtree is described exactly once.
 *
 * Instances are single use - call {@link #toPlan(Formula)} rather than reusing a visitor, since the identity map is
 * what makes the ids meaningful and it is scoped to one rendering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class FormulaPlanVisitor implements FormulaVisitor {

	/**
	 * Ids already handed out, keyed by formula **instance**. It is an identity map on purpose: two structurally
	 * equal formulas that are different objects are computed separately and must not be collapsed, which is
	 * precisely the distinction {@link FormulaPlan#hash()} does not make.
	 */
	private final IdentityHashMap<Formula, Integer> instanceIds = new IdentityHashMap<>();
	/**
	 * Children lists of the nodes currently being built, innermost on top. A node is only complete once its whole
	 * subtree has been visited, so each level parks its accumulating child list here while it descends.
	 */
	private final Deque<List<FormulaPlan>> childrenStack = new ArrayDeque<>();
	/**
	 * The finished root, assigned when the one node that was emitted with an empty stack completes.
	 */
	@Nullable private FormulaPlan result;

	/**
	 * Renders the passed formula tree into its structured plan, computing nothing.
	 *
	 * @param formula root of the formula tree to describe
	 * @return the plan, whose root corresponds to `formula`
	 */
	@Nonnull
	public static FormulaPlan toPlan(@Nonnull Formula formula) {
		final FormulaPlanVisitor visitor = new FormulaPlanVisitor();
		formula.accept(visitor);
		final FormulaPlan result = visitor.result;
		if (result == null) {
			throw new GenericEvitaInternalError(
				"Formula plan rendering produced no root node - the formula did not accept the visitor."
			);
		}
		return result;
	}

	@Override
	public void visit(@Nonnull Formula formula) {
		final Integer alreadySeenId = this.instanceIds.get(formula);
		if (alreadySeenId != null) {
			// a repeated instance is the same object and will not be computed again, so it is emitted as a bare
			// pointer - descending into it once more would triple-count a shared subtree in the reader's head
			emit(
				new FormulaPlan(
					alreadySeenId, alreadySeenId, formula.getHash(),
					null, formula.getEstimatedCost(), null, null, List.of()
				)
			);
			return;
		}

		final int id = this.instanceIds.size();
		this.instanceIds.put(formula, id);

		final Formula[] innerFormulas = formula.getInnerFormulas();
		final List<FormulaPlan> children = new ArrayList<>(innerFormulas.length);
		this.childrenStack.push(children);
		for (final Formula innerFormula : innerFormulas) {
			innerFormula.accept(this);
		}
		this.childrenStack.pop();

		// both outcome numbers are read through the free-of-charge accessors, and neither may trigger a computation.
		// getCost() would: on a node that is memoized but whose cost nobody has asked for yet, it falls through to
		// AbstractFormula#getCostInternal(), which calls compute() on every inner formula - including ones this
		// formula's own computation skipped (DisentangleFormula's X\X guard is exactly that shape). The two reads
		// are independent, so a node can legitimately report a result count with no cost beside it: that says the
		// formula ran but nothing has priced it, which is a different statement from "it never ran"
		final Bitmap memoizedResult = formula.getMemoizedResult();
		emit(
			new FormulaPlan(
				id, null, formula.getHash(),
				formula.toString(),
				formula.getEstimatedCost(),
				formula.getMemoizedCost(),
				memoizedResult == null ? null : memoizedResult.size(),
				children
			)
		);
	}

	/**
	 * Attaches a finished node to its parent, or records it as the root when there is no parent.
	 *
	 * @param node the completed node
	 */
	private void emit(@Nonnull FormulaPlan node) {
		final List<FormulaPlan> parentChildren = this.childrenStack.peek();
		if (parentChildren == null) {
			this.result = node;
		} else {
			parentChildren.add(node);
		}
	}
}
