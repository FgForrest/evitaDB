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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;

/**
 * Represents a GraphQL WebSocket session that manages the state and lifecycle of a single
 * WebSocket connection. This class handles session-specific operations including connection
 * state management, subscription tracking, message routing, and cleanup operations.
 * It maintains the context for all GraphQL operations performed within a single WebSocket connection.
 * TODO LHO REWRITE TO ARMERIA
 *
 * @author Lukáš Hornych, 2023
 */
@RequiredArgsConstructor
public class GraphQLWebSocketSession {

	/*@Nonnull private final WebSocketChannel channel;*/
	@Nonnull private final ObjectMapper objectMapper;

	@Nonnull private GraphQLWebSocketConnectionState state = GraphQLWebSocketConnectionState.NEW;


	private final Map<String, GraphQLWebSocketSubscription> subscriptions = createConcurrentHashMap(5);

	public boolean isNew() {
		return this.state == GraphQLWebSocketConnectionState.NEW;
	}

	public boolean isActive() {
		return this.state == GraphQLWebSocketConnectionState.ACTIVE;
	}

	public void activate() {
		this.state = GraphQLWebSocketConnectionState.ACTIVE;
	}

	public boolean isExistsSubscription(@Nonnull String id) {
		return this.subscriptions.containsKey(id);
	}

	@Nonnull
	public Optional<GraphQLWebSocketSubscription> getSubscription(@Nonnull String id) {
		return Optional.ofNullable(this.subscriptions.get(id));
	}

	public void addSubscription(@Nonnull GraphQLWebSocketSubscription subscription) {
		this.subscriptions.put(subscription.getId(), subscription);
	}

	public void removeSubscription(@Nonnull String id) {
		this.subscriptions.remove(id);
	}

	public void removeSubscription(@Nonnull GraphQLWebSocketSubscription subscription) {
		this.subscriptions.remove(subscription.getId());
	}

	/**
	 * Sends GraphQL WebSocket message to a client.
	 */
	public void sendMessage(@Nonnull GraphQLWebSocketMessage message) {
		final String serializedMessage;
		try {
			serializedMessage = this.objectMapper.writeValueAsString(message);
		} catch (JsonProcessingException e) {
			throw new GraphQLInternalError("Couldn't serialize message.", e);
		}
		/*WebSockets.sendText(serializedMessage, channel, null);*/
	}

	/**
	 * Closes a channel with proper close message
	 */
	public void closeChannel(@Nonnull GraphQLWebSocketCloseMessage closeMessage) {
		/*WebSockets.sendClose(closeMessage, channel, null);*/
	}

}
