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
	private static final String INVALID_CONTEXT_OBJECT_ERROR_MESSAGE = "Invalid object type sent as a External API tracing context!";

	public DelegateExternalApiTracingContext() {
		final TracingContext context = TracingContextProvider.getContext();
		jsonApiTracingContext = new JsonApiTracingContext(context);
		grpcApiTracingContext = new GrpcTracingContext(context);
	}
	@Override
	public void executeWithinBlock(@Nonnull String protocolName, @Nonnull Object context, @Nullable Map<String, Object> attributes, @Nonnull Runnable runnable) {
		if (context instanceof HttpServerExchange httpServerExchange) {
			jsonApiTracingContext.executeWithinBlock(protocolName, httpServerExchange, attributes, runnable);
		} else if (context instanceof Metadata metadata) {
			grpcApiTracingContext.executeWithinBlock(protocolName, metadata, attributes, runnable);
		} else {
			throw new EvitaInternalError(INVALID_CONTEXT_OBJECT_ERROR_MESSAGE);
		}
	}

	@Override
	public <T> T executeWithinBlock(@Nonnull String protocolName, @Nonnull Object context, @Nullable Map<String, Object> attributes, @Nonnull Supplier<T> lambda) {
		if (context instanceof HttpServerExchange httpServerExchange) {
			return jsonApiTracingContext.executeWithinBlock(protocolName, httpServerExchange, attributes, lambda);
		} else if (context instanceof Metadata metadata) {
			return grpcApiTracingContext.executeWithinBlock(protocolName, metadata, attributes, lambda);
		} else {
			throw new EvitaInvalidUsageException(INVALID_CONTEXT_OBJECT_ERROR_MESSAGE);
		}
	}

	@Override
	public <T> T getServerInterceptor(Class<T> type) {
		if (type.equals(ServerInterceptor.class) && grpcApiTracingContext != null) {
			//noinspection unchecked
			return (T) grpcApiTracingContext.getServerInterceptor();
		}
		return null;
	}
}
