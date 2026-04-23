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
import io.evitadb.api.query.GenericConstraint;
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
 * The `inScope` constraint is a scope-restricting container that limits the application of its child filtering constraints to a specific data
 * scope ({@link Scope#LIVE} or {@link Scope#ARCHIVED}). This constraint enables querying entities across multiple scopes simultaneously while
 * applying different filtering logic to each scope based on their indexing capabilities and data availability.
 *
 * ## Purpose and Design Intent
 *
 * evitaDB organizes entities into separate scopes: LIVE entities are currently active and reside in fully-indexed data sets, while ARCHIVED
 * entities represent soft-deleted or historical data with limited indexing (fewer indexed attributes, no facets, no hierarchies). When a query
 * targets multiple scopes via {@link EntityScope}, certain filtering constraints may only be applicable to one scope due to indexing differences.
 * The `inScope` container allows developers to specify scope-specific filtering logic without causing query failures.
 *
 * ## Scope Validation
 *
 * The scope specified in `inScope` must match one of the scopes declared in the query's {@link EntityScope} constraint. Using `inScope(LIVE, ...)`
 * in a query that only searches `scope(ARCHIVED)` will result in a validation error, as the filtering constraints would never be applied.
 *
 * ## Multi-Scope Query Behavior
 *
 * When querying multiple scopes with `scope(LIVE, ARCHIVED)`:
 *
 * - Filtering constraints **outside** `inScope` containers apply to **all scopes** and must reference only attributes/properties indexed in all
 *   target scopes
 * - Filtering constraints **inside** `inScope(LIVE, ...)` apply **only when searching the LIVE scope**
 * - Filtering constraints **inside** `inScope(ARCHIVED, ...)` apply **only when searching the ARCHIVED scope**
 * - If no scope-specific constraints match, the entity is evaluated solely against the global constraints
 *
 * This design prevents query failures when attributes are indexed differently across scopes and allows flexible filtering logic tailored to each
 * scope's capabilities.
 *
 * ## EvitaQL Syntax
 *
 * ```
 * inScope(
 *     LIVE|ARCHIVED,
 *     filterConstraint:any+
 * )
 * ```
 *
 * The constraint accepts:
 * - A single {@link Scope} argument (LIVE or ARCHIVED) specifying the target scope
 * - One or more child {@link FilterConstraint} instances that should apply only to that scope
 *
 * ## Usage Examples
 *
 * **Example 1: Filtering by scope-specific indexed attributes**
 *
 * ```
 * query(
 *     collection('Product'),
 *     filterBy(
 *         and(
 *             attributeEquals('code', '123'), // applies to both LIVE and ARCHIVED scopes
 *             inScope(
 *                 LIVE,
 *                 entityLocaleEquals(Locale.ENGLISH), // locale filtering only in LIVE scope
 *                 attributeEquals('name', 'LED TV') // 'name' attribute only indexed in LIVE
 *             ),
 *             scope(LIVE, ARCHIVED) // search both scopes
 *         )
 *     )
 * )
 * ```
 *
 * In this example, the `code` attribute is indexed in both scopes and filters all entities, while `name` attribute and locale context are only
 * available in the LIVE scope. ARCHIVED entities are matched solely by the `code` filter.
 *
 * **Example 2: Different filtering logic per scope**
 *
 * ```
 * query(
 *     collection('Order'),
 *     filterBy(
 *         and(
 *             inScope(LIVE, attributeGreaterThan('createdAt', '2025-01-01')), // recent orders in LIVE
 *             inScope(ARCHIVED, attributeLessThan('createdAt', '2024-01-01')), // old orders in ARCHIVED
 *             scope(LIVE, ARCHIVED)
 *         )
 *     )
 * )
 * ```
 *
 * This query searches for recent orders in LIVE scope and historical orders in ARCHIVED scope using distinct date ranges for each scope.
 *
 * **Example 3: Preventing query failure with scope-specific indexing**
 *
 * ```
 * // This query would FAIL if 'description' is not indexed in ARCHIVED scope:
 * filterBy(
 *     and(
 *         attributeContains('description', 'premium'),
 *         scope(LIVE, ARCHIVED)
 *     )
 * )
 *
 * // Corrected query using inScope:
 * filterBy(
 *     and(
 *         inScope(LIVE, attributeContains('description', 'premium')), // only filter by description in LIVE
 *         scope(LIVE, ARCHIVED)
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/behavioral#in-scope)
 *
 * @see Scope
 * @see EntityScope
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inScope",
	shortDescription = "Limits enclosed filtering constraints to be applied only when searching for entities in named scope.",
	userDocsLink = "/documentation/query/filtering/behavioral#in-scope",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE }
)
public class FilterInScope extends AbstractFilterConstraintContainer implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -2943395408560139656L;
	private static final String CONSTRAINT_NAME = "inScope";

	@Creator
	public FilterInScope(@Nonnull Scope scope, @Nonnull FilterConstraint... filtering) {
		super(CONSTRAINT_NAME, new Serializable[] { scope }, filtering);
		Assert.isTrue(scope != null, "Scope must be provided!");
		Assert.isTrue(!ArrayUtils.isEmptyOrItsValuesNull(filtering), "At least one filtering constraint must be provided!");
	}

	private FilterInScope(@Nonnull Serializable[] arguments, @Nonnull FilterConstraint... children) {
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
		return new FilterInScope(newArguments, getChildren());
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(
		@Nonnull FilterConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint InScope doesn't accept other than filtering constraints!"
		);
		return new FilterInScope(new Serializable[] { getScope() }, children);
	}
}