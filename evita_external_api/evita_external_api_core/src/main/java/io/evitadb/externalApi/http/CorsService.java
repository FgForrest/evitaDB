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

package io.evitadb.externalApi.http;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseBuilder;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import io.evitadb.externalApi.utils.NotFoundService;
import io.netty.util.AsciiString;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Enables CORS filtering and preflight requests for endpoints.
 *
 * Should be used as filter for all standard endpoints.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class CorsService extends SimpleDecoratingHttpService implements WebSocketDecoratingHandler {

	private static final String REQUEST_HEADERS_DELIMITER_PATTERN = ",";
	private static final int CORS_MAX_AGE = 300;

	private final boolean preflightEnabled;

	@Nonnull
	private final Set<String> allowedMethods;
	@Nonnull
	private final Set<String> allowedHeaders;

	private CorsService(@Nullable HttpService delegate,
	                    boolean preflightEnabled,
	                    @Nonnull Set<HttpMethod> allowedMethods,
	                    @Nonnull Set<AsciiString> allowedHeaders) {
		super(delegate != null ? delegate : new NotFoundService());
		this.preflightEnabled = preflightEnabled;
		this.allowedMethods = allowedMethods.stream().map(HttpMethod::name).map(String::toLowerCase).collect(Collectors.toSet());
		this.allowedHeaders = allowedHeaders.stream().map(AsciiString::toString).map(String::toLowerCase).collect(Collectors.toSet());
	}

	@Nonnull
	public static CorsService filter(@Nullable HttpService delegate,
	                                 @Nonnull Set<HttpMethod> allowedMethods,
	                                 @Nonnull Set<AsciiString> allowedHeaders) {
		return new CorsService(
			delegate,
			true,
			allowedMethods,
			allowedHeaders
		);
	}

	@Nonnull
	public static CorsService standaloneFilter(@Nullable HttpService delegate) {
		return new CorsService(
			delegate,
			false,
			Set.of(),
			Set.of()
		);
	}


	@Nonnull
	public static CorsService preflightHandler(@Nonnull Set<HttpMethod> allowedMethods,
	                                           @Nonnull Set<AsciiString> allowedHeaders) {
		return new CorsService(
			new NotFoundService(),
			true,
			allowedMethods,
			allowedHeaders
		);
	}

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		final String requestOrigin = req.headers().get(HttpHeaderNames.ORIGIN);
		// only verify CORS requests, non-CORS requests needs to be handled in a standard way
		if (isCorsRequest(requestOrigin)) {
			if (this.preflightEnabled && isPreflightRequest(req)) {
				final String requestMethod = req.headers().get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD);
				if (!isRequestMethodAllowed(requestMethod)) {
					log.warn("Forbidden request method `{}` for origin `{}` is trying to access the API.", requestMethod, requestOrigin);
					return handleForbidden();
				}
				final String requestHeaders = req.headers().get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS);
				if (!isRequestHeaderAllowed(requestHeaders)) {
					log.warn("Forbidden request headers `{}` for origin `{}` is trying to access the API.", requestHeaders, requestOrigin);
					return handleForbidden();
				}

				return handlePreflightRequest();
			} else {
				return handleVerifyingRequest(ctx, req);
			}
		} else {
			return delegateServe(ctx, req);
		}
	}

	@Nonnull
	@Override
	public WebSocket handle(@Nonnull ServiceRequestContext ctx, @Nonnull RoutableWebSocket in) {
		// there is currently no way to properly validate CORS requests for WebSocket connections, and it seems
		// that browsers don't enforce CORS for WebSocket connections either right now
		return this.unwrapWebSocketHandler().handle(ctx, in);
	}

	private static boolean isCorsRequest(@Nullable String requestOrigin) {
		return requestOrigin != null;
	}

	private boolean isRequestMethodAllowed(@Nullable String requestMethod) {
		if (requestMethod == null) {
			log.warn("Missing request method in preflight request.");
			return false;
		}
		return this.allowedMethods.contains(requestMethod.toLowerCase());
	}

	private boolean isRequestHeaderAllowed(@Nullable String requestHeaders) {
		if (requestHeaders == null) {
			log.warn("Missing request header in preflight request.");
			return false;
		}
		return Arrays.stream(requestHeaders.split(REQUEST_HEADERS_DELIMITER_PATTERN))
			.map(String::trim)
			.map(String::toLowerCase)
			.allMatch(this.allowedHeaders::contains);
	}

	@Nonnull
	private static HttpResponse handleForbidden() {
		final HttpResponseBuilder responseBuilder = HttpResponse.builder();
		responseBuilder.status(HttpStatus.FORBIDDEN);
		return responseBuilder.build();
	}

	private static boolean isPreflightRequest(@Nonnull HttpRequest req) {
		return HttpMethod.OPTIONS.equals(req.method());
	}

	@Nonnull
	private HttpResponse handlePreflightRequest() {
		final HttpResponseBuilder responseBuilder = HttpResponse.builder();

		responseBuilder.header(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		responseBuilder.header(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, this.allowedMethods.stream().map(Objects::toString).collect(Collectors.joining(", ")));
		responseBuilder.header(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, String.join(", ", this.allowedHeaders));
		responseBuilder.header(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, CORS_MAX_AGE);
		responseBuilder.status(HttpStatus.OK);

		return responseBuilder.build();
	}

	@Nonnull
	private HttpResponse handleVerifyingRequest(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		final Map<AsciiString, String> responseHeaders = createHashMap(1);
		responseHeaders.put(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		return delegateServe(ctx, req, responseHeaders);
	}

	@Nonnull
	private HttpResponse delegateServe(@Nonnull ServiceRequestContext ctx,
	                                   @Nonnull HttpRequest req) throws Exception {
		return delegateServe(ctx, req, Map.of());
	}

	@Nonnull
	private HttpResponse delegateServe(@Nonnull ServiceRequestContext ctx,
	                                   @Nonnull HttpRequest req,
	                                   @Nullable Map<AsciiString, String> responseHeaders) throws Exception {
		return unwrap().serve(ctx, req).mapHeaders(headers -> {
			final ResponseHeadersBuilder builder = headers.toBuilder();
			if (responseHeaders != null) {
				responseHeaders.forEach((name, value) -> {
					builder.removeAndThen(name).add(name, value);
				});
			}
			return builder.build();
		});
	}
}