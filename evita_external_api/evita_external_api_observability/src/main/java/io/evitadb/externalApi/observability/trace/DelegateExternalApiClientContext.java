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
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.grpc.Metadata;
import io.grpc.ServerInterceptor;
import io.undertow.server.HttpServerExchange;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Delegates tracing call either to {@link JsonApiClientContext} or {@link GrpcClientContext}. The decision of which to
 * choose is based on the passed `context` type.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class DelegateExternalApiClientContext implements ExternalApiTracingContext<Object> {
	private final JsonApiClientContext jsonApiClientContext;
	private final GrpcClientContext grpcApiClientContext;
	private static final String INVALID_CONTEXT_OBJECT_ERROR_MESSAGE = "Invalid object type sent as a External API tracing context!";

	public DelegateExternalApiClientContext() {
		final TracingContext context = TracingContextProvider.getContext();
		jsonApiClientContext = new JsonApiClientContext(context);
		grpcApiClientContext = new GrpcClientContext(context);
	}
	@Override
	public void executeWithinBlock(@Nonnull String protocolName, @Nonnull SocketAddress sourceAddress, @Nonnull Object context, @Nullable Map<String, Object> attributes, @Nonnull Runnable runnable) {
		if (context instanceof HttpServerExchange httpServerExchange) {
			jsonApiClientContext.executeWithinBlock(protocolName, sourceAddress, httpServerExchange, attributes, runnable);
		} else if (context instanceof Metadata metadata) {
			grpcApiClientContext.executeWithinBlock(protocolName, sourceAddress, metadata, attributes, runnable);
		} else {
			throw new EvitaInternalError(INVALID_CONTEXT_OBJECT_ERROR_MESSAGE);
		}
	}

	@Override
	public <T> T executeWithinBlock(@Nonnull String protocolName, @Nonnull SocketAddress sourceAddress, @Nonnull Object context, @Nullable Map<String, Object> attributes, @Nonnull Supplier<T> lambda) {
		if (context instanceof HttpServerExchange httpServerExchange) {
			return jsonApiClientContext.executeWithinBlock(protocolName, sourceAddress, httpServerExchange, attributes, lambda);
		} else if (context instanceof Metadata metadata) {
			return grpcApiClientContext.executeWithinBlock(protocolName, sourceAddress, metadata, attributes, lambda);
		} else {
			throw new EvitaInvalidUsageException(INVALID_CONTEXT_OBJECT_ERROR_MESSAGE);
		}
	}

	@Override
	public <T> T getServerInterceptor(Class<T> type) {
		if (type.equals(ServerInterceptor.class) && grpcApiClientContext != null) {
			//noinspection unchecked
			return (T) grpcApiClientContext.getServerInterceptor();
		}
		return null;
	}
}
