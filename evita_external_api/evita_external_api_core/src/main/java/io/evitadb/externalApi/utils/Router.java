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

package io.evitadb.externalApi.utils;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;

import javax.annotation.Nonnull;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public class Router {
	private final String prefix;
	private final Map<String, Router> subRouters = new HashMap<>(8);
	private final Map<HttpMethod, HttpService> services = new EnumMap<>(HttpMethod.class);

	public Router(String prefix) {
		this.prefix = prefix;
	}

	public Router() {
		this.prefix = "/";
	}

	public void addService(HttpMethod method, String subPath, HttpService service) {
		services.put(method, service);
		// Register the service dynamically based on the method and subPath
	}

	public Router addSubRouter(String subPath) {
		return subRouters.computeIfAbsent(subPath, p -> new Router(this.prefix + p));
	}

	public Router addSubRouter(Router router) {
		return subRouters.computeIfAbsent(router.prefix, r -> router);
	}

	public void registerRoutes(@Nonnull ServerBuilder serverBuilder) {
		for (Map.Entry<HttpMethod, HttpService> entry : services.entrySet()) {
			serverBuilder.route()
				.path(prefix)
				.methods(entry.getKey())
				.build(entry.getValue());
		}
		for (Router subRouter : subRouters.values()) {
			subRouter.registerRoutes(serverBuilder);
		}
	}

	public void unregisterRoute(String route) {
		final String subPath = route.substring(prefix.length());
		final int slashIndex = subPath.indexOf('/');
		if (slashIndex == -1) {
			throw new RuntimeException("No / in route path!");
		} else {
			final String subRouterPath = subPath.substring(0, slashIndex);
			final Router subRouter = subRouters.get(subRouterPath);
			if (subRouter != null) {
				subRouter.unregisterRoute(route);
			}
		}
	}

	public HttpResponse handleRequest(ServiceRequestContext ctx, HttpRequest request) {
		return handleRequest(ctx, request, null);
	}

	public HttpResponse handleRequest(ServiceRequestContext ctx, HttpRequest request, String path) {
		final String pathToUse = path == null ? request.path() : path;
		if (prefix.equals(pathToUse)) {
			try {
				return services.get(request.method()).serve(ctx, request);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		final String subPath = pathToUse.substring(prefix.length());
		final int slashIndex = subPath.indexOf('/');
		if (slashIndex == -1) {
			throw new RuntimeException("No / in route path!");
		} else {
			final String subRouterPath = subPath.substring(0, slashIndex);
			final Router subRouter = subRouters.get(subRouterPath);
			if (subRouter != null) {
				return subRouter.handleRequest(ctx, request, subPath);
			}
			throw new RuntimeException("No matching route found for " + pathToUse);
		}
	}
}
