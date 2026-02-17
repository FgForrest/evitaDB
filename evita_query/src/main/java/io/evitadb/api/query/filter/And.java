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
 * Represents a logical conjunction (AND) that returns results only when ALL child constraints are satisfied. This
 * constraint implements the fundamental boolean AND operation in evitaDB's query language, allowing you to combine
 * multiple filtering conditions that must all be true for an entity to match.
 *
 * **Logical Semantics**
 *
 * The `and` container follows standard [logical conjunction](https://en.wikipedia.org/wiki/Logical_conjunction) rules:
 *
 * | A     | B     | A ∧ B |
 * |-------|-------|-------|
 * | True  | True  | True  |
 * | True  | False | False |
 * | False | True  | False |
 * | False | False | False |
 *
 * The result set contains only entities that satisfy all child constraints simultaneously. This produces the
 * intersection of the entity sets matched by each individual child constraint.
 *
 * **Default Logical Binding**
 *
 * When multiple filter constraints are placed directly within a container without an explicit logical operator,
 * evitaDB defaults to conjunction (AND). Therefore, `filterBy(a, b, c)` is semantically equivalent to
 * `filterBy(and(a, b, c))`. The explicit `and` is necessary only when you need to nest conjunction within other
 * logical operators (such as within {@link Or} or {@link Not}), or when you want to make the AND semantics explicit
 * for clarity.
 *
 * **Usage Context**
 *
 * This constraint can be used in multiple domains:
 * - `ENTITY`: within {@link FilterBy} to combine entity-level filter constraints
 * - `REFERENCE`: within {@link ReferenceHaving} to combine reference-level constraints
 * - `INLINE_REFERENCE`: within inline reference filters
 * - `FACET`: within {@link FacetHaving} to combine facet-level constraints
 *
 * **Necessity and Applicability**
 *
 * The `and` constraint is considered **necessary** only when it contains two or more children. A single-child `and`
 * is redundant (the child constraint alone has the same effect) and will typically be optimized away during query
 * normalization. An empty `and` is not applicable and represents an invalid state.
 *
 * **EvitaQL Syntax**
 *
 * ```evitaql
 * and(filterConstraint:any+)
 * ```
 *
 * **Example Usage**
 *
 * ```java
 * // Basic conjunction - find products that are available AND in a specific price range
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             attributeEquals("available", true),
 *             priceBetween(100, 500)
 *         )
 *     )
 * )
 *
 * // Nested within OR - find products matching brand A with condition X, or brand B with condition Y
 * query(
 *     collection("Product"),
 *     filterBy(
 *         or(
 *             and(
 *                 attributeEquals("brand", "Nike"),
 *                 attributeGreaterThan("rating", 4.0)
 *             ),
 *             and(
 *                 attributeEquals("brand", "Adidas"),
 *                 attributeEquals("onSale", true)
 *             )
 *         )
 *     )
 * )
 *
 * // Intersection example - returns only entity 106742 which appears in all three sets
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             entityPrimaryKeyInSet(110066, 106742, 110513),
 *             entityPrimaryKeyInSet(110066, 106742),
 *             entityPrimaryKeyInSet(107546, 106742, 107546)
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/logical#and)
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "and",
	shortDescription = "The container that combines inner constraints with [logical AND](https://en.wikipedia.org/wiki/Logical_conjunction).",
	userDocsLink = "/documentation/query/filtering/logical#and",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE, ConstraintDomain.FACET }
)
public class And extends AbstractFilterConstraintContainer implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -3383976355275556890L;

	@Creator
	public And(@Nonnull FilterConstraint... children) {
		super(children);
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(ArrayUtils.isEmpty(additionalChildren), "And doesn't accept other than filtering constraints!");
		return new And(children);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("And filtering query has no arguments!");
	}

}
