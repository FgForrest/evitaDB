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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.function.BiFunction;

import static java.util.Optional.ofNullable;

/**
 * Returns this constraint or copy of this constraint applying the transformation logic.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ConstraintCloneVisitor implements ConstraintVisitor {
	private final Deque<List<Constraint<?>>> levelConstraints = new LinkedList<>();
	private final BiFunction<ConstraintCloneVisitor, Constraint<?>, Constraint<?>> constraintTranslator;
	private Constraint<?> result = null;

	private ConstraintCloneVisitor() {
		this(null);
	}

	private ConstraintCloneVisitor(@Nullable BiFunction<ConstraintCloneVisitor, Constraint<?>, Constraint<?>> constraintTranslator) {
		this.constraintTranslator = ofNullable(constraintTranslator).orElse((me, constraint) -> constraint);
	}

	public static <T extends Constraint<T>> T clone(@Nonnull T constraint, @Nullable BiFunction<ConstraintCloneVisitor, Constraint<?>, Constraint<?>> constraintTranslator) {
		final ConstraintCloneVisitor visitor = new ConstraintCloneVisitor(constraintTranslator);
		constraint.accept(visitor);
		//noinspection unchecked
		return (T) visitor.getResult();
	}

	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		if (constraint instanceof final ConstraintContainer<?> container) {
			final Constraint<?> translatedConstraint = constraintTranslator.apply(this, constraint);
			if (translatedConstraint == constraint) {
				levelConstraints.push(new ArrayList<>(container.getChildrenCount()));
				for (Constraint<?> child : container) {
					child.accept(this);
				}
				final List<Constraint<?>> children = levelConstraints.pop();

				levelConstraints.push(new ArrayList<>(container.getAdditionalChildrenCount()));
				for (Constraint<?> additionalChild : container.getAdditionalChildren()) {
					additionalChild.accept(this);
				}
				final List<Constraint<?>> additionalChildren = levelConstraints.pop();

				if (isEqual(container.getChildren(), children) &&
					isEqual(container.getAdditionalChildren(), additionalChildren)) {
					addOnCurrentLevel(constraint);
				} else {
					createNewContainerWithModifiedChildren(container, children, additionalChildren);
				}
			} else {
				addOnCurrentLevel(translatedConstraint);
			}
		} else if (constraint instanceof ConstraintLeaf) {
			addOnCurrentLevel(constraintTranslator.apply(this, constraint));
		}
	}

	/**
	 * Method traverses the passed container applying cloning logic of this visitor. The method is expected to be called
	 * from within the {@link #constraintTranslator} lambda.
	 */
	public List<Constraint<?>> analyseChildren(ConstraintContainer<?> constraint) {
		levelConstraints.push(new ArrayList<>(constraint.getChildrenCount()));
		for (Constraint<?> innerConstraint : constraint) {
			innerConstraint.accept(this);
		}
		return levelConstraints.pop();
	}

	public Constraint<?> getResult() {
		return result;
	}

	/**
	 * Creates new immutable container with modified count of children.
	 */
	private <T extends Constraint<T>> void createNewContainerWithModifiedChildren(ConstraintContainer<T> container,
														List<Constraint<?>> modifiedChildren,
														List<Constraint<?>> modifiedAdditionalChildren) {
		//noinspection unchecked
		final T[] newChildren = modifiedChildren.toArray(value -> (T[]) Array.newInstance(container.getType(), 0));
		final Constraint<?>[] newAdditionalChildren = modifiedAdditionalChildren.toArray(Constraint<?>[]::new);
		final Constraint<?> copyWithNewChildren = container.getCopyWithNewChildren(newChildren, newAdditionalChildren);
		if (copyWithNewChildren.isApplicable()) {
			addOnCurrentLevel(getFlattenedResult(copyWithNewChildren));
		}
	}

	/**
	 * Adds normalized constraint to the new composition.
	 */
	private void addOnCurrentLevel(Constraint<?> constraint) {
		if (constraint != null && constraint.isApplicable()) {
			if (levelConstraints.isEmpty()) {
				result = getFlattenedResult(constraint);
			} else {
				levelConstraints.peek().add(getFlattenedResult(constraint));
			}
		}
	}

	/**
	 * Flattens constraint container if it's not necessary according to {@link ConstraintContainer#isNecessary()} logic.
	 */
	private Constraint<?> getFlattenedResult(Constraint<?> constraint) {
		if (constraint instanceof final ConstraintContainer<?> constraintContainer) {
			if (constraintContainer.isNecessary()) {
				return constraint;
			} else {
				final Constraint<?>[] children = constraintContainer.getChildren();
				if (children.length == 1) {
					return children[0];
				} else {
					throw new EvitaInternalError(
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
	 * Returns true only if array and list contents are same - i.e. have same quantity, and same instances (in terms of
	 * reference identity).
	 */
	private boolean isEqual(Constraint<?>[] constraints, List<Constraint<?>> comparedConstraints) {
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
}
