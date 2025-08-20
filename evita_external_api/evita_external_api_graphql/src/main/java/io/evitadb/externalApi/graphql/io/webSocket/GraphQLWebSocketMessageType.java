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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Enumeration of message types used in the GraphQL WebSocket protocol. This enum defines
 * all the standard message types that can be exchanged between client and server during
 * a GraphQL WebSocket session, including connection lifecycle messages, subscription
 * management, and data transmission messages as specified by the GraphQL WebSocket subprotocol.
 *
 * Based on <a href="https://github.com/spring-projects/spring-graphql/blob/main/spring-graphql/src/main/java/org/springframework/graphql/server/support/GraphQlWebSocketMessageType.java">Spring GraphQL library</a>.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public enum GraphQLWebSocketMessageType {

	CONNECTION_INIT("connection_init", false, false),
	CONNECTION_ACK("connection_ack", false, false),
	PING("ping", false, false),
	PONG("pong", false, false),
	SUBSCRIBE("subscribe", true, true),
	NEXT("next", true, true),
	ERROR("error", true, true),
	COMPLETE("complete", true, false),
	INVALID_MESSAGE("invalid_message", false, false);

	@Getter private final String value;
	@Getter private final boolean requiresId;
	@Getter private final boolean requiresPayload;

	@Nonnull
	public static GraphQLWebSocketMessageType fromValue(@Nonnull String value) {
		for (GraphQLWebSocketMessageType type : GraphQLWebSocketMessageType.values()) {
			if (type.getValue().equals(value)) {
				return type;
			}
		}
		throw new IllegalArgumentException("Unknown message type `" + value + "`.");
	}

}
