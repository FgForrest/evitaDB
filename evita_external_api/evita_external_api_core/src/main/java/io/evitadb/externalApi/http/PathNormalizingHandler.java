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
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Normalizes request path for all subsequent handlers. Currently, it removes trailing slash if present to support
 * endpoints on URLs with and without trailing slash because the {@link io.undertow.server.RoutingHandler} doesn't
 * accept multiple same URL one with slash and one without.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class PathNormalizingHandler implements HttpService {

	@Nonnull private final HttpService next;

	@Override
	public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
		if (!req.path().isEmpty() && req.path().charAt(req.path().length() - 1) == '/') {
			final String path = req.path().substring(0, req.path().length() - 1);
			//todo tpz solve
		}
		return next.serve(ctx, req);
	}
}
