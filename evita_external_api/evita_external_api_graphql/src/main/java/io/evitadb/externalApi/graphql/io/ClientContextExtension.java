/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.graphql.io;

import javax.annotation.Nullable;

/**
 * DTO for passing client context information from client for entire GQL request execution.
 *
 * @see io.evitadb.api.ClientContext
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public record ClientContextExtension(@Nullable String clientId,
                                     @Nullable String requestId) {

	static final String CLIENT_CONTEXT_EXTENSION = "clientContext";
	static final String CLIENT_ID = "clientId";
	static final String REQUEST_ID = "requestId";

	/**
	 * Client didn't send any client context information, but we want to still classify the usage somehow.
	 */
	public static ClientContextExtension empty() {
		return new ClientContextExtension(null, null);
	}
}
