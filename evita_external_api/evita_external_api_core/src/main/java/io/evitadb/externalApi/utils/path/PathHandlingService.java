/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.utils.path;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.WebSocketServiceHandler;
import io.evitadb.externalApi.http.WebSocketHandler;
import io.evitadb.externalApi.http.RoutableWebSocket;
import io.evitadb.externalApi.utils.NotFoundService;
import io.evitadb.externalApi.utils.NotFoundWebSocketHandler;
import io.evitadb.externalApi.utils.path.routing.PathHandlerDescriptor;
import io.evitadb.externalApi.utils.path.routing.PathMatcher;
import io.evitadb.externalApi.utils.path.routing.PathMatcher.PathMatch;
import io.evitadb.externalApi.utils.path.routing.cache.LRUCache;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adapted from `io.undertow.server.handlers.PathHandler` to Armeria.
 *
 * Handler that dispatches to a given handler based of a prefix match of the path.
 * <p>
 * This only matches a single level of a request, e.g if you have a request that takes the form:
 * <p>
 * /foo/bar
 * <p>
 *
 * @author Stuart Douglas
 */
public class PathHandlingService implements HttpService, WebSocketHandler {
	private final PathMatcher<PathHandlerDescriptor> pathMatcher = new PathMatcher<>();

	private final LRUCache<String, PathMatch<PathHandlerDescriptor>> cache;

	private final HttpService fallbackHttpService = new NotFoundService();
	private final WebSocketHandler fallbackWebSocketHandler = new NotFoundWebSocketHandler();

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		PathMatcher.PathMatch<PathHandlerDescriptor> match = null;
		boolean hit = false;
		final String path = URLUtils.normalizeSlashes(req.uri().getPath());
		if(this.cache != null) {
			match = this.cache.get(path);
			hit = true;
		}
		if (match == null) {
			match = this.pathMatcher.match(path);
		}
		if (match.getValue() == null || !match.getValue().isHttpService()) {
			return this.fallbackHttpService.serve(ctx, req);
		}
		if (hit) {
			this.cache.add(path, match);
		}
		final RequestHeadersBuilder headersBuilder = req.headers().toBuilder().path(match.getRemaining());
		return match.getValue().asHttpService().serve(ctx, req.withHeaders(headersBuilder.build()));
	}

	@Override
	@Nonnull
	public WebSocket handle(@Nonnull ServiceRequestContext ctx, @Nonnull RoutableWebSocket in) {
		PathMatcher.PathMatch<PathHandlerDescriptor> match = null;
		boolean hit = false;
		final String path = URLUtils.normalizeSlashes(in.path());
		if (this.cache != null) {
			match = this.cache.get(path);
			hit = true;
		}
		if (match == null) {
			match = this.pathMatcher.match(path);
		}
		if (match.getValue() == null || !match.getValue().isWebSocketHandler()) {
			return this.fallbackWebSocketHandler.handle(ctx, in);
		}
		if (hit) {
			this.cache.add(path, match);
		}
		return match.getValue().asWebSocketHandler().handle(ctx, in.withPath(match.getRemaining()));
	}

	public PathHandlingService() {
		this(0);
	}

	public PathHandlingService(int cacheSize) {
		if(cacheSize > 0) {
			this.cache = new LRUCache<>(cacheSize, -1, true);
		} else {
			this.cache = null;
		}
	}

	@Override
	public String toString() {
		Set<Entry<String, PathHandlerDescriptor>> paths = this.pathMatcher.getPaths().entrySet();
		if (paths.size() == 1) {
			return "path( " + paths.toArray()[0] + " )";
		} else {
			return "path( {" + paths.stream().map(s -> s.getValue().toString()).collect(Collectors.joining(", ")) + "} )";
		}
	}

	/**
	 * Adds a path prefix and a handler for that path. If the path does not start
	 * with a / then one will be prepended.
	 * <p>
	 * The match is done on a prefix bases, so registering /foo will also match /foo/bar.
	 * Though exact path matches are taken into account before prefix path matches. So
	 * if an exact path match exists its handler will be triggered.
	 * <p>
	 * If / is specified as the path then it will replace the default handler.
	 *
	 * @param path    If the request contains this prefix, run handler.
	 * @param handler The handler which is activated upon match.
	 * @return The resulting PathHandler after this path has been added to it.
	 */
	public synchronized PathHandlingService addPrefixPath(final String path, final Object handler) {
		Assert.notNull(handler, "Path handler cannot be null.");
		this.pathMatcher.addPrefixPath(path, new PathHandlerDescriptor(handler));
		return this;
	}

	/**
	 * Adds a path prefix and a handler for that path. If the path does not start
	 * with a / then one will be prepended.
	 * <p>
	 * The match is done on a prefix bases, so registering /foo will also match /foo/bar.
	 * Though exact path matches are taken into account before prefix path matches. So
	 * if an exact path match exists its handler will be triggered.
	 * <p>
	 * If / is specified as the path then it will replace the default handler.
	 *
	 * @param path    If the request contains this prefix, run handler.
	 * @param handler The handler which is activated upon match.
	 * @return The resulting PathHandler after this path has been added to it.
	 */
	public synchronized PathHandlingService addPrefixPath(final String path, final HttpService handler) {
		Assert.notNull(handler, "Path handler cannot be null.");
		this.pathMatcher.addPrefixPath(path, new PathHandlerDescriptor(handler));
		return this;
	}

	/**
	 * Adds a path prefix and a handler for that path. If the path does not start
	 * with a / then one will be prepended.
	 * <p>
	 * The match is done on a prefix bases, so registering /foo will also match /foo/bar.
	 * Though exact path matches are taken into account before prefix path matches. So
	 * if an exact path match exists its handler will be triggered.
	 * <p>
	 * If / is specified as the path then it will replace the default handler.
	 *
	 * @param path    If the request contains this prefix, run handler.
	 * @param handler The handler which is activated upon match.
	 * @return The resulting PathHandler after this path has been added to it.
	 */
	public synchronized PathHandlingService addPrefixPath(final String path, final WebSocketServiceHandler handler) {
		Assert.notNull(handler, "Path handler cannot be null.");
		this.pathMatcher.addPrefixPath(path, new PathHandlerDescriptor(handler));
		return this;
	}

	/**
	 * If the request path is exactly equal to the given path, run the handler.
	 * <p>
	 * Exact paths are prioritized higher than prefix paths.
	 *
	 * @param path If the request path is exactly this, run handler.
	 * @param handler Handler run upon exact path match.
	 * @return The resulting PathHandler after this path has been added to it.
	 */
	public synchronized PathHandlingService addExactPath(final String path, final Object handler) {
		Assert.notNull(handler, "Path handler cannot be null.");
		this.pathMatcher.addExactPath(path, new PathHandlerDescriptor(handler));
		return this;
	}

	/**
	 * If the request path is exactly equal to the given path, run the handler.
	 * <p>
	 * Exact paths are prioritized higher than prefix paths.
	 *
	 * @param path If the request path is exactly this, run handler.
	 * @param handler Handler run upon exact path match.
	 * @return The resulting PathHandler after this path has been added to it.
	 */
	public synchronized PathHandlingService addExactPath(final String path, final HttpService handler) {
		Assert.notNull(handler, "Path handler cannot be null.");
		this.pathMatcher.addExactPath(path, new PathHandlerDescriptor(handler));
		return this;
	}

	/**
	 * If the request path is exactly equal to the given path, run the handler.
	 * <p>
	 * Exact paths are prioritized higher than prefix paths.
	 *
	 * @param path If the request path is exactly this, run handler.
	 * @param handler Handler run upon exact path match.
	 * @return The resulting PathHandler after this path has been added to it.
	 */
	public synchronized PathHandlingService addExactPath(final String path, final WebSocketServiceHandler handler) {
		Assert.notNull(handler, "Path handler cannot be null.");
		this.pathMatcher.addExactPath(path, new PathHandlerDescriptor(handler));
		return this;
	}

	public synchronized PathHandlingService removePrefixPath(final String path) {
		this.pathMatcher.removePrefixPath(path);
		return this;
	}

	public synchronized PathHandlingService removeExactPath(final String path) {
		this.pathMatcher.removeExactPath(path);
		return this;
	}

	public synchronized PathHandlingService clearPaths() {
		this.pathMatcher.clearPaths();
		return this;
	}

}
