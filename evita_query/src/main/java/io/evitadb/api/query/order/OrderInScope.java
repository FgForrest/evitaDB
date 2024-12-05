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

package io.evitadb.api.query.order;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This `inScope` order container can be used to enclose set of ordering constraints that should be applied only when
 * searching for entities in specific scope. It has single argument of type {@link Scope} that defines the scope where
 * the enclosed ordering constraints should be applied. Consider following example:
 *
 * ```
 * filterBy(
 *    scope(LIVE, ARCHIVED),
 * ),
 * orderBy(
 *    inScope(LIVE, attributeNatural("name", ASC")),
 *    attributeNatural("code", DESC),
 * )
 * ```
 *
 * Query looks for matching entities in multiple scopes, but in archived scope the "name" attribute is not indexed and
 * cannot be used for ordering. If it's not enclosed in `inScope` container, the query would fail with exception that
 * the attribute is not indexed in ARCHIVED scope. To avoid this problem, the `inScope` container is used to limit the
 * ordering to LIVE scope only. Attribute "code" is indexed in both scopes and can be used for ordering without any
 * restrictions in this example.
 *
 * <p><a href="https://evitadb.io/documentation/query/ordering/behavioral#in-scope">Visit detailed user documentation</a></p>
 *
 * @see Scope
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inScope",
	shortDescription = "Limits enclosed ordering constraints to be applied only when sorting entities in named scope.",
	userDocsLink = "/documentation/query/ordering/behavioral#in-scope",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE }
)
public class OrderInScope extends AbstractOrderConstraintContainer implements GenericConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = 9023638910417966280L;
	private static final String CONSTRAINT_NAME = "inScope";

	@Creator
	public OrderInScope(@Nonnull Scope scope, @Nonnull OrderConstraint... ordering) {
		super(CONSTRAINT_NAME, new Serializable[] { scope }, ordering);
		Assert.isTrue(scope != null, "Scope must be provided!");
		Assert.isTrue(!ArrayUtils.isEmptyOrItsValuesNull(ordering), "At least one ordering constraint must be provided!");
	}

	private OrderInScope(@Nonnull Serializable[] arguments, @Nonnull OrderConstraint... children) {
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
	 * Returns ordering constraints that should be applied when searching executed in the requested scope.
	 */
	@Nonnull
	public OrderConstraint[] getOrdering() {
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
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof Scope,
			"Constraint InScope requires exactly one argument of type Scope!"
		);
		return this;
	}

	@Nonnull
	@Override
	public OrderConstraint getCopyWithNewChildren(@Nonnull OrderConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint InScope doesn't accept other than ordering constraints!"
		);
		return new OrderInScope(new Serializable[] { getScope() }, children);
	}
}