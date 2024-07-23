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
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseBuilder;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import io.netty.util.AsciiString;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Filters requests with CORS. Mainly, checks if request origin is allowed to access certain endpoint. Should be
 * used as filter for all standard endpoints. Also, appends CORS header for standard requests.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class CorsFilter extends SimpleDecoratingHttpService {

	/**
	 * Set of allowed origins, other origins will return error. If null, all origins are allowed.
	 */
	@Nullable
	private final Set<String> allowedOrigins;

	public CorsFilter(@Nonnull HttpService delegate, @Nullable String[] allowedOrigins) {
		this(
			delegate,
			allowedOrigins != null ? Set.of(allowedOrigins) : null
		);
	}

	public CorsFilter(@Nonnull HttpService delegate, @Nullable Set<String> allowedOrigins) {
		super(delegate);
		this.allowedOrigins = allowedOrigins;
	}

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		final Map<AsciiString, String> responseHeaders = createHashMap(3);

		if (allowedOrigins != null) {
			final String requestOrigin = req.headers().get(HttpHeaderNames.ORIGIN);
			if (requestOrigin == null || !allowedOrigins.contains(requestOrigin)) {
				log.warn("Forbidden origin `" + requestOrigin + "` is trying to access the API.");
				final HttpResponseBuilder responseBuilder = HttpResponse.builder();
				responseBuilder.status(HttpStatus.FORBIDDEN);
				return responseBuilder.build();
			}

			responseHeaders.put(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, requestOrigin);
			responseHeaders.put(HttpHeaderNames.VARY, "Origin");
		} else {
			responseHeaders.put(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		}

		return unwrap().serve(ctx, req).mapHeaders(headers -> {
			final ResponseHeadersBuilder builder = headers.toBuilder();
			responseHeaders.forEach((name, value) -> {
				builder.removeAndThen(name).add(name, value);
			});
			return builder.build();
		});
	}
}
