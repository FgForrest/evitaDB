/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `inScope` require container restricts a set of {@link RequireConstraint} children so that they are applied
 * only when the query is processing entities in a specific {@link Scope} (either {@link Scope#LIVE} or
 * {@link Scope#ARCHIVED}). Its filter-side counterpart is
 * {@link io.evitadb.api.query.filter.FilterInScope}.
 *
 * ## Purpose and design intent
 *
 * evitaDB organises entities into separate scopes: {@link Scope#LIVE} entities are fully indexed (attributes,
 * facets, hierarchies, prices), while {@link Scope#ARCHIVED} entities reside in a reduced index that may not
 * support all require computations (e.g., facet summaries, price histograms, hierarchy trees). When a query
 * searches across multiple scopes via `scope(LIVE, ARCHIVED)`, applying a requirement that is unsupported in the
 * archived scope would ordinarily cause a query execution failure.
 *
 * `inScope` solves this by scoping each requirement to only the scope(s) where it is valid:
 *
 * ```evitaql
 * filterBy(
 *    entityScope(LIVE, ARCHIVED)
 * ),
 * require(
 *    inScope(LIVE, facetSummary())
 * )
 * ```
 *
 * With this query, the facet summary is computed only for the LIVE portion of the result, so no failure occurs
 * even though the archived scope does not maintain a facet index.
 *
 * ## Constraints
 *
 * - The `scope` argument is mandatory and must not be `null`.
 * - At least one child {@link RequireConstraint} must be provided; an empty `inScope` is rejected at construction.
 * - Additional children of different constraint types are not accepted — all children must be
 *   `RequireConstraint` instances.
 * - Children must be unique within the container (see `@Child(uniqueChildren = true)`).
 *
 * ## Applicability and necessity
 *
 * The container is applicable and necessary only when both the scope argument and at least one child are present.
 * An `inScope` with no children is not a valid state (the constructor rejects it) but can arise through
 * `getCopyWithNewChildren()` if all children are stripped away.
 *
 * ## Usage example
 *
 * ```evitaql
 * require(
 *    inScope(ARCHIVED, entityFetch(attributeContentAll()))
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/require/behavioral#in-scope)
 *
 * @see Scope
 * @see io.evitadb.api.query.filter.FilterInScope
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inScope",
	shortDescription = "The constraint limits enclosed require constraints to apply only when processing entities in a specific scope (LIVE or ARCHIVED).",
	userDocsLink = "/documentation/query/require/behavioral#in-scope"
)
public class RequireInScope extends AbstractRequireConstraintContainer implements GenericConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = 6118312763849285407L;
	private static final String CONSTRAINT_NAME = "inScope";

	@Creator
	public RequireInScope(
		@Nonnull Scope scope,
		@Nonnull @Child(uniqueChildren = true) RequireConstraint... require
	) {
		super(CONSTRAINT_NAME, new Serializable[] { scope }, require);
		Assert.isTrue(scope != null, "Scope must be provided!");
		Assert.isTrue(!ArrayUtils.isEmptyOrItsValuesNull(require), "At least one require constraint must be provided!");
	}

	private RequireInScope(
		@Nonnull Serializable[] arguments,
		@Nonnull RequireConstraint... children
	) {
		super(CONSTRAINT_NAME, arguments, children);
	}

	/**
	 * Returns requested scope.
	 */
	@Nonnull
	public Scope getScope() {
		return (Scope) getArguments()[0];
	}

	/**
	 * Returns require constraints that should be applied when searching executed in the requested scope.
	 */
	@Nonnull
	public RequireConstraint[] getRequire() {
		return getChildren();
	}

	@Override
	public boolean isNecessary() {
		return getArguments().length > 0 && getChildren().length > 0;
	}

	@Override
	public boolean isApplicable() {
		return getArguments().length > 0 && getChildren().length > 0;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof Scope,
			"Constraint InScope requires exactly one argument of type Scope!"
		);
		return new RequireInScope(newArguments, getChildren());
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint InScope doesn't accept other than require constraints!"
		);
		return new RequireInScope(new Serializable[] {getScope()}, children);
	}
}