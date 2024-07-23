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
 * Represents base constraint container accepting only requirement constraints.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
abstract class AbstractRequireConstraintContainer extends ConstraintContainer<RequireConstraint> implements RequireConstraint {
	@Serial private static final long serialVersionUID = 5596073952193919059L;

	public AbstractRequireConstraintContainer(@Nullable String name, @Nonnull Serializable[] arguments, @Nonnull RequireConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(name, arguments, children, additionalChildren);
	}

	public AbstractRequireConstraintContainer(@Nullable String name, @Nonnull Serializable[] arguments, @Nonnull RequireConstraint... children) {
		super(name, arguments, children);
	}

	protected AbstractRequireConstraintContainer(@Nonnull Serializable[] arguments, @Nonnull RequireConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(arguments, children, additionalChildren);
	}

	protected AbstractRequireConstraintContainer(@Nullable String name, @Nonnull RequireConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(name, NO_ARGS, children, additionalChildren);
	}

	protected AbstractRequireConstraintContainer(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(children, additionalChildren);
	}

	protected AbstractRequireConstraintContainer(Serializable[] arguments, RequireConstraint... children) {
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
