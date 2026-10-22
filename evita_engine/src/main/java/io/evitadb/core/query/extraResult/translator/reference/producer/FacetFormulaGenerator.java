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
import io.evitadb.core.query.algebra.facet.ScopeContainerFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.extraResult.translator.reference.FilterFormulaFacetOptimizeVisitor;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
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
	 * The number of the relation types, the number of the slots of one relation within the group in the arrays of
	 * {@link #cache}.
	 */
	private static final int RELATION_TYPE_COUNT = FacetRelationType.values().length;
	/**
	 * Contains cache for already generated formulas. The key is the name of the faceted reference - the calculator
	 * serves the summaries of all the requested references, and the relations are resolved for a group of one
	 * reference, so the reference pins them together with the relations. The value holds a slot for each combination
	 * of the relation of the facets within their group and the relation of the group to the other groups - see
	 * {@link #getCacheSlot}: the shape of the formula depends on both, so two groups whose facets share a relation
	 * may still differ in the other one, and must not share one formula.
	 */
	private final Map<String, Formula[]> cache = CollectionUtils.createHashMap(16);

	public FacetFormulaGenerator(
		@Nonnull FacetRelationTypeResolver facetRelationType,
		@Nonnull FacetGroupRelationTypeResolver isFacetGroupConjunction,
		@Nonnull FacetGroupRelationTypeResolver isFacetGroupNegation,
		@Nonnull FacetGroupRelationTypeResolver isFacetGroupExclusive
	) {
		super(facetRelationType, isFacetGroupConjunction, isFacetGroupNegation, isFacetGroupExclusive);
	}

	/**
	 * Generates the count formula of the facet in its group. The formula of the first facet of the same reference and
	 * relations is generated and cached, every other one replaces the facet in it - so the groups the facet is
	 * referenced under, which only the generation reads, are created for the first facet only.
	 */
	@Nonnull
	@Override
	public Formula generateFormula(
		@Nonnull Formula baseFormula,
		@Nonnull Formula baseFormulaWithoutUserFilter,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer facetGroupId,
		int facetId,
		@Nonnull Bitmap[] facetEntityIds
	) {
		final Formula formula = getCachedFormula(referenceSchema, facetGroupId);
		return formula == null ?
			generateFormula(
				baseFormula, baseFormulaWithoutUserFilter, referenceSchema, facetGroupId, facetId, facetEntityIds,
				FacetGroupOccurrences.singleGroup(facetGroupId, getBaseEntityIds(facetEntityIds))
			) :
			replaceFacet(formula, referenceSchema, facetGroupId, facetId, getBaseEntityIds(facetEntityIds));
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
		// the count of an entry counts the facet in the group of the entry only, even when the facet is referenced
		// under several groups - the prediction of the selection taking part in all of them is the impact
		Assert.isPremiseValid(
			facetGroupOccurrences.isSingleGroup(), "The count of a facet is computed for a single group of it!"
		);
		final Formula formula = getCachedFormula(referenceSchema, facetGroupId);
		if (formula == null) {
			// the count drops the whole user filter - a user filter inside a scope container restricts only that scope,
			// and a negated user filter is subtracted, and so would be the facet replacing its contents, so the facet
			// goes to an empty user filter over the formula without the user filter instead, the same as when there is
			// none
			final Formula countedFormula = isUserFilterScoped(baseFormula) || isUserFilterNegated(baseFormula) ?
				FilterFormulaFacetOptimizeVisitor.optimize(baseFormulaWithoutUserFilter) : baseFormula;
			final Formula generatedFormula = super.generateFormula(
				countedFormula, baseFormulaWithoutUserFilter, referenceSchema, facetGroupId, facetId,
				facetEntityIds, facetGroupOccurrences
			);
			this.cache.computeIfAbsent(
				referenceSchema.getName(), referenceName -> new Formula[RELATION_TYPE_COUNT * RELATION_TYPE_COUNT]
			)[getCacheSlot(referenceSchema, facetGroupId)] = generatedFormula;
			return generatedFormula;
		} else {
			return replaceFacet(
				formula, referenceSchema, facetGroupId, facetId, facetGroupOccurrences.getEntityIds(facetGroupId)
			);
		}
	}

	/**
	 * Returns the cached count formula of the reference and relations of the passed group.
	 *
	 * @param referenceSchema the schema of the faceted reference
	 * @param facetGroupId    the group, NULL for the facets without a group
	 * @return the cached formula, NULL when none has been generated yet
	 */
	@Nullable
	private Formula getCachedFormula(@Nonnull ReferenceSchemaContract referenceSchema, @Nullable Integer facetGroupId) {
		final Formula[] formulasOfReference = this.cache.get(referenceSchema.getName());
		return formulasOfReference == null ? null : formulasOfReference[getCacheSlot(referenceSchema, facetGroupId)];
	}

	/**
	 * Returns the slot of the formula of the passed group in the array of its reference in {@link #cache} - one for
	 * each combination of the relation of the facets within the group and the relation of the group to the other
	 * groups.
	 *
	 * @param referenceSchema the schema of the faceted reference
	 * @param facetGroupId    the group, NULL for the facets without a group
	 * @return the slot
	 */
	private int getCacheSlot(@Nonnull ReferenceSchemaContract referenceSchema, @Nullable Integer facetGroupId) {
		return this.facetRelationType.resolve(referenceSchema, facetGroupId, WITH_DIFFERENT_FACETS_IN_GROUP).ordinal() *
			RELATION_TYPE_COUNT +
			this.facetRelationType.resolve(referenceSchema, facetGroupId, WITH_DIFFERENT_GROUPS).ordinal();
	}

	/**
	 * Replaces the facet in the cached count formula with the passed one.
	 *
	 * @param formula         the cached count formula of the reference and relations of the facet
	 * @param referenceSchema the schema of the faceted reference
	 * @param facetGroupId    the group of the facet, NULL for the facets without a group
	 * @param facetId         the facet
	 * @param entityIds       the entities referencing the facet under the group
	 * @return the passed formula with the facet replaced
	 */
	@Nonnull
	private Formula replaceFacet(
		@Nonnull Formula formula,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer facetGroupId,
		int facetId,
		@Nonnull Bitmap entityIds
	) {
		final MutableFormulaFinderAndReplacer mutableFormulaFinderAndReplacer = new MutableFormulaFinderAndReplacer(
			mutableFormula -> createFacetGroupFormula(referenceSchema, facetGroupId, facetId, entityIds, false)
		);
		formula.accept(mutableFormulaFinderAndReplacer);
		Assert.isPremiseValid(
			mutableFormulaFinderAndReplacer.isTargetFound(), "Expected a MutableFormula in the formula tree!"
		);
		return formula;
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
		if (this.result == baseFormula) {
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
		} else {
			// output changed - just propagate it
			return this.result;
		}
	}

}
