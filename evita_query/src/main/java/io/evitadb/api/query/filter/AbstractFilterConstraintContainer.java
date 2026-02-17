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
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Abstract base class for all container filter constraints — structural nodes in the filter constraint tree that hold
 * child constraints. Container filter constraints enable composition of complex filtering logic by nesting multiple
 * filtering conditions.
 *
 * **Design Purpose**
 *
 * This class specializes {@link ConstraintContainer} for the filter constraint domain by restricting both primary
 * children and the constraint type to {@link FilterConstraint}. It serves as the foundation for all structural
 * filtering constructs in evitaDB queries, including logical operators, scoped filters, and the root filter container.
 *
 * **Class Hierarchy**
 *
 * The filter constraint container hierarchy is organized by composition semantics:
 *
 * - **Logical operators**: {@link And}, {@link Or}, {@link Not} — combine child filters using boolean logic
 *   (conjunction, disjunction, negation)
 * - **Scoped containers**: {@link ReferenceHaving}, {@link EntityHaving}, {@link FacetHaving} — apply filters to
 *   specific entity scopes (references, associated entities, facets)
 * - **Hierarchical containers**: {@link HierarchyWithin}, {@link HierarchyWithinRoot}, {@link HierarchyHaving},
 *   {@link HierarchyAnyHaving}, {@link HierarchyExcluding} — define hierarchical filtering conditions with optional
 *   nested filters for matching hierarchy nodes
 * - **Facet containers**: {@link FacetIncludingChildren}, {@link FacetIncludingChildrenExcept} — control facet
 *   hierarchy inclusion with exception handling
 * - **Root container**: {@link FilterBy} — top-level container for all filtering constraints in a query
 * - **Grouping containers**: {@link FilterGroupBy} — groups filters by scope (e.g., locale-specific filtering)
 * - **User filter container**: {@link UserFilter} — separates user-provided filters from system filters for facet
 *   impact calculation
 * - **Scope filter**: {@link FilterInScope} — applies filters within a specific entity scope (LIVE/ARCHIVED)
 *
 * **Constructor Overloads**
 *
 * This class provides numerous constructor overloads to support different combinations of arguments and children.
 * These constructors enable flexible instantiation patterns in concrete subclasses:
 *
 * - Constructors accepting `FilterConstraint[] children` for simple logical operators
 * - Constructors accepting `Serializable[] arguments, FilterConstraint[] children` for parameterized containers
 * - Constructors accepting `Constraint<?>... additionalChildren` for containers that mix constraint types
 * - Constructors with optional `String name` parameter for constraints with suffix support
 *
 * **Type Safety**
 *
 * By restricting children to `FilterConstraint`, this class enforces type safety at compile time. Attempting to add
 * non-filter constraints (like order or require constraints) to filter containers will result in compilation errors,
 * preventing malformed query trees.
 *
 * **Immutability**
 *
 * Like all constraints, filter containers are immutable. All fields are final, and child arrays are defensively
 * handled to prevent modifications. The {@link ConstraintContainer#getCopyWithNewChildren} method enables creating
 * modified copies with different child sets, which is used by query optimization and normalization logic.
 *
 * **Applicability**
 *
 * A filter container is considered applicable ({@link ConstraintContainer#isApplicable()}) if it has at least one
 * child constraint. Empty containers are typically removed during query normalization.
 *
 * **Usage Examples**
 *
 * Typical usage in filter expressions:
 * ```java
 * // Logical operators
 * and(
 *     equals("visible", true),
 *     or(
 *         equals("category", "phones"),
 *         equals("category", "tablets")
 *     ),
 *     not(equals("discontinued", true))
 * )
 *
 * // Scoped filtering
 * referenceHaving(
 *     "brand",
 *     entityPrimaryKeyInSet(1, 2, 3)
 * )
 *
 * // Hierarchical filtering
 * hierarchyWithin(
 *     "category",
 *     1,
 *     entityHaving(
 *         attributeEquals("visible", true)
 *     )
 * )
 * ```
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 * @see AbstractFilterConstraintLeaf
 * @see FilterConstraint
 * @see ConstraintContainer
 */
abstract class AbstractFilterConstraintContainer
	extends ConstraintContainer<FilterConstraint>
	implements FilterConstraint {
	@Serial private static final long serialVersionUID = 1585533135394728582L;

	protected AbstractFilterConstraintContainer(@Nonnull Serializable[] arguments, @Nonnull FilterConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(arguments, children, additionalChildren);
	}

	protected AbstractFilterConstraintContainer(@Nullable String name, @Nonnull Serializable[] arguments, @Nonnull FilterConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(name, arguments, children, additionalChildren);
	}

	protected AbstractFilterConstraintContainer(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(children, additionalChildren);
	}

	protected AbstractFilterConstraintContainer(@Nullable String name, @Nonnull FilterConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(name, NO_ARGS, children, additionalChildren);
	}

	protected AbstractFilterConstraintContainer(@Nonnull Serializable[] arguments, @Nonnull FilterConstraint... children) {
		super(arguments, children);
	}

	protected AbstractFilterConstraintContainer(@Nullable String name, @Nonnull Serializable[] arguments, @Nonnull FilterConstraint... children) {
		super(name, arguments, children);
	}

	protected AbstractFilterConstraintContainer(@Nonnull Serializable argument, @Nonnull FilterConstraint... children) {
		super(new Serializable[] {argument}, children);
	}

	protected AbstractFilterConstraintContainer(@Nullable String name, @Nonnull Serializable argument, @Nonnull FilterConstraint... children) {
		super(name, new Serializable[] {argument}, children);
	}

	protected AbstractFilterConstraintContainer(@Nonnull Serializable argument1, @Nonnull Serializable argument2, @Nonnull FilterConstraint... children) {
		super(new Serializable[] {argument1, argument2}, children);
	}

	protected AbstractFilterConstraintContainer(@Nullable String name, @Nonnull Serializable argument1, @Nonnull Serializable argument2, @Nonnull FilterConstraint... children) {
		super(name, new Serializable[] {argument1, argument2}, children);
	}

	protected AbstractFilterConstraintContainer(@Nonnull FilterConstraint... children) {
		super(children);
	}

	protected AbstractFilterConstraintContainer(@Nullable String name, FilterConstraint... children) {
		super(name, NO_ARGS, children);
	}

	@Nonnull
	@Override
	public Class<FilterConstraint> getType() {
		return FilterConstraint.class;
	}

	@Override
	public void accept(@Nonnull ConstraintVisitor visitor) {
		visitor.visit(this);
	}

}
