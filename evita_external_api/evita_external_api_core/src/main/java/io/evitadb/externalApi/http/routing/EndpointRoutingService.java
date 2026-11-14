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

package io.evitadb.externalApi.http.routing;

import com.linecorp.armeria.common.*;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.http.RoutableWebSocket;
import io.evitadb.externalApi.http.WebSocketHandler;
import io.evitadb.externalApi.utils.NotFoundService;
import io.evitadb.externalApi.utils.NotFoundWebSocketHandler;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


/**
 * Method-first endpoint dispatcher at the leaf of the routing tree. A {@link PrefixRoutingService} above it selects
 * the API and supplies the rest of the path, and this router matches that remainder against endpoint templates.
 *
 * ## Template grammar
 *
 * - A template consists of whole segments: literals, `{name}` parameters and an optional final `*` wildcard.
 *   A missing leading slash is supplied and the empty template denotes `/`. The empty name of `{}` is accepted
 *   too; its capture name is empty.
 * - A wildcard must be the only route out of its node: no route may end there or continue through a literal or
 *   a parameter, whichever is registered first.
 * - Non-root trailing slashes, question marks, repeated parameter names, empty segments after a parameter,
 *   partial parameters (`{x}tail`, `{x`) and partial or non-final wildcards throw
 *   {@link GenericEvitaInternalError}. Empty segments before the first parameter are literals, so `//file` is valid.
 * - A repeated registration whose spelling, leading slash included, equals the stored template keeps the first
 *   handler. Every other registration on an occupied route throws: a different parameter name, and a repeat
 *   written without the leading slash.
 *
 * {@link Builder#addLiteral} registers every segment as literal text without grammar validation or path captures,
 * for paths taken from configuration, such as file names, that must not be read as templates.
 * It supplies a missing leading slash, retains empty segments, shares the method tries and duplicate handling,
 * and obeys the same sole-route wildcard restriction. Braces, stars and question marks have no template meaning.
 *
 * ## Matching
 *
 * Each HTTP method has its own trie. The literal child, the parameter child and the wildcard are tried in that
 * order, backtracking out of a branch that fails deeper down. A wildcard captures the rest of the path, slashes
 * included, and needs one more segment: `/a/*` does not match `/a`, while `/*` matches `/` with an empty capture.
 * Input is the encoded request target, bounded before its real query and trailing slashes. Segments split only
 * on raw slashes; escaped literal probes compare decoded characters without temporary strings. Captured strings
 * are decoded once after selection.
 * Escaped slash and question mark remain data within their segment. Malformed escapes or UTF-8 answer 404.
 *
 * ## 405 and 404
 *
 * Other methods are searched only after the request method misses. When some of them match, the answer is 405 with
 * exactly those methods in `Allow`, in `HttpMethod` declaration order; otherwise it is 404. The CONNECT route
 * registered automatically for a GET WebSocket handler dispatches like any other but never appears in `Allow`.
 *
 * ## Handler contract
 *
 * HTTP handlers receive the original request unchanged. Named captures are attached under {@link RouteParameters#KEY}
 * only for routes declaring parameters; query parsing belongs to the REST handler. Wildcard tails have no production
 * parameter reader. Production nests only prefix routers above one endpoint router, so no capture merge is needed.
 * WebSockets dispatch through GET on HTTP/1 and CONNECT on HTTP/2; another session protocol, a miss or a handler
 * that is not a {@link WebSocketHandler} gets the WebSocket fallback. WebSocket handlers receive their input
 * unchanged, and the capability is checked on the registered object without unwrapping it. Handlers must be
 * thread-safe.
 *
 * ## Threading
 *
 * A built router never changes and all its fields are final, so concurrent readers need no lock once the owner has
 * published it safely, for example through a prefix router's volatile table. `Builder#build()` copies the topology,
 * so later builder edits never reach a built router.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
public final class EndpointRoutingService implements HttpService, WebSocketHandler {
	/** Cached `HttpMethod.values()`, which clones on every call; its ordinals index the per-method tries. */
	private static final HttpMethod[] METHODS = HttpMethod.values();
	/** Trie roots indexed by `HttpMethod#ordinal()`; null for a method without routes. */
	private final EndpointTrieNode[] roots;
	/** Answers with the standard 404 body when no method has a route for the path. */
	private final HttpService notFound = new NotFoundService();
	/**
	 * Closes a WebSocket with a policy violation when its session protocol selects no method, its path is malformed
	 * or matches no route, or the route's registered object is not a {@link WebSocketHandler}.
	 */
	private final WebSocketHandler webSocketNotFound = new NotFoundWebSocketHandler();

	/** Freezes independent method tries, retaining explicit registration provenance for the cold 405 path. */
	private EndpointRoutingService(@Nonnull Builder builder) {
		this.roots = new EndpointTrieNode[METHODS.length];
		for (final HttpMethod method : METHODS) {
			final EndpointTrieBuilder root = builder.roots[method.ordinal()];
			if (root != null) {
				this.roots[method.ordinal()] = new EndpointTrieNode(root);
			}
		}
	}

	/**
	 * Selects the method before the encoded path, then attaches named captures to the context. A plain HTTP
	 * request with method CONNECT also reaches the CONNECT route registered automatically for a WebSocket handler.
	 */
	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		final String target = req.path();
		final String path = target.isEmpty() ? "/" : target;
		final long scanned = RoutingPath.scan(path);
		final int pathEnd = (int) (scanned >>> 1);
		final boolean escaped = (scanned & 1) != 0;
		if (path.charAt(0) != '/' || scanned < 0) {
			return this.notFound.serve(ctx, req);
		}
		final EndpointRouteTerminal terminal = find(
			this.roots[req.method().ordinal()], path, 1, pathEnd, escaped, false
		);
		if (terminal != null) {
			if (terminal.parameterNames.length > 0) {
				final RouteParameters parameters = terminal.captureParameters(path, pathEnd, escaped);
				if (parameters == null) {
					return this.notFound.serve(ctx, req);
				}
				ctx.setAttr(RouteParameters.KEY, parameters);
			}
			return terminal.handler.serve(ctx, req);
		}
		return notFoundOrMethodNotAllowed(ctx, req, path, pathEnd, escaped);
	}

	/**
	 * Answers a request whose own method missed: 405 when another method matches the path, else 404. Only this cold
	 * path probes the other tries, and it ignores automatically registered CONNECT routes.
	 */
	@Nonnull
	private HttpResponse notFoundOrMethodNotAllowed(
		@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req, @Nonnull String path, int pathEnd,
		boolean escaped
	) throws Exception {
		StringBuilder allow = null;
		for (final HttpMethod method : METHODS) {
			if (method != req.method() &&
				find(this.roots[method.ordinal()], path, 1, pathEnd, escaped, true) != null) {
				if (allow == null) {
					allow = new StringBuilder(METHODS.length * 10);
				} else {
					allow.append(", ");
				}
				allow.append(method.name());
			}
		}
		if (allow == null) {
			return this.notFound.serve(ctx, req);
		}
		return HttpResponse.builder()
			.status(HttpStatus.METHOD_NOT_ALLOWED)
			.header(HttpHeaderNames.ALLOW, allow.toString())
			.content(MediaType.PLAIN_TEXT, "405 Method Not Allowed: The requested method is not supported.")
			.build();
	}

	/** Dispatches HTTP/1 WebSockets through GET and HTTP/2 WebSockets through CONNECT without header injection. */
	@Nonnull
	@Override
	public WebSocket handle(@Nonnull ServiceRequestContext ctx, @Nonnull RoutableWebSocket in) {
		final HttpMethod method;
		if (ctx.sessionProtocol().isExplicitHttp1()) {
			method = HttpMethod.GET;
		} else if (ctx.sessionProtocol().isExplicitHttp2()) {
			method = HttpMethod.CONNECT;
		} else {
			return this.webSocketNotFound.handle(ctx, in);
		}
		final String target = in.path();
		final String path = target.isEmpty() ? "/" : target;
		final long scanned = RoutingPath.scan(path);
		final int pathEnd = (int) (scanned >>> 1);
		final boolean escaped = (scanned & 1) != 0;
		if (path.charAt(0) != '/' || scanned < 0) {
			return this.webSocketNotFound.handle(ctx, in);
		}
		final EndpointRouteTerminal terminal = find(
			this.roots[method.ordinal()], path, 1, pathEnd, escaped, false
		);
		return terminal != null && terminal.handler instanceof WebSocketHandler handler ?
			handler.handle(ctx, in) : this.webSocketNotFound.handle(ctx, in);
	}

	/**
	 * Finds the route matching the rest of the path, trying the literal child, the parameter child and the wildcard in
	 * that order and backtracking out of a branch that fails deeper down.
	 *
	 * @param node         node reached by the path before `start`, or null when no route continues there
	 * @param path         encoded request path
	 * @param start        index of the first character of the current segment
	 * @param limit        exclusive end of the encoded path, excluding query and trailing slashes
	 * @param escaped      whether the bounded path contains an escape, determined at entry
	 * @param explicitOnly true to ignore automatically registered CONNECT routes, as the 405 probe does
	 * @return the matching route, or null
	 */
	@Nullable
	private static EndpointRouteTerminal find(
		@Nullable EndpointTrieNode node, @Nonnull String path, int start, int limit,
		boolean escaped, boolean explicitOnly
	) {
		if (node == null) {
			return null;
		}
		// the path ends at this node, or it is the root path `/`, whose single empty segment may still continue
		if (start > limit || (start == 1 && limit == 1)) {
			if (node.terminal != null && (!explicitOnly || node.terminal.explicit)) {
				return node.terminal;
			}
			if (start > limit) {
				return null;
			}
		}
		final long segment = RoutingPath.segment(path, start, limit, escaped);
		final int end = (int) (segment >>> 1);
		final EndpointTrieNode literal = node.literals.isEmpty() ? null : (segment & 1) != 0 ?
			node.literals.getEscaped(path, start, end) : node.literals.get(path, start, end - start);
		final EndpointRouteTerminal literalMatch = find(literal, path, end + 1, limit, escaped, explicitOnly);
		if (literalMatch != null) {
			return literalMatch;
		}
		if (node.parameter != null) {
			final EndpointRouteTerminal parameterMatch = find(
				node.parameter, path, end + 1, limit, escaped, explicitOnly
			);
			if (parameterMatch != null) {
				return parameterMatch;
			}
		}
		return node.wildcard != null && (!explicitOnly || node.wildcard.explicit) ? node.wildcard : null;
	}

	/**
	 * Builds complete endpoint routers before their publication by a prefix router.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@NotThreadSafe
	public static final class Builder {
		/** Mutable tries indexed by `HttpMethod#ordinal()`; null for a method without routes. */
		private final EndpointTrieBuilder[] roots = new EndpointTrieBuilder[METHODS.length];

		/** Creates an empty builder. */
		public Builder() {
		}

		/**
		 * Registers a handler for one method and template. A GET handler that is also a {@link WebSocketHandler} is
		 * registered for CONNECT on the same template too, marked automatic, so that HTTP/2 WebSockets reach it.
		 *
		 * @param method   method whose trie receives the route
		 * @param template endpoint template in the grammar described on {@link EndpointRoutingService}
		 * @param handler  thread-safe handler of the matched requests
		 * @return this builder
		 * @throws NullPointerException       when the handler is null
		 * @throws GenericEvitaInternalError when the template breaks the grammar or conflicts with a registered
		 *                                    route; the builder may then keep the GET half of a WebSocket
		 *                                    registration whose CONNECT half failed, so callers discard it
		 */
		@Nonnull
		public Builder add(@Nonnull HttpMethod method, @Nonnull String template, @Nonnull HttpService handler) {
			addRoute(method, template, handler, false);
			return this;
		}

		/**
		 * Registers a literal path, including empty segments and characters reserved by template registration.
		 * GET WebSocket handlers also receive an automatic CONNECT registration. No segment is a capture.
		 *
		 * @param method  method whose trie receives the route
		 * @param path    literal path; a missing leading slash is supplied
		 * @param handler thread-safe handler of the matched requests
		 * @return this builder
		 * @throws NullPointerException when the handler is null
		 * @throws GenericEvitaInternalError when the route conflicts with a wildcard or an occupied route;
		 *                                    discard the builder if the automatic CONNECT registration fails
		 */
		@Nonnull
		public Builder addLiteral(@Nonnull HttpMethod method, @Nonnull String path, @Nonnull HttpService handler) {
			addRoute(method, path, handler, true);
			return this;
		}

		/**
		 * Registers the explicit route and, for a GET WebSocket handler, its automatic CONNECT twin.
		 *
		 * @param literal true to register every segment as literal text instead of parsing a template
		 */
		private void addRoute(
			@Nonnull HttpMethod method, @Nonnull String input, @Nonnull HttpService handler, boolean literal
		) {
			Objects.requireNonNull(handler, "Handler must be specified.");
			register(method, input, handler, true, literal);
			if (method == HttpMethod.GET && handler instanceof WebSocketHandler) {
				register(HttpMethod.CONNECT, input, handler, false, literal);
			}
		}

		/**
		 * Inserts one template or literal route, rejecting a wildcard that would share its node with another route
		 * in either registration order. Template grammar is validated before any new node is created.
		 */
		private void register(
			@Nonnull HttpMethod method, @Nonnull String input, @Nonnull HttpService handler, boolean explicit,
			boolean literal
		) {
			final String template = input.startsWith("/") ? input : "/" + input;
			final String[] segments = literal ? RoutingPath.splitSegments(template) : parseSegments(template, input);
			EndpointTrieBuilder node = this.roots[method.ordinal()];
			if (node == null) {
				node = new EndpointTrieBuilder();
				this.roots[method.ordinal()] = node;
			}
			for (int i = 0; i < segments.length; i++) {
				final String segment = segments[i];
				if (!literal && segment.equals("*")) {
					if (node.parameter != null || !node.literals.isEmpty() || node.terminal != null) {
						throw invalid("Wildcard cannot share a node with another route", input);
					}
					node.wildcard = terminal(node.wildcard, template, input, segments, handler, explicit);
					return;
				} else if (!literal && segment.startsWith("{")) {
					if (node.wildcard != null) {
						throw invalid("Parameter cannot share a node with a wildcard", input);
					}
					if (node.parameter == null) {
						node.parameter = new EndpointTrieBuilder();
					}
					node = node.parameter;
				} else {
					if (node.wildcard != null) {
						throw invalid("Literal cannot share a node with a wildcard", input);
					}
					node = node.literals.computeIfAbsent(segment, key -> new EndpointTrieBuilder());
				}
			}
			if (node.wildcard != null) {
				throw invalid("Route end cannot share a node with a wildcard", input);
			}
			node.terminal = terminal(
				node.terminal, template, input, literal ? new String[0] : segments, handler, explicit
			);
		}

		/** Validates the whole template grammar before any trie node is touched and returns its segments. */
		@Nonnull
		private static String[] parseSegments(@Nonnull String template, @Nonnull String input) {
			if (template.length() > 1 && template.endsWith("/")) {
				throw invalid("Trailing slash is only supported for root", input);
			}
			if (template.indexOf('?') >= 0) {
				throw invalid("Question marks are not supported in templates", input);
			}
			final String[] segments = RoutingPath.splitSegments(template);
			final Set<String> names = CollectionUtils.createHashSet(segments.length);
			boolean afterParameter = false;
			for (int i = 0; i < segments.length; i++) {
				final String segment = segments[i];
				final boolean parameter = segment.startsWith("{") && segment.endsWith("}");
				if (segment.equals("*")) {
					if (i != segments.length - 1) {
						throw invalid("Wildcard must be the final whole segment", input);
					}
				} else if (parameter) {
					final String name = segment.substring(1, segment.length() - 1);
					if (containsReservedCharacter(name)) {
						throw invalid("Invalid parameter segment", input);
					}
					if (!names.add(name)) {
						throw invalid("Repeated parameter name " + name, input);
					}
					afterParameter = true;
				} else if (containsReservedCharacter(segment)) {
					throw invalid("Expected a whole literal, parameter or wildcard segment", input);
				} else if (afterParameter && segment.isEmpty()) {
					throw invalid("Empty segments are not supported after a parameter", input);
				}
			}
			return segments;
		}

		/** Tells whether the text contains a character that has template meaning (`*`, `{` or `}`). */
		private static boolean containsReservedCharacter(@Nonnull String text) {
			return text.indexOf('*') >= 0 || text.indexOf('{') >= 0 || text.indexOf('}') >= 0;
		}

		/**
		 * Returns the route to store at a position. A repeat whose spelling equals the stored template keeps the
		 * stored handler; an explicit repeat of an automatic CONNECT route becomes explicit and still keeps the
		 * automatic entry's handler. Any other registration on an occupied position throws.
		 */
		@Nonnull
		private static EndpointRouteTerminal terminal(
			@Nullable EndpointRouteTerminal previous, @Nonnull String template, @Nonnull String input,
			@Nonnull String[] segments, @Nonnull HttpService handler, boolean explicit
		) {
			if (previous != null) {
				if (!previous.template.equals(input)) {
					throw invalid("Equivalent route already registered as " + previous.template, input);
				}
				return explicit && !previous.explicit ? new EndpointRouteTerminal(
					previous.template, segments, previous.handler, true
				) : previous;
			}
			return new EndpointRouteTerminal(template, segments, handler, explicit);
		}

		/** Produces an independent immutable router; subsequent builder edits cannot affect it. */
		@Nonnull
		public EndpointRoutingService build() {
			return new EndpointRoutingService(this);
		}

		/** Describes a registration conflict or unsupported grammar in the deployment's terms. */
		@Nonnull
		private static GenericEvitaInternalError invalid(@Nonnull String reason, @Nonnull String template) {
			return new GenericEvitaInternalError(reason + ": " + template);
		}
	}

	/**
	 * Mutable construction state which is never retained by a built router.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class EndpointTrieBuilder {
		/** Children by literal segment text, the empty segment included. */
		private final Map<String, EndpointTrieBuilder> literals = CollectionUtils.createHashMap(4);
		/** Child for a parameter segment, shared by every parameter name. */
		@Nullable private EndpointTrieBuilder parameter;
		/** Route ending at this node. */
		@Nullable private EndpointRouteTerminal terminal;
		/** Route whose final `*` starts at this node; then no other route leaves the node. */
		@Nullable private EndpointRouteTerminal wildcard;

		/** Creates construction state for one segment. */
		private EndpointTrieBuilder() {
		}
	}

	/**
	 * Frozen trie node. It holds no parameter names, because routes sharing a parameter node may spell the parameter
	 * differently (`/a/{x}/one` and `/a/{z}/three`); each route's terminal carries its own names.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class EndpointTrieNode {
		/** Children by literal segment text, probed by a region of the request path. */
		private final RegionRoutingTable<EndpointTrieNode> literals;
		/** Child for a parameter segment. */
		@Nullable private final EndpointTrieNode parameter;
		/** Route ending at this node. */
		@Nullable private final EndpointRouteTerminal terminal;
		/** Route whose final `*` starts at this node. */
		@Nullable private final EndpointRouteTerminal wildcard;

		/** Recursively freezes the builder without retaining mutable construction state. */
		private EndpointTrieNode(@Nonnull EndpointTrieBuilder builder) {
			final Map<String, EndpointTrieNode> children = CollectionUtils.createHashMap(builder.literals.size());
			for (final Map.Entry<String, EndpointTrieBuilder> entry : builder.literals.entrySet()) {
				children.put(entry.getKey(), new EndpointTrieNode(entry.getValue()));
			}
			this.literals = new RegionRoutingTable<>(children);
			this.parameter = builder.parameter == null ? null : new EndpointTrieNode(builder.parameter);
			this.terminal = builder.terminal;
			this.wildcard = builder.wildcard;
		}
	}

	/**
	 * One registered route at the node where it ends: its spelling, where its captures sit, its handler, and whether
	 * it was registered explicitly or automatically as the CONNECT twin of a GET WebSocket handler. Its arrays are
	 * never modified after construction.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class EndpointRouteTerminal {
		/** Registered spelling with the leading slash supplied; detects repeats and names the route in conflicts. */
		private final String template;
		/** Declared name of each capture; shared by the request carriers. */
		private final String[] parameterNames;
		/** Segment index of each capture, in template order. */
		private final int[] captureSegments;
		/** Handler serving the matched requests. */
		private final HttpService handler;
		/** False only for the CONNECT route registered automatically for a GET WebSocket handler. */
		private final boolean explicit;

		/** Retains immutable route metadata created exclusively during registration. */
		private EndpointRouteTerminal(
			@Nonnull String template, @Nonnull String[] segments, @Nonnull HttpService handler,
			boolean explicit
		) {
			this.template = template;
			int captures = 0;
			for (final String segment : segments) {
				if (isCapture(segment)) {
					captures++;
				}
			}
			this.parameterNames = new String[captures];
			this.captureSegments = new int[captures];
			int capture = 0;
			for (int i = 0; i < segments.length; i++) {
				final String segment = segments[i];
				if (isCapture(segment)) {
					this.parameterNames[capture] = segment.substring(1, segment.length() - 1);
					this.captureSegments[capture++] = i;
				}
			}
			this.handler = handler;
			this.explicit = explicit;
		}

		/** Tells whether a validated segment captures a named value. */
		private static boolean isCapture(@Nonnull String segment) {
			return segment.startsWith("{");
		}

		/**
		 * Builds the named capture carrier for a path that `find` matched to this route. Segment positions are found by
		 * counting slashes, which is only valid for such a path.
		 *
		 * @param path     encoded path matched to this route
		 * @param pathEnd  exclusive encoded path bound, excluding query and trailing slashes
		 * @param escaped  whether the bounded path contains an escape
		 * @return the captures in template order; null only when a captured segment fails strict decoding, which a
		 * path accepted by `RoutingPath#scan` cannot produce
		 */
		@Nullable
		private RouteParameters captureParameters(@Nonnull String path, int pathEnd, boolean escaped) {
			final String[] values = new String[this.parameterNames.length];
			int start = 1;
			int segment = 0;
			for (int i = 0; i < this.captureSegments.length; i++) {
				while (segment < this.captureSegments[i]) {
					start = (int) (RoutingPath.segment(path, start, pathEnd, false) >>> 1) + 1;
					segment++;
				}
				final long bounds = RoutingPath.segment(path, start, pathEnd, escaped);
				final int end = (int) (bounds >>> 1);
				final String value = RoutingPath.decode(path, start, end, (bounds & 1) != 0);
				if (value == null) {
					return null;
				}
				values[i] = value;
				// step over the slash that ended the captured segment
				start = end + 1;
				segment++;
			}
			return new RouteParameters(this.parameterNames, values);
		}
	}
}
