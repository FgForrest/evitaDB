/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

/**
 * Handles CORS pre-flight requests and responses with correct CORS headers. Also, if request origins is forbidden, this
 * handler returns error.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
@RequiredArgsConstructor
public class CorsPreflightHandler implements HttpHandler {

	/**
	 * Set of allowed origins, other origins will return error. If null, all origins are allowed.
	 */
	@Nullable
	private final Set<String> allowedOrigins;
	@Nonnull
	private final Set<String> allowedMethods;
	@Nonnull
	private final Set<String> allowedHeaders;

	public CorsPreflightHandler(@Nullable String[] allowedOrigins,
	                            @Nonnull Set<String> allowedMethods,
	                            @Nonnull Set<String> allowedHeaders) {
		this(
			allowedOrigins != null ? Set.of(allowedOrigins) : null,
			allowedMethods,
			allowedHeaders
		);
	}

	@Override
	public void handleRequest(HttpServerExchange exchange) throws Exception {
		if (allowedOrigins == null) {
			exchange.getResponseHeaders().put(AdditionalHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		} else {
			final String requestOrigin = exchange.getRequestHeaders().getFirst(Headers.ORIGIN);
			if (requestOrigin == null || !allowedOrigins.contains(requestOrigin)) {
				log.warn("Forbidden origin `" + requestOrigin + "` is trying to access the API.");
				exchange.setStatusCode(StatusCodes.FORBIDDEN);
				exchange.endExchange();
				return;
			} else {
				exchange.getResponseHeaders().put(AdditionalHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, requestOrigin);
				exchange.getResponseHeaders().put(Headers.VARY, "Origin");
			}
		}
		exchange.getResponseHeaders().put(AdditionalHeaders.ACCESS_CONTROL_ALLOW_METHODS, String.join(", ", allowedMethods));
		exchange.getResponseHeaders().put(AdditionalHeaders.ACCESS_CONTROL_ALLOW_HEADERS, String.join(", ", allowedHeaders));
		exchange.setStatusCode(StatusCodes.OK);
		exchange.endExchange();
	}
}
