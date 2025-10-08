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
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * This interface provides utility methods for functional programming constructs.
 * It contains static methods that return commonly used functional interfaces with predefined behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface Functions {

	/**
	 * Returns a no-operation consumer that accepts any object but performs no action.
	 * This is useful in scenarios where a Consumer is required by an API but no action needs to be performed,
	 * avoiding the need to create anonymous implementations or lambda expressions for such cases.
	 *
	 * @param <T> the type of the input to the operation
	 * @return a Consumer that performs no operation
	 */
	@Nonnull
	static <T> Consumer<T> noOpConsumer() {
		return t -> {};
	}

	/**
	 * Returns a no-operation {@link Runnable} that performs no action when run.
	 * This can be used in scenarios where a {@link Runnable} is required but no specific action is needed.
	 *
	 * @return a {@link Runnable} that performs no operation
	 */
	@Nonnull
	static Runnable noOpRunnable() {
		return () -> {};
	}

	/**
	 * Returns a no-operation function that takes an input argument and always returns null.
	 * This can be useful in scenarios where a Function is required, but no meaningful operation or computation is performed.
	 *
	 * @param <T> the type of the input to the function
	 * @param <U> the type of the result of the function
	 * @return a Function that accepts an input of type T and always returns null
	 */
	@Nonnull
	static <T, U> Function<T, U> noOpFunction() {
		return t -> null;
	}

	/**
	 * Returns a no-operation function that takes a two input arguments and always returns null.
	 * This can be useful in scenarios where a Function is required, but no meaningful operation or computation is performed.
	 *
	 * @param <S> the type of the first input to the function
	 * @param <T> the type of the second input to the function
	 * @param <U> the type of the result of the function
	 * @return a Function that accepts an input of type T and always returns null
	 */
	@Nonnull
	static <S, T, U> BiFunction<S, T, U> noOpBiFunction() {
		return (s, t) -> null;
	}

	/**
	 * Returns a no-operation BiConsumer that accepts two input arguments but performs no action.
	 * This is useful in scenarios where a BiConsumer is required by an API but no action needs to be performed,
	 * avoiding the need to create anonymous implementations or lambda expressions for such cases.
	 *
	 * @param <T> the type of the first input argument
	 * @param <U> the type of the second input argument
	 * @return a BiConsumer that performs no operation
	 */
	@Nonnull
	static <T, U> BiConsumer<T, U> noOpBiConsumer() {
		return (t, u) -> {};
	}

	/**
	 * Returns a no-operation LongConsumer that accepts a long value but performs no action.
	 * This is useful in scenarios where a LongConsumer is required by an API but no action needs to be performed,
	 * avoiding the need to create anonymous implementations or lambda expressions for such cases.
	 *
	 * @return a LongConsumer that performs no operation
	 */
	@Nonnull
	static LongConsumer noOpLongConsumer() {
		return l -> {
			// no operation performed
		};
	}

	/**
	 * Returns a no-operation IntConsumer that accepts an integer value but performs no action.
	 * This is useful in scenarios where an IntConsumer is required by an API but no action needs to be performed,
	 * avoiding the need to create anonymous implementations or lambda expressions for such cases.
	 *
	 * @return an IntConsumer that performs no operation
	 */
	@Nonnull
	static IntConsumer noOpIntConsumer() {
		return i -> {
			// no operation performed
		};
	}
}
