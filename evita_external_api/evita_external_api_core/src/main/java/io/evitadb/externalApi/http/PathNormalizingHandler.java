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
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.externalApi.utils.path.RoutingHandlerService;
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
@RequiredArgsConstructor
public class PathNormalizingHandler implements HttpService {
	private static final char SLASH = '/';
	private static final char QUESTION_MARK = '?';

	@Nonnull private final HttpService next;

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, HttpRequest req) throws Exception {
		final String path;
		if (!req.path().isEmpty() && req.path().charAt(req.path().length() - 1) == SLASH) {
			path = req.path().substring(0, req.path().length() - 1);
		} else if (req.path().isEmpty()) {
			path = String.valueOf(SLASH);
		} else if (req.path().contains(String.valueOf(QUESTION_MARK))) {
			final URI uri = req.uri();
			final String baseUrl = Optional.ofNullable(uri.getQuery())
				.map(query -> uri.getPath() + QUESTION_MARK + query)
				.orElse(uri.getPath());
			if (baseUrl.charAt(baseUrl.length() - 1) == SLASH) {
				path = baseUrl.substring(0, baseUrl.length() - 1);
			} else {
				path = baseUrl;
			}
		} else {
			return this.next.serve(ctx, req);
		}

 		final RequestHeaders newHeaders = req.headers().withMutations(builder -> builder.set(":path", path));
		return this.next.serve(ctx, req.withHeaders(newHeaders));
	}

}
