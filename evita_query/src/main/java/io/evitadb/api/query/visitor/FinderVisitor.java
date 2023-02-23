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
import io.evitadb.api.query.ConstraintVisitor;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;

/**
 * This visitor traverses through specific constraint tree and finds all constraints that match the passed predicate.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class FinderVisitor implements ConstraintVisitor {
	private final List<Constraint<?>> result = new LinkedList<>();
	/**
	 * Predicate that matches constraints, that we're looking for.
	 */
	private final Predicate<Constraint<?>> matcher;
	/**
	 * Predicate that matches containers which contents should not be searched.
	 * If it returns TRUE for the constraint, its contents will not be examined at all.
	 */
	private final Predicate<Constraint<?>> stopper;

	private FinderVisitor(Predicate<Constraint<?>> matcher) {
		this.matcher = matcher;
		this.stopper = constraint -> false;
	}

	/**
	 * Finds all constraints that match the predicate.
	 */
	@Nonnull
	public static <T extends Constraint<?>> List<T> findConstraints(@Nonnull Constraint<?> constraint,
																	@Nonnull Predicate<Constraint<?>> matcher) {
		final FinderVisitor visitor = new FinderVisitor(matcher);
		constraint.accept(visitor);
		//noinspection unchecked
		return (List<T>) visitor.getResults();
	}

	/**
	 * Finds all constraints that match the predicate.
	 */
	@Nullable
	public static <T extends Constraint<?>> T findConstraint(@Nonnull Constraint<?> constraint,
															 @Nonnull Predicate<Constraint<?>> matcher) {
		final FinderVisitor visitor = new FinderVisitor(matcher);
		constraint.accept(visitor);
		//noinspection unchecked
		return (T) visitor.getResult();
	}

	/**
	 * Finds all constraints that match the predicate. Searching excluded contents of all constraints that match
	 * `stopper`.
	 */
	@Nonnull
	public static <T extends Constraint<?>> List<T> findConstraints(@Nonnull Constraint<?> constraint,
																	@Nonnull Predicate<Constraint<?>> matcher,
																	@Nonnull Predicate<Constraint<?>> stopper) {
		final FinderVisitor visitor = new FinderVisitor(matcher, stopper);
		constraint.accept(visitor);
		//noinspection unchecked
		return (List<T>) visitor.getResults();
	}

	/**
	 * Finds all constraints that match the predicate. Searching excluded contents of all constraints that match
	 * `stopper`.
	 */
	@Nullable
	public static <T extends Constraint<?>> T findConstraint(@Nonnull Constraint<?> constraint,
															 @Nonnull Predicate<Constraint<?>> matcher,
															 Predicate<Constraint<?>> stopper) {
		final FinderVisitor visitor = new FinderVisitor(matcher, stopper);
		constraint.accept(visitor);
		//noinspection unchecked
		return (T) visitor.getResult();
	}

	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		if (this.matcher.test(constraint)) {
			this.result.add(constraint);
		}
		if (constraint instanceof final ConstraintContainer<?> cnt && !stopper.test(constraint)) {
			for (Constraint<?> child : cnt.getChildren()) {
				child.accept(this);
			}
			for (Constraint<?> additionalChild : cnt.getAdditionalChildren()) {
				additionalChild.accept(this);
			}
		}
	}

	@Nullable
	public Constraint<?> getResult() throws MoreThanSingleResultException {
		if (result.isEmpty()) {
			return null;
		} else if (result.size() == 1) {
			return result.get(0);
		} else {
			throw new MoreThanSingleResultException(
				"Total " + result.size() + " constraints found in query!"
			);
		}
	}

	public List<Constraint<?>> getResults() {
		return result;
	}

	public static class MoreThanSingleResultException extends IllegalArgumentException {
		@Serial private static final long serialVersionUID = 5992942222164725144L;

		public MoreThanSingleResultException(String s) {
			super(s);
		}

	}

}
