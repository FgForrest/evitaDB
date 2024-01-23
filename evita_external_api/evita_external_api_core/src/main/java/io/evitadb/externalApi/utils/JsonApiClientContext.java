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

package io.evitadb.externalApi.utils;

import io.evitadb.api.ClientContext;
import io.evitadb.externalApi.trace.OpenTelemetryTracerSetup;
import io.opentelemetry.context.Scope;
import io.undertow.util.HeaderMap;

import javax.annotation.Nonnull;
import java.net.SocketAddress;
import java.util.function.Supplier;

public abstract class JsonApiClientContext extends ExternalApiClientContext {

	public JsonApiClientContext(@Nonnull ClientContext internalClientContext) {
		super(internalClientContext);
	}

	public void executeWithClientAndRequestId(@Nonnull SocketAddress clientAddress,
	                                          @Nonnull HeaderMap headers,
	                                          @Nonnull Runnable lambda) {
		try (Scope scope = OpenTelemetryTracerSetup.extractContextFromHeaders(headers).makeCurrent()) {
			super.executeWithClientAndRequestId(
				clientAddress,
				headers.getFirst("clientId"),
				headers.getFirst("requestId"),
				lambda
			);
		}
	}

	public <T> T executeWithClientAndRequestId(@Nonnull SocketAddress clientAddress,
	                                           @Nonnull HeaderMap headers,
	                                           @Nonnull Supplier<T> lambda) {
		try (Scope scope = OpenTelemetryTracerSetup.extractContextFromHeaders(headers).makeCurrent()) {
			return super.executeWithClientAndRequestId(
				clientAddress,
				headers.getFirst("clientId"),
				headers.getFirst("requestId"),
				lambda
			);
		}
	}
}
