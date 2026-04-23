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
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `require` container is one of the four top-level sections of an EvitaQL query (alongside `collection`,
 * `filterBy`, and `orderBy`). It is the root holder for all {@link RequireConstraint} children and is the only
 * place where requirement constraints are permitted in a query.
 *
 * ## Purpose
 *
 * Requirements have no direct parallel in traditional SQL or other database query languages. They define
 * **sideway computations** that accompany the main result set without altering the set of matched entities
 * or their ordering. Specifically, they control:
 *
 * - **Paging** — how many entities are returned and at which offset ({@link Page}, {@link Strip})
 * - **Entity content** — which attributes, associated data, prices, and references to load per entity
 *   ({@link EntityFetch}, {@link EntityGroupFetch})
 * - **Extra result computations** — facet summaries, attribute histograms, hierarchy trees, price histograms,
 *   and other contextual data sets that enrich the response beyond the entity list itself
 *
 * ## Uniqueness of children
 *
 * The `require` container enforces that each child constraint type appears at most once (see `@Child(uniqueChildren = true)`).
 * Duplicate constraint types within the same `require` block result in a validation error during query parsing.
 *
 * ## Applicability and necessity
 *
 * A `require` container without any children is considered neither applicable nor necessary. The query engine
 * omits it from processing, which is equivalent to having no `require` section at all.
 *
 * ## Usage example
 *
 * ```evitaql
 * require(
 *     page(1, 20),
 *     entityFetch(
 *         attributeContentAll(),
 *         priceContentAll()
 *     ),
 *     facetSummary()
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/basics#require)
 *
 * @see Page
 * @see Strip
 * @see EntityFetch
 * @see RequireInScope
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "require",
	shortDescription = "The top-level container that encapsulates all require constraints defining what additional data and computations the query should produce.",
	userDocsLink = "/documentation/query/basics#require"
)
public class Require extends AbstractRequireConstraintContainer implements GenericConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = 6115101893250263038L;

	@Creator
	public Require(@Nonnull @Child(uniqueChildren = true) RequireConstraint... children) {
		super(children);
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return new Require(children);
	}

	@Override
	public boolean isNecessary() {
		return isApplicable();
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isPremiseValid(
			newArguments.length == 0,
			"Require constraint has no arguments!"
		);
		return new Require(getChildren());
	}
}
