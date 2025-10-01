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

package io.evitadb.externalApi.rest.io.webSocket;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.RoutableWebSocket;
import io.evitadb.externalApi.http.WebSocketHandler;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.io.RestEndpointHandler;
import io.evitadb.externalApi.rest.io.RestHandlingContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Main handler for REST WebSocket connections. This class manages the lifecycle of
 * WebSocket connections for REST API operations, including connection establishment,
 * message processing, subscription management, and connection termination. It implements
 * custom [WebSocket subprotocol]() to enable real-time subscriptions.
 *
 * @author Lukáš Hornych, 2023
 */
@Slf4j
public class RestWebSocketHandler<CTX extends RestHandlingContext>
	extends RestEndpointHandler<CTX>
	implements WebSocketHandler {

	private static final String REST_WS_SUB_PROTOCOL = "rest-transport-ws";

	@Nonnull
	private final RestWebSocketExecutor restWebSocketExecutor;
	@Nullable
	private final RestEndpointHandler<CTX> fallbackService;

	public RestWebSocketHandler(
		@Nonnull CTX restHandlingContext,
		@Nonnull RestWebSocketExecutor restWebSocketExecutor,
		@Nullable RestEndpointHandler<CTX> fallbackService
	) {
		super(restHandlingContext);
		this.restWebSocketExecutor = restWebSocketExecutor;
		this.fallbackService = fallbackService;
	}

	@Nonnull
	@Override
	public WebSocket handle(@Nonnull ServiceRequestContext ctx, @Nonnull RoutableWebSocket in) {
		if (!isSubprotocolSupported(ctx, REST_WS_SUB_PROTOCOL)) {
			return subprotocolNotSupported();
		}

		final WebSocketWriter outgoing = WebSocket.streaming();
		final RestWSSubProtocol protocol = new RestWSSubProtocol(ctx, this.restWebSocketExecutor);
		in.incomingWebSocket().subscribe(new RestWebSocketSubscriber(protocol, outgoing));
		return outgoing;
	}

//	@Override
//	@Nonnull
//	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
//		// WebSocket connection must be already open at this point, so it should call the handle() method.
//		// However, we want to use the GET method via HTTP for other purposes, so we delegate the processing to nested
//		// service.
//		if (this.fallbackService == null) {
//			// todo lho better exception
//			throw new RestInvalidArgumentException("Only WebSocket connections are supported.");
//		}
//		return this.fallbackService.serve(ctx, req);
//	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(HttpMethod.GET, HttpMethod.CONNECT);
	}

	@Nonnull
	@Override
	protected CompletableFuture<EndpointResponse> doHandleRequest(
		@Nonnull RestEndpointExecutionContext executionContext
	) {
		// todo lho what with this
		throw new RestInvalidArgumentException("Only WebSocket connections are supported.");
	}
}
