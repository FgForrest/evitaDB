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
 * Represents a logical disjunction (OR) that returns results when AT LEAST ONE child constraint is satisfied. This
 * constraint implements the fundamental boolean OR operation in evitaDB's query language, allowing you to combine
 * multiple filtering conditions where any single match is sufficient for an entity to qualify.
 *
 * **Logical Semantics**
 *
 * The `or` container follows standard [logical disjunction](https://en.wikipedia.org/wiki/Logical_disjunction) rules:
 *
 * | A     | B     | A ∨ B |
 * |-------|-------|-------|
 * | True  | True  | True  |
 * | True  | False | True  |
 * | False | True  | True  |
 * | False | False | False |
 *
 * The result set contains entities that satisfy at least one of the child constraints. This produces the union of the
 * entity sets matched by each individual child constraint (with duplicates removed).
 *
 * **Contrast with Default AND Semantics**
 *
 * Unlike the implicit conjunction applied when multiple constraints are listed without a logical operator, `or`
 * explicitly requires only one child to match. Compare:
 * - `filterBy(a, b, c)` - requires ALL of a, b, c to match (implicit AND)
 * - `filterBy(or(a, b, c))` - requires ANY of a, b, c to match (explicit OR)
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
 * The `or` constraint is considered **necessary** only when it contains two or more children. A single-child `or` is
 * redundant (the child constraint alone has the same effect) and will typically be optimized away during query
 * normalization. An empty `or` is not applicable and represents an invalid state.
 *
 * **EvitaQL Syntax**
 *
 * ```evitaql
 * or(filterConstraint:any+)
 * ```
 *
 * **Example Usage**
 *
 * ```java
 * // Basic disjunction - find products from either Nike or Adidas
 * query(
 *     collection("Product"),
 *     filterBy(
 *         or(
 *             attributeEquals("brand", "Nike"),
 *             attributeEquals("brand", "Adidas")
 *         )
 *     )
 * )
 *
 * // Nested within AND - find available products that are either on sale OR highly rated
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             attributeEquals("available", true),
 *             or(
 *                 attributeEquals("onSale", true),
 *                 attributeGreaterThan("rating", 4.5)
 *             )
 *         )
 *     )
 * )
 *
 * // Union example - returns four distinct entities (110066, 106742, 110513, 107546)
 * query(
 *     collection("Product"),
 *     filterBy(
 *         or(
 *             entityPrimaryKeyInSet(110066, 106742, 110513),
 *             entityPrimaryKeyInSet(110066, 106742),
 *             entityPrimaryKeyInSet(107546, 106742, 107546)
 *         )
 *     )
 * )
 *
 * // Complex multi-criteria search - match by name OR description OR code
 * query(
 *     collection("Product"),
 *     filterBy(
 *         or(
 *             attributeContains("name", "wireless"),
 *             attributeContains("description", "wireless"),
 *             attributeStartsWith("code", "WLS")
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/logical#or)
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "or",
	shortDescription = "The container that combines inner constraints with [logical OR](https://en.wikipedia.org/wiki/Logical_disjunction).",
	userDocsLink = "/documentation/query/filtering/logical#or",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE, ConstraintDomain.FACET }
)
public class Or extends AbstractFilterConstraintContainer implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -7264763953915262562L;

	@Creator
	public Or(@Nonnull FilterConstraint... children) {
		super(children);
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(ArrayUtils.isEmpty(additionalChildren), "Or doesn't accept other than filtering constraints!");
		return new Or(children);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("Or filtering query has no arguments!");
	}

}
