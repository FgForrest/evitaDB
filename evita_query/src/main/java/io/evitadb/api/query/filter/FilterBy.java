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
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The root container for all filtering constraints in a query. This constraint acts as the top-level filter section,
 * analogous to the `WHERE` clause in SQL, and determines which entities from the target collection are included in
 * the query result set.
 *
 * **Purpose**
 *
 * `FilterBy` serves as the mandatory root container for all filtering logic. It groups one or more child filter
 * constraints that define the selection criteria for entities. All evitaDB queries that need to filter results must
 * use `filterBy` to specify the filtering conditions.
 *
 * **Default Conjunction Semantics**
 *
 * When multiple child constraints are specified directly within `filterBy`, they are implicitly combined using
 * **logical conjunction (AND)**. All child constraints must be satisfied for an entity to match. This means:
 * - `filterBy(a, b, c)` is equivalent to `filterBy(and(a, b, c))`
 * - All conditions a, b, and c must evaluate to true for the entity to be included in the result
 *
 * To use different logical combinations (OR, NOT), you must explicitly nest the appropriate logical operators
 * ({@link And}, {@link Or}, {@link Not}) within `filterBy`.
 *
 * **Usage Context**
 *
 * This constraint can be used in multiple domains:
 * - `GENERIC`: as a top-level query section in {@link io.evitadb.api.query.Query}
 * - `ENTITY`: within entity query context
 * - `SEGMENT`: within segment filtering contexts
 * - `INLINE_REFERENCE`: within inline reference filtering
 *
 * **Necessity and Applicability**
 *
 * The `filterBy` constraint is considered **necessary** only when it has at least one applicable child constraint.
 * An empty `filterBy` (with no children) is neither necessary nor applicable and will typically be omitted during
 * query normalization. The `isNecessary()` implementation delegates to `isApplicable()` for this reason.
 *
 * **EvitaQL Syntax**
 *
 * ```evitaql
 * filterBy(filterConstraint:any*)
 * ```
 *
 * **Example Usage**
 *
 * ```java
 * // Simple attribute filtering - implicit AND
 * query(
 *     collection("Product"),
 *     filterBy(
 *         attributeEquals("available", true),
 *         attributeEquals("category", "Electronics")
 *     )
 * )
 *
 * // Complex filtering with explicit logical operators
 * query(
 *     collection("Product"),
 *     filterBy(
 *         attributeEquals("available", true),
 *         or(
 *             attributeEquals("brand", "Nike"),
 *             attributeEquals("brand", "Adidas")
 *         ),
 *         priceBetween(100, 500)
 *     )
 * )
 *
 * // Filtering with negation
 * query(
 *     collection("Product"),
 *     filterBy(
 *         attributeEquals("category", "Shoes"),
 *         not(
 *             attributeEquals("onSale", true)
 *         )
 *     )
 * )
 *
 * // Full-featured filter with multiple constraint types
 * query(
 *     collection("Product"),
 *     filterBy(
 *         entityPrimaryKeyInSet(1, 2, 3, 5, 8, 13),
 *         entityLocaleEquals(Locale.ENGLISH),
 *         attributeIsNotNull("code"),
 *         or(
 *             attributeEquals("code", "ABCD"),
 *             attributeStartsWith("title", "Knife")
 *         ),
 *         priceInCurrency(Currency.USD),
 *         priceValidIn(OffsetDateTime.now())
 *     )
 * )
 *
 * // Filtering with hierarchical and reference constraints
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin("category", 10),
 *         referenceHaving(
 *             "brand",
 *             entityPrimaryKeyInSet(100, 200)
 *         ),
 *         attributeGreaterThan("rating", 4.0)
 *     )
 * )
 * ```
 *
 * **Relationship to Other Containers**
 *
 * `FilterBy` is one of the four top-level query sections in evitaDB:
 * - **FilterBy**: defines which entities to include (this constraint)
 * - **OrderBy**: defines the sorting order of results
 * - **Require**: specifies data fetching and computational requirements (projections, aggregations, etc.)
 * - **Collection/Head**: specifies the target entity collection
 *
 * Within facet summary requirements, there is a specialized variant {@link FilterGroupBy} used to filter facet groups
 * rather than entities.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/basics#filter-by)
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "filterBy",
	shortDescription = "The root container for all filtering constraints in a query, analogous to the WHERE clause in SQL." +
		" Multiple children are implicitly combined with logical AND.",
	userDocsLink = "/documentation/query/basics#filter-by",
	supportedIn = { ConstraintDomain.GENERIC, ConstraintDomain.ENTITY, ConstraintDomain.SEGMENT, ConstraintDomain.INLINE_REFERENCE }
)
public class FilterBy extends AbstractFilterConstraintContainer implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -2294600717092701351L;

	FilterBy() {
		super();
	}

	@Creator
	public FilterBy(@Nonnull @Child(uniqueChildren = true) FilterConstraint... children) {
		super(children);
	}

	@Override
	public boolean isNecessary() {
		return isApplicable();
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("FilterBy filtering query has no arguments!");
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(ArrayUtils.isEmpty(additionalChildren), "FilterBy doesn't accept other than filtering constraints!");
		return children.length > 0 ? new FilterBy(children) : new FilterBy();
	}

}
