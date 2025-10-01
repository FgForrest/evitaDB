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

package io.evitadb.externalApi.http;

import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.WebSocketProtocolHandler;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.server.websocket.WebSocketServiceHandler;

import javax.annotation.Nonnull;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class WebSocketEnablingService implements WebSocketService, WebSocketServiceHandler {

	@Nonnull private final WebSocketHandler webSocketTrafficHandler;

	@Nonnull private final WebSocketService webSocketResolver;

	public WebSocketEnablingService(
		@Nonnull HttpService httpTrafficHandler,
		@Nonnull WebSocketHandler webSocketTrafficHandler
	) {
		this.webSocketTrafficHandler = webSocketTrafficHandler;

		this.webSocketResolver = WebSocketService.builder(this)
	         .subprotocols("*") // todo lho
	         .fallbackService(httpTrafficHandler) // handles non-websocket traffic, just a pass-through basically
	         .build();
	}

	@Nonnull
	@Override
	public WebSocket serve(@Nonnull ServiceRequestContext ctx, @Nonnull WebSocket in) throws Exception {
		// Handles the web socket if connection upgrade was successful, by delegating it to the handle() method
		return this.webSocketResolver.serve(ctx, in);
	}

	@Nonnull
	@Override
	public WebSocket handle(@Nonnull ServiceRequestContext ctx, @Nonnull WebSocket in) {
		// delegate the actual websocket to the defined handler
		return this.webSocketTrafficHandler.handle(ctx, new RoutableWebSocket(in, ctx.uri().getPath()));
	}

	@Nonnull
	@Override
	public WebSocketProtocolHandler protocolHandler() {
		return this.webSocketResolver.protocolHandler();
	}
}
