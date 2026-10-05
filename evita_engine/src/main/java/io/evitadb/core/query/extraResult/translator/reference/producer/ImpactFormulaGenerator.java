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

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import io.evitadb.api.query.require.FacetRelationType;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS;

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
	/**
	 * Contains the groups of the facet being computed that have been found in the user filter the visitor is
	 * currently in, and whose formulas have been enriched with the facet; NULL stands for the facets without a group.
	 * The scope post-processing copies the user filter into the branch of every scope, and the user filter of each
	 * scope may select different groups, so whether the facet still has to be added to a user filter is decided by
	 * each user filter on its own - unlike {@link #facetGroupsInUserFilter}, which collects the groups of all of them.
	 */
	private final Set<Integer> groupsEnrichedInCurrentUserFilter = new HashSet<>(4);

	public ImpactFormulaGenerator(
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
		final FacetRelationType relationType = this.facetRelationType.resolve(
			referenceSchema, facetGroupId, WITH_DIFFERENT_GROUPS
		);
		final String referenceName = referenceSchema.getName();

		// if we didn't find the facet group in the user filter, we can use the generic formula of the reference
		final CacheKey key = new CacheKey(
			referenceName, relationType,
			getGroupsFoundInUserFilter(referenceName, facetGroupId, facetGroupOccurrences),
			facetGroupOccurrences.getSignature()
		);

		final Formula formula = this.cache.get(key);
		if (formula != null) {
			final MutableFormulaFinderAndReplacer mutableFormulaFinderAndReplacer = new MutableFormulaFinderAndReplacer(
				mutableFormula -> {
					// a facet with a single group shares the formula of any other such facet of the same relations,
					// a facet with more groups shares it only with facets of the very same groups
					final Integer groupId = facetGroupOccurrences.isSingleGroup() ?
						facetGroupId : mutableFormula.getFacetGroupId();
					return createFacetGroupFormula(
						referenceSchema, groupId, facetId, facetGroupOccurrences.getEntityIds(groupId), false
					);
				}
			);
			formula.accept(mutableFormulaFinderAndReplacer);
			return formula;
		} else {
			this.groupsEnrichedInCurrentUserFilter.clear();
			final Formula result = super.generateFormula(
				baseFormula, baseFormulaWithoutUserFilter, referenceSchema, facetGroupId, facetId, facetEntityIds,
				facetGroupOccurrences
			);
			// the generation may have been the first time we've seen the formula, so the facetGroupsInUserFilter
			// may not contain the referenceName yet, and we have to repeat the look-up
			final CacheKey cacheKey = new CacheKey(
				referenceName, relationType,
				getGroupsFoundInUserFilter(referenceName, facetGroupId, facetGroupOccurrences),
				facetGroupOccurrences.getSignature()
			);
			this.cache.put(cacheKey, result);
			return result;
		}
	}

	/**
	 * Returns the groups of the facet being computed that some user filter of the formula selects, as recorded in
	 * {@link #facetGroupsInUserFilter}. NULL, standing for the facets without a group, is recorded as
	 * {@link Integer#MIN_VALUE}, because an {@link IntSet} cannot hold it - we're risking that someone will have facet
	 * group with such id, but it's very unlikely.
	 *
	 * @param referenceName         the name of the faceted reference
	 * @param facetGroupId          the group of the statistics being computed
	 * @param facetGroupOccurrences the groups the facet is referenced under
	 * @return the found groups in their recorded form, or NULL when none is found
	 */
	@Nullable
	private List<Integer> getGroupsFoundInUserFilter(
		@Nonnull String referenceName,
		@Nullable Integer facetGroupId,
		@Nonnull FacetGroupOccurrences facetGroupOccurrences
	) {
		final IntSet groupsForReference = this.facetGroupsInUserFilter.get(referenceName);
		if (groupsForReference == null) {
			return null;
		}
		final List<Integer> groups;
		if (facetGroupOccurrences.isSingleGroup()) {
			groups = Collections.singletonList(facetGroupId);
		} else {
			// a scope holding no reference to the facet makes it a facet without a group there
			groups = new ArrayList<>(facetGroupOccurrences.getGroups(null));
			groups.add(null);
		}
		List<Integer> foundGroups = null;
		for (final Integer groupId : groups) {
			final int normalizedGroupId = groupId == null ? Integer.MIN_VALUE : groupId;
			if (groupsForReference.contains(normalizedGroupId)) {
				if (foundGroups == null) {
					foundGroups = new ArrayList<>(groups.size());
				}
				foundGroups.add(normalizedGroupId);
			}
		}
		return foundGroups;
	}

	@Override
	protected boolean handleFormula(@Nonnull Formula formula) {
		// if the examined formula is facet group formula matching the same facet `entityType` and `facetGroupId`
		if (isInsideUserFilter() && formula instanceof FacetGroupFormula oldFacetGroupFormula) {
			// register the facet group formu   la in the index
			final Integer oldFacetGroupId = oldFacetGroupFormula.getFacetGroupId();
			this.facetGroupsInUserFilter.computeIfAbsent(
				oldFacetGroupFormula.getReferenceName(),
				s -> new IntHashSet(16)
			).add(oldFacetGroupId == null ? Integer.MIN_VALUE : oldFacetGroupId);

			// now process it for current facet as well - in any group the facet takes part in within this user filter
			if (Objects.equals(this.referenceSchema.getName(), oldFacetGroupFormula.getReferenceName()) &&
				getFacetGroupsOfCurrentScope().contains(oldFacetGroupId)
			) {
				final MutableFormula newFacetGroupFormula = createNewFacetGroupFormula(oldFacetGroupId);
				// we found the facet group formula - we need to enrich it with new facet
				if (!this.isFacetGroupExclusivity.test(
					this.referenceSchema, oldFacetGroupId, WITH_DIFFERENT_FACETS_IN_GROUP
				)) {
					// if the facet group is not exclusive, we just combine the new facet formula with the existing formula
					newFacetGroupFormula.setPivot(oldFacetGroupFormula);
				}
				storeFormula(newFacetGroupFormula);
				this.groupsEnrichedInCurrentUserFilter.add(oldFacetGroupId);
				// we've stored the formula - instruct super method to skip it's handling
				return true;
			}
		}
		// let the upper implementation handle the formula
		return false;
	}

	@Override
	protected boolean handleUserFilter(@Nonnull Formula formula, @Nonnull Formula[] updatedChildren) {
		try {
			final List<Integer> groups = getFacetGroupsOfCurrentScope();
			// a facet of a group exclusive with the other groups deselects them, so the selection of its reference is
			// replaced by the enriched group formula even when the user filter already selects the group
			if (!isExclusiveWithOtherGroups(groups) && this.groupsEnrichedInCurrentUserFilter.containsAll(groups)) {
				// we've already enriched existing formulas with new formula - let the logic continue without
				// modification
				return false;
			} else {
				// some group formula of the facet was not inside - we have to create a brand new one and add it before
				// leaving user filter, or the selection of the reference is replaced by the facet group formulas
				return super.handleUserFilter(formula, updatedChildren);
			}
		} finally {
			// the user filters of the scopes are decided each on its own - the next one starts afresh
			this.groupsEnrichedInCurrentUserFilter.clear();
		}
	}

	@Override
	protected boolean isGroupEnrichedInCurrentUserFilter(@Nullable Integer groupId) {
		return this.groupsEnrichedInCurrentUserFilter.contains(groupId);
	}

	/**
	 * We need to calculate whether the `facetId` returns any results when other facets in the same group are removed.
	 *
	 * @param hypotheticalFormula the current formula including this facet and all other facets
	 * @param referenceSchema     the reference schema of the facet group
	 * @param facetGroupId        the facet group id
	 * @param facetId             the examined facet id
	 * @param facetEntityIds      the examined facet entity ids
	 * @return true when there is at least one result when the formula is altered in a way, that the `facetId` is requested
	 * on its own in the facet group OR formula
	 */
	public boolean hasSenseAlone(
		@Nonnull Formula hypotheticalFormula,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer facetGroupId,
		int facetId,
		@Nonnull Bitmap[] facetEntityIds
	) {
		return hasSenseAlone(
			hypotheticalFormula, referenceSchema, facetGroupId, facetId,
			FacetGroupOccurrences.singleGroup(facetGroupId, getBaseEntityIds(facetEntityIds))
		);
	}

	/**
	 * We need to calculate whether the `facetId` returns any results when other facets in the groups it takes part in
	 * are removed.
	 *
	 * A facet of a single group whose facets are joined by conjunction is answered by the hypothetical formula itself -
	 * removing the other facets of the group can only widen the facet's term. A facet taking part in several groups is
	 * always requested on its own in each of them, because the shortcut would depend on the group of the statistics
	 * being computed, and every entry of such a facet - one for each of its groups - predicts the same selection.
	 *
	 * @param hypotheticalFormula   the current formula including this facet and all other facets
	 * @param referenceSchema       the reference schema of the facet group
	 * @param facetGroupId          the facet group id of the statistics being computed
	 * @param facetId               the examined facet id
	 * @param facetGroupOccurrences the groups the facet is referenced under
	 * @return true when there is at least one result when the formula is altered in a way, that the `facetId` is
	 * requested on its own in each of its facet group OR formulas
	 */
	public boolean hasSenseAlone(
		@Nonnull Formula hypotheticalFormula,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Integer facetGroupId,
		int facetId,
		@Nonnull FacetGroupOccurrences facetGroupOccurrences
	) {
		if (facetGroupOccurrences.isSingleGroup() &&
			this.isFacetGroupConjunction.test(referenceSchema, facetGroupId, WITH_DIFFERENT_FACETS_IN_GROUP)) {
			return !hypotheticalFormula.compute().isEmpty();
		} else {
			final MutableFormulaFinderAndReplacer mutableFormulaFinderAndReplacer = new MutableFormulaFinderAndReplacer(
				mutableFormula -> {
					final Integer groupId = facetGroupOccurrences.isSingleGroup() ?
						facetGroupId : mutableFormula.getFacetGroupId();
					return createFacetGroupFormula(
						referenceSchema, groupId, facetId, facetGroupOccurrences.getEntityIds(groupId), true
					);
				}
			);
			hypotheticalFormula.accept(mutableFormulaFinderAndReplacer);
			Assert.isPremiseValid(
				mutableFormulaFinderAndReplacer.isTargetFound(), "Expected a MutableFormula in the formula tree!"
			);
			return mutableFormulaFinderAndReplacer.suppressPivot(
				() -> !hypotheticalFormula.compute().isEmpty()
			);
		}
	}

	/**
	 * Represents a cache key used for caching formula generation results. The cache key contains all key information
	 * needed to distinguish the situation when we need to analyze and create new formula composition and we can reuse
	 * the existing one and just replace one formula with another.
	 *
	 * The facet group id is set only for cache keys that represents existing facet group formulas in original formula
	 * tree inside user filter container - inside any of them, when the scope post-processing produced a user filter for
	 * each scope. The formula of such a key enriches the group formula in the user filters selecting the group and
	 * adds the facet to the others, which is the same for every facet of the group, so it serves all of them. If such
	 * formula is not found, we may reuse the generic formula of the
	 * reference, because new formula is added always at the same place with behavior driven only by negation /
	 * disjunction / conjunction combination. The place depends on the reference, though - a positive facet joins the
	 * facet selection of its own reference in the user filter - so the generic formula of one reference must not
	 * serve another.
	 *
	 * A facet referenced under several groups, or in a scope that holds no reference to it, takes part in other groups
	 * than the group of its statistics, so its formula serves only the facets of the very same groups - its key
	 * carries their signature.
	 *
	 * @param referenceName the reference name of the facet group
	 * @param relationType  the relation type of the facet group with other groups
	 * @param facetGroupIds the facet group ids of the facet found in the main formula inside a user filter, NULL
	 *                      standing for the facets without a group recorded as {@link Integer#MIN_VALUE}; null when
	 *                      none is found
	 * @param signature     the {@link FacetGroupOccurrences#getSignature() signature} of the groups of the facet, null
	 *                      for a facet with a single group everywhere
	 */
	private record CacheKey(
		@Nonnull String referenceName,
		@Nonnull FacetRelationType relationType,
		@Nullable List<Integer> facetGroupIds,
		@Nullable Object signature
	) {

		@Nonnull
		@Override
		public String toString() {
			return "CacheKey{" +
				"referenceName='" + this.referenceName + '\'' +
				", relationType=" + this.relationType +
				", facetGroupIds=" + this.facetGroupIds +
				", signature=" + this.signature +
				'}';
		}

	}

}
