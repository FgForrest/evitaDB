/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Represents a logical negation (NOT) that inverts the result of its child constraint, excluding all entities that
 * match the child and including all others. This constraint implements the fundamental boolean NOT operation in
 * evitaDB's query language, allowing you to express negative filtering conditions.
 *
 * **Logical Semantics**
 *
 * The `not` container follows standard [logical negation](https://en.wikipedia.org/wiki/Negation) rules:
 *
 * | A     | ¬ A   |
 * |-------|-------|
 * | True  | False |
 * | False | True  |
 *
 * Given a superset of entities (either the entire collection or a subset defined by sibling constraints), `not`
 * subtracts the entities matched by its child constraint, returning all remaining entities.
 *
 * **Unary Operator Contract**
 *
 * Unlike {@link And} and {@link Or} which accept multiple children, `not` is a unary operator that accepts **exactly
 * one** child constraint. This is enforced at construction time. If you need to negate multiple conditions combined
 * with AND or OR, you must wrap them:
 * - `not(and(a, b))` - negates the conjunction (equivalent to `or(not(a), not(b))` by De Morgan's law)
 * - `not(or(a, b))` - negates the disjunction (equivalent to `and(not(a), not(b))` by De Morgan's law)
 *
 * **Usage Context**
 *
 * This constraint can be used in multiple domains:
 * - `ENTITY`: within {@link FilterBy} to negate entity-level filter constraints
 * - `REFERENCE`: within {@link ReferenceHaving} to negate reference-level constraints
 * - `INLINE_REFERENCE`: within inline reference filters
 * - `FACET`: within {@link FacetHaving} to negate facet-level constraints
 *
 * **Necessity and Applicability**
 *
 * The `not` constraint is considered **necessary** only when it contains exactly one child. An empty `not` (with no
 * child) is not necessary and not applicable - it represents an invalid or normalized-away state. During query
 * normalization, unnecessary `not` constraints may be removed.
 *
 * **Performance Considerations**
 *
 * Negation can be computationally expensive when applied to the entire collection, as it requires materializing the
 * superset and then subtracting the matched entities. Performance is better when `not` is used within a constrained
 * superset (e.g., combined with other positive filters via AND).
 *
 * **EvitaQL Syntax**
 *
 * ```evitaql
 * not(filterConstraint:any!)
 * ```
 *
 * **Example Usage**
 *
 * ```java
 * // Exclude specific products - returns all products except three specified entities
 * query(
 *     collection("Product"),
 *     filterBy(
 *         not(
 *             entityPrimaryKeyInSet(110066, 106742, 110513)
 *         )
 *     )
 * )
 *
 * // Narrow the superset first, then exclude - returns products 66567, 66574, 66556
 * query(
 *     collection("Product"),
 *     filterBy(
 *         entityPrimaryKeyInSet(110513, 66567, 106742, 66574, 66556, 110066),
 *         not(
 *             entityPrimaryKeyInSet(110066, 106742, 110513)
 *         )
 *     )
 * )
 *
 * // Exclude products on sale - find regular-price products only
 * query(
 *     collection("Product"),
 *     filterBy(
 *         attributeEquals("available", true),
 *         not(
 *             attributeEquals("onSale", true)
 *         )
 *     )
 * )
 *
 * // Negate a complex condition - find products that are NOT (Nike AND expensive)
 * query(
 *     collection("Product"),
 *     filterBy(
 *         not(
 *             and(
 *                 attributeEquals("brand", "Nike"),
 *                 priceGreaterThan(200)
 *             )
 *         )
 *     )
 * )
 *
 * // Exclude entities having certain references
 * query(
 *     collection("Product"),
 *     filterBy(
 *         not(
 *             referenceHaving(
 *                 "category",
 *                 entityPrimaryKeyInSet(10, 20)
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/logical#not)
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "not",
	shortDescription = "The container that behaves as [logical NOT](https://en.wikipedia.org/wiki/Negation) for the inner constraint.",
	userDocsLink = "/documentation/query/filtering/logical#not",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE, ConstraintDomain.FACET }
)
public class Not extends AbstractFilterConstraintContainer implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = 7151549459608672988L;

	/**
	 * Private constructor that creates unnecessary / not applicable version of the query.
	 */
	private Not() {}

	@Creator
	public Not(@Nonnull FilterConstraint child) {
		super(child);
	}

	@Override
	public boolean isNecessary() {
		return getChildren().length > 0;
	}

	/**
	 * Returns the single child constraint of this negation.
	 *
	 * @throws io.evitadb.exception.EvitaInvalidUsageException if there are no children (empty Not)
	 */
	@Nonnull
	public FilterConstraint getChild() {
		Assert.isTrue(getChildren().length > 0, "Not constraint has no child!");
		return getChildren()[0];
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(ArrayUtils.isEmpty(additionalChildren), "Not doesn't accept other than filtering constraints!");
		return children.length == 0 ? new Not() : new Not(children[0]);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("Not filtering query has no arguments!");
	}

}
