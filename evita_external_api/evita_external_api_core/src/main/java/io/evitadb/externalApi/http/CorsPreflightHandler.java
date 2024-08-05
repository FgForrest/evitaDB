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

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseBuilder;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.netty.util.AsciiString;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles CORS pre-flight requests and responses with correct CORS headers. Also, if request origins is forbidden, this
 * handler returns error.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class CorsPreflightHandler implements HttpService {

	@Nullable
	private final HttpService delegate;
	/**
	 * Set of allowed origins, other origins will return error. If null, all origins are allowed.
	 */
	@Nullable
	private final Set<String> allowedOrigins;
	@Nonnull
	private final Set<HttpMethod> allowedMethods;
	@Nonnull
	private final Set<AsciiString> allowedHeaders;

	public CorsPreflightHandler(@Nullable String[] allowedOrigins,
	                            @Nonnull Set<HttpMethod> allowedMethods,
	                            @Nonnull Set<AsciiString> allowedHeaders) {
		this(null, allowedOrigins, allowedMethods, allowedHeaders);
	}

	public CorsPreflightHandler(@Nullable Set<String> allowedOrigins,
	                            @Nonnull Set<HttpMethod> allowedMethods,
	                            @Nonnull Set<AsciiString> allowedHeaders) {
		this(null, allowedOrigins, allowedMethods, allowedHeaders);
	}

	public CorsPreflightHandler(@Nullable HttpService delegate,
	                            @Nullable String[] allowedOrigins,
	                            @Nonnull Set<HttpMethod> allowedMethods,
	                            @Nonnull Set<AsciiString> allowedHeaders) {
		this(
			delegate,
			allowedOrigins != null ? Set.of(allowedOrigins) : null,
			allowedMethods,
			allowedHeaders
		);
	}

	public CorsPreflightHandler(@Nullable HttpService delegate,
	                            @Nullable Set<String> allowedOrigins,
	                            @Nonnull Set<HttpMethod> allowedMethods,
	                            @Nonnull Set<AsciiString> allowedHeaders) {
		this.delegate = delegate;
		this.allowedOrigins = allowedOrigins;
		this.allowedMethods = allowedMethods;
		this.allowedHeaders = allowedHeaders;
	}

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		if (isPreflightRequest(req)) {
			final HttpResponseBuilder responseBuilder = HttpResponse.builder();
			if (allowedOrigins == null) {
				responseBuilder.header(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
			} else {
				final String requestOrigin = req.headers().get("Origin");
				if (requestOrigin == null || !allowedOrigins.contains(requestOrigin)) {
					log.warn("Forbidden origin `{}` is trying to access the API.", requestOrigin);
					responseBuilder.status(HttpStatus.FORBIDDEN);
					return responseBuilder.build();
				} else {
					responseBuilder.header(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, requestOrigin);
					responseBuilder.header(HttpHeaderNames.VARY, "Origin");
				}
			}
			responseBuilder.header(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, allowedMethods.stream().map(Objects::toString).collect(Collectors.joining(", ")));
			responseBuilder.header(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, String.join(", ", allowedHeaders));
			responseBuilder.status(HttpStatus.OK);
			return responseBuilder.build();
		} else {
			return delegate.serve(ctx, req);
		}
	}

	private boolean isPreflightRequest(@Nonnull HttpRequest req) {
		return delegate == null || HttpMethod.OPTIONS.equals(req.method());
	}
}
