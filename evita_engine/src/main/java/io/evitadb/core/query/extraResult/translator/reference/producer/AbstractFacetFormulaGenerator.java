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

package io.evitadb.core.query.extraResult.translator.reference.producer;

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
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupAndFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupOrFormula;
import io.evitadb.core.query.algebra.facet.FacetHavingFormula;
import io.evitadb.core.query.algebra.facet.ScopeContainerFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.filter.translator.facet.FacetHavingTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Function;

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
	 * Resolves the one relation the facets of a group take at a level - the same resolution the query result uses,
	 * so that the reference summary predicts the products the result returns. Relations declared by
	 * {@link FacetGroupsConjunction}, {@link FacetGroupsDisjunction}, {@link FacetGroupsNegation} or
	 * {@link FacetGroupsExclusivity} in the input {@link EvitaRequest} take precedence over the request-wide default.
	 */
	@Nonnull
	protected final FacetRelationTypeResolver facetRelationType;
	/**
	 * Predicate returns TRUE when facet covered by {@link FacetGroupsConjunction} require query in
	 * input {@link EvitaRequest}.
	 */
	@Nonnull
	protected final FacetGroupRelationTypeResolver isFacetGroupConjunction;
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
	 * Contains the groups the facet of {@link #facetId} is referenced under - in each scope and in the whole query -
	 * and the entities referencing it under each of them.
	 */
	protected FacetGroupOccurrences facetGroupOccurrences;
	/**
	 * Contains the scopes of the {@link ScopeContainerFormula scope containers} the visitor is currently in - the
	 * scope post-processing copies the user filter into the container of every scope.
	 */
	private final Deque<Scope> scopes = new ArrayDeque<>(4);
	/**
	 * Result optimized form of formula.
	 */
	@Nullable @Getter protected Formula result;

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
	 * Returns true if a {@link UserFilterFormula} of the formula sits in the subtracted part of a {@link NotFormula} -
	 * a user filter negated by the user, `not(userFilter(...))` - so the formula subtracts the user filter rather than
	 * restricting the result to it. A user filter in the superset part of a {@link NotFormula} - next to a negated
	 * constraint - still restricts the result, the negated constraint is subtracted from it.
	 *
	 * @param formula the formula of the facet computation
	 * @return true if the user filter of the formula is subtracted
	 */
	protected static boolean isUserFilterNegated(@Nonnull Formula formula) {
		return FormulaFinder.find(formula, NotFormula.class, LookUp.DEEP)
			.stream()
			.anyMatch(
				it -> !FormulaFinder.find(it.getSubtractedFormula(), UserFilterFormula.class, LookUp.SHALLOW).isEmpty()
			);
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
				PersistentRoaringBitmap.or(
					Arrays.stream(facetEntityIds)
						.map(RoaringBitmapBackedBitmap::getRoaringBitmap)
						.toArray(PersistentRoaringBitmap[]::new)
				)
			);
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
		return generateFormula(
			baseFormula, baseFormulaWithoutUserFilter, referenceSchema, facetGroupId, facetId, facetEntityIds,
			FacetGroupOccurrences.singleGroup(facetGroupId, getBaseEntityIds(facetEntityIds))
		);
	}

	/**
	 * Generates a formula based on the given parameters - the facet takes part in every group its occurrences give it
	 * in the scope of the user filter it joins.
	 *
	 * @param baseFormula                  The base formula to generate the formula from.
	 * @param baseFormulaWithoutUserFilter The base formula without the user filter applied.
	 * @param referenceSchema              The reference schema contract.
	 * @param facetGroupId                 The facet group ID of the statistics being computed.
	 * @param facetId                      The facet ID.
	 * @param facetEntityIds               The facet entity IDs referencing the facet under the facet group.
	 * @param facetGroupOccurrences        The groups the facet is referenced under.
	 * @return The generated formula.
	 */
	@Nonnull
	public Formula generateFormula(
		@Nonnull Formula baseFormula,
		@Nonnull Formula baseFormulaWithoutUserFilter,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer facetGroupId,
		int facetId,
		@Nonnull Bitmap[] facetEntityIds,
		@Nonnull FacetGroupOccurrences facetGroupOccurrences
	) {
		try {
			// initialize global variables for this execution
			this.result = null;
			this.baseFormulaWithoutUserFilter = baseFormulaWithoutUserFilter;
			this.referenceSchema = referenceSchema;
			this.facetId = facetId;
			this.facetGroupId = facetGroupId;
			this.facetGroupOccurrences = facetGroupOccurrences;

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
		final boolean isScopeContainer = formula instanceof ScopeContainerFormula;
		if (isScopeContainer) {
			this.scopes.push(((ScopeContainerFormula) formula).getScope());
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
			if (isScopeContainer) {
				this.scopes.pop();
			}
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
	 * Returns true if currently examined constraint is placed within user filter container (may not be placed directly in it).
	 */
	protected boolean isInsideUserFilter() {
		return !this.insideUserFilter.isEmpty() && this.insideUserFilter.peek();
	}

	/**
	 * Returns the groups the facet takes part in when it joins the currently visited user filter - the groups the
	 * scope of the enclosing {@link ScopeContainerFormula} gives it, or the groups of the whole query when the user
	 * filter is not copied into a scope container.
	 *
	 * @return the groups, NULL standing for the facets without a group; never empty
	 */
	@Nonnull
	protected List<Integer> getFacetGroupsOfCurrentScope() {
		return this.facetGroupOccurrences.getGroups(this.scopes.peek());
	}

	/**
	 * Returns true if the group formula of the passed group has been enriched with the facet in place inside the user
	 * filter the visitor is leaving, so that no new formula has to be added for the group.
	 *
	 * @param groupId the group, NULL for the facets without a group
	 * @return true if the group has been enriched in place
	 */
	protected boolean isGroupEnrichedInCurrentUserFilter(@Nullable Integer groupId) {
		return false;
	}

	/**
	 * Returns true if any of the passed groups of the facet is exclusive with the other groups, so that selecting the
	 * facet replaces the selection of its reference.
	 *
	 * @param groups the groups of the facet
	 * @return true if selecting the facet deselects the other groups of its reference
	 */
	protected boolean isExclusiveWithOtherGroups(@Nonnull List<Integer> groups) {
		for (final Integer groupId : groups) {
			if (this.facetRelationType.resolve(this.referenceSchema, groupId, WITH_DIFFERENT_GROUPS) ==
				FacetRelationType.EXCLUSIVITY) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Method allows reacting to currently processed formula.
	 */
	protected boolean handleFormula(@Nonnull Formula formula) {
		return false;
	}

	/**
	 * Method allows to respond to leaving {@link UserFilterFormula} scope. The facet joins the user filter wherever the
	 * user filter is placed - also in the subtracted part of a {@link NotFormula} (`not(userFilter(...))`) or in its
	 * superset part (a negated constraint next to the user filter) - because selecting the facet extends the user
	 * filter only, and the formula around it then composes the extended user filter the way the result does.
	 *
	 * The facet joins the user filter in every group it is referenced under in the scope the user filter restricts
	 * (see {@link FacetGroupOccurrences}): the positive groups join the facet selection of its reference, the negated
	 * ones subtract the facet from the selection of its reference - from the whole user filter where that is the same
	 * set - and a group exclusive with the other groups replaces the selection of the reference.
	 */
	protected boolean handleUserFilter(@Nonnull Formula formula, @Nonnull Formula[] updatedChildren) {
		// the facet takes part in every group the scope of the user filter gives it, each with a formula of its own
		// unless the subclass has enriched the group formula of the user filter in place
		final List<Integer> groups = getFacetGroupsOfCurrentScope();
		final List<Formula> newFormulas = new ArrayList<>(groups.size());
		for (final Integer groupId : groups) {
			if (!isGroupEnrichedInCurrentUserFilter(groupId)) {
				newFormulas.add(createNewFacetGroupFormula(groupId));
			}
		}
		final Formula[] alteredChildren;
		if (isExclusiveWithOtherGroups(groups)) {
			alteredChildren = replaceFacetSelection(newFormulas, updatedChildren);
		} else {
			final List<Formula> positiveFormulas = new ArrayList<>(newFormulas.size());
			final List<Formula> negatedFormulas = new ArrayList<>(newFormulas.size());
			for (final Formula newFormula : newFormulas) {
				if (getRelationBetweenGroups(newFormula) == FacetRelationType.NEGATION) {
					negatedFormulas.add(newFormula);
				} else {
					positiveFormulas.add(newFormula);
				}
			}
			Formula[] children = updatedChildren;
			if (!positiveFormulas.isEmpty()) {
				// a positive facet joins the facet selection of its reference the way the result composes it, unless
				// joining the user filter as a conjunct of its own is the same set: for a conjunctive facet `g` and
				// a selection without a disjunctive group `(C AND g) AND NOT N` = `(C AND NOT N) AND g`, and
				// `(rest AND g) AND NOT N` = `(rest AND NOT N) AND g` for a NOT-only one, while conjunctions and
				// superset parts of NOT pass the `AND g` up unchanged; with a disjunctive group `D`,
				// `((C AND g) OR D)` differs from `(C OR D) AND g`; the conjunct keeps the selection and its NOT
				// memoized for every facet the cached formula serves
				final Formula[] childrenWithFacetSelection =
					isFacetSelectionNarrowedByConjunction(positiveFormulas, children) ?
						null : addNewFormulasToFacetSelection(positiveFormulas, children);
				// when the user filter selects no facet of the reference, the facet joins the user filter itself -
				// the relation between groups applies between the groups of one reference only, and the user filter
				// combines its constraints, the facet selections of different references included, by conjunction
				children = childrenWithFacetSelection == null ?
					ArrayUtils.insertRecordIntoArrayOnIndex(
						composeFacetSelection(positiveFormulas), children, children.length
					) :
					childrenWithFacetSelection;
			}
			if (!negatedFormulas.isEmpty()) {
				// a negated group subtracts the facet from the facet selection of its reference, which is the same set
				// as subtracting it from the whole user filter when every selection of the reference is reached through
				// conjunctions and superset parts of NOT only - the user filter selecting no facet of the reference
				// included - and the whole user filter keeps the selection and its NOT memoized
				children = isFacetSelectionNarrowedByConjunction(children, true) ?
					addNewFormulaAsNegation(
						negatedFormulas.size() == 1 ?
							negatedFormulas.get(0) : FormulaFactory.or(negatedFormulas.toArray(Formula[]::new)),
						children,
						this.baseFormulaWithoutUserFilter
					) :
					addNegatedFormulasToFacetSelection(negatedFormulas, children);
			}
			alteredChildren = children;
		}
		// we can immediately alter the current formula adding new facet formula
		storeFormula(formula.getCloneWithInnerFormulas(alteredChildren));
		// we've stored the formula - instruct super method to skip it's handling
		return true;
	}

	/**
	 * Returns the relation of the group of the passed group formula - a {@link FacetGroupFormula} or
	 * a {@link MutableFormula} standing for one - to the other groups of the reference.
	 *
	 * @param groupFormula the group formula
	 * @return the relation of its group to the other groups
	 */
	@Nonnull
	private FacetRelationType getRelationBetweenGroups(@Nonnull Formula groupFormula) {
		return this.facetRelationType.resolve(
			this.referenceSchema, getFacetGroupId(groupFormula), WITH_DIFFERENT_GROUPS
		);
	}

	/**
	 * Returns the group of the passed group formula - a {@link FacetGroupFormula} or a {@link MutableFormula} standing
	 * for one.
	 *
	 * @param groupFormula the group formula
	 * @return the group, NULL for the facets without a group
	 */
	@Nullable
	private static Integer getFacetGroupId(@Nonnull Formula groupFormula) {
		return groupFormula instanceof MutableFormula mutableFormula ?
			mutableFormula.getFacetGroupId() : ((FacetGroupFormula) groupFormula).getFacetGroupId();
	}

	/**
	 * Composes the facet selection of the facet's reference from the passed group formulas of the facet, the way
	 * {@link FacetHavingTranslator} composes the selection of the facet alone. A single formula is returned as it is.
	 *
	 * @param groupFormulas the group formulas of the facet
	 * @return the facet selection
	 */
	@Nonnull
	private Formula composeFacetSelection(@Nonnull List<Formula> groupFormulas) {
		return groupFormulas.size() == 1 ?
			groupFormulas.get(0) :
			FacetHavingTranslator.composeFacetSelectionFormula(
				this.referenceSchema.getName(), groupFormulas, this::getRelationBetweenGroups
			);
	}

	/**
	 * Returns true if the formulas of a positive facet narrow every facet selection of its reference that
	 * {@link #addNewFormulasToFacetSelection} would compose them into by conjunction only, so that joining them to
	 * the user filter as conjuncts of their own selects the same entities. That holds when:
	 *
	 * - every group of the facet relates to the other groups by conjunction
	 * - no selection of the reference has a group related to the other groups by disjunction, which
	 *   {@link FacetHavingTranslator} composes as a union with the conjunctive groups
	 * - every selection of the reference is positive or subtracted as a whole by a {@link NotFormula} of its own, and
	 *   is reached from the user filter through {@link AndFormula conjunctions} and superset parts of
	 *   {@link NotFormula} only - both pass a narrowing of their part on to their own result unchanged
	 *
	 * @param positiveFormulas the formulas of the facet being added, one for each of its groups not negated
	 * @param children         the children of the user filter formula
	 * @return true if the formulas may join the user filter as conjuncts of their own
	 */
	private boolean isFacetSelectionNarrowedByConjunction(
		@Nonnull List<Formula> positiveFormulas,
		@Nonnull Formula[] children
	) {
		for (final Formula positiveFormula : positiveFormulas) {
			if (getRelationBetweenGroups(positiveFormula) != FacetRelationType.CONJUNCTION) {
				return false;
			}
		}
		return isFacetSelectionNarrowedByConjunction(children, false);
	}

	/**
	 * Returns true if every facet selection of the facet's reference in the children of the user filter is narrowed by
	 * a conjunct of the user filter the way it is narrowed by a conjunctive facet joining it, or by a negated facet
	 * subtracted from it - see {@link #isFacetSelectionNarrowedByConjunction(List, Formula[])} for the conditions.
	 * A negated facet subtracted from a selection with a disjunctive group narrows it the same way as when subtracted
	 * from the whole user filter: `((C OR D) AND NOT N) AND NOT f` = `(C OR D) AND NOT (N OR f)`.
	 *
	 * @param children                 the children of the user filter formula
	 * @param disjunctiveGroupsNarrowed true if a selection with a disjunctive group is narrowed too - for a negated
	 *                                 facet
	 * @return true if every selection of the reference is narrowed by a conjunct of the user filter
	 */
	private boolean isFacetSelectionNarrowedByConjunction(
		@Nonnull Formula[] children,
		boolean disjunctiveGroupsNarrowed
	) {
		final String referenceName = this.referenceSchema.getName();
		for (final Formula child : children) {
			if (!isFacetSelectionNarrowedByConjunction(child, referenceName, true, disjunctiveGroupsNarrowed)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns true if every facet selection of the reference in the passed formula that
	 * {@link #addNewFormulasToFacetSelection} would compose a conjunctive facet into is narrowed by it the way
	 * a conjunct of the passed formula narrows it - see {@link #isFacetSelectionNarrowedByConjunction(List, Formula[])}.
	 *
	 * @param formula                   the examined part of the user filter
	 * @param referenceName             the name of the reference of the facet being added
	 * @param conjunctivePosition       true if the part is reached from the user filter through conjunctions and
	 *                                  superset parts of {@link NotFormula} only
	 * @param disjunctiveGroupsNarrowed true if a selection with a disjunctive group is narrowed too - for a negated
	 *                                  facet
	 * @return true if no selection of the reference in the part prevents the facet joining the user filter on its own
	 */
	private boolean isFacetSelectionNarrowedByConjunction(
		@Nonnull Formula formula,
		@Nonnull String referenceName,
		boolean conjunctivePosition,
		boolean disjunctiveGroupsNarrowed
	) {
		if (formula instanceof NotFormula notFormula &&
			isNegatedFacetSelection(notFormula.getSubtractedFormula(), referenceName)) {
			// the facet would join its superset part
			return conjunctivePosition;
		} else if (formula instanceof FacetHavingFormula facetHavingFormula &&
			referenceName.equals(facetHavingFormula.getReferenceName())) {
			if (isNegatedFacetSelection(facetHavingFormula, referenceName)) {
				// a selection with only negated groups subtracted by a NOT of its own is caught by the first branch, so
				// this one sits elsewhere - in the superset part of the NOT the post-processing of a disjunction
				// creates - and the facet takes its place, see `addNewFormulasToFacetSelection`
				return false;
			}
			if (!conjunctivePosition) {
				return false;
			}
			if (disjunctiveGroupsNarrowed) {
				return true;
			}
			for (final Formula groupFormula : collectFacetGroupFormulas(facetHavingFormula)) {
				if (getRelationBetweenGroups(groupFormula) == FacetRelationType.DISJUNCTION) {
					return false;
				}
			}
			return true;
		} else if (formula instanceof NotFormula notFormula) {
			return isFacetSelectionNarrowedByConjunction(
				notFormula.getSubtractedFormula(), referenceName, false, disjunctiveGroupsNarrowed
			) &&
				isFacetSelectionNarrowedByConjunction(
					notFormula.getSupersetFormula(), referenceName, conjunctivePosition, disjunctiveGroupsNarrowed
				);
		} else {
			final boolean innerPosition = conjunctivePosition && formula instanceof AndFormula;
			for (final Formula innerFormula : formula.getInnerFormulas()) {
				if (!isFacetSelectionNarrowedByConjunction(
					innerFormula, referenceName, innerPosition, disjunctiveGroupsNarrowed
				)) {
					return false;
				}
			}
			return true;
		}
	}

	/**
	 * Adds the formulas of a positive facet - one for each of its groups disjunctive or conjunctive to the other
	 * groups - to the facet selection the user filter holds for the facet's reference, so that the prediction has
	 * exactly the shape of the result selecting the facet along with the others. {@link FacetHavingTranslator}
	 * composes the selection of one reference as `(conjunctive groups OR disjunctive groups) AND NOT negated groups`,
	 * so a facet joining the selection is subtracted by its negated groups, too - merely joining it to the rest of the
	 * user filter would let it escape them.
	 *
	 * The selection takes one of two places in the user filter:
	 *
	 * - a {@link FacetHavingFormula} with at least one positive group is a positive part of the user filter, and it
	 *   is composed anew from its group formulas and the new one by
	 *   {@link FacetHavingTranslator#composeFacetSelectionFormula(String, java.util.Collection, java.util.function.Function)}
	 * - a {@link FacetHavingFormula} with only negated groups is what {@link FutureNotFormula} post-processing
	 *   subtracts from the rest of the user filter - `NOT(negated, rest)` - and the result selecting a positive facet
	 *   along with it is `rest AND (facet AND NOT negated)`, which is the same set as `NOT(negated, rest AND facet)`,
	 *   so the new formula joins the superset part of the enclosing {@link NotFormula}
	 * - a {@link FacetHavingFormula} with only negated groups anywhere else stands for the complement of the
	 *   selection, too - the selection `NOT negated` the user nested in an `or` with `P` is post-processed into
	 *   `NOT(NOT(P, negated), superset)` - and the selection holding the positive facet as well is
	 *   `facet AND NOT negated`, whose complement is `negated OR NOT facet`, so that formula takes its place; the facet
	 *   is complemented within the base formula without the user filter, which the user filter is a part of
	 *
	 * Every occurrence is altered, because the scope post-processing copies the user filter into the
	 * {@link ScopeContainerFormula} of every scope.
	 *
	 * @param newFormulas the formulas of the facet being added, one for each of its groups not negated
	 * @param children    the children of the user filter formula
	 * @return the altered children, or NULL when the user filter selects no facet of the reference
	 */
	@Nullable
	private Formula[] addNewFormulasToFacetSelection(
		@Nonnull List<Formula> newFormulas,
		@Nonnull Formula[] children
	) {
		final String referenceName = this.referenceSchema.getName();
		final Formula[] alteredChildren = new Formula[children.length];
		boolean altered = false;
		for (int i = 0; i < children.length; i++) {
			alteredChildren[i] = FormulaCloner.clone(
				children[i],
				examinedFormula -> {
					if (examinedFormula instanceof NotFormula notFormula &&
						isNegatedFacetSelection(notFormula.getSubtractedFormula(), referenceName)) {
						return notFormula.getCloneWithInnerFormulas(
							notFormula.getSubtractedFormula(),
							FormulaFactory.and(notFormula.getSupersetFormula(), composeFacetSelection(newFormulas))
						);
					} else if (examinedFormula instanceof FacetHavingFormula facetHavingFormula &&
						referenceName.equals(facetHavingFormula.getReferenceName())) {
						if (isNegatedFacetSelection(facetHavingFormula, referenceName)) {
							// not subtracted by a NOT of its own - the formula is the complement of the selection
							return FormulaFactory.or(
								facetHavingFormula,
								FormulaFactory.not(
									composeFacetSelection(newFormulas), this.baseFormulaWithoutUserFilter
								)
							);
						}
						final List<Formula> groupFormulas = collectFacetGroupFormulas(facetHavingFormula);
						groupFormulas.addAll(newFormulas);
						return FacetHavingTranslator.composeFacetSelectionFormula(
							referenceName, groupFormulas, this::getRelationBetweenGroups
						);
					} else {
						return examinedFormula;
					}
				}
			);
			altered |= alteredChildren[i] != children[i];
		}
		return altered ? alteredChildren : null;
	}

	/**
	 * Subtracts the formulas of a negated facet - one for each of its negated groups - from every facet selection the
	 * user filter holds for the facet's reference, so that the prediction has exactly the shape of the result selecting
	 * the facet along with the others. It is used when some selection is not narrowed by a conjunct of the user filter
	 * - see {@link #isFacetSelectionNarrowedByConjunction(Formula[], boolean)} - where subtracting the facet from the
	 * whole user filter would subtract it from the parts the user placed next to the selection in an `or` as well.
	 *
	 * - a {@link FacetHavingFormula} with a positive group is composed anew from its group formulas and the new ones by
	 *   {@link FacetHavingTranslator#composeFacetSelectionFormula(String, java.util.Collection,
	 *   java.util.function.Function)}, which subtracts the negated groups
	 * - a {@link FacetHavingFormula} with only negated groups stands for the complement of the selection wherever it
	 *   is placed - see {@link #addNewFormulasToFacetSelection} - and the selection subtracting the facet as well is
	 *   `NOT (negated OR facet)`, so the union of the two takes its place
	 *
	 * Every occurrence is altered, because the scope post-processing copies the user filter into the
	 * {@link ScopeContainerFormula} of every scope.
	 *
	 * @param negatedFormulas the formulas of the facet being added, one for each of its negated groups
	 * @param children        the children of the user filter formula
	 * @return the altered children
	 */
	@Nonnull
	private Formula[] addNegatedFormulasToFacetSelection(
		@Nonnull List<Formula> negatedFormulas,
		@Nonnull Formula[] children
	) {
		final String referenceName = this.referenceSchema.getName();
		final Formula[] alteredChildren = new Formula[children.length];
		for (int i = 0; i < children.length; i++) {
			alteredChildren[i] = FormulaCloner.clone(
				children[i],
				examinedFormula -> {
					if (examinedFormula instanceof FacetHavingFormula facetHavingFormula &&
						referenceName.equals(facetHavingFormula.getReferenceName())) {
						if (isNegatedFacetSelection(facetHavingFormula, referenceName)) {
							final Formula[] negatedSelections = new Formula[negatedFormulas.size() + 1];
							negatedSelections[0] = facetHavingFormula;
							for (int j = 0; j < negatedFormulas.size(); j++) {
								negatedSelections[j + 1] = negatedFormulas.get(j);
							}
							return FormulaFactory.or(negatedSelections);
						}
						final List<Formula> groupFormulas = collectFacetGroupFormulas(facetHavingFormula);
						groupFormulas.addAll(negatedFormulas);
						return FacetHavingTranslator.composeFacetSelectionFormula(
							referenceName, groupFormulas, this::getRelationBetweenGroups
						);
					} else {
						return examinedFormula;
					}
				}
			);
		}
		return alteredChildren;
	}

	/**
	 * Replaces the facet selection the user filter holds for the facet's reference with the formula of a facet whose
	 * group is exclusive with the other groups. Selecting such a facet deselects the facets of the other groups of its
	 * reference, but neither the selections of other references nor the other constraints of the user filter, which
	 * the user filter keeps combining by conjunction.
	 *
	 * The selection of the reference takes one of the two places {@link #addNewFormulasToFacetSelection} describes:
	 *
	 * - a {@link FacetHavingFormula} with at least one positive group is replaced by the selection of the facet - alone,
	 *   or joined with the other facets of its groups the user filter already selects, whose group formulas the
	 *   subclass has replaced with a {@link MutableFormula} joining the facet to them
	 * - a {@link NotFormula} subtracting the facet selection of the reference with only negated groups no longer
	 *   subtracts it, because those groups are deselected
	 *
	 * When the user filter holds no positive facet selection of the reference, the facet joins the user filter as one
	 * more conjunct.
	 *
	 * @param newFormulas the formulas of the facet being added, one for each of its groups not enriched in place
	 * @param children    the children of the user filter formula
	 * @return the altered children
	 */
	@Nonnull
	private Formula[] replaceFacetSelection(@Nonnull List<Formula> newFormulas, @Nonnull Formula[] children) {
		final String referenceName = this.referenceSchema.getName();
		final AtomicBoolean selectionReplaced = new AtomicBoolean();
		final Formula[] alteredChildren = new Formula[children.length];
		for (int i = 0; i < children.length; i++) {
			alteredChildren[i] = FormulaCloner.clone(
				children[i],
				examinedFormula -> {
					if (examinedFormula instanceof NotFormula notFormula &&
						isNegatedFacetSelection(notFormula.getSubtractedFormula(), referenceName)) {
						final Formula otherNegatedSelections = withoutNegatedFacetSelection(
							notFormula.getSubtractedFormula(), referenceName
						);
						return otherNegatedSelections == null ?
							notFormula.getSupersetFormula() :
							notFormula.getCloneWithInnerFormulas(otherNegatedSelections, notFormula.getSupersetFormula());
					} else if (examinedFormula instanceof FacetHavingFormula facetHavingFormula &&
						referenceName.equals(facetHavingFormula.getReferenceName())) {
						// the group of the facet is exclusive, never negated, so a selection holding its group is positive
						final List<Formula> facetFormulas = findMutableFormulas(facetHavingFormula);
						if (facetFormulas.isEmpty() && isNegatedFacetSelection(facetHavingFormula, referenceName)) {
							return examinedFormula;
						}
						selectionReplaced.set(true);
						facetFormulas.addAll(newFormulas);
						return FacetHavingTranslator.composeFacetSelectionFormula(
							referenceName, facetFormulas, this::getRelationBetweenGroups
						);
					} else {
						return examinedFormula;
					}
				}
			);
		}
		return selectionReplaced.get() || newFormulas.isEmpty() ?
			alteredChildren :
			ArrayUtils.insertRecordIntoArrayOnIndex(
				composeFacetSelection(newFormulas), alteredChildren, alteredChildren.length
			);
	}

	/**
	 * Returns the subtracted part of a {@link NotFormula} without the facet selection of the reference with only
	 * negated groups - see {@link #isNegatedFacetSelection(Formula, String)} for the shapes the part takes.
	 *
	 * @param subtractedFormula the subtracted part containing the negated facet selection of the reference
	 * @param referenceName     the name of the reference whose negated facet selection is removed
	 * @return the rest of the subtracted part, or NULL when nothing else is subtracted
	 */
	@Nullable
	private Formula withoutNegatedFacetSelection(@Nonnull Formula subtractedFormula, @Nonnull String referenceName) {
		if (subtractedFormula instanceof FacetHavingFormula) {
			return null;
		}
		final Formula[] otherSelections = Arrays.stream(subtractedFormula.getInnerFormulas())
			.filter(it -> !(it instanceof FacetHavingFormula && isNegatedFacetSelection(it, referenceName)))
			.toArray(Formula[]::new);
		if (otherSelections.length == 0) {
			return null;
		} else if (otherSelections.length == 1) {
			return otherSelections[0];
		} else {
			return subtractedFormula.getCloneWithInnerFormulas(otherSelections);
		}
	}

	/**
	 * Finds the {@link MutableFormula mutable formulas} a subclass placed into the facet selection in place of the
	 * formulas of the groups of the facet being added.
	 *
	 * @param facetHavingFormula the facet selection of one reference
	 * @return the mutable formulas in a mutable list, empty when the selection holds none
	 */
	@Nonnull
	private static List<Formula> findMutableFormulas(@Nonnull FacetHavingFormula facetHavingFormula) {
		final List<Formula> mutableFormulas = new ArrayList<>(2);
		final Deque<Formula> stack = new ArrayDeque<>(8);
		stack.push(facetHavingFormula);
		while (!stack.isEmpty()) {
			final Formula examinedFormula = stack.pop();
			if (examinedFormula instanceof MutableFormula) {
				mutableFormulas.add(examinedFormula);
			} else if (!(examinedFormula instanceof FacetGroupFormula)) {
				for (Formula innerFormula : examinedFormula.getInnerFormulas()) {
					stack.push(innerFormula);
				}
			}
		}
		return mutableFormulas;
	}

	/**
	 * Returns true if the passed formula is the facet selection of the reference with only negated groups, or a
	 * disjunction of the negated parts of the user filter containing it - the subtracted part {@link FutureNotFormula}
	 * post-processing produces.
	 *
	 * @param formula       the examined formula
	 * @param referenceName the name of the reference of the facet being added
	 * @return true if the formula is or directly contains the facet selection of the reference with only negated groups
	 */
	private boolean isNegatedFacetSelection(@Nonnull Formula formula, @Nonnull String referenceName) {
		if (formula instanceof FacetHavingFormula facetHavingFormula) {
			if (!referenceName.equals(facetHavingFormula.getReferenceName())) {
				return false;
			}
			for (Formula groupFormula : collectFacetGroupFormulas(facetHavingFormula)) {
				if (getRelationBetweenGroups(groupFormula) != FacetRelationType.NEGATION) {
					return false;
				}
			}
			return true;
		} else if (formula instanceof OrFormula) {
			for (Formula innerFormula : formula.getInnerFormulas()) {
				if (innerFormula instanceof FacetHavingFormula && isNegatedFacetSelection(innerFormula, referenceName)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Collects the formulas of the facet groups the facet selection is composed of. {@link FacetHavingTranslator}
	 * composes the selection of {@link FacetGroupFormula} leaves only, joined by logical containers; a subclass may
	 * have replaced some of them with a {@link MutableFormula} standing for the group.
	 *
	 * @param facetHavingFormula the facet selection of one reference
	 * @return the formulas of its facet groups, in a mutable list
	 */
	@Nonnull
	private static List<Formula> collectFacetGroupFormulas(@Nonnull FacetHavingFormula facetHavingFormula) {
		final List<Formula> groupFormulas = new ArrayList<>(8);
		final Deque<Formula> stack = new ArrayDeque<>(8);
		stack.push(facetHavingFormula);
		while (!stack.isEmpty()) {
			final Formula examinedFormula = stack.pop();
			if (examinedFormula instanceof FacetGroupFormula || examinedFormula instanceof MutableFormula) {
				groupFormulas.add(examinedFormula);
			} else {
				final Formula[] innerFormulas = examinedFormula.getInnerFormulas();
				Assert.isPremiseValid(
					innerFormulas.length > 0,
					() -> "The facet selection is expected to be composed of facet group formulas only, but " +
						"contains: " + examinedFormula
				);
				for (Formula innerFormula : innerFormulas) {
					stack.push(innerFormula);
				}
			}
		}
		return groupFormulas;
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
	 * Method creates new {@link Formula} instance of the facet in the group of the statistics being computed that
	 * corresponds with requested {@link FacetGroupsConjunction} requirement in input {@link EvitaRequest}.
	 */
	@Nonnull
	protected MutableFormula createNewFacetGroupFormula() {
		return createNewFacetGroupFormula(this.facetGroupId);
	}

	/**
	 * Method creates new {@link Formula} instance of the facet in the passed group, with the entities referencing the
	 * facet under that group, that corresponds with requested {@link FacetGroupsConjunction} requirement in input
	 * {@link EvitaRequest}.
	 *
	 * @param groupId the group, NULL for the facets without a group
	 * @return the formula of the facet in the group
	 */
	@Nonnull
	protected MutableFormula createNewFacetGroupFormula(@Nullable Integer groupId) {
		return new MutableFormula(createFacetGroupFormula(groupId, false));
	}

	/**
	 * Creates the formula of the facet in the passed group, with the entities referencing the facet under that group.
	 *
	 * @param groupId     the group, NULL for the facets without a group
	 * @param disjunctive true to create a disjunctive formula whatever the relation of the facets in the group is
	 * @return the formula of the facet in the group
	 */
	@Nonnull
	protected FacetGroupFormula createFacetGroupFormula(@Nullable Integer groupId, boolean disjunctive) {
		return createFacetGroupFormula(
			this.referenceSchema, groupId, this.facetId, this.facetGroupOccurrences.getEntityIds(groupId), disjunctive
		);
	}

	/**
	 * Creates the formula of a facet in a group, whose facets are joined by conjunction when the input
	 * {@link EvitaRequest} says so, and by disjunction otherwise.
	 *
	 * @param referenceSchema the schema of the faceted reference
	 * @param groupId         the group, NULL for the facets without a group
	 * @param facetId         the facet
	 * @param entityIds       the entities referencing the facet under the group
	 * @param disjunctive     true to create a disjunctive formula whatever the relation of the facets in the group is
	 * @return the formula of the facet in the group
	 */
	@Nonnull
	protected FacetGroupFormula createFacetGroupFormula(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer groupId,
		int facetId,
		@Nonnull Bitmap entityIds,
		boolean disjunctive
	) {
		return !disjunctive &&
			this.isFacetGroupConjunction.test(referenceSchema, groupId, WITH_DIFFERENT_FACETS_IN_GROUP) ?
			new FacetGroupAndFormula(referenceSchema.getName(), groupId, new BaseBitmap(facetId), entityIds) :
			new FacetGroupOrFormula(referenceSchema.getName(), groupId, new BaseBitmap(facetId), entityIds);
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
	 * stack tracking the user filter context (`insideUserFilter`),
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
		this.facetGroupOccurrences = null;
		this.result = null;
		this.insideUserFilter.clear();
		this.scopes.clear();
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
	 * A functional interface that resolves the one relation the facets of a group take at a level. Unlike
	 * {@link FacetGroupRelationTypeResolver}, which tests a single relation, it decides among all of them, so that
	 * the precedence of the declared relations over the default lives in one place.
	 */
	@FunctionalInterface
	public interface FacetRelationTypeResolver {

		/**
		 * Returns the relation the facets of the passed group take at the passed level.
		 *
		 * @param referenceSchemaContract the schema of the reference to which the facet group belongs
		 * @param facetGroupId            the identifier of the group; NULL for the facets without a group
		 * @param level                   the level of the facet group relation
		 * @return the relation of the facets of the group at the level
		 */
		@Nonnull
		FacetRelationType resolve(
			@Nonnull ReferenceSchemaContract referenceSchemaContract,
			@Nullable Integer facetGroupId,
			@Nonnull FacetGroupRelationLevel level
		);

	}

	/**
	 * This implementation of {@link FormulaVisitor} traverses the formula tree and replaces every found
	 * {@link MutableFormula} with a formula provided by the function for it, a new one for each occurrence - the
	 * function may tell the occurrences apart by the group each stands for. There is usually
	 * a single one, but a user filter that the scope post-processing copies into the {@link ScopeContainerFormula} of
	 * every scope holds one in each container, and a facet replaced in one of them only would leave the previous facet
	 * selected in the other scopes. The replacement is done in-place and the memoized results of all the parent
	 * formulas of each occurrence are cleared so that the new formula has chance to alter the computation result.
	 */
	@RequiredArgsConstructor
	protected static class MutableFormulaFinderAndReplacer implements FormulaVisitor {
		/**
		 * The function providing the formula that replaces each found {@link MutableFormula}.
		 */
		private final Function<MutableFormula, FacetGroupFormula> formulaToReplaceFactory;
		/**
		 * The stack of parent formulas of the currently visited formula tree.
		 */
		private final Deque<Formula> formulaStack = new ArrayDeque<>(16);
		/**
		 * The first {@link MutableFormula} found.
		 */
		@Nullable private MutableFormula target;
		/**
		 * The {@link MutableFormula} instances found after the {@link #target}, allocated only when there are any.
		 */
		@Nullable private MutableFormula[] otherTargets;
		/**
		 * The number of valid entries in {@link #otherTargets}.
		 */
		private int otherTargetCount;

		/**
		 * Returns true if at least one target {@link MutableFormula} has been found.
		 *
		 * @return True if at least one target {@link MutableFormula} has been found.
		 */
		public boolean isTargetFound() {
			return this.target != null;
		}

		/**
		 * Evaluates the lambda with the pivot of every found {@link MutableFormula} suppressed - see
		 * {@link MutableFormula#suppressPivot(BooleanSupplier)}. Suppressing it in one of them only would let the pivot
		 * of the others still contribute to the result.
		 *
		 * @param lambda the lambda to be evaluated
		 * @return the result of the lambda
		 */
		public boolean suppressPivot(@Nonnull BooleanSupplier lambda) {
			Assert.isPremiseValid(this.target != null, "Expected a MutableFormula in the formula tree!");
			return this.otherTargetCount == 0 ?
				this.target.suppressPivot(lambda) :
				this.target.suppressPivot(() -> suppressPivotOfOtherTargets(0, lambda));
		}

		@Override
		public void visit(@Nonnull Formula formula) {
			if (formula instanceof MutableFormula mutableFormula) {
				registerTarget(mutableFormula);
				mutableFormula.setDelegate(this.formulaToReplaceFactory.apply(mutableFormula));
				for (Formula parentFormula : this.formulaStack) {
					parentFormula.clearMemory();
				}
			} else {
				this.formulaStack.push(formula);
				for (Formula innerFormula : formula.getInnerFormulas()) {
					innerFormula.accept(this);
				}
				this.formulaStack.pop();
			}
		}

		/**
		 * Registers the found {@link MutableFormula} - the first one in {@link #target}, the others in
		 * {@link #otherTargets}.
		 *
		 * @param mutableFormula the found formula
		 */
		private void registerTarget(@Nonnull MutableFormula mutableFormula) {
			if (this.target == null) {
				this.target = mutableFormula;
			} else {
				if (this.otherTargets == null) {
					this.otherTargets = new MutableFormula[2];
				} else if (this.otherTargetCount == this.otherTargets.length) {
					this.otherTargets = Arrays.copyOf(this.otherTargets, this.otherTargetCount << 1);
				}
				this.otherTargets[this.otherTargetCount++] = mutableFormula;
			}
		}

		/**
		 * Evaluates the lambda with the pivot of the {@link #otherTargets} from the passed index on suppressed.
		 *
		 * @param index  the index of the first target in {@link #otherTargets} to suppress the pivot of
		 * @param lambda the lambda to be evaluated
		 * @return the result of the lambda
		 */
		@SuppressWarnings("DataFlowIssue")
		private boolean suppressPivotOfOtherTargets(int index, @Nonnull BooleanSupplier lambda) {
			final MutableFormula otherTarget = this.otherTargets[index];
			return index == this.otherTargetCount - 1 ?
				otherTarget.suppressPivot(lambda) :
				otherTarget.suppressPivot(() -> suppressPivotOfOtherTargets(index + 1, lambda));
		}

	}
}
