/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.function;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * A primitive-specialized predicate of two arguments: an int and an object of type {@code T}.
 * This interface avoids boxing of the int compared to using {@code BiPredicate<Integer, T>}.
 *
 * The functional method is {@link #test(int, Object)}.
 *
 * - Inspired by JDK's {@code IntPredicate} and {@code BiPredicate}
 * - Provides short-circuiting combinators: {@link #and(IntObjPredicate)}, {@link #or(IntObjPredicate)}
 *   and {@link #negate()}
 *
 * @param <T> type of the object argument
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @apiNote inspired by the JDK interface
 */
@FunctionalInterface
public interface IntObjPredicate<T> {
	/**
	 * Evaluates this predicate on the given arguments.
	 *
	 * @param value the int input argument
	 * @param obj the object input argument
	 * @return {@code true} if the input arguments match the predicate; otherwise {@code false}
	 */
	boolean test(int value, @Nonnull T obj);

	/**
	 * Returns a predicate that represents the logical negation of this predicate.
	 *
	 * This method has the same semantics as JDK's {@code IntPredicate.negate()}.
	 *
	 * @return a predicate that negates the result of this predicate
	 */
	@Nonnull
	default IntObjPredicate<T> negate() {
		return (int value, @Nonnull T obj) -> !this.test(value, obj);
	}

	/**
	 * Returns a composed predicate that represents a short-circuiting logical AND of this predicate and the
	 * {@code other} predicate. When this predicate returns {@code false}, the {@code other} predicate is not
	 * evaluated.
	 *
	 * This method has the same semantics as JDK's {@code IntPredicate.and(IntPredicate)}.
	 *
	 * @param other the predicate to combine with using logical AND; must not be {@code null}
	 * @return a composed predicate that represents the logical AND of this predicate and {@code other}
	 * @throws NullPointerException if {@code other} is {@code null}
	 */
	@Nonnull
	default IntObjPredicate<T> and(@Nonnull IntObjPredicate<? super T> other) {
		final IntObjPredicate<? super T> otherPredicate = Objects.requireNonNull(other, "other");
		// short-circuit: evaluate other only if this returns true
		return (int value, @Nonnull T obj) -> this.test(value, obj) && otherPredicate.test(value, obj);
	}

	/**
	 * Returns a composed predicate that represents a short-circuiting logical OR of this predicate and the
	 * {@code other} predicate. When this predicate returns {@code true}, the {@code other} predicate is not
	 * evaluated.
	 *
	 * This method has the same semantics as JDK's {@code IntPredicate.or(IntPredicate)}.
	 *
	 * @param other the predicate to combine with using logical OR; must not be {@code null}
	 * @return a composed predicate that represents the logical OR of this predicate and {@code other}
	 * @throws NullPointerException if {@code other} is {@code null}
	 */
	@Nonnull
	default IntObjPredicate<T> or(@Nonnull IntObjPredicate<? super T> other) {
		final IntObjPredicate<? super T> otherPredicate = Objects.requireNonNull(other, "other");
		// short-circuit: evaluate other only if this returns false
		return (int value, @Nonnull T obj) -> this.test(value, obj) || otherPredicate.test(value, obj);
	}
}
