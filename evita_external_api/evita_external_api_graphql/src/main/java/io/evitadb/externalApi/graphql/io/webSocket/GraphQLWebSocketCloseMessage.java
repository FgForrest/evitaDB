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


import javax.annotation.Nonnull;

/**
 * Represents a close message in the GraphQL WebSocket protocol. This record encapsulates
 * the information needed to properly close a WebSocket connection, including the close
 * code and reason for closure, following the GraphQL WebSocket subprotocol specifications.
 *
 * @author Lukáš Hornych, 2023
 */
class GraphQLWebSocketCloseMessage /*extends CloseMessage*/ {

	private GraphQLWebSocketCloseMessage(int code, @Nonnull String reason) {
		/*super(code, reason);*/
	}

	static GraphQLWebSocketCloseMessage invalidMessage(@Nonnull String reason) {
		return new GraphQLWebSocketCloseMessage(4400, reason);
	}

	static GraphQLWebSocketCloseMessage unauthorized() {
		return new GraphQLWebSocketCloseMessage(4401, "Unauthorized");
	}

	static GraphQLWebSocketCloseMessage forbidden() {
		return new GraphQLWebSocketCloseMessage(4403, "Forbidden");
	}

	static GraphQLWebSocketCloseMessage initTimeout() {
		return new GraphQLWebSocketCloseMessage(4408, "Connection initialisation timeout");
	}

	static GraphQLWebSocketCloseMessage subscriberAlreadyExists() {
		return new GraphQLWebSocketCloseMessage(4409, "Subscriber already exists");
	}

	static GraphQLWebSocketCloseMessage tooManyInitRequests() {
		return new GraphQLWebSocketCloseMessage(4429, "Too many initialisation requests");
	}

	static GraphQLWebSocketCloseMessage internalServerError() {
		return new GraphQLWebSocketCloseMessage(4500, "Internal server error");
	}
}
