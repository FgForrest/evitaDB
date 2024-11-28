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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
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
 * This `inScope` filter container can be used to enclose set of filtering constraints that should be applied only when
 * searching for entities in specific scope. It has single argument of type {@link Scope} that defines the scope where
 * the enclosed filtering constraints should be applied. Consider following example:
 *
 * ```
 * filterBy(
 *    attributeEquals("code", "123"),
 *    inScope(LIVE, entityLocaleEquals(Locale.ENGLISH), attributeName("name", "LED TV")),
 *    scope(LIVE, ARCHIVED),
 * )
 * ```
 *
 * Query looks for matching entities in multiple scopes, but in archived scope the "name" attribute is not indexed and
 * cannot be used for filtering. If it's not enclosed in `inScope` container, the query would fail with exception that
 * the attribute is not indexed in ARCHIVED scope. To avoid this problem, the `inScope` container is used to limit the
 * filtering to LIVE scope only. Attribute "code" is indexed in both scopes and can be used for filtering without any
 * restrictions in this example.
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/behavioral#in-scope">Visit detailed user documentation</a></p>
 *
 * @see Scope
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inScope",
	shortDescription = "Limits enclosed filtering constraints to be applied only when searching for entities in named scope.",
	userDocsLink = "/documentation/query/filtering/behavioral#in-scope"
)
public class FilterInScope extends AbstractFilterConstraintContainer {
	@Serial private static final long serialVersionUID = -2943395408560139656L;
	private static final String CONSTRAINT_NAME = "inScope";

	@Creator
	public FilterInScope(@Nonnull Scope scope, @Nonnull @Child(domain = ConstraintDomain.ENTITY) FilterConstraint... filtering) {
		super(CONSTRAINT_NAME, new Serializable[] { scope }, filtering);
		Assert.isTrue(scope != null, "Scope must be provided!");
		Assert.isTrue(!ArrayUtils.isEmptyOrItsValuesNull(filtering), "At least one filtering constraint must be provided!");
	}

	/**
	 * Returns requested scope.
	 */
	@Nonnull
	public Scope getScope() {
		return (Scope) getArguments()[0];
	}

	/**
	 * Returns filtering constraints that should be applied when searching executed in the requested scope.
	 */
	@Nonnull
	public FilterConstraint[] getFiltering() {
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
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof Scope,
			"Constraint InScope requires exactly one argument of type Scope!"
		);
		return this;
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint InScope doesn't accept other than filtering constraints!"
		);
		return new FilterInScope(getScope(), children);
	}
}