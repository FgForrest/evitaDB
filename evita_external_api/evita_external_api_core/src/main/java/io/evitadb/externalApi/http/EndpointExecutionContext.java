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

package io.evitadb.externalApi.http;

<<<<<<<< HEAD:evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/EndpointRequest.java
import com.linecorp.armeria.common.HttpRequest;
========
import io.undertow.server.HttpServerExchange;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
>>>>>>>> refs/heads/dev:evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/EndpointExecutionContext.java

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Context data present during entire execution of certain endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
<<<<<<<< HEAD:evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/EndpointRequest.java
public interface EndpointRequest extends AutoCloseable {
========
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class EndpointExecutionContext implements AutoCloseable {

	@Nonnull private final HttpServerExchange serverExchange;
>>>>>>>> refs/heads/dev:evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/EndpointExecutionContext.java

	/**
	 * Underlying HTTP server exchange
	 */
	@Nonnull
<<<<<<<< HEAD:evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/EndpointRequest.java
	HttpRequest httpRequest();
========
	public HttpServerExchange serverExchange() {
		return serverExchange;
	}
>>>>>>>> refs/heads/dev:evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/EndpointExecutionContext.java

	/**
	 * Provides request body content type for the execution. Can be called only once.
	 */
	public abstract void provideRequestBodyContentType(@Nonnull String contentType);

	/**
	 * Parsed content type of request body, if any request body is present.
	 */
	@Nullable
	public abstract String requestBodyContentType();

	/**
	 * Provides preferred response body content type for the execution. Can be called only once.
	 */
	public abstract void providePreferredResponseContentType(@Nonnull String contentType);

	/**
	 * Preferred content type of response body, if any response body is will be send.
	 */
	@Nullable
	public abstract String preferredResponseContentType();

	/**
	 * Called by endpoint when error occurred. Can be used for logging.
	 * The error should not be thrown.
	 */
	public void notifyError(@Nonnull Exception e) {
		// do nothing
	}

	@Override
	public void close() {
		// do nothing
	}
}
