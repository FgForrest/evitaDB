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

import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.query.require.FacetRelationType;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.facet.ScopeContainerFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.extraResult.translator.reference.FilterFormulaFacetOptimizeVisitor;
import io.evitadb.core.query.filter.translator.facet.FacetHavingTranslator;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS;

/**
 * This implementation contains the heavy part of {@link FacetCalculator} interface implementation. It computes how many
 * entities posses the specified facet respecting current {@link EvitaRequest} filtering query except contents
 * of the {@link UserFilter}. It means that it respects all mandatory filtering constraints which gets enriched by
 * additional query that represents single facet. The result of the query represents the number of products having
 * such facet.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class FacetFormulaGenerator extends AbstractFacetFormulaGenerator {
	/**
	 * Contains cache for already generated formulas indexed by a {@link CacheKey} that distinguishes the key
	 * situations where the formulas have to have different shape and structure.
	 */
	private final Map<CacheKey, Formula> cache = CollectionUtils.createHashMap(64);

	public FacetFormulaGenerator(
		@Nonnull FacetRelationTypeResolver facetRelationType,
		@Nonnull FacetGroupRelationTypeResolver isFacetGroupConjunction,
		@Nonnull FacetGroupRelationTypeResolver isFacetGroupNegation,
		@Nonnull FacetGroupRelationTypeResolver isFacetGroupExclusive
	) {
		super(facetRelationType, isFacetGroupConjunction, isFacetGroupNegation, isFacetGroupExclusive);
	}

	@Nonnull
	@Override
	public Formula generateFormula(
		@Nonnull Formula baseFormula,
		@Nonnull Formula baseFormulaWithoutUserFilter,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer facetGroupId,
		int facetId,
		@Nonnull Bitmap[] facetEntityIds,
		@Nonnull FacetGroupOccurrences facetGroupOccurrences
	) {
		// the shape of the formula depends on the relation of the group to the other groups as well - two groups whose
		// facets share a relation may still differ in it, and must not share one formula; a facet taking part in
		// several groups shares it only with the facets of the very same groups of the very same reference, because
		// the relations of those groups are the relations of that reference
		final CacheKey key = new CacheKey(
			referenceSchema.getName(),
			this.facetRelationType.resolve(referenceSchema, facetGroupId, WITH_DIFFERENT_FACETS_IN_GROUP),
			this.facetRelationType.resolve(referenceSchema, facetGroupId, WITH_DIFFERENT_GROUPS),
			facetGroupOccurrences.getSignature()
		);
		return this.cache.compute(
			key,
			(cacheKey, formula) -> {
				if (formula == null) {
					// the count drops the whole user filter - a user filter inside a scope container restricts only
					// that scope, and a negated user filter is subtracted, and so would be the facet replacing its
					// contents, so the facet goes to an empty user filter over the formula without the user filter
					// instead, the same as when there is none
					final Formula countedFormula = isUserFilterScoped(baseFormula) || isUserFilterNegated(baseFormula) ?
						FilterFormulaFacetOptimizeVisitor.optimize(baseFormulaWithoutUserFilter) : baseFormula;
					return super.generateFormula(
						countedFormula, baseFormulaWithoutUserFilter, referenceSchema, facetGroupId, facetId,
						facetEntityIds, facetGroupOccurrences
					);
				} else {
					final MutableFormulaFinderAndReplacer mutableFormulaFinderAndReplacer = new MutableFormulaFinderAndReplacer(
						mutableFormula -> {
							final Integer groupId = facetGroupOccurrences.isSingleGroup() ?
								facetGroupId : mutableFormula.getFacetGroupId();
							return createFacetGroupFormula(
								referenceSchema, groupId, facetId, facetGroupOccurrences.getEntityIds(groupId), false
							);
						}
					);
					formula.accept(mutableFormulaFinderAndReplacer);
					Assert.isPremiseValid(
						mutableFormulaFinderAndReplacer.isTargetFound(), "Expected a MutableFormula in the formula tree!"
					);
					return formula;
				}
			}
		);
	}

	/**
	 * Returns true if a {@link UserFilterFormula} of the formula sits inside a {@link ScopeContainerFormula} - the
	 * inScope post-processor puts each scope of the query into one, so such a user filter restricts the scopes whose
	 * containers hold it rather than the whole formula.
	 *
	 * @param formula the base formula of the facet computation
	 * @return true if the user filter of the formula is placed in a scope container
	 */
	private static boolean isUserFilterScoped(@Nonnull Formula formula) {
		return FormulaFinder.find(formula, ScopeContainerFormula.class, LookUp.SHALLOW)
			.stream()
			.anyMatch(it -> !FormulaFinder.find(it, UserFilterFormula.class, LookUp.SHALLOW).isEmpty());
	}

	/**
	 * Returns true if a {@link UserFilterFormula} of the formula sits in the subtracted part of a {@link NotFormula} -
	 * a user filter negated by the user, `not(userFilter(...))` - so the formula subtracts the user filter rather than
	 * restricting the result to it. A user filter in the superset part of a {@link NotFormula} - next to a negated
	 * constraint - still restricts the result, the negated constraint is subtracted from it.
	 *
	 * @param formula the base formula of the facet computation
	 * @return true if the user filter of the formula is subtracted
	 */
	private static boolean isUserFilterNegated(@Nonnull Formula formula) {
		return FormulaFinder.find(formula, NotFormula.class, LookUp.DEEP)
			.stream()
			.anyMatch(it -> !FormulaFinder.find(it.getSubtractedFormula(), UserFilterFormula.class, LookUp.SHALLOW).isEmpty());
	}

	@Override
	protected boolean shouldIncludeChildren(boolean isUserFilter) {
		// this implementation skips former contents of the user filter formula
		// (it just adds single new facet formula for the computation
		return !isUserFilter;
	}

	@Nonnull
	@Override
	protected Formula getResult(@Nonnull Formula baseFormula) {
		Assert.isPremiseValid(this.result != null, "Result formula must be set!");
		// if the output is same as input, it means the input didn't contain UserFilterFormula
		if (this.result == baseFormula && this.facetGroupOccurrences.isSingleGroup()) {
			// so we need to change it here adding new facet group formula
			if (this.isFacetGroupNegation.test(this.referenceSchema, this.facetGroupId, WITH_DIFFERENT_FACETS_IN_GROUP)) {
				return FormulaFactory.not(
					createNewFacetGroupFormula(),
					baseFormula
				);
			} else {
				return FormulaFactory.and(
					baseFormula,
					createNewFacetGroupFormula()
				);
			}
		} else if (this.result == baseFormula) {
			// a facet taking part in several groups is selected in each of them, composed the way the result of
			// selecting it composes the groups of one reference - the negated groups are subtracted from the rest
			final List<Formula> positiveFormulas = new ArrayList<>(4);
			final List<Formula> negatedFormulas = new ArrayList<>(4);
			for (final Integer groupId : this.facetGroupOccurrences.getGroups(null)) {
				final MutableFormula groupFormula = createNewFacetGroupFormula(groupId);
				final FacetRelationType relationType = this.facetRelationType.resolve(
					this.referenceSchema, groupId, WITH_DIFFERENT_GROUPS
				);
				if (relationType == FacetRelationType.NEGATION) {
					negatedFormulas.add(groupFormula);
				} else {
					positiveFormulas.add(groupFormula);
				}
			}
			final Formula positiveFormula = positiveFormulas.isEmpty() ?
				baseFormula :
				FormulaFactory.and(
					baseFormula,
					FacetHavingTranslator.composeFacetSelectionFormula(
						this.referenceSchema.getName(),
						positiveFormulas,
						it -> this.facetRelationType.resolve(
							this.referenceSchema, ((MutableFormula) it).getFacetGroupId(), WITH_DIFFERENT_GROUPS
						)
					)
				);
			return negatedFormulas.isEmpty() ?
				positiveFormula :
				FormulaFactory.not(FormulaFactory.or(negatedFormulas.toArray(Formula[]::new)), positiveFormula);
		} else {
			// output changed - just propagate it
			return this.result;
		}
	}

	/**
	 * Key of the {@link #cache}: the relations that decide the shape of the generated formula. The calculator serves
	 * the summaries of all the requested references, and the relations are resolved for a group of one reference - a
	 * facet taking part in several groups is composed by the relations of all of them, so the reference and the
	 * signature together pin the relations of every group in the formula, not only those of the group of the
	 * statistics.
	 *
	 * @param referenceName     the name of the faceted reference
	 * @param facetRelationType the relation of the facets within their group
	 * @param groupRelationType the relation of the group to the other groups
	 * @param signature         the {@link FacetGroupOccurrences#getSignature() signature} of the groups of the facet,
	 *                          null for a facet with a single group everywhere
	 */
	private record CacheKey(
		@Nonnull String referenceName,
		@Nonnull FacetRelationType facetRelationType,
		@Nonnull FacetRelationType groupRelationType,
		@Nullable Object signature
	) {

	}

}
