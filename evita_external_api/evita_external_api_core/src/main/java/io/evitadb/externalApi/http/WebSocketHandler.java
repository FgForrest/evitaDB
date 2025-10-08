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

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.internal.shaded.guava.base.Splitter;
import com.linecorp.armeria.server.ServiceRequestContext;

import javax.annotation.Nonnull;

/**
 * TODO lho docs
 * Loose extension of the {@link com.linecorp.armeria.server.HttpService} but can be used as standalone unit.
 *
 * Version of the {@link com.linecorp.armeria.server.websocket.WebSocketServiceHandler} with routing
 * capabilities.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface WebSocketHandler {

	String SUB_PROTOCOL_WILDCARD = "*";
	Splitter SUBPROTOCOL_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

	/**
	 * Handles the incoming {@link RoutableWebSocket} and returns {@link WebSocket} created via
	 * {@link WebSocket#streaming()} to send {@link WebSocketFrame}s.
	 */
	@Nonnull
	WebSocket handle(@Nonnull ServiceRequestContext ctx, @Nonnull RoutableWebSocket in);

	default boolean isSubprotocolSupported(@Nonnull ServiceRequestContext ctx, @Nonnull String subprotocol) {
		final String subprotocols = ctx.request().headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "");
		return SUBPROTOCOL_SPLITTER.splitToStream(subprotocols)
		                           .anyMatch(sub -> SUB_PROTOCOL_WILDCARD.equals(sub) || subprotocol.equals(sub));
	}

	@Nonnull
	default WebSocket subprotocolNotSupported() {
		final WebSocketWriter out = WebSocket.streaming();
		// this is not an ideal solution to not accepting WebSocket connection due to invalid subprotocol, but we don't
		// know that before the WebSocket channel is opened and routed due to the Armeria limitations of WebSocketService
		// placement needing to be registered as a root service
		out.close(WebSocketCloseStatus.PROTOCOL_ERROR, "Subprotocol not supported.");
		return out;
	}
}
