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

package io.evitadb.utils;


import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Utility class providing helper methods for working with exceptions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ExceptionUtils {

	/**
	 * Upper bound on the number of {@link CompletionException} / {@link ExecutionException} layers
	 * {@link #unwrapCompletionWrappers(Throwable)} will peel before giving up. Deeply nested async
	 * chains (nested `thenCompose`, `CompletableFuture#get` over a `join`) can legitimately stack a
	 * few wrappers, but going deeper than this signals a self-referential cycle that should not be
	 * followed.
	 */
	public static final int UNWRAP_COMPLETION_WRAPPERS_MAX_DEPTH = 10;

	/**
	 * Finds the root cause of an exception by traversing the exception chain.
	 * This method handles circular references in the exception chain by keeping track of visited exceptions.
	 *
	 * @param throwable the throwable to find the root cause for, must not be null
	 * @return the root cause of the throwable, or the throwable itself if it has no cause
	 */
	@Nonnull
	public static Throwable getRootCause(@Nonnull Throwable throwable) {
		Throwable rootCause = throwable;
		final Set<Throwable> visited = new HashSet<>();

		while (rootCause.getCause() != null && !visited.contains(rootCause)) {
			visited.add(rootCause);
			rootCause = rootCause.getCause();
		}

		return rootCause;
	}

	/**
	 * Checks if the provided throwable or any of its causes matches the specified exception type.
	 * The method traverses the causal chain, handling circular references safely.
	 *
	 * @param throwable the throwable to evaluate, must not be null
	 * @param exceptionType the class of the exception type to check for, must not be null
	 * @return true if the specified exception type is found in the causal chain, false otherwise
	 */
	public static boolean causeChainContains(
		@Nonnull Throwable throwable,
		@Nonnull Class<? extends Throwable> exceptionType
	) {
		final Set<Throwable> visited = new HashSet<>();
		Throwable current = throwable;
		while (current != null && visited.add(current)) {
			if (exceptionType.isInstance(current)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	/**
	 * Walks the cause chain of the supplied throwable, peeling every {@link CompletionException} and
	 * {@link ExecutionException} wrapper layer so callers can pattern-match the original exception
	 * type. `CompletableFuture` / `ProgressingFuture` chains commonly produce multiply-wrapped
	 * failures (e.g. nested `thenCompose` wraps both layers in `CompletionException`), so a
	 * single-layer peel would hide a domain exception behind a generic wrapper and miss the
	 * pattern-match.
	 *
	 * Stops at the first non-wrapper layer, on a null cause, on a cause already visited (cycle), or
	 * after {@link #UNWRAP_COMPLETION_WRAPPERS_MAX_DEPTH} layers (defensive — prevents an unbounded
	 * loop should a pathological chain form a longer cycle, and bounds work for legitimately deep
	 * chains). When a cycle is detected the last wrapper held is returned; when the depth cap is
	 * reached the originally-passed throwable is returned unchanged so the caller's pattern-match
	 * simply misses rather than operating on a possibly-stale intermediate layer.
	 *
	 * Note: only `CompletionException` and `ExecutionException` are peeled — domain exceptions
	 * encountered along the way are returned as-is, preserving any further cause chain they carry.
	 *
	 * @param throwable the exception to unwrap, must not be null
	 * @return the deepest non-wrapper cause, the original throwable if nothing to unwrap or the
	 *         depth cap is reached, or the last wrapper held when a cycle is detected
	 */
	@Nonnull
	public static Throwable unwrapCompletionWrappers(@Nonnull Throwable throwable) {
		final Set<Throwable> visited = CollectionUtils.createHashSet(UNWRAP_COMPLETION_WRAPPERS_MAX_DEPTH);
		Throwable current = throwable;
		// Bounded walk — peel CompletionException / ExecutionException layers until a non-wrapper,
		// a null cause, a previously-visited cause (cycle), or the depth cap is reached.
		for (int depth = 0; depth < UNWRAP_COMPLETION_WRAPPERS_MAX_DEPTH; depth++) {
			if (!(current instanceof CompletionException) && !(current instanceof ExecutionException)) {
				return current;
			}
			if (!visited.add(current)) {
				// Cycle detected — return whatever wrapper we currently hold.
				return current;
			}
			final Throwable cause = current.getCause();
			if (cause == null) {
				return current;
			}
			current = cause;
		}
		// Depth cap reached — fall back to the originally-passed throwable so the caller's
		// pattern-match simply misses rather than operating on a possibly-stale intermediate layer.
		return throwable;
	}

	/**
	 * Executes the given supplier and unwraps any {@link CompletionException} that is thrown,
	 * rethrowing its cause if the cause is a {@link RuntimeException}. If the cause is not
	 * a RuntimeException, the original CompletionException is rethrown.
	 *
	 * @param <T> the type of result supplied by the given supplier
	 * @param supplier the supplier to execute, must not be null
	 * @return the result of the supplier
	 * @throws CompletionException if the supplier throws a CompletionException whose cause is not a RuntimeException
	 * @throws RuntimeException if the cause of a thrown CompletionException is a RuntimeException
	 */
	public static <T> T unwrapCompletionException(@Nonnull Supplier<T> supplier) {
		try {
			return supplier.get();
		} catch (CompletionException ex) {
			if (ex.getCause() instanceof RuntimeException rex) {
				throw rex;
			} else {
				throw ex;
			}
		}
	}

}
