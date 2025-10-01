/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.utils.path.RoutingHandlerService;
import io.evitadb.externalApi.utils.path.routing.PathHandlerDescriptor;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.net.URI;
import java.util.Optional;

/**
 * Normalizes request path for all subsequent handlers. Currently, it removes trailing slash if present to support
 * endpoints on URLs with and without trailing slash because the {@link RoutingHandlerService} doesn't
 * accept multiple same URL one with slash and one without.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class PathNormalizingHandler extends SimpleDecoratingHttpService implements WebSocketHandler {
	private static final char SLASH = '/';
	private static final char QUESTION_MARK = '?';

	/**
	 * Creates a new instance that decorates the specified {@link HttpService}.
	 */
	public PathNormalizingHandler(HttpService delegate) {
		super(delegate);
	}

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, HttpRequest req) throws Exception {
		final String currentPath = req.path();
		final String normalizedPath;
		if (!currentPath.isEmpty() && currentPath.charAt(currentPath.length() - 1) == SLASH) {
			normalizedPath = currentPath.substring(0, currentPath.length() - 1);
		} else if (currentPath.isEmpty()) {
			normalizedPath = String.valueOf(SLASH);
		} else if (currentPath.contains(String.valueOf(QUESTION_MARK))) {
			// todo lho fix?
			final URI uri = req.uri();
			final String baseUrl = Optional.ofNullable(uri.getQuery())
				.map(query -> uri.getPath() + QUESTION_MARK + query)
				.orElse(uri.getPath());
			if (baseUrl.charAt(baseUrl.length() - 1) == SLASH) {
				normalizedPath = baseUrl.substring(0, baseUrl.length() - 1);
			} else {
				normalizedPath = baseUrl;
			}
		} else {
			return this.unwrap().serve(ctx, req);
		}

 		final RequestHeaders newHeaders = req.headers().withMutations(builder -> builder.set(":path", normalizedPath));
		return this.unwrap().serve(ctx, req.withHeaders(newHeaders));
	}

	@Nonnull
	@Override
	public WebSocket handle(@Nonnull ServiceRequestContext ctx, @Nonnull RoutableWebSocket in) {
		final WebSocketHandler webSocketHandler = this.unwrap().as(WebSocketHandler.class);
		if (webSocketHandler == null) {
			// todo lho verify error handling
			throw new ExternalApiInternalError("Expected Web Socket handler.");
		}

		final String currentPath = in.path();
		final String normalizedPath;
		if (!currentPath.isEmpty() && currentPath.charAt(currentPath.length() - 1) == SLASH) {
			normalizedPath = currentPath.substring(0, currentPath.length() - 1);
		} else if (currentPath.isEmpty()) {
			normalizedPath = String.valueOf(SLASH);
		} else if (currentPath.contains(String.valueOf(QUESTION_MARK))) {
			// todo lho fix?
//			final URI uri = req.uri();
//			final String baseUrl = Optional.ofNullable(uri.getQuery())
//			                               .map(query -> uri.getPath() + QUESTION_MARK + query)
//			                               .orElse(uri.getPath());
//			if (baseUrl.charAt(baseUrl.length() - 1) == SLASH) {
//				normalizedPath = baseUrl.substring(0, baseUrl.length() - 1);
//			} else {
//				normalizedPath = baseUrl;
//			}
			normalizedPath = currentPath;
		} else {
			return webSocketHandler.handle(ctx, in);
		}

		return webSocketHandler.handle(ctx, in.withPath(normalizedPath));
	}
}
