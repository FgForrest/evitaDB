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

import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
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
	 * Name of property representing the client IP address in the {@link MDC}.
	 */
	String MDC_CLIENT_IP_ADDRESS = "clientIp";

	/**
	 * Executes the provided operation within the context of the specified client IP address.
	 * The client IP address is temporarily stored in the MDC (Mapped Diagnostic Context) for tracking
	 * or logging purposes during the execution of the operation. After execution, the client IP address
	 * is removed from the MDC.
	 *
	 * @param clientIpAddress the IP address of the client, which will be added to the MDC for the scope of this execution
	 * @param runnable the operation to be executed, provided as a {@code SupplierThrowingException} returning a result of type {@code T}
	 * @return the result of the operation executed by {@code runnable}
	 * @throws Exception if the provided {@code runnable} operation throws an exception
	 */
	static <T> T executeWithClientIpAddress(@Nonnull String clientIpAddress, @Nonnull SupplierThrowingException<T> runnable) throws Exception {
		MDC.put(MDC_CLIENT_IP_ADDRESS, clientIpAddress);
		try {
			return runnable.get();
		} finally {
			MDC.remove(MDC_CLIENT_IP_ADDRESS);
		}
	}

	/**
	 * Returns the trace identifier associated with the trace.
	 * @return the trace identifier
	 */
	@Nonnull
	Optional<String> getTraceId();

	/**
	 * Returns the client identifier associated with the trace.
	 * @return the client identifier
	 */
	@Nonnull
	Optional<String> getClientId();

	/**
	 * Returns the client IP address associated with the trace.
	 * @return the client IP address
	 */
	@Nonnull
	default Optional<String> getClientIpAddress() {
		return Optional.ofNullable(MDC.get(MDC_CLIENT_IP_ADDRESS));
	}

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
	) {
		public static final SpanAttribute[] EMPTY_ARRAY = new SpanAttribute[0];
	}

	/**
	 * Interface similar to {@link Supplier} but allows to throw checked exceptions.
	 */
	@FunctionalInterface
	interface SupplierThrowingException<T> {

		T get() throws Exception;

	}

}
