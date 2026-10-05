/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The groups one facet is referenced under, which the reference summary needs to predict the result of selecting
 * the facet. The group is a property of a reference rather than of the facet, so a facet may be referenced under
 * several groups - without a group included - and {@link FacetHaving} selects it in each of them. A scope that holds
 * no reference to the facet gives it no group, and the facet is a facet without a group there. The reference summary
 * lists such a facet once in each of its groups. Each of these entries counts the entities referencing the facet under
 * its own group, but all of them predict the same selection - the impact of selecting the facet in every group.
 *
 * The groups are kept for each scope of the query, because the scope post-processing copies a user filter into the
 * branch of every scope and each copy composes the facet the way its own scope does, and for the query as a whole,
 * for a user filter that is not copied. The entities referencing the facet are kept for each group.
 *
 * The reference summary resolves the occurrences once for the facet and shares them by all of its entries, which
 * share the prediction of the selection through them as well - the impact is computed for the first entry asking and
 * reused by the others. The instance is not thread safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class FacetGroupOccurrences {
	/**
	 * The groups of a facet no reference gives a group to - the facets without a group.
	 */
	private static final List<Integer> WITHOUT_GROUP = Collections.singletonList(null);
	/**
	 * The groups of the facet in each scope of the query, NULL standing for the facets without a group; NULL when the
	 * facet has the same single group in every scope - see {@link #isSingleGroup()}.
	 */
	@Nullable private final Map<Scope, List<Integer>> groupsByScope;
	/**
	 * The groups of the facet in any scope of the query, in the order of their first occurrence.
	 */
	@Nonnull private final List<Integer> groupsInQuery;
	/**
	 * The entities referencing the facet under each of its groups, the NULL key standing for the references without
	 * a group.
	 */
	@Nonnull private final Map<Integer, Bitmap> entityIdsByGroup;
	/**
	 * The impact of adding the facet to the selection, shared by all the entries of the facet; valid only when
	 * {@link #impactComputed} is true - the impact is NULL when it is not requested.
	 */
	@Nullable private RequestImpact impact;
	/**
	 * True once {@link #impact} has been computed.
	 */
	private boolean impactComputed;

	/**
	 * Creates the occurrences of a facet referenced under the passed single group in every scope of the query.
	 *
	 * @param groupId   the group, NULL for the facets without a group
	 * @param entityIds the entities referencing the facet
	 * @return the occurrences
	 */
	@Nonnull
	public static FacetGroupOccurrences singleGroup(@Nullable Integer groupId, @Nonnull Bitmap entityIds) {
		final Map<Integer, Bitmap> entityIdsByGroup = new HashMap<>(2);
		entityIdsByGroup.put(groupId, entityIds);
		return new FacetGroupOccurrences(null, Collections.singletonList(groupId), entityIdsByGroup);
	}

	/**
	 * Creates the occurrences of a facet.
	 *
	 * @param groupsByScope    the groups of the facet in each scope of the query, NULL standing for the facets without
	 *                         a group, an empty list for a scope holding no reference to the facet; NULL when the facet
	 *                         has the same single group in every scope
	 * @param groupsInQuery    the groups of the facet in any scope of the query
	 * @param entityIdsByGroup the entities referencing the facet under each of its groups
	 */
	public FacetGroupOccurrences(
		@Nullable Map<Scope, List<Integer>> groupsByScope,
		@Nonnull List<Integer> groupsInQuery,
		@Nonnull Map<Integer, Bitmap> entityIdsByGroup
	) {
		this.groupsByScope = groupsByScope;
		this.groupsInQuery = groupsInQuery;
		this.entityIdsByGroup = entityIdsByGroup;
	}

	/**
	 * Returns true if the facet has the same single group in every scope of the query, so that every user filter
	 * composes it into that group, whatever scope it restricts.
	 *
	 * @return true if the facet has a single group everywhere
	 */
	public boolean isSingleGroup() {
		return this.groupsByScope == null;
	}

	/**
	 * Returns the groups the facet takes part in when selected in a user filter restricting the passed scope, or in
	 * a user filter of the whole query. A facet the scope holds no reference to is a facet without a group there.
	 *
	 * @param scope the scope the user filter restricts, NULL for a user filter of the whole query
	 * @return the groups, NULL standing for the facets without a group; never empty
	 */
	@Nonnull
	public List<Integer> getGroups(@Nullable Scope scope) {
		final List<Integer> groups = scope == null || this.groupsByScope == null ?
			this.groupsInQuery : this.groupsByScope.get(scope);
		return groups == null || groups.isEmpty() ? WITHOUT_GROUP : groups;
	}

	/**
	 * Returns the entities referencing the facet under the passed group.
	 *
	 * @param groupId the group, NULL for the references without a group
	 * @return the entities, empty when no reference to the facet has the group
	 */
	@Nonnull
	public Bitmap getEntityIds(@Nullable Integer groupId) {
		final Bitmap entityIds = this.entityIdsByGroup.get(groupId);
		return entityIds == null ? EmptyBitmap.INSTANCE : entityIds;
	}

	/**
	 * Returns the value telling apart the facets whose formulas have a different shape: the groups the facet takes
	 * part in for each scope and for the whole query. Two facets with equal signatures have their terms at the same
	 * places of the formula, in the same groups.
	 *
	 * @return the signature, NULL for a facet with a single group everywhere - its formula has the shape of every
	 * other facet with a single group of the same relations
	 */
	@Nullable
	public Object getSignature() {
		return this.groupsByScope == null ? null : List.of(this.groupsByScope, this.groupsInQuery);
	}

	/**
	 * Returns the impact of adding the facet to the selection, computed by the passed function for the first entry of
	 * the facet asking - every entry of the facet, one for each group it is listed in, predicts the same selection.
	 *
	 * @param impactComputation computes the impact, NULL when the impact is not requested
	 * @return the impact, NULL when it is not requested
	 */
	@Nullable
	RequestImpact computeImpactIfAbsent(@Nonnull Supplier<RequestImpact> impactComputation) {
		if (!this.impactComputed) {
			this.impact = impactComputation.get();
			this.impactComputed = true;
		}
		return this.impact;
	}

	@Override
	public String toString() {
		return "FacetGroupOccurrences{" +
			"groupsByScope=" + this.groupsByScope +
			", groupsInQuery=" + this.groupsInQuery +
			'}';
	}

	/**
	 * Resolves the occurrences of a facet the reference summary computes statistics for.
	 */
	@FunctionalInterface
	public interface Resolver {

		/**
		 * Returns the occurrences of the facet.
		 *
		 * @param referenceSchema the schema of the faceted reference
		 * @param facetId         the facet
		 * @param facetGroupId    the group of the statistics being computed, NULL for the facets without a group
		 * @param facetEntityIds  the entities referencing the facet under that group
		 * @return the occurrences of the facet
		 */
		@Nonnull
		FacetGroupOccurrences resolve(
			@Nonnull ReferenceSchemaContract referenceSchema,
			int facetId,
			@Nullable Integer facetGroupId,
			@Nonnull Bitmap facetEntityIds
		);

	}

}
