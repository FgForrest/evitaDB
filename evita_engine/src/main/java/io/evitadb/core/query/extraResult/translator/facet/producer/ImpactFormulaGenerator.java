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

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.facet.FacetGroupAndFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupOrFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

import static java.util.Optional.ofNullable;

/**
 * This implementation contains the heavy part of {@link ImpactCalculator} interface implementation. It computes
 * {@link RequestImpact} data for each facet that is assigned to any of the product matching current
 * {@link EvitaRequest}. The impact captures the situation how many entities would be added/removed if the facet had
 * been selected.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class ImpactFormulaGenerator extends AbstractFacetFormulaGenerator {
	/**
	 * Contains cache for already generated formulas indexed by a {@link CacheKey} that distinguishes the key situations
	 * where the formulas have to have different shape and structure.
	 */
	private final Map<CacheKey, Formula> cache = CollectionUtils.createHashMap(64);
	/**
	 * This map stores the primary keys of all facet groups that were found in the existing formula by the visitor.
	 * The keys of the map are the `referenceName` of the facet groups, and the values are sets of `facetGroupId` that were found.
	 * The `UserFilterFormula` is used to find these facet groups in the existing formula.
	 */
	private final Map<String, IntSet> facetGroupsInUserFilter = CollectionUtils.createHashMap(16);

	public ImpactFormulaGenerator(
		@Nonnull BiPredicate<ReferenceSchemaContract, Integer> isFacetGroupConjunction,
		@Nonnull BiPredicate<ReferenceSchemaContract, Integer> isFacetGroupDisjunction,
		@Nonnull BiPredicate<ReferenceSchemaContract, Integer> isFacetGroupNegation
	) {
		super(isFacetGroupConjunction, isFacetGroupDisjunction, isFacetGroupNegation);
	}

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
		final boolean negation = this.isFacetGroupNegation.test(referenceSchema, facetGroupId);
		final boolean disjunction = this.isFacetGroupDisjunction.test(referenceSchema, facetGroupId);
		final boolean conjunction = this.isFacetGroupConjunction.test(referenceSchema, facetGroupId);

		final String referenceName = referenceSchema.getName();
		// when facetGroupId is null, we use Integer.MIN_VALUE as a placeholder because IntSet can't work with nulls
		// we're risking that someone will have facet group with such id, but it's very unlikely
		final Integer normalizedFacetGroupId = ofNullable(facetGroupId).orElse(Integer.MIN_VALUE);
		boolean found = ofNullable(this.facetGroupsInUserFilter.get(referenceName))
			.map(it -> it.contains(normalizedFacetGroupId))
			.orElse(false);

		// if we didn't find the facet group in the user filter, we can use the generic formula
		final CacheKey key = found ?
			new CacheKey(referenceName, negation, disjunction, conjunction, normalizedFacetGroupId) :
			new CacheKey(null, negation, disjunction, conjunction, null);

		final Formula formula = cache.get(key);
		if (formula != null) {
			final Bitmap facetEntityIdsBitmap = getBaseEntityIds(facetEntityIds);
			final MutableFormulaFinderAndReplacer mutableFormulaFinderAndReplacer = new MutableFormulaFinderAndReplacer(
				() -> conjunction ?
					new FacetGroupAndFormula(referenceName, facetGroupId, new BaseBitmap(facetId), facetEntityIdsBitmap) :
					new FacetGroupOrFormula(referenceName, facetGroupId, new BaseBitmap(facetId), facetEntityIdsBitmap)
			);
			formula.accept(mutableFormulaFinderAndReplacer);
			return formula;
		} else {
			final Formula result = super.generateFormula(
				baseFormula, baseFormulaWithoutUserFilter, referenceSchema, facetGroupId, facetId, facetEntityIds
			);
			// the generation may have been the first time we've seen the formula, so the facetGroupsInUserFilter
			// may not contain the referenceName yet and we have to repeat the look-up
			boolean foundAtLast = ofNullable(this.facetGroupsInUserFilter.get(referenceName))
				.map(it -> it.contains(normalizedFacetGroupId))
				.orElse(false);
			final CacheKey cacheKey = new CacheKey(
				foundAtLast ? referenceName : null, negation, disjunction, conjunction, foundAtLast ? facetGroupId : null
			);
			this.cache.put(cacheKey, result);
			return result;
		}
	}

	@Override
	protected boolean handleFormula(@Nonnull Formula formula) {
		// if the examined formula is facet group formula matching the same facet `entityType` and `facetGroupId`
		if (isInsideUserFilter() && formula instanceof FacetGroupFormula oldFacetGroupFormula) {
			// register the facet group formula in the index
			this.facetGroupsInUserFilter.computeIfAbsent(
				oldFacetGroupFormula.getReferenceName(),
				s -> new IntHashSet(16)
			).add(ofNullable(oldFacetGroupFormula.getFacetGroupId()).orElse(Integer.MIN_VALUE));

			// now process it for current facet as well
			if (Objects.equals(referenceSchema.getName(), oldFacetGroupFormula.getReferenceName()) &&
				Objects.equals(facetGroupId, oldFacetGroupFormula.getFacetGroupId())
			) {
				final MutableFormula newFacetGroupFormula = createNewFacetGroupFormula();
				// we found the facet group formula - we need to enrich it with new facet
				newFacetGroupFormula.setPivot(oldFacetGroupFormula);
				storeFormula(newFacetGroupFormula);
				// we've stored the formula - instruct super method to skip it's handling
				return true;
			}
		}
		// let the upper implementation handle the formula
		return false;
	}

	@Override
	protected boolean handleUserFilter(@Nonnull Formula formula, @Nonnull Formula[] updatedChildren) {
		final Boolean wasFoundInTheUserFilter = ofNullable(this.facetGroupsInUserFilter.get(referenceSchema.getName()))
			.map(it -> it.contains(ofNullable(facetGroupId).orElse(Integer.MIN_VALUE)))
			.orElse(false);

		if (wasFoundInTheUserFilter) {
			// we've already enriched existing formula with new formula - let the logic continue without modification
			return false;
		} else {
			// there was no FacetGroupFormula inside - we have to create a brand new one and add it before leaving user filter
			return super.handleUserFilter(formula, updatedChildren);
		}
	}

	/**
	 * Represents a cache key used for caching formula generation results. The cache key contains all key information
	 * needed to distinguish the situation when we need to analyze and create new formula composition and we can reuse
	 * the existing one and just replace one formula with another.
	 *
	 * The reference name and the facet group id are set only for cache keys that represents existing facet group
	 * formulas in original formula tree inside user filter container. If such formula is not found, we may reuse
	 * the generic formula, because new formula is added always at the same place with behavior driven only by
	 * negation / disjunction / conjunction combination.
	 *
	 * @param referenceName the reference name of the facet group
	 * @param isConjunction true if the facet group is conjunction
	 * @param isDisjunction true if the facet group is disjunction
	 * @param isNegation    true if the facet group is negation
	 * @param facetGroupId  the facet group id - non-null only if the formula for particular facet group is found in
	 *                      the main formula
	 */
	private record CacheKey(
		@Nullable String referenceName,
		boolean isNegation,
		boolean isDisjunction,
		boolean isConjunction,
		@Nullable Integer facetGroupId
	) {

		@Override
		public String toString() {
			return "CacheKey{" +
				"referenceName='" + referenceName + '\'' +
				", isNegation=" + isNegation +
				", isDisjunction=" + isDisjunction +
				", isConjunction=" + isConjunction +
				", facetGroupId=" + facetGroupId +
				'}';
		}

	}

}
