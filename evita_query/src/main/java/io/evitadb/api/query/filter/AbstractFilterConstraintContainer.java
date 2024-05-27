/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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
 * Represents base query container accepting only filtering constraints.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
abstract class AbstractFilterConstraintContainer extends ConstraintContainer<FilterConstraint> implements FilterConstraint {
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

	protected AbstractFilterConstraintContainer(Serializable[] arguments, FilterConstraint... children) {
		super(arguments, children);
	}

	protected AbstractFilterConstraintContainer(@Nullable String name, Serializable[] arguments, FilterConstraint... children) {
		super(name, arguments, children);
	}

	protected AbstractFilterConstraintContainer(Serializable argument, FilterConstraint... children) {
		super(new Serializable[] {argument}, children);
	}

	protected AbstractFilterConstraintContainer(@Nullable String name, Serializable argument, FilterConstraint... children) {
		super(name, new Serializable[] {argument}, children);
	}

	protected AbstractFilterConstraintContainer(Serializable argument1, Serializable argument2, FilterConstraint... children) {
		super(new Serializable[] {argument1, argument2}, children);
	}

	protected AbstractFilterConstraintContainer(@Nullable String name, Serializable argument1, Serializable argument2, FilterConstraint... children) {
		super(name, new Serializable[] {argument1, argument2}, children);
	}

	protected AbstractFilterConstraintContainer(FilterConstraint... children) {
		super(children);
	}

	protected AbstractFilterConstraintContainer(@Nullable String name, FilterConstraint... children) {
		super(name, children);
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
