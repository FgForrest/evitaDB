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
import com.linecorp.armeria.common.websocket.*;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.Route;
import io.evitadb.externalApi.http.RoutableWebSocket;
import io.evitadb.externalApi.http.WebSocketHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Real Armeria fixtures that retain selected handler identity, rewritten requests and context metadata.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class RoutingTestSupport {
	private static final ThreadLocal<ProbeService> SELECTED = new ThreadLocal<>();

	/** Prevents utility instantiation. */
	private RoutingTestSupport() {
	}


	/** Supplies the pseudo-headers installed by a real server before a request reaches routing. */
	@Nonnull
	static HttpRequest request(@Nonnull HttpMethod method, @Nonnull String path) {
		final HttpRequest request = HttpRequest.of(method, path);
		return request.withHeaders(request.headers().toBuilder().scheme("http").authority("localhost").build());
	}

	/** Invokes a router with an ordinary real request and context. */
	@Nonnull
	static RoutingResult call(@Nonnull HttpService service, @Nonnull HttpMethod method, @Nonnull String path)
		throws Exception {
		final HttpRequest request = request(method, path);
		return call(service, ServiceRequestContext.builder(request).route(Route.ofCatchAll()).build(), request);
	}

	/** Invokes with independently supplied context so empty rewritten targets remain testable. */
	@Nonnull
	static RoutingResult call(
		@Nonnull HttpService service, @Nonnull ServiceRequestContext context, @Nonnull HttpRequest request
	) throws Exception {
		SELECTED.remove();
		try {
			final AggregatedHttpResponse response = service.serve(context, request).aggregate()
				.get(30, TimeUnit.SECONDS);
			final ProbeService selected = SELECTED.get();
			return new RoutingResult(response, selected, selected == null ? null : selected.request, context.query());
		} finally {
			SELECTED.remove();
		}
	}

	/** Reads the route carrier into an assertion-friendly map without inspecting request headers. */
	@Nonnull
	static java.util.Map<String, String> parameters(@Nonnull RoutingResult result) {
		final java.util.Map<String, String> values = io.evitadb.utils.CollectionUtils.createLinkedHashMap(4);
		final RouteParameters parameters = result.handler().context.attr(RouteParameters.KEY);
		if (parameters != null) {
			for (int i = 0; i < parameters.size(); i++) {
				values.put(parameters.nameAt(i), parameters.valueAt(i));
			}
		}
		return values;
	}

	/** Asserts the WebSocket fallback: the stream closes with a policy violation naming the unavailable path. */
	static void assertPolicyViolation(@Nonnull WebSocket socket) throws Exception {
		final List<WebSocketFrame> frames = socket.collect().get(30, TimeUnit.SECONDS);
		assertFalse(frames.isEmpty(), "The fallback must emit a close frame");
		final CloseWebSocketFrame close = assertInstanceOf(CloseWebSocketFrame.class, frames.getFirst());
		assertEquals(WebSocketCloseStatus.POLICY_VIOLATION, close.status());
		assertEquals("WebSocket connection is not available for this path.", close.reasonPhrase());
	}

	/**
	 * Observed HTTP dispatch, including the actual registered service object.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	record RoutingResult(
		@Nonnull AggregatedHttpResponse response, @Nullable ProbeService handler,
		@Nullable HttpRequest request, @Nullable String contextQuery
	) {
	}

	/**
	 * Distinguishable HTTP service; no mocks or generated proxies are involved.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	static class ProbeService implements HttpService {
		private final String identity;
		HttpRequest request;
		ServiceRequestContext context;

		/** Names a handler so differential failures identify its registration. */
		ProbeService(@Nonnull String identity) {
			this.identity = identity;
		}

		/** Records exactly what reached the selected handler. */
		@Nonnull
		@Override
		public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) {
			SELECTED.set(this);
			this.request = req;
			this.context = ctx;
			return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, this.identity);
		}
	}

	/**
	 * Dual-capability service whose WebSocket output and input identity can be asserted directly.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	static final class SocketProbeService extends ProbeService implements WebSocketHandler {
		final WebSocketWriter output = WebSocket.streaming();
		RoutableWebSocket incoming;

		/** Creates a real closed output stream. */
		SocketProbeService(@Nonnull String identity) {
			super(identity);
			this.output.close();
		}

		/** Records the unchanged endpoint input and returns a distinguishable stream. */
		@Nonnull
		@Override
		public WebSocket handle(@Nonnull ServiceRequestContext ctx, @Nonnull RoutableWebSocket in) {
			this.context = ctx;
			this.incoming = in;
			return this.output;
		}
	}
}
