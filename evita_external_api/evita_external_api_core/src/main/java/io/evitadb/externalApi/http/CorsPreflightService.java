/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseBuilder;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
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
public class CorsPreflightService implements HttpService {

	/**
	 * Set of allowed origins, other origins will return error. If null, all origins are allowed.
	 */
	@Nullable
	private final Set<String> allowedOrigins;
	@Nonnull
	private final Set<String> allowedMethods;
	@Nonnull
	private final Set<String> allowedHeaders;

	public CorsPreflightService(@Nullable String[] allowedOrigins,
	                            @Nonnull Set<String> allowedMethods,
	                            @Nonnull Set<String> allowedHeaders) {
		this(
			allowedOrigins != null ? Set.of(allowedOrigins) : null,
			allowedMethods,
			allowedHeaders
		);
	}

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		final HttpResponseBuilder responseBuilder = HttpResponse.builder();
		// todo lho verify
		if (allowedOrigins == null) {
			responseBuilder.header(AdditionalHeaders.ACCESS_CONTROL_ALLOW_ORIGIN_STRING, "*");
			responseBuilder.header(AdditionalHeaders.ACCESS_CONTROL_ALLOW_METHODS_STRING, "*");
			responseBuilder.header(AdditionalHeaders.ACCESS_CONTROL_ALLOW_HEADERS_STRING, "*");
		} else {
			final String requestOrigin = req.headers().get("Origin");
			if (requestOrigin == null || !allowedOrigins.contains(requestOrigin)) {
				log.warn("Forbidden origin `{}` is trying to access the API.", requestOrigin);
				responseBuilder.status(HttpStatus.FORBIDDEN);
				return responseBuilder.build();
			} else {
				responseBuilder.header(AdditionalHeaders.ACCESS_CONTROL_ALLOW_ORIGIN_STRING, requestOrigin);
				responseBuilder.header(AdditionalHeaders.VARY_STRING, "Origin");
			}
			responseBuilder.header(AdditionalHeaders.ACCESS_CONTROL_ALLOW_METHODS_STRING, String.join(", ", allowedMethods));
			responseBuilder.header(AdditionalHeaders.ACCESS_CONTROL_ALLOW_HEADERS_STRING, String.join(", ", allowedHeaders));
		}
		responseBuilder.status(HttpStatus.OK);
		return responseBuilder.build();
	}
}
