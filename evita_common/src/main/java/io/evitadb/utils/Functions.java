/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * Utility class providing reusable functional interface constants and factory methods.
 * Avoids repeated creation of trivial lambda instances (e.g. `x -> true`) across the codebase.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Functions {

	/**
	 * A predicate that always returns `true` regardless of the input.
	 */
	private static final Predicate<?> ALWAYS_TRUE = t -> true;
	/**
	 * A predicate that always returns `false` regardless of the input.
	 */
	private static final Predicate<?> ALWAYS_FALSE = t -> false;
	/**
	 * An int predicate that always returns `true` regardless of the input.
	 */
	private static final IntPredicate INT_ALWAYS_TRUE = i -> true;
	/**
	 * An int predicate that always returns `false` regardless of the input.
	 */
	private static final IntPredicate INT_ALWAYS_FALSE = i -> false;

	/**
	 * Returns a predicate that always evaluates to `true`.
	 * Avoids allocating a new lambda instance on every call site.
	 *
	 * @param <T> the type of the input to the predicate
	 * @return a predicate that always returns `true`
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	public static <T> Predicate<T> alwaysTrue() {
		return (Predicate<T>) ALWAYS_TRUE;
	}

	/**
	 * Returns a predicate that always evaluates to `false`.
	 * Avoids allocating a new lambda instance on every call site.
	 *
	 * @param <T> the type of the input to the predicate
	 * @return a predicate that always returns `false`
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	public static <T> Predicate<T> alwaysFalse() {
		return (Predicate<T>) ALWAYS_FALSE;
	}

	/**
	 * Returns an int predicate that always evaluates to `true`.
	 * Avoids allocating a new lambda instance on every call site.
	 *
	 * @return an int predicate that always returns `true`
	 */
	@Nonnull
	public static IntPredicate intAlwaysTrue() {
		return INT_ALWAYS_TRUE;
	}

	/**
	 * Returns an int predicate that always evaluates to `false`.
	 * Avoids allocating a new lambda instance on every call site.
	 *
	 * @return an int predicate that always returns `false`
	 */
	@Nonnull
	public static IntPredicate intAlwaysFalse() {
		return INT_ALWAYS_FALSE;
	}

}
