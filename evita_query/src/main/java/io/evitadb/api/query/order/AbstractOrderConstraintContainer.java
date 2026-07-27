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

package io.evitadb.api.query.order;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.OrderConstraint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Abstract base class for all container order constraints — structural nodes in the order constraint tree that hold
 * child constraints. Container order constraints enable composition of complex ordering logic by nesting multiple
 * ordering conditions.
 *
 * **Design Purpose**
 *
 * This class specializes {@link ConstraintContainer} for the order constraint domain by restricting both primary
 * children and the constraint type to {@link OrderConstraint}. It serves as the foundation for all structural
 * ordering constructs in evitaDB queries, including the root order container, scoped ordering, reference property
 * ordering, and segmented ordering.
 *
 * **Class Hierarchy**
 *
 * The order constraint container hierarchy is organized by composition semantics:
 *
 * - **Root containers**: {@link OrderBy} — top-level container for all ordering constraints in a query;
 *   {@link OrderGroupBy} — defines ordering within facet groups
 * - **Scoped containers**: {@link OrderInScope} — applies ordering within a specific entity scope (LIVE/ARCHIVED)
 * - **Reference property containers**: {@link ReferenceProperty} — orders entities by attributes of their
 *   referenced entities
 * - **Entity property containers**: {@link EntityProperty}, {@link EntityGroupProperty} — order by properties of
 *   the entity or its group within a reference context
 * - **Segment containers**: {@link Segments}, {@link Segment} — define piecewise ordering where different segments
 *   of the result set can use different ordering criteria
 * - **Traversal containers**: {@link TraverseByEntityProperty}, {@link PickFirstByEntityProperty} — define ordering
 *   strategies for traversing hierarchical reference structures
 *
 * **Constructor Overloads**
 *
 * This class provides numerous constructor overloads to support different combinations of arguments and children.
 * These constructors enable flexible instantiation patterns in concrete subclasses:
 *
 * - Constructors accepting `OrderConstraint[] children` for simple structural wrappers
 * - Constructors accepting `Serializable[] arguments, OrderConstraint[] children` for parameterized containers
 * - Constructors accepting `Constraint<?>... additionalChildren` for containers that mix constraint types
 * - Constructors with optional `String name` parameter for constraints with suffix support
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 * @see AbstractOrderConstraintLeaf
 * @see OrderConstraint
 * @see ConstraintContainer
 */
abstract class AbstractOrderConstraintContainer
	extends ConstraintContainer<OrderConstraint>
	implements OrderConstraint {
	@Serial private static final long serialVersionUID = -7858636742421451053L;

	protected AbstractOrderConstraintContainer(
		@Nonnull Serializable[] arguments,
		@Nonnull OrderConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(arguments, children, additionalChildren);
	}

	protected AbstractOrderConstraintContainer(
		@Nullable String name,
		@Nonnull Serializable[] arguments,
		@Nonnull OrderConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(name, arguments, children, additionalChildren);
	}

	protected AbstractOrderConstraintContainer(
		@Nonnull OrderConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(children, additionalChildren);
	}

	protected AbstractOrderConstraintContainer(
		@Nullable String name,
		@Nonnull OrderConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(name, NO_ARGS, children, additionalChildren);
	}

	protected AbstractOrderConstraintContainer(
		@Nonnull Serializable[] arguments,
		@Nonnull OrderConstraint... children
	) {
		super(arguments, children);
	}

	protected AbstractOrderConstraintContainer(
		@Nullable String name,
		@Nonnull Serializable[] arguments,
		@Nonnull OrderConstraint... children
	) {
		super(name, arguments, children);
	}

	protected AbstractOrderConstraintContainer(
		@Nonnull Serializable argument,
		@Nonnull OrderConstraint... children
	) {
		super(new Serializable[] {argument}, children);
	}

	protected AbstractOrderConstraintContainer(
		@Nullable String name,
		@Nonnull Serializable argument,
		@Nonnull OrderConstraint... children
	) {
		super(name, new Serializable[] {argument}, children);
	}

	protected AbstractOrderConstraintContainer(@Nonnull OrderConstraint... children) {
		super(children);
	}

	protected AbstractOrderConstraintContainer(@Nullable String name, @Nonnull OrderConstraint... children) {
		super(name, children);
	}

	@Nonnull
	@Override
	public Class<OrderConstraint> getType() {
		return OrderConstraint.class;
	}

	@Override
	public void accept(@Nonnull ConstraintVisitor visitor) {
		visitor.visit(this);
	}

}
