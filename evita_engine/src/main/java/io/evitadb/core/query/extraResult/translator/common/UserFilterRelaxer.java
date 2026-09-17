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

package io.evitadb.core.query.extraResult.translator.common;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.FacetHavingFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.filter.AttributeRangeCarrierFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.PriceBetweenFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;

import javax.annotation.Nonnull;

import static io.evitadb.core.query.filter.FilterByVisitor.isConjunctiveFormula;
import java.util.Optional;

/**
 * Shared helper used by the three cross-influencing projections (attribute-family histograms, facet impact, price
 * histogram) to rebuild the query's filter formula with **their own group's range carriers stripped** from every
 * {@link UserFilterFormula} inside the tree. The other two groups' carriers stay applied — this is what makes the
 * three sliders / facet impact mutually visible instead of contracting under their own handles (see
 * {@link RangeCarrierGroup} for the full rationale).
 *
 * Each group resolves to a single carrier type: {@link AttributeRangeCarrierFormula} (interface, two implementers),
 * {@link FacetHavingFormula} (concrete class), or {@link PriceBetweenFormula} (concrete class). The group check is
 * always `carrierType.isInstance(probe)`.
 *
 * {@link SelectionFormula} wrapping from the prefetch optimisation is unwrapped transparently so that prefetch-wrapped
 * carriers are still peeled.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class UserFilterRelaxer {

	/**
	 * Not instantiable — the helper exposes only the static {@link #relax(Formula, RangeCarrierGroup)} entry point.
	 */
	private UserFilterRelaxer() {
	}

	/**
	 * Clones the given filter formula tree and rebuilds every encountered {@link UserFilterFormula} with the given
	 * group's carriers stripped. Non-matching children, the surrounding structure outside any user filter, and all
	 * memoized sub-results are preserved intact.
	 *
	 * The return type separates two outcomes that used to share the single value `EmptyFormula.INSTANCE`:
	 *
	 * - {@link Optional#empty()} - relaxation removed **everything**: the tree consisted of nothing but this group's
	 *   carriers, so no mandatory filter remains and every record passes. Callers map this to the catalog-wide
	 *   baseline.
	 * - a **present** value - a real formula, which may itself be or contain {@link EmptyFormula#INSTANCE}. That
	 *   means the **opposite**: the filter is unsatisfiable and matches nothing.
	 *
	 * Both states are reachable, and conflating them was a wrong-answer defect. The second one used to be
	 * unreachable here: the relaxer only ever removes nodes, so it cannot invent an emptiness of its own, and a
	 * filter that was already unsatisfiable was resolved during execution rather than folded during planning. Once
	 * `AttributeIsTranslator#subtractionMayYieldRecords` began folding provably-empty `attributeIs(NULL)`
	 * subtractions at planning time, `EmptyFormula.INSTANCE` became a routine *input* - and reading it as "all
	 * records pass" reported the whole catalog's histogram for a query that returned no records at all.
	 *
	 * @param filterFormula the filter formula produced by the filter-by translation pipeline - typically the output
	 *                      of `ExtraResultPlanningVisitor.getFilteringFormula()` or an already-optimised form
	 * @param group         the {@link RangeCarrierGroup} whose carrier type identifies formulas to strip
	 * @return {@link Optional#empty()} when the whole tree collapsed under relaxation because it held nothing but
	 * this group's carriers; otherwise the relaxed tree, structurally identical to the input except inside each
	 * {@link UserFilterFormula}
	 */
	@Nonnull
	public static Optional<Formula> relax(@Nonnull Formula filterFormula, @Nonnull RangeCarrierGroup group) {
		final Class<? extends Formula> carrierType = carrierTypeFor(group);
		final Formula relaxed = FormulaCloner.clone(
			filterFormula,
			(cloner, node) -> {
				if (node instanceof UserFilterFormula) {
					// A userFilter that is ALREADY unsatisfiable on arrival stays exactly as it is: peeling
					// cannot rescue it, and dropping it would be read upstream as "nothing constrains the result",
					// widening the baseline to the whole catalog - the precise inversion this guard prevents.
					//
					// The test must be SCOPE-AWARE, and an earlier version of this fix was not.
					// `FormulaOptimizer:255-264` returns an `OrFormula` untouched - dead child included - as soon
					// as two of its alternatives are non-empty, so an `EmptyFormula` really does survive inside a
					// live disjunction, where it is the identity element and constrains nothing. A plain "is there
					// one anywhere below" walk therefore declares `userFilter(or(slider, liveAlternative,
					// EmptyFormula))` unsatisfiable, skips relaxation entirely, and lets the user's own slider
					// contract the histogram it exists to span.
					if (isProvablyEmpty(node)) {
						return node;
					}
					final Formula rebuiltUserFilter = FormulaCloner.clone(
						node,
						innerFormula -> {
							final Formula probe;
							if (innerFormula instanceof SelectionFormula selection) {
								probe = selection.getDelegate();
							} else {
								probe = innerFormula;
							}
							return carrierType.isInstance(probe) ? null : innerFormula;
						}
					);
					// drop the emptied userFilter so downstream AND-chains do not short-circuit to an empty
					// bitmap. The scope-aware test is required here too: peeling the carrier out of
					// `or(carrier, liveAlternative, EmptyFormula)` leaves a disjunction that still holds the dead
					// child, and a structural walk would drop a userFilter `liveAlternative` still constrains.
					if (rebuiltUserFilter == null
						|| rebuiltUserFilter.getInnerFormulas().length == 0
						|| isProvablyEmpty(rebuiltUserFilter)) {
						return null;
					}
					return rebuiltUserFilter;
				}
				return node;
			}
		);
		return Optional.ofNullable(relaxed);
	}

	/**
	 * Returns true when the subtree can be shown, from its structure alone, to match nothing.
	 *
	 * The distinction that matters is **scope**, and getting it wrong breaks this class in opposite directions. An
	 * `EmptyFormula` in a *conjunctive* position empties everything around it. In a *disjunctive* one it is the
	 * identity element and constrains nothing - and it genuinely survives there, because
	 * `FormulaOptimizer:255-264` returns an `OrFormula` untouched as soon as two of its alternatives are non-empty.
	 *
	 * The two mistakes this method exists to avoid:
	 *
	 * - reading a *disjunctive* `EmptyFormula` as emptiness skips relaxation for a satisfiable userFilter, so the
	 *   user's own slider contracts the histogram it is supposed to span - the self-contraction this whole class
	 *   exists to prevent;
	 * - failing to see a *conjunctive* one lets the "everything was peeled" signal stand for "the filter matches
	 *   nothing", and the baseline widens to the entire catalog for a query returning no records.
	 *
	 * Anything that is neither a disjunction nor a recognised conjunction answers `false`. {@link
	 * io.evitadb.core.query.algebra.base.NotFormula} is the reason to be careful: `NOT(empty)` is the *superset*,
	 * the exact opposite of empty.
	 *
	 * **The two error directions are not symmetric, and that is what licenses the conservative default.** Every
	 * caller re-checks the returned formula semantically - `hasSenseWithMandatoryFilter` computes
	 * `AndFormula(histogramBitmaps, filteringFormula).compute().isEmpty()`, and the histogram computers compute the
	 * baseline outright. So UNDER-detecting (failing to prove an emptiness that is really there) costs at most a
	 * peel attempt that changes nothing, because the real computation downstream still finds it. OVER-detecting is
	 * the only direction that produces a wrong answer: it skips relaxation for a satisfiable userFilter and lets
	 * the user's own slider contract the histogram. When in doubt, answer `false`.
	 *
	 * One known under-detection, left as is for that reason: {@link AndFormula} and {@link OrFormula} each have a
	 * bitmap-only constructor that leaves {@link Formula#getInnerFormulas()} empty while `computeInternal()` works
	 * from raw bitmaps. The `children.length == 0` early return therefore answers `false` for such a node whatever
	 * its bitmaps hold - under-detection, hence harmless.
	 *
	 * @param formula the subtree to judge
	 * @return TRUE only when the structure proves the subtree matches nothing
	 */
	private static boolean isProvablyEmpty(@Nonnull Formula formula) {
		if (formula == EmptyFormula.INSTANCE) {
			return true;
		}
		final Formula[] children = formula.getInnerFormulas();
		if (children.length == 0) {
			return false;
		}
		if (formula instanceof OrFormula) {
			// a disjunction matches nothing only when EVERY alternative does
			for (final Formula child : children) {
				if (!isProvablyEmpty(child)) {
					return false;
				}
			}
			return true;
		}
		if (isConjunctiveFormula(formula.getClass())) {
			// one empty conjunct empties the whole conjunction, however many siblings it has
			for (final Formula child : children) {
				if (isProvablyEmpty(child)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Resolves the carrier type for the given group. The switch expression is exhaustive — adding a new enum
	 * constant without a new case arm surfaces as a compile-time error.
	 *
	 * **Why `ATTRIBUTE_HISTOGRAM` peels only `attributeBetween` and not `attributeLessThanEquals` and friends.**
	 * The carrier set models the UI control, not the constraint's arithmetic. A histogram slider defines BOTH
	 * bounds, so `attributeBetween` - and only it - is the shape that represents one, and it is peeled so the slider
	 * cannot contract the very span it is drawn on. A one-sided comparison is an ordinary filter rather than a
	 * slider, so it correctly narrows the histogram like any other constraint. Measured, in case it reads as a gap:
	 * `userFilter(attributeLessThanEquals(x, 4))` reports only the buckets at or below 4, every one of them flagged
	 * `requested`. That is the intended answer, not a missing carrier type.
	 */
	@Nonnull
	private static Class<? extends Formula> carrierTypeFor(@Nonnull RangeCarrierGroup group) {
		return switch (group) {
			case ATTRIBUTE_HISTOGRAM -> AttributeRangeCarrierFormula.class;
			case FACET_IMPACT -> FacetHavingFormula.class;
			case PRICE_HISTOGRAM -> PriceBetweenFormula.class;
		};
	}

}
