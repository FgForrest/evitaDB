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

package io.evitadb.api.trace;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Default NOOP implementation of {@link TracingContext}.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DefaultTracingContext implements TracingContext {
	public static final TracingContext INSTANCE = new DefaultTracingContext();
	private static final TracingContextReference<?> EMPTY_CONTEXT_HOLDER = new DefaultTracingContextReference();

	@Override
	public TracingContextReference<?> getCurrentContext() {
		// this is dummy implementation, it doesn't do anything
		return EMPTY_CONTEXT_HOLDER;
	}

	@Override
	public void executeWithinBlock(@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable SpanAttribute... attributes) {
		runnable.run();
	}

	@Override
	public <T> T executeWithinBlock(@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable SpanAttribute... attributes) {
		return lambda.get();
	}

	@Override
	public void executeWithinBlock(@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes) {
		runnable.run();
	}

	@Override
	public <T> T executeWithinBlock(@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes) {
		return lambda.get();
	}

	@Override
	public void executeWithinBlock(@Nonnull String taskName, @Nonnull Runnable runnable) {
		runnable.run();
	}

	@Override
	public <T> T executeWithinBlock(@Nonnull String taskName, @Nonnull Supplier<T> lambda) {
		return lambda.get();
	}

	@Override
	public void executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Runnable runnable, @Nullable SpanAttribute... attributes) {
		executeWithinBlock(taskName, runnable, attributes);
	}

	@Override
	public <T> T executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable SpanAttribute... attributes) {
		return executeWithinBlock(taskName, lambda, attributes);
	}

	@Override
	public void executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes) {
		executeWithinBlock(taskName, runnable, attributes);
	}

	@Override
	public <T> T executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes) {
		return executeWithinBlock(taskName, lambda, attributes);
	}

	@Override
	public void executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Runnable runnable) {
		executeWithinBlock(taskName, runnable);
	}

	@Override
	public <T> T executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextReference, @Nonnull String taskName, @Nonnull Supplier<T> lambda) {
		return executeWithinBlock(taskName, lambda);
	}

	@Override
	public void executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable SpanAttribute... attributes) {
		runnable.run();
	}

	@Override
	public <T> T executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable SpanAttribute... attributes) {
		return lambda.get();
	}

	@Override
	public void executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes) {
		runnable.run();
	}

	@Override
	public <T> T executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes) {
		return lambda.get();
	}

	@Override
	public void executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Runnable runnable) {
		runnable.run();
	}
}
