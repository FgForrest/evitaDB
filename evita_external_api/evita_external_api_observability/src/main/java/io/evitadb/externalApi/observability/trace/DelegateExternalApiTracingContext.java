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

package io.evitadb.externalApi.observability.trace;

import io.evitadb.api.trace.TracingContext;
import io.evitadb.api.trace.TracingContextProvider;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.grpc.Metadata;
import io.grpc.ServerInterceptor;
import io.undertow.server.HttpServerExchange;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Delegates tracing call either to {@link JsonApiTracingContext} or {@link GrpcTracingContext}. The decision of which to
 * choose is based on the passed `context` type.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class DelegateExternalApiTracingContext implements ExternalApiTracingContext<Object> {
	private final JsonApiTracingContext jsonApiTracingContext;
	private final GrpcTracingContext grpcApiTracingContext;

	public DelegateExternalApiTracingContext() {
		final TracingContext context = TracingContextProvider.getContext();
		jsonApiTracingContext = new JsonApiTracingContext(context);
		grpcApiTracingContext = new GrpcTracingContext(context);
	}

	/**
	 * Executes the provided {@link Runnable} within a block by delegating the execution to the appropriate
	 * {@link ExternalApiTracingContext} implementation based on the type of the context.
	 *
	 * @param protocolName The name of the protocol being executed within the block.
	 * @param context The context object representing the tracing context.
	 * @param attributes Additional attributes associated with the execution.
	 * @param runnable The {@link Runnable} to be executed within the block.
	 */
	@Override
	public void executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull Object context,
		@Nullable Map<String, Object> attributes,
		@Nonnull Runnable runnable
	) {
		executeWithinBlock(protocolName, context, attributes, () -> {
			runnable.run();
			return null;
		});
	}

	/**
	 * Executes the provided lambda within a block by delegating the execution to the appropriate
	 * ExternalApiTracingContext implementation based on the type of the context.
	 *
	 * @param protocolName The name of the protocol being executed within the block.
	 * @param context The context object representing the tracing context.
	 * @param attributes Additional attributes associated with the execution.
	 * @param lambda The lambda expression to be executed within the block.
	 * @param <T> The return type of the lambda expression.
	 * @return The result of the lambda execution.
	 * @throws EvitaInvalidUsageException If an invalid object type is sent as the External API tracing context.
	 */
	@Override
	public <T> T executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull Object context,
		@Nullable Map<String, Object> attributes,
		@Nonnull Supplier<T> lambda
	) {
		if (context instanceof HttpServerExchange httpServerExchange) {
			return jsonApiTracingContext.executeWithinBlock(protocolName, httpServerExchange, attributes, lambda);
		} else if (context instanceof Metadata metadata) {
			return grpcApiTracingContext.executeWithinBlock(protocolName, metadata, attributes, lambda);
		} else {
			throw new EvitaInvalidUsageException("Invalid object type sent as a External API tracing context!");
		}
	}

	/**
	 * Get the server interceptor of the specified type from the tracing context.
	 *
	 * @param type The class representing the type of the server interceptor.
	 * @param <T>  The type of the server interceptor.
	 * @return The server interceptor of the specified type, or null if not found.
	 */
	@Override
	public <T> T getServerInterceptor(@Nonnull Class<T> type) {
		if (type.equals(ServerInterceptor.class) && grpcApiTracingContext != null) {
			//noinspection unchecked
			return (T) grpcApiTracingContext.getServerInterceptor();
		}
		return null;
	}
}
