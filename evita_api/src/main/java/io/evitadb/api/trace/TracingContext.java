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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
	 * Sets the passed task name and attributes to the trace BEFORE the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed.
	 */
	default void executeWithinBlock(@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable SpanAttribute... attributes) {
		runnable.run();
	}

	/**
	 * Sets the passed task name and attributes to the trace BEFORE the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed.
	 */
	default <T> T executeWithinBlock(@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable SpanAttribute... attributes) {
		return lambda.get();
	}

	/**
	 * Sets the passed task name and attributes to the trace AFTER the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed. After the method successfully finishes,
	 * the attributes will be set to the trace. The attributes may take advantage of the data computed in the lambda
	 * itself.
	 */
	default void executeWithinBlock(@Nonnull String taskName, @Nonnull Runnable runnable, @Nullable Supplier<SpanAttribute[]> attributes) {
		runnable.run();
	}

	/**
	 * Sets the passed task name and attributes to the trace AFTER the lambda is executed. Within the method,
	 * the lambda with passed logic will be traced and properly executed. After the method successfully finishes,
	 * the attributes will be set to the trace. The attributes may take advantage of the data computed in the lambda
	 * itself.
	 */
	default <T> T executeWithinBlock(@Nonnull String taskName, @Nonnull Supplier<T> lambda, @Nullable Supplier<SpanAttribute[]> attributes) {
		return lambda.get();
	}

	/**
	 * Sets the passed task name to the trace. Within the method, the lambda with passed logic will be
	 * traced and properly executed.
	 */
	default void executeWithinBlock(@Nonnull String taskName, @Nonnull Runnable runnable) {
		runnable.run();
	}

	/**
	 * Sets the passed task name to the trace. Within the method, the lambda with passed logic will be
	 * traced and properly executed.
	 */
	default <T> T executeWithinBlock(@Nonnull String taskName, @Nonnull Supplier<T> lambda) {
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
		@Nonnull Object value
	) {}

}
