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

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.externalApi.http.RoutableWebSocket;
import io.evitadb.externalApi.http.WebSocketHandler;
import io.evitadb.externalApi.utils.NotFoundService;
import io.evitadb.externalApi.utils.NotFoundWebSocketHandler;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Path-prefix dispatcher of the external APIs. It selects a handler by a literal path and hands it the rest of the
 * path, so that the {@link EndpointRoutingService} below it matches only that remainder.
 *
 * ## Matching
 *
 * An exact path wins first, then the longest registered prefix ending on a segment boundary of the request path
 * (`/foo` matches `/foo` and `/foo/bar`, never `/foobar`), then the `/` prefix as the default. No match answers HTTP
 * with the standard 404 and a WebSocket with a policy-violation close. This router is method-independent and never
 * answers 405; endpoint routers do.
 *
 * ## Registration
 *
 * Paths are literal, never templates: braces and stars have no special meaning. The service normalizes every path:
 * it supplies a missing leading slash and removes trailing slashes except for the root, while interior empty
 * segments and letter case stay significant. A null or empty path throws
 * {@link io.evitadb.exception.GenericEvitaInternalError} and a null handler throws `NullPointerException`.
 *
 * ## Remainder
 *
 * HTTP and WebSocket input is encoded. Matching excludes the real query and trims trailing slashes by bounds.
 * Literal segments are decoded once for matching; consumed boundaries always refer to raw indices.
 * The selected handler receives the remainder as HTTP `:path` or as the WebSocket path: empty after an exact or
 * equal match, and the whole path for the `/` default, which consumes nothing. Rewriting `:path` drops its query,
 * while the context keeps the original query. Malformed escapes and invalid UTF-8 in matched segments answer 404.
 * The WebSocket capability is checked on the registered object after selection, without unwrapping it and without
 * retrying a shorter prefix.
 *
 * ## Threading
 *
 * Any thread may register, replace or remove bindings. A writer takes the lock, copies the current table, applies
 * its change and publishes one complete immutable table through a volatile field; unchanged paths keep their handler
 * objects. Every dispatch reads that field once, so readers take no lock and a request in flight keeps the bindings
 * it started with. Handlers must be thread-safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
public final class PrefixRoutingService implements HttpService, WebSocketHandler {
	/** Serializes writers so that no two changes derive from the same table; readers never take it. */
	private final ReentrantLock writerLock = new ReentrantLock();
	/** Answers an unmatched HTTP request with the standard 404 body. */
	private final HttpService notFound = new NotFoundService();
	/**
	 * Closes a WebSocket with a policy violation when no binding matches or the bound object is not a
	 * {@link WebSocketHandler}; the type is checked on the registered object, which is never unwrapped.
	 */
	private final WebSocketHandler webSocketNotFound = new NotFoundWebSocketHandler();
	/** The published bindings: replaced as a whole by writers and read once per dispatch. */
	private volatile PrefixRoutingTable table = new PrefixRoutingTable(Map.of(), Map.of());

	/** Creates an empty dispatcher. */
	public PrefixRoutingService() {
	}

	/**
	 * Registers a prefix, or replaces the handler of an equal one; an alias of {@link #replacePrefixPath}.
	 *
	 * @param path    literal prefix, normalized by the service; `/` registers the default, which consumes nothing
	 * @param handler thread-safe handler receiving the rest of the path
	 * @return this router
	 * @throws NullPointerException                           when the handler is null, checked before the path
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the path is null or empty
	 */
	@Nonnull
	public PrefixRoutingService addPrefixPath(@Nonnull String path, @Nonnull HttpService handler) {
		return replacePrefixPath(path, handler);
	}

	/**
	 * Registers a prefix, or replaces the handler of an equal one, publishing the change in one step.
	 *
	 * @param path    literal prefix, normalized by the service; `/` registers the default, which consumes nothing
	 * @param handler thread-safe handler receiving the rest of the path
	 * @return this router
	 * @throws NullPointerException                           when the handler is null, checked before the path
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the path is null or empty
	 */
	@Nonnull
	public PrefixRoutingService replacePrefixPath(@Nonnull String path, @Nonnull HttpService handler) {
		Objects.requireNonNull(handler, "Handler must be specified.");
		return change(path, handler, false);
	}

	/**
	 * Registers an exact path, or replaces the handler of an equal one. An exact path matches only the whole request
	 * path, wins over every prefix and hands its handler an empty remainder.
	 *
	 * @param path    literal path, normalized by the service
	 * @param handler thread-safe handler of the path
	 * @return this router
	 * @throws NullPointerException                           when the handler is null, checked before the path
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the path is null or empty
	 */
	@Nonnull
	public PrefixRoutingService addExactPath(@Nonnull String path, @Nonnull HttpService handler) {
		Objects.requireNonNull(handler, "Handler must be specified.");
		return change(path, handler, true);
	}

	/**
	 * Removes a prefix, or the default for `/`. An unknown prefix is ignored, and an exact path of the same spelling
	 * stays registered: this service offers no removal of exact paths.
	 *
	 * @param path literal prefix, normalized by the service
	 * @return this router
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the path is null or empty
	 */
	@Nonnull
	public PrefixRoutingService removePrefixPath(@Nonnull String path) {
		return change(path, null, false);
	}

	/** Copies under the writer lock and publishes only after the entire replacement is ready. */
	@Nonnull
	private PrefixRoutingService change(@Nonnull String path, @Nullable HttpService handler, boolean exact) {
		Assert.isPremiseValid(path != null && !path.isEmpty(), "Path must be specified.");
		final String normalized = RoutingPath.registration(path);
		this.writerLock.lock();
		try {
			final PrefixRoutingTable current = this.table;
			final Map<String, PrefixRouteBinding> prefixes = CollectionUtils.createHashMap(current.size + 1);
			final Map<String, PrefixRouteBinding> exactPaths = CollectionUtils.createHashMap(current.size + 1);
			current.prefixes.copyTo(prefixes);
			current.exactPaths.copyTo(exactPaths);
			final Map<String, PrefixRouteBinding> changed = exact ? exactPaths : prefixes;
			if (handler == null) {
				changed.remove(normalized);
			} else {
				final int consumed = !exact && normalized.equals("/") ? 0 : normalized.length();
				changed.put(
					normalized,
					new PrefixRouteBinding(handler, consumed, RoutingPath.splitSegments(normalized).length)
				);
			}
			this.table = new PrefixRoutingTable(exactPaths, prefixes);
			return this;
		} finally {
			this.writerLock.unlock();
		}
	}

	/** Dispatches HTTP with the encoded remainder as `:path`, preserving the original context. */
	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		final PrefixRoutingTable current = this.table;
		final String target = req.path();
		final String path = target.isEmpty() ? "/" : target;
		final long scanned = RoutingPath.scan(path);
		final int end = (int) (scanned >>> 1);
		final boolean escaped = (scanned & 1) != 0;
		final PrefixRouteBinding binding = path.charAt(0) == '/' && scanned >= 0 ?
			current.lookup(path, end, escaped) : null;
		if (binding != null) {
			return binding.handler.serve(
				ctx, req.withHeaders(req.headers().toBuilder()
					.path(path.substring(consumed(path, end, binding, escaped), end)).build())
			);
		}
		return this.notFound.serve(ctx, req);
	}

	/** Dispatches WebSockets using the same precedence and remainder rules as HTTP. */
	@Nonnull
	@Override
	public WebSocket handle(@Nonnull ServiceRequestContext ctx, @Nonnull RoutableWebSocket in) {
		final PrefixRoutingTable current = this.table;
		final String target = in.path();
		final String path = target.isEmpty() ? "/" : target;
		final long scanned = RoutingPath.scan(path);
		final int end = (int) (scanned >>> 1);
		final boolean escaped = (scanned & 1) != 0;
		final PrefixRouteBinding binding = path.charAt(0) == '/' && scanned >= 0 ?
			current.lookup(path, end, escaped) : null;
		return binding != null && binding.handler instanceof WebSocketHandler socketHandler ?
			socketHandler.handle(ctx, in.withPath(path.substring(consumed(path, end, binding, escaped), end))) :
			this.webSocketNotFound.handle(ctx, in);
	}

	/** Computes the raw boundary for an escaped match; ordinary targets retain the precomputed boundary. */
	private static int consumed(
		@Nonnull String path, int end, @Nonnull PrefixRouteBinding binding, boolean escaped
	) {
		if (binding.consumed == 0 || !escaped) {
			return binding.consumed;
		}
		if (binding.segments == 0) {
			return end;
		}
		int position = 1;
		for (int segment = 0; segment < binding.segments; segment++) {
			while (position < end && path.charAt(position) != '/') {
				position++;
			}
			if (segment + 1 < binding.segments) {
				position++;
			}
		}
		return position;
	}

	/**
	 * One complete set of bindings, published by a writer as a unit and never changed afterwards, so a dispatch that
	 * read it once sees a consistent set for the whole request.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class PrefixRoutingTable {
		/** Exact paths, consulted before any prefix. */
		private final RegionRoutingTable<PrefixRouteBinding> exactPaths;
		/** Prefixes, the `/` default included. */
		private final RegionRoutingTable<PrefixRouteBinding> prefixes;
		/** Distinct prefix lengths without `/`, longest first; `lookup` probes only these, longest match first. */
		private final int[] lengths;
		/** The `/` binding, answering when no other prefix matches; null when no default is registered. */
		@Nullable private final PrefixRouteBinding defaultHandler;
		/** Number of exact and prefix bindings together, sizing the maps a writer copies them into. */
		private final int size;
		/** Segment lookup used only for targets containing percent escapes. */
		private final EncodedPrefixNode encoded;

		/** Compiles independent tables and descending distinct prefix lengths. */
		private PrefixRoutingTable(
			@Nonnull Map<String, PrefixRouteBinding> exact, @Nonnull Map<String, PrefixRouteBinding> prefixes
		) {
			this.exactPaths = new RegionRoutingTable<>(exact);
			this.prefixes = new RegionRoutingTable<>(prefixes);
			this.defaultHandler = prefixes.get("/");
			this.size = exact.size() + prefixes.size();
			this.lengths = distinctDescendingLengths(prefixes);
			final EncodedPrefixBuilder builder = new EncodedPrefixBuilder();
			for (final Map.Entry<String, PrefixRouteBinding> entry : exact.entrySet()) {
				builder.add(entry.getKey(), entry.getValue(), true);
			}
			for (final Map.Entry<String, PrefixRouteBinding> entry : prefixes.entrySet()) {
				if (!entry.getKey().equals("/")) {
					builder.add(entry.getKey(), entry.getValue(), false);
				}
			}
			this.encoded = new EncodedPrefixNode(builder);
		}

		/**
		 * Returns the distinct lengths of every prefix except `/`, longest first, so that `lookup` tries the longest
		 * segment boundary first and probes each length only once.
		 */
		@Nonnull
		private static int[] distinctDescendingLengths(@Nonnull Map<String, PrefixRouteBinding> prefixes) {
			final int[] allLengths = new int[prefixes.size()];
			int count = 0;
			for (final String prefix : prefixes.keySet()) {
				if (!prefix.equals("/")) {
					allLengths[count++] = prefix.length();
				}
			}
			Arrays.sort(allLengths, 0, count);
			final int[] distinct = new int[count];
			int index = 0;
			// walk the ascending sort from its end and keep the first of each run of equal lengths
			for (int i = count - 1; i >= 0; i--) {
				if (i == count - 1 || allLengths[i] != allLengths[i + 1]) {
					distinct[index++] = allLengths[i];
				}
			}
			return Arrays.copyOf(distinct, index);
		}

		/** Returns a precompiled binding carrying its consumed length; never allocates or re-probes a matched key. */
		@Nullable
		private PrefixRouteBinding lookup(@Nonnull String path, int end, boolean escaped) {
			if (escaped) {
				return this.encoded.lookup(path, end, this.defaultHandler);
			}
			final PrefixRouteBinding exact = this.exactPaths.get(path, 0, end);
			if (exact != null) {
				return exact;
			}
			for (final int length : this.lengths) {
				if (length <= end && (length == end || path.charAt(length) == '/')) {
					final PrefixRouteBinding prefix = this.prefixes.get(path, 0, length);
					if (prefix != null) {
						return prefix;
					}
				}
			}
			return this.defaultHandler;
		}
	}

	/**
	 * Registration-only segment tree for escaped prefix lookups.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class EncodedPrefixBuilder {
		/** Child nodes by decoded segment text, the empty segment included. */
		private final Map<String, EncodedPrefixBuilder> children = CollectionUtils.createHashMap(0);
		/** Binding of the exact path ending at this node; null when none ends here. */
		@Nullable private PrefixRouteBinding exact;
		/** Binding of the prefix ending at this node; null when none ends here. */
		@Nullable private PrefixRouteBinding prefix;

		/** Creates an empty registration node. */
		private EncodedPrefixBuilder() {
		}

		/** Adds decoded registration segments without applying request-target syntax. */
		private void add(@Nonnull String path, @Nonnull PrefixRouteBinding binding, boolean exact) {
			EncodedPrefixBuilder node = this;
			for (final String segment : RoutingPath.splitSegments(path)) {
				node = node.children.computeIfAbsent(segment, ignored -> new EncodedPrefixBuilder());
			}
			if (exact) {
				node.exact = binding;
			} else {
				node.prefix = binding;
			}
		}
	}

	/**
	 * Immutable decoded-segment tree that remembers exact and longest-prefix matches without a match allocation.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class EncodedPrefixNode {
		/** Child nodes by decoded segment text, probed by a region of the request path. */
		private final RegionRoutingTable<EncodedPrefixNode> children;
		/** Binding of the exact path ending at this node; null when none ends here. */
		@Nullable private final PrefixRouteBinding exact;
		/** Binding of the prefix ending at this node; null when none ends here. */
		@Nullable private final PrefixRouteBinding prefix;

		/** Freezes registration state before publication. */
		private EncodedPrefixNode(@Nonnull EncodedPrefixBuilder builder) {
			final Map<String, EncodedPrefixNode> children = CollectionUtils.createHashMap(builder.children.size());
			for (final Map.Entry<String, EncodedPrefixBuilder> entry : builder.children.entrySet()) {
				children.put(entry.getKey(), new EncodedPrefixNode(entry.getValue()));
			}
			this.children = new RegionRoutingTable<>(children);
			this.exact = builder.exact;
			this.prefix = builder.prefix;
		}

		/** Walks raw slash boundaries, decoding only escaped segments; exact wins over the deepest prefix. */
		@Nullable
		private PrefixRouteBinding lookup(
			@Nonnull String path, int end, @Nullable PrefixRouteBinding fallback
		) {
			EncodedPrefixNode node = this;
			PrefixRouteBinding best = fallback;
			int start = 1;
			while (start < end) {
				final long bounds = RoutingPath.segment(path, start, end, true);
				final int segmentEnd = (int) (bounds >>> 1);
				node = (bounds & 1) != 0 ? node.children.getEscaped(path, start, segmentEnd) :
					node.children.get(path, start, segmentEnd - start);
				if (node == null) {
					return best;
				}
				if (node.prefix != null) {
					best = node.prefix;
				}
				start = segmentEnd + 1;
			}
			return node.exact != null ? node.exact : best;
		}
	}

	/**
	 * Handler with the number of path characters it consumes, compiled by a writer so that one lookup returns both
	 * without allocating or probing twice.
	 *
	 * @param handler  handler of the registered path
	 * @param consumed number of leading path characters stripped before delegating: the normalized path length, or 0
	 *                 for the `/` default
	 * @param segments number of registered path segments, used to recover escaped raw boundaries
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record PrefixRouteBinding(@Nonnull HttpService handler, int consumed, int segments) {
	}
}
