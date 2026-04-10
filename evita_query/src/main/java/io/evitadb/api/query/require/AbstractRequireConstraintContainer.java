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
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.RequireConstraint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Base class for all container-level require constraints in the EvitaQL require constraint hierarchy. Container
 * constraints can hold child `RequireConstraint` instances as well as *additional children* of other constraint
 * types (typically filter or order constraints), allowing for richly structured sub-trees.
 *
 * This abstract class wires the `RequireConstraint` type token into `ConstraintContainer`, overrides `isNecessary()`
 * to always return `true` (require containers are always semantically meaningful even when they carry only a single
 * child — unlike filter containers where single-child `and`/`or` wrappers are redundant), and dispatches to
 * the visitor without recursing (the visitor controls traversal order).
 *
 * The multiple constructor overloads support the range of child-specification patterns used across the require
 * constraint hierarchy:
 * - explicit name + arguments + primary children + additional children (used e.g. by named output constraints)
 * - arguments-only with primary children (most common for constraints with parameters)
 * - children-only (for pure structural wrappers like {@link Require})
 *
 * Concrete subclasses include:
 * - {@link Require} — the top-level require container; holds all require constraints in a query
 * - {@link EntityFetch} / {@link EntityGroupFetch} — define entity body richness
 * - {@link ReferenceContent} — specifies reference loading with optional nested entity fetch
 * - {@link HierarchyOfSelf} / {@link HierarchyOfReference} — trigger hierarchy tree computation
 * - {@link FacetSummary} / {@link FacetSummaryOfReference} — trigger facet statistic computation
 *
 * All subclasses are immutable.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
abstract class AbstractRequireConstraintContainer extends ConstraintContainer<RequireConstraint> implements RequireConstraint {
	@Serial private static final long serialVersionUID = 5596073952193919059L;

	public AbstractRequireConstraintContainer(
		@Nullable String name,
		@Nonnull Serializable[] arguments,
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(name, arguments, children, additionalChildren);
	}

	public AbstractRequireConstraintContainer(
		@Nullable String name,
		@Nonnull Serializable[] arguments,
		@Nonnull RequireConstraint... children
	) {
		super(name, arguments, children);
	}

	protected AbstractRequireConstraintContainer(
		@Nonnull Serializable[] arguments,
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(arguments, children, additionalChildren);
	}

	protected AbstractRequireConstraintContainer(
		@Nullable String name,
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(name, NO_ARGS, children, additionalChildren);
	}

	protected AbstractRequireConstraintContainer(
		@Nonnull RequireConstraint[] children,
		@Nonnull Constraint<?>... additionalChildren
	) {
		super(children, additionalChildren);
	}

	protected AbstractRequireConstraintContainer(
		@Nonnull Serializable[] arguments,
		@Nonnull RequireConstraint... children
	) {
		super(arguments, children);
	}

	protected AbstractRequireConstraintContainer(@Nullable String name, @Nonnull RequireConstraint... children) {
		super(name, NO_ARGS, children);
	}

	protected AbstractRequireConstraintContainer(@Nonnull RequireConstraint... children) {
		super(children);
	}

	@Nonnull
	@Override
	public Class<RequireConstraint> getType() {
		return RequireConstraint.class;
	}

	@Override
	public void accept(@Nonnull ConstraintVisitor visitor) {
		visitor.visit(this);
	}

	@Override
	public boolean isNecessary() {
		return true;
	}

}
