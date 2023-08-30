/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.io;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * DTO for passing client context information from client for entire GQL request execution.
 *
 * @see io.evitadb.api.ClientContext
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public record ClientContextExtension(@Nonnull String clientId,
                                     @Nonnull String requestId) {

	public static final String UNKNOWN_CLIENT_ID = "unknownGraphQLClient";

	static final String CLIENT_CONTEXT_EXTENSION = "clientContext";
	static final String CLIENT_ID = "clientId";
	static final String REQUEST_ID = "requestId";

	/**
	 * Client didn't sent any client context information, but we want to still classify the usage somehow.
	 */
	public static ClientContextExtension unknown() {
		return new ClientContextExtension(UNKNOWN_CLIENT_ID, UUID.randomUUID().toString());
	}
}
