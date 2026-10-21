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

package io.evitadb.core.query.algebra.facet;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.ChildrenDependentFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.core.query.algebra.NonCollapsibleFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Intersects one owner-locale branch with its facet selection. The branch stays visible when summary counts discard
 * facet selections and when impact projections rebuild them. Its first child is the owner condition, its second
 * child is the facet selection; both remain formula children so hashes and transactional dependencies include them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FacetLocaleFormula extends AbstractFormula
	implements ChildrenDependentFormula, NonCacheableFormula, NonCollapsibleFormula {
	/** Unique formula identifier, generated from the fully qualified class name. */
	private static final long CLASS_ID = -4855853891316253538L;
	/** Reference whose facet selection the owner condition belongs to. */
	@Nonnull private final String referenceName;

	/**
	 * Creates an owner guard around one facet-selection branch.
	 *
	 * @param referenceName  the reference the facet selection belongs to
	 * @param ownerCondition the owners satisfying the branch's locale predicate; the first child, which
	 *                       {@link #retainOwnerConditions} projects
	 * @param facetSelection the owners of the selected facets; the second child, which relaxation may strip
	 */
	public FacetLocaleFormula(
		@Nonnull String referenceName, @Nonnull Formula ownerCondition, @Nonnull Formula facetSelection
	) {
		this.referenceName = referenceName;
		this.initFields(ownerCondition, facetSelection);
	}

	/**
	 * Returns true if the formula holds an owner guard of any reference. A facet summary decides this once for its
	 * base formula, so that a query without a localized facet filter projects no owner condition per facet group.
	 *
	 * @param formula the formula to search
	 * @return true if the formula holds a {@link FacetLocaleFormula}
	 */
	public static boolean containsOwnerGuard(@Nonnull Formula formula) {
		return !FormulaFinder.find(formula, FacetLocaleFormula.class, LookUp.DEEP).isEmpty();
	}

	/**
	 * Projects the owner guards of one reference that sit in the user filter, preserving their boolean placement, so
	 * that facet summary counts, which discard the rest of the user filter, still restrict the owners the way the
	 * localized selection does. A guard outside the user filter belongs to the mandatory filter, which the counts apply
	 * whole, and a guard of another reference is ignored. Every other predicate is removed from the projection; only
	 * a disjunct selecting facets of the same reference without a guard voids it, because such a disjunction admits
	 * owners in any locale.
	 *
	 * @param formula       the formula to project - a whole filter formula, or a user filter formula itself
	 * @param referenceName the reference whose guards are projected
	 * @param superSet      the owners a negation subtracts from when its superset side holds no guard
	 * @return the projected owner condition, NULL when it restricts nothing
	 */
	@Nullable
	public static Formula retainOwnerConditions(
		@Nonnull Formula formula, @Nonnull String referenceName, @Nonnull Formula superSet
	) {
		for (final FacetLocaleFormula branch : FormulaFinder.find(formula, FacetLocaleFormula.class, LookUp.DEEP)) {
			if (referenceName.equals(branch.referenceName)) {
				return projectOwnerConditions(formula, referenceName, superSet, formula instanceof UserFilterFormula);
			}
		}
		return null;
	}

	/**
	 * Projects the guards after their presence has been established. A scope without a guard contributes its whole
	 * scope to an enclosing disjunction, so a locale confined to another scope does not become a query-wide condition.
	 */
	@Nullable
	private static Formula projectOwnerConditions(
		@Nonnull Formula formula, @Nonnull String referenceName, @Nonnull Formula superSet, boolean inUserFilter
	) {
		if (formula instanceof FacetLocaleFormula localeFormula) {
			// a guard outside the user filter belongs to the mandatory filter, which the statistics apply whole
			return inUserFilter && referenceName.equals(localeFormula.referenceName) ?
				localeFormula.innerFormulas[0] : null;
		}
		final boolean childrenInUserFilter = inUserFilter || formula instanceof UserFilterFormula;
		if (formula instanceof NotFormula notFormula) {
			final Formula subtracted = projectOwnerConditions(
				notFormula.getSubtractedFormula(), referenceName, superSet, childrenInUserFilter
			);
			final Formula retained = projectOwnerConditions(
				notFormula.getSupersetFormula(), referenceName, superSet, childrenInUserFilter
			);
			return subtracted == null ?
				retained : FormulaFactory.not(subtracted, retained == null ? superSet : retained);
		}
		final List<Formula> children = new ArrayList<>();
		for (final Formula child : formula.getInnerFormulas()) {
			final Formula retained = projectOwnerConditions(child, referenceName, superSet, childrenInUserFilter);
			if (retained != null) {
				children.add(retained);
			} else if (formula instanceof OrFormula && selectsFacetsOf(child, referenceName)) {
				// an unguarded selection of the same reference admits owners in any locale; any other disjunct is
				// discarded like the rest of the user filter
				return null;
			}
		}
		if (formula instanceof ScopeContainerFormula scopeFormula) {
			final Formula scopeUniverse = scopeFormula.getCloneWithInnerFormulas();
			if (children.isEmpty()) {
				return scopeUniverse;
			}
			final Formula condition = FormulaFactory.and(children.toArray(Formula[]::new));
			return scopeUniverse == EmptyFormula.INSTANCE ? scopeFormula.getCloneWithInnerFormulas(condition) :
				ScopeContainerFormula.restrictingScope(scopeFormula.getScope(), scopeUniverse, condition);
		}
		if (children.isEmpty()) {
			return null;
		}
		final Formula[] projected = children.toArray(Formula[]::new);
		return formula instanceof OrFormula ? FormulaFactory.or(projected) : FormulaFactory.and(projected);
	}

	/**
	 * Returns true if the formula selects facets of the reference. Inside the user filter a guarded selection projects
	 * its guard, so a disjunct projecting none can hold an unguarded selection only.
	 *
	 * @param formula       the disjunct whose projection holds no guard
	 * @param referenceName the reference whose guards are projected
	 * @return true if the formula holds a facet selection of the reference
	 */
	private static boolean selectsFacetsOf(@Nonnull Formula formula, @Nonnull String referenceName) {
		for (final FacetHavingFormula selection : FormulaFinder.find(formula, FacetHavingFormula.class, LookUp.DEEP)) {
			if (referenceName.equals(selection.getReferenceName())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Preserves both children during ordinary cloning. Relaxation may remove the facet-selection carrier, leaving
	 * the owner condition alone; stripping both children follows the empty-clone convention of boolean formulas.
	 */
	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		if (innerFormulas.length == 0) {
			return EmptyFormula.INSTANCE;
		} else if (innerFormulas.length == 1) {
			return innerFormulas[0];
		}
		Assert.isPremiseValid(
			innerFormulas.length == 2, "A facet locale branch requires an owner guard and a selection!"
		);
		return new FacetLocaleFormula(this.referenceName, innerFormulas[0], innerFormulas[1]);
	}

	/** Returns the cost of intersecting the two child bitmaps. */
	@Override
	public long getOperationCost() {
		return 9;
	}

	/** Estimates the intersection by the smaller child. */
	@Override
	public int getEstimatedCardinality() {
		return Math.min(
			this.innerFormulas[0].getEstimatedCardinality(), this.innerFormulas[1].getEstimatedCardinality()
		);
	}

	/** Restricts the selected facets to owners satisfying this branch's locale predicate. */
	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return new BaseBitmap(PersistentRoaringBitmap.and(
			RoaringBitmapBackedBitmap.getRoaringBitmap(this.innerFormulas[0].compute()),
			RoaringBitmapBackedBitmap.getRoaringBitmap(this.innerFormulas[1].compute())
		));
	}

	/** Includes the reference identity as well as the child formulas in the hash. */
	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return CLASS_ID + 31 * hashFunction.hashChars(this.referenceName);
	}

	/** Returns the generated class identifier. */
	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	/** Names the owner guard in query telemetry. */
	@Nonnull
	@Override
	public String toString() {
		return "FACET OWNER LOCALE";
	}
}
