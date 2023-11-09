/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.extraResult.translator.facet.producer;

import io.evitadb.api.query.require.FacetGroupsConjunction;
import io.evitadb.api.query.require.FacetGroupsDisjunction;
import io.evitadb.api.query.require.FacetGroupsNegation;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.QueryPlanner.FutureNotFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaVisitor;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.CombinedFacetFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupAndFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupOrFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.core.query.filter.translator.facet.FacetHavingTranslator;
import io.evitadb.index.array.CompositeObjectArray;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * Abstract ancestor for {@link FacetCalculator} and {@link ImpactFormulaGenerator} that captures the shared logic
 * between both of them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public abstract class AbstractFacetFormulaGenerator implements FormulaVisitor {
	/**
	 * Predicate returns TRUE when facet covered by {@link FacetGroupsConjunction} require query in
	 * input {@link EvitaRequest}.
	 */
	@Nonnull
	protected final BiPredicate<ReferenceSchemaContract, Integer> isFacetGroupConjunction;
	/**
	 * Predicate returns TRUE when facet covered by {@link FacetGroupsDisjunction} require query in
	 * input {@link EvitaRequest}.
	 */
	@Nonnull
	protected final BiPredicate<ReferenceSchemaContract, Integer> isFacetGroupDisjunction;
	/**
	 * Predicate returns TRUE when facet covered by {@link FacetGroupsNegation} require query in
	 * input {@link EvitaRequest}.
	 */
	@Nonnull
	protected final BiPredicate<ReferenceSchemaContract, Integer> isFacetGroupNegation;
	/**
	 * Stack serves internally to collect the cloned tree of formulas.
	 */
	protected final Deque<CompositeObjectArray<Formula>> levelStack = new LinkedList<>();
	/**
	 * Contains filtering formula that has been stripped of user-defined filter.
	 */
	protected Formula baseFormulaWithoutUserFilter;
	/**
	 * Contains {@link ReferenceSchema} of the facet entity.
	 */
	protected ReferenceSchemaContract referenceSchema;
	/**
	 * Contains primary key of the facet that is being computed.
	 */
	protected int facetId;
	/**
	 * Contains id of the group the {@link #facetId} is part of.
	 */
	@Nullable
	protected Integer facetGroupId;
	/**
	 * Contains bitmaps of all entity primary keys that posses facet of {@link #facetId} taken from
	 * the {@link FacetIndex}.
	 */
	protected Bitmap facetEntityIds;
	/**
	 * Contains true if visitor is currently within the scope of {@link NotFormula}.
	 */
	private final Deque<Boolean> insideNotContainer = new LinkedList<>();
	/**
	 * Contains true if visitor is currently within the scope of {@link UserFilterFormula}.
	 */
	private final Deque<Boolean> insideUserFilter = new LinkedList<>();
	/**
	 * Contains deferred lambda function that should be applied at the moment {@link NotFormula} processing is finished
	 * by this visitor. This postponed mutator solves the situation when the facet formula needs to be applied above
	 * NOT container and not within it. This is related to the internal mechanisms of {@link FutureNotFormula}
	 * propagation and {@link FacetHavingTranslator} facet formula composition.
	 */
	protected BiFunction<Formula, Formula[], Formula> deferredMutator;
	/**
	 * Result optimized form of formula.
	 */
	@Getter protected Formula result;

	public Formula generateFormula(
		@Nonnull Formula baseFormula,
		@Nonnull Formula baseFormulaWithoutUserFilter,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer facetGroupId,
		int facetId,
		@Nonnull Bitmap[] facetEntityIds
	) {
		try {
			// initialize global variables for this execution
			this.result = null;
			this.baseFormulaWithoutUserFilter = baseFormulaWithoutUserFilter;
			this.referenceSchema = referenceSchema;
			this.facetId = facetId;
			this.facetGroupId = facetGroupId;

			// facets from multiple indexes are always joined with OR
			if (facetEntityIds.length == 0) {
				this.facetEntityIds = EmptyBitmap.INSTANCE;
			} else if (facetEntityIds.length == 1) {
				this.facetEntityIds = facetEntityIds[0];
			} else {
				this.facetEntityIds = new BaseBitmap(
					RoaringBitmap.or(
						Arrays.stream(facetEntityIds)
							.map(RoaringBitmapBackedBitmap::getRoaringBitmap)
							.toArray(RoaringBitmap[]::new)
					)
				);
			}
			// now compute the formula
			baseFormula.accept(this);
			// and return computation result
			return getResult(baseFormula);
		} finally {
			// finally, clear all internal global variables in a safe manner
			this.referenceSchema = null;
			this.baseFormulaWithoutUserFilter = null;
			this.facetId = -1;
			this.facetGroupId = null;
			this.facetEntityIds = null;
			this.result = null;
			this.deferredMutator = null;
			this.insideNotContainer.clear();
			this.insideUserFilter.clear();
		}
	}

	@Override
	public void visit(Formula formula) {
		// evaluate and set flag that signalizes visitor is within UserFilterFormula scope
		boolean isUserFilter = formula instanceof UserFilterFormula;
		if (isUserFilter) {
			insideUserFilter.push(true);
		}
		// evaluate and set flag that signalizes visitor is within NotFormula scope
		boolean isNotContainer = formula instanceof NotFormula;
		if (isNotContainer) {
			insideNotContainer.push(true);
		}
		// now iterate and copy children
		final Formula[] updatedChildren;
		levelStack.push(new CompositeObjectArray<>(Formula.class));
		try {
			// but only if implementation says so - FacetCalculator omits UserFilter contents
			if (shouldIncludeChildren(isUserFilter)) {
				for (Formula innerFormula : formula.getInnerFormulas()) {
					innerFormula.accept(this);
				}
			}
		} finally {
			updatedChildren = levelStack.pop().toArray();
		}
		// if we're leaving UserFilterFormula scope
		if (isUserFilter) {
			// reset inside user filter flag
			insideUserFilter.pop();
			// apply respective modifications
			if (handleUserFilter(formula, updatedChildren)) {
				// if the user filter has been handled skip early - we don't need another storeFormula call
				return;
			}
		}
		// if we're leaving NotFormula scope
		if (isInsideNotContainer() && isNotContainer) {
			// reset not container flag
			insideNotContainer.pop();
			// if the logic instantiated deferred mutator - now it's time to apply it
			if (deferredMutator != null) {
				storeFormula(
					deferredMutator.apply(formula, updatedChildren)
				);
				// if the user filter has been handled skip early - we don't need another storeFormula call
				return;
			}
		}

		// allow descendants to react to current formula
		if (handleFormula(formula)) {
			// if it has been handled skip early - we don't need another storeFormula call
			return;
		}

		// if the children were really changed
		if (isAnyChildrenExchanged(formula, updatedChildren)) {
			// store clone of the current formula
			storeFormula(
				formula.getCloneWithInnerFormulas(updatedChildren)
			);
		} else {
			// reuse original formula
			storeFormula(formula);
		}
	}

	/**
	 * Returns true if currently examined constraint is placed within NOT container (may not be placed directly in it).
	 */
	protected boolean isInsideNotContainer() {
		return !insideNotContainer.isEmpty() && insideNotContainer.peek();
	}

	/**
	 * Returns true if currently examined constraint is placed within user filter container (may not be placed directly in it).
	 */
	protected boolean isInsideUserFilter() {
		return !insideUserFilter.isEmpty() && insideUserFilter.peek();
	}

	/**
	 * Method allows reacting to currently processed formula.
	 */
	protected boolean handleFormula(@Nonnull Formula formula) {
		return false;
	}

	/**
	 * Method allows to respond to leaving {@link UserFilterFormula} scope.
	 */
	protected boolean handleUserFilter(@Nonnull Formula formula, @Nonnull Formula[] updatedChildren) {
		// should we treat passed facet as negated one?
		final boolean isNewFacetNegation = isFacetGroupNegation.test(referenceSchema, facetGroupId);
		// should we treat passed facet as a part of disjuncted group formula?
		final boolean isNewFacetDisjunction = isFacetGroupDisjunction.test(referenceSchema, facetGroupId);
		// create facet group formula
		final Formula newFormula = createNewFacetGroupFormula();
		// if we're inside NotFormula
		if (isInsideNotContainer()) {
			// and the facet is also negated
			if (isNewFacetNegation) {
				// we need to defer the mutation to the moment when we leave not container and subtract
				deferredMutator = (laterEncounteredFormula, laterEncounteredChildren) -> {
					// and we need to create facet conjunction with base formula without user filter
					final Formula facetConjunction = FormulaFactory.and(
						newFormula,
						baseFormulaWithoutUserFilter
					);
					if (facetConjunction.compute().isEmpty()) {
						// and if product is empty - return empty formula (no entity matches negation of this facet)
						return EmptyFormula.INSTANCE;
					} else {
						// and return the original negation
						return laterEncounteredFormula.getCloneWithInnerFormulas(
							laterEncounteredChildren[0], // subtracted part is untouched
							FormulaFactory.not(
								facetConjunction,
								baseFormulaWithoutUserFilter
							) // but we subtract the conjunction of new formula and base formula instead of superset
						);
					}
				};
				return false;
			} else {
				// we need to defer the mutation to the moment when we leave not container and add the facet formula
				// to the "positive" (superset) part of the not container
				deferredMutator = (laterEncounteredFormula, laterEncounteredChildren) -> {
					final Formula replacedNotContainer = laterEncounteredFormula.getCloneWithInnerFormulas(
						laterEncounteredChildren[0], // subtracted part is untouched
						newFormula // but we add new facet formula as its superset
					);
					// and now combine it all with original superset in and container
					return FormulaFactory.and(
						laterEncounteredChildren[1], // original superset
						replacedNotContainer // altered not container
					);
				};
				return false;
			}
		} else {
			// we can immediately alter the current formula adding new facet formula
			storeFormula(
				formula.getCloneWithInnerFormulas(
					alterFormula(newFormula, isNewFacetDisjunction, isNewFacetNegation, updatedChildren)
				)
			);
			// we've stored the formula - instruct super method to skip it's handling
			return true;
		}
	}

	/**
	 * Method allows instructing code to skip iterating and including children formulas to the output formula.
	 */
	protected boolean shouldIncludeChildren(boolean isUserFilter) {
		// by default, we include children
		return true;
	}

	/**
	 * Method allows to alter the result before it is returned to the caller.
	 */
	protected Formula getResult(@Nonnull Formula baseFormula) {
		// simply return the result
		return result;
	}

	/**
	 * Method creates new {@link Formula} instance that corresponds with requested
	 * {@link FacetGroupsConjunction} requirement in input {@link EvitaRequest}.
	 */
	@Nonnull
	protected FacetGroupFormula createNewFacetGroupFormula() {
		return isFacetGroupConjunction.test(referenceSchema, facetGroupId) ?
			new FacetGroupAndFormula(referenceSchema.getName(), facetGroupId, new BaseBitmap(facetId), facetEntityIds) :
			new FacetGroupOrFormula(referenceSchema.getName(), facetGroupId, new BaseBitmap(facetId), facetEntityIds);
	}

	/**
	 * Method contains the logic that adds brand new {@link Formula} to the examined formula tree. It has
	 * to take group relation requested by {@link FacetGroupsDisjunction} and {@link FacetGroupsNegation} into
	 * an account.
	 */
	@Nonnull
	protected static Formula[] alterFormula(@Nonnull Formula newFormula, boolean disjunction, boolean negation, @Nonnull Formula... children) {
		// if newly added formula should represent OR join
		if (disjunction) {
			return addNewFormulaAsDisjunction(newFormula, children);
		} else if (negation) {
			return addNewFormulaAsNegation(newFormula, children);
		} else {
			return addNewFormulaAsConjunction(newFormula, children);
		}
	}

	/**
	 * Method adds `newFormula` to existing disjunction or creates new one. The method logic must cope with different
	 * source formula composition. We know that parent is {@link UserFilterFormula} that represents implicit AND. But
	 * we need to attach `newFormula` with OR.
	 *
	 * There might be following compositions:
	 *
	 * 1. no OR container is present
	 *
	 * USER FILTER
	 *   FACET PARAMETER OR (zero or multiple formulas)
	 *
	 * that will be transformed to:
	 *
	 * USER FILTER
	 *   OR
	 * 	    AND
	 * 	       FACET PARAMETER OR (zero or multiple original formulas)
	 * 	    FACET PARAMETER OR (newFormula)
	 *
	 * 2. existing OR container is present
	 *
	 * USER FILTER
	 *   OR
	 *      FACET PARAMETER OR (zero or multiple formulas)
	 *
	 * that will be transformed to:
	 *
	 * USER FILTER
	 *   OR
	 * 	    FACET PARAMETER OR (zero or multiple original formulas)
	 * 	    FACET PARAMETER OR (newFormula)
	 *
	 * 3. user filter wth combined facet relations
	 *
	 * USER FILTER
	 *    COMBINED AND+OR
	 *       FACET PARAMETER OR (one or multiple original formulas) - AND relation
	 *       FACET PARAMETER OR (one or multiple original formulas) - OR relation
	 *
	 * that will be transformed to:
	 *
	 * USER FILTER
	 *    COMBINED AND+OR
	 *       FACET PARAMETER OR (one or multiple original formulas) - AND relation
	 *       FACET PARAMETER OR (one or multiple original formulas) - OR relation + newFormula
	 *
	 * This method also needs to cope with complicated compositions in case multiple source indexes are used - in such
	 * occasion the above-mentioned composition is nested within OR containers that combine results from multiple
	 * source indexes. That's why we use {@link FormulaCloner} internally that traverses the entire `children` structure.
	 */
	@Nonnull
	private static Formula[] addNewFormulaAsDisjunction(@Nonnull Formula newFormula, @Nonnull Formula[] children) {
		// iterate over existing children
		final AtomicBoolean childrenAltered = new AtomicBoolean();
		for (int i = 0; i < children.length; i++) {
			final Formula mutatedChild = FormulaCloner.clone(children[i], examinedFormula -> {
				// and if existing OR formula is found
				if (examinedFormula instanceof OrFormula) {
					// simply add new facet group formula to the OR formula
					return examinedFormula.getCloneWithInnerFormulas(
						ArrayUtils.insertRecordIntoArray(
							newFormula, examinedFormula.getInnerFormulas(), examinedFormula.getInnerFormulas().length
						)
					);
				} else if (examinedFormula instanceof final CombinedFacetFormula combinedFacetFormula) {
					// if combined facet formula is found - we know there is combination of AND and OR formulas inside
					// take the OR part of the combined formula
					final Formula orFormula = combinedFacetFormula.getOrFormula();
					// and replace combined formula with AND part untouched and OR part enriched with new facet formula
					return examinedFormula.getCloneWithInnerFormulas(
						combinedFacetFormula.getAndFormula(),
						FormulaFactory.or(
							newFormula,
							orFormula
						)
					);
				} else {
					return examinedFormula;
				}
			});

			if (mutatedChild != children[i]) {
				children[i] = mutatedChild;
				childrenAltered.set(true);
			}
		}
		if (childrenAltered.get()) {
			// return the updated array of children
			return children;
		} else {
			// neither OR or combined formula found in children - create new OR wrapping formula and
			// combine existing children with new facet formula
			return new Formula[]{
				FormulaFactory.or(
					FormulaFactory.and(children),
					newFormula
				)
			};
		}
	}

	/**
	 * Method adds `newFormula` to existing conjunction or creates new one. The method logic must cope with different
	 * source formula composition. We know that parent is {@link UserFilterFormula} that represents implicit AND and we
	 * need to append `newFormula` with the same relation type (but on the proper place).
	 *
	 * There might be following compositions:
	 *
	 * 1. no AND container is present
	 *
	 * USER FILTER
	 *   FACET PARAMETER OR (zero or multiple formulas)
	 *
	 * that will be transformed to:
	 *
	 * USER FILTER
	 * 	 AND
	 * 	    FACET PARAMETER OR (zero or multiple original formulas)
	 * 	    FACET PARAMETER OR (newFormula)
	 *
	 * 2. existing AND container is present
	 *
	 * USER FILTER
	 *   AND
	 *      FACET PARAMETER OR (zero or multiple formulas)
	 *
	 * that will be transformed to:
	 *
	 * USER FILTER
	 *   AND
	 * 	    FACET PARAMETER OR (zero or multiple original formulas)
	 * 	    FACET PARAMETER OR (newFormula)
	 *
	 * 3. user filter wth combined facet relations
	 *
	 * USER FILTER
	 *    COMBINED AND+OR
	 *       FACET PARAMETER OR (one or multiple original formulas) - AND relation
	 *       FACET PARAMETER OR (one or multiple original formulas) - OR relation
	 *
	 * that will be transformed to:
	 *
	 * USER FILTER
	 *    COMBINED AND+OR
	 *       FACET PARAMETER OR (one or multiple original formulas) - AND relation + newFormula
	 *       FACET PARAMETER OR (one or multiple original formulas) - OR relation
	 *
	 * This method also needs to cope with complicated compositions in case multiple source indexes are used - in such
	 * occasion the above-mentioned composition is nested within OR containers that combine results from multiple
	 * source indexes. That's why we use {@link FormulaCloner} internally that traverses the entire `children` structure.
	 */
	@Nonnull
	private static Formula[] addNewFormulaAsConjunction(@Nonnull Formula newFormula, @Nonnull Formula[] children) {
		// if newly added formula should represent AND join
		// iterate over existing children
		final AtomicBoolean childrenAltered = new AtomicBoolean();
		for (int i = 0; i < children.length; i++) {
			final Formula mutatedChild = FormulaCloner.clone(children[i], examinedFormula -> {
				// and if existing AND formula is found
				if (examinedFormula instanceof AndFormula) {
					// simply add new facet group formula to the AND formula
					return examinedFormula.getCloneWithInnerFormulas(
						ArrayUtils.insertRecordIntoArray(
							newFormula, examinedFormula.getInnerFormulas(), examinedFormula.getInnerFormulas().length
						)
					);
				} else if (examinedFormula instanceof final CombinedFacetFormula combinedFacetFormula) {
					// if combined facet formula is found - we know there is combination of AND and OR formulas inside
					// take the AND part of the combined formula
					final Formula andFormula = combinedFacetFormula.getAndFormula();
					// and replace combined formula with OR part untouched and AND part enriched with new facet formula
					return examinedFormula.getCloneWithInnerFormulas(
						FormulaFactory.and(
							andFormula,
							newFormula
						),
						combinedFacetFormula.getOrFormula()
					);
				} else {
					return examinedFormula;
				}
			});

			if (mutatedChild != children[i]) {
				children[i] = mutatedChild;
				childrenAltered.set(true);
			}
		}
		if (childrenAltered.get()) {
			// return the updated array of children
			return children;
		} else {
			// neither AND or combined formula found in children - we know that parent is UserFilterFormula that
			// represents AND wrapping formula, so we can just combine existing children with new facet formula
			return ArrayUtils.insertRecordIntoArray(newFormula, children, children.length);
		}
	}

	/**
	 * Method adds `newFormula` as negated facet query. The implementation is straightforward - it takes existing
	 * filter and combines it with `newFormula` in NOT composition where `newFormula` represents subracted set.
	 */
	@Nonnull
	private static Formula[] addNewFormulaAsNegation(@Nonnull Formula newFormula, @Nonnull Formula[] children) {
		// if newly added formula should represent OR join
		// combine existing children with new facet formula in NOT container - now is not yet created
		// (otherwise this method would not be called at all)
		return new Formula[]{
			FormulaFactory.not(
				newFormula,
				FormulaFactory.and(children)
			)
		};
	}

	/**
	 * Method returns true if any of the `updateChildren` differs from (not same as) passed `formula` children.
	 */
	protected static boolean isAnyChildrenExchanged(@Nonnull Formula formula, @Nonnull Formula[] updatedChildren) {
		return updatedChildren.length != formula.getInnerFormulas().length ||
			Arrays.stream(formula.getInnerFormulas()).anyMatch(examinedFormula -> !ArrayUtils.contains(updatedChildren, examinedFormula));
	}

	/**
	 * Method stores formula to the result of the visitor on current {@link #levelStack}.
	 */
	protected void storeFormula(Formula formula) {
		// store updated formula
		if (levelStack.isEmpty()) {
			this.result = formula;
		} else {
			levelStack.peek().add(formula);
		}
	}

}
