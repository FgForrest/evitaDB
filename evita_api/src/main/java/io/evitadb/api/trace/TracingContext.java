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

import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Context for tracing purposes. It offers a set of methods - each of those could be used as a wrapper that accepts
 * a call metadata and a lambda function with logic to be traced.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public interface TracingContext {
	/**
	 * Name of property representing the client identifier in the {@link MDC}.
	 */
	String MDC_CLIENT_ID_PROPERTY = "clientId";
	/**
	 * Name of property representing the trace identifier in the {@link MDC}.
	 */
	String MDC_TRACE_ID_PROPERTY = "traceId";

	/**
	 * Returns a reference to currently active underlying context implementation.
	 * Mainly to pass the context to detached function call (usually in different thread).
	 */
	TracingContextReference<?> getCurrentContext();

	/**
	 * Sets the passed task name and attributes to the trace block and activates it. The block is then returned and
	 * client must handle its closing after client code is executed.
	 */
	@Nonnull
	default TracingBlockReference createAndActivateBlock(@Nonnull String taskName, @Nullable SpanAttribute... attributes) {
		return new DefaultTracingBlockReference();
	}

	/**
	 * Sets the passed task name and attributes to the trace block and activates it. The block is then returned and
	 * client must handle its closing after client code is executed. When the block is closed,
	 * the attributes will be set to the trace. The attributes may take advantage of the data computed in the lambda
	 * itself.
	 */
	@Nonnull
	default TracingBlockReference createAndActivateBlock(@Nonnull String taskName, @Nullable Supplier<SpanAttribute[]> attributes) {
		return new DefaultTracingBlockReference();
	}

	/**
	 * Sets the passed task name to the trace block and activates it. The block is then returned and
	 * client must handle its closing after client code is executed.
	 */
	@Nonnull
	default TracingBlockReference createAndActivateBlock(@Nonnull String taskName) {
		return new DefaultTracingBlockReference();
	}

	/**
	 * Sets the passed task name and attributes to the trace BEFORE the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed.
	 */
	void executeWithinBlock(@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable SpanAttribute... attributes);

	/**
	 * Sets the passed task name and attributes to the trace BEFORE the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed.
	 */
	<T> T executeWithinBlock(@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable SpanAttribute... attributes);

	/**
	 * Sets the passed task name and attributes to the trace AFTER the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed. After the method successfully finishes,
	 * the attributes will be set to the trace. The attributes may take advantage of the data computed in the lambda
	 * itself.
	 */
	void executeWithinBlock(@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes);

	/**
	 * Sets the passed task name and attributes to the trace AFTER the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed. After the method successfully finishes,
	 * the attributes will be set to the trace. The attributes may take advantage of the data computed in the lambda
	 * itself.
	 */
	<T> T executeWithinBlock(@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes);

	/**
	 * Sets the passed task name to the trace. Within the method, the lambda with passed logic will be
	 * traced and properly executed.
	 */
	void executeWithinBlock(@Nonnull String taskName, @Nonnull Runnable runnable);

	/**
	 * Sets the passed task name to the trace. Within the method, the lambda with passed logic will be
	 * traced and properly executed.
	 */
	default <T> T executeWithinBlock(@Nonnull String taskName, @Nonnull Supplier<T> lambda) {
		return lambda.get();
	}

	/**
	 * Sets the passed task name and attributes to the trace block and activates it. The block is then returned and
	 * client must handle its closing after client code is executed.
	 */
	@Nonnull
	default TracingBlockReference createAndActivateBlockWithParentContext(@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName, @Nullable SpanAttribute... attributes) {
		return new DefaultTracingBlockReference();
	}

	/**
	 * Sets the passed task name and attributes to the trace block and activates it. The block is then returned and
	 * client must handle its closing after client code is executed. When the block is closed,
	 * the attributes will be set to the trace. The attributes may take advantage of the data computed in the lambda
	 * itself.
	 */
	@Nonnull
	default TracingBlockReference createAndActivateBlockWithParentContext(@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName, @Nullable Supplier<SpanAttribute[]> attributes) {
		return new DefaultTracingBlockReference();
	}

	/**
	 * Sets the passed task name and attributes to the trace block and activates it. The block is then returned and
	 * client must handle its closing after client code is executed.
	 */
	@Nonnull
	default TracingBlockReference createAndActivateBlockWithParentContext(@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName) {
		return new DefaultTracingBlockReference();
	}

	/**
	 * Sets the passed task name and attributes to the trace BEFORE the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed.
	 */
	void executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName, @Nonnull Runnable runnable, @Nullable SpanAttribute... attributes);

	/**
	 * Sets the passed task name and attributes to the trace BEFORE the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed.
	 */
	<T> T executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable SpanAttribute... attributes);

	/**
	 * Sets the passed task name and attributes to the trace AFTER the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed. After the method successfully finishes,
	 * the attributes will be set to the trace. The attributes may take advantage of the data computed in the lambda
	 * itself.
	 */
	void executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes);

	/**
	 * Sets the passed task name and attributes to the trace AFTER the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed. After the method successfully finishes,
	 * the attributes will be set to the trace. The attributes may take advantage of the data computed in the lambda
	 * itself.
	 */
	<T> T executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes);

	/**
	 * Sets the passed task name to the trace. Within the method, the lambda with passed logic will be
	 * traced and properly executed.
	 */
	void executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName, @Nonnull Runnable runnable);

	/**
	 * Sets the passed task name to the trace. Within the method, the lambda with passed logic will be
	 * traced and properly executed.
	 */
	default <T> T executeWithinBlockWithParentContext(@Nonnull TracingContextReference<?> contextHolderToUse, @Nonnull String taskName, @Nonnull Supplier<T> lambda) {
		return lambda.get();
	}

	/**
	 * Sets the passed task name and attributes to the trace block and activates it. The block is then returned and
	 * client must handle its closing after client code is executed.
	 */
	@Nullable
	default TracingBlockReference createAndActivateBlockIfParentContextAvailable(@Nonnull String taskName, @Nullable SpanAttribute... attributes) {
		return null;
	}

	/**
	 * Sets the passed task name and attributes to the trace block and activates it. The block is then returned and
	 * client must handle its closing after client code is executed. When the block is closed,
	 * the attributes will be set to the trace. The attributes may take advantage of the data computed in the lambda
	 * itself.
	 */
	@Nullable
	default TracingBlockReference createAndActivateBlockIfParentContextAvailable(@Nonnull String taskName, @Nullable Supplier<SpanAttribute[]> attributes) {
		return null;
	}

	/**
	 * Sets the passed task name and attributes to the trace block and activates it. The block is then returned and
	 * client must handle its closing after client code is executed.
	 */
	@Nullable
	default TracingBlockReference createAndActivateBlockIfParentContextAvailable(@Nonnull String taskName) {
		return null;
	}

	/**
	 * Sets the passed task name and attributes to the trace BEFORE the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed.
	 */
	void executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable SpanAttribute... attributes);

	/**
	 * Sets the passed task name and attributes to the trace BEFORE the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed.
	 */
	<T> T executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable SpanAttribute... attributes);

	/**
	 * Sets the passed task name and attributes to the trace AFTER the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed. After the method successfully finishes,
	 * the attributes will be set to the trace. The attributes may take advantage of the data computed in the lambda
	 * itself.
	 */
	void executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes);

	/**
	 * Sets the passed task name and attributes to the trace AFTER the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed. After the method successfully finishes,
	 * the attributes will be set to the trace. The attributes may take advantage of the data computed in the lambda
	 * itself.
	 */
	<T> T executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes);

	/**
	 * Sets the passed task name to the trace. Within the method, the lambda with passed logic will be
	 * traced and properly executed.
	 */
	void executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Runnable runnable);

	/**
	 * Sets the passed task name to the trace. Within the method, the lambda with passed logic will be
	 * traced and properly executed.
	 */
	default <T> T executeWithinBlockIfParentContextAvailable(@Nonnull String taskName, @Nonnull Supplier<T> lambda) {
		return lambda.get();
	}

	/**
	 * Represents a key-value pair of an attribute in a span.
	 *
	 * @param key   the key of the attribute
	 * @param value the value of the attribute (only primitive types and Strings are allowed)
	 */
	record SpanAttribute(
		@Nonnull String key,
		@Nullable Object value
	) {}

}
