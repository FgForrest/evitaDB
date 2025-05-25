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
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.exception.EvitaInvalidUsageException;
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

	private FinderVisitor(@Nonnull Predicate<Constraint<?>> matcher) {
		this.matcher = matcher;
		this.stopper = constraint -> false;
	}

	/**
	 * Finds all constraints that match the given matcher predicate within the specified constraint container.
	 *
	 * @param constraint the root constraint from which to start the search, must not be null
	 * @param matcher the predicate to match the desired constraints, must not be null
	 * @param <T> the type of the expected constraints that match the predicate
	 * @return a list of constraints that match the given matcher predicate
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
	 * Finds a single constraint within the specified constraint container that matches the given matcher predicate.
	 *
	 * @param constraint the root constraint to start the search from, must not be null
	 * @param matcher the predicate to match the desired constraint, must not be null
	 * @param <T> the type of the expected constraint that matches the predicate
	 * @return the matched constraint, or null if no constraints match
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
	 * Finds all constraints that match the given matcher predicate within the specified constraint container.
	 * The search can be stopped early if the stopper predicate is satisfied.
	 *
	 * @param constraint the root constraint from which to start the search, must not be null
	 * @param matcher the predicate to match the desired constraints, must not be null
	 * @param stopper the predicate to stop the search early, must not be null
	 * @param <T> the type of the expected constraints that match the predicate
	 * @return a list of constraints that match the given matcher predicate
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
	 * Finds a single constraint within the specified constraint container that matches the given matcher predicate.
	 * The search will stop if the constraint matches the stopper predicate.
	 *
	 * @param constraint the root constraint to start the search from, must not be null
	 * @param matcher the predicate to match the desired constraint, must not be null
	 * @param stopper the predicate to stop the search early, must not be null
	 * @param <T> the type of the expected constraint that matches the predicate
	 * @return the matched constraint, or null if no constraints match
	 */
	@Nullable
	public static <T extends Constraint<?>> T findConstraint(
		@Nonnull Constraint<?> constraint,
		@Nonnull Predicate<Constraint<?>> matcher,
		@Nonnull Predicate<Constraint<?>> stopper
	) {
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
		if (constraint instanceof final ConstraintContainer<?> cnt && !this.stopper.test(constraint)) {
			for (Constraint<?> child : cnt.getChildren()) {
				child.accept(this);
			}
			for (Constraint<?> additionalChild : cnt.getAdditionalChildren()) {
				additionalChild.accept(this);
			}
		}
	}

	/**
	 * Retrieves a single constraint if exactly one matches the criteria or null if no constraints match.
	 * Throws an exception if more than one constraint matches.
	 *
	 * @return the matched constraint, or null if no constraints match
	 * @throws MoreThanSingleResultException if more than one constraint matches
	 */
	@Nullable
	public Constraint<?> getResult() throws MoreThanSingleResultException {
		if (this.result.isEmpty()) {
			return null;
		} else if (this.result.size() == 1) {
			return this.result.get(0);
		} else if (this.matcher instanceof PredicateWithDescription<?> withDescription) {
			throw new MoreThanSingleResultException(
				"A total of `" + this.result.size() + "` constraints were found in a query that searched for " + withDescription + ", but only one was expected!"
			);
		} else {
			throw new MoreThanSingleResultException(
				"A total of `" + this.result.size() + "` constraints were found in a query, but expected is only one!"
			);
		}
	}

	/**
	 * Retrieves a list of constraints that matched the predicate during traversal.
	 *
	 * @return a list of matched constraints.
	 */
	@Nonnull
	public List<Constraint<?>> getResults() {
		return this.result;
	}

	public static class MoreThanSingleResultException extends EvitaInvalidUsageException {
		@Serial private static final long serialVersionUID = 5992942222164725144L;

		public MoreThanSingleResultException(@Nonnull String publicMessage) {
			super(publicMessage);
		}

	}

	@FunctionalInterface
	public interface PredicateWithDescription<T> extends Predicate<T> {

		/**
		 * Returns a human-readable description of the predicate.
		 * @return a human-readable description of the predicate
		 */
		@Override
		@Nonnull
		String toString();

	}

}
