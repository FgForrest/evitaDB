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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import graphql.GraphQLError;
import io.evitadb.externalApi.graphql.io.GraphQLResponse;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents a message in the GraphQL WebSocket protocol. This record encapsulates
 * the structure of messages exchanged between client and server in a GraphQL WebSocket
 * connection, including message type, optional ID for tracking, and payload data.
 * It follows the GraphQL WebSocket subprotocol message format specifications.
 *
 * @author Lukáš Hornych, 2023
 */
@JsonInclude(Include.NON_NULL)
public record GraphQLWebSocketMessage(@Nullable String id,
                                      @Nonnull GraphQLWebSocketMessageType type,
                                      @Nullable Object payload) {

	public GraphQLWebSocketMessage {
		Assert.notNull(
			type,
			"Type message cannot be empty."
		);
		if (type.isRequiresId()) {
			Assert.notNull(
				id,
				"Id of message cannot be empty for type `" + type.getValue() + "`."
			);
		}
		if (type.isRequiresPayload()) {
			Assert.notNull(
				payload,
				"Payload of message cannot be empty for type `" + type.getValue() + "`."
			);
		}
	}

	@JsonCreator
	private static GraphQLWebSocketMessage fromJson(@Nullable @JsonProperty("id") String id,
	                                                @Nonnull @JsonProperty("type") String type,
	                                                @Nullable @JsonProperty("payload") Map<String, Object> payload) {
		return new GraphQLWebSocketMessage(id, GraphQLWebSocketMessageType.fromValue(type), payload);
	}

	@JsonGetter("type")
	private String serializedType() {
		return type().getValue();
	}

	/**
	 * Create a {@code "connection_ack"} server message.
	 */
	public static GraphQLWebSocketMessage connectionAck() {
		return new GraphQLWebSocketMessage(null, GraphQLWebSocketMessageType.CONNECTION_ACK, null);
	}

	/**
	 * Create a {@code "next"} server message.
	 *
	 * @param id unique request id
	 * @param response the response
	 */
	public static GraphQLWebSocketMessage next(@Nonnull String id, @Nonnull GraphQLResponse<?> response) {
		Assert.notNull(response, "'responseMap' is required");
		return new GraphQLWebSocketMessage(id, GraphQLWebSocketMessageType.NEXT, response);
	}

	/**
	 * Create an {@code "error"} server message.
	 *
	 * @param id unique request id
	 * @param errors the error to add as the message payload
	 */
	public static GraphQLWebSocketMessage error(@Nonnull String id, @Nonnull GraphQLError... errors) {
		Assert.notNull(errors, "GraphQlError's are required");
		return new GraphQLWebSocketMessage(
			id,
			GraphQLWebSocketMessageType.ERROR,
			Arrays.stream(errors).map(GraphQLError::toSpecification).collect(Collectors.toList())
		);
	}

	/**
	 * Create a {@code "complete"} server message.
	 *
	 * @param id unique request id
	 */
	public static GraphQLWebSocketMessage complete(@Nonnull String id) {
		return new GraphQLWebSocketMessage(id, GraphQLWebSocketMessageType.COMPLETE, null);
	}

	/**
	 * Create a {@code "ping"} client or server message.
	 */
	public static GraphQLWebSocketMessage ping() {
		return new GraphQLWebSocketMessage(null, GraphQLWebSocketMessageType.PING, null);
	}

	/**
	 * Create a {@code "pong"} client or server message.
	 */
	public static GraphQLWebSocketMessage pong() {
		return new GraphQLWebSocketMessage(null, GraphQLWebSocketMessageType.PONG, null);
	}
}
