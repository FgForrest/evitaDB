/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.performance.externalApi.routing;

import com.linecorp.armeria.common.*;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.externalApi.utils.NotFoundService;
import io.evitadb.externalApi.http.routing.RouteParameters;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.evitadb.performance.externalApi.routing.RoutingBenchmarkFixtures.RoutingBenchmarkRoute;

/**
 * Plain HashMap plus substring reference, confined to the benchmark source. Prefix lengths are tried descending;
 * endpoints use one trie per method and the same literal-first backtracking and context-capture contract as REGION.
 * Only the generated REST grammar used by EndpointRoutingBenchmark is implemented (no wildcard or encoded targets).
 * Unlike a linear template scan, this keeps the matching topology constant while changing the table/key strategy.
 * There is no fork logger in this arm. Maps are writer-owned during setup and never changed after publication.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class SubstringRoutingReference implements HttpService {
	private static final HttpMethod[] METHODS = HttpMethod.values();
	private final Map<String, HttpService> prefixes;
	private final int[] lengths;
	private final SubstringNode[] roots;
	private final HttpService notFound = new NotFoundService();

	/** Freezes prefix bindings and distinct descending lengths. */
	SubstringRoutingReference(@Nonnull Map<String, HttpService> bindings) {
		this.prefixes = CollectionUtils.createHashMap(bindings.size());
		this.prefixes.putAll(bindings);
		this.lengths = bindings.keySet().stream().mapToInt(String::length).distinct().sorted().toArray();
		this.roots = new SubstringNode[0];
	}

	/** Compiles the identical generated endpoint inventory into substring-keyed method tries. */
	SubstringRoutingReference(@Nonnull List<RoutingBenchmarkRoute> routes) {
		this.prefixes = Map.of();
		this.lengths = new int[0];
		this.roots = new SubstringNode[METHODS.length];
		for (final RoutingBenchmarkRoute route : routes) {
			SubstringNode node = this.roots[route.method().ordinal()];
			if (node == null) {
				node = new SubstringNode();
				this.roots[route.method().ordinal()] = node;
			}
			final String[] parts = route.template().equals("/") ?
				new String[0] : route.template().substring(1).split("/");
			for (final String part : parts) {
				if (part.startsWith("{")) {
					if (node.parameter == null) {
						node.parameter = new SubstringNode();
					}
					node = node.parameter;
				} else {
					node = node.literals.computeIfAbsent(part, key -> new SubstringNode());
				}
			}
			node.terminal = new SubstringTerminal(route, parts);
		}
	}

	/** Routes one request with substring map probes and context capture transport. */
	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		final String path = req.path().isEmpty() ? "/" : req.path();
		final int query = path.indexOf('?');
		int end = query < 0 ? path.length() : query;
		while (end > 1 && path.charAt(end - 1) == '/') {
			end--;
		}
		if (this.roots.length == 0) {
			for (int i = this.lengths.length - 1; i >= 0; i--) {
				final int length = this.lengths[i];
				if (length <= end && (length == end || path.charAt(length) == '/')) {
					final HttpService handler = this.prefixes.get(path.substring(0, length));
					if (handler != null) {
						return handler.serve(ctx, req.withHeaders(req.headers().toBuilder()
							.path(path.substring(length, end)).build()));
					}
				}
			}
			return this.notFound.serve(ctx, req);
		}
		final SubstringTerminal match = find(this.roots[req.method().ordinal()], path, 1, end);
		if (match != null) {
			if (match.parameterNames.length > 0) {
				ctx.setAttr(RouteParameters.KEY, match.captureParameters(path, end));
			}
			return match.route.handler().serve(ctx, req);
		}
		StringBuilder allow = null;
		for (final HttpMethod method : METHODS) {
			if (method != req.method() && find(this.roots[method.ordinal()], path, 1, end) != null) {
				if (allow == null) {
					allow = new StringBuilder(METHODS.length * 10);
				} else {
					allow.append(", ");
				}
				allow.append(method.name());
			}
		}
		return allow == null ? this.notFound.serve(ctx, req) : HttpResponse.builder()
			.status(HttpStatus.METHOD_NOT_ALLOWED)
			.header(HttpHeaderNames.ALLOW, allow.toString())
			.content(MediaType.PLAIN_TEXT, "405 Method Not Allowed: The requested method is not supported.")
			.build();
	}

	/** Walks literal children through fresh substring keys, then backtracks into the parameter child. */
	@Nullable
	private static SubstringTerminal find(@Nullable SubstringNode node, @Nonnull String path, int start, int limit) {
		if (node == null) {
			return null;
		}
		if (start > limit || (start == 1 && limit == 1)) {
			return node.terminal;
		}
		final int slash = path.indexOf('/', start);
		final int end = slash < 0 ? limit : Math.min(slash, limit);
		final SubstringTerminal literal = node.literals.isEmpty() ? null :
			find(node.literals.get(path.substring(start, end)), path, end + 1, limit);
		return literal == null ? find(node.parameter, path, end + 1, limit) : literal;
	}

	/**
	 * Method trie node populated exclusively during setup.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class SubstringNode {
		private final Map<String, SubstringNode> literals = CollectionUtils.createHashMap(4);
		private SubstringNode parameter;
		private SubstringTerminal terminal;

		/** Creates a setup-owned node. */
		private SubstringNode() {
		}
	}

	/**
	 * Precompiled capture metadata, mirroring the REGION arm's successful-match copy work.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class SubstringTerminal {
		private final RoutingBenchmarkRoute route;
		private final String[] parameterNames;
		private final int[] positions;

		/** Compiles parameter names and segment positions, outside measurement. */
		private SubstringTerminal(@Nonnull RoutingBenchmarkRoute route, @Nonnull String[] parts) {
			this.route = route;
			final String[] names = new String[parts.length];
			final int[] segments = new int[parts.length];
			int count = 0;
			for (int i = 0; i < parts.length; i++) {
				if (parts[i].startsWith("{")) {
					names[count] = parts[i].substring(1, parts[i].length() - 1);
					segments[count++] = i;
				}
			}
			this.parameterNames = Arrays.copyOf(names, count);
			this.positions = Arrays.copyOf(segments, count);
		}

		/** Copies captures only after a successful match, without shared mutable capture storage. */
		@Nonnull
		private RouteParameters captureParameters(@Nonnull String path, int limit) {
			final String[] values = new String[this.positions.length];
			int start = 1;
			int segment = 0;
			for (int i = 0; i < this.positions.length; i++) {
				while (segment < this.positions[i]) {
					start = path.indexOf('/', start) + 1;
					segment++;
				}
				final int slash = path.indexOf('/', start);
				final int end = slash < 0 ? limit : Math.min(slash, limit);
				values[i] = path.substring(start, end);
				start = end + 1;
				segment++;
			}
			return new RouteParameters(this.parameterNames, values);
		}
	}
}
