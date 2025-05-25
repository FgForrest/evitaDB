/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.query.extraResult.translator.facet.producer;

import io.evitadb.api.query.require.FacetGroupRelationLevel;
import io.evitadb.api.query.require.FacetGroupsConjunction;
import io.evitadb.api.query.require.FacetGroupsDisjunction;
import io.evitadb.api.query.require.FacetGroupsExclusivity;
import io.evitadb.api.query.require.FacetGroupsNegation;
import io.evitadb.api.query.require.FacetRelationType;
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
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.facet.FacetHavingTranslator;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS;

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
	protected final FacetGroupRelationTypeResolver isFacetGroupConjunction;
	/**
	 * Predicate returns TRUE when facet covered by {@link FacetGroupsDisjunction} require query in
	 * input {@link EvitaRequest}.
	 */
	@Nonnull
	protected final FacetGroupRelationTypeResolver isFacetGroupDisjunction;
	/**
	 * Predicate returns TRUE when facet covered by {@link FacetGroupsNegation} require query in
	 * input {@link EvitaRequest}.
	 */
	@Nonnull
	protected final FacetGroupRelationTypeResolver isFacetGroupNegation;
	/**
	 * Predicate returns TRUE when facet covered by {@link FacetGroupsExclusivity} require query in
	 * input {@link EvitaRequest}.
	 */
	@Nonnull
	protected final FacetGroupRelationTypeResolver isFacetGroupExclusivity;
	/**
	 * Stack serves internally to collect the cloned tree of formulas.
	 */
	protected final Deque<CompositeObjectArray<Formula>> levelStack = new ArrayDeque<>(16);
	/**
	 * Contains true if visitor is currently within the scope of {@link NotFormula}.
	 */
	private final Deque<Boolean> insideNotContainer = new ArrayDeque<>(16);
	/**
	 * Contains true if visitor is currently within the scope of {@link UserFilterFormula}.
	 */
	private final Deque<Boolean> insideUserFilter = new ArrayDeque<>(16);
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
	 * Contains deferred lambda function that should be applied at the moment {@link NotFormula} processing is finished
	 * by this visitor. This postponed mutator solves the situation when the facet formula needs to be applied above
	 * NOT container and not within it. This is related to the internal mechanisms of {@link FutureNotFormula}
	 * propagation and {@link FacetHavingTranslator} facet formula composition.
	 */
	protected BiFunction<Formula, Formula[], Formula> deferredMutator;
	/**
	 * Result optimized form of formula.
	 */
	@Nullable @Getter protected Formula result;

	/**
	 * Method contains the logic that adds brand new {@link Formula} to the examined formula tree. It has
	 * to take group relation requested by {@link FacetGroupsDisjunction} and {@link FacetGroupsNegation} into
	 * an account.
	 */
	@Nonnull
	protected static Formula[] alterFormula(
		@Nonnull Formula newFormula,
		@Nonnull Formula superSetFormula,
		@Nonnull FacetRelationType relationType,
		@Nonnull Formula... children
	) {
		// if newly added formula should represent OR join
		return switch (relationType) {
			case DISJUNCTION -> addNewFormulaAsDisjunction(newFormula, children);
			case NEGATION -> addNewFormulaAsNegation(newFormula, children, superSetFormula);
			case EXCLUSIVITY -> new Formula[]{newFormula};
			case CONJUNCTION -> addNewFormulaAsConjunction(newFormula, children);
		};
	}

	/**
	 * Method returns true if any of the `updateChildren` differs from (not same as) passed `formula` children.
	 */
	protected static boolean isAnyChildrenExchanged(@Nonnull Formula formula, @Nonnull Formula[] updatedChildren) {
		if (updatedChildren.length != formula.getInnerFormulas().length) {
			return true;
		} else {
			for (int i = 0; i < updatedChildren.length; i++) {
				if (updatedChildren[i] != formula.getInnerFormulas()[i]) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * This method combines bitmaps of passed facet entity IDs into a single bitmap.
	 *
	 * @param facetEntityIds The array of facet entity IDs.
	 * @return The base entity IDs as a Bitmap.
	 */
	@Nonnull
	protected static Bitmap getBaseEntityIds(@Nonnull Bitmap[] facetEntityIds) {
		if (facetEntityIds.length == 0) {
			return EmptyBitmap.INSTANCE;
		} else if (facetEntityIds.length == 1) {
			return facetEntityIds[0];
		} else {
			return new BaseBitmap(
				RoaringBitmap.or(
					Arrays.stream(facetEntityIds)
						.map(RoaringBitmapBackedBitmap::getRoaringBitmap)
						.toArray(RoaringBitmap[]::new)
				)
			);
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
	 * FACET PARAMETER OR (zero or multiple formulas)
	 *
	 * that will be transformed to:
	 *
	 * USER FILTER
	 * OR
	 * AND
	 * FACET PARAMETER OR (zero or multiple original formulas)
	 * FACET PARAMETER OR (newFormula)
	 *
	 * 2. existing OR container is present
	 *
	 * USER FILTER
	 * OR
	 * FACET PARAMETER OR (zero or multiple formulas)
	 *
	 * that will be transformed to:
	 *
	 * USER FILTER
	 * OR
	 * FACET PARAMETER OR (zero or multiple original formulas)
	 * FACET PARAMETER OR (newFormula)
	 *
	 * 3. user filter wth combined facet relations
	 *
	 * USER FILTER
	 * COMBINED AND+OR
	 * FACET PARAMETER OR (one or multiple original formulas) - AND relation
	 * FACET PARAMETER OR (one or multiple original formulas) - OR relation
	 *
	 * that will be transformed to:
	 *
	 * USER FILTER
	 * COMBINED AND+OR
	 * FACET PARAMETER OR (one or multiple original formulas) - AND relation
	 * FACET PARAMETER OR (one or multiple original formulas) - OR relation + newFormula
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
						ArrayUtils.insertRecordIntoArrayOnIndex(
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
	 * FACET PARAMETER OR (zero or multiple formulas)
	 *
	 * that will be transformed to:
	 *
	 * USER FILTER
	 * AND
	 * FACET PARAMETER OR (zero or multiple original formulas)
	 * FACET PARAMETER OR (newFormula)
	 *
	 * 2. existing AND container is present
	 *
	 * USER FILTER
	 * AND
	 * FACET PARAMETER OR (zero or multiple formulas)
	 *
	 * that will be transformed to:
	 *
	 * USER FILTER
	 * AND
	 * FACET PARAMETER OR (zero or multiple original formulas)
	 * FACET PARAMETER OR (newFormula)
	 *
	 * 3. user filter wth combined facet relations
	 *
	 * USER FILTER
	 * COMBINED AND+OR
	 * FACET PARAMETER OR (one or multiple original formulas) - AND relation
	 * FACET PARAMETER OR (one or multiple original formulas) - OR relation
	 *
	 * that will be transformed to:
	 *
	 * USER FILTER
	 * COMBINED AND+OR
	 * FACET PARAMETER OR (one or multiple original formulas) - AND relation + newFormula
	 * FACET PARAMETER OR (one or multiple original formulas) - OR relation
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
			final Formula mutatedChild = FormulaCloner.clone(
				children[i],
				(cloner, examinedFormula) -> {
					// and if existing AND formula is found
					if (examinedFormula instanceof AndFormula && cloner.allParentsMatch(formula -> FilterByVisitor.isConjunctiveFormula(formula.getClass()))) {
						// simply add new facet group formula to the AND formula
						return examinedFormula.getCloneWithInnerFormulas(
							ArrayUtils.insertRecordIntoArrayOnIndex(
								newFormula, examinedFormula.getInnerFormulas(), examinedFormula.getInnerFormulas().length
							)
						);
					} else if (examinedFormula instanceof final CombinedFacetFormula combinedFacetFormula && cloner.allParentsMatch(formula -> FilterByVisitor.isConjunctiveFormula(formula.getClass()))) {
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
			return ArrayUtils.insertRecordIntoArrayOnIndex(newFormula, children, children.length);
		}
	}

	/**
	 * Method adds `newFormula` as negated facet query. The implementation is straightforward - it takes existing
	 * filter and combines it with `newFormula` in NOT composition where `newFormula` represents subracted set.
	 */
	@Nonnull
	private static Formula[] addNewFormulaAsNegation(@Nonnull Formula newFormula, @Nonnull Formula[] children, @Nonnull Formula superSetFormula) {
		// if newly added formula should represent OR join
		// combine existing children with new facet formula in NOT container - now is not yet created
		// (otherwise this method would not be called at all)
		return new Formula[]{
			FormulaFactory.not(
				newFormula,
				children.length == 0 ? superSetFormula : FormulaFactory.and(children)
			)
		};
	}

	/**
	 * Generates a formula based on the given parameters.
	 *
	 * @param baseFormula                  The base formula to generate the formula from.
	 * @param baseFormulaWithoutUserFilter The base formula without the user filter applied.
	 * @param referenceSchema              The reference schema contract.
	 * @param facetGroupId                 The facet group ID.
	 * @param facetId                      The facet ID.
	 * @param facetEntityIds               The facet entity IDs.
	 * @return The generated formula.
	 */
	@Nonnull
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
			this.facetEntityIds = getBaseEntityIds(facetEntityIds);
			// now compute the formula
			baseFormula.accept(this);
			// and return computation result
			return getResult(baseFormula);
		} finally {
			// finally, clear all internal global variables in a safe manner
			clearInternalStateAndMakeUnusable();
		}
	}

	@Override
	public void visit(@Nonnull Formula formula) {
		// evaluate and set flag that signalizes visitor is within UserFilterFormula scope
		boolean isUserFilter = formula instanceof UserFilterFormula;
		if (isUserFilter) {
			this.insideUserFilter.push(true);
		}
		// evaluate and set flag that signalizes visitor is within NotFormula scope
		boolean isNotContainer = formula instanceof NotFormula;
		if (isNotContainer) {
			this.insideNotContainer.push(true);
		}
		// now iterate and copy children
		final Formula[] updatedChildren;
		this.levelStack.push(new CompositeObjectArray<>(Formula.class));
		try {
			// but only if implementation says so - FacetCalculator omits UserFilter contents
			if (shouldIncludeChildren(isUserFilter)) {
				for (Formula innerFormula : formula.getInnerFormulas()) {
					innerFormula.accept(this);
				}
			}
		} finally {
			updatedChildren = this.levelStack.pop().toArray();
		}
		// if we're leaving UserFilterFormula scope
		if (isUserFilter) {
			// reset inside user filter flag
			this.insideUserFilter.pop();
			// apply respective modifications
			if (handleUserFilter(formula, updatedChildren)) {
				// if the user filter has been handled skip early - we don't need another storeFormula call
				return;
			}
		}
		// if we're leaving NotFormula scope
		if (isInsideNotContainer() && isNotContainer) {
			// reset not container flag
			this.insideNotContainer.pop();
			// if the logic instantiated deferred mutator - now it's time to apply it
			if (this.deferredMutator != null) {
				storeFormula(
					this.deferredMutator.apply(formula, updatedChildren)
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
		return !this.insideNotContainer.isEmpty() && this.insideNotContainer.peek();
	}

	/**
	 * Returns true if currently examined constraint is placed within user filter container (may not be placed directly in it).
	 */
	protected boolean isInsideUserFilter() {
		return !this.insideUserFilter.isEmpty() && this.insideUserFilter.peek();
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
		// determine the facet group relation to other groups
		final FacetRelationType relationType = getFacetRelationType(
			this.referenceSchema, WITH_DIFFERENT_GROUPS, FacetRelationType.CONJUNCTION, this.facetGroupId
		);
		// create facet group formula
		final Formula newFormula = createNewFacetGroupFormula();
		// if we're inside NotFormula
		if (isInsideNotContainer()) {
			// and the facet is also negated
			if (relationType == FacetRelationType.NEGATION) {
				// we need to defer the mutation to the moment when we leave not container and subtract
				this.deferredMutator = (laterEncounteredFormula, laterEncounteredChildren) -> {
					// and we need to create facet conjunction with base formula without user filter
					final Formula facetConjunction = FormulaFactory.and(
						newFormula,
						this.baseFormulaWithoutUserFilter
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
								this.baseFormulaWithoutUserFilter
							) // but we subtract the conjunction of new formula and base formula instead of superset
						);
					}
				};
				return false;
			} else {
				// we need to defer the mutation to the moment when we leave not container and add the facet formula
				// to the "positive" (superset) part of the not container
				this.deferredMutator = (laterEncounteredFormula, laterEncounteredChildren) -> {
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
					alterFormula(
						newFormula,
						this.baseFormulaWithoutUserFilter,
						relationType,
						updatedChildren
					)
				)
			);
			// we've stored the formula - instruct super method to skip it's handling
			return true;
		}
	}

	/**
	 * Determines the facet relation type based on the specified facet group relation level,
	 * using configured conditions. The method evaluates various facet group relation types
	 * such as negation, disjunction, exclusivity, and conjunction. If none of the conditions are met,
	 * the provided default relation type is returned.
	 *
	 * @param referenceSchema     the reference schema to evaluate
	 * @param level               the level of the facet group relation to evaluate
	 * @param defaultRelationType the default relation type to return if no specific relation condition is met
	 * @param theFacetGroup       the facet group to evaluate
	 * @return the determined facet relation type based on the evaluation of the provided level and conditions
	 */
	@Nonnull
	protected FacetRelationType getFacetRelationType(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FacetGroupRelationLevel level,
		@Nonnull FacetRelationType defaultRelationType,
		@Nullable Integer theFacetGroup
	) {
		if (this.isFacetGroupNegation.test(referenceSchema, theFacetGroup, level)) {
			return FacetRelationType.NEGATION;
		} else if (this.isFacetGroupDisjunction.test(referenceSchema, theFacetGroup, level)) {
			return FacetRelationType.DISJUNCTION;
		} else if (this.isFacetGroupExclusivity.test(referenceSchema, theFacetGroup, level)) {
			return FacetRelationType.EXCLUSIVITY;
		} else if (this.isFacetGroupConjunction.test(referenceSchema, theFacetGroup, level)) {
			return FacetRelationType.CONJUNCTION;
		} else {
			return defaultRelationType;
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
	 * Method allows altering the result before it is returned to the caller.
	 */
	@Nonnull
	protected Formula getResult(@Nonnull Formula baseFormula) {
		Assert.isPremiseValid(this.result != null, "Result formula must be set!");
		// simply return the result
		return this.result;
	}

	/**
	 * Method creates new {@link Formula} instance that corresponds with requested
	 * {@link FacetGroupsConjunction} requirement in input {@link EvitaRequest}.
	 */
	@Nonnull
	protected MutableFormula createNewFacetGroupFormula() {
		return new MutableFormula(
			this.isFacetGroupConjunction.test(this.referenceSchema, this.facetGroupId, WITH_DIFFERENT_FACETS_IN_GROUP) ?
				new FacetGroupAndFormula(this.referenceSchema.getName(), this.facetGroupId, new BaseBitmap(this.facetId), this.facetEntityIds) :
				new FacetGroupOrFormula(this.referenceSchema.getName(), this.facetGroupId, new BaseBitmap(this.facetId), this.facetEntityIds)
		);
	}

	/**
	 * Method stores formula to the result of the visitor on current {@link #levelStack}.
	 */
	protected void storeFormula(@Nonnull Formula formula) {
		// store updated formula
		if (this.levelStack.isEmpty()) {
			this.result = formula;
		} else {
			this.levelStack.peek().add(formula);
		}
	}

	/**
	 * Clears the internal state of the object and makes it unusable for further operations.
	 *
	 * This method sets several internal fields to null or reset values, effectively clearing
	 * any previously stored state or data within the object. It also clears the internal
	 * collections for tracking specific contexts (such as `insideNotContainer` and `insideUserFilter`),
	 * and resets identifiers like `facetId` and `facetGroupId`.
	 *
	 * This operation is intended to ensure that the object cannot be used in its current form
	 * after invoking this method, preventing unintended behavior due to lingering state.
	 */
	@SuppressWarnings("DataFlowIssue")
	private void clearInternalStateAndMakeUnusable() {
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

	/**
	 * A functional interface that resolves the type of relation for a facet group.
	 * The method evaluates the relation based on the provided reference schema, facet group ID,
	 * and the level of relation within the facet group.
	 *
	 * The implementation of this interface is expected to determine whether
	 * a condition is met based on the provided parameters - principle is same as {@link Predicate},
	 * but accepts 3 arguments.
	 */
	@FunctionalInterface
	public interface FacetGroupRelationTypeResolver {

		boolean test(
			@Nonnull ReferenceSchemaContract referenceSchemaContract,
			@Nullable Integer facetGroupId,
			@Nonnull FacetGroupRelationLevel level
		);

	}

	/**
	 * This implementation of {@link FormulaVisitor} traverses the formula tree and replaces the first found
	 * {@link MutableFormula} with the formula provided by the supplier (there should be only one such formula).
	 * The replacement is done in-place and the memoized results of all the parent formulas are cleared so that
	 * the new formula has chance to alter the computation result.
	 */
	@RequiredArgsConstructor
	protected static class MutableFormulaFinderAndReplacer implements FormulaVisitor {
		/**
		 * The supplier of the formula that should replace the first found {@link MutableFormula}.
		 */
		private final Supplier<FacetGroupFormula> formulaToReplaceSupplier;
		/**
		 * The stack of parent formulas of the currently visited formula tree.
		 */
		private final Deque<Formula> formulaStack = new ArrayDeque<>(16);
		/**
		 * Reference to {@link MutableFormula} found.
		 */
		@Getter
		private MutableFormula target;

		/**
		 * Returns true if the target {@link MutableFormula} has been found.
		 *
		 * @return True if the target {@link MutableFormula} has been found.
		 */
		public boolean isTargetFound() {
			return this.target != null;
		}

		@Override
		public void visit(@Nonnull Formula formula) {
			if (this.target == null) {
				if (formula instanceof MutableFormula mutableFormula) {
					if (this.target != null) {
						throw new GenericEvitaInternalError("Expected single MutableFormula in the formula tree!");
					} else {
						this.target = mutableFormula;
						mutableFormula.setDelegate(this.formulaToReplaceSupplier.get());
						for (Formula parentFormula : this.formulaStack) {
							parentFormula.clearMemory();
						}
					}
				} else {
					this.formulaStack.push(formula);
					for (Formula innerFormula : formula.getInnerFormulas()) {
						innerFormula.accept(this);
					}
					this.formulaStack.pop();
				}
			}
		}

	}

}
