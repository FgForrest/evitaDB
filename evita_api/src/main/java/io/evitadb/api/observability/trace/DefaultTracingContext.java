/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.api.observability.trace;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * No-op implementation of {@link TracingContext} that performs no tracing operations. This class
 * serves as a null object pattern to avoid null checks throughout the codebase when observability
 * is disabled or not configured.
 *
 * **Design Purpose:**
 * When no OpenTelemetry or other tracing backend is available, this implementation allows all
 * tracing-aware code to execute normally without overhead. All `executeWithinBlock` methods simply
 * invoke the provided lambda/runnable directly without creating spans or recording context.
 *
 * **Usage Context:**
 * - Default fallback used by {@link TracingContextProvider} when no ServiceLoader-provided
 * implementation is found
 * - Returns empty `Optional` values for trace ID, client ID, and other context metadata
 * - All block-creation methods return {@link DefaultTracingBlockReference} (another no-op)
 *
 * **Singleton Pattern:**
 * Access via `INSTANCE` constant to avoid unnecessary allocations.
 *
 * **Thread-Safety:**
 * This class is stateless and thread-safe.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DefaultTracingContext implements TracingContext {
	/**
	 * Singleton instance of the no-op tracing context. Used by {@link TracingContextProvider} as
	 * the default implementation when no real tracing backend is configured.
	 */
	public static final TracingContext INSTANCE = new DefaultTracingContext();

	/**
	 * Reusable no-op context reference returned by {@link #getCurrentContext()}. Since this
	 * implementation is stateless, the same instance can be reused across all calls.
	 */
	private static final TracingContextReference<?> EMPTY_CONTEXT_HOLDER = new DefaultTracingContextReference();

	/**
	 * Returns a static no-op context reference. Since this implementation does not maintain any
	 * real tracing context, the same empty reference is returned for all calls.
	 *
	 * @return a reusable {@link DefaultTracingContextReference} instance
	 */
	@Override
	public TracingContextReference<?> getCurrentContext() {
		// this is dummy implementation, it doesn't do anything
		return EMPTY_CONTEXT_HOLDER;
	}

	/**
	 * Returns an empty Optional since no tracing is active in this implementation.
	 *
	 * @return empty Optional (always)
	 */
	@Nonnull
	@Override
	public Optional<String> getTraceId() {
		return Optional.empty();
	}

	/**
	 * Returns an empty Optional since no client context is tracked in this implementation.
	 *
	 * @return empty Optional (always)
	 */
	@Nonnull
	@Override
	public Optional<String> getClientId() {
		return Optional.empty();
	}

	/**
	 * Executes the runnable directly without creating a trace span. The taskName and attributes
	 * are ignored.
	 *
	 * @param taskName   ignored
	 * @param runnable   the code to execute
	 * @param attributes ignored
	 */
	@Override
	public void executeWithinBlock(
		@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable SpanAttribute... attributes) {
		runnable.run();
	}

	/**
	 * Executes the supplier directly without creating a trace span. The taskName and attributes
	 * are ignored.
	 *
	 * @param taskName   ignored
	 * @param lambda     the code to execute
	 * @param attributes ignored
	 * @param <T>        return type
	 * @return the result of invoking the supplier
	 */
	@Override
	public <T> T executeWithinBlock(
		@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable SpanAttribute... attributes) {
		return lambda.get();
	}

	/**
	 * Executes the runnable directly without creating a trace span. The taskName and attributes
	 * supplier are ignored.
	 *
	 * @param taskName   ignored
	 * @param runnable   the code to execute
	 * @param attributes ignored (supplier is never invoked)
	 */
	@Override
	public void executeWithinBlock(
		@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes) {
		runnable.run();
	}

	/**
	 * Executes the supplier directly without creating a trace span. The taskName and attributes
	 * supplier are ignored.
	 *
	 * @param taskName   ignored
	 * @param lambda     the code to execute
	 * @param attributes ignored (supplier is never invoked)
	 * @param <T>        return type
	 * @return the result of invoking the supplier
	 */
	@Override
	public <T> T executeWithinBlock(
		@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes) {
		return lambda.get();
	}

	/**
	 * Executes the runnable directly without creating a trace span. The taskName is ignored.
	 *
	 * @param taskName ignored
	 * @param runnable the code to execute
	 */
	@Override
	public void executeWithinBlock(@Nonnull String taskName, @Nonnull Runnable runnable) {
		runnable.run();
	}

	/**
	 * Executes the runnable directly without creating a trace span. Delegates to
	 * {@link #executeWithinBlock} which ignores all tracing parameters.
	 *
	 * @param contextReference ignored
	 * @param taskName         ignored
	 * @param runnable         the code to execute
	 * @param attributes       ignored
	 */
	@Override
	public void executeWithinBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Runnable runnable,
		@Nullable SpanAttribute... attributes
	) {
		executeWithinBlock(taskName, runnable, attributes);
	}

	/**
	 * Executes the supplier directly without creating a trace span. Delegates to
	 * {@link #executeWithinBlock} which ignores all tracing parameters.
	 *
	 * @param contextReference ignored
	 * @param taskName         ignored
	 * @param lambda           the code to execute
	 * @param attributes       ignored
	 * @param <T>              return type
	 * @return the result of invoking the supplier
	 */
	@Override
	public <T> T executeWithinBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Supplier<T> lambda,
		@Nullable SpanAttribute... attributes
	) {
		return executeWithinBlock(taskName, lambda, attributes);
	}

	/**
	 * Executes the runnable directly without creating a trace span. Delegates to
	 * {@link #executeWithinBlock} which ignores all tracing parameters.
	 *
	 * @param contextReference ignored
	 * @param taskName         ignored
	 * @param runnable         the code to execute
	 * @param attributes       ignored (supplier is never invoked)
	 */
	@Override
	public void executeWithinBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Runnable runnable,
		@Nullable Supplier<SpanAttribute[]> attributes
	) {
		executeWithinBlock(taskName, runnable, attributes);
	}

	/**
	 * Executes the supplier directly without creating a trace span. Delegates to
	 * {@link #executeWithinBlock} which ignores all tracing parameters.
	 *
	 * @param contextReference ignored
	 * @param taskName         ignored
	 * @param lambda           the code to execute
	 * @param attributes       ignored (supplier is never invoked)
	 * @param <T>              return type
	 * @return the result of invoking the supplier
	 */
	@Override
	public <T> T executeWithinBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Supplier<T> lambda,
		@Nullable Supplier<SpanAttribute[]> attributes
	) {
		return executeWithinBlock(taskName, lambda, attributes);
	}

	/**
	 * Executes the runnable directly without creating a trace span. Delegates to
	 * {@link #executeWithinBlock} which ignores all tracing parameters.
	 *
	 * @param contextReference ignored
	 * @param taskName         ignored
	 * @param runnable         the code to execute
	 */
	@Override
	public void executeWithinBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Runnable runnable) {
		executeWithinBlock(taskName, runnable);
	}

	/**
	 * Executes the supplier directly without creating a trace span. Delegates to
	 * {@link #executeWithinBlock} which ignores all tracing parameters.
	 *
	 * @param contextReference ignored
	 * @param taskName         ignored
	 * @param lambda           the code to execute
	 * @param <T>              return type
	 * @return the result of invoking the supplier
	 */
	@Override
	public <T> T executeWithinBlockWithParentContext(
		@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Supplier<T> lambda) {
		return executeWithinBlock(taskName, lambda);
	}

	/**
	 * Executes the runnable directly without creating a trace span. Since no parent context exists
	 * in this implementation, the behavior is identical to {@link #executeWithinBlock}.
	 *
	 * @param taskName   ignored
	 * @param runnable   the code to execute
	 * @param attributes ignored
	 */
	@Override
	public void executeWithinBlockIfParentContextAvailable(
		@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable SpanAttribute... attributes) {
		runnable.run();
	}

	/**
	 * Executes the supplier directly without creating a trace span. Since no parent context exists
	 * in this implementation, the behavior is identical to {@link #executeWithinBlock}.
	 *
	 * @param taskName   ignored
	 * @param lambda     the code to execute
	 * @param attributes ignored
	 * @param <T>        return type
	 * @return the result of invoking the supplier
	 */
	@Override
	public <T> T executeWithinBlockIfParentContextAvailable(
		@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable SpanAttribute... attributes) {
		return lambda.get();
	}

	/**
	 * Executes the runnable directly without creating a trace span. Since no parent context exists
	 * in this implementation, the behavior is identical to {@link #executeWithinBlock}.
	 *
	 * @param taskName   ignored
	 * @param runnable   the code to execute
	 * @param attributes ignored (supplier is never invoked)
	 */
	@Override
	public void executeWithinBlockIfParentContextAvailable(
		@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes) {
		runnable.run();
	}

	/**
	 * Executes the supplier directly without creating a trace span. Since no parent context exists
	 * in this implementation, the behavior is identical to {@link #executeWithinBlock}.
	 *
	 * @param taskName   ignored
	 * @param lambda     the code to execute
	 * @param attributes ignored (supplier is never invoked)
	 * @param <T>        return type
	 * @return the result of invoking the supplier
	 */
	@Override
	public <T> T executeWithinBlockIfParentContextAvailable(
		@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes) {
		return lambda.get();
	}

	/**
	 * Executes the runnable directly without creating a trace span. Since no parent context exists
	 * in this implementation, the behavior is identical to {@link #executeWithinBlock}.
	 *
	 * @param taskName ignored
	 * @param runnable the code to execute
	 */
	@Override
	public void executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Runnable runnable) {
		runnable.run();
	}
}
