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

package io.evitadb.externalApi.graphql.io.webSocket;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import graphql.GraphQL;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import io.evitadb.externalApi.http.RoutableWebSocket;
import io.evitadb.externalApi.http.WebSocketHandler;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main handler for GraphQL WebSocket connections. This class manages the lifecycle of
 * WebSocket connections for GraphQL operations, including connection establishment,
 * message processing, subscription management, and connection termination. It implements
 * the GraphQL WebSocket subprotocol to enable real-time GraphQL subscriptions and queries.
 *
 * @author Lukáš Hornych, 2023
 */
@Slf4j
public class GraphQLWebSocketHandler implements WebSocketHandler, HttpService {

	private static final String GRAPHQL_WS_SUB_PROTOCOL = "graphql-transport-ws";

	@Nonnull
	private final AtomicReference<GraphQL> graphQL;

	@Nullable
	private final HttpService fallbackService;

	public GraphQLWebSocketHandler(
		@Nonnull AtomicReference<GraphQL> graphQL,
		@Nullable HttpService fallbackService
	) {
		this.graphQL = graphQL;
		this.fallbackService = fallbackService;
	}

	@Nonnull
	@Override
	public WebSocket handle(@Nonnull ServiceRequestContext ctx, @Nonnull RoutableWebSocket in) {
		if (!isSubprotocolSupported(ctx, GRAPHQL_WS_SUB_PROTOCOL)) {
			return subprotocolNotSupported();
		}

		final WebSocketWriter outgoing = WebSocket.streaming();
		final GraphQLWSSubProtocol protocol = new GraphQLWSSubProtocol(ctx, this.graphQL);
		in.incomingWebSocket().subscribe(new GraphQLWebSocketSubscriber(protocol, outgoing));
		return outgoing;
	}

	@Override
	@Nonnull
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		// WebSocket connection must be already open at this point, so it should call the handle() method.
		// However, we want to use the GET method via HTTP for other purposes, so we delegate the processing to nested
		// service.
		if (this.fallbackService == null) {
			throw new GraphQLInvalidArgumentException("Only WebSocket connections are supported.");
		}
		return this.fallbackService.serve(ctx, req);
	}
}
