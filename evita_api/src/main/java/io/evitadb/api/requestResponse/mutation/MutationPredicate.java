/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.requestResponse.mutation;

import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Base ancestor for all mutation predicates. Maintains shared context, that allow to access necessary information to
 * convert {@link Mutation} to {@link ChangeCatalogCapture}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public abstract class MutationPredicate implements Predicate<Mutation> {
	protected final MutationPredicateContext context;

	/**
	 * Method returns the context of the mutation predicate.
	 * @return context of the mutation predicate
	 */
	@Nonnull
	public MutationPredicateContext getContext() {
		return this.context;
	}

	/**
	 * Returns a composed predicate that represents a short-circuiting logical
	 * AND of this predicate and another.  When evaluating the composed
	 * predicate, if this predicate is {@code false}, then the {@code other}
	 * predicate is not evaluated.
	 *
	 * <p>Any exceptions thrown during evaluation of either predicate are relayed
	 * to the caller; if evaluation of this predicate throws an exception, the
	 * {@code other} predicate will not be evaluated.
	 *
	 * @param other a predicate that will be logically-ANDed with this
	 *              predicate
	 * @return a composed predicate that represents the short-circuiting logical
	 * AND of this predicate and the {@code other} predicate
	 * @throws NullPointerException if other is null
	 */
	@Nonnull
	public MutationPredicate and(@Nonnull MutationPredicate other) {
		Objects.requireNonNull(other);
		return new AndMutationPredicate(this, other);
	}

	/**
	 * Returns a composed predicate that represents a short-circuiting logical AND of this predicate and another.
	 */
	private static class AndMutationPredicate extends MutationPredicate {
		private final MutationPredicate former;
		private final MutationPredicate other;

		public AndMutationPredicate(@Nonnull MutationPredicate former, @Nonnull MutationPredicate other) {
			super(former.getContext());
			this.former = former;
			this.other = other;
			Assert.isPremiseValid(
				former.getContext().equals(other.getContext()),
				"Contexts of the predicates must be the same"
			);
		}

		@Override
		public boolean test(Mutation mutation) {
			return former.test(mutation) && other.test(mutation);
		}
	}
}
