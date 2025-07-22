/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.evitadb.externalApi.utils.path;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
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
public class PathHandlingService implements HttpService {
	private final PathMatcher<HttpService> pathMatcher = new PathMatcher<>();

	private final LRUCache<String, PathMatch<HttpService>> cache;
	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		PathMatcher.PathMatch<HttpService> match = null;
		boolean hit = false;
		final String path = URLUtils.normalizeSlashes(req.uri().getPath());
		if(cache != null) {
			match = cache.get(path);
			hit = true;
		}
		if(match == null) {
			match = pathMatcher.match(path);
		}
		if (match.getValue() == null) {
			return HttpResponse.of(HttpStatus.NOT_FOUND);
		}
		if(hit) {
			cache.add(path, match);
		}
		final RequestHeadersBuilder headersBuilder = req.headers().toBuilder().path(match.getRemaining());
		return match.getValue().serve(ctx, req.withHeaders(headersBuilder.build()));
	}

	public PathHandlingService(final HttpService defaultHandler) {
		this(0);
		pathMatcher.addPrefixPath("/", defaultHandler);
	}

	public PathHandlingService(final HttpService defaultHandler, int cacheSize) {
		this(cacheSize);
		pathMatcher.addPrefixPath("/", defaultHandler);
	}

	public PathHandlingService() {
		this(0);
	}

	public PathHandlingService(int cacheSize) {
		if(cacheSize > 0) {
			cache = new LRUCache<>(cacheSize, -1, true);
		} else {
			cache = null;
		}
	}

	@Override
	public String toString() {
		Set<Entry<String,HttpService>> paths = pathMatcher.getPaths().entrySet();
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
	 * The match is done on a prefix bases, so registering /foo will also match /foo/bar. Exact
	 * path matches are taken into account first.
	 * <p>
	 * If / is specified as the path then it will replace the default handler.
	 *
	 * @param path    The path
	 * @param handler The handler
	 * @see #addPrefixPath(String, HttpService)
	 * @deprecated Superseded by {@link #addPrefixPath(String, HttpService)}.
	 */
	@Deprecated(since="1.0.0", forRemoval=true)
	public synchronized PathHandlingService addPath(final String path, final HttpService handler) {
		return addPrefixPath(path, handler);
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
		pathMatcher.addPrefixPath(path, handler);
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
		pathMatcher.addExactPath(path, handler);
		return this;
	}

	@Deprecated(since = "2024.7", forRemoval = true)
	public synchronized PathHandlingService removePath(final String path) {
		return removePrefixPath(path);
	}

	public synchronized PathHandlingService removePrefixPath(final String path) {
		pathMatcher.removePrefixPath(path);
		return this;
	}

	public synchronized PathHandlingService removeExactPath(final String path) {
		pathMatcher.removeExactPath(path);
		return this;
	}

	public synchronized PathHandlingService clearPaths() {
		pathMatcher.clearPaths();
		return this;
	}
}
