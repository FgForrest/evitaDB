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

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.GraphQL;
import io.evitadb.core.Evita;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main handler for GraphQL WebSocket connections. This class manages the lifecycle of
 * WebSocket connections for GraphQL operations, including connection establishment,
 * message processing, subscription management, and connection termination. It implements
 * the GraphQL WebSocket subprotocol to enable real-time GraphQL subscriptions and queries.
 *
 * @author Lukáš Hornych, 2023
 */
public class GraphQLWebSocketHandler /*extends WebSocketProtocolHandshakeHandler*/ {

	private static final String GRAPHQL_WS_SUB_PROTOCOL = "graphql-transport-ws";
	private static final Set<String> SUPPORTED_SUB_PROTOCOLS = Set.of(GRAPHQL_WS_SUB_PROTOCOL);
	/*private static final Set<Handshake> SUPPORTED_HANDSHAKES = Set.of(
		new Hybi13Handshake(SUPPORTED_SUB_PROTOCOLS, false),
		new Hybi08Handshake(SUPPORTED_SUB_PROTOCOLS, false),
		new Hybi07Handshake(SUPPORTED_SUB_PROTOCOLS, false)
	);*/

	public GraphQLWebSocketHandler(@Nonnull ObjectMapper objectMapper,
								   @Nonnull Evita evita,
	                               @Nonnull AtomicReference<GraphQL> graphQL) {
		/*super(SUPPORTED_HANDSHAKES, new GraphQLWebSocketSocketHandler(objectMapper, evita, graphQL));*/
	}
}
