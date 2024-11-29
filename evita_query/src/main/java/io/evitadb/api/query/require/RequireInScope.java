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
 * This `inScope` require container can be used to enclose set of require constraints that should be applied only when
 * searching for entities in specific scope. It has single argument of type {@link Scope} that defines the scope where
 * the enclosed require constraints should be applied. Consider following example:
 *
 * ```
 * filterBy(
 *    scope(LIVE, ARCHIVED),
 * ),
 * require(
 *    inScope(LIVE, facetSummary())
 * )
 * ```
 *
 * Query looks for matching entities in multiple scopes, but in archived scope facet index is not maintained. If it's
 * not enclosed in `inScope` container, the query would fail with exception that the facets are not available in ARCHIVED
 * scope. To avoid this problem, the `inScope` container is used to limit the require to LIVE scope only.
 *
 * <p><a href="https://evitadb.io/documentation/query/require/behavioral#in-scope">Visit detailed user documentation</a></p>
 *
 * @see Scope
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inScope",
	shortDescription = "Limits enclosed require constraints to be applied only for entities in named scope.",
	userDocsLink = "/documentation/query/require/behavioral#in-scope"
)
public class RequireInScope extends AbstractRequireConstraintContainer implements GenericConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = 6118312763849285407L;
	private static final String CONSTRAINT_NAME = "inScope";

	@Creator
	public RequireInScope(@Nonnull Scope scope, @Nonnull @Child(uniqueChildren = true) RequireConstraint... require) {
		super(CONSTRAINT_NAME, new Serializable[] { scope }, require);
		Assert.isTrue(scope != null, "Scope must be provided!");
		Assert.isTrue(!ArrayUtils.isEmptyOrItsValuesNull(require), "At least one require constraint must be provided!");
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
		return this;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint InScope doesn't accept other than require constraints!"
		);
		return new RequireInScope(getScope(), children);
	}
}