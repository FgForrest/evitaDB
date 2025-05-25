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

package io.evitadb.api.query.visitor;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.UnaryOperator;

import static java.util.Optional.ofNullable;

/**
 * Returns this query or copy of this query without constraints that make no sense or are unnecessary. In other
 * words - all constraints that has not all required arguments (not {@link Constraint#isApplicable()}) are removed
 * from the query, all query containers that are {@link ConstraintContainer#isNecessary()} are removed
 * and their contents are propagated to their parent.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class QueryPurifierVisitor implements ConstraintVisitor {
	private final Deque<List<Constraint<?>>> levelConstraints = new ArrayDeque<>(16);
	private final UnaryOperator<Constraint<?>> constraintTranslator;
	private Constraint<?> result = null;

	@Nullable
	public static <T extends Constraint<T>> T purify(@Nonnull T constraint) {
		final QueryPurifierVisitor visitor = new QueryPurifierVisitor();
		constraint.accept(visitor);
		//noinspection unchecked
		return (T) visitor.getResult();
	}

	@Nullable
	public static <T extends Constraint<T>> T purify(@Nonnull T constraint, @Nullable UnaryOperator<T> constraintTranslator) {
		//noinspection unchecked
		final QueryPurifierVisitor visitor = new QueryPurifierVisitor((UnaryOperator<Constraint<?>>) constraintTranslator);
		constraint.accept(visitor);
		//noinspection unchecked
		return (T) visitor.getResult();
	}

	/**
	 * Flattens constraint container if it's not necessary according to {@link ConstraintContainer#isNecessary()} logic.
	 */
	private static Constraint<?> getFlattenedResult(@Nonnull Constraint<?> constraint) {
		if (constraint instanceof final ConstraintContainer<?> constraintContainer) {
			if (constraintContainer.isNecessary()) {
				return constraint;
			} else {
				final Constraint<?>[] children = constraintContainer.getChildren();
				if (children.length == 1) {
					return children[0];
				} else {
					throw new GenericEvitaInternalError(
						"Constraint container " + constraintContainer.getName() + " states it's not necessary, " +
							"but holds not exactly " + children.length + " child(ren)!"
					);
				}
			}
		} else {
			return constraint;
		}
	}

	/**
	 * Returns true only if array and list contents are same - i.e. has same count and same instances (in terms of reference
	 * identity).
	 */
	private static boolean isEqual(@Nonnull Constraint<?>[] constraints, @Nonnull List<Constraint<?>> comparedConstraints) {
		if (constraints.length != comparedConstraints.size()) {
			return false;
		}
		for (int i = 0; i < constraints.length; i++) {
			Constraint<?> constraint = constraints[i];
			Constraint<?> comparedConstraint = comparedConstraints.get(i);
			if (constraint != comparedConstraint) {
				return false;
			}
		}
		return true;
	}

	private QueryPurifierVisitor() {
		this(null);
	}

	private QueryPurifierVisitor(@Nullable UnaryOperator<Constraint<?>> constraintTranslator) {
		this.constraintTranslator = ofNullable(constraintTranslator).orElse(UnaryOperator.identity());
	}

	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		final boolean applicable = constraint.isApplicable();
		if (constraint instanceof final ConstraintContainer<?> container) {
			this.levelConstraints.push(new ArrayList<>(container.getChildrenCount()));
			for (Constraint<?> child : container) {
				child.accept(this);
			}
			final List<Constraint<?>> children = this.levelConstraints.pop();

			this.levelConstraints.push(new ArrayList<>(container.getAdditionalChildrenCount()));
			for (Constraint<?> additionalChild : container.getAdditionalChildren()) {
				additionalChild.accept(this);
			}
			final List<Constraint<?>> additionalChildren = this.levelConstraints.pop();

			if (isEqual(container.getChildren(), children) &&
				isEqual(container.getAdditionalChildren(), additionalChildren)) {
				addOnCurrentLevel(constraint);
			} else {
				createNewContainerWithReducedChildren(container, children, additionalChildren);
			}
		} else if (constraint instanceof ConstraintLeaf && applicable) {
			addOnCurrentLevel(
				this.constraintTranslator.apply(constraint)
			);
		}
	}

	@Nullable
	public Constraint<?> getResult() {
		return this.result;
	}

	/**
	 * Creates new immutable container with reduced count of children.
	 */
	@SuppressWarnings("DuplicatedCode")
	private <T extends Constraint<T>> void createNewContainerWithReducedChildren(
		@Nonnull ConstraintContainer<T> container,
		@Nonnull List<Constraint<?>> reducedChildren,
		@Nonnull List<Constraint<?>> reducedAdditionalChildren
	) {
		//noinspection unchecked,SuspiciousToArrayCall
		final T[] newChildren = reducedChildren.toArray(value -> (T[]) Array.newInstance(container.getType(), 0));
		final Constraint<?>[] newAdditionalChildren = reducedAdditionalChildren.toArray(Constraint<?>[]::new);
		final Constraint<?> copyWithNewChildren = container.getCopyWithNewChildren(newChildren, newAdditionalChildren);
		if (copyWithNewChildren.isApplicable()) {
			addOnCurrentLevel(getFlattenedResult(copyWithNewChildren));
		}
	}

	/**
	 * Adds normalized constraint to the new composition.
	 */
	private void addOnCurrentLevel(@Nullable Constraint<?> constraint) {
		if (constraint != null && constraint.isApplicable()) {
			if (this.levelConstraints.isEmpty()) {
				this.result = getFlattenedResult(constraint);
			} else {
				this.levelConstraints.peek().add(getFlattenedResult(constraint));
			}
		}
	}
}
