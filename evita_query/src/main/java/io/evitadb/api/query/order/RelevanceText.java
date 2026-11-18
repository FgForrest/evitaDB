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
 * - scoring sources come from the main filter and, inside a segment, that segment's predicate; other segments and
 *   requirement filters supply no scores here
 * - without a scoring source the constraint passes entities to the next ordering constraint; text in reach confined
 *   to hierarchy `having`, `anyHaving` or `excluding` is refused because it filters the tree and is not scored
 * - the constraint can be used in the top-level `orderBy` and inside a `segment`, but not inside the ordering
 *   containers that sort by properties of referenced entities (`referenceProperty`, `entityProperty`,
 *   `entityGroupProperty`, `traverseByEntityProperty`, `pickFirstByEntityProperty`)
 *
 * **Place weights**
 *
 * Children replace the schema search weights of individual searchable places for this relevance ordering:
 *
 * - `attributeWeight` and `associatedDataWeight` weigh attributes and associated data of the queried entity
 * - `referenceWeight` weighs the attributes of one reference and, through `entityWeight`, the places of its target
 *   collection that the query searches through composition
 *
 * Each `attributeWeight` and `associatedDataWeight` refuses a negative weight or one beyond the decimal
 * representation bounds when it is constructed. The other checks run during planning, before any index is read,
 * and a violation refuses the query:
 *
 * - every weighted place must exist and be searchable in every scope this ordering covers; enclosing ordering
 *   `inScope` containers narrow the query scopes, just as for filterable and sortable attributes
 * - weights must fit the internal float; one `relevanceText` may weight each place only once
 * - each `relevanceText` has its own weights, so segments and ordering `inScope` containers can rank differently
 * - when scoring text is in reach, each weighted place must be searched by a scoring `textMatches` of the main
 *   filter or this segment's predicate; text used only by hierarchy predicates or other segments does not count
 * - without scoring text in reach, the existing searched-place checks still apply; with no `textMatches` anywhere,
 *   weights receive schema validation only and order nothing
 *
 * Places not weighted by this `relevanceText` retain their schema search weights.
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
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE }
)
public class RelevanceText extends AbstractOrderConstraintContainer implements GenericConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = 2860118207357049441L;

	/**
	 * Creates the `relevanceText` ordering constraint.
	 *
	 * @param weights place weights for this relevance ordering, NULL or empty for schema defaults
	 * @throws GenericEvitaInternalError when a child is not a place weight allowed directly in `relevanceText`
	 */
	@Creator
	public RelevanceText(
		@Nullable @Child(domain = ConstraintDomain.TEXT) RelevanceTextEntitySpecificationOrderConstraint... weights
	) {
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
