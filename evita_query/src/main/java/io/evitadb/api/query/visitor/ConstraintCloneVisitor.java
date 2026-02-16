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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiFunction;

import static java.util.Optional.ofNullable;

/**
 * Creates a clone or a modified copy of the constraint tree, optionally applying transformation logic
 * during the cloning process via a {@link BiFunction} translator.
 *
 * The visitor uses reference identity (==) to detect whether any child changed during traversal.
 * If no children were modified, the original container instance is reused without creating a copy.
 *
 * This class is not thread-safe. Each visitor instance maintains mutable state during traversal
 * and must not be shared between threads. Use the static {@link #clone} method which creates
 * a new visitor instance for each operation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class ConstraintCloneVisitor implements ConstraintVisitor {
	/**
	 * Stack of parent constraints.
	 */
	private final Deque<Constraint<?>> parents = new ArrayDeque<>(16);
	/**
	 * Stack of constraints on the current level.
	 */
	private final Deque<List<Constraint<?>>> levelConstraints = new ArrayDeque<>(16);
	/**
	 * Function to apply during the cloning process.
	 */
	private final BiFunction<ConstraintCloneVisitor, Constraint<?>, Constraint<?>> constraintTranslator;
	/**
	 * Result of the cloning process.
	 */
	private Constraint<?> result = null;

	/**
	 * Creates a clone of the given constraint using the provided constraint translator, if any.
	 *
	 * @param <T> The type of the constraint being cloned.
	 * @param constraint The constraint instance to be cloned. Must not be null.
	 * @param constraintTranslator An optional translation function to apply during the cloning process. May be null.
	 * @return A new instance of the constraint with the applied cloning logic.
	 */
	@Nullable
	public static <T extends Constraint<T>> T clone(@Nonnull T constraint, @Nullable BiFunction<ConstraintCloneVisitor, Constraint<?>, Constraint<?>> constraintTranslator) {
		final ConstraintCloneVisitor visitor = new ConstraintCloneVisitor(constraintTranslator);
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
							"but holds not exactly one child (" + children.length + ")!"
					);
				}
			}
		} else {
			return constraint;
		}
	}

	/**
	 * Returns true only if array and list contents are same - i.e. have same quantity and same instances
	 * in terms of reference identity (==), not value equality. This is intentional for performance:
	 * if no child constraint object was replaced by a new instance, the original container can be reused.
	 */
	private static boolean isEqual(@Nonnull Constraint<?>[] constraints, @Nonnull List<Constraint<?>> comparedConstraints) {
		if (constraints.length != comparedConstraints.size()) {
			return false;
		}
		for (int i = 0; i < constraints.length; i++) {
			final Constraint<?> constraint = constraints[i];
			final Constraint<?> comparedConstraint = comparedConstraints.get(i);
			if (constraint != comparedConstraint) {
				return false;
			}
		}
		return true;
	}

	private ConstraintCloneVisitor() {
		this(null);
	}

	private ConstraintCloneVisitor(@Nullable BiFunction<ConstraintCloneVisitor, Constraint<?>, Constraint<?>> constraintTranslator) {
		this.constraintTranslator = ofNullable(constraintTranslator).orElse((me, constraint) -> constraint);
	}

	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		if (constraint instanceof final ConstraintContainer<?> container) {
			this.parents.push(container);
			try {
				final Constraint<?> translatedConstraint = this.constraintTranslator.apply(this, constraint);
				if (translatedConstraint == constraint) {
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
						createNewContainerWithModifiedChildren(container, children, additionalChildren);
					}
				} else {
					addOnCurrentLevel(translatedConstraint);
				}
			} finally {
				this.parents.pop();
			}
		} else if (constraint instanceof ConstraintLeaf) {
			addOnCurrentLevel(this.constraintTranslator.apply(this, constraint));
		}
	}

	/**
	 * Traverses the children of the passed container applying the cloning logic of this visitor and returns
	 * the resulting list of (potentially transformed) children.
	 *
	 * This method is designed to be called from within the {@link #constraintTranslator} lambda
	 * when the translator needs to inspect or transform children before deciding how to handle
	 * a container constraint. It pushes a new level onto the internal stack, visits each child,
	 * and pops the collected results.
	 *
	 * @param constraint the container whose children should be analyzed
	 * @return list of cloned/transformed children
	 */
	@Nonnull
	public List<Constraint<?>> analyseChildren(@Nonnull ConstraintContainer<?> constraint) {
		this.levelConstraints.push(new ArrayList<>(constraint.getChildrenCount()));
		for (Constraint<?> innerConstraint : constraint) {
			innerConstraint.accept(this);
		}
		return this.levelConstraints.pop();
	}

	@Nullable
	public Constraint<?> getResult() {
		return this.result;
	}

	/**
	 * Adds normalized constraint to the new composition.
	 */
	public void addOnCurrentLevel(@Nullable Constraint<?> constraint) {
		if (constraint != null && constraint.isApplicable()) {
			if (this.levelConstraints.isEmpty()) {
				this.result = getFlattenedResult(constraint);
			} else {
				this.levelConstraints.peek().add(getFlattenedResult(constraint));
			}
		}
	}

	/**
	 * Determines if the current constraint is within the specified filter class type.
	 *
	 * @param filterClass The class of type `FilterConstraint` against which the check will be performed.
	 *                    This parameter must not be null.
	 * @return true if any parent constraint in the chain is an instance of the specified filter class, false otherwise.
	 */
	public boolean isWithin(@Nonnull Class<? extends FilterConstraint> filterClass) {
		return this.parents.stream().anyMatch(filterClass::isInstance);
	}

	/**
	 * Creates new immutable container with modified count of children.
	 */
	@SuppressWarnings("DuplicatedCode")
	private <T extends Constraint<T>> void createNewContainerWithModifiedChildren(
		@Nonnull ConstraintContainer<T> container,
		@Nonnull List<Constraint<?>> modifiedChildren,
		@Nonnull List<Constraint<?>> modifiedAdditionalChildren
	) {
		//noinspection unchecked,SuspiciousToArrayCall
		final T[] newChildren = modifiedChildren.toArray(value -> (T[]) Array.newInstance(container.getType(), 0));
		final Constraint<?>[] newAdditionalChildren = modifiedAdditionalChildren.toArray(Constraint<?>[]::new);
		final Constraint<?> copyWithNewChildren = container.getCopyWithNewChildren(newChildren, newAdditionalChildren);
		if (copyWithNewChildren.isApplicable()) {
			addOnCurrentLevel(getFlattenedResult(copyWithNewChildren));
		}
	}
}
