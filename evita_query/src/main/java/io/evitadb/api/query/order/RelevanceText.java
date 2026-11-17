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
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
 * **Place weights**
 *
 * Children replace the schema search weights of individual searchable places for this query:
 *
 * - `attributeWeight` and `associatedDataWeight` weigh attributes and associated data of the queried entity
 * - `referenceWeight` weighs the attributes of one reference and, through `entityWeight`, the places of its target
 *   collection that the query searches through composition
 *
 * The weights are checked during planning, before any index is read, and a violation refuses the query:
 *
 * - every weighted place must exist and be searchable in at least one scope the query processes, no weight may be
 *   negative, and one `relevanceText` may weight each place only once
 * - a query has a single weight policy: every `relevanceText` in it, including those inside segments and those
 *   without children, must state the same weights
 * - when the query contains a `textMatches`, each weighted place must be searched by a `textMatches` of the main
 *   filter or of a segment's `entityHaving`; without any `textMatches` the weights are checked but order nothing
 *
 * When no `relevanceText` of the query states a weight, the schema search weights apply.
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     filterBy(
 *         entityLocaleEquals("en"),
 *         textMatches("black leather jacket")
 *     ),
 *     orderBy(
 *         relevanceText(
 *             attributeWeight("name", 3),
 *             associatedDataWeight("description", 0.5),
 *             referenceWeight("brand", entityWeight(attributeWeight("name", 2)))
 *         )
 *     )
 * )
 * ```
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
	 *
	 * @param weights place weights replacing schema search weights for this query, NULL or empty for none
	 * @throws GenericEvitaInternalError when a child is not a place weight allowed directly in `relevanceText`
	 */
	@Creator
	public RelevanceText(@Nullable @Child RelevanceTextEntitySpecificationOrderConstraint... weights) {
		this(weights == null ? new OrderConstraint[0] : weights);
	}

	/**
	 * Copies child weights while enforcing the entity level.
	 *
	 * @param weights the child constraints, each of them a place weight allowed directly in `relevanceText`
	 * @throws GenericEvitaInternalError when a child is not such a weight
	 */
	private RelevanceText(@Nonnull OrderConstraint[] weights) {
		super(weights);
		for (final OrderConstraint weight : getChildren()) {
			Assert.isPremiseValid(weight instanceof RelevanceTextEntitySpecificationOrderConstraint,
				() -> "Constraint `relevanceText` does not accept `" + weight + "` as its child!");
		}
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
			additionalChildren.length == 0,
			"Constraint `relevanceText` accepts only place weights!"
		);
		return new RelevanceText(children);
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("Constraint `relevanceText` doesn't support arguments!");
	}
}
