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
import io.evitadb.core.query.algebra.facet.FacetHavingFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.filter.AttributeRangeCarrierFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.PriceBetweenFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;

import javax.annotation.Nonnull;

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
	 * @param filterFormula the filter formula produced by the filter-by translation pipeline — typically the output
	 *                      of `ExtraResultPlanningVisitor.getFilteringFormula()` or an already-optimised form
	 * @param group         the {@link RangeCarrierGroup} whose carrier type identifies formulas to strip
	 * @return a formula tree structurally identical to the input except inside each {@link UserFilterFormula}, where
	 * the selected group's carriers have been dropped; {@link EmptyFormula#INSTANCE} as a canonical "whole tree
	 * collapsed under relaxation" sentinel (the filter consisted of nothing but peeled carriers) — **callers must
	 * interpret this sentinel as "no mandatory filter remains" / "all records pass", never as "empty result"**;
	 * genuine empty-result formulas can never surface here because the relaxer only removes nodes, it never synthesises
	 * an empty one
	 */
	@Nonnull
	public static Formula relax(@Nonnull Formula filterFormula, @Nonnull RangeCarrierGroup group) {
		final Class<? extends Formula> carrierType = carrierTypeFor(group);
		final Formula relaxed = FormulaCloner.clone(
			filterFormula,
			(cloner, node) -> {
				if (node instanceof UserFilterFormula) {
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
					// drop the empty userFilter so downstream AND-chains do not short-circuit to empty bitmap —
					// the rebuild collapses to EmptyFormula either directly (all direct children peeled) or
					// propagated upward from a nested container whose children were all peeled (e.g. the filter
					// optimiser wraps sibling `attributeBetween`s in an inner `AndFormula`, which rebuilds to
					// EmptyFormula once both carriers are peeled)
					if (rebuiltUserFilter == null
						|| rebuiltUserFilter.getInnerFormulas().length == 0
						|| containsEmptyFormula(rebuiltUserFilter)) {
						return null;
					}
					return rebuiltUserFilter;
				}
				return node;
			}
		);
		return relaxed == null ? EmptyFormula.INSTANCE : relaxed;
	}

	/**
	 * Returns true when the given subtree contains {@link EmptyFormula#INSTANCE} anywhere — a signal that the
	 * inner relaxation collapsed a nested container (AND / OR / UserFilter) after all its meaningful children were
	 * peeled. Since user queries never emit {@code EmptyFormula} directly, encountering one after relaxation means
	 * the surrounding userFilter semantically became empty and must be dropped.
	 */
	private static boolean containsEmptyFormula(@Nonnull Formula formula) {
		if (formula == EmptyFormula.INSTANCE) {
			return true;
		}
		for (final Formula child : formula.getInnerFormulas()) {
			if (containsEmptyFormula(child)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Resolves the carrier type for the given group. The switch expression is exhaustive — adding a new enum
	 * constant without a new case arm surfaces as a compile-time error.
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
