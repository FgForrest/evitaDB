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

package io.evitadb.externalApi.observability.trace;

import com.linecorp.armeria.common.HttpRequest;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
import io.evitadb.api.observability.trace.TracingContextProvider;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.grpc.Metadata;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
		this.jsonApiTracingContext = new JsonApiTracingContext(context);
		this.grpcApiTracingContext = new GrpcTracingContext(context);
	}

	@Override
	public void configureHeaders(@Nonnull HeaderOptions headerOptions) {
		this.jsonApiTracingContext.setHeaderOptions(headerOptions);
	}

	@Override
	public void executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull Object context,
		@Nonnull Runnable runnable,
		@Nullable SpanAttribute... attributes
	) {
		if (context instanceof HttpRequest httpRequest) {
			this.jsonApiTracingContext.executeWithinBlock(protocolName, httpRequest, runnable, attributes);
		} else if (context instanceof Metadata metadata) {
			this.grpcApiTracingContext.executeWithinBlock(protocolName, metadata, runnable, attributes);
		} else {
			throw new EvitaInvalidUsageException("Invalid object type sent as a External API tracing context!");
		}
	}

	@Override
	public <T> T executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull Object context,
		@Nonnull Supplier<T> lambda,
		@Nullable SpanAttribute... attributes
	) {
		if (context instanceof HttpRequest httpRequest) {
			return this.jsonApiTracingContext.executeWithinBlock(protocolName, httpRequest, lambda, attributes);
		} else if (context instanceof Metadata metadata) {
			return this.grpcApiTracingContext.executeWithinBlock(protocolName, metadata, lambda, attributes);
		} else {
			throw new EvitaInvalidUsageException("Invalid object type sent as a External API tracing context!");
		}
	}

	@Override
	public void executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull Object context,
		@Nonnull Runnable runnable,
		@Nullable Supplier<SpanAttribute[]> attributes
	) {
		if (context instanceof HttpRequest httpRequest) {
			this.jsonApiTracingContext.executeWithinBlock(protocolName, httpRequest, runnable, attributes);
		} else if (context instanceof Metadata metadata) {
			this.grpcApiTracingContext.executeWithinBlock(protocolName, metadata, runnable, attributes);
		} else {
			throw new EvitaInvalidUsageException("Invalid object type sent as a External API tracing context!");
		}
	}

	@Override
	public <T> T executeWithinBlock(
		@Nonnull String protocolName,
		@Nonnull Object context,
		@Nonnull Supplier<T> lambda,
		@Nullable Supplier<SpanAttribute[]> attributes
	) {
		if (context instanceof HttpRequest httpRequest) {
			return this.jsonApiTracingContext.executeWithinBlock(protocolName, httpRequest, lambda, attributes);
		} else if (context instanceof Metadata metadata) {
			return this.grpcApiTracingContext.executeWithinBlock(protocolName, metadata, lambda, attributes);
		} else {
			throw new EvitaInvalidUsageException("Invalid object type sent as a External API tracing context!");
		}
	}

	@Override
	public void executeWithinBlock(@Nonnull String protocolName, @Nonnull Object context, @Nonnull Runnable runnable) {
		if (context instanceof HttpRequest httpRequest) {
			this.jsonApiTracingContext.executeWithinBlock(protocolName, httpRequest, runnable);
		} else if (context instanceof Metadata metadata) {
			this.grpcApiTracingContext.executeWithinBlock(protocolName, metadata, runnable);
		} else {
			throw new EvitaInvalidUsageException("Invalid object type sent as a External API tracing context!");
		}
	}

	@Nullable
	@Override
	public <T> T executeWithinBlock(@Nonnull String protocolName, @Nonnull Object context, @Nonnull Supplier<T> lambda) {
		if (context instanceof HttpRequest httpRequest) {
			return this.jsonApiTracingContext.executeWithinBlock(protocolName, httpRequest, lambda);
		} else if (context instanceof Metadata metadata) {
			return this.grpcApiTracingContext.executeWithinBlock(protocolName, metadata, lambda);
		} else {
			throw new EvitaInvalidUsageException("Invalid object type sent as a External API tracing context!");
		}
	}
}
