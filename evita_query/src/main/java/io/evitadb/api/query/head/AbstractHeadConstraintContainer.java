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

package io.evitadb.api.query.head;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.HeadConstraint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Represents base query container accepting only heading constraints.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
abstract class AbstractHeadConstraintContainer extends ConstraintContainer<HeadConstraint> implements HeadConstraint {
	@Serial private static final long serialVersionUID = -6235571323977842467L;

	protected AbstractHeadConstraintContainer(@Nonnull Serializable[] arguments, @Nonnull HeadConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(arguments, children, additionalChildren);
	}

	protected AbstractHeadConstraintContainer(@Nullable String name, @Nonnull Serializable[] arguments, @Nonnull HeadConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(name, arguments, children, additionalChildren);
	}

	protected AbstractHeadConstraintContainer(@Nonnull HeadConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(children, additionalChildren);
	}

	protected AbstractHeadConstraintContainer(@Nullable String name, @Nonnull HeadConstraint[] children, @Nonnull Constraint<?>... additionalChildren) {
		super(name, NO_ARGS, children, additionalChildren);
	}

	protected AbstractHeadConstraintContainer(Serializable[] arguments, HeadConstraint... children) {
		super(arguments, children);
	}

	protected AbstractHeadConstraintContainer(@Nullable String name, Serializable[] arguments, HeadConstraint... children) {
		super(name, arguments, children);
	}

	protected AbstractHeadConstraintContainer(Serializable argument, HeadConstraint... children) {
		super(new Serializable[] {argument}, children);
	}

	protected AbstractHeadConstraintContainer(@Nullable String name, Serializable argument, HeadConstraint... children) {
		super(name, new Serializable[] {argument}, children);
	}

	protected AbstractHeadConstraintContainer(Serializable argument1, Serializable argument2, HeadConstraint... children) {
		super(new Serializable[] {argument1, argument2}, children);
	}

	protected AbstractHeadConstraintContainer(@Nullable String name, Serializable argument1, Serializable argument2, HeadConstraint... children) {
		super(name, new Serializable[] {argument1, argument2}, children);
	}

	protected AbstractHeadConstraintContainer(HeadConstraint... children) {
		super(children);
	}

	protected AbstractHeadConstraintContainer(@Nullable String name, HeadConstraint... children) {
		super(name, children);
	}

	@Nonnull
	@Override
	public Class<HeadConstraint> getType() {
		return HeadConstraint.class;
	}

	@Override
	public void accept(@Nonnull ConstraintVisitor visitor) {
		visitor.visit(this);
	}

}
