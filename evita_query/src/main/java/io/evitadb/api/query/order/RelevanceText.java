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

package io.evitadb.api.query.order;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `relevanceText` constraint sorts the entities matched by the {@link io.evitadb.api.query.filter.TextMatches}
 * fulltext filter from the most relevant to the least relevant one.
 *
 * **Syntax**
 *
 * ```evitaql
 * relevanceText()
 * ```
 *
 * **Example**
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     filterBy(
 *         entityLocaleEquals("en"),
 *         textMatches("black leather jacket")
 *     ),
 *     orderBy(
 *         relevanceText()
 *     )
 * )
 * ```
 *
 * **Behaviour**
 *
 * - without a `textMatches` filter in the query there is no relevance to sort by, and the constraint passes all
 *   entities on to the next ordering constraint in their incoming order
 * - the constraint can be used in the top-level `orderBy` and inside a `segment`, but not inside the ordering
 *   containers that sort by properties of referenced entities (`referenceProperty`, `entityProperty`,
 *   `entityGroupProperty`, `traverseByEntityProperty`, `pickFirstByEntityProperty`)
 *
 * **Why a container without children**
 *
 * The constraint is a container even though it accepts no children yet. The ranking policy (field weights, boost
 * tables and similar) will be expressed by its children, and turning a leaf constraint into a container later would
 * change its shape in every API that mirrors the query language.
 *
 * @see io.evitadb.api.query.filter.TextMatches
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ConstraintDefinition(
	name = "relevanceText",
	shortDescription = "The constraint sorts entities by the fulltext relevance computed by the `textMatches` filter.",
	userDocsLink = "/documentation/query/ordering/relevance#relevance-text",
	supportedIn = ConstraintDomain.ENTITY
)
public class RelevanceText extends AbstractOrderConstraintContainer implements GenericConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = 2860118207357049441L;

	/**
	 * Creates the `relevanceText` ordering constraint.
	 */
	@Creator
	public RelevanceText() {
		super();
	}

	/**
	 * The constraint is applicable without children — it has no mandatory part.
	 */
	@Override
	public boolean isApplicable() {
		return true;
	}

	/**
	 * The constraint is never flattened away, even without children.
	 */
	@Override
	public boolean isNecessary() {
		return true;
	}

	@Nonnull
	@Override
	public OrderConstraint getCopyWithNewChildren(
		@Nonnull OrderConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isPremiseValid(
			children.length == 0 && additionalChildren.length == 0,
			"Constraint `relevanceText` doesn't accept any children!"
		);
		return new RelevanceText();
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("Constraint `relevanceText` doesn't support arguments!");
	}
}
