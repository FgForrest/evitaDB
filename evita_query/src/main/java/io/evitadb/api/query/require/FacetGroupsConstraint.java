/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.filter.FilterBy;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Shared interface for all require constraints that override the *default Boolean relation* between selected facets
 * within or across facet groups of a specific reference type.
 *
 * By default, evitaDB treats selected facets within the same group as *disjunctive* (OR — any of the selected
 * values matches) and facets across different groups as *conjunctive* (AND — all group conditions must be met).
 * The `FacetGroupsConstraint` implementations allow this default to be changed on a per-reference, per-group
 * basis and for two distinct *levels* of interaction ({@link FacetGroupRelationLevel}):
 *
 * - `WITH_DIFFERENT_FACETS_IN_GROUP` — controls the relation between multiple selected facets inside one group
 * - `WITH_DIFFERENT_GROUPS` — controls the relation between results from different facet groups
 *
 * Concrete implementations that override the relation type:
 * - {@link FacetGroupsConjunction} — within or across groups, all selected facets must match (AND)
 * - {@link FacetGroupsDisjunction} — across groups, any group's condition is enough (OR)
 * - {@link FacetGroupsNegation} — the selected group(s) contribute a *negation* of the normal facet filter
 * - {@link FacetGroupsExclusivity} — within a group, selecting one facet automatically deselects others (radio logic)
 *
 * The target groups are identified via `getReferenceName()` (the reference type the facets belong to) combined
 * with `getFacetGroups()` — an optional `FilterBy` constraint that resolves to a set of group primary keys. When
 * `getFacetGroups()` is empty/absent, the constraint applies to all groups of that reference type.
 *
 * These constraints have no effect on the primary entity result set; they only influence the computation of
 * {@link FacetSummary} extra results and the counting/impact calculations within `UserFilter` evaluation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface FacetGroupsConstraint extends Constraint<RequireConstraint>, RequireConstraint, FacetConstraint<RequireConstraint> {

	/**
	 * Returns the name of the reference this constraint relates to.
	 *
	 * @return the reference name
	 */
	@Nonnull
	String getReferenceName();

	/**
	 * Returns the level at which this relation type is applied.
	 *
	 * @return the facet group relation level
	 */
	@AliasForParameter("relation")
	@Nonnull
	FacetGroupRelationLevel getFacetGroupRelationLevel();

	/**
	 * Returns the filter constraint that can be resolved to an array of facet group primary keys.
	 *
	 * @return optional filter constraint for facet groups
	 */
	@AliasForParameter("filterBy")
	@Nonnull
	Optional<FilterBy> getFacetGroups();

}
