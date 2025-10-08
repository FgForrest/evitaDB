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

package io.evitadb.externalApi.utils;

import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.externalApi.http.RoutableWebSocket;
import io.evitadb.externalApi.http.WebSocketHandler;

import javax.annotation.Nonnull;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class NotFoundWebSocketHandler implements WebSocketHandler {

	@Nonnull
	@Override
	public WebSocket handle(@Nonnull ServiceRequestContext ctx, @Nonnull RoutableWebSocket in) {
		final WebSocketWriter out = WebSocket.streaming();
		// this is not an ideal solution to not finding WebSocket handler, but we don't know that before the
		// WebSocket channel is opened and routed, due to the Armeria limitations of WebSocketService placement
		// needing to be registered as a root service
		out.close(WebSocketCloseStatus.POLICY_VIOLATION, "WebSocket connection is not available for this path.");
		return out;
	}
}
