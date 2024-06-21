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
import com.linecorp.armeria.server.cors.CorsService;
import com.linecorp.armeria.server.cors.CorsServiceBuilder;
import io.undertow.server.HttpHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.function.Function;

/**
 * Filters requests with CORS. Mainly, checks if request origin is allowed to access certain endpoint. Should be
 * used as filter for all standard endpoints. Also, appends CORS header for standard requests.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
@RequiredArgsConstructor
public class CorsFilterServiceDecorator  {
	/**
	 * Set of allowed origins, other origins will return error. If null, all origins are allowed.
	 */
	@Nullable
	private final Set<String> allowedOrigins;

	public CorsFilterServiceDecorator(@Nullable String[] allowedOrigins) {
		this(
			allowedOrigins != null ? Set.of(allowedOrigins) : null
		);
	}

	public Function<? super HttpService, CorsService> createDecorator() {
		// todo: verify
		final CorsServiceBuilder builder;
		if (allowedOrigins != null) {
			builder = CorsService.builder(allowedOrigins);
			builder.allowNullOrigin();
			builder.preflightResponseHeader("Vary", "Origin");
		} else {
			builder = CorsService.builder("*");
			builder.preflightResponseHeader(AdditionalHeaders.ACCESS_CONTROL_ALLOW_ORIGIN_STRING, "*");
		}
		return builder.newDecorator();
	}
}
