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

import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * The `facetCalculationRules` requirement sets **query-wide default relation types** for the two independent levels
 * at which evitaDB combines selected facets. It is the global counterpart to the per-reference, per-group constraints
 * ({@link FacetGroupsConjunction}, {@link FacetGroupsDisjunction}, {@link FacetGroupsNegation},
 * {@link FacetGroupsExclusivity}): it affects all faceted references and groups in the query, while the per-group
 * constraints take precedence for the specific groups they target.
 *
 * Without this constraint the built-in defaults are:
 *
 * - facets **within the same group** → `DISJUNCTION` (logical OR)
 * - facets **across different groups or references** → `CONJUNCTION` (logical AND)
 *
 * ## Arguments
 *
 * Both arguments are mandatory and must be values of {@link FacetRelationType}:
 *
 * - **facetsWithSameGroup** — the default relation applied to facets **within** the same group; null defaults to
 *   `DISJUNCTION`
 * - **facetsWithDifferentGroups** — the default relation applied to facets **across** different groups or references;
 *   null defaults to `CONJUNCTION`
 *
 * Available relation types:
 *
 * - `DISJUNCTION` — logical OR; any selected option is sufficient (more results)
 * - `CONJUNCTION` — logical AND; all selected options must be present (fewer results)
 * - `NEGATION` — logical NOT; entities with the selected facet are **excluded**
 * - `EXCLUSIVITY` — at most one option can be active at this level; impact predictions treat selection as
 *   replacement rather than addition
 *
 * ## Scope
 *
 * This constraint is scoped to the single query in which it appears. It does not persist across queries.
 *
 * Per-reference constraints ({@link FacetGroupsConjunction} etc.) always override the rules set here for the
 * specific groups they target.
 *
 * ## Example — AND within groups, OR across groups
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     require(
 *         facetSummary(IMPACT),
 *         facetCalculationRules(CONJUNCTION, DISJUNCTION)
 *     )
 * )
 * ```
 *
 * ## Example — conjunction within groups, exclusivity across groups
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     require(
 *         facetSummary(IMPACT),
 *         facetCalculationRules(CONJUNCTION, EXCLUSIVITY)
 *     )
 * )
 * ```
 *
 * This is equivalent to applying `facetGroupsConjunction` and `facetGroupsExclusivity` (without a filterBy) to every
 * faceted reference in the query.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-calculation-rules)
 *
 * @see FacetRelationType
 * @see FacetGroupsConjunction
 * @see FacetGroupsDisjunction
 * @see FacetGroupsNegation
 * @see FacetGroupsExclusivity
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ConstraintDefinition(
	name = "calculationRules",
	shortDescription = "The constraint sets query-wide default facet relation types (conjunction, disjunction, negation, exclusivity) for intra-group and inter-group levels.",
	userDocsLink = "/documentation/query/requirements/facet#facet-calculation-rules"
)
public class FacetCalculationRules extends AbstractRequireConstraintLeaf implements FacetConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = -5431884195676521481L;

	private FacetCalculationRules(@Nonnull Serializable[] arguments) {
		super(arguments);
	}

	@Creator
	public FacetCalculationRules(
		@Nullable FacetRelationType facetsWithSameGroup,
		@Nullable FacetRelationType facetsWithDifferentGroups
	) {
		super(
			facetsWithSameGroup != null ? facetsWithSameGroup : FacetRelationType.DISJUNCTION,
			facetsWithDifferentGroups != null ? facetsWithDifferentGroups : FacetRelationType.CONJUNCTION);
	}

	/**
	 * Returns type of relation that should be applied on facets within the same group by default.
	 */
	@AliasForParameter("facetsWithSameGroup")
	@Nonnull
	public FacetRelationType getFacetsWithSameGroupRelationType() {
		return (FacetRelationType) getArguments()[0];
	}

	/**
	 * Returns type of relation that should be applied on facets among different groups or relations by default.
	 */
	@AliasForParameter("facetsWithDifferentGroups")
	@Nonnull
	public FacetRelationType getFacetsWithDifferentGroupsRelationType() {
		return (FacetRelationType) getArguments()[1];
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(newArguments.length == 2, "FacetCalculationRules requires exactly two arguments.");
		Assert.isPremiseValid(
			Arrays.stream(newArguments).allMatch(FacetRelationType.class::isInstance),
			"All arguments must be of type FacetRelationType."
		);
		return new FacetCalculationRules(newArguments);
	}

}